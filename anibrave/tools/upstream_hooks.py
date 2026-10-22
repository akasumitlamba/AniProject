"""Small fail-closed transformations of pinned upstream methods."""
import re


def remove_shortcut_close(text):
    # This ImageView is Brave's floating shortcut exit button, not site DOM or
    # browser/video fullscreen controls. Preserve initialization and permissions.
    pattern = (r'(?m)^    invoke-virtual \{v3, v5, v1\}, '
               r'Landroid/view/ViewGroup;->addView\(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;\)V$')
    if text.count('new-instance v5, Landroid/widget/ImageView;') != 1:
        raise RuntimeError('Fullscreen shortcut close overlay changed')
    changed, count = re.subn(pattern, '    nop    # AniBrave shortcuts use system Back, no floating exit overlay.', text)
    if count != 1:
        raise RuntimeError('Expected one fullscreen shortcut close attachment')
    return changed


def bootstrap_shortcut_controls(text):
    # O3 is the pinned post-native/profile initialization callback. Starting the
    # page controller from onResume alone is too early on shortcut-only startup.
    call = '    invoke-super {p0}, Lorg/chromium/chrome/browser/customtabs/CustomTabActivity;->O3()V'
    if text.count(call) != 1:
        raise RuntimeError('Fullscreen shortcut native-ready callback changed')
    return text.replace(call, call + '\n    invoke-static/range {p0 .. p0}, Lapp/anibrave/AniRuntime;->shortcutReady(Landroid/app/Activity;)V')
