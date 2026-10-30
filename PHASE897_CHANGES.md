# Phase 8.97 — For You contiguity, load-window ANR fixes, brightness lifecycle + slim side-rail HUD, Info text link

Branch `sky-bridge` (PR #13), on top of 8.96 (head 74a0cea3).
One commit: "8.97: For You contiguity + load ANR fixes, brightness
lifecycle + slim HUD, Info text link". His 8.96 device verdict
(2026-10-30, screenshots 12:01–12:12) drove all five jobs:

1. Loaded Feed: chips → "For You" header → a tall BLANK band →
   🔥 Trending → ⭐ Top 250; the For You posters were NOT under
   their header. The skeleton state showed the same dead band.
   Order: "স্মুথ করো".
2. Tapping anywhere in Feed DURING LOAD → "CloudStream Pro isn't
   responding"; taps fine once the load settles.
3. "ব্রাইটনেস সম্পূর্ণ ডিসপ্লে তেই হচ্ছে" — the feed brightness
   gesture affected the whole display, though 8.92 designed it
   window-attribute-only with restore on destroy.
4. The brightness/volume feedback (big centred dark box: sun +
   "60%" + thick bar) must become the MAIN player's slim
   semi-transparent SIDE bar, detail beside it.
5. Info must not be a pill BUTTON: clean text "Info" + a small
   RIGHT-pointing chevron (› style, like the reference app's
   "Info ›") as a proper vector drawable in muted grey — his
   amendment 2026-10-30 12:25, superseding the downward ⌄ in the
   original brief. No pill background, same place (watch title
   row), opens the FeedInfoSheet.

Local Gradle verification is impossible on this VM (standing
fact); Kotlin files were syntax-scanned with the embeddable
compiler from the dokar harness (no parse errors; the diagnostics
are the expected unresolved-reference cascade of compiling
without the app classpath) and both edited layouts parse as XML.

═══════════════════════════════════════════════════════════════════
## JOB 1 — For You blank + dead gap + smoothness
═══════════════════════════════════════════════════════════════════

### Diagnosis (proven in code)

The "For You" header and its grid were never one unit:

- The header text block ("For You" + red underline) was the
  BOTTOM BLOCK OF THE FLOATING `feed_header` — a FrameLayout
  overlay in fragment_feed.xml hanging over the top of the
  screen, followed by the RecyclerView whose top padding
  reserves the header's height.
- The grid it titles is the LAST adapter of the ConcatAdapter
  (categoryRowsAdapter → chromeSkeletonAdapter → grid adapter):
  the grid's items render only AFTER every category row. In the
  loaded state the floating header therefore sat over the
  Trending/Top 250 rows — not over its own grid — and the
  vertical space under the header text read as a blank band
  belonging to no content, exactly as his 12:01 screenshot
  shows (chips → For You → band → Trending). In earlier builds
  the posters appeared "under the header" only transiently,
  while the rows adapter was still empty and the grid happened
  to top the list.
- The loading overlay told the same story: the full-screen
  skeleton reserved the (taller, header-inclusive) top padding
  and laid its category-row skeletons under the floating
  header's For You block — a band of nothing between the header
  and the first skeleton row (his circled 12:08 photo).

Pixel-measuring the exact band height off the screenshots was
NOT conclusive (cropped/annotated images at non-uniform scale),
so no padding-mismeasure cause is claimed; the structural cause
above fully explains a header visibly detached from its grid in
every state, and the fix makes the detachment impossible rather
than tuning heights. (The header-height half of the reserve is
additionally hardened below — the one-shot measurement could
drift from the rendered header once the chips row rebuilds.)

### Changes

- NEW `ui/feed/FeedForYouHeaderAdapter.kt`: a one-item adapter
  rendering `res/layout/feed_for_you_header.xml` (the same
  16sp bold title + 34×3dp red underline the floating block
  used). `setShowing()` flips itemCount 0↔1.
- FeedFragment: the ConcatAdapter is now
  (categoryRowsAdapter, chromeSkeletonAdapter,
  forYouHeaderAdapter, grid adapter); the span lookup counts the
  header item so it spans full width. Adapter ORDER deliberate
  deviation from the brief's literal "header/chips → For You
  grid → category rows": the grid is the endless full pool
  (loadMore appends page after page), so rows placed after it
  would be unreachable — the 8.92 "categories gone" trauma.
  Rows keep their 8.96 position (top, right under the floating
  chips), then the For You header glued DIRECTLY over its grid.
  The contiguity the job demands (header visible only together
  with its grid, no band in any state) holds in this order too.
- ALL grid submits route through `submitFeedList(list, cb)`
  (FeedFragment.kt:247; 7 call sites: pool observer, hide,
  applySearch ×5), which sets the header item's visibility from
  the SAME facts as the submit: browsing (no live query, no
  active filters) AND the submitted list non-empty. Filtered /
  searched / empty states have no header item at all — the
  header can never float over a void, and during load the
  full-screen skeleton (below) covers the grid area.
- fragment_feed.xml: the For You block is REMOVED from the
  floating header (title bar + chips only now). The loading
  overlay skeleton gains a For You title-bar skeleton (bar +
  red underline, mirroring feed_for_you_header.xml) directly
  above its two poster-grid skeleton rows, so the loading state
  mirrors the final structure: header skeleton → grid skeleton
  → (in-list) category skeletons.
- Header height tracking: `setupHeaderCollapse` adds an
  OnLayoutChangeListener on feedHeader — whenever the header's
  real height changes (chips row rebuilds on a chrome post),
  headerFullHeight updates and the reserve follows. The reserve
  can no longer describe a header that no longer exists.

### Smoothness audit (provable items only)

- Image request sizes: grid/row binds request 200×300 renditions
  (FeedAdapter, unchanged) against ~113dp-wide cells — already
  right-sized; no change.
- Shimmer: verified the skeleton adapters animate ONLY through
  the facebook ShimmerFrameLayout (a container-level animator,
  not per-holder runnables), and both skeleton adapters leave
  the adapter chain / overlay the moment content posts — no
  off-screen shimmer cost to fix. No change.
- Per-frame layout work: PROVEN and fixed under Job 2(b) —
  updateHeaderPadding re-applied setPadding (→ requestLayout)
  on every header-animator frame and every metadata burst;
  now change-only.
- Scroll-state resolver pausing (8.92) and the signature-gated
  adapter submits were already in place; unchanged.

═══════════════════════════════════════════════════════════════════
## JOB 2 — ANR when tapping Feed during load
═══════════════════════════════════════════════════════════════════

Suspects in the brief's order, each verified in code:

### (a) The 8.95 synchronizedMap memo — CLEARED as an ANR mechanism; hardened anyway

`metaOf` (FeedFragment.kt:730) used the memo as three SEPARATE
calls — containsKey, get, put — each internally synchronized by
Collections.synchronizedMap for the duration of that single map
operation only. The slow work (MetadataResolver.metaCached)
happens at `?:` BETWEEN get and put, outside any monitor; no
code anywhere synchronizes on the map object itself, and
`sectionTagsOf`'s lock guards a different structure
(sectionTagsCache). The main thread could therefore never block
on the memo across slow work — the held-across-work pattern the
symptom would need does not exist. (The 8.95 change itself stays
load-bearing: the memo MUST tolerate null values; ConcurrentHashMap
cannot, which was the proven 8.94 crash.)

Hardening applied regardless (cheap, removes the whole class):
the memo is now a `ThreadLocal<MutableMap<…>>` (FeedFragment
.kt:725). computeChrome installs a plain HashMap on its own
background thread for the pass and removes it in `finally`;
metaOf outside a pass sees no memo and uses the resolver caches
exactly as pre-8.94. The main thread never touches pass state.

### (b) The 8.96 per-frame padding fix — PROVEN waste, fixed

`updateHeaderPadding` (FeedFragment.kt:2147) ran
`feedRecycler.setPadding` + `feedSkeleton.setPadding` +
`feedSwipeRefresh.setProgressViewOffset` unconditionally. It is
called from every frame of the header auto-hide animator AND
from applySearch — which the fragment re-runs via
metaApplyRunnable (900 ms debounce) on every metadata/rating
burst while a filter is live, i.e. repeatedly through the load
window. View.setPadding calls requestLayout() even when the
value is unchanged, so the load window carried a stream of full
RecyclerView layout passes for a padding value that had not
moved — main-thread work in exactly the seconds taps ANR'd.

Fix: change-only application. `lastHeaderPad` /
`lastProgressOffset` guards (reset per view in onBindingCreated,
since a fresh view starts from XML padding); the refresh-ring
offset, which depends only on the full header height, is set
when THAT changes, not per frame.

### (c) Adapter submits / diffing — CLEARED, with one PROVEN storm found beside them

- Grid: FeedAdapter is a ListAdapter — AsyncListDiffer computes
  diffs on its background executor; areContentsTheSame compares
  url+apiName only. Cleared.
- Rows: FeedCategoryRowsAdapter.submit uses notifyDataSetChanged
  but is signature-gated in the fragment (a pass whose signature
  matches the posted one never re-submits). Cleared.
- (d) FeedCategorySession.lists: the uncapped per-row lists are
  built inside computeChrome / computeChromePhase1 on
  Dispatchers.Default and only ASSIGNED on Main. Cleared.

PROVEN storm 1 — rating lookups (FeedAdapter.kt, fixed):
`setRatingOverride` located its item with
`currentList.indexOfFirst { MetadataResolver.keyFor(it.name, yearOf(it)) == key }`
— keyFor is cleanPosterTitle + a two-Regex normalize, so EVERY
rating landing cost an O(pool) regex-heavy scan ON THE MAIN
THREAD, and ratings land in bursts throughout the load window
(the 24-item pre-kick + per-bind resolves). Fix: a key→first-index
map rebuilt once per list change in `onCurrentListChanged`
(FeedAdapter.kt:63); each landing is now a map lookup + one
notifyItemChanged (FeedAdapter.kt:77).

PROVEN storm 2 — chip presence checks (FeedFragment, fixed):
`refreshTabStyles` computed each chip's alpha from
`tagItems(tag).isNotEmpty()` ON THE MAIN THREAD. tagItems walks
the whole pool calling TitleResolver.cachedType +
MetadataResolver.metaCached per entry — each a SharedPreferences
read, resolver MISSES additionally attempt a JSONObject parse on
every call (never negatively cached). Worse: every rating LANDING
bumps tagGeneration, which lazily invalidates the tagItems memo —
so a refreshTabStyles inside the load window (a chip tap, a new
tab build) recomputed full-pool tag lists on Main. Fix
(FeedFragment.kt:549): presence now reads `currentTabNames` —
the list the last background pass already posted ("tags with
≥1 pool item" is exactly what computePresentTags built it from;
buildCategoryTabs assigns it BEFORE calling refreshTabStyles,
and a selected-but-absent tag still renders via the
`tag == selected` alpha branch). Zero pool work on Main.

### Net effect

The load window's main thread no longer: waits on any pass
structure (a), eats no-op layout passes (b), scans the pool per
rating (storm 1), or recomputes tag membership per chip refresh
(storm 2). 8.94/8.95 behaviours preserved: categories still
arrive via the same coalescing scheduler + two-phase pass, and
the memo still tolerates nulls (plain HashMap now).

═══════════════════════════════════════════════════════════════════
## JOB 3 — Brightness scope
═══════════════════════════════════════════════════════════════════

### Diagnosis (proven lifecycle gap)

FeedWatchFragment shares MainActivity's single window. The
8.92 design wrote the gesture level to that window's
screenBrightness and restored BRIGHTNESS_OVERRIDE_NONE ONLY in
onDestroyView. `onPause` saved the position and stopped timers
but did NOT restore, and `onResume` did NOT re-apply. Any pause
that leaves the fragment alive — the activity covered or
backgrounded (call, system activity, recents) without the view
being destroyed — therefore left the gesture's level governing
the shared window: the whole app/display ran at the video's
brightness, which is what his 12:12 photo shows. (The minimise
path destroys the view via onDestroyView's save+restore, so it
was already covered — pause was the hole. While ON the watch
screen, a window attribute inherently covers that window's whole
surface — video + Up Next — exactly like the main player and
YouTube portrait; nothing per-view exists on Android. The
fixable defect was the override OUTLIVING the screen, and that
is what is fixed.)

### Changes (FeedWatchFragment.kt)

- `setWindowBrightness(fraction)` extracted (:994) — the same
  window-attribute write + 0.01 floor, nothing else.
- `restoreWindowBrightness()` extracted (:1007) — the old
  onDestroyView block, unchanged semantics.
- onPause (:613) now calls restoreWindowBrightness(); the level
  stays in savedFeedBrightness.
- onResume (:598) re-applies `savedFeedBrightness` after its
  existing work — a pause/resume round trip is invisible.
- onDestroyView keeps the restore as the teardown backstop.
- Settings.System is never touched (settled rule, unchanged).

═══════════════════════════════════════════════════════════════════
## JOB 4 — HUD = the main player's slim side rail
═══════════════════════════════════════════════════════════════════

### What the main player actually uses (verified in code)

`ui/player/PlayerGestureHelper.kt` + `res/layout/player_custom_layout.xml`:

- Holders `playerProgressbarLeftHolder` (VOLUME) and
  `playerProgressbarRightHolder` (BRIGHTNESS) — 100dp-wide,
  vertically centred at the video's sides, transparent (no
  background box at all). NOTE the player's placement is its own
  legacy mapping (volume left / brightness right); the Feed
  keeps ITS settled gesture zones (left 30% brightness, right
  30% volume — his safe-area design), so the Feed's rails mirror
  the ZONES: brightness left, volume right.
- Each holder: an icon above a 5dp-wide × 150dp-tall ProgressBar
  (style Widget.Material.ProgressBar.Horizontal,
  progressDrawable `@drawable/progress_drawable_vertical` —
  layer-list: `@color/progressBackgroundColor` #1AFFFFFF track +
  white shape, scaleGravity bottom), progressTint white src_in,
  max 100_000 with progress clamped to a 2_000 floor for
  smoothness. Brightness icons step sun_1..sun_7 by level
  (PlayerGestureHelper.brightnessIcons); volume uses
  mute/down/up icons.
- Show: pop to alpha 1 (180 ms). Hide: after a 1_500 ms delay,
  fade alpha→0 over 300 ms.
- The >100% "boost" level2 bars are a main-player volume
  extension the Feed does not have (0–100%) — not ported.

### Changes

- fragment_feed_watch.xml: the centred `feed_gesture_indicator`
  box (dark feed_search_bg pill, 26dp icon, 14sp %, 110×4dp RED
  horizontal bar, 1.2 s hard hide) is DELETED. Two transparent
  side holders now sit inside the player box: `feed_gesture_left`
  (start|center_vertical, 12dp inset) and `feed_gesture_right`
  (end|center_vertical), each = 22dp icon + 11sp % text + a
  5dp-wide × 110dp-tall ProgressBar reusing
  `@drawable/progress_drawable_vertical`, white tint src_in,
  max 100_000 — the main player's exact recipe, sized to the
  feed player's ~215dp height.
- FeedWatchFragment.showGestureIndicator (:1086) rewritten:
  routes to the gesture's own side; brightness icon now steps
  sun_1..sun_7 by level (was hard-coded sun_4); volume icons
  unchanged; progress = fraction × 100_000 clamped to the
  player's 2_000 floor; the other rail hides immediately; hide
  = 1_500 ms delay then a 300 ms fade (gestureHideRunnable),
  mirroring the player.

═══════════════════════════════════════════════════════════════════
## JOB 5 — Info: text link, not a button
═══════════════════════════════════════════════════════════════════

- NEW `res/drawable/ic_feed_chevron_right.xml`: a proper vector
  (16dp, 24 viewport, the standard keyboard-arrow-right path),
  fill muted grey #FF9E9E9E baked in — "Info ›" like the
  reference app, per his 12:25 amendment (NOT the downward ⌄ of
  the original brief, NOT a text glyph).
- fragment_feed_watch.xml `feed_watch_more`: pill background
  (@drawable/feed_pill_bg) REMOVED, leading ⓘ
  (ic_outline_info_24 + tint) REMOVED; now plain white 13sp
  "Info" text with `app:drawableEndCompat` = the new chevron,
  drawablePadding 2dp. Touch area: padding 8/8/4/8dp +
  minHeight 40dp; the borderless ripple foreground and the
  title-row constraints (packed after the title, row's right
  end) are unchanged.
- Click wiring untouched: still opens FeedInfoSheet
  (FeedWatchFragment onBindingCreated), still shown by
  updateHeader() with R.string.feed_info.

═══════════════════════════════════════════════════════════════════
## Files in this push (9 paths: 8 app files + this doc)
═══════════════════════════════════════════════════════════════════

Modified:
- app/src/main/java/com/lagradost/cloudstream3/ui/feed/FeedFragment.kt
- app/src/main/java/com/lagradost/cloudstream3/ui/feed/FeedAdapter.kt
- app/src/main/java/com/lagradost/cloudstream3/ui/feed/FeedWatchFragment.kt
- app/src/main/res/layout/fragment_feed.xml
- app/src/main/res/layout/fragment_feed_watch.xml

New:
- app/src/main/java/com/lagradost/cloudstream3/ui/feed/FeedForYouHeaderAdapter.kt
- app/src/main/res/layout/feed_for_you_header.xml
- app/src/main/res/drawable/ic_feed_chevron_right.xml

Plus this file (repo root: PHASE897_CHANGES.md).

## Device checks that decide this build
- Feed cold open: no blank band anywhere; category rows, then
  the For You header directly over its posters.
- Tap around Feed WHILE it loads: no "isn't responding".
- Feed video: swipe brightness up, leave the screen (Home /
  recents / another app) — the rest of the phone/app must be at
  normal brightness; come back — the level is as left.
- Brightness/volume swipes show the slim side rails (icon + % +
  thin vertical bar) on the gesture's own side, fading after
  ~1.5 s.
- Watch title row: "Info ›" as plain text; tap opens the info
  sheet.
