# Streaming buffer

**AniBrave features → Streaming buffer** applies across normal tabs and fullscreen
shortcuts, including embedded players. The default request is three minutes of
media ahead; choose Site default or 1/3/5/10 minutes. Ordinary media gets the
browser's `preload=auto` hint without reloading, seeking or replacing its source.
Exposed, matching HLS.js and Shaka instances receive a larger forward-buffer goal.
Site default restores the original goal on attached supported instances.

The request preserves the player's quality selection, byte limits, loader,
authentication, encrypted-media session and site playback position. Live streams,
recognized ad containers and unrelated player instances are excluded. Paused hidden
pages do not receive new buffer-goal updates from the controller. A site can hide its player API,
limit fetching or stop downloads while paused; the target is not a guarantee that
the entire episode will be downloaded. Preloading uses the existing browser/player
buffer, not a persistent offline file or a second native player.

## Buffer guard fallback

Buffer guard is enabled by default and can be turned off in the same dialog. After
a finite video runs dry while playing, it owns a temporary pause and waits for
roughly 15 seconds of real playback cushion (adjusted for speed, up to 30 media
seconds). It resumes only that pause when the cushion is available, or earlier when a
useful buffer stops growing for several seconds (a player may cap its buffer). It never
changes the video quality or seeks ahead to simulate progress.

A trusted touch/key action cancels automatic resumption so the viewer retains
control. Seeking, source changes, hiding/leaving the page, ended/live media and
ads cannot trigger a stale resume. A 20-second deadline and 30-second cooldown
bound retries. If the buffer does not grow, playback stays paused with guidance
to press Play or lower quality; the guard does not invent bandwidth. A larger
buffer helps connection dips, but sustained throughput below the required video
bitrate multiplied by playback speed still causes waiting.

## Verification limits

Unit fixtures cover scoped HLS/Shaka configuration/restoration, invalid inputs,
live exclusion, preload restoration, rate-aware pause/resume, trusted cancellation,
timeout and source/visibility/seek boundaries. Matching-engine Android tests cover
real page media and the native controls. These checks do not prove improved
throughput or whole-episode prefetch on every AniKoto, Crunchyroll or HiAnime host.

References: [HTML preload hint](https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement/preload),
[HLS.js buffer configuration](https://hlsjs.video-dev.org/api-docs/hls.js.streamcontrollerconfig),
[Shaka streaming configuration](https://shaka-player-demo.appspot.com/docs/api/shaka.extern.html).
