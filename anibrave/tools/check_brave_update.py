"""Prepare an engine-upgrade proposal. Never change the tested engine pin or sign APKs."""
import argparse
import json
import os
from pathlib import Path
import re
import urllib.request

REPOSITORY = 'brave/brave-browser'
ASSET = 'Bravearm64Universal.apk'

def version(tag):
    if not isinstance(tag, str) or not re.fullmatch(r'v1\.\d+\.\d+', tag):
        raise ValueError('Expected a stable Brave version tag')
    return tuple(map(int, tag[1:].split('.')))

def proposal(pin, release):
    current = version(pin['tag'])
    if release.get('draft') or release.get('prerelease'):
        return None
    tag = release['tag_name']
    target = version(tag)
    if target <= current:
        return None
    if pin['repository'] != REPOSITORY or pin['asset'] != ASSET:
        raise ValueError('Unexpected upstream repository or architecture')
    assets = [a for a in release['assets'] if a['name'] == ASSET and a.get('state') == 'uploaded']
    if len(assets) != 1:
        raise ValueError('Stable release must contain exactly one ARM64 universal APK')
    asset = assets[0]
    digest = asset.get('digest', '')
    url = f'https://github.com/{REPOSITORY}/releases/download/{tag}/{ASSET}'
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', digest) or asset.get('browser_download_url') != url:
        raise ValueError('Missing upstream SHA-256 or unexpected asset URL; manual review required')
    size = asset.get('size')
    if type(size) is not int or not 0 < size <= 400 * 1024 * 1024:
        raise ValueError('Unexpected APK size')
    return {'schema': 1, 'status': 'compatibility-review-required', 'current_tag': pin['tag'],
            'proposed_tag': tag, 'repository': REPOSITORY, 'asset': ASSET,
            'sha256': digest[7:], 'bytes': size, 'download_url': url,
            'release_url': f'https://github.com/{REPOSITORY}/releases/tag/{tag}'}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pin', type=Path, default=Path('anibrave/upstream.json'))
    parser.add_argument('--release-json', type=Path, help='Offline release fixture for testing')
    parser.add_argument('--output', type=Path, default=Path('anibrave/upstream-candidate.json'))
    args = parser.parse_args()
    if args.release_json:
        release = json.loads(args.release_json.read_text())
    else:
        headers = {'Accept': 'application/vnd.github+json', 'User-Agent': 'AniBrave-update-check'}
        token = os.environ.get('GH_TOKEN')
        if token: headers['Authorization'] = f'Bearer {token}'
        request = urllib.request.Request(f'https://api.github.com/repos/{REPOSITORY}/releases/latest', headers=headers)
        with urllib.request.urlopen(request, timeout=30) as response:
            data = response.read(4 * 1024 * 1024 + 1)
        if len(data) > 4 * 1024 * 1024: raise ValueError('Release metadata too large')
        release = json.loads(data)
    candidate = proposal(json.loads(args.pin.read_text()), release)
    if candidate:
        args.output.write_text(json.dumps(candidate, indent=2) + '\n')
        print(f"New Brave candidate: {candidate['proposed_tag']}; compatibility review required")
    else:
        print('No newer stable Android engine found; tested pin unchanged')
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write(f"available={'true' if candidate else 'false'}\n")
            if candidate: output.write(f"tag={candidate['proposed_tag']}\n")

if __name__ == '__main__': main()
