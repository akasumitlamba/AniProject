# AniBrave for Brave desktop

An unpacked Manifest V3 extension for Brave on PC. No Android build or web app is required.

## Install

1. Extract `AniBrave-Extension-0.1.4.zip` into a permanent folder.
2. Open `brave://extensions` in Brave and enable **Developer mode**.
3. Choose **Load unpacked**, then select the extracted folder containing `manifest.json`.
4. Open Brave’s extensions menu (puzzle piece) and pin **AniBrave — Playback & Anime Memory**. The AniBrave lion appears in the toolbar.
5. Reload any websites already open before installation.

Keep that folder: Brave loads the extension from it. To update, replace its files and press Reload on the extension card. Local settings survive extension reloads and browser restarts; uninstalling removes extension storage. No store account, subscription, server, or build tool is required.

## Playback

Use the − and + buttons to adjust speed in 0.25× steps, from 0.25× through 5×. The same speed is shared with the site’s own playback control. Changing it on YouTube (or another HTML player) updates the extension, including the popup while it is open. Changing it in the extension updates the player and its on-screen control through the native player API where available. Native API acknowledgements are handled asynchronously; an old reply cannot overwrite a newer selection. Existing native selections are preserved even when a legacy API reports an incomplete speed list. An explicit extension choice takes precedence over a restricted site speed menu. If the native player API refuses it, the extension applies the requested speed to the HTML media element and filters scripted rate resets instead of repeatedly rewriting competing speeds. A deliberate speed-control click or speed key releases that filter and becomes the new saved choice. The site’s menu may lack the higher rate even while actual media playback uses it. Protected players or code that bypasses the media setter can still restrict playback. Repeated competing writes can cause visible speed oscillation and unnecessary player work.

The selection is remembered per hostname; `www` and the bare hostname share settings, while other subdomains remain separate. YouTube and Crunchyroll can have different speeds. New videos, episode transitions, frames, reloads, and later visits reapply the selected speed. A short, bounded startup window restores the saved speed over automatic player resets. A deliberate speed-control click or speed key on the site takes priority even during that window. YouTube ad playback is left alone when the player reports its ad state, and does not overwrite the saved speed. Rates outside 0.25×–5× are left to the site. Long HTML audio is supported; clips under 20 seconds are not sped up. Existing website media controls stay on the page.

Choose a website view from the single View dropdown. Episode memory is collapsed until opened.

## Website views

- **Desktop**: restore normal browser identity and layout (the default).
- **Mobile**: Android phone identity, touch emulation, and a 412 × 915 phone viewport.
- **Mobile identity**: Android phone identity while keeping the browser’s desktop viewport.

Changing view saves the choice for that site and reloads the current tab. Other tabs pick up the saved view on their next navigation/reload. Emulation is attached per tab and removed when navigating to a site using Desktop. A restored browser session applies saved modes when its pages load.

Brave displays a debugger notice while a mobile mode is active. Opening DevTools or cancelling that notice can disconnect emulation; select the mode again to resume. Some sites redirect, use account preferences, reject spoofing, or choose layouts independently. Device identity is emulation, not a physical phone. The initial request to a newly opened site may precede application of its saved mode; reload if its server-selected layout needs it.

## Episode memory

Automatic detection supports Crunchyroll, HiAnime (`hianime.to` / `hianimez.to`), and AniKotoTV (`anikototv.to`). It reads the series title, season where available, and the real episode number. It never treats HiAnime’s internal `?ep=` ID as an episode number. Watching for 15 seconds of advancing HTML video saves progress; opening a page alone does not mark an episode watched. Seeks, pauses, buffering, and media under a minute do not count. Accessible embedded HTML players can report progress to the main watch page.

On a supported site’s homepage, a card shows up to three remembered anime, including entries saved on another site. On an identified series/watch page it shows that anime’s saved episode. Closing the card keeps the history; it can appear again on a new visit. The popup contains the library with return links, editing, and individual deletion. **+ Remember** works on any website and is also the fallback when automatic identification is unavailable. Use consistent titles for cross-site matching. Different translations, aliases, season numbering, or changed website markup may require manual correction; there is no external anime catalog or account synchronization.

## Privacy and permissions

All settings/history stay in `chrome.storage.local` on this browser profile. If enabled in a private window, episode memory is hidden and does not save or delete watch records; playback/view preferences remain shared with the regular profile. No analytics, remote code, external requests, or cloud service. Website access lets the content script control media and read episode headings across player frames. `tabs` resolves the active site; `webNavigation` restores site modes; `debugger` implements real viewport and user-agent/client-hint emulation; `storage` saves preferences and episode memory. Browser internal pages, extension store pages, inaccessible players, and DRM-controlled behavior may be restricted.

## Validation

- `node --test tests/*.test.js`: 40 checks covering speed ordering, validation, history edits, private-window history guards, message scope and debugger cancellation.
- `node tests/browser.cjs`: isolated real Brave profile; extension loading, popup speed changes, site-control sync in both directions, asynchronous native acknowledgements, rapid clicks, player speed limits, ad playback left intact, replacement videos, cross-origin embedded players, open shadow roots, reload persistence, history rename/deletion, reminder dismissal/revisit, phone layout, identity-only view and desktop restoration. Requires Playwright and Brave; set `PLAYWRIGHT_MODULE` / `BRAVE_BINARY` if needed.

Two Python packaging tests verify manifest resources and the page-world bridge. The automated browser suite uses local HTML fixtures. An additional unsigned-in public YouTube check used its actual Playback speed menu and verified site-control-to-extension and extension-to-player synchronization. Signed-in YouTube/Crunchyroll streams, DRM playback, and future live-site markup changes still need acceptance testing. No claim of bug-free compatibility is made.

## 0.1.3 speed precedence

Extends the selectable range to 5× and retains explicit extension speeds above the native player menu limit. The media fallback releases on real site interaction, ad playback and detached players. Regression checks cover native menu rejection, scripted resets, trusted site changes and ads.

## 0.1.2 maintenance

Includes the page-world bridge in every ZIP; awaits native rate acknowledgements and confines them to the corresponding player; retains rapid popup changes; respects debugger cancellation; fixes renamed history edits and invalid history inputs; and removes stale episode reminders after history deletion/navigation. Mobile validation uses a responsive fixture and checks the actual layout viewport and touch emulation rather than mistaking fixed-width page overflow for an emulation failure.

## 0.1.4 reliability fixes

Only deliberate speed controls or the `<` / `>` keys can replace the stored speed. Play clicks, automatic startup resets and maintenance polls reapply it. Long audio still receives the selected speed, but cannot replace a visible video's saved speed. Generic open/closed shadow players are discovered on large pages and when their shadow roots attach later.

A CDN/quality change, title refinement or temporary metadata gap preserves the current episode's watch clock; navigation and episode/season changes reset it. HiAnime home/browse pages use the general memory card and cannot write featured/trailer episodes. Identified HiAnime series pages retain their single-series reminder while only watch pages can automatically save episodes.
