import json
from pathlib import Path
import sys
import unittest

TOOLS = Path(__file__).resolve().parents[1] / 'tools'
sys.path.insert(0, str(TOOLS))
import map_engine  # noqa: E402
from smali_index import normal, obfuscated, params  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]


class EngineSymbolsTest(unittest.TestCase):
    def test_generated_java_matches_symbol_file(self):
        result = json.loads((ROOT / 'engine-symbols.json').read_text())
        self.assertEqual((ROOT / 'src' / 'EngineSymbols.java').read_text(), map_engine.java_source(result))

    def test_symbol_file_names_the_pinned_engine(self):
        result = json.loads((ROOT / 'engine-symbols.json').read_text())
        self.assertEqual(result['engine'], json.loads((ROOT / 'upstream.json').read_text())['tag'])

    def test_shape_normalization_hides_only_obfuscated_names(self):
        self.assertTrue(obfuscated('Lfdj;'))
        self.assertFalse(obfuscated('Lorg/chromium/chrome/browser/tab/Tab;'))
        self.assertEqual(normal('[Lc2k;'), '[L?;')
        self.assertEqual(params('ILfdj;[Ljava/lang/String;Z'), ['I', 'Lfdj;', '[Ljava/lang/String;', 'Z'])


if __name__ == '__main__':
    unittest.main()
