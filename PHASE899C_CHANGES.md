# Phase 8.99c — ratio-lock collapse via uniform scale + system-level brightness

Branch `sky-bridge` (PR #13), on top of 8.99b (`cde1dfb8`). Two jobs, both from his
device verdicts on the 8.99b build (CI #287 green, user-tested): the feed player
STILL squashed on scroll-collapse, and the restored window-attribute brightness
had NO effect on his device at all. His orders: "ratio area lock করো" (lock the
ratio area) and full SCREEN-LEVEL brightness.

## Job A — collapse is a uniform scale transform; the player is never resized again

### What failed before (device-proven, three rounds)
- 8.98: collapse wrote the player box's layout height (100% → 45%).
- 8.99: PlayerView pinned at full height inside the box as a pure clip window.
- 8.99b: rendering moved to a TextureView inside a dedicated exact-height stage.
The picture deformed on his Android 8.1 device under all three. This round takes
layout out of the equation entirely instead of tuning a fourth variation.

### The new mechanism (FeedWatchFragment)
- After creation, NOTHING in the player subtree (`feed_watch_player_box`,
  `feed_watch_player_stage`, `feed_watch_player`) ever receives a layout size
  write again. The box keeps its natural XML size (0dp + `layout_constraint
  DimensionRatio 16:9`) permanently. The 8.98 `doOnLayout` height takeover, the
  ratio-nulling, the 8.99 PlayerView pin and the 8.99b one-time stage sizing are
  all deleted; `doOnLayout` now only MEASURES (captures the box's natural height
  once as the collapse anchor), pins the scale pivot, and re-applies the current
  transform after view recreation.
- Collapse writes `box.scaleX = box.scaleY = f`, f from 1.0 → **0.45**, with
  `pivotX = box.width/2`, `pivotY = 0` (shrinks toward the top-centre). A uniform
  scale multiplies both axes by the same factor, so deformation is impossible by
  construction — this is the ratio lock he ordered.
- Space reclaim: `feed_watch_title_box` and `feed_watch_upnext_refresh` get
  `translationY = -(1 - f) * playerFullHeight`. Because the compact end is 45%,
  the collapse range is 55% of the full height, so `(1 - f) * fullHeight` equals
  exactly the clamped collapse amount in px — the followers slide up by the same
  numbers the old height system used, sit flush under the shrunken player, and
  move identically (no gap/overlap between them).
- The driver is unchanged: the existing Up-next scroll listener accumulates
  `playerCollapsePx` 0..range with the same list-top rule; the snap animator
  (320 ms, OvershootInterpolator 1.1) feeds the same values; overshoot is clamped
  to the same range/6 px band past either end, now expressed in scale
  (≈ ±0.09 around 0.45/1.0). Writes are change-guarded (applied scale + shift
  memoised) and scroll-driven only — no frame loop, no layout pass on the player.
- Follower-only addition (flagged): the Up-next container is bottom-pinned with
  a 0dp height, so pure translation would leave a dead black band at the screen
  bottom and shorten the list — the blank-band pattern he flagged in 8.96. Its
  layout height therefore grows by the same clamped px (top anchored,
  `verticalBias = 0`), keeping its bottom edge on the screen bottom. This is a
  write on a follower, never on the player subtree (one line group in
  `applyPlayerTransform`; remove it if pure translation is preferred and the
  band is acceptable).
- Identity resets: `resetPlayerCollapse()` (animator cancel + collapse 0 +
  re-apply, which writes scale 1 / translations 0 / base refresh height) is
  called on item change (via `refreshUpNext`, as before), on the fullscreen
  handoff (`openInMainPlayer`), and now also in `onDestroyView`.
- Stage decision: the 8.99b stage is KEPT as a plain `match_parent` wrapper but
  its one-time exact-height set is dropped — justified because it is a
  match_parent child of a permanently full-size box, so its measured size is
  always the natural 16:9; the set had nothing left to guard. `surface_type`
  stays `texture_view` (in-window compositing, so the ancestor scale transform
  applies to the video pixels exactly as to the surrounding layout).

### Writer inventory — player area
BEFORE (8.99b): box `layoutParams.height` in `applyPlayerHeight`; box params
(dimensionRatio=null + height) and stage params (exact height) in `doOnLayout`;
title-box height in the auto-hide animator; trailer slot LayoutParams at
creation.
AFTER (8.99c), every size/transform writer in the watch page:
- `applyPlayerTransform`: box `scaleX`/`scaleY`; title box `translationY`;
  Up-next refresh `translationY`; Up-next refresh `layoutParams.height`
  (+ `verticalBias`, follower-only band compensation above).
- `doOnLayout` (once per view creation): box `pivotX`/`pivotY` (transform
  properties, not layout); guard resets.
- Unchanged and unrelated to the player: the title-box auto-hide animator
  (title box height/alpha), the trailer slot LayoutParams at creation.
No code path writes the box's, stage's or PlayerView's layout size anywhere.

### Touch/gesture reasoning (verified in code)
The touch listener and GestureDetector sit on the PlayerView itself. Android
dispatches touches down the hierarchy applying the inverse of the accumulated
transform, so `event.x/y` arrive in the PlayerView's LOCAL (layout) coordinate
space, and `view.width/height` report layout size, which a scale transform never
changes. Therefore the zone fractions (down-x <30% brightness / ≥70% volume /
middle safe), the 2%-of-height claim rule, the ×2.0 travel multiplier over
`view.height`, the double-tap half split and the 160px minimise threshold all
behave identically at any f. One honest nuance: thresholds are in local px, so
on-screen finger travel maps through 1/f — at f=0.45 the minimise drag needs
~72 physical px instead of 160. Gestures stay functional at every f; they just
feel proportionally lighter when collapsed. Minimise (middle zone only) and
double-tap seek were exercised by construction, not re-architected.

### Expected device look
The collapsed player is now visibly a proportional MINI player — narrower as
well as shorter, centred under the status bar — not a full-width strip. Faces
can only ever be smaller, never flatter.

## Job B — brightness mirrors the main player's system-level mechanism

### Facts (read first-hand)
- The feed's 8.99b chain (gesture → window `screenBrightness`) was complete in
  code and had NO effect on his device.
- `PlayerGestureHelper.kt` (the main player — whose brightness WORKS on his
  phone) reads/writes `Settings.System.SCREEN_BRIGHTNESS`, setting
  `SCREEN_BRIGHTNESS_MODE_MANUAL` first (~lines 434–490), `useTrueSystem
  Brightness` defaulting true (~line 119), with an exception-driven fail-over to
  the window attribute (floor 0.004) when a system call throws.
- Permission: grep-verified — there is NO `canWrite` /
  `ACTION_MANAGE_WRITE_SETTINGS` flow anywhere in the app. The helper's
  try/catch flip IS the permission handling; the feed mirrors it, so a missing
  permission lands the gesture on the exact fallback the main player uses,
  never on a silent no-op.
- Exit contract: the helper's `release()` never restores the system level; on
  entry the next touch-down simply re-reads the live level. The main player
  LEAVES the level changed. The feed mirrors that (see below).

### The mirror (replicated in FeedWatchFragment, helper not reused)
The helper is bound to the main player's PlayerView, its lock/extra-brightness
machinery and its HUD rails, so its two small functions are replicated
faithfully instead of imported:
- `currentFeedBrightness()` ≡ `getBrightness()`: system read / 255 while
  `useFeedSystemBrightness`; on throw, flip the flag and re-read on the fallback
  (window attr if set → saved level → 0.5, the feed's pre-existing tail).
- `setFeedBrightness(f)` ≡ `setBrightness()`: `SCREEN_BRIGHTNESS_MODE_MANUAL`
  then `SCREEN_BRIGHTNESS`, same try; on throw, flip the flag and write the same
  value through `setWindowBrightness` (floor now the helper's 0.004, was 0.01).
- Entry snapshot: the system level is read once in `onBindingCreated` into
  `entrySystemBrightness` — a record, deliberately NOT a restore source.
- Exit: NO system restore on pause/destroy (mirror of the helper's contract —
  screen-level means the set level stays). The WINDOW fallback keeps the 8.97
  discipline — `restoreWindowBrightness()` in onPause/onDestroyView (a no-op in
  system mode, since no override was ever set) and the saved-level re-apply in
  onResume now gated on fallback mode only.
- HUD rails and saved-level semantics unchanged; the gesture plumbing
  (touch-down snapshot, ×2.0 multiplier) now snapshots from
  `currentFeedBrightness()`. Removed as dead: `currentWindowBrightness()` and
  the stale "never Settings.System / deliberately NOT mirrored" docs. The main
  player is untouched.

### One deliberate, flagged deviation
The helper's system write is literally `min(1, (fraction * 255).toInt())` —
capped at 1 of 255, which contradicts its own read (`value / 255`). The feed
writes the full 0..255 value the read side expects:
`(fraction.coerceIn(0f, 1f) * 255).toInt()`. Path, manual-mode handling,
fallback order and floors are otherwise the helper's own. If literal mirroring
is ever preferred, it is a one-expression change in `setFeedBrightness`.

## Device checks for this build
1. Scroll Up next up: the player shrinks proportionally toward the top-centre
   (narrower AND shorter, spring bounce at the ends as before); at no stop
   point is any face flatter or wider than the picture itself. Scroll back to
   the top: full player, no leftover shift; the list reaches the screen bottom
   at every collapse amount (no dead black band).
2. Minimise drag (middle zone), double-tap seek and the side gestures still
   work while collapsed.
3. Brightness swipe on the watch page now changes the WHOLE screen like the
   main player (system level — it stays changed after leaving the page, same
   as the main player; check against the system brightness slider).
4. Volume side unchanged; HUD rails unchanged.
