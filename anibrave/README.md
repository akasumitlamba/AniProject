# AniBrave

Personal Android browser based on the official Brave 1.98.60 ARM64 APK (Chromium 156.0.8078.48). Install identity: `app.anibrave.main`. Launcher name: **AniBrave**. This installs alongside official Brave. AniBrowser Full (Firefox/GeckoView) and Lite have been removed from this repository. It has an independent profile: cookies are shared between AniBrave tabs and its shortcuts, not imported from other installed browsers.

The native engine and Shields libraries remain byte-for-byte identical to the verified upstream APK. This is a locally compiled Java/JavaScript customization and repackaging of that APK, not a full Chromium source build. It uses a separate personal signing key. A daily GitHub Actions check prepares draft engine-upgrade proposals; compatibility testing, signing and installation remain explicit steps (see [engine updates](docs/engine-updates.md)). It is an experimental personal build; physical tablet startup and settings application have been checked, but universal streaming compatibility is not claimed.

## Included scope

- Per-site speed choices from 0.5× to 4×, saved immediately. `www` and mobile hostname aliases share settings. Embedded frames use the top website's setting.
- Two-way site/browser speed synchronization, with bounded recovery across episode/player replacements. Browser rates above a site menu limit take precedence.
- A native sliders icon beside the tab switcher opens Playback speed, Site view and Fullscreen shortcut. It belongs to the address-bar layout and scrolls with Brave's toolbar; there is no persistent floating button. Website seeking, subtitles and playback controls remain untouched.
- Shortcuts resume the last foreground normal-profile URL for the same site, including pages visited in the normal browser. Private/background pages never update that destination.
- Android pinned website shortcuts launch Brave's existing FullScreenCustomTabActivity via an internal activity, using its normal browsing profile, zero-height browser toolbar and immersive Android system bars. Edge gestures can temporarily reveal system bars.
- Scroll or vertical touch gestures in immersive shortcuts reveal tools without consuming page input.
- Mobile, desktop identity with mobile viewport, and desktop viewport settings saved per site. Changes reload the page. Device rotation updates viewport dimensions.
- Website-provided launcher artwork, with an AniBrave icon fallback.
- Brave's ordinary address-bar scroll behavior, Shields and DNS settings remain in place.
- New-tab URLs are translated to `about:blank`; no AniHome/dashboard/wallpaper is added.

No extra playback/seek controls, AniBrowser redirect prompts, custom DNS interface or extension framework is added.

## Use

Open a site, tap the **sliders icon next to Tabs**, then choose **Playback speed**, **Site view**, **Fullscreen shortcut**, or **Watched anime**. Immersive shortcuts keep gesture-revealed temporary tools because their browser toolbar is hidden. Confirm the Android launcher prompt to create a shortcut. Create shortcuts within AniBrave: shortcuts made by another browser remain associated with that browser.

Website players may resist speed changes, and native/closed-shadow/DRM-controlled playback can restrict scripting. The controller does not bypass DRM or modify the website's fullscreen DOM. Website speed support, subtitles, login persistence and controls in fullscreen must be checked on the tablet.

## Build on Windows

```powershell
.\anibrave\build.ps1
# Controller checks only:
.\anibrave\build.ps1 -TestsOnly
```

Requires Java 17, Node, Python, `gh`, and the workspace Android SDK 37.1/build-tools 37.0.0. The script pins and checks upstream APK/tool hashes, compiles the feature module, assembles five narrowly scoped integration hooks, rewrites install-identity string constants through dexlib2, rebuilds Android resources, retains all original native libraries, aligns and signs the APK, and verifies the result. Set `ANIBRAVE_KEYSTORE` to your private existing keystore and supply signing passwords through environment variables (see below). Preserve that signing identity for future updates. Signing files and downloaded binaries are ignored by Git.

Output: `output/anibrave/AniBrave-0.1.0-arm64-v8a.apk`, plus `verification.json` and `SHA256SUMS.txt`. Minimum Android version: Android 10. Only this ARM64 build is intended for delivery.

## Validation and limits

The controller suite includes JavaScript checks plus Java resume/site/HTTP, identity, reminder and layout policies. It covers native rate synchronization, replacement videos, bounded recovery, stale choices, private URL exclusion, preserved fullscreen controls and gestures. APK signature, 16 KiB library alignment, APK integrity, all eight DEX checksums and all five original native-library hashes are checked.

The original page-control connection waited for EOF on Chromium's persistent HTTP connection, preventing any settings from being applied. The response reader now consumes exactly Content-Length bytes, including partial reads, with size and truncation checks. Browser-level events without a page session no longer trigger a null-key lookup and reconnect. Target URL changes update saved settings. Diagnostic logs report connection and protocol failures without logging page contents.

Historical preview checks covered native menu speed changes, recovery after a rate reset, saved preferences after reload, and three site viewing modes. Synthetic media checks do not establish protected-streaming compatibility.

These are settings integration checks, not live streaming/DRM or fullscreen shortcut acceptance. An earlier private x86-64 emulator harness checked startup only; its local video launch was blocked by automatic approval review. Website playback and shortcut behavior still need physical user acceptance.

The internal controller uses Chromium's DevTools protocol through an abstract Unix socket named `anibrave_devtools_remote`. It opens no TCP port. Native authorization retains Chromium's ADB/root rules and additionally allows AniBrave's own UID; other ordinary apps are denied. The client and socket stop when the browser activity pauses. The socket is not a public Android service or JavaScript bridge exposed to website scripts; bindings live in a separate isolated world.

## Upstream and notices

- [Brave 1.98.60 release](https://github.com/brave/brave-browser/releases/tag/v1.98.60)
- [Corresponding Brave source](https://github.com/brave/brave-core/tree/v1.98.60), tag commit `52db4b25c0aa3038e9a1c6021428b136cc3cb5fc`
- [Chromium source](https://chromium.googlesource.com/chromium/src/+/156.0.8078.48/)
- [Brave source license](https://github.com/brave/brave-core/blob/v1.98.60/LICENSE)

Existing native and browser notices are retained in the app. Customization sources in this folder are MPL-2.0. AniBrave is independent and is not an official Brave Software product. Brave, Chromium, apktool, smali/dexlib2 and their dependencies retain their respective licenses and trademarks.

## Local anime history

Tracking belongs to AniBrave. Updates preserve the browser profile when signed with the same identity. See [TRACKING.md](TRACKING.md) for progress gating, cross-site reminders, local import, privacy, build verification and acceptance limits. The current update is 0.2.1 (Brave 1.98.60), versionCode 429705621. This engine update retains local per-episode continuation, conservative cross-site search, timestamp/speed restoration and the editable episode archive.

## Episode reminders and protected playback

Episode reminders hide after eight seconds, respect accessibility reading time, and retain explicit dismissal. Watching a different episode updates the local record. See [tracking](TRACKING.md) and [protected playback troubleshooting](docs/playback-troubleshooting.md). Physical-player compatibility remains separate from synthetic and emulator validation.

### Tab layout

The normal browser supports a collapsible vertical tab sidebar. Open AniBrave features (the sliders icon) → **Tab layout settings** → **Vertical** or **Horizontal**.
The choice is remembered; horizontal remains the default. The sidebar has a compact
header, Regular / Private switch, 48dp rows with native site favicons, a bottom action row and light/dark
appearance. Tab overview and AniBrave features live in that rail without duplicate
address-bar controls or a redundant rail More menu. Native horizontal tabs stay
hidden while scrolling. Its expanded width is 240dp. Immersive shortcuts
stay full-page. See [controls, implementation and QA](docs/vertical-tabs.md).

## Private signing configuration

The PowerShell build requires `ANIBRAVE_KEYSTORE` pointing to an existing private keystore and `ANIBRAVE_KS_PASS` supplied through the environment. `ANIBRAVE_KEY_ALIAS` defaults to `anibrave`; `ANIBRAVE_KEY_PASS` defaults to the store password. No signing key or password is generated or embedded in the build script. The Python feature-update tools accept `--keystore` and use the same password environment variables. Keep the signing identity stable for in-place updates; a new independent build needs its own privately generated key.

## Resume and speed synchronization

See [website resume and speed](docs/resume-and-speed.md) for shortcut behavior, native-player synchronization, recovery policy and validation scope. AniHome 0.8.16 adds Android pinned-shortcut confirmation support.

### Android regression checks

`python3 anibrave/tools/run_android_history_tests.py --serial <emulator-serial>`
runs real SQLite imports, rollback, persistence and stale-profile tab action checks in
an isolated, disposable QA package. Set `JAVA_HOME` and `ANDROID_HOME` (SDK 37.1,
build-tools 37.0.0). The runner installs/removes only `app.anibrave.qatests` and never
updates the browser or reads its profile. Its signing key is temporary and is not a
release key. Launcher integration checks are built with
`./gradlew :app:assembleDebugAndroidTest` and run with the configured
`RegressionInstrumentation` on a matching debug installation. They use a dedicated
QA cache database; they do not migrate the user's launcher database.

Private tabs never contribute to the normal watch library: tracking requires the
DevTools default browser context and a foreground normal-profile tab. Unknown
context identities fail closed. AniKoto slug fallback preserves alphabetic words;
opaque alphabetic suffixes can remain until the authoritative heading is available.

## Combined release

AniBrave 0.1.6 is included with AniHome 0.8.21 and desktop extension 0.1.3 in the
[AniProject 2026.10.21 release](https://github.com/akasumitlamba/AniProject/releases/tag/aniproject-2026.10.21).
The Android update retains the existing signing identity and profile.

## Streaming buffer

[Streaming buffer](docs/buffering.md) requests more media ahead across sites and provides a bounded, cancelable buffer-guard fallback after stalls. It preserves the site player and does not guarantee full-episode loading on unsupported players. Fullscreen shortcut tools omit shortcut creation because the current window is already immersive.

## Full engine build on Linux

Use `python3 anibrave/tools/build_engine.py --input <verified-official-arm64-apk> --output <signed-output.apk> --keystore <existing-private-key>`. Set JDK17 on PATH and the signing password environment described above. The tool reads `upstream.json` and `version.json`, verifies locked build tools and the upstream APK hash, isolates decoded caches by engine hash, applies version-pinned hooks, preserves native libraries and signs locally. SDK37.1/build-tools37.0.0 are required. Never reuse decoded outputs from another engine version.
