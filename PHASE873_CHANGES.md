# Phase 8.73 — fixes from his 8.71 device verdict (2026-10-28, 3 screenshots)

Ships together with the staged 8.72 (MaterialSwitch + Monet tints) as
his requested "last build" on top of 8.71 (PR #11, branch feed-polish).

1. **Home shake (categories / whole screen)** — three sources:
   (a) SearchResultBuilder reloaded every poster on every bind
   (loadImage disposes + crossfades), and Home rebinds constantly as
   titles/ratings resolve → posters blinked in waves. Bind now skips
   the reload when the view already shows that poster (URL tag guard,
   same pattern as the 8.71 DownloadAdapter fix).
   (b) The 8.71 hero pull-grow also fired on edge AB SORB mid-fling,
   fighting the scroll — grow is now gated to scroll offset == 0.
   (c) Row rotation stays at his spec (60 s + refresh); with (a) it
   reads as a clean swap, not a shiver.
2. **Provider switcher label stale** — the FAB/head label was only set
   by the apiName observer; the page observer now re-asserts it from
   the actually-loaded provider after every successful load.
3. **Auto-hide chrome (his new ask)** — scrolling down hides the
   provider switcher + the activity bottom nav (accumulated-delta
   thresholds: 40dp down / 25dp up, 200 ms slides); scrolling up
   restores them. Restored unconditionally in onDestroyView.
4. **Title-under gap** — the 8.71 minLines=2 reserved a phantom second
   line under 1-line titles (the dead space in his screenshot). Removed
   on home_result_grid_expanded + feed_card (top gravity + bold stay,
   so titles still hug their posters uniformly). feed_card_meta also
   lost its bottom-to-parent constraint, which let it float inside
   grid-stretched cards.
5. **Up next header** — per his suggestion: "For You" label with a red
   underline bar; top margin 18dp → 10dp (new string feed_for_you).
6. **Hide category not hiding** — two defects: the long-press menu
   resolved its row from the fragment-side list copy, but
   ParentItemAdapter re-sorts rows (empty rows last), so positions
   could point at a DIFFERENT category than the one pressed; the menu
   now resolves from the adapter's current list. And hidden-name
   matching is normalized (trim + lowercase) against scraped-name
   whitespace/casing drift between loads.
7. **Love react not filling** — the project's ic_baseline_favorite_24
   is a hollow outline; bindLike now uses a new solid-heart drawable
   (ic_favorite_filled_24, canonical Material favorite path) with the
   red tint, so a liked heart is filled red.

Files: HomeFragment.kt, SearchResultBuilder.kt, FeedAdapter.kt,
home_result_grid_expanded.xml, feed_card.xml, fragment_feed_watch.xml,
strings.xml, ic_favorite_filled_24.xml (new).
Plus 8.72: styles.xml, main_settings.xml, item_stremio_addon.xml,
item_nuvio_scraper.xml, NuvioPluginsFragment.kt, PHASE872_CHANGES.md.
