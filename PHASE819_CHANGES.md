# Phase 8.19 — Feed polish (owner's device feedback, 2026-10-24)

8.18's Feed was confirmed working on the owner's phone ("পারফেক্ট কাজ
করছে"). This phase applies his five follow-up asks. Same branch
(feed-section / PR #7), still stacked on PR #6.

## 1. Two-column grid
Cards were one-per-row and huge. `feed_card.xml` is now a grid cell:
poster fills the cell width (2:3), the heart floats on the poster's
bottom-right corner over a dim circle, title (13sp, 2 lines) and meta
underneath. Feed and the watch page's Up next both use
`GridLayoutManager(2)`.

## 2. Refresh = new content
Pull-to-refresh now advances every provider to its NEXT homepage page
(`getMainPage(page)`, page increments per refresh), so a refresh shows a
genuinely different batch instead of the same first page reshuffled.
When a page comes back empty the cycle wraps to page 1. Refreshing also
clears any active search text.

## 3. Deeper pool
The per-provider cap rose from 10 to 30 items (all homepage categories
flattened, as before), so big providers actually fill the feed.

## 4. Feed search
Search bar pinned above the grid. Typing filters the loaded pool by
title instantly; at 3+ characters (600 ms debounce) it also runs
`APIRepository.search` against every provider and merges the hits in,
deduplicated by URL. Stale results are dropped if the query changed.

## 5. Custom player grows up — still fully self-contained
Per the owner's instruction, nothing is reused from the main player;
everything is fetched and handled inside the feed's own player:
- **Double-tap skip**: left half −10 s, right half +10 s, with the
  skip-back/forward overlay flash and a haptic tick. Single tap still
  toggles the controls (GestureDetector: onSingleTapConfirmed /
  onDoubleTap).
- **Quality**: the link/source list moved to a top-bar button and is
  presented quality-first ("1080p • Source"). Switching quality now
  keeps the fetched subtitles (they were dropped before).
- **Subtitles**: top-bar button opens a picker over the media's actual
  text tracks (embedded + the externally fetched ones, which arrive as
  text tracks) with an Off option — same TrackSelectionParameters calls
  CS3IPlayer uses (`setTrackTypeDisabled` + `TrackSelectionOverride`).
- **Audio**: top-bar button lists the media's audio tracks (language /
  label, channel count when > 2) and switches via override; a toast
  explains when there is only one track.
