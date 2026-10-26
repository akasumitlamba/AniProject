import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from app_resources import ANDROID as A, configure_tasks, write_icon_placement
from patch_icon import require_engine_version


class AppResourcesTest(unittest.TestCase):
    def test_feature_update_refuses_a_different_engine(self):
        require_engine_version("versionName='0.1.5-brave-1.97.59'", 'v1.97.59')
        for value in ["versionName='0.1.4-brave-1.97.56'", "versionName='1.97.59'", '']:
            with self.assertRaises(RuntimeError):
                require_engine_version(value, 'v1.97.59')

    def test_shared_product_version_has_separate_engine_identity(self):
        manifest=ET.fromstring('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><meta-data android:name="app.anibrave.BRAVE_VERSION" android:value="1.98.60"/></application></manifest>')
        require_engine_version("versionName='0.9.0'", 'v1.98.60', manifest)
        with self.assertRaises(RuntimeError):
            require_engine_version("versionName='0.9.0'", 'v1.97.59', manifest)

    def test_retained_root_is_internal_separate_and_idempotent(self):
        app = ET.fromstring('<application><activity name="unrelated"/></application>')
        configure_tasks(app)
        configure_tasks(app)
        roots = [a for a in app.findall('activity') if a.get(A+'name') == 'app.anibrave.ShortcutTaskActivity']
        self.assertEqual(len(roots), 1)
        root = roots[0]
        self.assertEqual(root.get(A+'exported'), 'false')
        self.assertEqual(root.get(A+'taskAffinity'), 'app.anibrave.main.shortcuts')
        self.assertEqual(root.get(A+'excludeFromRecents'), 'false')
        self.assertEqual(root.get(A+'autoRemoveFromRecents'), 'false')
        self.assertEqual(root.get(A+'documentLaunchMode'), 'intoExisting')
        self.assertEqual(len(app.findall('activity')), 2)

    def test_initial_and_update_icon_placement_center_face_axis(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            for folder, xml in [('drawable', '<bitmap/>'), ('mipmap-anydpi', '<bitmap/>')]:
                (root / folder).mkdir()
                (root / folder / 'anibrave_icon.xml').write_text(xml)
            write_icon_placement(root)
            legacy = ET.parse(root/'drawable/anibrave_icon.xml').getroot()
            self.assertEqual(legacy.tag, 'inset')
            left = float(legacy.get(A+'insetLeft')[:-1])/100
            right = float(legacy.get(A+'insetRight')[:-1])/100
            self.assertLess(abs(left + (1-left-right)*.575 - .5), .001)
            self.assertTrue(all(float(legacy.get(A+key)[:-1]) >= 0 for key in ('insetLeft','insetRight','insetTop','insetBottom')))
            foreground = ET.parse(root/'mipmap-anydpi-v26/anibrave_icon.xml').getroot().find('foreground/inset')
            left = float(foreground.get(A+'insetLeft')[:-1])/100
            right = float(foreground.get(A+'insetRight')[:-1])/100
            self.assertLess(abs(left + (1-left-right)*.575 - .5), .001)
            self.assertEqual(foreground.find('bitmap').get(A+'gravity'), 'fill')


if __name__ == '__main__':
    unittest.main()
