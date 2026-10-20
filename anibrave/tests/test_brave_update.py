import copy
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from check_brave_update import proposal

class BraveUpdateTest(unittest.TestCase):
    def setUp(self):
        self.pin = {'tag':'v1.97.56','repository':'brave/brave-browser','asset':'Bravearm64Universal.apk'}
        self.release = {'tag_name':'v1.98.1','draft':False,'prerelease':False,'assets':[
            {'name':'Bravearm64Universal.apk','state':'uploaded','size':215000000,
             'digest':'sha256:'+'a'*64,'browser_download_url':'https://github.com/brave/brave-browser/releases/download/v1.98.1/Bravearm64Universal.apk'}]}
    def test_new_stable_proposes_without_mutating_pin(self):
        original = copy.deepcopy(self.pin)
        candidate = proposal(self.pin,self.release)
        self.assertEqual(candidate['status'],'compatibility-review-required')
        self.assertEqual(candidate['sha256'],'a'*64)
        self.assertEqual(self.pin,original)
    def test_old_same_and_prerelease_do_not_propose(self):
        for tag in ['v1.97.56','v1.96.1']:
            self.release['tag_name']=tag
            self.assertIsNone(proposal(self.pin,self.release))
        self.release['tag_name']='v1.98.1'; self.release['prerelease']=True
        self.assertIsNone(proposal(self.pin,self.release))
    def test_missing_digest_or_wrong_url_fails(self):
        for field,value in [('digest',''),('browser_download_url','https://example.com/app.apk')]:
            release=copy.deepcopy(self.release);release['assets'][0][field]=value
            with self.assertRaises(ValueError):proposal(self.pin,release)
    def test_invalid_tag_fails(self):
        self.release['tag_name']='v1.98.1; echo unexpected'
        with self.assertRaises(ValueError):proposal(self.pin,self.release)
    def test_missing_or_duplicate_architecture_fails(self):
        for assets in [[],self.release['assets']*2]:
            release=copy.deepcopy(self.release);release['assets']=assets
            with self.assertRaises(ValueError):proposal(self.pin,release)
    def test_excessive_size_fails(self):
        self.release['assets'][0]['size']=500*1024*1024
        with self.assertRaises(ValueError):proposal(self.pin,self.release)

if __name__=='__main__':unittest.main()
