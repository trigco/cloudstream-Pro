# Phase 8.28 — extraction returns ZERO links: align + probe (PR #7)

The 8.27 diagnostic paid off on the first screenshot: for
"Bethlehem Kudumba Unit" the feed's error box showed

    PLAY Bethlehem Kudumba Unit
    extract ok=true links=0

Extraction "succeeds" but delivers no links at all — the
failure was never in playback. Every earlier round assumed
links existed and died in the player; cold in the feed, the
generator's loadLinks completes (ok=true) having called back
zero times, while the same title extracts fine in the main
player flow (whose saturated RepoLinkGenerator cache the feed
then replays — the whole warm/cold asymmetry).

Episode construction was verified identical to
ResultViewModel2 (dataUrl, apiName, id) — so the remaining
differences in the feed's generateLinks call are probed and
aligned here:

- The feed passed LOADTYPE_INAPP; ResultViewModel2's own
  loadLinks passes LOADTYPE_ALL. The generator delivers a link
  to the callback only when its type is in sourceTypes, but
  caches it regardless — a type outside the INAPP set would
  produce exactly this signature (ok=true, 0 delivered, cache
  full). The feed now passes LOADTYPE_ALL like the viewmodel;
  playableOf still filters to playable types afterwards.
- New probes in the on-screen log: extraction duration, and
  the generator cache state after extraction (entry size,
  saturated flag, and the distinct link types it holds) — this
  distinguishes "delivered but filtered" from "loadLinks
  delivered nothing" in one screenshot.

## Files
- ui/feed/FeedPlayer.kt (LOADTYPE_ALL, duration + cache probes)
