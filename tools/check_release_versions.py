"""Check the shared public product version before building a combined release."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def check_versions(root=ROOT):
    expected = json.loads((root / 'release.json').read_text())['version']
    assert re.fullmatch(r'\d+\.\d+\.\d+', expected), 'Invalid product version'
    home = re.search(r'versionName\s*=\s*"([^"]+)"', (root / 'launcher/app/build.gradle.kts').read_text()).group(1)
    browser = json.loads((root / 'anibrave/version.json').read_text())['name']
    extension = json.loads((root / 'extensions/anibrave/manifest.json').read_text())['version']
    assert home == browser == extension == expected, f'Product versions differ: Home={home}, browser={browser}, extension={extension}, release={expected}'
    return expected

if __name__ == '__main__':
    print('All product versions match:', check_versions())
