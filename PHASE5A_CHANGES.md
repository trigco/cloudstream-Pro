# Phase 5A — Floating top bar, settings grouping, icon animations

Local only, nothing pushed. No palette/color files touched (colors.xml,
item_select_color.xml and the variant overlays are a separate pending decision).

---

## Task 1 — Global floating top bar (phone only)

### Files created
- `res/drawable/bg_topbar_floating.xml` — exact mirror of the Phase 4
  `bg_nav_floating` recipe: rectangle shape, 28dp corners,
  `solid ?attr/primaryGrayBackground` (flips per theme variant).

### `res/layout/activity_main.xml` (modified)
New last child of the ConstraintLayout (so it draws above the NavHost):
a horizontal `LinearLayout` `@+id/top_bar`, height 56dp, width 0dp,
margins 16dp start/end + 12dp top, background `@drawable/bg_topbar_floating`,
elevation 8dp, `visibility="gone"` initially (Kotlin drives it from the first
destination callback), start constrained to end of `@id/nav_rail_view`
(the same pattern the NavHost itself uses — when the rail is GONE on phone
portrait the constraint collapses to the parent start, exactly like the
NavHost behaves today).
Contents:
- Title `TextView`: `@string/app_name` ("CloudStream"), 17sp bold,
  `android:textColor="?attr/textColor"` (verified layout precedent,
  e.g. account_select_linear.xml), weight 1.
- `@+id/top_bar_search` `ImageView` (40dp, 9dp padding):
  `src="@drawable/search_icon"` — the exact drawable the bottom-nav menu
  uses for the Search tab (`res/menu/bottom_nav_menu.xml`).
- `@+id/top_bar_profile` `ImageView` (40dp, 9dp padding):
  `src="@drawable/ic_outline_account_circle_24"` — an existing
  person/account vector already in the project.
- Both buttons: `foreground="@drawable/rounded_select_ripple"` (existing
  project ripple, same one the old home profile button used),
  `android:stateListAnimator="@animator/icon_press_scale"` (Task 3),
  clickable + focusable.

**Icon tint — set in Kotlin, not XML.** Grep found NO precedent for
`android:tint="?attr/..."` on an ImageView in project layouts (only
`app:tint="@color/..."`), so per the project rule the tint is applied in
`MainActivity` (see below) via `imageTintList`.

### Phone gating mechanism (found + used)
The activity has BOTH bars in one layout (there are no layout-land /
layout-television variants of activity_main.xml — verified). Phone vs TV is
decided in code: `Globals.isLayout(PHONE)` (layout type) + `Globals.isLandscape()`,
and `MainActivity.updateNavBar(destination)` already centralises
destination-driven bar visibility:
`navView.isVisible = isNavVisible && !isLandscape()`. The top bar uses the
SAME phone case: `isLayout(PHONE) && !isLandscape()`.

### Destination logic — `MainActivity.kt` (`updateNavBar`)
`updateNavBar` is already invoked from the existing
`NavController.OnDestinationChangedListener` (and from
`onConfigurationChanged`), so the top-bar logic lives there instead of a
second listener:

- `isTopTab` = destination ∈ {navigation_home, navigation_search,
  navigation_library, navigation_downloads, navigation_settings}
- `showTopBar = isLayout(PHONE) && !isLandscape() && isTopTab`
- `topBar.isVisible = showTopBar` (GONE on details, player, settings
  sub-pages, quick search, … — those destinations are simply not in the list)
- NavHost top padding: `76.toPx` when `showTopBar && destination != home`,
  else 0. 76dp = bar height 56 + top margin 12 + 8dp breathing room.
  Home keeps 0 padding — the hero is full-bleed under the floating bar by
  design (same as the old floating icons). Padding is applied with
  `navHostFragment.setPadding(...)` preserving the existing start/end/bottom
  padding (all 0 today).

### Button wiring — `MainActivity.kt`
Right after the bottom-nav setup:
- `topBarSearch` click → `navigateTopBar(navController, R.id.navigation_search)`
- `topBarProfile` click → `navigateTopBar(navController, R.id.navigation_settings)`
- New helper `navigateTopBar(...)`: no-op if already on the destination;
  `navController.navigate(...)` wrapped in the same
  `IllegalArgumentException` catch + "NavigationError" log tag that
  `onNavDestinationSelected` already uses. (Plain navigate is enough:
  `setupWithNavController`'s destination listener still syncs the bottom-nav
  checked item, and `updateNavBar` re-checks the settings/downloads groups.)
- Icon tint: `imageTintList = ColorStateList.valueOf(getResourceColor(R.attr.white, 1.0f))`
  on both buttons. Why `R.attr.white` and not `iconColor`: the `white` attr
  FLIPS per variant (LightMode swaps the white/black attrs — the same
  mechanism behind the Phase 4 settings-icon end state, and the same tint
  these two vectors carry intrinsically), giving white icons on the dark bar
  and black icons on the light bar. `iconColor` is NOT rebound by LightMode
  (documented Phase 4 §3 nuance) and would stay #B3B3B3 on the light bar —
  the washed-out look the user already rejected once.
  Note: the tint is idempotent at runtime; if the theme is switched while
  MainActivity is recreated (it is — theme changes recreate activities in
  this app), the fresh inflation re-applies it with the new theme.

### Home head — `res/layout/fragment_home_head.xml` (modified)
- `@+id/home_search` (SearchView): added `android:visibility="gone"`.
- `@+id/home_head_profile_card` (CardView): added `android:visibility="gone"`.
- Views and IDs untouched — HomeFragment/adapter code still binds them.
  Only Kotlin usage of `home_search` anywhere is
  `HomeParentItemAdapterPreview` setting an `OnQueryTextListener` (harmless
  on a gone view). NOTE: the 50dp wrapper `home_head_profile_padding` is
  still visible/clickable (it holds the account-select ripple) — it now
  shows an empty ripple circle where the avatar used to be. Hiding it too
  was out of the specified scope (only the two named views), but it is a
  one-line follow-up if the empty tap target looks odd.

---

## Task 2 — Settings main menu grouping

### Files created
- `res/drawable/bg_settings_group.xml` — rectangle, 20dp corners,
  `solid ?attr/primaryGrayBackground` (same surface-attr pattern as the bars).

### Files modified
- `res/values/strings.xml` — no existing strings fit, so three were added next
  to the other category strings: `settings_group_app` "App",
  `settings_group_content` "Content", `settings_group_system` "System".
- `res/layout/main_settings.xml` — the flat list of 7 `SettingsItem`
  TextViews is now three groups. Profile header block and the version footer
  are byte-identical to before.

Structure (each group = header `TextView` ABOVE a rounded container
`LinearLayout` with `bg_settings_group`, 16dp side margins; headers are
12sp bold, `textAllCaps`, `letterSpacing="0.08"` — both attrs verified in
project layouts (download_button_layout.xml) — `textColor="?attr/grayTextColor"`
(verified precedent), marginStart 20dp, marginBottom 8dp, marginTop 18dp
for groups 2/3, 4dp for the first):

| Group | Items (order preserved from the original list) |
|---|---|
| App | General (`settings_general`), Player (`settings_player`), UI (`settings_ui`) |
| Content | Providers (`settings_providers`), Extensions (`settings_extensions`) |
| System | Updates (`settings_updates`), Account (`settings_credits`) |

- ALL item view IDs, texts, icons (`app:drawableStartCompat`) and the
  `SettingsItem` style are unchanged → `SettingsFragment`'s view-binding
  references (`settingsGeneral` … `settingsExtensions`) keep working;
  binding is by ID and nesting depth does not matter.
- The `android:nextFocusUp/Down` chain was re-threaded to the NEW visual
  order (general → player → ui → providers → extensions → updates →
  credits); previously it followed the old flat order and would have made
  TV focus jump between groups.
- **clipToOutline NOT used**: grep shows `android:clipToOutline` appears
  NOWHERE in the project's res/, so per the unverified-attr rule it was left
  out. Consequence (accepted, documented): the items' press ripple is the
  Phase 4 `bg_ripple_rounded` whose mask is rounded at 16dp while the group
  container is 20dp — while pressing the FIRST or LAST row of a group, a
  faint ~1–2dp ripple sliver can extend past the container's corner arc
  (the ripple colour is the low-alpha `?attr/colorControlHighlight`).
  Unpressed rows are transparent, so resting state shows clean 20dp
  container corners. If it ever bothers anyone, `clipToOutline` on the
  containers is the one-line fix — it just needs the project's
  first-use verification like every other attr got.

---

## Task 3 — Icon animations

### Bottom-nav pop — `MainActivity.kt`
The existing phone bottom-nav listener
(`binding.navView.setOnItemSelectedListener`, which delegates to
`onNavDestinationSelected`) was EXTENDED, not replaced: the handler result
is captured, and only when navigation actually happened (`handled == true`
— i.e. not a reselection and not the 400ms debounce rejection) the new
`animateNavIconPop(navView, item.itemId)` runs; the lambda still returns
`handled`, so selection behaviour is byte-for-byte the old semantics.
- `animateNavIconPop`: `navView.getChildAt(0)` as the menu ViewGroup,
  iterates its children for the item view whose **view id equals the menu
  item id** (BottomNavigationItemView carries the item id as its own id),
  then a recursive `findFirstImageView` DFS finds the icon.
- Animation: `ViewPropertyAnimator` scale 1.0 → 1.2 in 120ms, then
  (withEndAction) → 1.0 in 130ms = ~250ms total. Any previous animation on
  that icon is cancelled and scale reset first, so rapid tab switching
  can't strand an icon at 1.2×.
- Fully defensive: safe casts, null returns, whole body in
  try/catch → `logError(t)`. Worst case on an unexpected Material
  internals change = no animation, never a crash, never blocked navigation.

### Top-bar press scale — `res/animator/icon_press_scale.xml` (created)
- Gate check passed: `android:stateListAnimator` IS used in the project
  (styles.xml sets it to `@null` in several styles), and the
  `<objectAnimator>` vocabulary (`propertyName`, `valueTo`, `valueType`,
  `duration`) is copied from the project's animated-vector drawables
  (`play_to_pause.xml` / `pause_to_play.xml`). So the sub-item was done
  as specified (the ripple-only fallback was not needed — the buttons
  ALSO keep the `rounded_select_ripple` foreground).
- Root `<selector>`: pressed item → scaleX/scaleY to 0.88 in 90ms;
  default item → back to 1.0 in 140ms. `valueTo`-only (no `valueFrom`) so
  each state animates from the CURRENT scale and the default state can
  never stamp a wrong start value when the animator attaches.
- Applied via `android:stateListAnimator="@animator/icon_press_scale"`
  on both top-bar ImageViews. First `res/animator/` resource in the
  project — see Risks.

---

## Validation
- minidom parse OK: activity_main.xml, fragment_home_head.xml,
  main_settings.xml, strings.xml, bg_topbar_floating.xml,
  bg_settings_group.xml, icon_press_scale.xml.
- Kotlin reviewed by diff (no Android SDK on this machine — CI is the check,
  as in Phases 1–4). No new imports were needed: BottomNavigationView, View,
  ViewGroup, ImageView, ColorStateList, Log, @IdRes, isVisible, toPx,
  getResourceColor are all already used/imported in MainActivity.kt, and
  `R.attr.white` is declared in the project's attrs.xml (format="color").

## Risks for the build (for the parent's eye)
1. **MainActivity compile**: the three additions use only in-file precedents.
   The one spot to eyeball is `animateNavIconPop(this, ...)` inside the
   `setOnItemSelectedListener` lambda — `this` resolves to the
   `binding.navView.apply` receiver (BottomNavigationView), because the
   listener lambda has no receiver of its own. If a future edit wraps it
   in another receiver scope, re-check.
2. **Nav icon lookup**: relies on Material 1.14 `BottomNavigationView`
   internals (child 0 = menu view; item views carry their menu item id).
   True for the pinned Material version and written to fail silently —
   but the pop is therefore "works on current Material, degrades to
   nothing", by design.
3. **res/animator/ is a new resource folder for this project.** Animator
   XML is standard platform format and AAPT doesn't VdUtil-parse it
   (the Phase 4 merger trap was specific to vector paths), so risk is
   low — but it is the only genuinely new resource TYPE in this phase;
   if CI surprises us, dropping the two `android:stateListAnimator`
   lines (buttons keep their ripple foreground) isolates it instantly.
4. **NavHost padding**: fragments that draw their own top chrome
   (Search especially) will now sit 76dp lower on phone portrait —
   intended, but worth a visual pass on Search/Downloads/Library/
   Settings after install. Player/results/quick-search never get the
   padding (bar hidden there), so playback layouts are untouched.
5. Top bar overlaps the home hero's top area (title art can run under
   it) — that is the requested Telegram-style float, and the old home
   icons lived in the same strip; screenshot check recommended.

## Color pass (parent, after user's decision: Play-only red, rest monochrome)
- colors.xml: colorPrimary/colorAccent #2962FF → #FFFFFF (dark-base monochrome accent); colorPrimaryDark → #CCCCCC; primaryBlackBackground #141414 → #000000 (pure black default); primaryGrayBackground #1F1F1F → #121212; colorPrimaryRed → #E50914 (restored red slot).
- styles.xml: colorOnPrimary whiteText → blackText in AppTheme + AmoledMode + OverlayPrimaryColorNormal only (the app has a built-in accent picker — OverlayPrimaryColorBlue/Red/etc. keep whiteText for their colored primaries); LightMode gains colorPrimary + android:colorPrimary = @color/black (monochrome accent on light; the overlay previously did not rebind colorPrimary at all).
- Play buttons repointed from @color/colorPrimary to @color/colorPrimaryRed: fragment_home_head.xml (hero) and fragment_result.xml (details) — the only two layout backgroundTint refs to colorPrimary. All other ?attr/colorPrimary consumers follow the theme automatically.
- fragment_home_head.xml: home_head_profile_padding also set gone (the subagent hid the card but left an empty clickable ripple circle on the hero).
