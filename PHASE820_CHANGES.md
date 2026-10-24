# Phase 8.20 — Feed, second polish round (owner's device feedback)

8.19 was confirmed working; the owner sent annotated screenshots plus
six asks. Same branch (feed-section / PR #7), stacked on PR #6.

## Playback reliability (the "works in main player, not in feed" bug)
Root cause candidates addressed together: the feed player used a
plain ExoPlayer while the app's player decodes through nextlib
extension renderers, and it gave up after a single link. Now:
- Playback lives in a new shared engine, `FeedPlayer`, which builds
  its ExoPlayer with the app's own `FixedNextRenderersFactory`
  (extension renderers ON + decoder fallback) — codec parity with the
  main player.
- On a playback error it automatically walks down the extracted link
  list (keeping position) before showing the error state.
- The error state gains a **Play in main player** button (when a
  generator exists) — the owner's own workaround, built in.
- **Up next** is populated immediately, not only after a successful
  load, so loading/error screens no longer show an empty list.
- Watch loading state rebuilt: the item's poster as backdrop with the
  spinner and status stacked (they overlapped before).

## Series: next episode
A skip-next button appears in the control bar when the current item
has a following episode; playback also auto-advances on episode end.
Episode construction moved into `FeedPlayer` unchanged, so ids and
resume positions still match the main player.

## Mini player (YouTube-style)
`FeedPlayer` owns the player instance, so surfaces can come and go:
- Drag the watch-page video **down** → it minimises into a floating
  mini player over the Feed (poster-sized video, title, play/pause,
  close) and the feed scrolls and works while it plays.
- Tap the mini player or drag it **up** → back to the watch page,
  playback uninterrupted (same player, new surface).
- Drag it down or tap ✕ → playback stops.
- Leaving the watch page any other way (plain back) stops playback;
  the landscape handoff behaves as before.

## Feed header: chips + auto-hide + scroll-to-top
- Genre chips under the search bar (All, Horror, Comedy, Romance,
  Thriller, Action, Drama, Family, Sci-Fi, Animation); a chip drives
  the same search path as typing (pool filter, then provider search).
- The whole header collapses on scroll down and returns on scroll up
  (animated height).
- Re-tapping the Feed item in the bottom bar (or nav rail) scrolls
  the feed to the top and restores the header — wired in MainActivity
  via `setOnItemReselectedListener` on both nav views.

## Skeleton loading
While the first feed build runs, a `ShimmerFrameLayout` shows six
placeholder cards matching the real grid cards (2:3 poster block +
title/meta bars, `@color/grayShimmer`, the home screen's shimmer
setup); real content replaces it the moment data lands.
