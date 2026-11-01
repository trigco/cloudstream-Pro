# Phase 8.101 — Feed player returns to the wrong video after the main player

Device report (2026-11-01, v1.4.0 line): in the Feed player, rotate /
open a video in the main player, then press Back — the FIRST video
played in that Feed session starts again from 0 (fresh load), even if
the user had since moved on to a different video. The watched position
(e.g. 10 minutes in) is lost.

## Root cause (proven from code)

`FeedWatchFragment` is seeded entirely from its fragment **arguments**,
and its arguments never change:

1. `onBindingCreated` unconditionally ran
   `FeedPlayer.play(arguments.url, …)` + `refreshUpNext(arguments.url)`.
   The arguments are frozen at the item the watch screen was first
   opened with.
2. Switching videos inside the watch screen (Up-next tap) calls
   `FeedPlayer.play(newUrl)` directly — the singleton `FeedPlayer`
   session's `currentUrl` moves on; the arguments stay at item #1.
3. The handoff (`openInMainPlayer`) navigates to the main player.
   Navigation destroys the watch fragment's **view**; the fragment
   instance and its arguments stay on the back stack
   (`onDestroyView` deliberately skips `FeedPlayer.stop()` when
   `handedOff`).
4. On Back the view is recreated → `onBindingCreated` re-runs →
   `FeedPlayer.play(firstUrl)`. play()'s no-op guard
   (`url == currentUrl`) fails, because `currentUrl` is the LATEST
   item — so play() cancels the live load, resets `currentUrl` to the
   first item, stops/clears the player and cold-loads item #1. Its
   start position comes from item #1's own stored ViewPos (briefly
   watched at session start; the 5–95% resume rule zeroes it) —
   hence "first video, from zero, with a load".
5. `onResume` then runs the Phase 8.50 return mechanism,
   `FeedPlayer.replayCurrent()` — but `currentUrl` has ALREADY been
   overwritten with the first item in step 4, so it faithfully
   replays the wrong video. The position the main player saved for
   the current item is never reached.

The same stale seed also sat under the batch-download page return
(Phase 8.38's "same video, same position" path) whenever the user had
switched items before opening it.

## Fix (FeedWatchFragment.kt only)

The watch screen now follows the codebase's own existing rule —
session first, arguments only as the entry seed (the same pattern the
Info button already used: `FeedPlayer.currentUrl ?: arguments…`):

- New instance field `enteredWithArgs`, set on this fragment
  instance's first seed.
- In `onBindingCreated`: if this instance has already entered AND
  `FeedPlayer.hasActiveItem`, the view was recreated over a live
  session — do NOT call `play()` from the arguments; only
  `refreshUpNext(FeedPlayer.currentUrl)`. The player surface rebind
  at the top of `onBindingCreated` already re-attaches the session's
  player, and after a handoff `onResume`'s `replayCurrent()`
  restores the CURRENT item at the position the main player saved,
  as Phase 8.50 designed.
- Fresh entries (new instance — grid tap, search/category tap,
  mini-player expand) take the old path unchanged.

Nothing else changes: FeedPlayer itself, the handoff, gestures, the
locked 16:9 player area, and all other settled behaviour are
untouched. The session (`FeedPlayer.currentUrl` / `episodeIndex` /
the DataStore ViewPos the main player writes) is the persisted
state — it already tracked the latest item and position; the bug
was the watch screen overwriting it from stale arguments on view
recreation.

## Verify on device

1. Feed → play video A, switch to video B (Up next), watch B a few
   minutes.
2. Rotate / open B in the main player, watch a bit more, press Back.
3. Expected: B resumes in the feed player at the position the main
   player left — not A, not from 0.
4. Also: A-only round trip (no switch) still resumes A at the main
   player's position; batch-page return keeps the current video.
