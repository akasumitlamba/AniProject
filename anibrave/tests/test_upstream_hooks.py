import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from upstream_hooks import remove_shortcut_close, bootstrap_shortcut_controls

class ShortcutOverlayTest(unittest.TestCase):
    def test_only_close_attachment_removed(self):
        body = '''invoke-super init
new-instance v5, Landroid/widget/ImageView;
    invoke-virtual {v3, v5, v1}, Landroid/view/ViewGroup;->addView(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V
request-notification-permission
'''
        patched=remove_shortcut_close(body)
        self.assertNotIn('->addView(',patched)
        self.assertIn('invoke-super init',patched)
        self.assertIn('request-notification-permission',patched)
    def test_changed_overlay_fails_closed(self):
        with self.assertRaises(RuntimeError):remove_shortcut_close('different upstream')

class ShortcutBootstrapTest(unittest.TestCase):
    def test_bootstrap_follows_native_initialization(self):
        call='    invoke-super {p0}, Lorg/chromium/chrome/browser/customtabs/CustomTabActivity;->O3()V'
        patched=bootstrap_shortcut_controls(call+'\n    return-void')
        self.assertLess(patched.index('invoke-super'),patched.index('->shortcutReady('))
        self.assertLess(patched.index('->shortcutReady('),patched.index('return-void'))
    def test_changed_native_callback_fails_closed(self):
        with self.assertRaises(RuntimeError):bootstrap_shortcut_controls('changed engine')
