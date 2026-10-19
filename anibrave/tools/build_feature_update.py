"""Build the AniBrave feature DEX and patch an existing installed APK.

Requires an existing private signing key/password environment. Does not install.
All upstream Brave DEX/native libraries and the signing certificate are verified.
"""
import argparse
from pathlib import Path
import subprocess
import sys


def main():
    root=Path(__file__).resolve().parents[2]
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--keystore',type=Path,required=True)
    parser.add_argument('--framework',type=Path,default=root/'.tools/sdk/platforms/android-37.1/android.jar')
    parser.add_argument('--build-tools',type=Path,default=root/'.tools/sdk/build-tools/37.0.0')
    parser.add_argument('--version-name', help='Optional application version name for the update')
    args=parser.parse_args()
    work=root/'anibrave/compiled/features'
    classes=work/'classes';classes.mkdir(parents=True,exist_ok=True)
    # Remove stale classes when a source class disappears.
    for path in classes.rglob('*.class'):path.unlink()
    subprocess.run(['javac','-encoding','UTF-8','--release','17','-cp',str(args.framework),'-d',str(classes),*[str(p) for p in sorted((root/'anibrave/src').glob('*.java'))]],check=True)
    subprocess.run(['jar','--create','--file',str(work/'features.jar'),'-C',str(classes),'.'],check=True)
    subprocess.run([str(args.build_tools/'d8'),'--min-api','29','--lib',str(args.framework),'--output',str(work),str(work/'features.jar')],check=True)
    command=[sys.executable,str(root/'anibrave/tools/patch_icon.py'),'--input',str(args.input),'--output',str(args.output),'--keystore',str(args.keystore),'--framework',str(args.framework),'--build-tools',str(args.build_tools),'--feature-dex',str(work/'classes.dex')]
    if args.version_name:command.extend(['--version-name',args.version_name])
    subprocess.run(command,check=True)


if __name__=='__main__':main()
