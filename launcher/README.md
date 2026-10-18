# AniHome Launcher

AniHome is an Android HOME launcher with a TV-style grid of apps and websites over the system wallpaper. Websites open through AniBrave's immersive shortcut activity, sharing its browser profile. Package: `app.anihome.launcher`; Android 8.0+; current source version 0.8.15.

## Home and app drawer

- App/site banners support custom artwork, drag ordering, optional names and contrasting notification-count tags.
- The dock includes a clock/date, apps, notifications and optional Recent apps. Settings can hide the Recents button independently of gestures.
- Double tap empty Home to lock (optional Accessibility control); long press empty Home, including margins, to enter Edit Home. Drag banners or hold a widget’s Edit header to rearrange; tap the dock checkmark to finish. Double tap is disabled while editing and can be turned off in Gestures.
- Configure up, down, left and right swipes independently. Defaults: up opens Recents, left opens Apps, right opens Notifications, down has no action.
- Portrait drawer: Frequent apps and Recent files occupy the left column; All applications occupies the wider right column. Frequent apps uses two columns, All applications four. Landscape uses Favorites / All applications / Recent files.
- Search filters apps and files. Hold an app for Add to Home, Hide/Restore, Uninstall or App info. Batch adding apps/websites preserves existing tiles.
- Widgets share Home's continuous scroll. Android provider widgets and built-in Clock/date, Battery and Quick note are supported. Notes stay local.
- Home settings include layout columns, names, editing, dock preferences and gestures. Status bars are hidden on Home; system navigation remains available.

## Notifications and quick settings

The left sidebar attaches to the screen edge. Live notifications remain in memory; app notifications are never uploaded or archived. Tap outside the sidebar to close it. Expand/collapse, notification actions, swipe/button dismissal and Clear all respect Android's ongoing/non-clearable alerts. Dismissing the final child also clears its dismissible group summary. Cancellation is reconciled with Android’s active notifications, and a refused dismissal stays visible with feedback. Notification access is optional.

Wi-Fi and Bluetooth expand inside the sidebar with labeled headers, refresh and power controls. Connected/recent devices sort first; long lists scroll. Rotation cycles Portrait → Landscape → Auto without a popup. Home settings expand beside the radio controls.

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
