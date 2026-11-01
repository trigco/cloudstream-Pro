# Phase 8.102 — Details header rework (full poster, meta row, trailer button)

Device report (2026-11-01, annotated screenshot of the "If Wishes
Could Kill" details page): the header image is zoom-cropped so only
part of the poster art shows; the meta row overflows the right edge
and the year is cut off; the title sits detached from the header;
the trailer starts on its own when a page loads and the header's
spacing breaks when it does. Plus: the "Synopsis" heading should
read "Description", and a later clarification — when a trailer does
play, it must fill the header/poster area completely, with no gap
around it and no layout jump below.

## 1. Full poster view + scroll animation (fragment_result.xml, ResultFragmentPhone)

- `result_poster_background` scaleType `centerCrop` → `fitCenter`:
  the whole poster/backdrop art is always visible, never hard
  cropped. The holder and the page are both black, so the fitted
  image sits on black. Trade-off, stated plainly: a 16:9 backdrop
  in the ~1.4:1 header letterboxes slightly instead of filling
  edge-to-edge — the device verdict can revisit this if the bars
  bother more than the cropping did. (The small poster card keeps
  centerCrop; it is a fixed 2:3 card.)
- Scroll-linked animation: a scroll listener on `result_scroll`
  drifts the backdrop image inside its fixed 280dp holder at
  0.35× the scroll rate (parallax; the holder clips it, the fade
  and everything below stay put), so scrolling eases the full
  poster view through the header instead of the header sliding
  away rigidly. The 8.86 entrance zoom (scale) is untouched — it
  animates a different property. Listener is cleared in
  onDestroyView.

## 2. Meta row overflow (fragment_result.xml)

Root cause: both meta rows lived in the weighted text column
beside the 92dp poster (~222dp wide on a 360dp screen); the chips
row (provider + type + rating + year) is wider than that, so the
trailing items drew off the right edge.

- Both meta rows moved OUT of the poster column into full-width
  rows directly under the header row — same view ids, styles and
  setText(null) hide behaviour, so all Kotlin wiring is unchanged.
- The year moved from row 1 to the START of row 2 (year ·
  duration · rating · status), keeping both rows comfortably
  inside the full page width. Nothing can clip now.

## 3. Title integrated (fragment_result.xml)

The title column beside the poster is now vertically centred
against the poster (`layout_gravity="center_vertical"`), and with
the meta rows forming full-width rows immediately beneath the
header row, poster + title + meta read as one header block
instead of a title floating beside a thumbnail with its details
squeezed off-screen.

## 4. Trailer: no autoplay, Trailer button, exact header fill

How it worked before (proven from code): `setTrailers()` called
`loadTrailer()` the moment the page's trailers arrived, swapping
the header from poster to trailer surface. When the video's
dimensions landed, `ResultTrailerPlayer.fixPlayerSize()` resized
the trailer surface to the video's aspect height inside a
`wrap_content` holder, and once the intro overlay was dismissed
it ANIMATED the top holder's height to match — that resize is
the broken gap / layout jump in the report.

- `setTrailers()` now only STORES the list (still gated on
  `LoadResponse.isTrailersEnabled`) and shows the new Trailer
  button; nothing loads on page arrival. (`loadTrailer` became
  `protected` so the subclass can call it.)
- New `result_trailer_button`: full-width outlined pill under the
  Play/Download row in `result_play_area`, text `@string/trailer`,
  visible only when the page ships trailers.
- The tap (wired in `ResultTrailerPlayer`, the actual nav
  destination) mirrors the existing intro-play tap: load the best
  stored trailer, dismiss the intro overlay, fire Play, show
  controls.
- Exact fill: `result_smallscreen_holder` is now match_parent ×
  match_parent inside the fixed 280dp top holder, and
  `fixPlayerSize()` in header mode sets the trailer surface
  height to the top holder's height — the aspect resize and the
  holder-height animation are DELETED. The header footprint never
  changes, so the content below cannot jump, and the trailer
  occupies the poster's exact area with no gap around it.
  Fullscreen mode keeps its previous MATCH_PARENT behaviour.
  (Now-unused `ValueAnimator` / screen-width/height imports went
  with the deleted code.)

## 5. "Synopsis" → "Description" (fragment_result.xml, strings.xml)

The `synopsis` string is SHARED (TV details dialog title, and the
`result_plot` alias), so it was NOT renamed. A new string
`description_heading` ("Description") was added and the phone
details heading now points at it. The TV dialog still says
"Synopsis" — the request was about the phone details page in the
screenshot.

## Feed trailers — checked, already in place, unchanged

The user also asked for feed trailers to play in the feed player
instead of routing to the main player. They already do (Phase
8.98): the Feed Info sheet plays the trailer in an inline
ExoPlayer inside the sheet (`FeedInfoSheet.playTrailer`), and the
Feed watch page has its own trailer banner wired to the same
helper (pausing the feed video first). No feed trailer path
routes to the main player, so nothing was changed there.

## Local validation (no Gradle available on this VM)

- All four edited pre-existing files were blob-verified identical
  to master 691869fc before editing (the working tree's
  build.gradle.kts carries the abi-splits PR #15 edits and was
  NOT touched).
- fragment_result.xml: XML-parses and passes a standalone aapt2
  compile cleanly.
- strings.xml: a standalone aapt2 compile shows one error that is
  byte-identical in master (a stray quote in an upstream string,
  shifted by exactly the one added line) — pre-existing, not from
  this change; the real CI build accepts the file as-is.
- Kotlin: differential toy-compile (master vs edited, android.jar
  + stdlib) — zero new error kinds beyond explained unresolveds:
  `resultTrailerButton` (new binding field, generated at build)
  in ResultFragmentPhone, and the inherited trailer members in
  ResultTrailerPlayer (base class not on the toy classpath). No
  syntax errors.

## Verify on device

1. Open a details page whose art is a portrait poster (e.g. the
   screenshot's title): the FULL poster shows in the header, no
   cropped zoom; scroll slowly — the backdrop drifts gently
   (parallax) and the header never changes height.
2. Meta rows: provider/type/rating chips on one full-width row;
   year · duration · rating · status on the next — the year is
   fully visible, nothing runs off the right edge.
3. The heading over the plot text reads "Description".
4. On a page with a trailer: NO trailer loads or plays by
   itself; a "Trailer" button sits under Play/Download. Tap it —
   the trailer plays filling the whole header area exactly, and
   the content below does not move. Back/close returns to the
   poster with the same spacing as before.
5. Feed: Info sheet / watch-page trailer banner still plays the
   trailer inline, as before.

## Round 2 (device verdict on 8.102, same day)

Verdict on the "Oh! My Guard (2026)" details page — a PORTRAIT
poster serving as the header art: "পোস্টার দুইপাশে ফাঁকা পড়ে,
ট্রেইলার cut হয়ে যায়" — the poster leaves empty space on both
sides, and the trailer gets cut. Two fixes:

### 6. Blur-fill behind the poster (fragment_result.xml, ResultFragmentPhone, utils/BlurFillTransformation)

The round-1 fitCenter change did show the whole portrait poster,
but floating in the middle of the wide 280dp header with bare
black on both sides — rejected. The header now layers the SAME
art twice:

- Behind: new `result_poster_background_blur` ImageView
  (centerCrop, fills the whole holder) loaded with the new
  `BlurFillTransformation` — the art blurred and darkened, so the
  sides read as a soft extension of the poster instead of empty
  black, and the top-bar icons stay readable over it.
- On top: the sharp backdrop, unchanged — fitCenter, whole art,
  uncropped.

The transformation is deliberately cheap and API-27 safe:
downscale to 48px wide, two passes of a sliding-window box blur,
then darken (RGB × 0.55) — no RenderEffect (API 31+), no
RenderScript, no new dependency (plain Coil 3 Transformation,
same pattern as the app's CircleCropTransformation uses). The
fetch is the same URL the sharp load already makes; Coil caches
the transformed copy under its own key.

Parallax choice: the scroll listener now drifts BOTH layers at
the same 0.35× rate, so the fill and the sharp poster move as
one and never separate; the holder, fade and content below are
untouched. The 8.86 entrance zoom still animates only the sharp
layer (it scales UP from 1.08, so it can never expose an edge of
the layer beneath).

### 7. Trailer fits its full frame in the header (ResultTrailerPlayer)

Root cause (proven from code): the trailer host is the shared
`PlayerView`, whose `initialize()` applies the app-wide resize
mode — Zoom by default since 8.99e — to its media3 view. In the
fullscreen player that is what the user asked for, but inside
the fixed 280dp header (~1.3:1) ZOOM scales a 16:9 trailer to
cover and slices its left/right edges (his screenshot: a
watermark at the frame's right edge is cut in half).

- In header (non-fullscreen) mode the trailer surface is now
  pinned to `RESIZE_MODE_FIT` — set directly on the media3 view
  at the end of `updateFullscreen()`, i.e. after
  `exitFullscreen()`'s own zoom reset, so the pin always wins in
  header mode. The whole frame shows, letterboxed top/bottom on
  the black page; the holder height is untouched, so still no
  resize and no layout jump.
- The pin is view-level only: `PlayerView.resize()` also WRITES
  the shared resize preference, so it is deliberately NOT used
  for the pin — the user's global mode and the main player's
  behaviour are unchanged. Entering fullscreen restores the
  global mode via `host.resize(host.resizeMode, false)`.

### Local validation (round 2)

- fragment_result.xml passes standalone aapt2 compile.
- BlurFillTransformation.kt compiles FOR REAL against Coil
  3.3.0's coil-core classes + android.jar (zero errors) — the
  Transformation API usage is proven, not assumed.
- Differential toy-compile (branch vs edited) for both fragment
  files: no new error kinds beyond explained unresolveds —
  `resultPosterBackgroundBlur` (new binding field, generated at
  build) and `AspectRatioFrameLayout`/`resize`/`resizeMode`
  (real members of media3 and PlayerView respectively; those
  classpaths are absent from the toy compile). No syntax errors.
- CI #296 FAILED on the first push of this round — and the toy
  compile HAD shown the cause: a new unresolved reference
  `transformations` in ResultFragmentPhone, initially filed as
  another classpath artifact. It was real: in Coil 3,
  `transformations()` is an EXTENSION (`coil3.request.
  transformations`, exactly what PluginAdapter imports), not an
  ImageRequest.Builder member (verified against the 3.3.0
  classes with javap). Repair = that one import line, pushed as
  a follow-up commit on this branch; verdict waits on the next
  run.

### Verify on device (round 2)

1. Portrait-poster title (e.g. "Oh! My Guard"): the header's
   sides are now a soft blurred extension of the poster — no
   bare black bands; the sharp poster in the middle is still
   whole and uncropped. Scroll: both layers drift together.
2. Landscape-backdrop title: header looks as before (the fill
   sits behind an already full-bleed image).
3. Trailer in the header: the FULL 16:9 frame is visible —
   nothing cut at the sides; thin black bars above/below the
   frame are expected. Fullscreen from the header still uses
   your normal resize mode.
