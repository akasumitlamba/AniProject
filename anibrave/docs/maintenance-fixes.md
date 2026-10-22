# AniBrave 0.1.6 maintenance fixes

The Brave 1.97.59 full-engine integration now suppresses the upstream fullscreen
shortcut exit overlay at its attachment point. Initialization, permissions, system
Back and website video controls remain intact. A fail-closed transformation test
requires the expected upstream attachment; changing the engine must revalidate it.
The native-ready callback also starts page controls on a cold shortcut-only launch,
without requiring the normal browser to be opened first.

Vertical tabs delegate strip-height transitions to the pinned native coordinator.
AniBrave no longer registers a second toolbar capture callback or bypasses the
native minimum-height protocol. Pre-draw reconciles strip visibility without
queuing a height capture each frame. Temporarily unattached compositor owners are
retried, and favicons are copied and compared within per-icon error isolation.
Desktop viewport metrics follow the available content width beside the rail.

Watch-page comparison tolerates equivalent host aliases, trailing slashes and
percent encoding while preserving episode paths, queries, ports and hash routes.
Crunchyroll compact/spelled-out season labels retain season identity. Smaller
trailers and recognized ads cannot advance the main episode's watch clock.
Revisiting an episode within the same six-hour site sitting updates its existing
log row. Imports preserve a local same-episode reminder dismissal. A temporary
missing address does not hide the current reminder.

Shortcut resume observations are persisted per Android task, with bounded local
storage. An invalid address read or an unchanged older task cannot replace the
newer site's saved destination. Explicit site speed choices are accepted while
metadata is loading; automatic resets remain suppressed.

Desktop extension 0.1.4 independently fixes automatic speed adoption, audio
selection, large-page shadow-player discovery, watch-clock resets and HiAnime
homepage versus series identification. Updating the Android APK does not update
a desktop extension; reload the separately updated extension and player tabs.

Validation includes controller and policy regressions, real Android SQLite tests,
matching-engine emulator rendering and connected-tablet shortcut checks. This does
not establish universal protected-streaming or every device configuration support.
