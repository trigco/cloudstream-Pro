# Phase 7.5 — FIX-2: Hero card inset not visible on device + centered title/meta

Scope: `res/layout/home_scroll_view.xml` only. No Kotlin changes (verified:
`HomeScrollAdapter` never references `home_scroll_card`; title/meta centering is
pure layout gravity — the adapter only sets text/visibility). TV layout
(`home_scroll_view_tv.xml`) and `fragment_home_head.xml` untouched.

## Root cause (investigated, not assumed)

The Phase 7 CardView **was** in the shipped hierarchy with its margins laid
out. Evidence:

- The Phase 7–only meta `TextView` renders on device ("2026 • ★ 3.5 • Series"),
  which only exists in the new `home_scroll_view.xml`.
- On the user's screenshots the hero title starts ≈30dp from the screen edge —
  exactly the new layout's 16dp card margin + 16dp inner padding. A
  margin-less page would put it at 16dp.

The inset was defeated **visually**, by four compounding factors:

1. **`app:cardElevation="0dp"`** — the card cast no shadow, so nothing
   separated it from the page behind it.
2. **The margin moat is invisible**: the page root's background is
   `?attr/primaryBlackBackground` (near-black), the same as the home screen
   around the pager — the exposed margins read as part of the poster, not as
   a frame.
3. **The poster fills the card interior edge-to-edge** (`match_parent`
   `centerCrop` ImageView), so the only clipping the CardView performed was
   the outermost ~24dp corner pixels — which on these posters sit in dark,
   vignetted art and under the in-card scrims.
4. **`fragment_home_head.xml` draws a full-width 220dp gradient scrim OVER
   the pager** (outside the card, `layout_gravity="bottom"` of the 500dp
   pager frame). It darkens the strip below the card's bottom edge exactly
   like the poster's own fade, faking image continuation across the card's
   bottom margin and erasing the card's bottom edge entirely.

Net effect: structurally inset, perceptually full-bleed — matching the user's
report.

Checked and **cleared** as causes:

- `HomeScrollTransformer` (the pager's `PageTransformer`) sets
  position-dependent padding on the page root, but the padding is exactly 0
  for the settled/current page — it cannot remove the settled card's margins.
  Left untouched (pre-existing upstream parallax, out of scope).
- No flavor/variant override of `home_scroll_view.xml` exists (only `main`
  and the separate TV file), and no code replaces the page root's or the
  card's layout params.

## Fix (details-card recipe — the one that demonstrably rendered on this device)

`res/layout/home_scroll_view.xml`:

- Replaced `androidx.cardview.widget.CardView` (`@+id/home_scroll_card`) with
  a plain `FrameLayout` keeping the **same id and the same margins**
  (16dp sides / 14dp top / 108dp bottom, so the card still clears the 100dp
  action-row holder in `fragment_home_head.xml`), now with the exact recipe
  of the details page's `result_top_holder`, which renders visibly on this
  device:
  - `android:background="@drawable/bg_details_backdrop"` (24dp rounded
    shape, `?attr/primaryGrayBackground` — the same color the CardView used
    via `cardBackgroundColor`)
  - `android:clipToOutline="true"` (real corner clipping of the poster)
  - `android:elevation="6dp"` (**the missing ingredient**: a real shadow
    outline separating the card from the black page on all four sides)
- Title + meta **centered** in the card (user request, mirroring the details
  title): `home_scroll_preview_title` and `home_scroll_preview_meta` are now
  `match_parent` + `layout_gravity="center_horizontal"` + `gravity="center"`
  (were `wrap_content` + start-aligned). They remain stacked at the card's
  bottom over the in-card scrim, inside the same bottom `LinearLayout`.
- Unchanged: page root stays `match_parent` with
  `?attr/primaryBlackBackground`; auto-slide/paging behavior identical;
  quality badge stays `gone` (LoadResponse has no quality — Phase 7 finding);
  tags/logo stay hidden; all view ids preserved.

## Risks

- `clipToOutline` + `elevation` are API 21+ and are the same combination the
  details backdrop card already ships with on the user's Android 8.1 device —
  low risk.
- Removing CardView changes the generated binding type of `home_scroll_card`
  (CardView → FrameLayout). Nothing in Kotlin references that field
  (grep-verified), and ViewBinding regenerates at build time.
- The head-level full-width bottom gradient in `fragment_home_head.xml`
  still overlays the pager's bottom strip (out of scope, deliberately left).
  The card's bottom edge is now defined by its outline + shadow, but on very
  dark posters the bottom edge will still read softer than the sides/top —
  inherent to the shared scrim design, and the same behaviour the details
  card has.
- During pager swipes, `HomeScrollTransformer`'s padding parallax still
  applies to the page root; the card moves with its page exactly as before.
