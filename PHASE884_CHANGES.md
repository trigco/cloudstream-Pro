# Phase 8.84 — Home polish from the SkyStream reference screenshots

His verdicts (2026-10-29) comparing our Home with SkyStream's: flat
category headers like theirs, tighter poster corners, the red
underline on headers, pure black background, the provider switcher
smaller and at the top beside the profile, the provider popup
smaller, and the hero slide animation replaced.

## 1. Section headers — flat, red underline, "View All" pill

- `homepage_parent.xml` (phone): the row container lost its card
  (`bg_settings_group` surface + 16dp side margins gone) — rows now
  sit flat on the page background. Header = title (unchanged
  `WatchHeaderText`, id `home_child_more_info` kept) with the Feed
  "For You" red underline treatment copied exactly (34dp × 3dp bar,
  4dp under the title, `@color/colorPrimaryRed` — there was no
  drawable for it; it is a plain coloured View in both places), and
  a new **"View All" pill** at the right (new id
  `home_child_view_all`, new drawable `bg_home_view_all.xml` =
  `@color/extButtonBackground` chip, 20dp corners; text from new
  string `home_view_all`; chevron
  `ic_baseline_keyboard_arrow_right_24`). The bare arrow on the
  title is gone.
- `HomeParentItemAdapter.kt` (`ParentItemAdapter.onBindContent`):
  on PHONE the pill gets the same click as the title —
  `moreInfoClickCallback` (the full-listing popup). Title click
  unchanged.
- `homepage_parent_tv.xml` / `homepage_parent_emulator.xml`: same
  header structure (title + underline + pill, navbar inset kept on
  the header row). Required, not cosmetic: all three layouts bind
  through `HomepageParentBinding`, and a missing required id makes
  `bind()` throw and silently fall back to the phone layout. TV
  click behaviour unchanged (wiring is PHONE-only, as before).
- Custom Home Lists rows render through this same adapter/layout —
  covered automatically.

## 2. Pure black background

- `colors.xml`: `darkThemeBackground` #121212 → **#000000**.
  Verified it is referenced ONLY by `styles.xml` `AmoledModeLight`
  (the default Dark overlay) as `primaryBlackBackground` — so this
  is exactly the app dark-theme page background (Home root, head
  root). Card/surface colour `amoledModeLight` (#1F1F1F,
  `primaryGrayBackground`) untouched; Monet and all sheets/player
  surfaces untouched.

## 3. Poster corners tighter

- The radius was the SHARED dimen `rounded_image_radius` (20dp,
  used by account/download/result/search/cast layouts) — left
  alone. New home-only dimen `home_poster_radius` = **13dp** (~65%)
  applied in the three home-specific child layouts:
  `home_result_grid.xml`, `home_result_grid_expanded.xml` (both
  CardViews), `home_result_big_grid.xml`. Search/grid/feed posters
  unchanged. `home_remove_grid.xml` deliberately untouched (it is
  the hide-category management UI, not a home row).

## 4. Provider switcher → top-bar pill; floating pill removed

- Removed from `fragment_home.xml` + `fragment_home_tv.xml`:
  `home_api_fab` (ExtendedFloatingActionButton). Removed from
  `HomeFragment.kt`: `anchorHomeApiFab()` and every call site
  (fixLayout, onBindingCreated, page-load observer),
  the onDestroyView reset block, the FAB half of
  `applyHomeChromeVisibility()` (bottom-nav auto-hide kept,
  nav-only), the FAB click/long-click listeners, the
  `homeApiFab.text` writes in both observers, and the now-unused
  `Gravity`/`FrameLayout` imports. The 8.73/8.5C comments that
  described the FAB were rewritten to match. `home_random` FAB is
  untouched (still shrinks/extends on scroll).
- New pill in `fragment_home_head.xml`, in the hero top row between
  the search view and the profile avatar (new drawable
  `bg_home_provider_pill.xml`, same chip surface): puzzle icon
  (`ic_baseline_extension_24`, carries its own white tint), name
  TextView `home_provider_pill_text` (white, 13sp, single line,
  ellipsised, maxWidth 150dp), chevron
  (`ic_baseline_keyboard_arrow_down_24`, `app:tint` white). Focus
  chain updated: search → pill → profile.
- Wiring in `HomeParentItemAdapterPreview.kt` (`HeaderViewHolder`,
  nullable findViewById like its neighbours — null on the TV head):
  tap calls `Context.selectHomepage` with selection semantics
  copied 1:1 from the old FAB listener (custom list →
  `activateCustomList`; provider → `exitCustomMode` +
  `loadAndCancel(forceReload = true, fromUI = true)`); the label
  observes `viewModel.apiName` and renders through the existing
  `getDisplayName`. The loading holder's `home_change_api` button
  keeps its own listener/label as before.

## 5. Switcher sheet smaller

- `HomeFragment.selectHomepage`: `dialog.behavior.maxHeight` is
  now set to 72% of the screen height before `STATE_EXPANDED`
  (Material 1.14.0). The list is wrap_content and scrolls
  internally past the cap; the type-filter chips row stays pinned
  above it. All 8.83 behaviour (data, pins, tags, radio rows)
  untouched.
- `home_provider_switcher_row.xml`: row vertical padding 11dp → 8dp.
  `home_provider_switcher.xml`: title/close-button vertical padding
  trimmed slightly.

## 6. Hero animation → crossfade

- The hero is a ViewPager2 (`home_preview_viewpager`) with
  auto-advance (5s handler) — unchanged. `HomeScrollTransformer.kt`
  rewritten: the old padding-based slide is replaced by a pure
  crossfade — `translationX = -position * page.width` cancels the
  pager's layout travel (no horizontal movement) and
  `alpha = 1 - |position|` fades between pages; padding is zeroed
  because recycled pages carried the old transformer's padding.
  Class name/file kept (referenced from the holder + a doc comment,
  whose wording was updated to stay truthful).

## Deviations / notes

- **Continue Watching + Bookmarks headers keep their card style.**
  They are not adapter rows — they live in `fragment_home_head.xml`
  as separate holders (the CW title click opens a popup with a
  delete action; the Bookmarks header hosts a ChipGroup). The
  brief's header item targets the adapter rows; flattening those
  two cards too is a follow-up if he wants it.
- **A twin pill in the no-hero row.** `alternative_account_padding`
  (shown when there is no hero, e.g. the None provider, when the
  hero frame and its top row are hidden) got the same pill
  (`home_provider_pill_alt` / `home_provider_pill_text_alt`, same
  wiring) — otherwise the switcher would vanish exactly in the
  state where the floating pill used to be the entry point.
- **The FAB's long-press reload went with the FAB** (it was a
  FAB-only gesture; reload still exists via pull-to-refresh and the
  loading holder's reload affordances).
- **The hero has no indicator dots** in this implementation —
  nothing existed to keep or remove; auto-advance and the page
  callback are untouched.
- Poster radius: the brief's example assumed ~12dp; the actual
  value was 20dp, so 13dp is the ~65% equivalent.

## Verification (static — local Gradle is impossible on this VM)

- All 16 touched XML files parse (ElementTree).
- Zero remaining `homeApiFab` / `anchorHomeApiFab` / `home_api_fab`
  references in `app/src` outside the new 8.84 comments.
- Every new id resolves: `home_child_view_all` present in all three
  parent layouts; `home_provider_pill`, `home_provider_pill_text`,
  `home_provider_pill_alt`, `home_provider_pill_text_alt` in the
  head; string `home_view_all`, dimen `home_poster_radius`,
  drawables `bg_home_view_all` / `bg_home_provider_pill`,
  `ic_baseline_keyboard_arrow_right_24`,
  `ic_baseline_extension_24`, `ic_baseline_keyboard_arrow_down_24`
  all exist.
- Kotlin delimiter balance on the four touched Kotlin files:
  HomeFragment 315/315 braces, 826/826 parens; ParentItemAdapter
  29/29, 70/70; Preview 138/138, 341/341; Transformer 2/2, 6/6.
- No `android:tint` added (chevron uses `app:tint`; the puzzle
  vector carries its own tint); every new LinearLayout declares
  `android:orientation`; no code inserted between annotations and
  classes; no `${` in raw strings.
