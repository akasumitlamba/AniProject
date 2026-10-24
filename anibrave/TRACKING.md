# Local watched history in AniBrave

Anime tracking belongs in AniBrave.
AniHome is the app/website launcher and retains its old database only for an
explicit local migration into the browser.

## Watching and reminders

AniBrave reads series titles and real episode numbers from Crunchyroll, HiAnime
and AniKoto watch pages. HiAnime's `?ep=` is an internal ID and is never treated
as an episode number. AniKoto's `/watch/{slug}/ep-N` contains the real episode.
Crunchyroll uses its series link and episode heading.

Opening a page can show the existing reminder; opening alone does not add a
watch log entry. A video must advance for at least 15 seconds of actual playing
time. Paused video, buffering, seeks and short media do not accumulate watched
time. Embedded players report progress through AniBrave's existing isolated
browser controller; the top watch page supplies the anime identity. Only the
currently open browser page can update the log. Third-party players without
accessible HTML video progress cannot be recorded automatically.

The compact native continuation card shows the series, season/part, episode and
saved timestamp. On an identified series/watch page it offers that series' local
history; a supported homepage can offer one recent cross-site episode. Unrelated
pages stay quiet. Ordinary offers hide after 12 seconds (extended for Android
accessibility) and remain dismissed for that episode; checkpoints do not revive
them. A different episode can create a new offer.

**Continue here** uses a learned exact URL first. Otherwise it searches the target
site and follows only a unique exact title and matching season/episode. Ambiguous
results and missing metadata require manual navigation and **Confirm episode**;
identifiers are never guessed. **Open previous site** uses the saved source URL.
An explicit handoff belongs to the native browser tab and survives navigation,
reload and process restart for 30 minutes. **Done** or the dismiss control clears
it. A seekable matching player restores position and per-site speed without
starting playback or changing the site's view mode. Unsupported players retain
manual guidance. This does not bypass DRM or provide downloading.

`Watched anime` opens the local episode archive with separate season/part records,
partial position, watched intervals and sticky completion at 85% actual coverage.
Rewatching an earlier episode never erases completed later episodes. The archive
supports correcting title/episode/time, deleting one episode, deleting all history,
and opening the dated legacy watch log. Explicit confirmations learn local title
aliases, numbering offsets and exact episode URLs. There is no remote catalog,
server or cloud lookup. Private tabs neither read nor write normal continuation
history; the native card is removed before a tab/profile transition is drawn.

## Storage and migration

The original `anibrave-watch.db` and its `anime`, `site_progress` and
`episode_log` tables remain intact. The sidecar `anibrave-continuation.db` imports
legacy records idempotently and stores episode coverage, positions, metadata,
learned links/aliases, dismissals and tab-scoped pending handoffs. Legacy timestamps
and log records are preserved; old completion is never guessed. Both databases
remain in AniBrave's private app storage. Browser backup is disabled in
the feature APK so Android does not copy the history to cloud backup.

AniHome explicitly targets `app.anibrave.HistoryImportActivity` with
`ACTION_SEND`, `application/json`, a read-granted content URI under
`app.anihome.launcher.history-transfer`, and `startActivityForResult`. AniBrave
checks the calling package and URI authority, asks the user to confirm import, reads
off the UI thread and commits all rows in one transaction. The JSON schema is
version 1, source `app.anihome.launcher`, with `anime`, `site_progress` and
`episode_log` arrays carrying the prior SQLite column names.

The importer preserves exported keys and millisecond timestamps, ignores old
row IDs, merges newer summaries without overwriting newer browser records and
deduplicates logs by anime key, episode, host and timestamp. Input is capped at
16 MiB, 10,000 anime, 30,000 site rows and 100,000 log rows. Invalid data rolls
back the transaction. AniHome keeps its original database, including after
successful import. This transfer never invokes a generic share chooser or
uploads history.

## Build and checks

AniBrave 0.2.1 uses the pinned Brave 1.98.60 / Chromium 155 engine. Linux
`tools/build_engine.py` builds the complete verified APK. Feature-only updates can
use `tools/build_feature_update.py` with the original signing key; all four JS
assets and the compiled feature DEX must match the source. APKs, keys and passwords
stay outside Git.

Run `node --test anibrave/tests/*.test.cjs`, the Python policy suite, and the Java
policy tests in `.github/workflows/source-checks.yml`. Real SQLite migration,
coverage, deletion, alias and handoff checks run in a disposable Android package:
`python3 anibrave/tools/run_android_continuation_tests.py --serial DEVICE --sdk SDK
--java-home JDK`. This never opens production browser data.

Controlled real-media fixtures validate seek/navigation behavior separately from
live signed-in site acceptance. Protected/native players, inaccessible cross-origin
media, site DOM changes and ambiguous catalog results can require manual action;
fixture success does not establish universal streaming or DRM compatibility.
