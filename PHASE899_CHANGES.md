# Phase 8.99 — Watch-collapse distortion fix + incremental/cached feed loading

Base: sky-bridge @ b81da9c4 (8.98). One commit. No merges.

Device evidence behind the batch (8.98 build): (1) scrolling
the watch page collapses the player and the video picture
visibly SQUASHES (people flattened) instead of cropping;
(2) a cold Feed open takes 30 s+ on his ~33 KB/s link.

## Job 1 — Collapse distortion (FeedWatchFragment.kt)

### Proven mechanism

The 8.98 collapse writes only the outer box's height
(feed_watch_player_box, 100% → 45%). The PlayerView inside it
is `match_parent` (fragment_feed_watch.xml), so the PlayerView
itself is resized to the collapsed strip — full width, 45% of
the 16:9 height, i.e. a ~3.6:1 view aspect for 16:9 video.

The XML sets `app:resize_mode="zoom"`, expecting
AspectRatioFrameLayout to crop. That adjustment lives in the
frame's own onMeasure (it enlarges its measured size in one
dimension so the surface overflows and is clipped). Under the
measure specs this hierarchy actually produces — EXACTLY in
both dimensions, because the PlayerView is match_parent under
a parent whose height is written in px — the surface child is
laid out (and its buffer sized) at the PlayerView's squashed
bounds, and the decoded frame is scaled into that buffer
non-uniformly. The device confirms which path ran: the image
FLATTENS (uniform vertical squash — a scaling artefact), it
does not crop (a zoom artefact). A resize mode whose
correctness depends on re-measure behaviour under EXACTLY
specs cannot be trusted for programmatic height animation.

### Fix

Remove the variable instead of tuning it: the PlayerView's
own bounds now never change during the collapse.

- In the existing first-measure block (where the box's
  dimensionRatio is nulled and its height taken over), the
  PlayerView's layout height is pinned to playerFullHeight
  with `gravity = Gravity.CENTER`, and the box's clipChildren
  is set explicitly (already the ViewGroup default).
- The box is now purely a clipping WINDOW: applyPlayerHeight
  still animates its height (scroll-driven, change-guarded,
  spring snap — all 8.98 behaviour untouched), and the
  visible strip is the central band of a PlayerView that is
  always exactly the natural 16:9 size.

Aspect preservation is by construction: the PlayerView's
internal aspect handling sees byte-for-byte the full-height
case at every collapse fraction; the collapse only clips —
the definition of centre-crop. Distortion is impossible at
any fraction, including the spring overshoot band (the box
may exceed full height by range/6: the centred full-size
player then shows thin black bands briefly, never a scaled
picture).

Untouched, verified in code: playback is never re-attached
(same player, same position); the touch listener sits on the
PlayerView and its zones derive from the view's (unchanged)
width and relative movement only; the middle-zone minimise
drag uses relative dy; fullscreen hands off to the main
player (openInMainPlayer) rather than resizing this view;
the poster ImageView (centerCrop) never distorted and still
fills the box; controls/gesture rails are box children and
keep tracking the visible strip, so the seek bar and rails
stay usable while collapsed.

## Job 2 — Feed 30 s+ cold load

### What dominated, per the code (no invented timings)

1. **Await-all gate on page 1.** buildFeed fanned providers
   out with the library `amap` (ParCollections.kt:36) —
   unbounded `async` for every provider, then `await()` on
   ALL of them. The first pool post therefore waited for the
   slowest provider's homepage; the 8.92 split only removed
   page 2 from that wait, not the other providers.
2. **A 120 s per-provider ceiling.** APIRepository.getMainPage
   wraps the call in withTimeout(getTimeout(...)) whose
   DEFAULT_TIMEOUT is 120_000 ms (APIRepository.kt:31,62,158)
   — a single hung/slow-dripping provider could hold the
   await-all for up to two minutes and still count as
   "working".
3. **Unbounded concurrency on a shared straw.** With every
   provider downloading at once over ~33 KB/s, each homepage
   gets a sliver of bandwidth; nothing completes early, so
   even the average provider lands late — and the gate in (1)
   exposes the user to the worst of them.
4. **No persistence.** FeedPool was a @Volatile in-memory
   object; process death (reboot, force-stop, LMK) reset the
   Feed to a blank skeleton and the full re-download above.
5. **A hidden second round before first paint (likes only).**
   publish() ran withLikedBoost — a searchAll over ALL
   providers (another unbounded amap round) — inside the
   first publish, before the first post.

### (a) Incremental publishing — buildFeedStreaming

The first build's page 1 now streams (FeedViewModel.
buildFeedStreaming): providers run as bounded async jobs
(see b); each completion stores its items in its provider
slot and, under a mutex, publishes the pool-so-far —
interleaved (round-robin over landed providers, provider
order) via the existing publish() merge, so dedupe and
section-provenance unions are the same code path as before.
First paint now waits for the FIRST provider to finish, not
the slowest. Partials are throttled (first non-empty always;
then only on ≥10 new entries) so fast networks can't create
a submit storm; the two-phase chrome pass already tolerates
a growing pool (phase 1 posts, phase 2 merges behind the
signature gate), and rows are never lost — publishes only
ever union into the pool. When every provider has settled,
the complete page-1 list is published once more with
completesFirstBuild=true: only THAT publish runs the
liked-boost weave and flips firstBuild (partial publishes do
neither — per-landing boost rounds would recreate storm 5,
and the canonical interleave/boost order lands in one final
submit, as page 2's merge already did).

### (b) Bounded concurrency + per-provider timeout

- mapProvidersBounded: async jobs under a Semaphore(4).
  Four is the balance point for this device/link: enough to
  keep the pipe full when individual homepages stall, few
  enough that the front of the queue completes — and paints —
  quickly instead of every download trickling, and it keeps
  simultaneous homepage parsing (CPU-heavy, Dispatchers.
  Default) to what a low-end phone absorbs without jank.
  buildFeed (page 2 / refresh / load-more) uses the same
  bounded map; searchAll keeps the library amap (it is a
  user-initiated search, not the load path — unchanged).
- fetchProviderPage wraps the repository call in
  withTimeoutOrNull(30_000): a quarter of the repository's
  own 120 s default, still ~1 MB of homepage at ~33 KB/s —
  far beyond a typical homepage — while a truly hung
  provider can no longer gate a round for minutes. A
  timed-out/throwing provider contributes nothing this round
  and is logged (Log.w "Skipping <api> page <n>"). It is NOT
  retried inside the round: it rejoins naturally on the next
  refresh/load-more round (a fresh 30 s chance), and its
  items are usually already on screen from the disk cache
  (see c) — stated plainly: there is no in-round second
  chance, by design, because a retry queue is exactly the
  tail latency this batch removes.

### (c) Disk cache — stale-while-revalidate (FeedPoolCache.kt + FeedViewModel + FeedFragment)

- FeedPoolCache (new file) persists the pool's minimal
  display data after every completed build (from build()'s
  finally, on Dispatchers.IO, capped at 400 entries /
  ~512 KB): per entry — url, apiName, name, posterUrl, type
  (TvType name), score (double, /10 scale), sections —
  encoded in the FeedLikes style (unit/record/group
  separators) in filesDir/feed_pool_cache_v1.txt.
- On cold open FeedFragment calls
  viewModel.paintCachedPoolIfCold() just before
  loadIfNeeded(): the cache is read on Dispatchers.IO,
  entries are rebuilt through each provider's own
  new*SearchResponse builders (entries whose provider was
  uninstalled are skipped — they could not play), filtered
  (below), put in FeedPool and posted as a normal Success —
  so the grid AND a chrome pass render from cache while the
  fresh build runs behind; loadIfNeeded was taught that a
  cache-only Success is a paint, not a build, and still
  builds. A volatile freshPublishSeen flag (set by every
  real publish) guarantees a late cache read can never
  overwrite fresher pool state.
- Filtering at paint mirrors filtering at build: hidden
  items drop (as in publish); with Family Mode ON at paint
  time, adult providers (isAdultApi), adult items
  (isAdultItem) and entries whose every section is
  adult-labelled (isAdultSection — the cached form of
  buildFeed's whole-shelf drop) are removed. The cache
  itself stores unfiltered display data, so a cache written
  with Family Mode off cannot leak into an on session.
  Corrupt/missing cache = the old cold path, silently.

### (d) Page-2 deferral

Already the shape since 8.92 and preserved exactly: page 2
(buildFeed(2)) starts only after the page-1 round RETURNS —
now meaning every provider landed or hit the 30 s cap — so
page 2 never competes with first paint for bandwidth; it
merges behind via the same publish().

### Deviations / notes

- Streaming publishes show providers in arrival order
  during the load window; the final page-1 publish restores
  the canonical round-robin + boost order in one submit
  (same visible behaviour as page 2's merge in 8.92+).
- Cache-painted entries persist in the pool for the session
  under the pool's existing union semantics (the pool
  already accumulates across pages); freshness arrives as
  fresh copies union over them.
- withTimeoutOrNull abandons the WAIT, not necessarily the
  socket: the repository call is cancelled with its
  coroutine; if its safeApiCall converts the cancellation
  into a Failure result instead, the provider still
  contributes nothing this round — the build is unblocked
  either way at ~30 s worst case instead of 120 s.

## Job 3 — Video-only brightness (FeedWatchFragment.kt + fragment_feed_watch.xml + media3-effect)

His standing order, repeated 2026-10-30 16:30: the Feed
player's brightness must work ONLY on the video layout,
never the whole screen. 8.97 had stopped the leak AFTER
exit, but DURING playback the gesture still wrote the
hosting window's screenBrightness attribute, so the title,
pills and Up next all brightened/dimmed with the video
while the watch screen was up. That path is now gone.

### What was removed

- `setWindowBrightness` / `restoreWindowBrightness` /
  `currentWindowBrightness` are deleted; their call sites
  (gesture snapshot, applyFeedBrightness, onResume re-apply,
  onPause restore, onDestroyView backstop) now run on the
  new model below. Grep proof: no `screenBrightness` /
  `BRIGHTNESS_OVERRIDE` code remains anywhere in ui/feed —
  the one surviving hit is a historical comment. The MAIN
  player's brightness (a different player) is untouched.

### The new model — one scale, two halves

The gesture itself is unchanged: same zones, same snapshot
at touch-down, same ± travel × 2.0 over the view height,
same HUD (the % shown is the scale). The scale is 0..1 and
0.5 is the video's natural level:

- DIM (scale < 0.5): a black scrim view
  (feed_watch_brightness_scrim) layered directly over the
  PlayerView inside the player box — above the video, below
  loading/error/seek-FX/rails/controls, non-clickable so
  touches fall through. It is sized to the box, so it covers
  exactly the visible strip of video at every 8.99 collapse
  height (the box clips; the PlayerView stays full-height).
  Alpha = (0.5 − scale) × 2 × 0.85 — the cap at scale 0 is
  0.85: dark, never fully black.
- BOOST (scale > 0.5): a media3 `Brightness` video effect
  on the feed player via `ExoPlayer.setVideoEffects`.
  Gain = (scale − 0.5) × 2 × 0.35 — the max gain at scale 1
  is +0.35 in the effect's units (its full range is
  −1..+1, 0 = unchanged), deliberately conservative so
  highlights do not blow out.
- Baseline (scale = 0.5): no scrim, no effect.

`androidx.media3:media3-effect` was NOT in the dependency
graph (the media3 bundle had no effect artifact, and
media3's own docs state the module must be added by the app
at the same version): added to gradle/libs.versions.toml
as `media3-effect` (version.ref = media3 → 1.9.3) and to
the media3 bundle the app module already consumes.

### Application discipline

- The saved level keeps its old semantics: savedFeedBrightness
  is the one saved value — gesture snapshots start from it,
  onResume re-applies it after a pause, and an item change
  clears the old item's visuals and re-applies the level for
  the new item. onPause and onDestroyView hand the applied
  visuals back (scrim → 0, effects cleared) so the SHARED
  feed player never carries this screen's boost into the
  mini player or anywhere else.
- Mid-drag boost updates are throttled: every
  setVideoEffects call rebuilds the player's effects
  pipeline, so gains within 0.08 of the applied gain are
  skipped during the drag and the exact final gain is
  flushed on ACTION_UP/ACTION_CANCEL. The scrim (a plain
  alpha) updates on every move event.
- setVideoEffects is reached through one guarded door
  (setFeedVideoEffects): any throw marks the item
  scrim-only and drops the effect.

### Fallback — scrim-only items

media3's documented limits: effects do not work with
DRM-protected content, nor with non-default video
renderers (FeedPlayer's factory runs the nextlib extension
renderers in MODE_ON — core renderer first, extensions as
fallback — so exotic formats can land on a renderer that
cannot take effects). Three guards, all per item (keyed by
the item url, remembered for the session):

1. A media item carrying a DRM configuration never gets a
   boost armed (checked before applying).
2. iconListener.onPlayerError: an error arriving while a
   boost is live marks the item unsupported and clears the
   effect — FeedPlayer's own link fail-over then proceeds
   effect-free from the first failure.
3. A throwing setVideoEffects call does the same.

For such an item the boost half is capped at baseline; the
dim half (scrim) is unaffected, and the HUD still shows the
requested scale. Nothing in this path can produce a black
or broken video: the worst case is a video at its natural
brightness.

## Device checks that decide this build

- Scroll the watch page up/down through the collapse at
  several stop points (full, mid, 45%): faces/bodies keep
  natural proportions everywhere — the picture must crop,
  never flatten; playback position and sound uninterrupted.
- Force-stop the app, reopen with a warm cache on the slow
  link: the Feed paints posters + category rows from the
  last session within the first seconds, then fresh items
  swap/merge in.
- Cold install (or cleared app data) on the slow link:
  first posters appear after the first provider lands
  (seconds, not the full ~30 s), rows grow in as more
  providers land, and one dead provider no longer stalls
  the whole feed.
- Family Mode: turn it ON, force-stop, reopen — cached
  adult items must not appear in the painted feed.
