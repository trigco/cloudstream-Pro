# Phase 8.87 — Feed hero stability fix + wider genres + smart Search page

Three parts: (1) the 8.85 Feed hero's scroll/overlap bug, diagnosed
from the code and fixed structurally; (2) the Feed genre vocabulary
widened from 9 to 18 genres behind one matcher; (3) the Search page
gets a smart landing (trending chip cloud) and content-type tabs.
Code only — no push.

## Files changed

| File | Change |
|---|---|
| `ui/feed/FeedHeroHeaderAdapter.kt` | **NEW** — the hero as a RecyclerView header adapter (pager, backdrop, pre-warm, clipping setup). |
| `res/layout/feed_hero_header.xml` | **NEW** — the hero card as a list item, explicit clipping at every level. |
| `res/layout/fragment_feed.xml` | The 8.85 root-level hero overlay box (`feed_hero_box` and its pager/backdrop views) removed; explanatory comment left in its place. |
| `ui/feed/FeedFragment.kt` | Hero rewired to the header adapter (ConcatAdapter, full-span position 0, padding no longer fakes hero space); backdrop/pre-warm code moved out to the adapter; genre vocabulary widened + unified matcher. |
| `ui/search/SearchFragment.kt` | Content-type tabs + smart landing built in code (phone only); results rendering extracted to `submitMasterResults()` / `submitGridResults()` over kept raw payloads; history observer also feeds the landing; clear-history dialog extracted to `showClearHistoryConfirm()`. |
| `res/drawable/ic_whatshot_24.xml` | **NEW** — flame icon (Material whatshot) for the trending heading. |
| `res/values/strings.xml` | **NEW** string `search_trending` = "Trending searches" (grepped first: no trending/everyone key existed). Tab labels reuse existing `all` / `movies` / `tv_series` / `anime` strings. |

---

## Part 1 — Feed hero bug: diagnosis and fix

### Device symptom
Scrolling the Feed, hero posters draw **outside** the hero card,
stacked over the poster grid; the hero also rides up over content
instead of scrolling away.

### Root cause (evidence, not a guess)

**Cause A — the hero was not part of the scrolling content at all.**
In the 8.85 tree, `fragment_feed.xml` placed `feed_hero_box` as a
direct child of the fragment's root `FrameLayout` — a *sibling* of
the `SwipeRefreshLayout`, outside the `RecyclerView`. It was
positioned purely in code: `FeedFragment.updateHeroChrome()` set
`lp.topMargin = headerFullHeight + dp(12)` on the box and faked its
space in the list by inflating `feedRecycler`'s top padding by
220dp + 12dp. A view positioned like that cannot scroll with the
list: on scroll the grid slides *underneath* the pinned box, and
when the floating header collapses (translationY animation in
`animateHeader`) the box does not follow it — the hero detaches and
sits on top of rows. That is the "rises over content" half of the
report, reproduced by reading the layout, no device needed.

**Cause B — page containment depended on clipping that nobody set.**
The shared `HomeScrollTransformer.transformPage()` cancels each
page's layout offset (`page.translationX = -position * page.width`),
so *every* attached hero page renders stacked at the same slot and
only its alpha/scale differ. Containment is therefore entirely the
ancestors' job. In the Feed hero's ancestor chain (inner
`FrameLayout` → `CardView` → `feed_hero_box` → root `FrameLayout`,
plus the `ViewPager2` and its inner `RecyclerView`) **no view set
`clipChildren` or `clipToPadding` in our code** — contrast the Home
hero, whose pager sits inside the home `RecyclerView`'s first item,
where the outer RecyclerView clips children to its own bounds on
every frame and the item scrolls away with the list. With no
clipping scroll container above it, the Feed pager's mid-fade pages
(alpha > 0 for |position| < 1) painted past the 220dp card over the
grid below — the two stacked posters in the user's screenshot.

### Fix
- The hero is now **the grid's first list element**:
  `feed_hero_header.xml` + `FeedHeroHeaderAdapter`, joined to the
  feed cards with a `ConcatAdapter`; a `GridLayoutManager`
  span-size lookup gives position 0 the full 3-column span while
  the hero is showing. It scrolls away with the content like Home's
  hero, and `feed_recycler` clips it like every other row.
- **Explicit clipping at every level** of the hero's own chain as
  well: `android:clipChildren="true"` + `clipToPadding="true"` on
  both frames and on the `ViewPager2` in XML, and in
  `FeedHeroHeaderAdapter.onCreateViewHolder` the pager's inner
  `RecyclerView` (child 0, only reachable from code) gets
  `clipChildren = true` / `clipToPadding = true`.
- `offscreenPageLimit` is pinned to **1** (the ViewPager2 effective
  default): only the settling pair of pages is ever attached, and
  both now stay strictly inside the card.
- The settled-poster **backdrop layer stays inside the card**
  (first child of the card's frame, under the pager) and still pins
  the settled poster so the crossfade never flashes black.
- Behaviour parity: auto-advance is still the fragment's 5s
  handler, now calling `heroHeaderAdapter.advance()` (which checks
  showing / ≥2 entries / not user-dragging, as before); the
  crossfade + pop transformer is the same `HomeScrollTransformer`;
  the backdrop update + next-poster Coil pre-warm moved *verbatim*
  from `FeedFragment.onHeroSettled` into the adapter's `onSettled`
  (including the `coil3.network.httpHeaders` extension import that
  8.85b established). The hero still shows only over the
  unfiltered, unsearched feed, still takes the pool's top 6
  poster-bearing entries, and a tap is still the card's tap
  (`openWatch` direct play). The grid's top padding now reserves
  only the floating header's height — the hero takes its own room
  in the flow.

## Part 2 — Wider Feed genres

- The single shared vocabulary (`FeedFragment.genreKeywords`) went
  from 9 tags to the standard 18: **Action, Adventure, Animation,
  Comedy, Crime, Documentary, Drama, Family, Fantasy, History,
  Horror, Music, Mystery, Romance, Sci-Fi, Thriller, War, Western**
  — each with its title keywords: the genre name plus spelling
  variants where the name alone misses real titles (Animation:
  "Anime", "Animated"; History: "Historical"; Music: "Musical";
  Romance: "Romantic"; Sci-Fi: "Sci Fi", "Science Fiction").
- One matcher now serves all three call sites —
  `genreMatches(title, genre)` (any keyword, case-insensitive
  contains, i.e. the matching rule exactly as it was, over a wider
  keyword set): the chips' dimming check (`refreshChipAlphas`), the
  genre filter (`applyFeedFilters`), and the Filters sheet Genre
  tab's presence check. Chips and sheet cannot drift apart.
- **Deviation (flagged):** the brief described the chips rule as
  "shows only genres present in the pool". The actual existing rule
  (Phase 8.36, a deliberate earlier decision) is: the chip row
  always shows every genre and *dims* zero-match genres to 0.45
  alpha, because a dimmed chip advertises that provider search can
  still find more; the Filters sheet's Genre tab is the one that
  lists present-only. That behaviour was kept untouched — only the
  vocabulary and the matcher changed.

## Part 3 — Smart Search page

### (a) What the "top searches" block actually was, and what was removed
There is **no separate top-searches feature anywhere in the app**
(grep over `ui/search` + all `values*` resources: no top/trending
search code or strings). The pre-query landing presentation *was*
the search-history `RecyclerView` (`search_history_recycler` +
`SearchHistoryAdaptor` over `SearchViewModel.currentHistory`) — a
full-screen plain list of past queries. Top searches and history
were the same view.

On the **phone**, that list is removed as the landing presentation:
it is never shown again (`isVisible = false` in `setupSmartLanding`
and the query-change toggle now requires `!isLayout(PHONE)`). The
view and adapter **remain in place** because `fragment_search_tv.xml`
mirrors every phone id into the shared `FragmentSearchBinding` —
the TV landing is still that history list, unchanged. **History
handling itself is untouched**: queries are still recorded by
`SearchViewModel.search()`, `updateHistory()` still reads the same
store, and the data now also feeds the phone landing's chips.

### (b) Trending searches landing (phone)
- Shown exactly when the old landing was (empty query): a flame
  icon (`ic_whatshot_24`, red) + the new `search_trending` heading
  ("Trending searches" — not "everyone is searching"; there is no
  backend to claim that from) over a `ChipGroup` cloud.
- Chips = the user's recent searches first (newest first, from
  `currentHistory`), then deduplicated titles sampled from
  `FeedPool.entries` in pool order (the popularity proxy: the feed
  build interleaves providers evenly and weaves liked types /
  providers forward), cleaned with
  `SearchResultBuilder.cleanPosterTitle`, skipping ID-like names
  via `TitleResolver.isIdLike`. Dedupe is case-insensitive across
  both sources; total capped at **12**.
- A chip tap runs that search (`mainSearch.setQuery(text, true)`,
  the same path as a suggestion tap). Recent-sourced chips keep
  their remove affordance: a close icon that deletes that history
  entry (same `removeKey` + `updateHistory` as the old row's X).
  The old footer's clear-all survives too: a quiet "Clear history"
  text row under the chips (visible only while recents exist)
  running the *same* confirmation dialog — the dialog code was
  extracted from the history callback into
  `showClearHistoryConfirm()`, which both now call.
- Fallbacks: empty pool → recents only; both empty → the landing
  hides entirely (`landingHasContent`). Chips refresh on history
  updates, on returning to a blank query, and on `onResume` (the
  pool may have been built since the page was last shown).

### (c) Content-type tabs
- A tabs row **All | Movies | Series | Anime** sits under the
  search bar, visible on the landing and over results. Labels reuse
  existing strings (`all`, `movies`, `tv_series`, `anime`); styling
  mirrors the Feed chips (selected = `feed_chip_selected` + black
  text, else `feed_search_bg` + white).
- The tabs filter **items by `SearchResponse.type`** (TvType sets
  follow the app's own convention from `HomeFragment.getPairList` —
  Anime = Anime + AnimeMovie + OVA; Series = TvSeries +
  AsianDrama folded in, since the 4-tab bar has no separate Asian
  tab; Movies = Movie).
- Wiring: the `currentSearch` and `searchResponse` observers now
  keep their **raw payloads** (`lastSearchMap`, `lastGridList`);
  rendering moved into `submitMasterResults()` /
  `submitGridResults()`, which apply the existing film-quality
  filter **and** the tab filter. Switching a tab just re-renders
  from those copies — **no provider is re-queried**, and later
  observer updates (providers finishing, section expands) render
  through the same filtered path. A section the tab empties drops
  out by the existing Phase 8.9 empty-section rule.
- **Why not `selectedSearchTypes`:** that state is provider
  *selection*, consumed at query time inside `search()` to decide
  which providers run. Reusing it for the tabs would re-query
  providers on every tab switch — exactly what the brief forbids.
  The tabs are a display filter over results already fetched.
- **Deviation (flagged):** `submitGridResults()` now submits even
  an empty filtered list (the old observer skipped empty lists).
  Necessary so switching to a tab with no matches clears the grid
  instead of leaving the previous tab's results standing; the only
  empty-success producer is `clearSearch()`, where the grid is
  hidden behind the landing anyway.
- Tabs + landing are **phone-only**, built programmatically:
  `fragment_search_tv.xml` shares this fragment and binding and
  mirrors its ids, so new XML views would have needed TV mirrors
  and focus-flow work; the existing code already guards phone-only
  search UI the same way (`isLayout(PHONE)`). TV search behaviour
  is byte-for-byte unchanged.

## Verification
- XML parses: `fragment_feed.xml`, `feed_hero_header.xml`,
  `ic_whatshot_24.xml`, `strings.xml`.
- Kotlin delimiter balance exact on `FeedFragment.kt`,
  `FeedHeroHeaderAdapter.kt`, `SearchFragment.kt`.
- No dangling references: `feedHeroBox` appears nowhere in
  `app/src`; `feedHeroPager` / `feedHeroBackdrop` exist only as
  `FeedHeroHeaderBinding` fields used by `FeedHeroHeaderAdapter`;
  the imports orphaned by the hero move (ViewPager2,
  SingletonImageLoader, NetworkHeaders, httpHeaders, ImageRequest,
  crossfade, USER_AGENT, loadImage, getImageFromDrawable,
  FrameLayout, HomeScrollTransformer) were removed from
  `FeedFragment.kt`.
- Every new reference resolves: `R.string.search_trending` (added),
  `R.drawable.ic_whatshot_24` (added), `feed_chip_selected` /
  `feed_search_bg` / `extButtonBackground` / `colorPrimaryRed`
  (existing), `SearchResultBuilder.cleanPosterTitle`,
  `TitleResolver.isIdLike`, `FeedPool.entries`, `ConcatAdapter`.
- Lint discipline kept: no `android:tint` added (the flame's tint
  is set in code via `imageTintList`; the vector follows the
  project's baseline-icon format), new LinearLayouts declare
  `android:orientation`, no annotation/class splits, no `${` in raw
  strings, strings grepped before adding.
