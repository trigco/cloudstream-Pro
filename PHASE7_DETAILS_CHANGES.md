# Phase 7 — Details Page (title / description / tags / backdrop card)

User feedback addressed: on the video/details page the title, description and
tags looked FLAT (no rounded containers); the title should be CENTERED below
the thumbnail; and the backdrop itself should look like a floating rounded
CARD (like the new home hero card) instead of full-bleed edge-to-edge.

## Scope note (deviation from brief, forced by the actual file structure)

The brief scoped edits to `fragment_result_swipe.xml`. Investigation showed
that file contains only the swipe panels shell and the Phase 6 top-bar
overlay — the backdrop, title, description and tag chips all live in
**`fragment_result.xml`**, which the swipe layout `<include>`s as its center
panel. There is no way to restyle those views from the swipe file, so the
content edits were made in `fragment_result.xml`. This is safe for the other
Phase 7 agents: `fragment_result.xml` is inflated ONLY by the phone path
(`ResultFragmentPhone` → `FragmentResultSwipeBinding` → `FragmentResultBinding`
via the include; a repo-wide grep confirms no other consumer). TV uses the
separate `fragment_result_tv.xml` / `ResultFragmentTv` and is untouched.
`fragment_result_swipe.xml` itself received exactly one edit (top-bar offset).

## Investigation findings

- Layout chain (phone): `fragment_result_swipe.xml` (root FrameLayout:
  panels + `result_top_bar` overlay) → include of `fragment_result.xml` →
  `result_scroll` (NestedScrollView) → vertical column:
  `result_top_holder` (FrameLayout, 200dp) holds `result_smallscreen_holder`
  (trailer include) + `result_poster_background_holder`
  (`result_poster_background` ImageView, bottom shadow, watermark logo badge);
  below it a padded body column with the header row, cast, tags, buttons.
- Kotlin (`ResultFragmentPhone.kt`): NO programmatic title gravity, NO backdrop
  layout-param manipulation, NO parallax/fade on scroll for the backdrop
  (TV has parallax, in its own class/layout). The phone scroll listener only
  shrinks/extends the bookmark FAB and pauses the trailer when scrolled past.
  The backdrop holder only receives an alpha fade animation when a trailer
  loads (`resultPosterBackgroundHolder.startAnimation(AlphaAnimation…)`),
  which is unaffected by margins/clipping on its parent.
- `result_title` sat in a vertical column BESIDE `result_poster_holder`
  inside a horizontal row. The poster holder is never referenced in phone
  code and stays `gone` — the row was effectively text-only.
- `result_meta_*` line (site chip, content-rating chip, type, year, rating,
  status, duration) lives in the custom `FlowLayout`, which packs children
  left-to-right and has NO gravity support — centering is done by making the
  FlowLayout `wrap_content` + `layout_gravity="center_horizontal"`.
- `result_tag_holder` ("Tags" label) is never referenced in code and stays
  `gone`; the visible tags row is the `result_tag` ChipGroup alone.
  `populateChips()` only fills the group — nothing hid it when empty.

## Changes

### New drawables
- `res/drawable/bg_details_backdrop.xml` — shape, 24dp corners, solid
  `?attr/primaryGrayBackground` (shows before the backdrop image loads).
- `res/drawable/bg_details_card.xml` — shape, 20dp corners, solid
  `?attr/primaryGrayBackground` (same pattern as `bg_settings_group`).

### `res/layout/fragment_result.xml`
1. **Backdrop card** — `result_top_holder` gained:
   `layout_marginStart/End = @dimen/result_padding` (15dp, aligns exactly with
   the body column's padding), `layout_marginTop = 16dp`,
   `background = @drawable/bg_details_backdrop`, `clipToOutline = true`
   (real corner clipping of the backdrop image, shadow strip, watermark badge
   AND the in-card trailer, whose player uses `surface_type="texture_view"`,
   so outline clipping applies cleanly), `elevation = 6dp` for the floating
   feel. Height unchanged (200dp); the card is in-flow and scrolls with the
   page exactly as the full-bleed backdrop did.
2. **Header stack** — the horizontal row (poster + text column) became a
   vertical stack (`orientation="vertical"`, top margin 14dp). Poster holder
   node kept in place (gone) so its binding is preserved.
3. **Title centered** — `result_title`: `layout_width` wrap_content →
   match_parent + `android:gravity="center"`, directly under the card.
4. **Meta line centered** — the meta `FlowLayout`: match_parent →
   wrap_content + `layout_gravity="center_horizontal"` (block centered;
   multi-row wraps pack left inside the block, widest row defines its width).
5. **Description card** — the FrameLayout wrapping `result_description`:
   height match_parent → wrap_content, `background = @drawable/bg_details_card`,
   `padding = 16dp`, `layout_marginTop = 12dp`. The description TextView keeps
   its justified text, 5-line collapse/expand and focus attrs.
6. **Tags card** — `result_tag` ChipGroup gained
   `background = @drawable/bg_details_card`, `padding = 16dp`,
   `layout_marginTop = 10dp`. Chips themselves unchanged (already pill-shaped);
   `ChipParent` style sets only chip spacing/text color, no padding conflict.

### `res/layout/fragment_result_swipe.xml`
7. **Top-bar alignment** — `result_top_bar` gained `layout_marginTop = 24dp`
   so the 9 Phase 6 circle buttons (ids, order, `bg_circle_overlay` unchanged)
   sit over the card's top corners (card top edge at 16dp, circles span
   24–64dp; horizontal padding already equals the card's side inset).

### `ResultFragmentPhone.kt` (one strictly-required addition)
8. After `populateChips(resultTag, d.tags)` in the page observer:
   `resultTag.isVisible = d.tags.isNotEmpty()` — without it, the newly padded,
   backgrounded ChipGroup would render as an empty rounded bar on tag-less
   pages. `isVisible` was already imported/used in the file. No other Kotlin
   changes.

## Behavior / tradeoffs

- Scroll behavior is unchanged in kind: no parallax existed on phone, and the
  backdrop was already in-flow inside the NestedScrollView — the card simply
  scrolls with the page, passing under the fixed floating circle buttons
  (same overlay relationship as Phase 6).
- The trailer state machine is untouched: on trailer load the backdrop holder
  still alpha-fades out and the smallscreen player shows — now both inside the
  card frame, so trailers get the same rounded inset look.
- Elevation shadow: the card's parent column does not clip vertically and
  `result_scroll` already sets `clipToPadding="false"`; the 6dp shadow renders
  against the page's black background.

## Risks

- `clipToOutline` + shape background is the standard clipping pattern and the
  trailer uses a TextureView, but on very old GPUs a clipped TextureView can
  occasionally show square corners mid-animation; visual only, no crash risk.
- When the provider supplies a title LOGO (`bindLogo`), the logo badge still
  sits at the card's bottom-start inside the backdrop (unchanged position
  logic) — intended.
- The meta FlowLayout centering is block-level: with many meta items wrapping
  to 2 rows, the second row is left-packed within the centered block rather
  than independently centered (FlowLayout has no per-row alignment support).
- All edited XML validated with `xml.dom.minidom`. No ids added/removed;
  every existing binding is preserved. Nothing pushed.
