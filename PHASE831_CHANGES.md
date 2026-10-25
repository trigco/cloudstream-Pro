# Phase 8.31 — integrate the main player's loading system (PR #7)

Owner's direction: stop replicating pieces — integrate the main
player's system in the feed. Reading both call paths end to end
found the one systematic difference left:

- Main player (PlayerGeneratorViewModel.loadLinks):
  `safeApiCall { generator.generateLinks(...) }`, and safeApiCall
  runs its work via ioWork → withContext(workerDispatcher).
  Extraction therefore runs on a WORKER thread.
- Feed (until now): generateLinks called directly on
  Dispatchers.Main — and the provider's loadLinks quietly
  returns true with zero links there (dur ~2 s, cache empty),
  while the identical call on a worker thread does the full
  ~18 s extraction. This also explains why Retry never helped
  (same main-thread call) and why only the main player could
  warm a title (its cache replay then served the feed).

Change: the feed now invokes extraction — and the details
load() before it — on Dispatchers.IO, the same call context
the main player's loading system uses. The 8.30 second pass is
kept (worker thread too) as a harmless fallback, and the
diagnostic log stays until the fix is proven on device.

## Files
- ui/feed/FeedPlayer.kt (extraction + load on Dispatchers.IO)
