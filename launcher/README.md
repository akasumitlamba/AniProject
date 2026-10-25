# AniHome Launcher

AniHome is an Android HOME launcher with a TV-style grid of apps and websites over the system wallpaper. Websites open through AniBrave's immersive shortcut activity, sharing its browser profile. Package: `app.anihome.launcher`; Android 8.0+; current source version 0.8.25.

## Home and app drawer

- Android pinned shortcuts are supported: confirm Add to Home in the requesting browser/app. Shortcuts retain their original app destination, including fullscreen browser launches, and remain separate from normal app tiles.
- App/site banners support custom artwork, drag ordering, optional names and contrasting notification-count tags.
- Tap the dock clock/date for notifications. The count sits next to the time, with the date below. Battery percentage appears beside the clock in a bottom dock and below it in a side dock, with a separate gold charging bolt while actively charging. Its level fill and outline are red at 20% or below, yellow at 21–49%, and green from 50%. The USB icon appears only while USB is connected. Google/search sits beside it; remaining room shows chosen app pins. Add appears only in edit mode. Settings can hide the Recents button independently of gestures, choose the dock browser with its matching icon, or hide search entirely. Pinned icons use a consistent circular mask. Landscape uses a side dock on phones and tablets. The app-menu button has solid grid blocks inside a transparent outlined circle.
- Double tap empty Home to lock (optional Accessibility control); long press empty Home, including margins, to enter Edit Home. Drag banners, or drag a widget’s centre in Edit mode, to rearrange. Long press a widget to enter editing; its edges resize directly and its only control is Remove. Tap empty Home or the dock checkmark to finish. Double tap is disabled while editing and can be turned off in Gestures.
- Configure up, down, left and right swipes independently. Tablet defaults: up opens Recents, left opens Apps, right opens Notifications, down has no action. Phone defaults: up opens Apps and down opens Notifications; horizontal swipes have no action. Saved choices are preserved.
- Phone drawer: a full-width app grid adapts to cell width and text size, with a separated suggestion row on tall screens. Recent files expands from an integrated handle/header to half the usable screen. Each file offers Open, Share and File info; sharing opens Android’s chooser without sending automatically.
- Tablet drawer: Recent files occupies the left column. The wider right column has one horizontally scrollable Favorites row above the independently scrolling All applications grid. Portrait keeps four app columns.
- Search filters apps and files. Hold an app for Add to Home, Hide/Restore, Uninstall or App info. Batch adding apps/websites preserves existing tiles.
- Widgets and banners share one persistent cell layout, including intentional empty spaces. Android provider widgets and built-in Clock/date, Battery and Quick note are supported. Notes stay local.
- Home settings include layout columns, names, rotation, dock preferences and gestures. Status bars are hidden on Home; system navigation remains available.

## Validation — 21 October 2026

Version 0.8.22 passed 108 JVM tests, release lint, and signed release assembly. Earlier 0.8.19 physical-phone checks covered portrait/landscape rotation, the landscape side dock and 360dp sidebar cap, app-grid geometry, dock pin selection/persistence/launch, and recent-file expansion, metadata and Android sharing. Earlier tablet-emulator checks covered both drawer orientations. Dock browser selection, browser launch, search visibility and preference persistence were checked in the emulator.

Auto mode enables Android sensor rotation; physical sensor movement and every vendor’s firmware have not been exhaustively tested. System-wide rotation needs optional Android modify-system-settings access. Direct Shizuku controls additionally lock the display against app and Recents orientation requests; Auto releases that display lock. Unsupported firmware reports an error instead of assuming enforcement succeeded. Local launcher rotation does not need extra access.

## Notifications and quick settings

The left sidebar attaches to the screen edge. Live notifications remain in memory; app notifications are never uploaded or archived. Tap outside the sidebar to close it. Expand/collapse, notification actions, swipe/button dismissal and Clear all respect Android's ongoing/non-clearable alerts. Dismissing the final child also clears its dismissible group summary. Cancellation is reconciled with Android’s active notifications, and a refused dismissal stays visible with feedback. Notification access is optional.

Wi-Fi and Bluetooth expand inside the sidebar with labeled headers, refresh and power controls. Connected/recent devices sort first; long lists scroll. Rotation toggles Auto / Fixed without a popup. Fixed captures the current display angle, including reverse landscape, and preserves it after activity recreation. Previously saved Portrait/Landscape preferences remain supported until changed. Home settings expand beside the radio controls. Landscape panels are capped at 360dp instead of spanning a phone’s width.

Direct radio operations use an optional, narrowly scoped Shizuku service. Android permissions, system Location, pairing approval and profile support still apply. See [radio setup](docs/radio-controls.md) and [validation boundaries](../PROJECT-CONTEXT.md). Accessibility controls for Recents and screen lock are optional and do not request screen-content access.

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

The dock clock grows when neither charging nor USB is active. The app-menu outline is inset equally within its touch target.

Version 0.8.22 adds a shared compact time/count row and date row in either dock layout, plus optional direct system rotation enforcement against app-requested orientation.

Version 0.8.22 adds rounded phone Recent files corners, 160–190 ms gesture-directed opening and expansion motion, animated notification list removal, and long-press notification snoozing/channel settings. Animations respect Android’s animator setting. Wi-Fi and Bluetooth retain inline discovery/connection controls; their options buttons open Android’s advanced radio settings. Emulator checks covered the new rotation toggle/current-angle lock, notification snoozing and radio options. Physical-device validation of this version remains pending.

Version 0.8.22 adds widget edge resizing, a compact widget menu, blank-area tap to finish Home editing, and circular dock artwork with consistent spacing. QA repairs preserve Home drag order across asynchronous reloads and completed legacy seed migrations, retain shortcut tiles when Android refuses unpinning, keep limited Wi-Fi scan feedback, and cancel pending Bluetooth UI state when radio/profile services disappear. Portrait tablet Frequent apps retain two columns; the clock adapts to available badge and power-indicator space.

Banner artwork and notification tags share one clipped surface to avoid colour fringes at the outer corner. Edit-mode remove controls overlap the corner using a neutral glass treatment, outside the clipped artwork.

Version 0.8.23 passed 114 JVM tests, release lint and signed assembly. Emulator checks cover live app install/removal while the drawer remains open, widget long-press editing, direct edge resizing, movement between banners and into empty cells, persistence after process restart, a bound Android provider widget, and safe blank-area editing gestures. App discovery refreshes on every drawer opening and observes package changes without relying on a manifest broadcast alone.

Version 0.8.24 places a counted message control above Apps in the side dock and at the left end of the portrait dock, and enlarges the side-dock clock. Drawer entry waits for the app list, then uses a 280 ms directional transition. Recent files render visible rows only; Home prepares app icons in the background. Release validation: 114 JVM tests, release lint and signed assembly.

Version 0.8.25 improves dock contrast, empty/edit guidance, reversible ordinary tile removal, press/edit motion and new-tile feedback, local launcher search with web fallback, default-home prompt dismissal, drawer visibility feedback and setting summaries. It adds an optional Home layout lock while preserving the side dock, inline sidebar settings and user gesture choices.

Newly added application tiles automatically use verified public-catalog banners on exact package matches, with application-icon fallback. Manual image choices and resets are persisted and protected against late automatic responses for the lifetime of that Home tile. Existing tiles retain their artwork.
