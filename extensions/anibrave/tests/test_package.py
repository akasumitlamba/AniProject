import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('packager', ROOT / 'package.py')
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)

class PackageTests(unittest.TestCase):
    def test_archive_contains_every_manifest_resource_and_exact_runtime_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'extension.zip'
            with ZipFile(path, 'w') as archive:
                for name in packager.runtime_files(): archive.write(ROOT / name, name)
            with ZipFile(path) as archive:
                manifest = json.loads(archive.read('manifest.json'))
                for content in manifest['content_scripts']:
                    for name in content['js']: self.assertEqual(archive.read(name), (ROOT / name).read_bytes())
                self.assertIn('player.js', archive.namelist())
                self.assertFalse(any(name.startswith('tests/') for name in archive.namelist()))

    def test_missing_declared_script_is_a_packaging_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'manifest.json').write_text(json.dumps({'background':{'service_worker':'background.js'},'action':{'default_popup':'popup.html'},'content_scripts':[{'js':['missing-player.js']}],'icons':{}}))
            with self.assertRaises(ValueError): packager.runtime_files(root)

if __name__ == '__main__': unittest.main()
