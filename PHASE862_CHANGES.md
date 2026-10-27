# Phase 8.62 — YouTube wedge seek FX + cast button in the feed

His references (2026-10-27): an MX-Player-style clean player, and the
YouTube double-tap wedge circled in a screenshot — that wedge is the
seek effect he wants (the 8.61 feed badge is replaced). Plus: cast
option in the feed. Plus a regression report (skeleton shimmer /
video loading / feed-search loading "missing") — investigated: the
branch diff vs master (1.2.0) touches NONE of HomeFragment,
FeedFragment, fragment_home.xml, fragment_feed.xml, or the player
loading code; those files are byte-identical to the release. No code
change made for it; asked him to pinpoint where he doesn't see it.

## YouTube wedge seek FX (main player)
- player_seek_fx_left/right are now edge-hugging wedges: a 300x560dp
  translucent disc (#2BFFFFFF) pushed 150dp off-screen so only a
  curved half shows, with two filled triangles (ic_seek_triangles;
  rewind side mirrored) and the amount as "10 seconds"
  (seek_fx_seconds_format) under it.
- PlayerView.showSeekFx: fade in 130ms, hold, fade out at 620ms;
  repeated taps restart the clock (token-guarded).

## Cast in the feed
- fragment_feed_watch: MediaRouteButton feed_watch_cast in the
  top-right control cluster (40dp, feed_center_button_bg), starting
  gone — the exact pattern of the details page.
- FeedWatchFragment wires it like ResultFragmentPhone: guarded by
  isCastApiAvailable(), CastButtonFactory.setUpMediaRouteButton,
  CastContext init task shows it only on success.
- Scope note: the button connects/manages the cast session from the
  feed (sessions are app-global); the feed's inline player does not
  mirror — casting a title still goes through the normal
  details/main-player play flow, as in the rest of the app.
