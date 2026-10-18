"""Check tracked source without printing potentially sensitive matching values."""
import re
import subprocess
from pathlib import Path

PRIVATE_PATH = re.compile(
    r'(^|/)(?:AGENTS_CONTEXT\.md|CONTEXT-HANDOFF\.md|PROJECT-STORY\.md|'
    r'keystore\.properties|local\.properties|\.env(?:\..*)?|signing|qa-output|private-backups)(?:/|$)'
    r'|\.(?:jks|keystore|p12|pfx|pem|key|p8|mobileprovision|db|sqlite|sqlite3)$', re.I)
RULES = {
    'private key': re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----'),
    'GitHub token': re.compile(rb'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b'),
    'cloud access key': re.compile(rb'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'),
    'local home path': re.compile(rb'(?:/ho' rb'me/[^/\s"\x27]+/' rb'|/Us' rb'ers/[^/\s"\x27]+/|[A-Z]:\\Users\\[^\\\s"\x27]+\\)'),
    'literal signing password': re.compile(rb'(?:--(?:ks-pass|key-pass)[^\r\n]{0,30}["\x27]pass:|-(?:storepass|keypass)[\s,]+["\x27][^$])'),
    'literal adb serial': re.compile(rb'\badb(?:\.exe)?\s+-s\s+(?!emulator-|\$|[<{])[^\s"\x27]{8,}'),
}

def findings(path, data):
    if PRIVATE_PATH.search(path):
        yield 'private file', 0
    if b'\x00' not in data:
        for rule, pattern in RULES.items():
            for match in pattern.finditer(data):
                yield rule, data[:match.start()].count(b'\n') + 1


def main():
    paths = subprocess.check_output(['git', 'ls-files', '-z']).decode().split('\0')
    failed = False
    for name in filter(None, paths):
        path = Path(name)
        if not path.is_file():
            continue
        for rule, line in findings(name, path.read_bytes()):
            print(f'{name}:{line}: {rule}')
            failed = True
    if not failed:
        print('Tracked-source privacy check passed.')
    return int(failed)

if __name__ == '__main__':
    raise SystemExit(main())
