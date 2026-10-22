from pathlib import Path
import subprocess,zipfile
import argparse, os, tempfile, secrets
parser=argparse.ArgumentParser(description="Run real SQLite history tests in a disposable Android QA app; never touches AniBrave data.")
parser.add_argument('--serial', required=True)
parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME'))
parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
args=parser.parse_args()
if args.sdk is None or args.java_home is None:parser.error('set ANDROID_HOME/JAVA_HOME or pass --sdk/--java-home')
root=Path(__file__).resolve().parents[2]
temporary=tempfile.TemporaryDirectory(prefix='anibrave-history-qa-');work=Path(temporary.name)
jdk=args.java_home/'bin';bt=args.sdk/'build-tools/37.0.0';android=args.sdk/'platforms/android-37.1/android.jar'
environment=dict(os.environ, ANI_QA_SIGN_PASS=secrets.token_urlsafe(32))
def run(*a):subprocess.run([str(x) for x in a],check=True,env=environment)
classes=work/'classes';classes.mkdir(exist_ok=True)
run(jdk/'javac','--release','17','-cp',android,'-d',classes,*[root/'anibrave/src'/n for n in ['AnimeHistory.java','AnimeIdentity.java','WatchUrl.java','ResumeLocation.java','SiteKey.java','ContinuationStore.java']],root/'anibrave/tests/android/ContinuationInstrumentation.java')
run(jdk/'jar','cf',work/'test.jar','-C',classes,'.')
run(bt/'d8','--min-api','29','--lib',android,'--output',work,work/'test.jar')
run(bt/'aapt2','link','-o',work/'test.apk','-I',android,'--manifest',root/'anibrave/tests/android/ContinuationManifest.xml')
with zipfile.ZipFile(work/'test.apk','a') as z:z.write(work/'classes.dex','classes.dex')
if not (work/'qa.jks').exists():run(jdk/'keytool','-genkeypair','-keystore',work/'qa.jks','-storepass:env','ANI_QA_SIGN_PASS','-keypass:env','ANI_QA_SIGN_PASS','-alias','qa','-dname','CN=Disposable QA','-keyalg','RSA','-validity','2')
run(bt/'apksigner','sign','--ks',work/'qa.jks','--ks-pass','env:ANI_QA_SIGN_PASS',work/'test.apk')
adb=args.sdk/'platform-tools/adb'
subprocess.run([str(adb),'-s',args.serial,'uninstall','app.anibrave.continuationqa'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
run(adb,'-s',args.serial,'install','-r',work/'test.apk')
try:
 result=subprocess.run([str(adb),'-s',args.serial,'shell','am','instrument','-w','app.anibrave.continuationqa/app.anibrave.ContinuationInstrumentation'],check=True,capture_output=True,text=True)
 print(result.stdout)
 if 'Android SQLite/continuation checks passed' not in result.stdout:raise RuntimeError('Android regression checks failed')
finally:
 subprocess.run([str(adb),'-s',args.serial,'uninstall','app.anibrave.continuationqa'],check=True)
 temporary.cleanup()
