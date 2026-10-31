# Phase 8.99e — Device verdict fixes on 8.99d

Base: `54c835f6` (8.99d + hierarchy-import fix, CI #290 green — the
build device-tested 2026-10-31 morning). Nine verdict items from his
annotated screenshots, fixed in one batch. Nothing merged; PR #13
still waits for his device verdict.

## 1. Details page: Play / Download pills touched

**Root cause (proven in code, `ResultFragmentPhone` resume
observer):** for a movie WITH resume progress, the resume holder
becomes visible and the Play half of the movie row is hidden, so the
Download button expands to full width directly under the resume
pill. Neither the holder nor the row carried any vertical margin —
zero gap, the pills touched (his screenshot).

**Fix (`fragment_result.xml`, `result_play_area` only):**
- `result_resume_progress_holder` gains `layout_marginBottom="10dp"`.
- `result_next_series_button`: `layout_marginVertical="5dp"` →
  `layout_marginBottom="10dp"`, so the series stack uses the same
  scheme.

Every visible pill row in the column is now separated by a
consistent 10dp in all four states (movie normal, movie resume,
series resume, series next-episode). GONE children contribute no
margins in a LinearLayout, so the normal side-by-side Play+Download
row is pixel-identical to before. `fragment_result_tv.xml` was
checked: its holder is a different horizontal structure that
already carries a 10dp bottom margin — untouched.

## 2. Continue Watching: grey panel removed

`home_watch_holder` in `fragment_home_head.xml` lost the Phase 8.8
section-card treatment (`bg_settings_group` background + 16dp side
margins). The section now sits flat on the pure-black page like the
homepage rows below it; the header's side padding becomes 16dp to
align with those rows' headers (the card strip keeps its 10dp
horizontal padding, matching the rows' strips). Top gap (12dp),
bottom padding, visibility behaviour and the adapters are
unchanged. The sibling Bookmarks holder was NOT named in the
verdict and keeps its card — flagged here deliberately.

## 3. Floating nav: blob rebuilt as the item's own background

**Root cause:** the highlight was a separate `View` behind the row
whose LayoutParams were rewritten in `onLayout` to chase the
selected item, filled with a hand-drawn 24×24 vector stretched to
~110×50dp. The stretched vector read as a squished ellipse, its
internal transparent margins offset it from the item's real bounds,
and the LayoutParams chase could lag the width animation.

**Fix (`FloatingNavView.kt`):**
- Blob view and the `onLayout` override deleted.
- The selected item now carries the blob as its **own background**:
  a `GradientDrawable` with the approved preview's organic corner
  radii (58% 42% 55% 45% / 52% 55% 45% 48% expressed as fixed
  per-corner dp radii that never clamp at any tab's expanded
  width), filled with the settled grey `@color/extButtonBackground`
  (#262626). Being drawn by the item itself, it tracks the animated
  width exactly on every frame — no chase, no lag, no stretch.
- The expanded-width formula drops its +2dp fudge, so the
  icon+label group is centred with exactly 13dp on both sides.

Kept verbatim: 420ms `OvershootInterpolator(1.1)` spring, label
fade/slide (alpha over the last 70%, outgoing fast fade), the
BottomNavigationView as state holder, `syncSelection` read-back,
scrub (8dp threshold), reselection callback, NavController
following with the download/settings groups, `setItemIcon`.
`bg_nav_blob.xml` is now unused; push-files cannot delete files,
so it remains in the tree harmlessly (delete via the GitHub UI
whenever).

## 4. Icon quality pass

Audited the full 8.99d mapping table plus co-location of every
suspect pair. Four files changed; everything stays Lucide or
Lucide-style custom (24 grid, 2px stroke, round caps; filled family
filled). Root tint/alpha/autoMirrored and all references preserved
— geometry only.

| File | Was | Now | Reason |
|---|---|---|---|
| `library_icon` | Lucide `library` (stroke) | Lucide `book-marked` (stroke) | His circle: `library` renders as meaningless vertical bars. The Library tab is his saved collection; a book with a bookmark ribbon reads instantly as exactly that, and is distinct from every other nav glyph and from the plain bookmark action icon. |
| `library_icon_filled` | custom solid book-spines | filled `book-marked` (custom, evenOdd ribbon knockout) | Filled companion of the above, same construction as the 8.99d filled family. |
| `baseline_text_snippet_24` | Lucide `file-text` | Lucide `scroll-text` | Exact duplicate of `baseline_description_24` (also file-text). This one labels provider-test response text — a scroll of text is the clearer read; description keeps file-text. |
| `ic_baseline_dns_24` | Lucide `server` | Lucide `network` | Exact look-alike of `ic_settings_providers` (also `server`) inside the same Settings flow. This drawable labels the DNS preference and the feed-watch Sources row; the node-tree glyph reads as network/DNS and ends the collision. |

**Audited and deliberately kept:**
- `ic_baseline_public_24` vs `ic_sheet_browser` (both `globe`):
  both literally mean "open in browser" (Details button / sheet
  action) — same action, same icon is consistency, not confusion.
- `settings_icon_*` vs `ic_baseline_tune_24` (both
  `sliders-horizontal`): Settings tab vs Feed Filters / subtitle /
  plugin "adjust" actions on different surfaces — the platform
  standard mapping; changing one would hurt recognition.
- `sun_1..7` / `brightness_1..7` families: graduated level
  indicators, shown one at a time in the gesture HUD — similarity
  is the design.
- `ic_baseline_collections_bookmark_24` (`book-copy`): no live
  references found; left as-is.

## 5. Provider switcher pill: semi-transparent

`bg_home_provider_pill.xml` (used by both `home_provider_pill` and
`home_provider_pill_alt` in `fragment_home_head.xml`): solid
`@color/extButtonBackground` → **`#A6000000`** (~65% alpha black),
so the hero photo shows through slightly. Label/icon are
white/light, so contrast over photos is preserved.

## 6. Main player lock icon oversized

**Root cause (not the drawable):** `video_locked` is a normal 24dp
Lucide lock, unchanged in geometry by the sweep — but `player_lock`
in `player_custom_layout.xml` was the ONLY button in the bottom
control row carrying `app:iconSize="30dp"` (grep: the file's sole
iconSize). The override is removed; the lock now renders at the
row's standard 24dp like playlist/PiP/subtitle/rotate/resize.
(Checked and left alone: the top `video_locked_img` is the
locked-state indicator, not the circled row button.)

## 7. Resize modes: Zoom default, Fit out of the cycle

His order: "ডিফল্ট ভাবে zoom রাখবে, fit to screen বাদে". The
`PlayerResize` enum and ordinals are untouched (stored-pref
compatibility); Fit simply becomes unreachable from the UI:

- `DataStoreHelper.resizeMode` default: `0` (Fit) → `2`
  (`PlayerResize.Zoom.ordinal`, literal + comment to avoid a
  utils→ui import cycle).
- `PlayerView.initialize()`: a legacy stored Fit is mapped to Zoom
  before applying (the apply also re-stores it, migrating the
  pref).
- `PlayerView.nextResize()`: cycles **Zoom ↔ Fill** only; any
  other stored value lands on Zoom.
- `PlayerGestureHelper.resetZoomToDefault()` (called from
  `exitFullscreen`): resets to **Zoom** instead of Fit, following
  the new default. Pinch-zoom matrix internals are untouched (its
  temporary FIT→ZOOM surface handling at pinch start is not the
  resize cycle).
- Feed player: verified unchanged — `fragment_feed_watch.xml`
  already carries `app:resize_mode="zoom"`.

## 8. Feed watch: collapse REMOVED — the player area is locked

Three shrink mechanisms failed on his device in a row (8.98 layout
height + clip, 8.99 pinned PlayerView, 8.99b TextureView stage,
8.99c uniform scale + follower translation): the video still
squashed on scroll, and the scale version left a black gap below
(the translated/grown Up-next container's dead band — his
screenshot's huge void under the shrunken Paramount frame).

**End state:** the player area is locked at its natural XML 16:9
forever. Deleted from `FeedWatchFragment.kt`:
- all collapse state fields (`playerFullHeight`,
  `playerCompactHeight`, `playerCollapsePx`, `playerAppliedScale`,
  `playerAppliedShift`, `playerRefreshBaseHeight`,
  `playerAppliedRefreshHeight`, `playerSnapAnimator`),
- `playerCollapseListener`, `applyPlayerTransform()`,
  `snapPlayerCollapse()`, `resetPlayerCollapse()` and its three
  call sites (`onDestroyView`, `refreshUpNext`,
  `openInMainPlayer`),
- the `doOnLayout` measure/pivot block and the
  `addOnScrollListener` attach in setup,
- the `PLAYER_MIN_SCALE` constant, and the now-unused
  `ConstraintLayout` / `doOnLayout` imports.

Nothing in the fragment writes the player box's size, scale or
translation anymore (grep-verified). The layout already separates
the regions (player box 16:9 → title box → Up-next refresh region),
so the list scrolls in its own region beneath the locked player.
Kept intact: the TextureView stage, all gestures (30/40/30 zones,
opposite-side rails, double-tap seek, minimise drag), the trailer
slot, title auto-hide, Up-next behaviour.

**Feed grid clearance:** `feed_recycler` `paddingBottom` 80dp →
**76dp** — exactly the floating bar's footprint (14dp lift + 62dp
pill = pill top edge 76dp above the screen bottom on his API 27
device). The 80dp was flat-bar-era padding and left a dead black
band under the last row. The watch page's Up-next list keeps its
16dp bottom padding inside its own region.

## 9. Pause icon on Continue Watching cards + episode thumbnails

**The rule (his words + the state that can back it):** the centre
badge on a Continue Watching card and on an episode thumbnail/row
is a state icon — **PAUSE when that item is the one the player most
recently had loaded, PLAY otherwise.**

**State source:** `DataStoreHelper.setViewPos()` is called ONLY
from playback paths (main player via `setViewPosAndResume`, feed
player, offline/download player), so it now also records
`lastPlayedContentId` (per-account key `last_played_id`,
change-guarded so progress ticks don't rewrite it). Watched
episodes keep their existing check mark — completion outranks the
pause state.

**Surfaces:**
- Home Continue Watching cards (`SearchResultBuilder.bind`,
  `ResumeWatchingResult` branch): the existing centre
  play/progress badge swaps to `ic_baseline_pause_24` for the
  last-played card id, `play_button_transparent` otherwise (set in
  both directions — holders recycle). Same for the expanded card
  variant (shared builder + view ids).
- Details episode list, large cards (`result_episode_large.xml` +
  `EpisodeAdapter`): the 8.86 centre badge gains the id
  `episode_state_icon` and is driven in bind — pause for the
  last-played episode, play otherwise, hidden while the upcoming
  (hourglass) badge shows (including the faulty-poster async
  path).
- Details episode list, compact rows (`EpisodeAdapter`,
  `ResultEpisodeBinding`): the left play-underlay icon follows the
  same rule next to its progress ring; watched/completed keeps
  the check.

## Verification done locally (no Gradle — impossible on this VM)

- `aapt2 compile` (build-tools 36.0.0): all 10 changed layouts /
  drawables pass, including the four new vector pathData sets.
- Kotlin embeddable-compiler parse scan (harness first proven on a
  deliberately broken file): all 7 edited `.kt` files parse clean.
  Full type-check is CI's job, as always.
- Grep audits: no remaining references to the deleted collapse
  machinery anywhere in the codebase; `app:iconSize` no longer
  exists in `player_custom_layout.xml`; `PlayerResize.Fit`
  survives only in the initialize-migration check and the
  enum→ExoPlayer mapping inside `resize()`.

## Files changed (17)

Layouts: `fragment_result.xml`, `fragment_home_head.xml`,
`player_custom_layout.xml`, `fragment_feed.xml`,
`result_episode_large.xml`.
Drawables: `bg_home_provider_pill.xml`, `library_icon.xml`,
`library_icon_filled.xml`, `baseline_text_snippet_24.xml`,
`ic_baseline_dns_24.xml`.
Kotlin: `FloatingNavView.kt`, `PlayerView.kt`,
`PlayerGestureHelper.kt`, `DataStoreHelper.kt`,
`SearchResultBuilder.kt`, `EpisodeAdapter.kt`,
`FeedWatchFragment.kt`.
Plus this file.
