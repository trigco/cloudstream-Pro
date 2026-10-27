# Phase 8.29 — probe: which URL/data does the feed extract from? (PR #7)

The 8.28 screenshot for Epic - First Semester:

    extract ok=true links=0 dur=1498ms
    gcache=0 sat=false types=

Extraction finished in ~1.5 s with the generator cache EMPTY —
loadLinks never did the real work (the main player spends ~18 s
extracting this title) and the LOADTYPE theory is dead: nothing
was delivered or cached at all. The generator cache key
(apiName + episode id) provably matches the main player's —
after he played the title there, the feed replayed it — so the
remaining suspect is the input: the episode's data string,
which comes from the LoadResponse the feed's own load() call
produced for the feed item's URL (home-page items may carry a
different URL form than search items, yielding a different
dataUrl whose page has no player sources).

Probe only: the on-screen log now also prints the item URL and
the episode data (host + path) on every play.

## Files
- ui/feed/FeedPlayer.kt (url/data probes)
