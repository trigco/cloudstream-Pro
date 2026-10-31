# Phase 8.90 — Feed fixes: hero, header/tags, category rows, Up-next grid

Device verdicts on the 8.89 build (2026-10-29 20:18 + clarifications
20:22 / 20:24): the hero looked broken, the category row was nowhere
to be seen, the header still carried both a search icon and an inline
search bar, and the search button did not lead to the smart Search
page. One batch, four fixes.

## 1. Hero: no more cropped portrait posters + type label agrees with the cards

His screenshot: the hero showed the "Epic" poster massively zoomed
(portrait 2:3 center-cropped into the 16:9 frame, faces sliced), and
the hero meta said "Netflix • Anime" while the grid card for the same
title said "Netflix • Movie".

- Root cause of the crop: `feed_hero_page.xml` loaded the poster with
  `centerCrop`. SearchResponse carries **no** landscape backdrop
  field (only LoadResponse has `backgroundPosterUrl`), so there is no
  better image to prefer for feed entries — the fix is the premium
  two-layer treatment in the page layout + `FeedHeroAdapter`:
  - backdrop layer: the same poster decoded at 48×27 (heavy
    downsample → upscaling reads as a soft blur on every API level),
    at 65% alpha under a 35% black overlay, plus a genuine
    `RenderEffect` blur (16px, CLAMP) on API 31+;
  - foreground layer: the sharp poster **fitCenter** — contained,
    never cropped.
  The bottom scrim + title/meta overlay are unchanged. (Coil 3.3 core
  in this project ships no blur transformation, hence the
  downsample + RenderEffect approach instead of a library blur.)
- Type label: the hero now reads the type from the **same source as
  the grid cards** — `TitleResolver.cachedType(url) ?: response.type`
  (Phase 8.71 rule) — instead of `response.type` alone.

## 2. Feed header: inline search bar removed, search button opens Search, tags rebuilt

- `fragment_feed.xml`: the inline "Search feed..." EditText bar (and
  its clear button) is **removed entirely**. The header is now: Feed
  title + circular filter/search buttons, the tag row, the "For You"
  header.
- The search (magnifier) button now opens the app's **main Search
  tab** — the 8.87 smart landing (Trending searches + type tabs) —
  via the same `selectedItemId = R.id.navigation_search` switch on
  `nav_view` / `nav_rail_view` that MainActivity's deep-link handler
  uses. It no longer focuses a feed-local field.
- FeedFragment: the removed field's wiring (TextWatcher, clear
  button, focus/keyboard code) is gone; the query the search/filter
  code paths were built around now lives in a `searchQuery` state
  field (always empty in practice — deep provider search from the
  Feed is superseded by the main Search tab).
- The chips row is now a **pool-derived tag row** over the full
  shared vocabulary: All + Trending + Top 250 + region categories
  present + Emotional (when present) + genres present (resolved ∪
  keyword). Only tags with pool items get a chip; the active
  filter's tag stays listed so it can be tapped off. Selected =
  white pill (existing style). A tap filters the grid through the
  existing `applyFeedFilters` path (genres → genre slot, other tags
  → category slot); tapping the selected tag clears back to All.
  Pre-8.90 the chips typed the tag into the search field and
  title-searched it — that behaviour is gone. The Filters sheet is
  untouched (the style he praised).

## 3. Category rows in the Feed, Home-style

"ক্যাটাগরি feed এ দেখাও যেমনটা home এ হচ্ছে" — the 8.88 tile row
(≥3 resolved items, smart categories only) is **retired**:
`FeedCategoriesAdapter` + `feed_categories_header.xml` are deleted,
replaced by `FeedCategoryRowsAdapter` + `feed_category_row.xml` +
`feed_row_item.xml`: real Home-style sections (8.84 header — title
sans-serif-medium + 34×3dp red underline + "View All ›" pill) over a
horizontal poster strip (104dp cells, Home poster radius, title +
provider • type under each, same type source as the cards).

- Vocabulary (his list, completed): **Trending** (pool head, pool
  order) → **Top 250** (highest-rated pool items by provider Score /
  resolved rating, descending; only rated items; the label is his
  name for it even though the pool is smaller) → the curated rows
  below → then Emotional, all region categories (K-Drama, C-Drama,
  J-Drama, Thai Drama, Turkish Dizi, Anime, Bollywood, Hollywood)
  and **all 18 canonical genres**, sorted by item count descending.
- Curated rows (reference-style, emoji in the row TITLES only —
  chips stay plain text):
  - **🏆 Must-watch Drama 🏆** — Drama items; when ≥4 of them are
    rated ≥7.0 the row is exactly those, best first; otherwise the
    Drama set in pool order (a rating-less pool still gets the row).
  - **✨ Must-watch Series ✨** — same curation over series-typed
    items (TvSeries / AsianDrama / Anime / OVA, details-page type
    first as elsewhere).
  - **⚡ Top Anime**, **🔥 Top K-Drama**, **🔥 Top C-Drama** — the
    region's items sorted by rating descending (unrated sink last,
    pool order kept). The reference's "Most Popular …" naming is
    merged into these single Top rows per region.
  - **🆕 New Release K-Drama** / **🆕 New Release C-Drama** — the
    region's year-carrying items (`MetadataResolver.yearOf`, the
    search-response year), most recent first; current/previous-year
    items form the row when ≥4 exist, else all year-known items
    year-descending.
  - Title decorations: 🔥 Trending, ⭐ Top 250, ✨ Emotional,
    💘 Romance; plain genre/region rows keep clean titles.
  - **Dedupe across a region's variants** (priority Top > New
    Release > plain): two variant rows whose shown items are ≥80%
    identical (of the smaller row) collapse to the higher-priority
    one. Pairs under 80% all stay.
- **Emotional** = Drama/Romance by the union genre rule, a resolved
  melodrama genre, or an emotional keyword (emotional / tearjerker /
  melodrama / heartfelt) in the title.
- A candidate needs ≥4 pool items to become a row; caps: **12 rows**,
  **15 items per row** (Android 8.1 perf).
- ConcatAdapter order: hero header → category rows → "For You" grid.
  Rows hide while any filter/search is active (they belong to
  browsing) and recompute as metadata progressively resolves
  (existing 900ms debounce).
- "View All ›" (or the row title) applies that tag as the grid
  filter and scrolls to the first grid card; a poster tap plays
  directly, like a grid card.
- One shared rule (`categoryMatchesEntry`) drives rows, chips and
  the grid filter so the three never disagree.

## 4. Feed player Up next: 3 columns

`FeedWatchFragment`: Up next `GridLayoutManager` spanCount 2 → 3.
The feed card was already sized for three-across in 8.85 (11.5sp
title / 9.5sp meta), so no card layout change was needed.

## Deviations / notes

- **No Ongoing rows** (Ongoing K-Drama / C-Drama / Ongoing Series):
  pool entries are `SearchResponse`s, and neither the interface nor
  any subclass carries an airing-status field — `ShowStatus` exists
  only on `LoadResponse` (details level), and `MetadataResolver`'s
  Meta has no status either. Episode counts on
  `TvSeriesSearchResponse`/`AnimeSearchResponse` say nothing about
  airing. Per the standing rule, nothing was faked from years or
  "S01E62"-style titles: the rows simply do not appear. If a future
  build resolves LoadResponses for pool heads, the slot is reserved
  in the curated order (after Must-watch Series).
- Hero backdrop: no landscape image exists on SearchResponse, so
  "prefer a backdrop when present" lands as the blurred-poster
  treatment for effectively every entry (documented in §1).
- The Feed's deep provider search (3+ chars merged remote results)
  is unreachable now that the text field is gone — the code path
  remains intact behind `searchQuery`, and the main Search tab is
  the app's search surface, per his order.
- TV layouts/behaviour untouched; Filters sheet untouched; player
  untouched. Clean flat UI, pure-black page, red only for
  Play/active states — unchanged.
