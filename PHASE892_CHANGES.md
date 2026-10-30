# Phase 8.92 — Feed verdict fixes (on top of 8.91b, head cc22c6c9)

Device verdict on 8.90+8.91b (2026-10-30 07:20, screenshots):
smart Search landing works, category rows exist (Trending / Top 250
seen), player Up next is 3-grid. His orders: (A) remove the hero,
(B) feed search must stay inside the Feed, (C) more categories —
content lower down sat uncategorised, (D) brightness + volume swipe
gestures in the feed player, brightness over the video only,
(E) the biggest problem: Feed loading is heavy and the page HANGS.

## A. Hero removed (FeedFragment, layouts, deleted files)

- `FeedHeroAdapter` / `FeedHeroHeaderAdapter` usage, the pager
  auto-advance timer, the settled-poster backdrop and next-poster
  pre-warm are all gone from `FeedFragment`. The ConcatAdapter is
  now `ConcatAdapter(categoryRowsAdapter, adapter)`; the span
  lookup counts only category rows as full-span headers.
- Deleted (unreferenced after the removal — verified by repo-wide
  grep; the only remaining mention is a KDoc comment in
  FeedCategoriesAdapter): `FeedHeroAdapter.kt`,
  `FeedHeroHeaderAdapter.kt`, `res/layout/feed_hero_header.xml`,
  `res/layout/feed_hero_page.xml`.
- The Home hero is a separate surface (HomeScrollAdapter /
  HomeScrollTransformer) and is untouched.

## B. In-feed search (FeedFragment, fragment_feed.xml, strings.xml)

- 8.90 made the header 🔍 jump to the main Search tab; reverted on
  his correction. The button now toggles a search row inside the
  Feed header (`feed_search_row`: `feed_search_input` + ✕
  `feed_search_clear`, new string `feed_search_close`).
- Typing is live with a 300 ms debounce into the existing
  `searchQuery` state + `applySearch` path: local pool title
  filtering immediately, the pre-existing deep provider search
  (3+ chars, its own 600 ms debounce) still merges remote hits.
  The IME Search action applies instantly.
- While a query is live, category rows hide synchronously
  (`hideCategoryRows` in `applySearch`); the ✕ clears the query,
  hides the row + keyboard and restores the full feed. The header
  re-measures after the row toggles so the grid's top padding
  tracks the real header height (`refreshHeaderMetrics`).
- The bottom-nav Search tab / smart Search landing is untouched.

## C. Wider categories (FeedFragment)

- Thresholds: row minimum 4 → **3** items, row cap 12 → **20**,
  items per row 15 → **20** (`ROW_MIN_ITEMS` / `ROW_CAP` /
  `ROW_ITEM_CAP`).
- Passthrough provider shelves (unmatched names kept under the
  provider's own label by FeedSectionTags) were verified to flow
  through `orderedSectionTags()` into rows and chips already in
  8.91 — what starved them was the 12-row cap + ≥4 threshold;
  both are loosened above.
- NEW final block of **per-provider rows**: every provider
  (apiName) with ≥ 6 pool items gets a row titled by the provider
  name ("Netflix", "MovieBox", "MovieLinkBD", …), providers
  sorted by item count, at most 10 such rows, appended after all
  tag rows. Row names carry the `provider:` prefix; View All
  routes into the existing Source filter slot (`filterSource`)
  instead of the category/genre slots. Provider rows are not
  tags — the Feed Categories settings page does not govern them
  — and they hide with the other rows while a search/filter is
  active (they are built inside the same browsing-only pass).
- Curated slot order, the ≥80% region-variant dedupe and the
  emoji row titles are unchanged.

## D. Feed player brightness + volume gestures (FeedWatchFragment,
## fragment_feed_watch.xml)

- Integrated into the existing `feedWatchPlayer` touch listener
  alongside the GestureDetector (single tap = controls,
  double-tap = seek — both untouched). Claim rule mirrors
  `PlayerGestureHelper`: a mostly-vertical move past 2% of the
  view height claims the gesture; the side is chosen by the
  touch-down X (left half = brightness, right half = volume);
  the value is the touch-down snapshot ± travel × 2.0 (the main
  player's VERTICAL_MULTIPLIER — full range = half the height).
- Feedback is a centred pill over the video
  (`feed_gesture_indicator`: sun/volume icon + percentage +
  level bar), auto-hiding 1.2 s after the last adjustment —
  the main player's icon+rail indicator in miniature.
- **Brightness is window-only**: it writes
  `WindowManager.LayoutParams.screenBrightness` on the hosting
  activity's window (video area only — the attribute is
  per-window, and this screen is the window's content), NEVER
  `Settings.System` (the main player's helper defaults to the
  system setting; that part is deliberately not mirrored).
  `onDestroyView` restores `BRIGHTNESS_OVERRIDE_NONE`, so the
  override cannot leak into the rest of the app. A fragment
  field remembers the level across a batch-page trip.
- Volume is `AudioManager.STREAM_MUSIC` steps with flag 0 (no
  system volume UI), like the main player.
- One deliberate integration compromise: the pre-8.92
  drag-down-to-minimise fired for a downward drag ANYWHERE on
  the surface, which is the same motion as a brightness/volume
  down-swipe. Minimise now requires the downward drag (>160 px,
  while playing — unchanged) to START in the player's top
  strip (top 25%): pulling the video down by its top edge.
  Downward swipes starting anywhere else adjust
  brightness/volume, as in the main player.

## E. Hang — diagnosed causes (verified in code) and fixes

### Cause 1 — every category/chip recompute ran on the MAIN thread
`updateCategoriesRow()` + `buildCategoryTabs()`/`presentTags()`
were invoked synchronously from the `viewModel.feed` observer,
from `metaApplyRunnable` (a main-Looper Handler) and from
`applyCategoryTab` — all main thread (FeedFragment.kt, pre-8.92).
Each pass is O(tags × pool): for every tag, a full pool scan of
predicates whose per-entry cost includes
`MetadataResolver.metaCached()` (SharedPreferences `getString`
on every memory miss — misses are not negatively cached there —
plus `cacheKey()` → `normalize()`, which instantiates two
`Regex` objects per call) and `TitleResolver.cachedType()`
(another SharedPreferences read per call). With 20+ tags and a
pool of several hundred entries, one pass is tens of thousands
of these calls on the UI thread.
- Compounding it, `ratingListener` cleared `tagItemsCache` on
  EVERY rating landing; ratings resolve in bursts while
  scrolling, so the next debounced pass was always a full cold
  recompute.
Fixes:
- The whole rows+chips computation now runs in ONE background
  pass (`scheduleChromeUpdate` → `computeChrome`) on
  `Dispatchers.Default` over a pool + ratings snapshot, with its
  own memo; only finished models cross back to the main thread.
  Stale passes (pool changed mid-compute) are discarded, and the
  adapter is only submitted when the row signature (names +
  item URLs) actually changed. `FeedCategoryRowsAdapter.submit`
  also no-ops on an identical list.
- The rating listener now bumps a generation counter instead of
  clearing the memo; the main-thread memo invalidates lazily,
  once per burst, on next use. Both listeners feed the same
  900 ms debounce (which still waits for scroll idle).
- The grid's category filter no longer scans the tag's whole
  item list per pool entry (`categoryMatchesEntry` inside
  `applyFeedFilters` was O(pool²) for curated tags): the URL set
  is built once per filter pass (`CURATED_MATCH_TAGS`).

### Cause 2 — the pool build parsed providers on the MAIN thread,
### and first paint waited for everyone × 2 pages
`FeedViewModel.build()` launched in `viewModelScope`
(Dispatchers.Main.immediate) and `buildFeed` fans out with
`amap`, whose `async {}` children inherit the caller's
dispatcher (library ParCollections.kt) — so the CPU-heavy half
of every provider's `getMainPage` (HTML/JSON parsing after the
suspending network call) executed on the UI thread, interleaved
across all providers. The first build additionally awaited BOTH
homepage pages of EVERY provider before the first
`Resource.Success` post, so first paint waited for the slowest
provider twice over.
Fixes:
- `build()` now launches on `Dispatchers.Default` (the merge /
  interleave / liked-boost work moves with it; results still
  post via `postValue`). `searchAll` (the feed's deep search,
  called from the fragment's Main lifecycleScope) is wrapped in
  `withContext(Dispatchers.Default)` for the same reason.
- The first build publishes page 1 as soon as it lands
  (`publish(..., suppressEmpty = true)`), then merges page 2 in
  behind it. The pool stays cumulative in the ViewModel /
  `FeedPool`, so tab switches never refetch (unchanged);
  pull-to-refresh still forces the next page.

### Cause 3 — metadata resolution storm while loading/scrolling
The pool observer pre-kicked `resolveMeta` for the pool's first
60 entries on every Success post, and `FeedAdapter.bind` kicked
`resolveMeta` + rating `resolve` for every uncached card —
funnelling into the shared Semaphore(3) resolver while the page
was still loading and scrolling; every landing fed Cause 1's
main-thread recompute.
Fixes:
- Pre-kick cut 60 → 24 (deeper cards still resolve on bind).
- `FeedAdapter.resolutionsPaused`: while the Feed grid is not
  in SCROLL_STATE_IDLE, binds render cached values but start no
  new resolutions; the fragment's scroll listener flips it and
  resolution resumes at idle.

### Not claimed
No single line "was" the hang; the three causes above are the
ones verified in code, and they compound (slow first paint →
resolver storm → repeated full main-thread recomputes). Whether
the device now feels smooth is for the device verdict, per the
build loop — green CI only means ready to check.

## Files

Changed: `ui/feed/FeedFragment.kt`, `ui/feed/FeedViewModel.kt`,
`ui/feed/FeedAdapter.kt`, `ui/feed/FeedCategoryRowsAdapter.kt`,
`ui/feed/FeedWatchFragment.kt`, `res/layout/fragment_feed.xml`,
`res/layout/fragment_feed_watch.xml`, `res/values/strings.xml`,
this file.
Deleted after push: `ui/feed/FeedHeroAdapter.kt`,
`ui/feed/FeedHeroHeaderAdapter.kt`,
`res/layout/feed_hero_header.xml`, `res/layout/feed_hero_page.xml`.

## Deviations

- Minimise-by-drag narrowed to top-strip drags (see D) — the
  only way to keep both it and the ordered swipe gestures on
  one surface; double-tap seek and tap controls are untouched.
- The Feed Categories settings page needed no changes for the
  new thresholds (it reads the same canonical vocabulary); it
  does not list provider rows, by design (they are not tags).
- Progressive pool publishing is first-build only (page 1 then
  page 2); later builds were already single-page appends/prepends
  that publish once.
