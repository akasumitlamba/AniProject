# Protected playback and Widevine

Protected streaming may require Widevine to be enabled in AniBrave's native browser settings. Open **Menu → Settings → Media → Widevine DRM**, enable it if requested, and follow the relaunch prompt. See [Brave's Android Widevine setup](https://support.brave.com/hc/en-us/articles/17428756610061-How-do-I-enable-Widevine-DRM-on-Android).

A playback error such as KAT-6005 has multiple possible causes. Verify the native DRM preference, actual media error and EME capability before changing application code. Support varies by hardware, firmware and service. A synthetic video or successful EME request alone does not establish protected-playback compatibility; verify that the real video clock and decoded frames advance.

Preserve the browser profile, cookies, history and signing identity during repairs. Do not clear storage or DRM data as a routine first step. Respect paused playback. Do not capture authentication headers, cookies, license bodies, protected video or personal episode URLs in public diagnostics.

Remove temporary ADB forwarding after debugging. AniBrave's app-protected local controller socket remains part of its normal operation.
