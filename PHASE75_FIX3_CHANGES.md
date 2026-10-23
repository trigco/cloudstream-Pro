# Phase 7.5 FIX-3 — Details trailer vs rounded card

Scope: `fragment_result.xml`, `fragment_result_swipe.xml`, new
`bg_details_corner_mask.xml`. No Kotlin touched. Nothing pushed.

## Problem (user, on device, Android 8.1)

Phase 7 made the details backdrop an inset rounded card and it renders
rounded for a static backdrop — but when a trailer is present/playing the
video area goes square-cornered (and reads as full-bleed), and the
floating circle buttons look half-clipped at the image's top edge.

## Structure found (before the fix)

`fragment_result.xml`, inside the scroll content:

- `result_top_holder` (FrameLayout, 200dp tall, margins 15dp sides /
  16dp top, `background=@drawable/bg_details_backdrop`,
  `clipToOutline=true`, elevation 6dp) with two children:
  1. `result_smallscreen_holder` → `<include layout=@layout/fragment_trailer>`:
     a media3 `PlayerView` with `surface_type="texture_view"`.
     `ResultTrailerPlayer.fixPlayerSize()` (Kotlin, read-only here) sizes
     the trailer root to MATCH_PARENT × aspect height and animates the
     holder's height to match; for fullscreen the trailer root is
     re-parented into `result_fullscreen_holder` in the swipe layout.
  2. `result_poster_background_holder` (backdrop ImageView + bottom
     shadow + watermark badge), drawn **above** the trailer.
     `ResultFragmentPhone.loadTrailer()` fades this holder to alpha 0
     when a trailer loads, so in the trailer state the TextureView is
     the visible surface inside the card.

`fragment_result_swipe.xml`: `result_top_bar` (the 9 floating circles)
is a sibling overlay with `layout_marginTop=24dp` and **no elevation**
(its child buttons carry elevation 10dp individually).

## Why the trailer breaks the card (and the backdrop doesn't)

The holder's `clipToOutline` clips normal canvas-drawn children — the
backdrop ImageView proves it on device. A TextureView's video, however,
is composited from a SurfaceTexture hardware layer, and on API-27-era
GPUs that layer ignores the parent's outline clip: the video paints its
full rectangle, square corners included. The same layer compositing can
paint over the top bar's buttons where they overlap the card, which is
why the circles look half-clipped at the card's top edge only in the
trailer state.

Consequence for the fix: a corner mask **cannot** live inside the
holder — as a normal canvas-drawn child it would itself be clipped to
the rounded outline and could never paint the corner squares. The mask
must be a sibling overlaying the holder.

## Changes

### 1. `res/drawable/bg_details_corner_mask.xml` (new)

Layer-list with one item outset by **−10dp on all sides**, containing a
`<shape>` rounded rect, radius **34dp**, transparent solid, and a
**20dp stroke** in `?attr/primaryBlackBackground` (the page background
attr, so every theme — dark, amoled, light — paints its own page color).

The stroke is centered on the item's outline, which sits 10dp outside
the mask view's bounds; its inner edge therefore lands **exactly** on
the card's 24dp corner arc (arc center 24dp/24dp, radius 34 − 10 = 24).
Within the mask's bounds the paint covers precisely the four corner
regions outside the arc — which is all the mask ever paints:

- **Full corner coverage**: the square corner tip of the card rect lies
  24·√2 − 24 ≈ 9.9dp beyond the 24dp arc; the stroke covers everything
  from the arc outward (and 10dp beyond the view bounds, clipped away
  harmlessly), so no square tip can survive.
- **Zero paint inside the card's straight edges**: neither the backdrop
  image nor the video gains an inner frame/bezel; the watermark badge,
  the bottom shadow gradient, and the trailer intro overlay (big play
  button, "Trailer" label) are untouched — the label sits ≥ ~10dp from
  the edges, outside the corner triangles (at 10dp from the left edge
  the painted region is < 4.5dp tall at the bottom).

Degradation safety: if negative layer insets were ever clamped to 0,
the stroke would sit centered on the view bounds instead — a 10dp band
inside the edges with inner radius still 24dp, corners still fully
covered (10 ≥ 9.9). The fix cannot regress into square corners.

### 2. `res/layout/fragment_result.xml`

- New plain wrapper **`result_top_card_frame`** (FrameLayout,
  match_parent × wrap_content) now carries the card's margins
  (start/end `@dimen/result_padding`, top 16dp). `result_top_holder`
  keeps its id, size (200dp), background, `clipToOutline` and elevation;
  it only lost the margins to the wrapper. This is safe for the Kotlin
  that animates `resultTopHolder.layoutParams.height` (it treats the
  params generically) and for `fixPlayerSize()` (the trailer's own
  params are set against `result_smallscreen_holder`, unchanged).
- New **`result_top_corner_mask`** (plain View, match_parent ×
  match_parent, background = the mask drawable) added inside the
  wrapper **after** the holder, so it overlays the whole card in both
  states:
  - backdrop state: paints page color over the corner squares the
    holder's clip already leaves empty — visually a no-op, the card
    look the user approved is unchanged;
  - trailer state: same paint covers the TextureView's un-clipped
    square corners, so the video reads as the same rounded card. The
    intro overlay state (thumbnail + center play) is covered the same
    way, since it lives in the same surface.
  - fullscreen: the trailer root moves to `result_fullscreen_holder`
    and the whole main holder (wrapper included) is hidden — the mask
    never touches fullscreen, which stays full-bleed as intended.
- The mask is `clickable=false`, `focusable=false`,
  `importantForAccessibility="no"`, and paints only corner triangles:
  it cannot block the trailer's center play button or any card touch.

### 3. `res/layout/fragment_result_swipe.xml`

- `result_top_bar` gained **`android:elevation="12dp"`**, lifting the
  bar into its own composited layer above the trailer's texture layer,
  so the circles can no longer be painted over / half-clipped where
  they overlap the card in the trailer state. (The buttons' own 10dp
  elevations only ordered them among themselves; the bar as a whole
  had no z against the surface layer in the other subtree.)
- The 24dp top offset is **unchanged, deliberately**: both states share
  the one card frame, whose top edge is the frame's 16dp top margin —
  so the circles start 8dp inside the card's top edge in the backdrop
  state (verified good on device) and, with the frame now visually
  pinned to the same boundary by the mask, in the trailer state too.
  There is only one top offset because there is only one card frame.

All ids preserved (`result_top_holder`, `result_smallscreen_holder`,
`result_poster_background_holder`, `result_top_bar`, all buttons); two
ids added (`result_top_card_frame`, `result_top_corner_mask`) — view
binding picks them up additively.

## Why this works on the TextureView (old GPUs)

No clipping is requested from the video surface at all. The rounded
look is produced by ordinary opaque painting *above* the surface in
plain canvas-drawn views, which every GPU/driver composites in
documented z-order — the same mechanism that already renders the
backdrop card, the top bar circles and the page background correctly
on this device. The theme attr in the stroke means the painted corners
are pixel-identical to the surrounding page in every theme variant.

## Risks / notes

- **Negative layer-list insets** (−10dp) are standard `LayerDrawable`
  behavior on API 23+ (project minSdk 23; device API 27), and the
  fallback geometry above keeps the corners covered even if clamped.
- The mask adds one always-drawn view over the card (a single stroked
  shape, no bitmaps) — negligible overdraw, corners only.
- If a provider's trailer uses a SurfaceView instead of TextureView in
  some other build flavor, a true surface would punch through *any*
  overlay; in this codebase the layout hard-codes `texture_view`, so
  the mask is above the video by construction.
- CI is the first compiler, as usual: the two new binding fields are
  additive and no Kotlin references them.
