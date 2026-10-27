# Phase 8.42 — downloads fixed at the root + pre-fetched backup playback

## His evidence (2026-10-26 morning)
- Notifications NOW arrive (8.41's API-33 gate fix works) — but
  the download itself still fails: "Drishyam 2 (Hindi)" (Prime)
  from the DETAILS page failed; the queue row showed no poster
  and no details.
- Downloads tab thumbnails missing.
- A feed title (MovieBoxIN) failed to play; its title/meta
  overlapped on the watch screen.
- His ask: when a video is selected, fetch the same video on
  OTHER providers in the background as backup; if the chosen
  server is slow/down, play the backup instead of waiting.
- "এইবার ডাউনলোড একদম ফিক্স হওয়া চাই" — downloads must be
  properly fixed this time.

## Root cause of the main-download failures (found in code)
LOADTYPE_INAPP_DOWNLOAD = {VIDEO, M3U8} — DASH was EXCLUDED
from the link types a details-page download may extract. The
aoneroom-family providers (Prime / Netflix / Disney) serve
DASH-only links, so their downloads extracted ZERO usable
links and failed before anything downloaded — while the same
titles played fine (the player accepts DASH). The downloader
itself has handled DASH since 8.40; the extraction filter was
never updated. Fixed: DASH added to the download type set.

## Also in this phase
- **Cross-provider download fallback:** if every link of the
  chosen provider fails, the downloader now searches the other
  providers for the same title, extracts fresh links for the
  same episode, and downloads those — the file still lands
  under the chosen title's name/folder.
- **Failure reasons:** DASH export errors (Transformer
  errorCode + message), extraction exceptions and the
  empty-links case are recorded per download and shown in the
  queue row ("Download failed • DASH export 10002: …"), so the
  next failure explains itself on screen.
- **Feed prefetch backup (his ask):** selecting a feed video
  now also fetches the same title on another provider IN
  PARALLEL (search → load → extract links). If the primary
  stalls (watchdog), errors, or returns zero links, the
  pre-fetched copy takes over instantly; the on-demand search
  fallback from 8.37 remains behind it. Guarded to the same
  episode it was fetched for.
- **Title/meta overlap:** the watch title box's full height
  was measured once per view; a new item needing a different
  height kept the old smaller one and the meta drew over the
  title. The box is now re-measured per item before the
  collapse animation uses the height.
- **Queue rows:** poster falls back to the series/movie
  header poster; DASH downloads (total unknown until done)
  show "X MB downloaded"; active rows without progress show
  "Downloading…".

## Honest limits
- Torrent/magnet downloads remain unsupported (not HTTP).
- DASH exports still have no pause/resume (Transformer);
  segmented DASH remains the downloader-v2 plan.
