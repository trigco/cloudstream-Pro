# Phase 7.5 — FIX-5: Bottom Nav Truly Floating (Overlay Restructure)

User verdict on Phase 7 (on device): the nav pill *still* does not read as
floating — an opaque black band sits attached under/behind it. Phase 7 only
changed margins/elevation on the pill, which cannot fix the cause: in
`activity_main.xml` the ConstraintLayout **chain reserved layout space for
the bar**, so content stopped above it and the window background showed
through as a slab.

All changes are layout-only. **No Kotlin was touched** — `updateNavBar()`
only toggles `navView`/`navRailView` visibility and the landscape rail
margin; the Phase 7 scrub listener attaches to the nav item views by id
(ids unchanged); snackbars anchor to `android.R.id.content` with no anchor
view and already drew at the screen's bottom edge (unchanged behavior).

## Old chain vs new structure (activity_main.xml)

Old (portrait, nav visible, mini controller hidden):

```
parent bottom
  └─ nav_view            (bottom → parent, margins 20/18dp, height 70dp)
       └─ cast_mini_controller_holder   (bottom → top of nav_view, height 0)
            └─ nav_host_fragment      (bottom → top of holder)
```
Fragments ended exactly at the pill's top edge; the 88dp zone below them
(70dp pill + 18dp margin) showed the window background = the "black band".

New:

```
parent bottom
  ├─ nav_view                       (unchanged: bottom → parent, same margins)
  ├─ cast_mini_controller_holder    (unchanged: bottom → top of nav_view)
  └─ nav_host_fragment              (bottom → BOTTOM OF PARENT)  ← only change
```

- Content now flows underneath the pill; the pill floats over it.
- The cast holder keeps its constraint to `nav_view`'s top, and the pill
  itself did not move, so the mini controller's on-screen position is
  pixel-identical to before. (Deliberately NOT restructured: nothing proved
  the holder needed to move, and chaining it to the pill is what keeps it
  above the bar.)
- Landscape / TV: previously, with `nav_view` GONE, the chain already
  collapsed the gone view to a point at the parent's bottom edge, so the
  NavHost already extended full-height there. Portrait now matches;
  landscape and the rail are behavior-identical. `activity_main_tv.xml`
  untouched.

Nav zone math used everywhere below: pill height 70dp
(`@dimen/nav_view_height`) + bottom margin 18dp = **88dp**; list clearance
target ≈ 100dp (88 + ~12dp breathing), per the fix spec.

## Per-screen compensation

Geometry rule: elements that were bottom-anchored to the fragment lost the
88dp reservation, so bottom-anchored overlays were shifted up by exactly
88dp (restoring their pre-fix on-screen positions pixel-for-pixel), and
scrolling lists got bottom padding + `clipToPadding=false` so items scroll
out from behind the pill but never rest under it.

### fragment_home.xml
| Element | Change |
|---|---|
| `home_master_recycler` | + `paddingBottom="100dp"`, + `clipToPadding="false"` |
| `home_api_fab` (provider pill) | + `layout_marginBottom="104dp"` (style margin 16dp + 88dp) |
| `home_random` | + `layout_marginBottom="104dp"` (same reasoning) |

### fragment_library.xml
| Element | Change |
|---|---|
| `viewpager` (ViewPager2) | `paddingBottom` 40dp → **128dp** (40 strip clearance + 88 nav zone; page bottom edge lands exactly where it was on screen, above the shifted tab strip) |
| FAB wrapper FrameLayout | `layout_marginBottom` 40dp → **128dp** (+88) |
| `library_tab_layout` | + `layout_marginBottom="88dp"` — the 40dp tab strip was bottom-anchored and would otherwise sit entirely behind the pill |
| Loading shimmer GridView | untouched (already `paddingBottom="120dp"` + `clipToPadding="false"`; transient placeholder) |

Note: the per-page lists live in `library_viewpager_page.xml` (out of
scope). They don't need padding: the ViewPager2's own bottom padding
bounds each page's height, and 128dp keeps page bottoms at their old
on-screen position.

### fragment_downloads.xml
| Element | Change |
|---|---|
| `download_list` | `paddingBottom` 100dp → **188dp**. It already had 100dp + `clipToPadding=false`; the viewport grew 88dp and the queue bar returned to its old spot, so +88 keeps the last item's rest clearance (50dp above the queue bar) pixel-identical instead of regressing it behind the bar |
| `download_queue_button` | + `layout_marginBottom="88dp"` (bottom-anchored 50dp bar; restores old on-screen position above the pill) |
| FAB stack holder (LinearLayout) | `layout_marginBottom` 50dp → **138dp** (+88) |

### fragment_search.xml
| Element | Change |
|---|---|
| `search_autofit_results` | + `paddingBottom="100dp"` (already had `clipToPadding="false"`) |
| `search_master_recycler` | + `paddingBottom="100dp"`, + `clipToPadding="false"` |
| `search_history_recycler` | + `paddingBottom="100dp"`, + `clipToPadding="false"` |

### main_settings.xml (settings main tab)
| Element | Change |
|---|---|
| Root `ScrollView` | + `paddingBottom="100dp"`, + `clipToPadding="false"` |

## Risks / residuals

1. **Settings sub-pages** (General, Player, UI, … — `navigation_settings_*`
   destinations) also show the nav, and their preference RecyclerViews get
   no padding from this change (they are built by
   `BasePreferenceFragmentCompat`, outside this fix's layout scope). Their
   last row can rest behind the pill. Follow-up: add the same 100dp bottom
   padding in the base preference fragment. Same note for the downloads
   sub-screens (`navigation_download_child`, `navigation_download_queue`)
   and the test-providers screen, which also show the nav.
2. **Mini controller while casting:** on nav-visible screens the holder no
   longer pushes content up — it overlays the fragment's bottom strip
   (directly above the pill). Content lists have ≥100dp rest clearance vs
   the holder's position starting 88dp from the bottom, so a visible mini
   controller (~64dp tall) can overlap the last list item's rest position
   by up to ~52dp *while a cast session is active*. Transient and standard
   for floating-nav apps; revisit only if the user casts often.
   On nav-hidden screens where the holder stays visible (e.g. Actor page),
   it likewise overlays the bottom instead of reserving space.
3. **Home hero / rows under the pill:** intended (that's the floating
   look). The hero pager is inside `home_master_recycler`, so it scrolls
   under the pill with the rest of the feed.
4. **Home SwipeRefreshLayout** wraps the recycler with `wrap_content`
   height; it now measures against the full fragment height instead of
   height−88dp. Pull-to-refresh geometry is top-anchored and unaffected.
5. Fragment transition animations now render entrances at full height;
   previously the entering fragment was clipped to the chained bounds for
   nav-hidden destinations only in the holder-visible case (see risk 2) —
   no visual change expected elsewhere.
6. All six edited files validated with `xml.dom.minidom`. CI is the first
   compiler, as usual — no local SDK.

## Addendum — settings sub-pages nav clearance (same fix, applied after)

The overlay restructure above gave the main tab lists ≥100dp rest
clearance, but the SETTINGS SUB-PAGES (General, Player, UI, Account,
Providers, Updates) also show the nav and are built on
`BasePreferenceFragmentCompat` — their last rows could still rest hidden
behind the pill.

Change (only file: `ui/BaseFragment.kt`, the base class):

- New `applyNavClearancePadding()`, called at the end of the base class's
  `onViewCreated` (right after the Phase 7 entrance-animation lines, which
  are untouched) and re-applied from `onConfigurationChanged` — the same
  pattern the class already uses for `setSystemBarsPadding()`, since the
  pill's presence changes with orientation and views are not recreated.
- It sets `listView.clipToPadding = false` and overrides ONLY the bottom
  padding (left/top/right read back and preserved): **100dp** via the
  project's `UIHelper.toPx`, matching the main tabs' clearance against the
  88dp nav zone (70dp bar + 18dp bottom margin).
- Guard: `isLayout(PHONE or EMULATOR) && !isLandscape()` — the settings
  screens' own phone check (same flags `SettingsFragment` uses) plus
  MainActivity's portrait condition for the pill. TV never shows the pill
  and landscape shows the rail, so both keep the list's original bottom
  padding (captured once into `defaultListBottomPadding` and restored).
