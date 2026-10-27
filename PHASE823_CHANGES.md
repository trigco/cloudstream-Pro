# Phase 8.23 — background fetch, blend correction, scroll smoothness (PR #7)

Owner feedback on 8.19–8.22 (2026-10-25, five screenshots): DASH and
slow-extracting videos still fail on FIRST play in the feed (his main-
player test: with the player's skip-loading step out of the way the
same video plays — his ask: hide the loading step, fetch links in the
background); the 8.22 blend sits ON the video (it dims the picture's
bottom rows and the subtitles) where he wanted the treatment below /
a title bar that hides on scroll instead; the Feed grid shakes
(কাপাকাপি) while scrolling.

## Background fetch instead of first-link-or-fail (FeedPlayer)

Root cause of the first-play failures: the error handler walked the
link list as it existed at error time. On a cold play that list is
the one-link fast-start snapshot — if that link (often a DASH
manifest from a slow extractor) errored before the remaining
extractors returned, playback went terminally ERROR even though more
links arrived seconds later. Warm plays worked because cached
extraction completes instantly — the same warm/cold asymmetry he
saw all along.

- `extracting` / `pendingRetry` state: while link extraction is
  still running in the background, a player error no longer fails
  the video — the engine stays in LOADING and, the moment extraction
  completes, continues with the next untried link (same-resolution
  first). Terminal ERROR now means extraction finished AND every
  link was tried.
- The loading step itself stays invisible: poster + spinner only,
  links keep arriving behind it, playback starts on the first link
  and upgrades to the full list when extraction lands (unchanged
  8.21 behaviour, now failure-proof).

## Blend correction (watch page)

- The in-video bottom blend overlay is REMOVED (view +
  feed_blend_gradient usage). It sat above the PlayerView, so it
  darkened the picture's lower rows and the rendered subtitles —
  both visible in his screenshots.
- Instead, his alternative ask: the watch page's title bar (title +
  meta, now wrapped in `feed_watch_title_box`) auto-hides while
  scrolling Up next and returns on scroll up / at the top —
  YouTube-style, per his reference screenshot.

## Feed scroll jitter

- The collapsible header no longer animates its HEIGHT (a full
  relayout of the screen on every animation frame, fighting the
  scroll). The header now floats over the grid in the root
  FrameLayout and hides by sliding up (translationY); the grid gets
  a one-time top padding equal to the header height.
- RecyclerView: `setHasFixedSize(true)`, item animator removed.

## Files
- ui/feed/FeedPlayer.kt (extracting/pendingRetry wait-and-continue)
- ui/feed/FeedWatchFragment.kt (title bar auto-hide)
- res/layout/fragment_feed_watch.xml (blend view removed, title box)
- ui/feed/FeedFragment.kt (sliding header, RV stability)
- res/layout/fragment_feed.xml (header overlays the grid)

Note: res/drawable/feed_blend_gradient.xml still exists on the
branch but is now unreferenced (push tooling cannot delete files);
it is inert.
