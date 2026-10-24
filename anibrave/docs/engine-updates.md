# Brave engine updates

AniBrave is a separately signed app. Installing official Brave updates does not update
AniBrave. Its tested upstream engine is pinned in `anibrave/upstream.json`; the build
reads that tag and verifies the APK against the pinned SHA-256. Updating AniBrave's
feature DEX alone does not upgrade Chromium or Brave security fixes.

## Automated preparation

The **Brave upstream update check** GitHub Actions workflow runs daily and can be
started manually. It uses the official stable release API, validates the ARM64 APK
URL/size/upstream SHA-256, and compares semantic versions. Older/same releases and
prereleases do not create proposals. Missing digests or unexpected assets fail the
check instead of trusting an unverified download.

A newer release creates one draft PR with `anibrave/upstream-candidate.json`. While
an engine proposal is open, the bot does not open more. A closed proposal for the
same version is not recreated. Current controller/Python tests run before a proposal;
these baseline checks are **not** proof that the proposed engine supports our hooks.
The installed app and tested production pin remain unchanged. No APK is built,
signed, auto-merged, published or installed by this workflow. The configured maintenance repository owns update proposals; avoid duplicate
automation when publishing a separate source mirror.

## Completing an upgrade

The native integration is pinned to Brave's internal Java/smali APIs, obfuscated
members and resource IDs. An upstream update can change those APIs even when the
custom Java/JavaScript compiles. A maintainer must:

1. Verify the official candidate APK hash and engine identity; adapt native hooks,
   tab/strip models and resource lookups for that version.
2. Promote the reviewed candidate tag/hash into `upstream.json`, remove cached decoded
   outputs from previous engines and build with current AniBrave features.
3. Run controller/Java/privacy checks and matching-engine emulator checks for normal
   tabs, horizontal/vertical layouts, page dismissal, shortcuts/Recents/resume,
   playback speed ownership and native player synchronization. Check actual device
   startup and protected streaming separately.
4. Sign locally with the existing private key, preserve the profile through an
   in-place update, then use the reviewed release/public-export process.

The signing key stays local; no production signing secret is required by this
workflow. Fully unattended feature-preserving engine upgrades are not implemented.
Android installation also remains an explicit user action.

The repository must allow GitHub Actions to create pull requests. The workflow
requests only repository contents and pull-request write permissions for its proposal
job. Bot-created PR checks may require approval; the proposal job's baseline tests
run independently of that PR event.

Sources: [GitHub release API](https://docs.github.com/en/rest/releases/releases),
[scheduled workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule).


## Tested engine: 1.98.60

AniBrave 0.2.1 uses official Brave 1.98.60 / Chromium 156.0.8078.48.
The official release was published on 21 October 2026. The ARM64 input is pinned
by its GitHub asset SHA-256; its native libraries are retained byte-for-byte.
This is a full engine update, not a feature-DEX-only security update.

This upgrade adapts the generated native server initialization slot, socket
prefix factory, native-ready shortcut callback, current-tab/selector access,
Undo-safe tab removal, toolbar resources and compositor visibility API.
The compositor flag supplier is read through its public Supplier interface,
avoiding dependence on its changed internal storage field. Integration still
uses version-pinned native APIs and must be checked again for future releases.

The release API identifies the newer engine, but does not enumerate a specific
incident or CVE in its release body. This update does not claim to remediate an
unverified reported breach. Sources: [official release](https://github.com/brave/brave-browser/releases/tag/v1.98.60)
and [matching Brave source](https://github.com/brave/brave-core/tree/v1.98.60).


Validation for this upgrade: 67 JavaScript tests, 15 Python tests, Java identity,
URL, privacy and tab-layout policies, 12 native-strip policy/capture checks and
44 real Android SQLite checks. Matching-version emulator runtime checks cover
native tabs/private tabs, selection/close/Undo, speed ownership and synchronization,
buffer settings, vertical/horizontal layouts and immersive shortcuts. These do
not establish protected signed-in streaming compatibility on every site/device.
