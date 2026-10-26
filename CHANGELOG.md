# Release history

## 0.9.0 — unified product release

All active products now share version 0.9.0. This release combines AniHome 0.8.29 maintenance, AniBrave Android 0.2.2 and desktop extension continuation work, with 177 banners. Android package IDs, signing identities and user storage are preserved.

AniHome includes opaque tile rendering, unclipped floating remove controls, deletion compaction, bounded drag scrolling, an unobstructed empty wallpaper, system-only rotation, pinned shortcuts, responsive drawer/files, notification controls, widgets and configurable gestures.

AniBrave Android retains Brave 1.98.60, vertical tabs, immersive shortcuts in Recents, local episode history/continuation, timestamp and speed restoration, two-way playback speed control, and controller/discovery performance fixes. QA fixes preserve partial episode metadata and learned cross-site links during transactional identity corrections, and reject empty normalized identities. The upstream engine and native libraries are unchanged from the preceding verified browser build.

The desktop extension includes two-way speed synchronization with explicit above-menu speed precedence, per-site device views, local editable episode memory, conservative cross-site continuation/search, timestamp/speed restoration, persistent dismissal and private-window history guards.

Older combined releases remain available for rollback. The standalone release records below are superseded by the unified release; their Git tags remain. Historical test counts below describe those earlier builds, not new acceptance claims.

## Consolidated standalone release notes

### AniBrave 0.1.1 — vertical tabs

Historical tag: `anibrave-v0.1.1-vertical-tabs-fix`.

AniBrave Android browser customization, Android 10+, ARM64, version code 429705612. Includes vertical tabs, immersive shortcuts, saved playback preferences and local watch history.

This is the unchanged, audited signed binary for the included browser source. It is not a new engine build. Install over an existing matching-signature installation to preserve its browser profile. Protected streaming and device-specific behavior require hardware acceptance. Checksums and the independent public source snapshot are attached; no private development history is included.


### AniHome 0.8.14 — public banners

Historical tag: `launcher-v0.8.14`.

AniHome 0.8.14, Android 8+. Banner Store updates now load anonymously from AniProject. No account or token is required; obsolete banner-store access is discarded while existing tiles, artwork and app data are retained. Offline banners remain bundled.

Validation: 90 unit tests, release lint and signed assembly passed. APK signing identity is preserved for compatible in-place updates. Install the release APK over an existing matching-signature installation; do not uninstall or clear data.


### AniBrave desktop extension 0.1.3 — speed precedence

Historical tag: `anibrave-extension-v0.1.3`.

AniBrave desktop extension 0.1.3 supports 0.25×–5×. An explicit extension speed takes precedence over a restricted site speed menu, using HTML media playback when the native player API refuses the requested rate. Scripted rate resets are filtered without repeated competing media writes; real site-control selections still synchronize. Ad playback and detached media release the override.

Validation: 29 JavaScript tests, two packaging tests, and an isolated real Brave regression suite including 5× playback, native menu limits, repeated scripted resets, trusted site controls, ads, embedded/shadow players and website views. Protected players can still impose engine restrictions; signed-in DRM streams are not covered by fixture tests.

Extract to the existing unpacked-extension folder, press Reload on the AniBrave card at brave://extensions, then reload open player tabs. Existing local settings are retained. SHA-256 is included alongside the ZIP.


### AniHome 0.8.16 — pinned shortcuts and Home controls

Historical tag: `launcher-v0.8.16`.

AniHome 0.8.16 adds Android pinned shortcuts with explicit Add/Cancel confirmation. Browser fullscreen shortcuts retain their original destination and extras; removal allows re-pinning, and existing Home data upgrades in place.

Also includes the notification sidebar dismissal fixes, outside-tap closing, double-tap screen lock, empty-space long-press editing, banner/widget drag ordering, and inset remove buttons. Screen lock uses the optional AniHome Accessibility control.

Validation: 96 unit tests, release lint, debug/signed release builds, notification/gesture checks in both orientations, and Android pin-provider integration checks. Browser engine and protected-streaming acceptance are separate from synthetic shortcut validation.

Install the APK over the existing AniHome installation. The signing identity is unchanged; do not uninstall or clear data. SHA-256 is included alongside the APK.


### AniHome 0.8.29 — Home layout maintenance

Historical tag: `anihome-v0.8.29`.

# AniHome 0.8.29 — maintenance update (build 42)

Updates the existing 0.8.29 release; install the APK as an update to preserve Home data. The signing identity is unchanged.

- Added tiles stay fully opaque across artwork refreshes, edit changes and view recycling.
- Floating remove controls fit the banner and widget corners without ancestor clipping; labels receive sufficient layout height.
- Removing Home items compacts remaining tiles, compact icons and widgets in visual order, bringing lower rows forward.
- Dragging stays inside the visible scrolling area and follows the pointer while the board scrolls.
- An empty Home displays the wallpaper without an add prompt.
- Rotation settings are written only by an explicit Auto/Fixed toggle. Rejected changes roll back instead of leaving a partial setting. Opening Home or the app drawer does not change Android's rotation lock.
- Includes the expanded 177-entry banner catalog, with corrected brand artwork.

This release retains the earlier 0.8.29 system-rotation recovery and Home editing/settings improvements. Forced-display rotation and the local rotation guard are not included.

Validation for build 42: 121 JVM tests, release lint, signed assembly, 43 Android integration checks, an empty-Home UI check, and emulator checks that both Auto and Fixed settings survive Home launches unchanged. All bundled banner hashes, signing identity, staged-source privacy and secret scans were verified.

Limitations: Android owns rotation and individual apps retain Android-supported orientation behavior. A deliberately selected system Fixed lock may remain after uninstall; restore Auto-rotate in Android quick settings. This update cannot diagnose a disconnected device or a separate firmware/sensor problem. Physical-device drag, radio and every third-party widget combination have not been exhaustively tested for this build. AniBrave and the desktop extension are unchanged.


## Additional consolidated maintenance history

Earlier standalone AniHome releases established native-app and website tiles, wallpaper Home, local data persistence, widgets, notification controls and configurable Home gestures. Earlier standalone AniBrave patches added local history, auto-hiding reminders, stable vertical-tab rendering, shortcut resume, two-way speed synchronization and shortcut task recovery. These features are retained in the current products; obsolete browser previews do not define current acceptance. Retired Firefox/Lite product archives are not part of this consolidation.

### Browser maintenance consolidated from earlier standalone builds

- `anibrave-v0.1.1-local-history`: on-device episode summaries and watch logs for supported anime adapters, qualified video progress before recording, profile privacy guards and editable local history.
- `anibrave-v0.1.1-reminder-timeout`: reminder auto-hide after eight seconds, accessibility-adjusted reading time, and suppression of repeated presentation for the same page/episode.
- `anibrave-v0.1.1-vertical-tabs`, `anibrave-v0.1.1-sidebar-polish`, and `anibrave-v0.1.1-vertical-tabs-fix`: selectable horizontal/vertical native tab layouts, compact tab counts and rows, Regular/Private switching, persistent hiding of the horizontal compositor strip in vertical mode, scroll/toolbar behavior, restored tab selection and layout insets.
- `anibrave-v0.1.1-resume-speed` and `anibrave-v0.1.1-resume-speed-r2`: foreground normal-profile shortcut destination restoration, site/native playback-speed synchronization, delayed native menu acknowledgement and numeric-only rate labels such as 1.5. The r2 build superseded the earlier speed build.

These historical records describe feature lineage. Current acceptance is limited to the validation listed in the 0.9.0 release; old native UI test counts do not imply complete present-day site compatibility.
