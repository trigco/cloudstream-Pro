# Phase 8.25 — main-player parity for the first play (PR #7)

Owner evidence (2026-10-25, screen recording of "Epic - First
Semester", MovieBox): in the feed the title errors, Retry errors
again; "Play in main player" then spends ~18 s on the loading
screen (Skip Loading counting up) and plays the DASH link fine;
back in the feed afterwards, the same title plays instantly. His
earlier Sardar 2 case hung instead of erroring.

## What the code read showed

- `RepoLinkGenerator` keeps a static, 20-minute link cache keyed
  by (provider, episode id). Both players share it — which is the
  whole warm/cold asymmetry: after either player completes an
  extraction, the other replays it instantly.
- The main player (`GeneratorPlayer.startPlayer`) NEVER starts
  from a partial link list on its own: it starts when loading
  completes and picks the best link from the finished, sorted
  set. Playing early happens only when the user presses Skip
  Loading himself.
- The feed's 8.21 fast start did the opposite — it committed to
  the first link that arrived. On multi-source providers the
  early links are not the playable ones, and every patch since
  (error walk, background wait, watchdog) has been repairing
  symptoms of that one decision.

## The fix (FeedPlayer)

- `extractAndPlay` now waits for `generateLinks` to complete —
  poster + spinner meanwhile, no skip button to press (the
  owner's "fetch in the background, hide the loading step" ask,
  with the main player's reliability) — then plays the best link
  of the completed, sorted set (quality desc, DASH → HLS → MP4
  within a resolution, as in 8.24).
- If extraction reports failure but links were collected, they
  are still tried (the same grace as Skip Loading's partial set).
- The fail-over walk and the 12 s hang watchdog stay in place
  for links that fail or stall during playback.
- A stale item erroring while a new title loads now waits
  (extracting starts in `play()`) instead of flashing the error
  state.

## Also in this round

- Source-picker labels always carry the format now —
  "1080p • Hindi Audio • DASH" vs "1080p • Hindi Audio • MP4" —
  the recording showed identical duplicate rows because the
  DASH/MP4 pair of one language shared a label.

## Files
- ui/feed/FeedPlayer.kt (completed-set start; fast start removed)
- ui/feed/FeedWatchFragment.kt (source label format suffix)
