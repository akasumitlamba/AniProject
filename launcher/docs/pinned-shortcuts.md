# Pinned shortcuts

AniHome 0.8.16 implements Android's `CONFIRM_PIN_SHORTCUT` launcher activity. It uses a separate task affinity so Android's pin-request task flags cannot clear or replace the Home task.

The launcher accepts only a valid platform `LauncherApps.PinItemRequest` after Add confirmation. Cancel does not create a tile. Package, shortcut ID and Android profile serial identify the tile; Android retains the original destination intents and extras. Tapping uses `LauncherApps.startShortcut`, preserving browser fullscreen shortcut behavior rather than opening the browser's generic main activity. A disabled shortcut or unavailable profile shows an error instead of silently opening another destination.

Shortcut icons are stored locally; duplicate identities update the existing tile. Shortcuts remain separate from regular app tiles. Removing a shortcut updates Android's pinned IDs so it can be requested again. The database upgrades existing Home data in place with nullable shortcut columns. No notification, browser profile or website login data is copied.

Validation: 96 JVM checks, release lint and debug/signed release assembly pass. An isolated Android emulator confirms supported status, confirmation, cancellation, exact destination URI and fullscreen extra, launcher restart persistence, duplicate prevention, remove/re-pin behavior and rejection of a request without a platform token. The flow uses a synthetic provider implementing the same Android pin API as the browser; real browser engine/streaming behavior is a separate acceptance check.

Release APKs exclude Gradle VCS provenance and retain the existing signing identity for compatible updates.
