# Phase 8.32 — cold play FIXED (8.31); polish round (PR #7)

Phase 8.31 is the fix, confirmed on device 2026-10-25: the
main player calls generateLinks inside safeApiCall, which runs
on the worker dispatcher; the feed called it on the main
thread, where this provider's loadLinks silently returns true
with zero links. Extraction on Dispatchers.IO plays cold.
(Retry never helped because it repeated the same main-thread
call; only the main player's own call saturated the cache.)

Owner's follow-ups, addressed here:

- The on-screen diagnostic log is removed (the internal logcat
  lines stay). In its place the loading state shows a live
  "Finding sources… N found" counter.
- Loading boredom: playback now starts as soon as the FIRST
  playable link arrives (tryEarlyStart) instead of waiting out
  the full ~18 s extraction; extraction continues in the
  background and the completed set is folded in for the source
  picker and failover — the main player's Skip Loading,
  automated. Re-opens stay instant via the generator cache.
- Titles that arrive as a bare numeric id: the watch header now
  prefers the loaded page's real name.
- Sparse feed: the pool accumulates — first build pulls TWO
  homepage pages per provider (40 items each), pull-to-refresh
  and scrolling near the end append the next page instead of
  replacing the grid.

## Files
- ui/feed/FeedPlayer.kt (early start, count, title fallback)
- ui/feed/FeedWatchFragment.kt (counter, log UI removed)
- ui/feed/FeedViewModel.kt (accumulating pool)
- ui/feed/FeedFragment.kt (endless scroll)
- res/layout/fragment_feed_watch.xml (counter, log view removed)
- res/values/strings.xml (feed_finding_sources)
