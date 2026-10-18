# Native vertical tabs

AniBrave's **normal browser** offers AniBrave features (sliders in the sidebar for Vertical; beside Tabs for Horizontal) →
**Tab layout settings** → Horizontal / Vertical. Horizontal remains the default;
the choice and expanded/collapsed state are saved on this device. Immersive
AniHome website/search shortcuts do not get a tab sidebar. Switching back to
Horizontal refreshes the native browser window to restore its own toolbar cleanly;
tabs and profile are retained.

In Vertical mode:

- Tap a tab title to switch; × closes it through Brave's normal removal pipeline,
  including native Undo and group confirmations. Collapse the sidebar with the panel-arrow button.
  Compact site icons retain full title tooltips; hold one for Close tab.
- The compact header shows the current model and tab count. The Regular / Private
  switch sits above the list; the bottom New tab action opens a tab in that model.
  Switch models before using New tab to create a private tab. In collapsed mode,
  the model icon opens the regular/private chooser.
- The bottom row holds New tab, native Tab overview/groups and AniBrave features
  (sliders). Features includes playback speed, site view, shortcut, watched anime
  and tab layout settings. The duplicate address-bar tab counter/features are
  removed only in Vertical mode, including their reserved widths. The browser
  menu, navigation, address field and Shields remain.
- Wide windows reserve 240dp for expanded titles and at least 600dp for the page.
  Narrow windows reserve only 56dp; expanded titles overlay the page. Outside tap,
  Back or choosing a tab collapses that overlay. Short windows hide New tab (available inside native overview)
  to keep overview and features accessible with a keyboard or landscape.
- Native fullscreen hides the sidebar and returns its width to the page. Native
  overview/modal/private-lock surfaces suppress the sidebar; cached titles are
  cleared before drawing and refreshed before it returns. No titles are saved,
  logged, sent over a network, or copied between normal/private models.

The sidebar follows system light/dark appearance. Native site favicons (globe fallback when unavailable), a tinted
selected row and consistent drawn icons replace large repeated labels. Rows are
48dp high with separate 48dp close targets; titles ellipsize and retain full
accessibility descriptions/tooltips. Rows are recycled while scrolling.

## Implementation constraints

`NativeTabs.java` is deliberately pinned to the shipped **Brave 1.97.56** Java
engine. The rail owns no separate tabs or sessions. It queries the current native
model, resolves stable tab IDs again on each action and calls the native removal
pipeline rather than deleting a CDP page. Private models have a lazy wrapper;
selection uses native `B0(index, FROM_USER=3)` on both implementations.

`NativeStrip.java` follows Chromium's toolbar capture protocol before height changes,
uses a one-shot callback with cancellation/timeout, and settles pending work on
pause. System status-bar space is reserved only when missing from the native
parent; fullscreen removes that addition. Horizontal restoration recreates the
native window rather than retaining an intermediate compositor snapshot. Native
fullscreen exit restores the previous window-inset mode instead of forcing a
second status-bar reservation on Android11+.

The native hidden-strip policy `control_container.z.E.e(true)` is also applied,
not merely a zero height: otherwise the compositor strip returns during scroll.
That policy alone was insufficient on the physical tablet. The independent
scene overlay `control_container.z.E.J.a.C` also uses `t(1, false)` to set its
hidden bit, which gates `H3()` rendering/hit testing. This bit is enforced before
drawing; its previous value and the height policy are restored on detach while
other native visibility flags are preserved. Native `TabFavicon.from`
attaches the observer normally owned by that strip; `getBitmapWithFallback` reads
the icon. No separate favicon network requests or disk cache are added. The
per-model memory cache compares bitmap content, prunes closed IDs and clears on
model changes so native bitmap copies do not trigger endless row redraws.

`VerticalTabs.java` owns one resumed normal-browser activity. It removes callbacks,
listeners, padding and views on pause. Model/title reconciliation runs every 750ms
while attached and redraws rows only when their state changes. Native lock/overlay
protection also runs before drawing. The pinned strip transition
`control_container.z.E.J.d(height, true)` updates Chromium's compositor, toolbar
margins and browser-controls height together: zero for vertical; native width-dependent
height on return to horizontal. This moves the address bar up without cropping a
live page or painting over the old strip. An incompatible API disables vertical
mode and restores native layout rather than leaving a broken sidebar.

When upgrading the engine, revalidate every reflected API and overlay ID. Do not
assume obfuscated names are stable. Keep the original shortcut/fullscreen hooks
and signing identity; never publish an x86 review harness as a device release.

## QA scope (2026-10-16)

Automated checks passed: 14 playback/tracking JS tests, site/HTTP/anime/reminder
Java checks, 22 geometry scenarios and full feature compilation. UI checks use the same pinned Java engine with
the matching official x86-64 native library in an isolated Android11 emulator.
UI checks covered real native creation/link-open, stable-ID selection, close/Undo,
private-tab selection/last-private close, native overview, repeated layout switches,
pause/restart, fullscreen, immersive shortcuts and phone/tablet geometry/keyboard.
This is distinct from the signed ARM64 tablet release. Local fixture pages contain
no login or personal data. Physical hardware acceptance is separate; emulator checks do not prove subscription DRM playback.

Regression checklist:

1. Open a link in another native tab; switch titles, close selected/background
   tabs, Undo, close the last private tab and reopen native overview/groups.
2. Create/select/close private tabs; never display their titles in regular mode
   or over private reauthentication. Check actual lock/unlock on a configured device.
3. Switch layouts repeatedly; restart and rotate; the native strip/address field,
   page width, selection and existing sessions must stay consistent.
4. Review tablet/phone landscape/portrait, keyboard, scrolling, safe insets,
   narrow overlay Back/outside dismissal and large-font touch targets.
5. Enter/exit native website fullscreen, then open an immersive shortcut and
   return to normal browsing. No rail or address bar belongs in the shortcut.
6. Rerun all Java/JS tests, full feature compilation, signing/runtime hash checks.
   Preserve installed app data; never uninstall or clear the application profile.

## Sidebar polish (2026-10-17)

Narrower 240dp expanded panel, compact header/count, segmented model switch,
48dp rows and a single bottom action row. System light/dark palettes and fixed-size
stroke icons keep the controls consistent. Engine, tab storage and signing identity
are retained. Fresh matching-engine emulator checks cover native actions/Undo,
models/overview, layout switching, both themes, rotation, phone/tablet sizes and
restart. Physical ARM64 acceptance remains through hardware acceptance.

## Integration repair (2026-10-17)

Build610 was incomplete: horizontal compositor tabs returned while scrolling,
site icons were replaced by monograms and tab/features controls were duplicated.
The repair sets the native hidden-strip policy, uses native favicons and moves
those controls to the rail with no redundant rail More menu. Regular/private uses
a person/mask symbol; native overview uses a distinct four-square grid icon. Horizontal mode
restores the original controls/widths. Regression review includes repeated actual
page swipes while the address toolbar hides/reappears, favicon pixel evidence,
unique control/access checks, native Undo/models/overview, layout restoration,
rotation and fullscreen/shortcut behavior. Physical browsing acceptance remains
separate from fixture and signing checks.
