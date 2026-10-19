param([switch]$TestsOnly)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$sdk = Join-Path $project '.tools\sdk'
$androidJar = Join-Path $sdk 'platforms\android-37.1\android.jar'
$buildTools = Join-Path $sdk 'build-tools\37.0.0'
Set-Location $project
function Run([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "Build failed: $exe (exit $LASTEXITCODE)" }
}
New-Item -ItemType Directory -Force -Path anibrave\compiled\app-classes,anibrave\compiled\test-classes,anibrave\compiled\patches,anibrave\compiled\dex,anibrave\downloads,anibrave\tools,anibrave\signing,output\anibrave | Out-Null
Run 'node' @('--test','anibrave/tests/playback.test.cjs','anibrave/tests/player.test.cjs','anibrave/tests/tracking.test.cjs')
Run 'javac' @('-d','anibrave/compiled/test-classes','anibrave/src/SiteKey.java','anibrave/tests/SiteKeyTest.java')
Run 'java' @('-cp','anibrave/compiled/test-classes','app.anibrave.SiteKeyTest')
Run 'javac' @('-d','anibrave/compiled/test-classes','anibrave/src/SiteKey.java','anibrave/src/ResumeLocation.java','anibrave/tests/ResumeLocationTest.java')
Run 'java' @('-cp','anibrave/compiled/test-classes','app.anibrave.ResumeLocationTest')
Run 'javac' @('-d','anibrave/compiled/test-classes','anibrave/src/EngineHttp.java','anibrave/tests/EngineHttpTest.java')
Run 'java' @('-cp','anibrave/compiled/test-classes','app.anibrave.EngineHttpTest')
if ($TestsOnly) { return }
if (!(Test-Path -LiteralPath $androidJar)) { throw 'Android SDK 37.1 is required in .tools/sdk' }
$apk = 'anibrave/downloads/Bravearm64Universal.apk'
if (!(Test-Path -LiteralPath $apk)) {
    Run 'gh' @('release','download','v1.97.56','--repo','brave/brave-browser','--pattern','Bravearm64Universal.apk','--dir','anibrave/downloads')
}
if ((Get-FileHash $apk).Hash -ne '9B02840FB94DC172D3BF0073CE6877372BEA0E311BABA7952E4A0563DF88AE89') { throw 'Upstream Brave APK checksum mismatch' }
$jars = @{
    'apktool.jar'='https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar'
    'dexlib2.jar'='https://repo.maven.apache.org/maven2/org/smali/dexlib2/2.5.2/dexlib2-2.5.2.jar'
    'smali.jar'='https://repo.maven.apache.org/maven2/org/smali/smali/2.5.2/smali-2.5.2.jar'
    'smali-util.jar'='https://repo.maven.apache.org/maven2/org/smali/util/2.5.2/util-2.5.2.jar'
    'guava.jar'='https://repo.maven.apache.org/maven2/com/google/guava/guava/27.1-android/guava-27.1-android.jar'
    'antlr-runtime.jar'='https://repo.maven.apache.org/maven2/org/antlr/antlr-runtime/3.5.2/antlr-runtime-3.5.2.jar'
    'antlr.jar'='https://repo.maven.apache.org/maven2/org/antlr/antlr/3.5.2/antlr-3.5.2.jar'
    'jcommander.jar'='https://repo.maven.apache.org/maven2/com/beust/jcommander/1.64/jcommander-1.64.jar'
}
foreach ($jar in $jars.GetEnumerator()) {
    $path = Join-Path 'anibrave/tools' $jar.Key
    if (!(Test-Path -LiteralPath $path)) { Invoke-WebRequest -Uri $jar.Value -OutFile $path }
}
$lock = Get-Content anibrave/tools/tool-checksums.json -Raw | ConvertFrom-Json
foreach ($entry in $lock.PSObject.Properties) {
    if ((Get-FileHash ('anibrave/tools/'+$entry.Name)).Hash.ToLowerInvariant() -ne $entry.Value) { throw "Tool checksum mismatch: $($entry.Name)" }
}
$tool = 'anibrave/tools/apktool.jar'
Run 'java' @('-jar',$tool,'install-framework',$androidJar,'-p','anibrave/tools/framework')
if (!(Test-Path anibrave/decoded/smali_classes5/ukf.smali)) { Run 'java' @('-jar',$tool,'decode',$apk,'--output','anibrave/decoded','--no-res','--jobs','4','-p','anibrave/tools/framework') }
if (!(Test-Path anibrave/resources/AndroidManifest.xml)) { Run 'java' @('-jar',$tool,'decode',$apk,'--output','anibrave/resources','--no-src','--jobs','4','-p','anibrave/tools/framework') }
Run 'python' @('anibrave/tools/prepare.py')
$javaSources = @(Get-ChildItem anibrave/src/*.java | ForEach-Object FullName)
Run 'javac' (@('-encoding','UTF-8','--release','17','-cp',$androidJar,'-d','anibrave/compiled/app-classes')+$javaSources)
Run 'jar' @('--create','--file','anibrave/compiled/anibrave.jar','-C','anibrave/compiled/app-classes','.')
Run (Join-Path $buildTools 'd8.bat') @('--min-api','29','--lib',$androidJar,'--output','anibrave/compiled','anibrave/compiled/anibrave.jar')
$mergeCp = 'anibrave/compiled;anibrave/tools/dexlib2.jar;anibrave/tools/guava.jar'
Run 'javac' @('-cp','anibrave/tools/dexlib2.jar;anibrave/tools/guava.jar','-d','anibrave/compiled','anibrave/tools/MergeDex.java')
$smaliCp = 'anibrave/tools/smali.jar;anibrave/tools/dexlib2.jar;anibrave/tools/smali-util.jar;anibrave/tools/guava.jar;anibrave/tools/antlr-runtime.jar;anibrave/tools/antlr.jar;anibrave/tools/jcommander.jar'
Run 'java' @('-cp',$smaliCp,'org.jf.smali.Main','assemble','--api','29','--output','anibrave/compiled/hooks.dex','anibrave/compiled/patches')
foreach ($name in @('classes.dex','classes2.dex','classes3.dex','classes4.dex','classes5.dex','classes6.dex')) {
    $mergeArgs = @('-Xmx2g','-cp',$mergeCp,'MergeDex',('anibrave/resources/'+$name),('anibrave/compiled/dex/'+$name))
    if ($name -eq 'classes5.dex') { $mergeArgs += 'anibrave/compiled/hooks.dex' }
    Run 'java' $mergeArgs
}
Run 'java' @('-Xmx2g','-cp',$mergeCp,'MergeDex','anibrave/compiled/hooks.dex','anibrave/compiled/dex/classes7.dex')
Copy-Item -LiteralPath anibrave/compiled/classes.dex -Destination anibrave/compiled/dex/classes8.dex
Run 'java' @('-jar',$tool,'build','anibrave/resources','--output','anibrave/compiled/resources.apk','--jobs','4','-p','anibrave/tools/framework')
Run 'python' @('anibrave/tools/package.py')
$key = $env:ANIBRAVE_KEYSTORE
if ([string]::IsNullOrWhiteSpace($key) -or !(Test-Path -LiteralPath $key)) {
    throw 'Set ANIBRAVE_KEYSTORE to an existing private signing keystore. No key is generated automatically.'
}
if ([string]::IsNullOrWhiteSpace($env:ANIBRAVE_KS_PASS)) {
    throw 'Set ANIBRAVE_KS_PASS privately before signing.'
}
if ([string]::IsNullOrWhiteSpace($env:ANIBRAVE_KEY_PASS)) { $env:ANIBRAVE_KEY_PASS = $env:ANIBRAVE_KS_PASS }
$alias = if ($env:ANIBRAVE_KEY_ALIAS) { $env:ANIBRAVE_KEY_ALIAS } else { 'anibrave' }
Run (Join-Path $buildTools 'zipalign.exe') @('-f','-P','16','4','anibrave/compiled/AniBrave-unsigned.apk','anibrave/compiled/AniBrave-aligned.apk')
$result = 'output/anianibrave/AniBrave-0.1.0-arm64-v8a.apk'
Run (Join-Path $buildTools 'apksigner.bat') @('sign','--ks',$key,'--ks-key-alias',$alias,'--ks-pass','env:ANIBRAVE_KS_PASS','--key-pass','env:ANIBRAVE_KEY_PASS','--out',$result,'anibrave/compiled/AniBrave-aligned.apk')
Run (Join-Path $buildTools 'apksigner.bat') @('verify','--verbose','--print-certs',$result)
Run (Join-Path $buildTools 'zipalign.exe') @('-c','-P','16','4',$result)
Run 'python' @('anibrave/tools/verify.py')
Write-Host "Built $result"
