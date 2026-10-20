"""Linux full-engine build. Pins inputs, isolates caches by engine hash, keeps signing local."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.request

ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'anibrave'
TOOLS={
 'apktool.jar':'https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar',
 'dexlib2.jar':'https://repo.maven.apache.org/maven2/org/smali/dexlib2/2.5.2/dexlib2-2.5.2.jar',
 'smali.jar':'https://repo.maven.apache.org/maven2/org/smali/smali/2.5.2/smali-2.5.2.jar',
 'smali-util.jar':'https://repo.maven.apache.org/maven2/org/smali/util/2.5.2/util-2.5.2.jar',
 'guava.jar':'https://repo.maven.apache.org/maven2/com/google/guava/guava/27.1-android/guava-27.1-android.jar',
 'antlr-runtime.jar':'https://repo.maven.apache.org/maven2/org/antlr/antlr-runtime/3.5.2/antlr-runtime-3.5.2.jar',
 'antlr.jar':'https://repo.maven.apache.org/maven2/org/antlr/antlr/3.5.2/antlr-3.5.2.jar',
 'jcommander.jar':'https://repo.maven.apache.org/maven2/com/beust/jcommander/1.64/jcommander-1.64.jar'}

def sha(path):
 h=hashlib.sha256()
 with path.open('rb') as stream:
  while block:=stream.read(1024*1024):h.update(block)
 return h.hexdigest()

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--input',type=Path,required=True)
 p.add_argument('--output',type=Path,required=True)
 p.add_argument('--keystore',type=Path,required=True)
 p.add_argument('--sdk',type=Path,default=ROOT/'.tools/sdk')
 args=p.parse_args();pin=json.loads((BASE/'upstream.json').read_text())
 if sha(args.input)!=pin['sha256']:raise RuntimeError('Official upstream APK hash mismatch')
 if not args.keystore.is_file() or not os.environ.get('ANIBRAVE_KS_PASS'):raise RuntimeError('Existing private key and signing password environment required')
 work=ROOT/'.tools/engine-build'/pin['sha256'];compiled=work/'compiled';compiled.mkdir(parents=True,exist_ok=True)
 env=dict(os.environ,ANIBRAVE_ENGINE_WORK=str(work),ANIBRAVE_UPSTREAM_APK=str(args.input.resolve()))
 def run(*cmd):subprocess.run([str(c) for c in cmd],check=True,cwd=ROOT,env=env)
 jars=ROOT/'.tools/engine-tools';jars.mkdir(exist_ok=True)
 locks=json.loads((BASE/'tools/tool-checksums.json').read_text())
 for name,url in TOOLS.items():
  target=jars/name
  if not target.exists():
   # Only fixed tool URLs are used; failed downloads never become cached tools.
   temp=target.with_suffix('.download')
   with urllib.request.urlopen(url,timeout=60) as source,temp.open('wb') as output:
    while block:=source.read(1024*1024):output.write(block)
   if sha(temp)!=locks[name]:temp.unlink();raise RuntimeError('Tool checksum mismatch: '+name)
   temp.replace(target)
  if sha(target)!=locks[name]:raise RuntimeError('Cached tool checksum mismatch: '+name)
 android=args.sdk/'platforms/android-37.1/android.jar';bt=args.sdk/'build-tools/37.0.0';framework=work/'framework'
 run('java','-jar',jars/'apktool.jar','if',android,'-p',framework)
 if not (work/'decoded').exists():run('java','-jar',jars/'apktool.jar','d',args.input.resolve(),'-o',work/'decoded','--no-res','--jobs','4','-p',framework)
 if not (work/'resources').exists():run('java','-jar',jars/'apktool.jar','d',args.input.resolve(),'-o',work/'resources','--no-src','--jobs','4','-p',framework)
 import shutil
 shutil.rmtree(compiled/'patches',ignore_errors=True)
 run('python3',BASE/'tools/prepare.py')
 classes=compiled/'features';shutil.rmtree(classes,ignore_errors=True);classes.mkdir(exist_ok=True)
 run('javac','--release','17','-cp',android,'-d',classes,*sorted((BASE/'src').glob('*.java')))
 run('jar','cf',compiled/'features.jar','-C',classes,'.')
 run(bt/'d8','--min-api','29','--lib',android,'--output',compiled,compiled/'features.jar')
 cp=os.pathsep.join(str(jars/n) for n in ['dexlib2.jar','guava.jar'])
 run('javac','-cp',cp,'-d',compiled,BASE/'tools/MergeDex.java')
 smali_cp=os.pathsep.join(str(jars/n) for n in ['smali.jar','dexlib2.jar','smali-util.jar','guava.jar','antlr-runtime.jar','antlr.jar','jcommander.jar'])
 run('java','-cp',smali_cp,'org.jf.smali.Main','assemble','--api','29','--output',compiled/'hooks.dex',compiled/'patches')
 dex=compiled/'dex';dex.mkdir(exist_ok=True);merge_cp=str(compiled)+os.pathsep+cp
 for source in sorted((work/'resources').glob('classes*.dex')):
  command=['java','-Xmx2g','-cp',merge_cp,'MergeDex',source,dex/source.name]
  if source.name=='classes5.dex':command.append(compiled/'hooks.dex')
  run(*command)
 run('java','-Xmx2g','-cp',merge_cp,'MergeDex',compiled/'hooks.dex',dex/'classes7.dex')
 import shutil
 shutil.copyfile(compiled/'classes.dex',dex/'classes8.dex')
 run('java','-jar',jars/'apktool.jar','b',work/'resources','-o',compiled/'resources.apk','--jobs','4','-p',framework)
 run('python3',BASE/'tools/package.py')
 run(bt/'zipalign','-f','-P','16','4',compiled/'AniBrave-unsigned.apk',compiled/'aligned.apk')
 args.output.parent.mkdir(parents=True,exist_ok=True)
 keypass='ANIBRAVE_KEY_PASS' if env.get('ANIBRAVE_KEY_PASS') else 'ANIBRAVE_KS_PASS'
 run(bt/'apksigner','sign','--ks',args.keystore.resolve(),'--ks-key-alias',env.get('ANIBRAVE_KEY_ALIAS','anibrave'),'--ks-pass','env:ANIBRAVE_KS_PASS','--key-pass','env:'+keypass,'--out',args.output.resolve(),compiled/'aligned.apk')
 run(bt/'zipalign','-c','-P','16','4',args.output)
 run(bt/'apksigner','verify','--print-certs',args.output)
 print('Signed engine build:',args.output,'SHA256',sha(args.output),flush=True)

if __name__=='__main__':main()
