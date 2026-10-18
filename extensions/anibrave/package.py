"""Package all installable files, including each manifest-declared script."""
import json
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parent

def runtime_files(root=ROOT):
    manifest = json.loads((root / 'manifest.json').read_text())
    names = {'manifest.json', 'README.md'}
    for pattern in ('*.js', '*.html', '*.css', 'icons/*.png'):
        names.update(str(p.relative_to(root)) for p in root.glob(pattern))
    required = [manifest['background']['service_worker'], manifest['action']['default_popup']]
    for script in manifest['content_scripts']:
        required.extend(script.get('js', [])); required.extend(script.get('css', []))
    required.extend(manifest['icons'].values())
    for name in required:
        if name not in names or not (root / name).is_file():
            raise ValueError(f'Missing packaged manifest resource: {name}')
    return sorted(names)

def main():
    version = json.loads((ROOT / 'manifest.json').read_text())['version']
    out = ROOT.parents[1] / 'output' / f'AniBrave-Extension-{version}.zip'
    out.parent.mkdir(exist_ok=True)
    with ZipFile(out, 'w', ZIP_DEFLATED) as archive:
        for name in runtime_files():
            archive.write(ROOT / name, name)
    print(out)

if __name__ == '__main__': main()
