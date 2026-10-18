# Home interaction and notification validation

AniHome 0.8.15 removes the sidebar header close button; tapping outside or pressing Back closes the sheet. Individual notification close buttons and Clear all remain distinct. Clearable cards also support horizontal swipe dismissal.

Notification cancellation uses Android's active list to reconcile delayed/missed removal callbacks. Expansion state is discarded on dismissal and pruned when cards disappear. The final clearable child dismisses its hidden clearable summary; summaries above ongoing children remain. Android-protected notifications remain visible, with feedback if cancellation is refused.

Empty-space double tap locks using the optional Accessibility control, and can be disabled independently in Gestures. Empty-space long press enters Edit Home, including screen margins. Banners drag within their grid; widget Edit headers support long-press dragging within the widget grid. The dock checkmark finishes editing. Remove buttons sit inside banner bounds rather than extending beyond clipped corners.

Validation:

- 96 JVM unit tests, including grouped dismissal, non-clearable children, stale keys, swipe policy and drag bounds.
- Release lint, debug assembly and signed release assembly passed.
- Android emulator: expanded notification dismissal; first/final grouped-child dismissal; reopen persistence; swipe dismissal; Clear all preserving ongoing notifications; outside-tap close; margin long press; double-tap lock with Accessibility enabled; banner/widget drag ordering and saved order; finish-editing controls.
- Portrait and landscape visual checks confirmed inset remove controls and sidebar dismissal.

These are synthetic notification/emulator checks. Manufacturer-specific notification restrictions and physical-device screen locking still require hardware acceptance. No notification content is persisted or transmitted by AniHome.
