# AniProject

AniProject contains **AniHome**, an Android home-screen launcher, **AniBrave**, a Brave-based Android browser customization, and its desktop extension. Contributor and AI guidance is technical-only: see [CONTRIBUTING.md](CONTRIBUTING.md).

## Downloads

| Product | Download | Requirements |
| --- | --- | --- |
| AniHome 1.0.0 | [Download launcher](https://github.com/akasumitlamba/AniProject/releases/download/v1.0.0/AniHome-Launcher-1.0.0-release.apk) | Android 8+ |
| AniBrave 1.0.0 | [Download browser](https://github.com/akasumitlamba/AniProject/releases/download/v1.0.0/AniBrave-1.0.0-arm64-v8a.apk) | Android 10+, ARM64 |
| AniBrave desktop 1.0.0 | [Download extension](https://github.com/akasumitlamba/AniProject/releases/download/v1.0.0/AniBrave-Extension-1.0.0.zip) | Brave desktop |

[Download all three applications together](https://github.com/akasumitlamba/AniProject/releases/download/v1.0.0/AniProject-1.0.0-All-Products.zip), or use the individual downloads above.

For updates, install the matching signed APK over the existing app. Keep the same signing identity; do not uninstall or clear app data.

## AniHome

![AniHome emulator preview with sample banners](docs/screenshots/anihome-tablet.png)

[AniHome](launcher/README.md) combines apps and websites on the system wallpaper, with custom banner artwork, widgets, a searchable adaptive app drawer, recent files, configurable gestures and an inline notification/quick-settings sidebar. Wi-Fi and Bluetooth sections support scrolling lists and direct operations through optional Shizuku access. Rotation toggles Auto and Fixed, locking the current display angle. Gesture-driven panels use short directional animations. Notification counts use contrasting corner tags.

The [Banner Store](banners/README.md) includes 177 named banners. It works offline and refreshes anonymously from this public repository. No GitHub account or token is needed. Apps retain local settings, tiles and connection recency; notifications are not uploaded.

```sh
cd launcher
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleDebug
```

## AniBrave

![AniBrave emulator preview with local fixture pages](docs/screenshots/anibrave-vertical-tabs.png)

[AniBrave](anibrave/README.md) combines the official Brave 1.98.60 ARM64 engine with Java/JavaScript controls for saved playback speed, site viewing modes, immersive website shortcuts, vertical tabs, buffer-ahead preferences and local watch history. Anime reminders and watch logs stay on-device. Browser tabs and immersive shortcuts share the browser profile.

This is an experimental APK customization, not a complete Chromium source build or an official Brave product. Protected streaming and device-specific radio/biometric behavior require hardware validation. Screenshots above use emulator fixtures and are labeled by capture version in [screenshot notes](docs/screenshots/README.md).

## Source layout

- `launcher/`: native Android HOME app, package `app.anihome.launcher`.
- `anibrave/`: browser customization and packaging, package `app.anibrave.main`.
- `banners/`: artwork, catalog, public source references and contribution guide.
- `.github/workflows/`: launcher, browser and source privacy checks.
- `tools/`: tracked-source privacy guard.

## Privacy and licensing

This public repository contains source and technical documentation. Signing keys, credentials, private conversations, device backups and personal captures are excluded. Build signing uses private local configuration. See [contribution guidelines](CONTRIBUTING.md).

Customization sources are MPL-2.0. Upstream notices and licenses remain intact. Third-party trademarks belong to their respective owners; AniProject is independent of Brave Software and the services represented in its banner catalog.

## Launch release

All active products share version **1.0.0**: AniHome, AniBrave Android and the AniBrave desktop extension. [Download the complete release](https://github.com/akasumitlamba/AniProject/releases/tag/v1.0.0). Each release includes both APKs, the unpacked extension ZIP, banners, checksums and an all-products bundle. See [release policy](RELEASING.md) and [consolidated history](CHANGELOG.md).

The [1.0.0 launch notes](docs/releases/1.0.0.md) provide the complete feature guide, setup instructions, privacy/permission boundaries, upgrade details and validation limits.
