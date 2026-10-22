# Episode continuation validation — 0.2.0

This update retains Brave 1.97.59 / Chromium 155.0.8059.85 and the existing
application/signing identity. It adds local episode continuation rather than
changing the engine or replacing website players.

## Automated checks

- 67 JavaScript checks cover playback controls, tracking, conservative matching,
  ambiguous search results, known ads, seeks, paused media, resume cancellation,
  manual search, privacy and reload handoffs.
- 44 real Android SQLite checks cover legacy migration, episode/season separation,
  interval coverage and sticky completion, aliases/numbering offsets, exact URL
  learning, dismissal, handoff expiry, correction and deletion. They run in a
  disposable app, independently of the browser profile.
- The existing Java policies and 13 Python integration/resource checks pass.
- Twelve desktop Brave tests run the Android JavaScript with actual seekable
  WebM media. These exercise helpers; they do not establish native Android UI,
  profile or protected-player behavior.

## Physical Android checks

Controlled, uniquely identified fixture pages exercised the signed ARM64 browser:

- AniKoto to Crunchyroll: native Continue here searched, selected the unique
  series and Season 2 Episode 5 over Season 1 Episode 5, then restored 5:13
  at 1.5× without autoplay.
- Reload of the retained explicit handoff restored the same position and speed.
- Crunchyroll to AniKoto used the learned exact URL and restored 7:12 at 1.5×,
  paused. Done removed the card; an unrelated series remained quiet.
- Missing episode labels caused no guessed seek. Explicit native confirmation
  restored the saved episode, and the learned URL worked after reload.
- Switching to the native private profile removed the normal card. The same
  URL in a private tab did not expose normal continuation; the private history
  entry point denied access.

## Boundaries

These are controlled fixture results, not a claim of universal site support.
Authenticated protected streaming, every live catalog DOM, inaccessible players,
unknown ad implementations and all hardware configurations are not proven.
Ambiguous results require manual selection; unavailable/nonseekable players
retain manual guidance. Resume never forces playback or bypasses DRM.
