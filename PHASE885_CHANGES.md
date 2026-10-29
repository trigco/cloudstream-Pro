# Phase 8.85 — Feed becomes Explore-style + hero/nav/font fixes

Reference: SkyStream's Explore page (user screenshots) — top-left page
title, top-right circular filter + search buttons, a compact hero
carousel, sections below, and a Filters bottom sheet with
Lang / Genre / Year / Rating tabs and a big Done button.

## Part A — Feed page restyle (ui/feed)

### A1. Header / top bar (`fragment_feed.xml`, `FeedFragment.setupTopBar`)
- New top bar inside the existing floating header: **"Feed"** title
  (24sp, sans-serif-medium + bold) at the left; at the right two
  42dp circular icon buttons on a new `bg_feed_circle_button`
  (#FF262626 oval):
  - **Filter** (`ic_baseline_tune_24`) → opens the Filters sheet (A4).
    Its tint turns accent-red while any advanced filter is active.
  - **Search** (`search_icon`) → un-hides the header, focuses the
    existing `feed_search_input` and raises the keyboard. The
    existing search field, its watcher, clear button and the
    provider deep-search are untouched.
- The page root already painted `@android:color/black`; it stays
  pure black.
- The **"For You" header treatment** (white title + 34×3dp red
  underline, the 8.73 watch-screen treatment) now also sits at the
  bottom of the Feed page's floating header, above the grid.

### A2. Hero inside Feed (`fragment_feed.xml`, `feed_hero_page.xml`,
### new `FeedHeroAdapter.kt`, `FeedFragment.setupHero/updateHeroItems`)
- Compact carousel: **220dp** tall, full width minus **16dp side
  margins**, **16dp** CardView radius — deliberately not the Home
  500dp hero.
- Items: the feed pool's own top **6** entries that carry a poster
  (`pool` is already Family-Mode-filtered and hidden-filtered
  upstream, so the hero inherits both). Poster fills the card
  `centerCrop`; bottom gradient scrim (`bg_feed_hero_scrim`); title
  + `provider • type` line bottom-left.
- Auto-advance **5s** (paused while dragging), transformer is the
  shared `HomeScrollTransformer` (crossfade + 8.85 pop-out).
- Tap a page → `openWatch(entry)` — exactly the feed card's action
  (direct play flow, DASH gate untouched).
- The hero hides while a search query or any advanced filter is
  active, and its space is returned to the grid (see
  `updateHeroChrome`, which positions the hero under the floating
  header and maintains the grid/skeleton top padding + the
  refresh-spinner rest offset — the offset now follows the measured
  header height instead of the old fixed 132dp).

### A3. Grid → 3 columns (`FeedFragment`, `feed_card.xml`,
### `feed_skeleton_item.xml`, `fragment_feed.xml`)
- `GridLayoutManager(context, 3)` (was 2); skeleton rows are now
  3-across to match.
- Card retune for three-across: margin 6→4dp, radius 14→12dp,
  title 13→11.5sp, meta 11→9.5sp, heart 36→30dp (padding 6dp).
  Poster stays 2:3 `centerCrop`; title under poster; meta line
  stays `provider • type (• ★ rating • DUB/SUB)` from the existing
  adapter logic. Like hearts, long-press hide, title/rating
  resolution, direct play — all unchanged.

### A4. Filters popup (`FeedFragment.showFeedFilters`)
- `BottomSheetDialog` with `AppBottomSheetDialogTheme`; sheet root
  carries `rounded_dialog`, the dialog frame is forced transparent
  (the 8.82 recipe). Drag handle, "Filters" title with tune icon +
  close X, four tabs, a 320dp scroll viewport, and a full-width
  52dp accent-red **Done** button. Selections are staged locally
  and only committed by Done (X / back / outside tap discards);
  committed selections persist for the page's lifetime and combine
  (AND) with the search query and genre chips via
  `applyFeedFilters`, which now wraps every list the page submits
  (pool, local search hits, merged remote hits, hide flow).
- Tabs and **where each field comes from**:
  - **Genre** — 2-column chip grid: All + the genre tags actually
    present in the loaded pool. `SearchResponse` has no genre
    field; presence/matching is the chip row's long-standing
    title-keyword rule over the shared vocabulary (`genreTags`:
    Horror, Comedy, Romance, Thriller, Action, Drama, Family,
    Sci-Fi, Animation).
  - **Year** — 3-column chip grid: All + the distinct years present
    in the pool, descending. Year = `MetadataResolver.yearOf(response)`
    — the `year` field carried by Movie/TvSeries/Anime search
    responses; other response types have none and only pass under All.
  - **Rating** — rows Any / 5.0+ / 6.0+ / 7.0+ / 8.0+ (gold star,
    `ratingStarGold`) with a check on the selected row. Score =
    `FeedFragment.scoreOf`: the item's own provider score
    (`Score.toDouble(10)`) first, else the resolved universal
    rating (fragment's rating-listener cache, then
    `MetadataResolver.cachedFor`). Items with no score pass only
    under Any — nothing is fabricated.
  - **Source** — rows: All + the distinct `FeedEntry.apiName`
    values present in the pool (the provider that supplied each
    item). **This replaces the reference's Lang tab**: feed items
    carry no reliable content-language field, so the 4th tab is
    the provider/source, labelled "Source".
- The empty state's "Show all" reset now also clears the advanced
  filters.

### A5. Info fix (`fragment_feed_watch.xml`)
- `feed_watch_more` is now a compact pill chip (`feed_pill_bg`,
  white bold 12sp text, leading ⓘ `ic_outline_info_24`, **no
  chevron**) packed immediately after the title in the existing
  packed chain (fixed 8dp gap) and constrained top/bottom to the
  title. It stays a `TextView`, so `FeedWatchFragment`'s existing
  `setText(Info/Less)` toggle and click behaviour are unchanged.

## Part B — Home / hero / nav fixes

### B6. Hero black flash (`fragment_home_head.xml`,
### `fragment_home_head_tv.xml`, `HomeParentItemAdapterPreview`,
### Feed hero in `fragment_feed.xml`/`FeedFragment.onHeroSettled`)
- Both heroes get a **backdrop ImageView under the pager**
  (`home_hero_backdrop` / `feed_hero_backdrop`, centerCrop) showing
  the currently settled item's poster, so mid-crossfade the eye
  sees poster, never the black frame.
- Home: updated in `HeaderViewHolder.updateHeroBackdrop`, called
  from `onSelect` (which runs exactly on page settle — pager
  callback and the explicit bind in `updatePreview`). Poster
  choice matches `HomeScrollAdapter`: `backgroundPosterUrl ?:
  posterUrl`, with `posterHeaders`, no crossfade; the **next**
  item's poster is pre-warmed via a bare Coil `ImageRequest`
  enqueue on the singleton loader (same UA + poster headers as
  `ImageLoader`).
- Feed: same pattern in `onHeroSettled` (pager callback), warming
  the next hero poster the same way.

### B7. Hero pop-out (`HomeScrollTransformer`)
- The transformer now also scales each page **0.96 → 1.0** as
  |position| → 0 (outgoing page shrinks back), combined with the
  8.84 crossfade and translation cancellation. Pure function of
  scroll position — smooth, no bounce. Shared by Home + Feed heroes.

### B8. Nav bar black (`bg_nav_floating.xml`)
- Floating bottom-nav pill fill: `?attr/primaryGrayBackground` →
  **#FF000000** pure black; the 1dp #33FFFFFF stroke stays so the
  pill still reads over black content. (Used only by
  `activity_main.xml`'s BottomNavigationView.)

### B9. View All black (`bg_home_view_all.xml`)
- Fill: `@color/extButtonBackground` (chip gray) → **#FF000000**,
  plus a subtle 1dp #FF3A3A3A outline stroke; text/icon stay white.
  Applies to all three parent layouts that use the drawable.

### B10. Medium fonts
- `android:fontFamily="sans-serif-medium"` added (existing bold
  kept) on: Home section titles (`home_child_more_info` in
  homepage_parent / _tv / _emulator) and the Continue Watching
  header (`home_watch_parent_item_title`), the **View All** text in
  all three parent layouts, the Feed page title, the Feed "For You"
  header, and the watch screen's "For You" label.

## Files

Edited:
- `app/src/main/res/drawable/bg_nav_floating.xml`
- `app/src/main/res/drawable/bg_home_view_all.xml`
- `app/src/main/res/values/strings.xml` (10 new `feed_*` strings)
- `app/src/main/res/layout/fragment_feed.xml`
- `app/src/main/res/layout/feed_card.xml`
- `app/src/main/res/layout/feed_skeleton_item.xml`
- `app/src/main/res/layout/fragment_feed_watch.xml`
- `app/src/main/res/layout/fragment_home_head.xml`
- `app/src/main/res/layout/fragment_home_head_tv.xml`
- `app/src/main/res/layout/homepage_parent.xml`
- `app/src/main/res/layout/homepage_parent_tv.xml`
- `app/src/main/res/layout/homepage_parent_emulator.xml`
- `app/src/main/java/com/lagradost/cloudstream3/ui/home/HomeScrollTransformer.kt`
- `app/src/main/java/com/lagradost/cloudstream3/ui/home/HomeParentItemAdapterPreview.kt`
- `app/src/main/java/com/lagradost/cloudstream3/ui/feed/FeedFragment.kt`

New:
- `app/src/main/res/drawable/bg_feed_hero_scrim.xml`
- `app/src/main/res/drawable/bg_feed_circle_button.xml`
- `app/src/main/res/drawable/bg_feed_filter_selected.xml`
- `app/src/main/res/drawable/bg_feed_drag_handle.xml`
- `app/src/main/res/layout/feed_hero_page.xml`
- `app/src/main/java/com/lagradost/cloudstream3/ui/feed/FeedHeroAdapter.kt`

## Deviations / notes

- **Lang → Source** in the Filters sheet (see A4): no reliable
  content-language field exists on feed items; nothing fabricated.
- **Genre is a title-keyword match**, not metadata — SearchResponse
  carries no genre field; this is the same rule the genre chips
  have always used, now shared via one `genreTags` list.
- The Feed page's "For You" header lives at the bottom of the
  floating header, so it slides away with the header on scroll
  (like the chips) instead of being a static row above the grid.
- The 3-column card retune also reaches the watch page's "Up next"
  list — it shares `FeedAdapter`/`feed_card.xml` (still 2 columns
  there, slightly smaller type).
- The Filters sheet's option area is a fixed-height (320dp)
  scroll viewport regardless of tab, mirroring the reference
  sheet's stable size.
- Home hero backdrop was added to the TV head too (the same
  crossfade flash existed there); TV gets no pop-out change beyond
  the shared transformer.
- Untouched by design: feed direct-play + DASH gate, Up next,
  Home hero data flow, the 8.84 provider pill, hide-category,
  custom lists, Family Mode filtering (pool-level, upstream of
  everything added here).

## Verification
- All touched XML parses; every new `@drawable/@string/@layout/
  @color/@dimen/@id` reference in touched files resolves (the 5
  pre-existing TV-layout refs flagged by the checker live in
  donottranslate-strings/theme attrs and predate this build).
- No duplicate string keys introduced.
- Kotlin delimiter balance exact on all four touched/new .kt files.
- No `android:tint` added (only `app:tint`); every new LinearLayout
  declares `android:orientation`; no code inserted between any
  annotation and its class; no `${` in raw strings.
