# Phase 8.41 — downloads that actually download (his 19:37–19:38 report)

## His report (screen recording: Cruella, Disney)
- Tapped Download on a title that PLAYS fine; the Download queue
  ended EMPTY, no notification — video plays, download doesn't.
- Sometimes a download starts, then cuts off.
- Some providers hide resolutions inside the stream's tracks:
  the main player shows them, Sources didn't.
- No download notification while a queued download runs.
- Downloads tab rows should show thumbnail + title + MB done /
  remaining + minutes left.
- Scroll shakes on Home, on the Feed grid, and on the watch
  page's Up next.

## Root causes found in code
1. **DASH exports used the platform default HTTP stack** (8.40's
   known shortcut): provider CDNs that play fine through the
   app's client refuse it (UA/headers) → export dies → the queue
   item is consumed and vanishes, looking like "never started".
2. **Notifications were gated on POST_NOTIFICATIONS on ALL API
   levels** — that permission does not exist below API 33, so
   checkSelfPermission always DENIED on his Android 8.1 and every
   download notification was silently dropped.
3. Sources listed extractor FILES only; renditions inside one
   DASH/HLS stream were invisible outside the player's tracks.
4. Queue rows showed only a title.
5. Scroll shake: (Home) the background title resolver's FULL
   rebind fired mid-scroll despite the debounce — posters reload
   and crossfade under the finger; (Feed/Up next) cards resized
   when a resolved title changed their line count; (Watch) the
   title-box height animation restarted mid-fling.

## Changes
- DownloadManager: the DASH export now runs through
  ExoPlayerAssetLoader with the app's OkHttp client + the
  link's own headers (same stack that plays the title).
  Notification permission checks now apply only on API 33+.
- Feed Sources: adaptive links also list their in-stream
  renditions (from the player's video tracks, real heights);
  picking one applies a track override; switching links resets
  it (FeedPlayer clears video overrides per link). Audio stays
  language-only.
- Download queue rows: poster thumbnail, title, and a live
  details line — "45.2 MB of 182.0 MB • x min left" (speed
  sampled from progress events, ETA via the app's own
  getEstimatedTimeLeft), plus Queued / Download failed states.
- Home: title rebind waits until the list is at rest.
- Feed cards: titles reserve 2 lines — no mid-scroll resizing.
- Watch: title-box collapse uses firmer thresholds and never
  restarts while its animation is running.

## Honest limits
- DASH pause/resume still not possible via Transformer
  (segmented DASH + Mp4Muxer remains the downloader-v2 plan).
- A rendition pick is per-link by design (cleared on switch).
