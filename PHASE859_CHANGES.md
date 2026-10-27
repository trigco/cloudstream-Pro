# Phase 8.59 — Feed fallback repair + mpv-style preload buffer

From his own viewing session (2026-10-27): (1) "OMG 2 (Bangla)" on Rtally
failed in the feed and never switched providers although MovieLinkBD and
others carry the same movie; (2) the player barely buffers ahead (mpv's
read-ahead cache survives network drops; ours stopped at once).

## Fallback repair (ui/feed/FeedPlayer.kt) — two stacked root causes
1. titlesMatch could NEVER match language-tagged titles: cleanPosterTitle
   strips only year parentheses, so "OMG 2 (Bangla)" normalised to
   "omg 2 bangla" vs the candidates' "omg 2" — strict equality (8.57)
   rejected every candidate in BOTH the backup prefetch and the
   cross-provider fallback. Fix: normalizedTitle now drops language
   tokens (bangla/bengali/hindi/tamil/... dubbed/dub/sub) from BOTH
   sides before comparing; kind + year guards unchanged.
2. Playback-time failure had NO cross-provider path: onPlayerError ->
   failOver() walked same-provider links -> pre-fetched backup -> ERROR;
   tryCrossProviderFallback was only wired to load/extraction failures.
   Fix: failOver's last resort now runs tryPlaybackFallback() — one
   cross-provider attempt per play() session (playbackFallbackTried),
   status stays LOADING during the hunt; no hit -> the same terminal
   ERROR as before.
3. Candidate discovery: the search query is now the cleaned title with
   parenthesised tags removed (fallbackQuery), and the first 8 page-1
   results are scanned for a match instead of only the first hit.
   Applied in both tryCrossProviderFallback and startBackupPrefetch.

## Preload buffer (mpv-style read-ahead)
- Main player (CS3IPlayer): LoadControl now binds BOTH budgets —
  setPrioritizeTimeOverSizeThresholds(false) + default target buffer
  192 MB when the RAM pref is unset (was: unset bytes, 50 s time cap
  ruled). Back-buffer 30 s -> 90 s.
- PlayerView wiring: read-ahead default is the new Preload setting
  (was: buffer-length pref or ExoPlayer's 50 s). Disk SimpleCache gets
  a 512 MB floor when the Disk pref is 0 (was: LRU(0) = evict
  instantly, nothing persisted between sessions).
- Settings -> Player -> Cache: new "Preload buffer" ListPreference
  (player_preload_buffer_key): 1 / 3 / 5 / 10 minutes, default 5.
  An explicit Buffer length value still overrides it.
- Feed player: LoadControl read-ahead 50 s -> 120 s with a 96 MB byte
  cap, back-buffer 60 s.

Caveat (unchanged platform truth): cross-session disk reuse still
depends on provider URLs — signed/expiring URLs miss old cache entries;
the win is in-session read-ahead, seek-back and rewatch.
