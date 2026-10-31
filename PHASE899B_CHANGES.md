# Phase 8.99b — brightness revert + collapse geometry + blank titles

Device verdicts on 8.99 (CI #286 green, head de60a1a3): (1) the video
STILL squashes during scroll-collapse despite the 8.99 clip-window
pin; (2) the 8.99 video-only brightness is REJECTED — "ব্রাইটনেস
আগের সিস্টেম টাই আনো এখনকার টা না"; (3) cards from some providers
(Hotstar / Pixar "Anime" cards, grid + watch Up next) render with NO
title — only the "Hotstar • Anime" source line — while e.g. MovieBox
cards show titles; in the 8.95 build all cards showed titles.

---

## Job A — Feed brightness reverted to the 8.97 window-attribute model

Restored verbatim from the 8.97 tree (commit cd41bc65,
FeedWatchFragment_897.kt), adapted only where the file has since
grown (trailer slot, collapse block):

- `currentWindowBrightness()` — window attribute if set, else the
  saved level, else 0.5. The gesture's ACTION_DOWN snapshots it
  again (was `currentFeedBrightness()`, the 8.99 saved-only read).
- `setWindowBrightness(fraction)` — writes
  `WindowManager.LayoutParams.screenBrightness` (0.01 floor) on the
  hosting activity's window. Never Settings.System.
- `restoreWindowBrightness()` — back to
  `BRIGHTNESS_OVERRIDE_NONE`, no-op when no override is in place.
- `applyFeedBrightness(fraction)` = setWindowBrightness + save to
  `savedFeedBrightness` + gesture indicator (unchanged HUD rails).
- Lifecycle, exactly the 8.97 discipline: onResume re-applies
  `savedFeedBrightness`; onPause restores; onDestroyView restores
  again as the teardown backstop.

Removed completely (the whole 8.99 Job 3 machinery):
- `feed_watch_brightness_scrim` view (layout) and every code
  reference to it.
- `applyBrightnessVisual`, `applyBoostEffect`, `setFeedVideoEffects`,
  `onBrightnessEffectsFailed`, `clearBrightnessVisual`,
  `onBrightnessItemChanged`, `currentFeedBrightness`, the
  `brightnessScrimMax` / `brightnessBoostMax` / `brightnessBoostStep`
  constants, the `appliedBoostGain` and `effectsUnsupportedUrls`
  fields, the ACTION_UP boost flush, the `iconListener.onPlayerError`
  override that fed it, and the item-change hook in updatePlaybackUi.
- Imports `androidx.media3.effect.Brightness` and
  `androidx.media3.common.Effect` (only used by that block).
- The `media3-effect` dependency: the version-catalog entry
  (gradle/libs.versions.toml) and its entry in the `media3` bundle —
  it was added in 8.99 solely for this feature; the app consumes the
  bundle via `implementation(libs.bundles.media3)`.

Post-change grep proof: `screenBrightness` / `BRIGHTNESS_OVERRIDE`
appear in ui/feed only inside the three restored functions;
`setVideoEffects`, scrim ids and all removed symbol names have zero
references left in app/src/main.

Untouched: gesture zones (left 30% brightness / right 30% volume /
middle 40% safe), the ±travel × 2.0 scale, the opposite-side HUD
rails (8.98), saved-level semantics.

## Job B — collapse squash, round 2

### Step 1 — the hierarchy and every geometry write (pre-change)

Layout (fragment_feed_watch.xml): root ConstraintLayout →
FrameLayout `feed_watch_player_box` (width 0dp, height 0dp,
`layout_constraintDimensionRatio="16:9"`, start/end/top to parent).
Box children, all sized to the box: poster ImageView (match_parent,
gone while playing), PlayerView `feed_watch_player` (match_parent ×
match_parent, `resize_mode="zoom"`, default surface type =
SurfaceView), the 8.99 brightness scrim, loading box, error box,
seek-FX ×2, controls, gesture rails ×2. Below: title box (its height
animator is a separate, older feature), SwipeRefreshLayout + Up
next RecyclerView.

Code sites writing height / LayoutParams / scale / translation in
the player subtree (pre-change, FeedWatchFragment.kt):
1. First-measure `post{}` block: box ConstraintLayout.LayoutParams
   (dimensionRatio = null, height = full − collapse); box
   clipChildren = true; **the 8.99 pin** — PlayerView
   FrameLayout.LayoutParams height = playerFullHeight, gravity =
   CENTER.
2. `applyPlayerHeight()` (scroll listener + snap animator):
   box LayoutParams.height only, change-guarded.
3. `snapPlayerCollapse()`: writes playerCollapsePx → applyPlayerHeight.
4. Nothing else: updatePlaybackUi / item change / minimise
   (popBackStack) / fullscreen (openInMainPlayer = navigation
   handoff) write no geometry; FeedPlayer.kt contains no
   LayoutParams code at all.

### Step 2 — why the rendered surface still deformed

The pin itself was present and correct as a view-tree statement:
an exact-height child of a FrameLayout is measured EXACTLY at that
height on every pass, gravity-centred, and the box clips. Yet the
device shows the whole frame non-uniformly scaled into the strip —
i.e. the video BUFFER followed the strip height, not the pinned
view bounds. Every view-level suspect was checked and cleared with
the code above (pin applied once per view creation in the same
block that arms the collapse — and the collapse demonstrably runs,
so the block ran; no later code overwrites the PlayerView's params;
the box's per-frame height writes re-measure children with the same
exact spec). The one layer that is NOT governed by the view layout
system is the one PlayerView uses by default: a **SurfaceView**.
Its pixels live in a separate window surface whose buffer is sized
through the SurfaceHolder / compositor path (on API 27 this sizing
and cropping is done outside ordinary view measurement), and it is
that buffer which evidently tracked the visible strip. AspectRatio
FrameLayout + resize_mode=zoom can only work with the surface size
it is handed.

Honest limit: API-27 surface behaviour cannot be reproduced on this
VM (no local Gradle/device), so this is proven by elimination —
every other layer is enumerated above — not by a local repro. The
fix therefore removes the disputed layers instead of tuning them.

### Step 3 — the new structure (deformation impossible by construction)

- **TextureView rendering**: the PlayerView now declares
  `app:surface_type="texture_view"`. A TextureView composites
  in-window like an ordinary view: its content size IS its layout
  size and ancestor clipping applies exactly, on every API level.
  The out-of-tree surface sizing path no longer exists.
- **Dedicated stage**: the PlayerView is a plain match_parent child
  of a new plain FrameLayout `feed_watch_player_stage`
  (layout_gravity="center"). NO code ever writes the PlayerView's
  LayoutParams — the 8.99 pin on the PlayerView itself is gone.
- **One-time exact sizing**: in a `doOnLayout` one-shot (replaces
  the `post{}` block; same first-layout timing, before any scroll
  can occur) the box's ratio is nulled and its height taken over as
  before, and the STAGE's height is set once to the natural 16:9
  height (guarded, only when different). The stage is a dumb
  container with no surface logic of its own, so nothing inside
  it can rescale.
- **The box is the only animated dimension**, and `clipChildren=true`
  is now declared in XML so clipping holds from the first frame,
  not from whenever code runs. The stage, gravity-centred,
  overflows the collapsed box symmetrically; the box shows the
  centre band of the intact 16:9 picture — centre-crop by
  construction.
- Kept intact: playback continuity (the player is never
  re-attached; only layout heights move), position, gesture zones
  (they read the PlayerView's unchanged width and relative
  movement), opposite-side HUD rails, the spring snap
  (OvershootInterpolator 1.1f, 320ms), change-guarded writes,
  minimise drag, fullscreen handoff to the main player.

### Step 4 — height-writing call sites AFTER the change

FeedWatchFragment.kt (grep: layoutParams / .height = / translation /
scale over the whole file):
1. `animateTitleBox` (~248): writes `feedWatchTitleBox`
   LayoutParams.height — the title bar auto-hide, NOT the player.
2. `applyPlayerHeight` (~312): writes `feedWatchPlayerBox`
   LayoutParams.height — the one animated dimension.
3. `doOnLayout` (~461): writes the box's ConstraintLayout.LayoutParams
   (dimensionRatio = null, initial height) — once per view creation.
4. `doOnLayout` (~471): writes `feedWatchPlayerStage`
   LayoutParams.height = playerFullHeight — once per view creation,
   change-guarded.
5. Trailer slot (~487): a LinearLayout.LayoutParams inside the
   title box — NOT the player.
`feedWatchPlayer` (the PlayerView): **zero** LayoutParams / height /
scale / translation writes anywhere in the codebase (FeedPlayer.kt
also contains none). Its measured size is match_parent of the frozen
stage: full width × natural 16:9 height at every collapse fraction.

---

## Job C — blank card titles ("নাম blank থাকে")

### The blank condition (FeedAdapter.bind, lines ~97–115)

A card renders "" exactly when the shown title is id-like:
`shownTitle = titleOverrides[url] ?: TitleResolver.cached(url) ?:
displayTitle(response.name, response.url)`, and the text is set to
"" when `TitleResolver.isIdLike(shownTitle)`. displayTitle already
falls back cleaned-name → URL slug → "Untitled", so a blank needs an
id-like raw name AND an id-like slug AND no resolved title. For
Sky-bridged items the URL is a JSON payload whose last "/" segment
is the tail of the real content URL (typically a numeric id), so
the slug fallback is id-like too — the ONLY cure for such an item is
TitleResolver resolution landing (load() → the real name).

### What was eliminated (verified, NOT the cause)

- The 8.99 FeedPoolCache round-trip is faithful: save() stores
  `r.name`; read() rebuilds through the providers' own
  `new*SearchResponse` builders, which pass `name` verbatim into the
  response constructors (MainAPI.kt 1438–1500; constructors checked).
  No cache version bump needed — previously cached entries carry
  their names correctly.
- TitleResolver.kt and the FeedAdapter binding block are
  byte-identical between 8.95 (all titles shown) and 8.99.
- Sky home items always carry a title string (SkyParse.parseItem
  requires title/name) — but it can be an id-like string, which is
  exactly the population that renders blank.

### The proven path (each link code-cited)

1. **One-shot kicks.** A title kick fires only from bind()
   (FeedAdapter: `if (override == null && isIdLike(response.name))
   onTitleNeeded(entry)`). DiffUtil `areContentsTheSame` is
   url+apiName only, so an item a later publish leaves unchanged is
   NEVER rebound and NEVER re-kicked.
2. **The kicks now fire inside the load storm.** 8.99's cache paint
   binds (and kicks) at cold open, while the streaming build,
   page 2 and the deferred metadata pre-kick saturate the user's
   ~33 KB/s link; TitleResolver.load() ran under
   `withTimeoutOrNull(20_000)` — a Sky load (QuickJS spin-up + page
   fetch) times out, `inFlight` clears, and per (1) nothing retries.
   In 8.95 the pool posted once, after all providers finished, so
   kicks ran on a free link and landed. MovieBox cards are immune
   (real homepage titles — no resolution needed); Hotstar / Pixar
   (Sky plugins with id-like home titles) are exactly the victims,
   and the grid AND Up next show it because both read the same pool.
3. **Up next could not even kick.** FeedWatchFragment's
   upNextAdapter was constructed WITHOUT onTitleNeeded (the grid,
   category and search adapters all wire it), and its
   watchTitleListener refreshed only the current item's header —
   titles landing while the watch screen was up never reached its
   cards at all.

### Fixes

- **FeedAdapter.retryUnresolvedTitles()** (new): re-kicks
  onTitleNeeded for every listed item whose raw name is id-like,
  with no override and nothing cached. Called from
  `onCurrentListChanged` (every list application — streaming
  partials, final publish, cache paint, category/search submits)
  and from `onAttachedToRecyclerView`. resolve() self-guards
  (cached / in-flight), so repeat kicks are no-ops until a previous
  attempt has failed out — then the next publish retries it.
  Context is the RecyclerView's application context; nothing is
  retained on detach.
- **Up-next adapter wired**: FeedWatchFragment now passes
  onTitleNeeded (TitleResolver.resolve, same argument order as the
  other surfaces), and watchTitleListener calls
  `upNextAdapter.setTitleOverride(url, title)` for every landed
  title, in addition to the current-item header refresh.
- **Resolver timeout 20s → 45s** (TitleResolver): bounded relief
  for QuickJS-backed loads on a slow link; the retry loop above is
  the structural cure.

Fallback order is unchanged and uses only existing derivations:
resolved title → response name (cleaned) → URL slug → "Untitled".
No title scraping was invented; no provider code was touched.

---

## Files changed

- app/src/main/java/com/lagradost/cloudstream3/ui/feed/FeedWatchFragment.kt
  (Jobs A + B + C: brightness revert, doOnLayout/stage wiring,
  up-next title kick + listener)
- app/src/main/java/com/lagradost/cloudstream3/ui/feed/FeedAdapter.kt
  (Job C: retryUnresolvedTitles + attach/context plumbing)
- app/src/main/java/com/lagradost/cloudstream3/ui/search/TitleResolver.kt
  (Job C: load timeout 20s → 45s)
- app/src/main/res/layout/fragment_feed_watch.xml
  (Jobs A + B: scrim removed; stage wrapper; texture_view;
  clipChildren in XML)
- gradle/libs.versions.toml (Job A: media3-effect removed)
- PHASE899B_CHANGES.md (this file)

Not merged — PR #13 stays open for his device verdict, as always.
