# Phase 8.57 — his 8.56 verdict fixes (built on 8.56)

## Details pill row REMOVED (his order)
- "Add to list ওখানে চাই নি, শুধু feed-এ থাকবে; Share লাগবে
  না; Download লাগবে না — Download আগে থেকেই আছে।"
  fragment_result.xml: the 8.56 pill row is gone;
  ResultFragmentPhone: the three delegating click handlers
  are gone. The left-aligned title bar from 8.56 stays.
- Feed player gains the Add to list pill instead (his
  placement): a full-width pill under the Sources/Episodes/
  Download row in fragment_feed_watch.xml, opening the
  SAME watch-status bottom dialog as the details bookmark
  FAB (DataStoreHelper.setResultWatchState +
  setBookmarkedData on the loaded page). Its label mirrors
  the FAB: "Add to list", or the chosen status once set.

## Wrong title plays (fallback matching was too loose)
- FeedPlayer.titlesMatch: contains-matching ("Hope" ⊂
  "Hopeless", any short title ⊂ a longer one) let the backup
  prefetch (8.42) and the cross-provider fallback (8.37)
  adopt a DIFFERENT movie/series when the own provider
  failed. Now STRICT: exact cleaned-title equality + same
  movie/series kind + year agreement (≤1) when both years
  are known. No confident match → no takeover; the normal
  error/retry state shows instead of the wrong title.

## Movie labelled "S1 E1"
- FeedWatchFragment.updateHeader: the S/E suffix is now
  suppressed for movie-like items (episode tvType or page
  type Movie/AnimeMovie). A film's single episode is the
  film itself. Series labels unchanged.
- FeedAdapter.displayTitle strips a dangling trailing
  separator ("Dear Boss Bengali BongoBD -" → no " -").

## API key fetched nothing
- MetadataResolver now fetches through the app's own HTTP
  client (`app.get`, the stack every provider uses) instead
  of a raw HttpURLConnection.
- TMDB Read Access Tokens (eyJ…) are supported as Bearer
  auth, not only v3 api_key query keys.
- Settings → Account: saving a key now TESTS it live and
  toasts "Key works ✓" / "Key did not work…" — a rejected
  key is visible immediately instead of failing silently.

## Download visibility (queue / notification / Downloads tab)
Diagnosed from the code — three stacked defects, all worst
for DASH downloads, plus one latent freeze:
- A: DownloadQueueService.updateNotification and the 8.47
  updateProgressNotification checked POST_NOTIFICATIONS
  WITHOUT the API-33 gate. The permission does not exist
  before API 33, so on his Android 8.1 every update returned
  early — the shade kept the creation-time "0 downloads
  queued / 0 active downloads" forever. Both checks are
  now gated `SDK_INT >= 33 &&`, like the rest of the app.
- B: downloadDash() kept metadata.type = IsPending for the
  whole export, so notify() re-stamped the queue row
  "Queued" while bytes flowed. It now sets IsDownloading
  at start, mirroring downloadThing/downloadHLS.
- C: the Downloads page dropped in-progress DASH items
  (destination file is 0 bytes until the final copy, and
  the file-info total is 0). DownloadViewModel now tracks
  live progress (downloadProgressEvent), counts active
  children with live figures, and refreshes the list when
  a download starts as well as when it finishes.
- D: DownloadQueueManager.init() still used
  PluginManager.isSafeMode() (safe-mode file OR a latched
  crash record) to refuse starting the queue — the 8.46
  asymmetry. Now file-only, matching the service.

## Provider support popup (CNCVerse)
- No app-side change: the "Keep CNCVerse Alive" dialog is
  rendered by the CNCVerse plugin's own code inside its
  .cs3 file; CloudStream has no provider-popup API or
  switch to turn off. Reported to him with options.
