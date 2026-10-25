# Phase 8.26 — transport parity: Cronet + provider interceptor (PR #7)

Owner verdict on 8.25: still no cold play in the feed without the
main player's help. With link timing (8.25), link order (8.24),
hangs (8.24) and errors (8.23) all addressed, the remaining
difference had to be in the requests themselves — so this round
is a line-by-line read of CS3IPlayer's playback path against the
feed's.

## What the main player actually does (CS3IPlayer)

`loadOnlinePlayer` builds its data source via `createVideoSource`:

- If the provider supplies a **custom video interceptor**
  (`MainAPI.getVideoInterceptor(link)`), playback goes through
  OkHttp **with that interceptor applied** — the feed never
  applied it at all.
- Otherwise it plays through a **Cronet engine** (Chromium's
  network stack — HTTP/2, QUIC, Brotli, Chrome TLS fingerprint,
  `setHandleSetCookieRequests(true)`) created by
  `CS3IPlayer.tryCreateEngine`, falling back to OkHttp only when
  no engine can be built. The feed always used plain OkHttp —
  the one request-level difference left, and exactly the kind
  some providers' CDNs stall or refuse while serving the main
  player fine.
- Headers are `referer + link.headers` (not getAllHeaders()).
- Media sources are built with `UpdatedDefaultExtractorsFactory`
  (fragmented-MP4 SIDX merging) and the default LoadControl
  durations — the feed used a plain factory and its lighter
  preview load control.

## The fix (FeedPlayer)

- `createVideoDataSource()` replicates `createVideoSource`:
  provider interceptor → OkHttp + interceptor; else the shared
  Cronet engine via `CS3IPlayer.tryCreateEngine` (the engine is
  cached in CS3IPlayer's companion, so both players share one);
  else OkHttp. Same header assembly.
- Media source factory now uses the same
  UpdatedDefaultExtractorsFactory with
  FLAG_MERGE_FRAGMENTED_SIDX.
- LoadControl back to the main player's defaults (with back
  buffer); the preview fast-start control is retired.

## Files
- ui/feed/FeedPlayer.kt (createVideoDataSource, extractors,
  load control)
