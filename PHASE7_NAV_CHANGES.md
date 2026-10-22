# Phase 7 — Bottom Nav: Truly Floating + Swipe-to-Scrub

Scope (this agent only): `res/layout/activity_main.xml`, `res/drawable/bg_nav_floating.xml`, `MainActivity.kt`. Nothing pushed; local worktree only.

User feedback addressed: (1) the floating bottom nav still reads FLAT, not floating; (2) he wants to sweep a finger across the bar to move between tabs.

---

## 1. Investigation (before editing)

**Current layout** (`activity_main.xml`, phone `BottomNavigationView` `@+id/nav_view`):
- height `@dimen/nav_view_height` (70dp), `labelVisibilityMode="unlabeled"` (icon-only), background `@drawable/bg_nav_floating`.
- margins were 16dp start/end, 14dp bottom; elevation was already 8dp.
- `bg_nav_floating.xml` was a plain shape: 28dp corners, solid `?attr/primaryGrayBackground`, no stroke.

**Content-underlap check:** the content does **not** extend under the bar. Constraint chain: `nav_host_fragment` bottom → top of `cast_mini_controller_holder`, whose bottom → top of `nav_view`. So raising the bar's margins just shrinks the content area by the same delta via constraints — no list content is permanently hidden, and no bottom-padding mechanism exists that would need adjusting.

**Nav wiring** (`MainActivity`, `binding?.navView?.apply { ... }`):
- `setupWithNavController(navController)` first, then a custom `setOnItemSelectedListener` that calls `onNavDestinationSelected(item, navController)` — which applies the real NavOptions (launchSingleTop, restoreState, enter/exit/pop anims, popUpTo start destination with saveState) and returns a Boolean — and fires the Phase 5A `animateNavIconPop()` only when navigation actually happened.
- `onNavDestinationSelected` also has a 400 ms safe-debounce (`lastNavTime`) and returns false when the target is already the current destination.
- Programmatic selection through `navView.selectedItemId = ...` is already an established path elsewhere in the file (used to sync the nav from other screens), and it funnels through this same listener.
- `updateNavBar()` only toggles visibility (bar in portrait, rail in landscape); no other code repositions the bar.

---

## 2. Changes

### bg_nav_floating.xml
- Corner radius 28dp → **32dp**.
- Added a subtle stroke: `<stroke android:width="1dp" android:color="#33FFFFFF" />` (white at ~20% alpha, literal as specified) so the pill's edge catches light over scrolling posters.
- Solid unchanged (`?attr/primaryGrayBackground` — still theme-variant aware).

### activity_main.xml (`@+id/nav_view` only)
- `layout_marginStart` / `layout_marginEnd`: 16dp → **20dp**.
- `layout_marginBottom`: 14dp → **18dp**.
- `elevation`: 8dp → **12dp** (real shadow under the pill now that it insets further from the edges).
- Height, id, menu, tints, label mode all unchanged — still icon-only, same 70dp bar.

### MainActivity.kt
- Added `import android.view.MotionEvent` (`kotlin.math.abs`, `Int.toPx`, `isVisible`, `logError` were already imported/used).
- In the existing `binding?.navView?.apply { ... }` block, after the item-selected listener: `if (isLayout(PHONE)) setupNavScrub(this)`.
- New `setupNavScrub(navView)` next to `animateNavIconPop()` (full KDoc in code).

---

## 3. Scrub implementation (wiring path + touch logic)

**Wiring path used:** assigning `navView.selectedItemId = targetId` — the identical path a tap takes (NavigationBarView internal callback → the custom `OnItemSelectedListener` → `onNavDestinationSelected()` → `navController.navigate()` with the existing NavOptions). The Phase 5A icon pop fires from that listener whenever navigation succeeds, so scrubbed selections pop exactly like tapped ones. No direct `navController.navigate()` in the gesture code.

**Why the listener is on the item views, not on the BottomNavigationView:** item views consume every touch that lands on them, so an `OnTouchListener` set on the bar itself never receives gestures starting on an item — i.e. all of them. Instead, `setupNavScrub` reuses the lookup `animateNavIconPop()` already relies on (`navView.getChildAt(0)` = BottomNavigationMenuView) and, inside `navView.post {}` (guaranteeing the menu items exist), attaches one shared listener to each item view **and its descendants** (defensive: whichever inner view is the actual touch target, events are observed). All logic uses `event.rawX/rawY` against item-view screen bounds (`getLocationOnScreen`), so it is target-agnostic.

**Gesture state machine** (per-gesture state lives in the posted closure):
- `ACTION_DOWN`: record raw X/Y, reset flags, return **false** — item keeps pressed state, click stays armed.
- `ACTION_MOVE`: until movement exceeds the threshold (**8dp**, via existing `Int.toPx`, either axis), return false. Past it: `scrubbing = true`, `requestDisallowInterceptTouchEvent(true)` on the parent, then hit-test the finger position against each visible item view's screen bounds; if the hit item differs from `navView.selectedItemId`, assign `selectedItemId`. Success is confirmed by re-reading `selectedItemId` (a debounce- or already-there-rejected assignment leaves it unchanged, and a later MOVE in the same gesture retries — the finger usually clears the 400 ms debounce while travelling). MOVE returns `true` while scrubbing.
- `ACTION_UP`: consume (**return true**) **only if `selectionChanged`** — this is what stops the gesture-start item's click from firing and navigating straight back. If nothing changed, return false and the normal click proceeds untouched.
- `ACTION_CANCEL`: reset state; consume only if it had been scrubbing.

Guard: phone bottom nav only, via the file's existing `isLayout(PHONE)` check at the call site (the rail view is not touched). All ids, the existing listener, debounce, and `updateNavBar()` behavior are unchanged. Both edited XML files validated with `xml.dom.minidom`; Kotlin verified by brace balance + region review (CI remains the first compiler).

---

## 4. Risks

- **400 ms debounce vs. fast scrubs:** a quick sweep across several tabs inside 400 ms selects only the first tab immediately; intermediate assignments are rejected by `onNavDestinationSelected`. If the finger keeps moving (or rests) past the window, the next MOVE retries and lands. A fast flick-and-release may therefore land one tab short of the release point. This is inherited from the existing tap wiring (deliberately not bypassed) — changing the debounce is out of scope but is the known lever if the user finds scrubbing laggy.
- **Item-view internals:** the `getChildAt(0)` menu-view lookup is a Material-internal structure; it's the same assumption `animateNavIconPop()` already ships with, and the whole setup is wrapped in try/catch + `logError`, so any structural mismatch silently degrades to normal tap-only navigation (no crash). Item views are created once at inflation; if a future change re-inflates the menu at runtime, listeners would need re-attaching (menu here is static XML).
- **Gesture conflicts:** the bar sits below all content (constraint chain), so scrub gestures cannot fight a scrolling list; the only overlapped gesture is the item's own click/ripple, handled by the consume-only-on-change rule. A mostly-vertical drag starting on the bar past 8dp also enters scrub mode (harmless — hit-test selects nothing outside the bar's row, and UP then falls through unconsumed). Long-press on nav items (tooltips) still works: the listener returns false until the threshold is crossed.
- **TV/emulator:** untouched — rail keeps its own listener; scrub is phone-only. On phones in landscape the bar is GONE (rail shows instead), so no scrub there either, consistent with `updateNavBar()`.
- **Floating look:** elevation shadow renders over the content below the bar; because content is constrained above the bar, the shadow mostly falls on the screen's bottom edge/background — the visual lift comes mainly from the larger insets + 1dp stroke. If the user still finds it flat, next levers are a darker/lighter surface contrast or a real shadow via `outlineProvider` — not attempted here.
