# Website resume and playback speed

Fullscreen shortcuts keep their original destination as a fallback. On subsequent launches,
AniBrave opens the last foreground, normal-profile HTTP(S) page saved for that same site.
Normal browser visits and fullscreen windows share this local per-site destination and cookies.
Returning to an unchanged older window does not overwrite a newer shortcut visit.
The native browser still owns normal tab restoration; launching a shortcut does not replace its tabs.

This resumes a **URL**, not a playback timestamp or a forced next episode. Sites retain ownership
of their own player position. Private tabs, background tabs, subframes, non-web URLs and URLs with
embedded credentials cannot update the saved destination. HTTPS shortcuts cannot resume HTTP.
The store holds at most 100 sites in app-private preferences, with Android backup disabled.
New sites or invalid saved destinations fall back to the shortcut's original URL.

AniHome must support Android's pinned-shortcut confirmation flow. Request **Fullscreen shortcut**
from AniBrave features, confirm **Add to Home**, and tap the resulting tile. It retains Android's
publisher/package/shortcut identity and opens AniBrave's immersive activity; it is not a WebView.

## Speed ownership

- A browser speed selection is saved immediately per top-level site and applied to existing players,
  replacement videos, new episodes, frames, reloads and restarts.
- A deliberate speed-menu click or speed keyboard shortcut in the site updates the browser's saved
  setting, including private windows, just like an explicit choice in the native speed dialog.
  Numeric-only menu labels such as “1.5” are supported even after waiting in the menu. Startup resets, advertisements and scripted clamps do not become a new preference.
- Where a player exposes a matching native API, AniBrave updates it as well as the media element.
  If the site's menu cannot represent the requested browser speed, the browser's media rate wins;
  no fake extra menu options are injected into the website.
- Revision checks discard stale frame updates after a newer choice. A pending site selection is
  protected from the recovery timer. Recovery is bounded per player/time window and re-arms on
  episode/source changes, rather than permanently giving up after several earlier retries.
- The speed dialog includes a current native value even if it is between the predefined choices.

`playback.js` runs in the isolated AniBrave world. `player.js` runs in the page world solely to
reach that media element's native player API and guard unsupported clamps. Their messages contain
only validated media IDs/rates; the native binding remains isolated. The desktop extension has
its own sources and is not modified by the Android controller.

## Validation

Automated checks cover speed ownership, bounded recovery, episode resets, stale revisions,
ad exclusion, native API scoping and the resume URL boundary. Matching-engine emulator checks
exercise real native dialogs, finite local video, shortcut pinning and warm/cold resume. Synthetic
fixtures do not establish signed-in Crunchyroll episode transitions or protected-streaming behavior.
