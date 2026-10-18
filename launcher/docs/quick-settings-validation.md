# Quick settings validation and limits

AniHome's quick settings are native sections inside the notification sidebar. Wi-Fi and Bluetooth lists scroll and prioritize connected/recent entries. Rotation cycles Portrait → Landscape → Auto. See [direct radio setup](radio-controls.md).

## Automated and emulator coverage

Versioned checks cover stable ordering of long connection lists; rotation locks on natural-portrait and natural-landscape displays; UTF-8 SSID limits; WPA2 passphrase/PSK validation; and settings/gesture policy. Release builds run Android lint and signed assembly separately from unit checks.

Android 11 emulator validation has exercised real Shizuku authorization and user-service binding, Wi-Fi refresh, saved-network selection, power toggles, unavailable-manager recovery, inline section layout, rotation, permission denial and return, notification actions/counts, and phone/tablet geometries. Synthetic notifications and files are used for public evidence.

In-place update checks verify preservation of application data and the default HOME role. A successful scan or accepted connection request is distinguished from completed network/audio association.

## Hardware acceptance

Physical Bluetooth discovery, new pairing, audio-profile reconnection, secured-network association, crowded device lists and OEM rotation behavior require hardware checks. Android pairing may require system confirmation. Apps that enforce their own orientation can override system rotation. Shizuku provides narrowly scoped shell-identity operations; ordinary-app fallback remains subject to Android restrictions.

Nearby-device permissions on Android 12+, scan permissions/Location on older Android, and firmware-specific behavior need OS-specific validation. Emulator checks do not prove every hardware path works. Unavailable or failed connections must show a retryable state rather than silently navigating out of the sidebar.

No notification history, scan list or Wi-Fi password is persisted by AniHome. Only observed connection identifiers and timestamps are retained locally for ordering. Android manages saved network credentials. Public diagnostics must omit real network names, Bluetooth addresses, notifications and device identifiers.
