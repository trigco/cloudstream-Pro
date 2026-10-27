# Phase 8.34 — playback-state honesty, seek feel, title-row actions (PR #7)

Owner verdict on 8.33: titles/badges/avatar/refresh all good.
Follow-ups addressed here:

- "Video plays but Play in main player still shows": the error
  state could land over live playback — the 12 s watchdog judged
  by player STATE, so on a slow connection a healthy link still
  buffering at 12 s failed over, link after link, until the
  error screen appeared while the last link went on playing
  behind it. Now: the watchdog judges by progress (position
  advanced / buffer ahead; a growing buffer re-arms it),
  failOver refuses to run while the player is playing, and the
  terminal error path stops the player so sound never plays
  behind an error screen.
- Double-tap seek flashed every control: a seek's transient
  not-playing moment called showControls(). Controls now only
  stay up for a real pause (playWhenReady == false).
- Title-row quick actions (Premium Details-style): Source/dub,
  Episodes (picker dialog), Download (queues the current
  episode in the app's download manager, same queue item the
  details page builds) beside the watch title.
- Home scroll shaking: title resolutions arrive in bursts
  while scrolling and each one rebound the whole home list.
  The rebind is debounced to one per quiet gap.
- Posters: the disk cache (512 MB) was already on; the memory
  cache used weak references only, so scroll-backs re-decoded.
  Strong references are now enabled within the same 10%-heap cap.

## Files
- ui/feed/FeedPlayer.kt, ui/feed/FeedWatchFragment.kt
- res/layout/fragment_feed_watch.xml
- ui/home/HomeFragment.kt, utils/ImageModuleCoil.kt
