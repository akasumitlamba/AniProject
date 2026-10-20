"""Shared task manifest and icon placement for initial builds and APK updates."""
import xml.etree.ElementTree as ET

ANDROID = '{http://schemas.android.com/apk/res/android}'
# Use only positive fractions: AAPT can encode negative percentages above 100%.
LEGACY_ICON = '''<?xml version="1.0" encoding="utf-8"?>
<inset xmlns:android="http://schemas.android.com/apk/res/android"
    android:insetLeft="0%" android:insetRight="13.04%"
    android:insetTop="6.52%" android:insetBottom="6.52%">
    <bitmap android:src="@drawable/anibrave_lion" android:gravity="fill" android:filter="true"/>
</inset>
'''
# Fractions also scale with AdaptiveIconDrawable's expanded foreground bounds;
# fixed dp offsets drift when Android changes the icon size or mask.
ADAPTIVE_ICON = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/anibrave_icon_background"/>
    <foreground><inset android:insetLeft="15%" android:insetRight="24.13%" android:insetTop="19.565%" android:insetBottom="19.565%">
        <bitmap android:src="@drawable/anibrave_lion" android:gravity="fill" android:filter="true"/>
    </inset></foreground>
</adaptive-icon>
'''


def configure_tasks(application):
    name = 'app.anibrave.ShortcutTaskActivity'
    root = next((a for a in application.findall('activity') if a.get(ANDROID+'name') == name), None)
    if root is None:
        root = ET.SubElement(application, 'activity', {ANDROID+'name': name})
    for key, value in {
        'exported': 'false', 'theme': '@android:style/Theme.Translucent.NoTitleBar',
        'taskAffinity': 'app.anibrave.main.shortcuts', 'documentLaunchMode': 'intoExisting',
        'excludeFromRecents': 'false', 'autoRemoveFromRecents': 'false', 'maxRecents': '16',
        'configChanges': 'orientation|screenSize|smallestScreenSize|keyboardHidden|uiMode',
    }.items():
        root.set(ANDROID+key, value)


def write_icon_placement(resources):
    # Older shipped APKs only have a legacy mipmap. Add its adaptive variant so
    # Android does not re-normalize the legacy silhouette around the long ribbon.
    if (resources/'mipmap-anydpi/anibrave_icon.xml').exists():
        adaptive = resources/'mipmap-anydpi-v26/anibrave_icon.xml'
        adaptive.parent.mkdir(exist_ok=True)
        adaptive.write_text(ADAPTIVE_ICON)
    for target in resources.rglob('anibrave_icon.xml'):
        target.write_text(ADAPTIVE_ICON if ET.parse(target).getroot().tag == 'adaptive-icon' else LEGACY_ICON)
