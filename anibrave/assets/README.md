# AniBrave lion artwork

`anibrave-lion.png` is the lion and ninja headband with a transparent background.
The former baked-in black rounded card and its border were removed with the
built-in image generation tool on 2026-10-14. The same asset is used beside the
AniHome name in its home header.

Edit prompt: remove only the dark rounded-square background, border and shadow;
preserve the orange/red lion, angular white face, metal headband and flowing
black/red ribbon; return a centered transparent PNG without a new outline.

Android adaptive launcher icons still require a system-masked background. The
build uses warm orange instead of the former dark card. Legacy icons and the
AniHome header retain the artwork's transparency.

`tools/patch_icon.py` can update an existing AniBrave APK without replacing its
browser runtime. Supply the original keystore and passwords privately through
`ANIBRAVE_KS_PASS` and `ANIBRAVE_KEY_PASS`. It verifies that the certificate,
all DEX files and all native libraries are unchanged before delivering an APK.
Build outputs and signing material must stay out of git.

The launcher drawable centers the lion face rather than the full PNG bounds: the
trailing ribbon makes the raw face axis about 57.5% from the left. Shared XML
placement in `tools/app_resources.py` offsets the legacy bitmap and adaptive
foreground. The original transparent artwork remains unchanged.

Updates add an explicit adaptive mipmap when an older APK has only a legacy icon.
Both variants use positive proportional insets; adaptive foreground bounds expand
beyond the visible mask, so fixed dp offsets do not preserve the face center.
