# Technical project context

This is the durable technical handoff. Keep it current when architecture, behavior, toolchains or known limitations change. Product READMEs and focused setup guides contain the operating instructions; dated QA diaries and per-session refactor reports are not retained in the source tree. Git history preserves earlier project revisions.

## Products and ownership

- AniHome (`launcher/`): Kotlin/Java Android HOME launcher, package `app.anihome.launcher`, Android 8+. App/site banners, compact icons, widgets, app drawer, recent files, notifications, gestures and optional Shizuku radio controls.
- AniBrave (`anibrave/`): Java/JavaScript customization of a pinned official Brave ARM64 engine, package `app.anibrave.main`, Android 10+. Owns its browser profile, immersive website shortcuts, playback controls, continuation and watch history. Check the engine policy and README for the current pinned engine; this is APK customization, not a complete Chromium source build.
- Desktop extension (`extensions/anibrave/`): independently loaded and versioned. An Android APK update does not update it. Preserve unfinished extension changes when working on the launcher or browser.
- Banner catalog (`banners/`): reviewed artwork, metadata and hashes. AniHome has bundled offline artwork and anonymously fetches from AniProject. User-selected artwork takes priority over automatic artwork.

## Behavior and implementation constraints

- Preserve package IDs, signing identity, existing databases, layouts, notes and shortcut destinations for in-place updates. Do not uninstall or clear application data as a routine update. A rollback may need a higher versionCode rebuilt from older source with the original signing identity.
- Launcher tiles and widgets share a persistent cell board. Keep widget host views alive across ordinary refreshes. Resize from edges; long-hold and drag rearranges items. Avoid a generic RecyclerView item animator on the custom board layout manager. Drops must commit before a return-to-origin recovery animation.
- Empty-space vertical swipes scroll when content remains in that direction. Reaching an edge during the same swipe must not trigger an assigned gesture. Respect gesture assignments, system navigation, provider-widget touches and layout lock.
- Keep settings and quick controls inside the edge-attached notification sidebar. Sidebar content scrolls; its control footer stays anchored. App/file search must visibly offer the web/address action. Dock background opacity must not hide its outline or fade text/icons.
- For rotation changes, use Android as the single authority. Do not introduce a separate forced-display policy, orientation overlay or independent persisted app angle. Lock the visible orientation through system settings; distinguish normal Android app-orientation behavior from forced global enforcement.
- Notification cancellation must reconcile Android's active list, clearable children and group summaries. Ongoing/protected alerts remain visible. Discard stale expansion state; never persist or upload notification contents.
- Radio lists prioritize connected/recent devices and remain scrollable. Scanning, requesting a connection and successfully associating are distinct results. Report failures rather than silently opening unrelated system screens.
- Site-native and extension/browser playback choices must stay synchronized without a reset loop. Preserve deliberate rates above a site's native menu range. Resume never bypasses DRM or forces playback; ambiguous episode matches require selection. Normal and private browsing data remain isolated.

## Development and validation

Use JDK 17 and the Android toolchain pinned by each product's build configuration. Launcher checks include `:app:testDebugUnitTest`, `:app:lintRelease` and `:app:assembleRelease`. Browser/extension Java, JavaScript, Python and Android checks are documented in their READMEs and CI workflows. Validate banner metadata and hashes with the catalog tools.

Use synthetic fixtures for public screenshots and reports. Automated unit tests, emulator checks and physical-device acceptance are separate evidence. No fixture suite proves every device firmware, Bluetooth/audio profile, secured network, third-party widget, authenticated player or protected stream. Respect Android permissions, Location requirements, pairing approval and provider restrictions. Unsupported players retain their own buffering/download limits.

## Security and publication

Keep secrets, signing material, personal conversations, device identifiers, local paths, real notifications, network names and personal captures out of public source and release assets. Scan the exact staged public tree with `python3 tools/check_public_source.py` and a redacted secret scanner. Preserve licenses and third-party attribution.

Public publishing uses AniProject's independent history and reviewed project-only changes. Never mirror private history or copy private chat archives into public context. Public banner requests use no saved credentials. Technical contributor instructions and this handoff contain project guidance, not personal conversation transcripts.

## Current launcher recovery baseline

AniHome 0.8.29 removes forced display rotation, migrates old app-only angle preferences, and uses Android Auto-rotate/current-orientation lock. The retired privileged force operation cannot change display policy. The local rotation guard is not a project dependency. Unit tests and release lint passed for this baseline; retain separate hardware acceptance evidence outside source.

## Unified product releases

`release.json` records the shared product display version. AniHome, AniBrave Android and the AniBrave desktop extension must all match it. Platform version codes remain independently increasing to preserve in-place updates. Every new or updated release contains all active products, banners, checksums and an all-products bundle. Never publish a launcher-only or browser-only maintenance release. Preserve consolidated notes before removing superseded standalone releases; keep combined rollback releases and Git tags. See RELEASING.md.
