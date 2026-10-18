"""Catalog regression tests use tiny header fixtures; no real artwork is edited."""
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
from validate_banner_catalog import validate,dimensions


class BannerCatalogTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.root=Path(self.temporary.name);(self.root/'images').mkdir()
        # Dimensions reader fixture, deliberately not a deliverable image.
        self.bytes=b'\x89PNG\r\n\x1a\n'+struct.pack('>I',13)+b'IHDR'+struct.pack('>II',810,500)+bytes(9)
        (self.root/'images/example.png').write_bytes(self.bytes)
        self.row={'id':'example','name':'Example','file':'images/example.png','packages':['app.example.android'],'aliases':['Example app'],'webHosts':['example.com'],'sha256':hashlib.sha256(self.bytes).hexdigest()}
    def tearDown(self):self.temporary.cleanup()
    def check(self,rows=None):
        path=self.root/'catalog.json';path.write_text(json.dumps({'schema':1,'entries':rows or [self.row]}));return validate(path,minimum=1)
    def test_valid_metadata_and_dimension_reader(self):
        self.assertEqual(dimensions(self.bytes),(810,500));self.assertEqual(self.check(),([],[]))
    def test_matching_package_and_host_collisions_rejected(self):
        second=dict(self.row,id='other',name='Other',file='images/other.png');(self.root/'images/other.png').write_bytes(self.bytes)
        errors,warnings=self.check([self.row,second]);self.assertTrue(any('package' in message for message in errors));self.assertTrue(any('host' in message for message in errors))
    def test_duplicate_id_rejected(self):self.assertTrue(any('Duplicate id' in message for message in self.check([self.row,self.row])[0]))
    def test_label_ambiguity_surfaces_for_explicit_selection(self):
        second=dict(self.row,id='other',file='images/other.png',packages=['app.other.android'],webHosts=['other.com']);(self.root/'images/other.png').write_bytes(self.bytes)
        errors,warnings=self.check([self.row,second]);self.assertEqual(errors,[]);self.assertTrue(warnings)
    def test_path_traversal_and_remote_urls_rejected(self):
        for value in ['../secret','images/../../secret','https://other.example/a.png','images/nested/a.png']:
            self.row['file']=value;self.assertTrue(any('unsafe image path' in message for message in self.check()[0]))
    def test_corruption_and_missing_offline_asset_rejected(self):
        self.row['sha256']='0'*64;self.assertTrue(any('checksum mismatch' in message for message in self.check()[0]))
        self.row['sha256']=hashlib.sha256(self.bytes).hexdigest();self.check();errors,_=validate(self.root/'catalog.json',1,bundled=self.root/'missing');self.assertTrue(any('offline bundled image' in message for message in errors))
    def test_tablet_priority_and_category_contract(self):
        self.row['category']='Entertainment';self.row['tabletPriority']=101
        errors,_=self.check();self.assertTrue(any('category' in m for m in errors));self.assertTrue(any('priority' in m for m in errors))
        self.row['category']='entertainment';self.row['tabletPriority']=100;self.assertEqual(self.check()[0],[])
    def test_square_asset_and_invalid_package_rejected(self):
        data=self.bytes[:16]+struct.pack('>II',500,500)+self.bytes[24:];(self.root/'images/example.png').write_bytes(data);self.row['sha256']=hashlib.sha256(data).hexdigest();self.row['packages']=['looks.like?package']
        errors,_=self.check();self.assertTrue(any('landscape' in message for message in errors));self.assertTrue(any('invalid package' in message for message in errors))


if __name__=='__main__':unittest.main()
