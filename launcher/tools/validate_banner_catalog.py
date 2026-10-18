"""Validate curated Banner Store assets without network access or image editing.

Usage: python3 launcher/tools/validate_banner_catalog.py banners/catalog.json
       --bundled banners --minimum 100
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import unicodedata

ID=re.compile(r'[a-z0-9]+(?:-[a-z0-9]+)*\Z')
FILE=re.compile(r'images/[a-z0-9]+(?:-[a-z0-9]+)*\.(?:png|webp)\Z')
PACKAGE=re.compile(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+\Z')
HOST=re.compile(r'(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}\Z')


def normalized_name(value):
    return ''.join(c for c in value.lower() if unicodedata.category(c)[0] in ('L','N'))


def dimensions(data):
    """Read dimensions only; avoid decoding arbitrary full pixel arrays."""
    if data.startswith(b'\x89PNG\r\n\x1a\n') and len(data)>=33 and data[12:16]==b'IHDR':
        return struct.unpack('>II',data[16:24])
    if len(data)>=30 and data[:4]==b'RIFF' and data[8:12]==b'WEBP':
        kind=data[12:16]
        if kind==b'VP8X':return 1+int.from_bytes(data[24:27],'little'),1+int.from_bytes(data[27:30],'little')
        if kind==b'VP8L' and data[20]==0x2f:
            value=int.from_bytes(data[21:25],'little')
            return (value&0x3fff)+1,((value>>14)&0x3fff)+1
        if kind==b'VP8 ' and data[23:26]==b'\x9d\x01\x2a':
            return struct.unpack('<H',data[26:28])[0]&0x3fff,struct.unpack('<H',data[28:30])[0]&0x3fff
    raise ValueError('not a supported PNG/WebP image')


def validate(catalog_path,minimum=100,bundled=None):
    errors=[];warnings=[];catalog_path=Path(catalog_path)
    raw=catalog_path.read_bytes()
    if len(raw)>1024*1024:errors.append('Catalog exceeds Android 1 MiB limit')
    root=json.loads(raw)
    if root.get('schema',root.get('version'))!=1:errors.append('Expected schema 1')
    entries=root.get('entries',[])
    if not isinstance(entries,list):return ['entries must be an array'],[]
    if not minimum<=len(entries)<=2000:errors.append(f'Need {minimum}–2000 entries; found {len(entries)}')
    owners={kind:{} for kind in ('id','file','package','host','name')}
    def claim(kind,value,identifier,warning=False):
        previous=owners[kind].get(value)
        if previous and previous!=identifier:
            (warnings if warning else errors).append(f'{identifier}: {kind} {value!r} also belongs to {previous}')
        owners[kind][value]=identifier
    for index,row in enumerate(entries):
        if not isinstance(row,dict):errors.append(f'Entry {index} is not an object');continue
        identifier=row.get('id','')
        if not isinstance(identifier,str) or not ID.fullmatch(identifier):errors.append(f'Entry {index}: unsafe id');identifier=f'entry-{index}'
        claim('id',identifier,identifier)
        # claim() excludes same owner; repeated IDs need an explicit occurrence count.
        if sum(1 for item in entries[:index] if isinstance(item,dict) and item.get('id')==identifier):errors.append(f'Duplicate id {identifier}')
        name=row.get('name','')
        if not isinstance(name,str) or not name.strip() or len(name)>120:errors.append(f'{identifier}: invalid display name');name=''
        if name:claim('name',normalized_name(name),identifier,True)
        category=row.get('category','other');priority=row.get('tabletPriority',0)
        if not isinstance(category,str) or len(category)>40 or not ID.fullmatch(category):errors.append(f'{identifier}: invalid category')
        if not isinstance(priority,int) or isinstance(priority,bool) or not 0<=priority<=100:errors.append(f'{identifier}: invalid tablet priority')
        filename=row.get('file','')
        safe=isinstance(filename,str) and FILE.fullmatch(filename)
        if not safe:errors.append(f'{identifier}: unsafe image path');continue
        claim('file',filename,identifier)
        digest=row.get('sha256','')
        if not isinstance(digest,str) or not re.fullmatch('[a-f0-9]{64}',digest):errors.append(f'{identifier}: invalid SHA-256')
        for field,kind in (('packages','package'),('webHosts','host'),('aliases','name')):
            values=row.get(field,[])
            if not isinstance(values,list) or len(values)>100:errors.append(f'{identifier}: invalid {field} array');continue
            seen=set()
            for value in values:
                if not isinstance(value,str) or not value or len(value)>256:errors.append(f'{identifier}: invalid {field} value');continue
                if value in seen:errors.append(f'{identifier}: duplicate {field} value {value}');continue
                seen.add(value)
                if kind=='package' and not PACKAGE.fullmatch(value):errors.append(f'{identifier}: invalid package {value}')
                if kind=='host':
                    value=value.lower().removeprefix('www.')
                    if not HOST.fullmatch(value):errors.append(f'{identifier}: invalid host {value}')
                if kind=='name':value=normalized_name(value)
                claim(kind,value,identifier,kind=='name')
        path=catalog_path.parent/filename
        if path.is_symlink() or not path.resolve().is_relative_to(catalog_path.parent.resolve()):errors.append(f'{identifier}: image must be a real file inside catalog folder');continue
        if not path.is_file():errors.append(f'{identifier}: missing {filename}');continue
        data=path.read_bytes()
        if len(data)>4*1024*1024:errors.append(f'{identifier}: image exceeds Android 4 MiB limit')
        if hashlib.sha256(data).hexdigest()!=digest:errors.append(f'{identifier}: checksum mismatch')
        try:
            width,height=dimensions(data)
            if width<320 or height<160 or width>4096 or height>4096:errors.append(f'{identifier}: unsafe/small dimensions {width}×{height}')
            if not 1.57<=width/height<=1.67:errors.append(f'{identifier}: expected landscape 1.62:1; got {width}×{height}')
        except (ValueError,ZeroDivisionError) as failure:errors.append(f'{identifier}: {failure}')
        if bundled:
            mirror=Path(bundled)/filename
            if not mirror.is_file() or mirror.read_bytes()!=data:errors.append(f'{identifier}: offline bundled image differs/missing')
    if bundled:
        mirror=Path(bundled)/'catalog.json'
        if not mirror.is_file() or mirror.read_bytes()!=raw:errors.append('Bundled catalog differs/missing')
    return errors,warnings


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('catalog',type=Path);parser.add_argument('--minimum',type=int,default=100);parser.add_argument('--bundled',type=Path);args=parser.parse_args()
    try:errors,warnings=validate(args.catalog,args.minimum,args.bundled)
    except (OSError,ValueError) as failure:print(f'FAIL: {failure}');return 1
    for message in errors:print('FAIL:',message)
    for message in warnings:print('AMBIGUOUS NAME (manual selection required):',message)
    if errors:return 1
    print(f'Catalog valid: schema/files/checksums/dimensions/unique package and host mappings; {len(warnings)} label ambiguities')
    return 0


if __name__=='__main__':sys.exit(main())
