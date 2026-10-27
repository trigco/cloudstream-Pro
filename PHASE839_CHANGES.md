# Phase 8.39 — Up next UX (PR #7, branch feed-section)

Device asks (2026-10-25 18:00):
1. Getting back to the top of Up next took endless scrolling.
   -> Floating back-to-top button (feed_watch_to_top) appears
   once the first visible item is >= 8; tapping it jump-cuts
   near the top, then smooth-scrolls to position 0, and the
   existing at-top logic re-shows the title box.
2. Pull-to-refresh on the watch page did nothing.
   -> The Up next list is now wrapped in a SwipeRefreshLayout
   (feed_watch_upnext_refresh). Refreshing rotates the pool:
   entries outside the last shown top-30 window come first
   (shuffled), so the visible suggestions actually change.
