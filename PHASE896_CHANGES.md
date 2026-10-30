# Phase 8.96 — Feed-scoped search, two-phase categories + mirror skeleton, full category pages (on top of 8.95, head f6bf74ae)

Device verdict on 8.95 (2026-10-30 11:15, four screenshots):
crash FIXED (no Safe mode), categories DO arrive, nav v2 good
(labels fully visible). Remaining orders: (1) Feed search must
be FEED search — "feed search feed এ কাজ করবে" — the 8.94/8.95
page was the MAIN SearchFragment (global search) and he
rejects it; (2) Feed first renders with only the "All" chip +
For You grid and the category rows/chips pop in seconds later,
loading feels hangy; (3) under For You / inside categories
"কিছুই থাকে না" (nothing is there); (4) a large empty band
appears above the first category row when scrolled (circled
in his screenshot); (5) his own proposal, approved: load only
20 items per category up front — the full category fetches on
demand when the user opens it ("more" → in that category);
(6) the nav "Downloads" label truncates to "Downlo…".

## Job 1 — Feed-scoped search page (FeedSearchFragment)

- NEW `ui/feed/FeedSearchFragment.kt` + `fragment_feed_search.xml`.
  The `navigation_feed_search` destination now hosts it (same
  destination id, same action from the Feed — the push-over,
  back-to-Feed, nav-bar-hidden behaviour is unchanged). The
  8.94 graph arguments (`search_query`, `standalone_search`)
  on that destination are removed; nothing ever passed them
  there.
- The page searches THE FEED:
  - Primary source = the loaded pool (`FeedPool.entries`,
    hidden items excluded), filtered and RANKED by match
    quality: tier 0 the title starts with the query, tier 1 a
    word in the title starts with it, tier 2 the title merely
    contains it; pool order survives inside a tier. (The old
    in-feed search was contains-only — ranking is the only
    addition.) Results render in the Feed's own 3-column card
    grid — the same `FeedAdapter`, so cards look, like and
    long-press-hide exactly like the Feed's — and open the
    feed watch screen via the same `openWatch` bundle.
  - Deep results: for 3+ character queries, after the
    pre-8.92 timing (600 ms debounce), providers are searched
    via `FeedViewModel.searchAll` — the exact call the old
    in-feed search merged in — and the new hits append below
    the pool hits in the same grid, opening the watch screen
    the same way those merged results did before 8.92. The
    fragment uses its OWN FeedViewModel instance; searchAll
    is stateless (global provider list), so the Feed tab's
    ViewModel and pool build are untouched. The "empty" state
    follows the old in-feed rule: it shows only once every
    source has answered (spinner while the deep search is
    pending and the pool has no hits).
  - Landing (empty query): 🔥 "Trending searches" heading +
    a Material ChipGroup of the pool's top titles — the smart
    Search landing's pool recipe (cleaned titles, deduped,
    id-like placeholders skipped, capped at 12). A chip tap
    runs that search on THIS page. Recent feed searches:
    NONE are shown — the feed search has never kept a history
    store (verified: no prefs key exists for it), so none is
    invented.
- REMOVED (the 8.94 Job 3 reuse machinery): the standalone
  block in `SearchFragment` (back-arrow wiring + the
  shared-ViewModel landing reset), the `STANDALONE_SEARCH`
  constant, the 8.95 `sq` hoist (the declaration is a local
  inside `binding.apply` again, as before 8.94), the now
  unused `findNavController` import, and the
  `search_back_button` view in `fragment_search.xml`. The
  main Search TAB is otherwise byte-identical in behaviour,
  and the SEARCH_QUERY / filmography path (which targets the
  `navigation_search` destination) is untouched.

## Job 2 — Category loading: two-phase pass, mirror skeleton, hang, gap, empty categories

### (a) Two-phase chrome pass
`launchChromePass` now runs PHASE 1 for the first pass over
any pool instance (`chromePhase1Pool` identity), PHASE 2 for
every later pass over the same instance. A phase-1 post
always queues exactly one phase-2 follow-up.

- Phase 1 (`computeChromePhase1` / `computeTagItemsPhase1` /
  `computePresentTagsPhase1`) uses ONLY data already inside
  the pool entries: provider section tags (FeedSectionTags
  over the entries' shelf provenance), the responses' OWN
  provider scores (Top 250, must-watch, region Top rows —
  score-ordered), the listings' declared types (must-watch
  Series), title-keyword genres, and the per-provider rows.
  NO MetadataResolver and NO TitleResolver calls anywhere in
  phase 1 (the per-pass meta memo is not even installed), so
  the pass is short string/set work over the pool and posts
  as soon as page 1 lands. Slot order matches phase 2
  wherever the two overlap.
- Phase 2 is the previous full pass, unchanged (metadata
  genres/regions, resolved ratings, New Release variants,
  the ≥80% region dedupe). It merges behind phase 1 through
  the existing signature gate: a model that did not change
  never reaches the adapter, so the merge cannot flicker;
  rows phase 2 adds/refines arrive as one clean swap.

### (b) Skeleton that mirrors the final page
- NEW `feed_category_row_skeleton.xml` — a structural mirror
  of `feed_category_row.xml` + `feed_row_item.xml`: same root
  margin (12dp) and bottom padding (2dp), same header paddings
  (16/10/16/6), a 20dp title bar (the 16sp title's line) with
  the 34×3dp red underline block under it, a 29dp pill where
  "View All" sits, and a poster strip (10dp horizontal
  padding) of 104dp cells whose posters are exactly the real
  104×156dp with title/meta bars at the real line heights
  (15dp/12dp, margins 5dp/1dp). Row height is therefore the
  real row's height — the skeleton → real swap shifts nothing.
- In-list: NEW `FeedChromeSkeletonAdapter` (3 mirror rows,
  each under its own facebook-shimmer frame — the app's
  existing shimmer treatment) sits in the ConcatAdapter
  between the category rows and the grid while a chrome post
  is outstanding (`chromeAwaitingPost`), with the span lookup
  counting rows + skeleton items as full-span.
- Header chips: a shimmer overlay of 5 pill placeholders
  (`feed_chips_skeleton`, new `feed_skeleton_pill` drawable)
  covers the chips row. The real chips view goes INVISIBLE —
  never GONE — underneath, so the floating header's measured
  height (and the grid's top padding derived from it) is
  identical before and after the swap.
- Full-screen (pool still loading): the existing
  `feed_skeleton` content now mirrors the final structure —
  two skeleton category rows above the poster grid (was: a
  bare 3×3 poster grid).
- `updateChromeSkeleton()` derives all of it in one place:
  skeleton shows exactly while a chrome post is outstanding
  AND the Feed is browsing (no live query/filter).

### (c) Hang — the first-seconds contention, and what moved
Profiled path (pool post → first chrome post), pre-8.96:
the observer SYNCHRONOUSLY fired `resolveMeta` for the pool's
first 24 entries on the main thread, and each resolution that
landed re-armed `metaApplyRunnable` → `scheduleChromeUpdate`;
card binds added more resolves; all of them funnel into
MetadataResolver's shared Semaphore(3) — while the chrome
pass (the thing that makes rows/chips appear) competed for
the same CPU/semaphore window, and its 8.95 meta-memo pass
was the most expensive consumer in it. The first-seconds
"hang + late categories" is that contention.
Fix: the pre-kick is now DEFERRED. The observer stores the
pool head (`pendingPreKick`) and fires it
(`runPendingPreKick`) only (i) when the phase-1 pass posts —
the first rows are on screen before the burst starts, and
phase 1 needed none of it — or (ii) immediately when no pass
will run (a search/filter state). Phase 1 itself does no
resolver work at all (see (a)), so nothing competes with it.
The 8.92 scroll-pause for binds is unchanged.

### (d) Scroll gap — cause and fix
Cause (code-verified, geometry matched to his screenshot):
the grid's top padding is a RESERVE for the floating header
(`updateHeaderPadding`: `headerFullHeight + 6dp`), but it
never tracked the header's visibility. Auto-hide slides the
header up by its full height (translationY) while the
reserve stays — leaving a header-tall (~170dp) empty black
band above the first row whenever the list rests near the
top with the header hidden: exactly the band he circled over
the Trending row.
Fix: the reserve is now the header's VISIBLE height —
`(headerFullHeight + translationY)` clamped ≥ 0, + 6dp —
and the header animator re-applies it every frame, so the
padding follows the header exactly (content stays glued to
its bottom edge; fully hidden, the reserve collapses to
6dp). `applySearch`'s padding refresh uses the same formula,
so it can no longer resurrect the band while the header is
hidden.

### (e) Empty categories — cause and fix
View All / chip taps re-filtered the grid through a SECOND
rule that did not match the rows' rule. The rows are built
by `tagPredicate` = provider section tags ∪ resolved
metadata ∪ title keywords; the GENRE filter slot used
`genreMatchesEntry` alone (resolved ∪ keywords — NO section
tags). An item shelved by its provider under a genre shelf
("Animation", "Comedy", "Horror", …) whose title carries no
genre keyword and which has no resolved metadata therefore
appeared in the row but filtered to an EMPTY grid — his
"ক্যাটাগরির মধ্যে কিছুই থাকে না". (Smart/section tags already
matched, because their filter path goes through
tagPredicate; provider rows matched via apiName.)
Fix, two layers: (i) the genre filter now goes through
`categoryMatchesEntry` — which IS tagPredicate for
non-curated tags — so chip filtering ≡ row membership by
construction; (ii) View All no longer filters at all — it
opens the category page (Job 3), whose list is the row's own
computed list, eliminating the whole desync class for rows.

## Job 3 — 20-item cap + full category page
- Row cap verified: rows are built with `.take(ROW_ITEM_CAP)`
  (20) in both phases, and the phase passes memoise each
  tag's full list once (`itemsOf`) — the row never needs
  more than the cap to render.
- NEW `FeedCategorySession` (FeedViewModel.kt): every chrome
  post stores each row's FULL uncapped list, keyed by row
  name, exactly as computed by the pass (`fullLists` on
  ChromeResult). 
- NEW `ui/feed/FeedCategoryFragment.kt` +
  `fragment_feed_category.xml` + the
  `navigation_feed_category` destination (action from
  `navigation_feed`; arguments `tag` + `title`). A row's
  View All (or title tap) now navigates there instead of
  re-filtering the grid; chips keep filtering the grid
  (unchanged `applyCategoryTab` path). The page shows the
  category's complete pool list in the Feed's 3-column grid
  (same FeedAdapter; tap → watch; long-press → hide), titled
  with the row's own title. Back returns to the Feed; the
  destination is not in MainActivity's nav-visible list, so
  the bottom bar hides while it is open (same as the watch
  and search pages).
- Provider rows exceed the pool: on open, the page fetches
  ONE extra homepage page of that provider — page 3, since
  the standard first pool build already carries pages 1–2 —
  through `APIRepository.getMainPage`, mapped exactly like
  FeedViewModel's pool build (shelf provenance kept, Family
  Mode section/item filters applied), URL-deduped against
  what the page already shows and hidden-filtered, appended
  behind a footer spinner; any failure simply ends the
  fetch. (Pull-to-refresh may have advanced the pool past
  page 3 already — dedupe makes the fetch yield fewer, never
  duplicate, additions.)
- Session-loss fallback: if the session map is gone
  (process death), a provider page rebuilds from the live
  pool by apiName; a tag page shows its empty state rather
  than inventing a second membership rule.

## Job 4 — Nav label truncation
`activity_main.xml`'s BottomNavigationView now sets
`itemTextAppearanceActive/Inactive` to two new styles
(`NavLabelActive` / `NavLabelInactive` in styles.xml,
parent `TextAppearance.Material3.LabelMedium`, textSize
11sp). The numbers: at 360dp wide, six items get 60dp each;
"Downloads" is 9 glyphs — at the Material default 12sp that
is ~56dp before item padding (hence "Downlo…"), at 11sp it
is ~51dp and fits the 60dp slot; "Settings" (8 glyphs,
~46dp) fits with room to spare. Nothing else about the bar
changed (80dp height, tonal indicator pill, colours).

## Files

New: `ui/feed/FeedSearchFragment.kt`,
`ui/feed/FeedCategoryFragment.kt`,
`ui/feed/FeedChromeSkeletonAdapter.kt`,
`res/layout/fragment_feed_search.xml`,
`res/layout/fragment_feed_category.xml`,
`res/layout/feed_category_row_skeleton.xml`,
`res/layout/feed_chrome_skeleton_row.xml`,
`res/drawable/feed_skeleton_pill.xml`, this file.
Changed: `ui/feed/FeedFragment.kt`, `ui/feed/FeedViewModel.kt`
(FeedCategorySession), `ui/feed/FeedCategoryRowsAdapter.kt`
(KDoc), `ui/search/SearchFragment.kt` (standalone code
removed), `res/layout/fragment_feed.xml`,
`res/layout/fragment_search.xml` (back button removed),
`res/layout/activity_main.xml`, `res/values/styles.xml`,
`res/values/strings.xml` (feed_search_empty),
`res/navigation/mobile_navigation.xml`.

## Deviations / notes

- Phase 1 has no region-variant dedupe (Top vs plain rows
  can both appear); phase 2's dedupe applies on merge, as a
  single signature-gated swap.
- Phase 1's Top K/C-Drama rows are section-membership only
  (metadata-derived members join in phase 2), and there are
  no New Release rows in phase 1 (they need resolved years).
- The category page's extra fetch is homepage page 3 (see
  Job 3 for why); tag categories are pool-complete by
  construction, so they fetch nothing.
- Feed search recents are not shown (no store exists — see
  Job 1).
