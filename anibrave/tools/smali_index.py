"""Read-only index over an apktool-decoded APK's smali tree."""
from functools import lru_cache
from pathlib import Path
import re

FIELD = re.compile(r'^\.field ((?:[\w-]+ )*)(\w+):(\S+)', re.M)
METHOD = re.compile(r'^\.method ((?:[\w-]+ )*)(\S+?)\((.*?)\)(\S+)\n(.*?)^\.end method', re.M | re.S)


class Index:
    def __init__(self, decoded):
        self.root = Path(decoded)
        self.paths = {}
        for folder in sorted(self.root.glob('smali*')):
            for path in folder.rglob('*.smali'):
                self.paths.setdefault(path.relative_to(folder).as_posix()[:-6], path)

    def folder(self, cls):
        return self.paths[cls].relative_to(self.root).parts[0]

    @lru_cache(maxsize=None)
    def text(self, cls):
        path = self.paths.get(cls)
        return path.read_text(encoding='utf-8') if path else ''

    def super(self, cls):
        match = re.search(r'^\.super L([^;]+);', self.text(cls), re.M)
        return match and match.group(1)

    def interfaces(self, cls):
        return re.findall(r'^\.implements L([^;]+);', self.text(cls), re.M)

    def chain(self, cls):
        out = []
        while cls and cls in self.paths:
            out.append(cls)
            cls = self.super(cls)
        return out

    @lru_cache(maxsize=None)
    def fields(self, cls):
        """[(name, type, static)] in declaration order."""
        return [(m[2], m[3], 'static' in m[1]) for m in FIELD.finditer(self.text(cls))]

    @lru_cache(maxsize=None)
    def methods(self, cls):
        """[(name, params, return, modifiers, body)] in declaration order."""
        return [(m[2], m[3], m[4], m[1], m[5]) for m in METHOD.finditer(self.text(cls))]


def params(descriptor):
    return re.findall(r'\[*(?:L[^;]+;|[ZBCSIJFDV])', descriptor)


def obfuscated(type_name):
    """Short, package-less class names are R8 output; everything else is stable."""
    inner = type_name.lstrip('[')
    if not inner.startswith('L'):
        return False
    name = inner[1:-1]
    return '/' not in name and len(name) <= 4 or name.startswith('J/')


def normal(type_name):
    return re.sub(r'^(\[*)L[^;]+;$', r'\1L?;', type_name) if obfuscated(type_name) else type_name


def class_of(type_name):
    return type_name[1:-1] if type_name.startswith('L') and type_name.endswith(';') else None
