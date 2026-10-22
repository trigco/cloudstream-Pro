# Phase 6A — Structural fixes from the user's on-device verdict (5A/5B)

Local only, nothing pushed. No icon drawable contents touched (a separate
agent owns icon artwork); exactly one new background drawable was created
(`bg_circle_overlay.xml`, Task 2). All edited XML validated with minidom.

User verdict driving this phase: the 5A floating top bar is useless; the
details screen's solid black top bar cuts the backdrop; the big center play
circle ruins episode thumbnails; the home hero duplicates the title/genres
over backdrop art that already contains them; pull-to-refresh feels absent.

---

## Task 1 — Custom top bar removed, home icons restored

### `res/layout/activity_main.xml`
- The entire `@+id/top_bar` LinearLayout (added in 5A) is removed. The
  ConstraintLayout is back to rail + bottom nav + NavHost + cast mini
  controller holder.

### `MainActivity.kt`
Removed, surgically:
- the Phase 5A block inside `updateNavBar` (isTopTab list, `showTopBar`,
  `topBar.isVisible`, and the NavHost `setPadding` 76dp logic),
- the `navigateTopBar(...)` helper,
- the top-bar click-wiring block (`binding.topBarSearch` / `topBarProfile`,
  including the `R.attr.white` `imageTintList` code).
Kept intact:
- ALL pre-5A behaviour (nav rail/bottom-nav visibility, download/settings
  checked-item sync, `onNavDestinationSelected`, …),
- **the bottom-nav icon pop animation** (`animateNavIconPop` +
  `findFirstImageView` + its `setOnItemSelectedListener` hookup) — the user
  likes animations; grep confirms 0 leftover top-bar references and the pop
  still wired.
- `@IdRes` import stays (still used by `matchDestination`).

### `res/layout/fragment_home_head.xml`
- Removed `android:visibility="gone"` from `@+id/home_search`,
  `@+id/home_head_profile_padding` and `@+id/home_head_profile_card`, and
  removed the two now-stale "Phase 5A: replaced by…" comments. Home is back
  to its Phase 4 state: floating search + avatar over the hero.

### Leftovers (deliberately not deleted)
- `res/drawable/bg_topbar_floating.xml` and `res/animator/icon_press_scale.xml`
  are now unreferenced. Unreferenced resources are harmless to AAPT; the
  files were left in the tree rather than deleted (out of this phase's
  scope — a one-line cleanup if the parent wants it).

---

## Task 2 — Details: floating circle action icons over the backdrop (phone)

### Where the bar actually lives (finding)
The task pointed at `fragment_result.xml`, but that file's own top bar is
**commented-out dead code** (back/share/search block inside `<!-- -->`).
The live phone details screen is `ResultFragmentPhone`, which binds
**`FragmentResultSwipeBinding`** → `res/layout/fragment_result_swipe.xml`,
whose `@+id/result_top_bar` is the solid bar in the user's screenshots.
`fragment_result.xml` is included as the swipe layout's center panel (the
scroll content with the backdrop). All changes are in
**fragment_result_swipe.xml**; `fragment_result.xml` was not modified.
The TV result layout (`fragment_result_tv.xml`) was NOT touched.

### Menu vs layout finding
**Every top-bar icon is a layout-declared `ImageView`** — there is no
Toolbar and no menu-XML inflation on this screen, so **all** of them could
be restyled: `result_back`, `result_mini_sync`, `result_subscribe` (bell),
`result_favorite` (heart), `result_share`, `result_open_in_browser`,
`result_search`, `result_recommendations_btt` (list), plus the
`MediaRouteButton` (cast). Nothing had to be left unrestyled.

### Changes — `fragment_result_swipe.xml`
- **Root `LinearLayout` → `FrameLayout`** (style and size kept; the
  meaningless `orientation` attr dropped) and children reordered:
  `result_fullscreen_holder`, then `result_main_holder` (the panels +
  backdrop scroll content, now filling the whole screen), then
  `result_top_bar` LAST so it draws on top, pinned with
  `android:layout_gravity="top"`.
- `result_top_bar`: the solid `?attr/primaryGrayBackground` background is
  **removed** (transparent); side padding kept. All IDs, child order,
  focus chains, tooltips and click behaviour are byte-identical.
- Each action icon: background `?android:attr/selectableItemBackgroundBorderless`
  → **`@drawable/bg_circle_overlay`** (which carries its own ripple, see
  below); size normalised 25/30dp → **40dp** with 8–9dp padding so the
  glyph stays ~22–24dp; `app:tint` `?attr/textColor` / `?attr/white` →
  **`@color/white`** (they now sit over imagery in both themes — in Light
  mode the old `?attr/white` would have flipped black). `result_back` and
  `result_mini_sync` also gained `android:elevation="10dp"`, matching the
  right-side icons.
- `media_route_button` (cast): kept at 50dp (the mediarouter widget's own
  glyph/animation is sized by the library), gained the same circle
  background and `app:mediaRouteButtonTint` → `@color/white`.

### New drawable — `res/drawable/bg_circle_overlay.xml`
A `<ripple>` modelled exactly on the project's own
`rounded_select_ripple.xml`: oval `<item>` with solid
**`@color/black_overlay`** (the project's existing #66000000, inside the
instructed alpha range) + oval `@android:id/mask`, ripple colour
`?attr/colorControlHighlight` (same as both project ripples). One file
gives the translucent circle AND circle-clipped press feedback.
No `?attr` inside any vector (this is a shape/ripple, and the pattern is
the project's own).

### Behaviour notes
- At rest, the backdrop (200dp `result_top_holder` inside the included
  fragment_result) now reaches the very top of the screen, with the icons
  floating over it — the cut-off black strip is gone. Scrolling content
  passes underneath the floating bar; the dark circles + elevation keep
  the icons legible.
- `ResultTrailerPlayer` hides/shows `resultTopBar` during trailer
  playback — visibility toggling works identically in the new parent.
- `result_fullscreen_holder` now overlays instead of stacking when
  visible (it is `gone` by default; overlay is the correct semantic for
  the fullscreen trailer holder).

---

## Task 3 — Episode thumbnails: center play circle removed

### `res/layout/result_episode_large.xml`
- **Deleted** the `watch_progress_container` FrameLayout (the
  `episode_play_icon` ShapeableImageView + `episode_progress`
  CircularProgressIndicator) from the thumbnail CardView. The thumbnail
  is now clean artwork (the `episode_upcoming_icon` hourglass stays — it
  only appears as a poster-load fallback for upcoming episodes).
- **Added** `@+id/episode_bottom_progress`: a
  `LinearProgressIndicator` with the project's own
  `@style/RoundProgressbar` (live precedent: trailer_custom_layout.xml),
  `layout_gravity="bottom"`, `indeterminate="false"`, starts `gone`.

### `EpisodeAdapter.kt` — large-holder path only
(`is ResultEpisodeLargeBinding` branch; the small `ResultEpisodeBinding`
branch and `result_episode.xml` are untouched — the small row has no
poster, its circle sits beside the title, not over imagery.)
- The watch-state block now drives the bottom bar:
  - watched **or** position ≥ duration → **full bar** (when duration > 0),
  - in progress → partial bar (max = duration s, progress = position s),
  - not started / no duration → bar hidden.
  `max`/`progress` are only assigned when duration > 0 (avoids a zero-max
  indicator).
- The air-date block: upcoming episodes force the bar hidden; the two
  `episodePlayIcon.isVisible = true` lines are removed (the view no longer
  exists in this binding).
- All other large-path references to `episodePlayIcon` / `episodeProgress`
  are gone; grep confirms the only remaining references are in the small
  branch, whose layout still contains those views (compile-safe).

### Watched-progress outcome
Progress display is preserved, relocated: thin bar on the thumbnail's
bottom edge instead of the center ring. What is lost on large rows: the
center **check icon** for watched episodes (a full bar now signals
"finished") and the always-visible center play glyph. The small episode
rows keep their existing check/play circle unchanged.
`EpisodePlayUnderlayProgress` / `EpisodeCircularProgress` styles are now
unreferenced by layouts (definitions left in styles.xml, harmless).

---

## Task 4 — Home hero: duplicated title/details overlay hidden

### Where the overlay actually lives (finding)
`fragment_home_head.xml`'s `@+id/home_preview_title_holder` is, despite its
name, the **action row** (None/bookmark, Play, Info) — kept exactly as
instructed. The pager's pages are inflated from **`home_scroll_view.xml`**
(by `HomeScrollAdapter`), and that is where the overlaid text lives.

### Changes
- `res/layout/home_scroll_view.xml`: `android:visibility="gone"` added to
  - `@+id/home_scroll_preview_title` (the title TextView),
  - `@+id/home_scroll_preview_tags` (the "genre • details" line),
  - `@+id/home_preview_logo` (the provider title-logo image — the title's
    other form; see the code note). Views/IDs all kept.
- `HomeScrollAdapter.kt` (phone branch only; TV branch untouched):
  a layout-only `gone` would NOT have stuck — `bindLogo()` force-toggles
  title/logo visibility at bind time and the tags line was re-shown via
  `isGone = tags.isNullOrEmpty()`. So the phone branch now:
  - keeps the tags line permanently `isGone = true` (text still set),
  - sets title + logo `isGone = true` after setting the title text,
  - **no longer calls `bindLogo()`** for phone pages (its async listener
    would resurrect the logo/title); the now-unused `bindLogo` import was
    removed. TV pages still use their own layout/binding and are
    unaffected (the TV head binds its description/score separately).

Result: the hero is just the backdrop art + the action row, exactly as
the user asked ("background image-e naam thakleo upore extra kore naam…
diye rakhe" — removed).

---

## Task 5 — Pull-to-refresh on Home: DOES NOT EXIST (documented, not built)

Project-wide grep (layouts + Kotlin): **there is no `SwipeRefreshLayout`
anywhere in this app** — not in `fragment_home.xml`, not in any other
layout, and `HomeFragment` has no refresh listener/`isRefreshing` usage.
Home content reloads through provider switching and the manual refresh
affordances (e.g. the `ic_refresh` button in fragment_home.xml); what the
user feels when pulling is the RecyclerView's native overscroll.

Per this phase's instructions, the layout was **not** restructured to add
one. If the parent wants it scoped separately: the natural insertion
point is wrapping `home_main_recycler`'s container in fragment_home.xml
+ wiring `HomeViewModel` reload in HomeFragment — a behaviour change,
not a polish, so it needs its own decision.

---

## Validation
- minidom parse OK: activity_main.xml, fragment_home_head.xml,
  fragment_result_swipe.xml, home_scroll_view.xml,
  result_episode_large.xml, bg_circle_overlay.xml.
- Kotlin reviewed by diff (no Android SDK here — CI is the check, as in
  every phase). No new imports anywhere; one import removed
  (HomeScrollAdapter's `bindLogo`).

## Risks for the build (for the parent's eye)
1. **fragment_result_swipe root type change** (LinearLayout→FrameLayout):
   binding classes don't care, and no code casts `binding.root` (checked:
   only `bindViews(binding.root)` / `hideKeyboard(binding.root)`, both
   View-typed). The one behavioural delta is `result_fullscreen_holder`
   overlaying when visible instead of pushing content — it is gone by
   default and is the trailer-fullscreen holder, so overlay is correct.
2. **Details legibility while scrolling**: body text now scrolls under
   the floating icons. Icons have dark circles + 10dp elevation, and the
   strip they occupy overlays the backdrop/scrim zone at rest; long
   descriptions scrolling beneath stationary icons is the standard
   Netflix-style pattern the user asked for, but worth a screenshot pass.
3. **MediaRouteButton**: its internal cast glyph is drawn by the
   mediarouter library; the circle is a plain background behind it and
   the tint attr value type is unchanged (`@color/white` is a colour,
   same as any `@color/...`). If the library ever recolours the glyph
   itself, only the tint line is affected.
4. **Episode bottom bar corner overlap**: the 126×72 thumbnail CardView
   has 12dp corner radius; the progress bar is a square-ended strip at
   the very bottom edge — the poster image already overdraws the card's
   corners the same way in the current design, so the bar matches the
   existing look; worst case is a hairline corner difference at the
   bottom two corners.
5. **Hero logo**: providers that supply a `logoUrl` previously showed
   the title as an image; that is now hidden on phone along with the
   text title (deliberate — it is the same duplicated name, in image
   form). If the user wants logos kept while text titles go, restore by
   re-calling `bindLogo` and dropping the logo's `gone` — one-line each.
6. MainActivity: only deletions were made; the file's remaining Phase 5A
   artefact is nothing (verified by grep: no `topBar`/`navigateTopBar`
   references remain).
