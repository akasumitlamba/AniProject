# Direct radio controls with Shizuku (0.8.3)

AniHome can use the official [Shizuku](https://shizuku.rikka.app/) service to perform the Wi-Fi
operations Android blocks for ordinary launchers. Shizuku runs the narrowly scoped AniHome radio
service as the ADB/shell identity. The launcher does not expose arbitrary commands, root shells,
file operations or device-wide API access through that service.

Install/start Shizuku, open AniHome's Wi-Fi section, tap the key icon and allow AniHome access.
The key disappears when the radio service is ready. Saved personal
networks connect on selection; new personal networks accept a password in the sidebar and save
it through Android's Wi-Fi manager. WPA2 passphrases and raw 64-digit hexadecimal PSKs are
validated. Existing compatible enterprise profiles can be selected; new enterprise/WEP profiles
still need Android's configuration screen. Connection failure and authentication rejection are
shown instead of silently doing nothing. Invalid credentials can be corrected by tapping again.

Refresh uses the privileged scan API when available and reports rejected requests or a timeout.
It never pretends cached results are a fresh scan. The refresh vector is a single standard filled
path. The icon-only toolbar stays pinned above a bounded scrolling device list; list height wraps its content,
with consistent content padding, row gaps and compact icon targets.

Wi-Fi power can be changed directly through the service. Bluetooth uses Android's normal pairing
flow for new devices; the privileged service requests music and call-profile connections for paired audio devices.
This is not a universal connection implementation for every Bluetooth profile. Unsupported or
failed requests show a retryable status in the sidebar. Missing/stopped/revoked Shizuku access
shows setup/retry beside the key icon; device selection never automatically opens Android Settings.
There is no external-settings gear in the radio toolbar. First pairing can require Android’s confirmation. No notification or password data is sent
to a network service, and AniHome never saves passwords itself. Android saves configured networks.

## Restart after reboot

On non-rooted Android 10 devices, Shizuku must be restarted from a computer
after each reboot. Authorization remains saved. With USB debugging connected:

```
python3 launcher/tools/start_radio_controls.py --serial TABLET_SERIAL --adb /path/to/adb
```

The script discovers Shizuku's installed path each time rather than hard-coding its APK directory.
It uses the native starter included with official Shizuku 13.6.0. Shizuku's own View command is also
available. Android 11+ can use Shizuku's on-device wireless debugging setup instead. This release
does not promise a reboot-persistent privileged service without root.

## Validation scope

Tests cover connection ordering, system rotation, UTF-8 SSID limits, WPA2 passphrase boundaries, PSKs and escaping. Emulator checks exercise Shizuku authorization, user-service binding, refresh, saved-network selection, radio toggles and unavailable-service recovery. New secured-network association, Bluetooth audio profiles and OEM behavior require physical hardware acceptance.

Profile services bind from the ordinary AniHome process. The privileged service accepts validated Android A2DP/headset interface binders and discovers the runtime connect transaction. It does not expose arbitrary shell commands or bind application services from Shizuku's non-application process.
