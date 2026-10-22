"""Repackage an existing AniBrave APK with artwork only, retaining its runtime.

Passwords are supplied via ANIBRAVE_KS_PASS / ANIBRAVE_KEY_PASS environment
variables. The original certificate and every DEX/native library must match.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from app_resources import configure_tasks, write_icon_placement


def run(*args, capture=False):
    return subprocess.run([str(a) for a in args], check=True,
                          stdout=subprocess.PIPE if capture else None,
                          text=True).stdout


def certificate(signer, apk):
    output = run(signer, 'verify', '--print-certs', apk, capture=True)
    match = re.search(r'certificate SHA-256 digest: (\w+)', output)
    if not match:
        raise RuntimeError('APK signing certificate unavailable')
    return match.group(1)


def runtime(apk):
    with zipfile.ZipFile(apk) as archive:
        return {name: hashlib.sha256(archive.read(name)).hexdigest()
                for name in archive.namelist()
                if re.fullmatch(r'classes\d*\.dex', name)
                or (name.startswith('lib/') and name.endswith('.so'))}


def require_engine_version(badging, tag):
    match = re.search(r"versionName='[^']+-brave-([^']+)'", badging)
    if not match or match.group(1) != tag.removeprefix('v'):
        raise RuntimeError('Feature source and input engine differ; use a full-engine build')


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--alias', default='anibrave')
    parser.add_argument('--apktool', type=Path, default=root/'.tools/apktool_3.0.3.jar')
    parser.add_argument('--build-tools', type=Path, default=root/'.tools/sdk/build-tools/37.0.0')
    parser.add_argument('--framework', type=Path, default=root/'.tools/sdk/platforms/android-37.1/android.jar')
    parser.add_argument('--feature-dex', type=Path, help='Optional rebuilt app.anibrave classes8.dex; upstream engine stays identical')
    parser.add_argument('--feature-source', type=Path, default=root/'anibrave/src', help='JS asset source used with --feature-dex')
    parser.add_argument('--version-name', help='Optional application version name; versionCode still increments')
    args = parser.parse_args()
    if args.version_name and not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.+_-]{0,99}', args.version_name):
        parser.error('Invalid version name')
    if not os.environ.get('ANIBRAVE_KS_PASS'):
        raise RuntimeError('Set ANIBRAVE_KS_PASS privately before signing')
    os.environ.setdefault('ANIBRAVE_KEY_PASS', os.environ['ANIBRAVE_KS_PASS'])
    if args.feature_dex:
        badging = run(args.build_tools/'aapt2', 'dump', 'badging', args.input, capture=True)
        require_engine_version(badging, json.loads((root/'anibrave/upstream.json').read_text())['tag'])
    signer = args.build_tools/'apksigner'
    original_certificate = certificate(signer, args.input)
    original_runtime = runtime(args.input)
    with tempfile.TemporaryDirectory(prefix='anibrave-icon-') as temporary:
        work = Path(temporary)
        decoded = work/'decoded'
        framework = work/'framework'
        framework.mkdir()
        run('java', '-jar', args.apktool, 'if', args.framework, '-p', framework)
        run('java', '-jar', args.apktool, 'd', '-s', '-f', args.input, '-o', decoded, '-p', framework)
        res = decoded/'res'
        targets = list(res.rglob('anibrave_lion.png'))
        if not targets:
            raise RuntimeError('Input APK has no named AniBrave lion resource; refusing unrelated APK')
        for target in targets:
            shutil.copyfile(root/'anibrave/assets/anibrave-lion.png', target)
        for background in res.rglob('anibrave_icon_background.xml'):
            background.write_text('<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle"><solid android:color="#FF7638"/></shape>\n')
        write_icon_placement(res)
        manifest = decoded/'AndroidManifest.xml'
        tree = ET.parse(manifest)
        android = '{http://schemas.android.com/apk/res/android}'
        element = tree.getroot()
        if element.get('package') != 'app.anibrave.main':
            raise RuntimeError('Input must be installed AniBrave')
        if args.feature_dex:
            if 'classes8.dex' not in original_runtime:
                raise RuntimeError('Expected existing app.anibrave feature DEX')
            shutil.copyfile(args.feature_dex, decoded/'classes8.dex')
            assets=decoded/'assets/anibrave'
            assets.mkdir(parents=True,exist_ok=True)
            for name in ('playback.js','tracking.js','player.js','continuation.js'):
                shutil.copyfile(args.feature_source/name,assets/name)
            application=element.find('application')
            application.set(android+'allowBackup','false')
            configure_tasks(application)
            if not any(activity.get(android+'name')=='app.anibrave.HistoryImportActivity' for activity in application.findall('activity')):
                ET.SubElement(application,'activity',{android+'name':'app.anibrave.HistoryImportActivity',android+'exported':'true',android+'configChanges':'orientation|screenSize',android+'theme':'@android:style/Theme.Material.NoActionBar'})
        metadata = decoded/'apktool.yml'
        metadata_text = metadata.read_text()
        manifest_version = element.get(android+'versionCode')
        metadata_version = re.search(r"(?m)^(\s*versionCode:)\s*['\"]?(\d+)['\"]?\s*$", metadata_text)
        previous_version = int(manifest_version or (metadata_version.group(2) if metadata_version else '0'))
        if previous_version < 1:
            raise RuntimeError('Input versionCode unavailable')
        new_version = str(previous_version + 1)
        element.set(android+'versionCode', new_version)
        if args.version_name:
            element.set(android+'versionName', args.version_name)
            metadata_text = re.sub(r'(?m)^(\s*versionName:)\s*[^\r\n]+$', lambda match: match.group(1)+' '+args.version_name, metadata_text)
        tree.write(manifest, encoding='utf-8', xml_declaration=True)
        if metadata_version:
            metadata_text = metadata_text[:metadata_version.start()] + metadata_version.group(1) + ' ' + new_version + metadata_text[metadata_version.end():]
        metadata.write_text(metadata_text)
        run('java', '-jar', args.apktool, 'b', decoded, '-o', work/'unsigned.apk', '-p', framework)
        run(args.build_tools/'zipalign', '-f', '-P', '16', '4', work/'unsigned.apk', work/'aligned.apk')
        run(signer, 'sign', '--ks', args.keystore, '--ks-key-alias', args.alias,
            '--ks-pass', 'env:ANIBRAVE_KS_PASS', '--key-pass', 'env:ANIBRAVE_KEY_PASS',
            '--out', work/'signed.apk', work/'aligned.apk')
        if certificate(signer, work/'signed.apk') != original_certificate:
            raise RuntimeError('Certificate differs from installed APK; refusing update')
        rebuilt_runtime=runtime(work/'signed.apk')
        expected_runtime=dict(original_runtime)
        if args.feature_dex:
            expected_runtime['classes8.dex']=hashlib.sha256(args.feature_dex.read_bytes()).hexdigest()
        if rebuilt_runtime != expected_runtime:
            raise RuntimeError('DEX/native libraries changed; refusing icon-only update')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(work/'signed.apk', args.output)
    print(f'APK ready: {args.output}; certificate and upstream DEX/native libraries unchanged')


if __name__ == '__main__':
    main()
