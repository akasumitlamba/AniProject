# AniHome Launcher

AniHome is an Android HOME launcher with a TV-style grid of apps and websites over the system wallpaper. Websites open through AniBrave's immersive shortcut activity, sharing its browser profile. Package: `app.anihome.launcher`; Android 8.0+; current source version 0.8.18.

## Home and app drawer

- Android pinned shortcuts are supported: confirm Add to Home in the requesting browser/app. Shortcuts retain their original app destination, including fullscreen browser launches, and remain separate from normal app tiles.
- App/site banners support custom artwork, drag ordering, optional names and contrasting notification-count tags.
- Tap the dock clock/date for notifications. Google/search sits beside it; remaining room shows chosen app pins. Add appears only in edit mode. Settings can hide the Recents button independently of gestures, choose the dock browser with its matching icon, or hide search entirely. Pinned icons use a consistent circular mask. Landscape uses a side dock on phones and tablets.
- Double tap empty Home to lock (optional Accessibility control); long press empty Home, including margins, to enter Edit Home. Drag banners or hold a widget’s Edit header to rearrange; tap the dock checkmark to finish. Double tap is disabled while editing and can be turned off in Gestures.
- Configure up, down, left and right swipes independently. Tablet defaults: up opens Recents, left opens Apps, right opens Notifications, down has no action. Phone defaults: up opens Apps and down opens Notifications; horizontal swipes have no action. Saved choices are preserved.
- Phone drawer: a full-width app grid adapts to cell width and text size, with a separated suggestion row on tall screens. Recent files expands from an integrated handle/header to half the usable screen. Each file offers Open, Share and File info; sharing opens Android’s chooser without sending automatically.
- Tablet portrait drawer: Frequent apps and Recent files occupy the left column; All applications occupies the wider right column. Frequent apps uses two columns, All applications four. Landscape uses Favorites / All applications / Recent files.
- Search filters apps and files. Hold an app for Add to Home, Hide/Restore, Uninstall or App info. Batch adding apps/websites preserves existing tiles.
- Widgets share Home's continuous scroll. Android provider widgets and built-in Clock/date, Battery and Quick note are supported. Notes stay local.
- Home settings include layout columns, names, rotation, dock preferences and gestures. Status bars are hidden on Home; system navigation remains available.

## Validation — 20 October 2026

Version 0.8.18 passed 100 JVM tests, release lint, and debug/release assembly. Physical-phone checks covered portrait/landscape rotation, the landscape side dock and 360dp sidebar cap, app-grid geometry, dock pin selection/persistence/launch, and recent-file expansion, metadata and Android sharing. Tablet-emulator checks covered both drawer orientations. Dock browser selection, browser launch, search visibility and preference persistence were checked in the emulator.

Auto mode enables Android sensor rotation; physical sensor movement and every vendor’s firmware have not been exhaustively tested. Another app that forces orientation can override AniHome’s requests. System-wide rotation needs the optional Android modify-system-settings permission; local launcher rotation does not.

## Notifications and quick settings

The left sidebar attaches to the screen edge. Live notifications remain in memory; app notifications are never uploaded or archived. Tap outside the sidebar to close it. Expand/collapse, notification actions, swipe/button dismissal and Clear all respect Android's ongoing/non-clearable alerts. Dismissing the final child also clears its dismissible group summary. Cancellation is reconciled with Android’s active notifications, and a refused dismissal stays visible with feedback. Notification access is optional.

Wi-Fi and Bluetooth expand inside the sidebar with labeled headers, refresh and power controls. Connected/recent devices sort first; long lists scroll. Rotation cycles Portrait → Landscape → Auto without a popup. Home settings expand beside the radio controls. Landscape panels are capped at 360dp instead of spanning a phone’s width.

Direct radio operations use an optional, narrowly scoped Shizuku service. Android permissions, system Location, pairing approval and profile support still apply. See [radio setup](docs/radio-controls.md) and [validation scope](docs/quick-settings-validation.md). Accessibility controls for Recents and screen lock are optional and do not request screen-content access.

## Artwork and local data

Long-press a banner → Choose tile image to use Gallery, crop artwork, or browse the [bundled Banner Store](../banners/README.md). The store contains 159 named banners, supports search/preview, and applies only after confirmation. Offline use requires no account. Explicit Refresh requests the public AniProject catalog anonymously. No account or token is needed, and credentials from earlier private-catalog builds are discarded.

Notification counts, app frequencies, connection recency, tiles and notes stay local. Scan lists and Wi-Fi passwords are not persisted by AniHome. Android manages saved Wi-Fi credentials. Recent files use MediaStore and previously authorized document folders; the app requests access only when needed.

Watch tracking belongs to AniBrave. Legacy launcher history is preserved for an optional confirmed local transfer, described in [tracking and migration](../anibrave/TRACKING.md).

## Build

Requires JDK 17, Android SDK platform/build-tools 35 and Gradle 8.9 (wrapper included). Set `ANDROID_HOME` or an ignored `local.properties` with `sdk.dir`.

```sh
cd launcher
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleDebug
```

Release signing is optional for local builds. To sign, configure `ANIHOME_KEYSTORE`, `ANIHOME_KEYSTORE_PASSWORD`, `ANIHOME_KEY_ALIAS` and `ANIHOME_KEY_PASSWORD` privately, or use an ignored `launcher/keystore.properties`. Never commit signing material. Installed releases require the same signing identity for updates; use `adb install -r` to preserve data.

Public release downloads are listed in the [root README](../README.md). Release-specific notes in `docs/` distinguish automated/emulator checks from hardware acceptance. See [contribution guidelines](../CONTRIBUTING.md).

License: MPL-2.0.
