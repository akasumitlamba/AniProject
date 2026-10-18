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

The reminder reads `You were on episode N of TITLE`. It hides automatically after eight seconds
(or longer when Android accessibility settings recommend more reading time). Repeated progress
signals on the same page/episode do not restart the timer or revive the hidden card. A new visit,
another anime or another watched episode can show a fresh card. Timeout only hides the UI; it
never changes stored dismissal or watched history. Navigation/pause removes pending callbacks.
Its X explicitly dismisses it until another episode is watched. Tapping
the text opens a local log with date, time, site and episode. Watching a different
episode of that anime on any supported site updates the latest episode and
revives the reminder, including earlier and future episodes. Watching the same
episode again preserves dismissal. There is no next-episode navigation action.

`Watched anime` in the native browser tools opens the local library. Repeated
signals for the same anime, episode and site within six hours are one sitting.
Titles use the prior AniHome normalization rules, including season suffixes.
Different translations or aliases can still need identity matching; there is no
remote catalog, server or cloud lookup.

## Storage and migration

The database is `anibrave-watch.db` in AniBrave's private app storage. It has
`anime`, `site_progress` and `episode_log` tables. Browser backup is disabled in
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

The current tablet runtime is AniBrave 0.1.1. Its feature sources were recovered
from `codex/anibrave-fullscreen-controls` so changing tracking preserves the
existing fullscreen and controller repairs. This is a feature-module update,
not a merge of the old Firefox branch.

`tools/build_feature_update.py --input EXISTING.apk --output UPDATED.apk
--keystore ORIGINAL.jks` compiles `src/*.java` into the feature DEX and updates
an existing AniBrave APK. Put the matching key passwords in
`ANIBRAVE_KS_PASS`/`ANIBRAVE_KEY_PASS` privately. It delegates to
`tools/patch_icon.py --feature-dex ...`; that verifier permits only the rebuilt
`classes8.dex` while requiring identical upstream DEX and native libraries and
an identical signing certificate. The build increases versionCode, preserves
browser identity and profiles, adds the importer activity and copies both JS
assets. It does not install the APK. APKs, keys and build outputs stay out of git.

Tests: compile/run `tests/ReminderPresentationTest.java` with `src/ReminderPresentation.java`
for page/session rearming and timeout anti-repeat policy. Also `node --test tests/playback.test.cjs tests/tracking.test.cjs` and compile/
run `tests/AnimeIdentityTest.java` with `src/AnimeIdentity.java`. Real device
acceptance must still exercise each site's live player DOM, iframe progress,
cross-site reminders, import/reimport and fullscreen tool behavior.
