# Technical project context

## Products

- AniHome (`launcher/`): Kotlin/Java Android HOME launcher, package `app.anihome.launcher`, Android 8+. App/site banners, widgets, app drawer, recent files, notifications and optional Shizuku radio controls.
- AniBrave (`anibrave/`): Java/JavaScript customization of the pinned official Brave 1.97.59 ARM64 engine, package `app.anibrave.main`, Android 10+. Browser profile, watch history and immersive website shortcuts live here. Shortcuts resume same-site foreground normal-profile URLs; isolated playback control synchronizes deliberate native speed choices and preserves browser rates above native menu limits. Buffer-ahead preferences apply across sites through exposed matching player APIs, with a bounded user-cancelable stall guard; unsupported players retain their own download limits.
- Banner catalog (`banners/`): public named artwork, metadata and hashes. AniHome bundles an offline copy and explicitly refreshes anonymously from AniProject's main branch.

## Development

Use JDK 17 and Android SDK/build-tools 35 for AniHome. Run launcher unit tests, release lint and assembly. Browser Java/JavaScript checks and packaging instructions are in its README and CI workflows. Validate banner metadata and image hashes with the catalog tools before committing artwork.

Keep UI, tracking and data ownership within their respective apps. Preserve package IDs, data schemas and signing identity for compatible in-place updates. Use synthetic emulator fixtures for screenshots; describe hardware acceptance separately from automated checks.

## Security and privacy

No secrets, signing keys, private conversations, device serials, local paths, personal captures or data backups belong in source or release attachments. Signing configuration stays local. Public banner requests never use saved credentials. Run `python3 tools/check_public_source.py` before committing. Preserve upstream licenses and third-party attribution.

This document is technical contributor context, not a chat transcript or personal development diary.
