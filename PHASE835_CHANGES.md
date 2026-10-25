# Phase 8.35 — title-row pills, refresh truthfulness, category hide (PR #7)

Owner verdict on 8.34: most things right; six refinements.

- Title row: the bare icon buttons are now labeled pills
  (Sources / Episodes / Download) spread across the full row
  width, so the row no longer sits mostly empty. The duplicate
  quality button in the player controls cluster is removed —
  source selection lives in the title row's Sources pill.
- Up next space: the "Up next" label moved inside the title
  box, so the existing collapse animation takes it with the
  title — no dead strip is left above the grid while scrolling.
- "Finding sources… N found" counter hidden (spinner only).
- Double-tap still flashed the controls: updatePlaybackUi's
  READY branch called showControls() on EVERY player-state
  notification (seek/buffer churn included). Controls now show
  only when entering READY.
- Feed pull-refresh "not working": the spinner and the
  scroll-to-top were consumed by whichever build finished
  first (often an earlier append build), so a queued refresh
  looked dead. The spinner now tracks the build chain
  (FeedViewModel.isBuilding) and scroll-to-top only fires for
  the prepend build itself (lastBuildWasPrepend).
- Home categories can now be hidden: hold a category row
  without dragging (press, no move, release) opens a menu —
  Hide category, plus Unhide categories… with a checklist to
  restore. Hidden names persist (home_hidden_categories) and
  the list re-filters instantly from the unfiltered copy;
  drag-to-reorder is unchanged.

## Files
- res/layout/fragment_feed_watch.xml
- ui/feed/FeedWatchFragment.kt, ui/feed/FeedFragment.kt,
  ui/feed/FeedViewModel.kt
- ui/home/HomeFragment.kt, res/values/strings.xml
