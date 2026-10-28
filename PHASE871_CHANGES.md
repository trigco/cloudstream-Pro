# Phase 8.71 — Feed/Home polish batch (his 2026-10-28 feedback, 13 points + 2 follow-ups)

Branch: feed-polish (from master 13be0514 / v1.3.0). One batch, per his rule.

1. **Feed player icon backgrounds transparent** — `feed_center_button_bg` is now a
   transparent oval (was #B3262626): the cast/audio/subtitle circles and the
   center play/pause button lose the dark disc. (Feed-card heart shares the
   drawable and goes transparent too.)
2. **Pills one line, natural width** — Sources/Episodes/Download/Add-to-list all
   in ONE row inside a HorizontalScrollView, wrap_content pills. Deliberately
   NOT equal quarters: at 360dp that would truncate "Add to list" / status
   labels ("Plan to Watch"); natural widths never clip, the row swipes a
   little when it overflows.
3. **"Id 1260107139" titles** — FeedAdapter never prints an id-like placeholder
   as a title anymore: while TitleResolver is pending/failed the card shows
   poster + meta only; the resolved title swaps in when it lands.
4. **Anime-vs-Movie tag mismatch** — TitleResolver now also caches the TvType
   learned from an item's details page (`type:<url>` prefs + memory); the
   resolver's own load() stores it, and FeedWatchFragment.storeType records it
   on every open. Feed cards prefer the learned type, so card tag == watch tag
   after the first open.
5. **Rating + year on the feed watch header** — meta line appends
   `★ rating` (page score via toStringNull, else MetadataResolver cache) and
   the year.
6. **Home row rotation 20s → 60s + on pull-to-refresh** — both postDelayed
   constants changed; the swipe-refresh listener also calls
   rotateVisibleHomeRows() once.
7. **Home sections slimmer** — homepage_parent margins/padding 12dp → 8dp.
8. **Card title alignment + thin font** — home_result_grid_expanded's imageText:
   gravity top|center_horizontal (was center: short titles floated), textStyle
   bold (was unset/thin); minLines 2 kept so rows stay even. feed_card title
   gets minLines 2 + top gravity for even grid rows.
9. **Hero smooth animation (his follow-up ask)** — the 8.10 scroll-collapse
   curve strengthened (scale 1→0.85, fade 1→0.4, top-anchored pivot) and a
   pull-grow added: a custom top EdgeEffect on homeMasterRecycler accumulates
   heroPullGrow (cap +6%) while pulling at the top and springs it back over
   250ms on release. Transforms only — no layout passes.
10. **Ratings for all providers** — MetadataResolver: TMDB lookup retries
    (a) without the year filter when the year-filtered search misses (wrong
    provider years returned zero results), (b) the other kind (movie<->tv)
    when the first misses (mislabeled provider types); OMDb retries yearless.
    The resolver now also feeds the two surfaces that never had it:
    HomeScrollAdapter (Continue Watching / list strip meta) and the hero
    preview score (with the same color chip logic).
11. **Downloads live progress** — root cause: DASH downloads ran with
    totalBytes = 0, so every progress event said "x of 0" and the page froze
    while the notification (driven by bytes alone) moved. downloadDash now
    estimates the total from the MPD (duration x top bandwidth x 1.15) via
    estimateDashTotalBytes(); the temp-file poll raises totalBytes if the
    export outgrows the estimate. DownloadViewModel.updateChildList merges
    liveProgress like the header list already did.
12. **Thumbnail flicker** — DownloadAdapter only calls loadImage when the
    poster URL changed (view tag guard); the 1s list rebuild no longer
    disposes + crossfades every visible poster.
13. **Feed Add-to-list posters on Home** — HomeViewModel.loadStoredData now
    resolves all bookmarks once, defaults the strip's selected type to the
    MOST RECENTLY updated bookmark's type, and on passive rebuilds falls back
    to all bookmarks when the selected type has none — posters render inline
    instead of a bare category bar that needs a tap into the popup. Explicit
    chip taps keep their exact filter.
14. **Cinemeta default OFF (his follow-up order)** — AddonRepository seeds the
    built-in Cinemeta with enabled = false for new installs; stored choices
    on existing installs are untouched.

Files: AddonRepository.kt, HomeFragment.kt, HomeViewModel.kt,
HomeParentItemAdapterPreview.kt, HomeScrollAdapter.kt, HomeChildItemAdapter
(no change), FeedAdapter.kt, FeedWatchFragment.kt, TitleResolver.kt,
MetadataResolver.kt, DownloadManager.kt, DownloadViewModel.kt,
DownloadAdapter.kt, fragment_feed_watch.xml, feed_center_button_bg.xml,
homepage_parent.xml, home_result_grid_expanded.xml, feed_card.xml.
