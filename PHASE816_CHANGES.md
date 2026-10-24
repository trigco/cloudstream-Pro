# Phase 8.16 — Animation suite (Phases A + B + C in one build)

Owner's instruction: ANIMATION_PLAN.md Phases A, B and C together, in one
build. Written 2026-10-24 on top of Phase 8.15 (branch player-modern), so
this PR's build contains 8.15 + 8.16; PR #5 is superseded by this PR.

**Grounding first.** Checking the plan against the code showed several
planned items already exist from earlier phases, and they are NOT redone:
home skeleton already shimmers (`ShimmerFrameLayout`), pull-to-refresh
exists (6A-2, styled + soft pull), home sections already glide in on
scroll with a hero collapse (8.10), fragment transitions already use the
feel_* anims, nav icon pop exists (5A), gesture rails already fade.
What follows is only the genuinely new work.

## Phase A — touch feel & loading

- **Image crossfade** (`ImageModuleCoil.loadImageInternal`): every Coil
  load now crossfades 150 ms — posters/backdrops fade in instead of
  popping, app-wide (home, search, details, downloads).
- **Skeleton → content crossfade** (`HomeFragment`): on a successful load,
  the shimmer skeleton fades out while the feed fades in (250 ms) instead
  of a hard visibility cut.
- **Press-shrink** (new `utils/FeelEffects.kt`): `addPressShrink()` —
  0.97 scale while pressed, spring back on release; scroll-cancel resets
  it, and bind resets scale so a recycled holder can never stay shrunk.
  Attached to every home poster in `HomeChildItemAdapter`.
- **Haptics** (`FeelEffects.hapticTick()`, KEYBOARD_TAP, no patterns):
  bottom-bar and nav-rail tab switches, player play/pause + ±10s, home
  pull-to-refresh and the hero reload-provider button. New setting:
  Settings → General → "Haptic feedback" (default ON,
  `haptics_enabled_key`).

## Phase B — motion (what was missing)

- Fragment transitions, settings list animation and the details backdrop
  fade were verified present (nav graph feel_* anims; the new global
  crossfade covers the details hero fade-in). **True details parallax and
  poster shared-element transitions are deferred, deliberately**: the
  details screen is a swipe layout without an app bar, and a blind
  shared-element hook into the result flow risks breaking poster →
  details navigation on the owner's phone with no way to iterate
  in-session. They stay in ANIMATION_PLAN.md as a device-in-hand item.

## Phase C — hero & pull

- **Hero re-deal guard** (`HomeViewModel.load`): the hero list already
  reshuffles on every load; now the previous opener's URL is remembered,
  and if the fresh shuffle deals the same opening poster again, the list
  rotates once — a refresh visibly opens on a different movie every time.
- **Gesture rail pop** (`PlayerGestureHelper`): volume/brightness rails
  now scale-pop (0.92 → 1.0, 180 ms) as they fade in.
- Pull-to-refresh existed (6A-2); 8.16 adds its haptic tick (Phase A).

## Files

- new: `utils/FeelEffects.kt`
- `utils/ImageModuleCoil.kt`, `ui/home/HomeFragment.kt`,
  `ui/home/HomeViewModel.kt`, `ui/home/HomeChildItemAdapter.kt`,
  `MainActivity.kt`, `ui/player/PlayerView.kt`,
  `ui/player/PlayerGestureHelper.kt`
- `res/values/strings.xml`, `res/values/donottranslate-strings.xml`,
  `res/xml/settings_general.xml`

No layout/renderer changes; the compile check is this PR's Artifact
Build. Merge gate unchanged: the owner's device verdict.
