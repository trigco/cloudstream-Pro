# Phase 2 — Netflix-style redesign (structure & feel)

Base: fork branch `netflix-phase1` @ a0a5042 (Phase 1, build-verified green).
All edits were made on the fork's own files in this worktree. No upstream files
were copied in. No new dependencies. Not pushed — parent reviews and pushes.

## 1. Global rounded shapes — DONE

- `app/src/main/res/values/dimens.xml`
  - `rounded_image_radius`: 10dp → **16dp**.
  - Blast radius (checked before changing): this dimen is referenced by poster
    cards (`home_result_grid.xml` etc. via `app:cardCornerRadius`), the preview
    sheet poster card, account cards, the DUB/SUB/TYPE badge drawables
    (`dub_bg_color.xml`, `sub_bg_color.xml`, `type_bg_color.xml`), outline
    drawables (`outline.xml`, `outline_card.xml`) and TV player buttons
    (`player_button_tv_attr*.xml`). All of these get rounder; the small badges
    become pill-like. One-line revert if unwanted.
- `app/src/main/res/values/styles.xml`
  - New styles `ShapeAppearanceNetflixSmall` (cornerSize 12dp),
    `ShapeAppearanceNetflixMedium` (16dp), `ShapeAppearanceNetflixLarge` (24dp),
    each with `cornerFamily = rounded`. They are built with exactly the
    `cornerFamily`/`cornerSize` items the project already uses in
    `RoundedImageView` (styles.xml).
  - Wired into `AppTheme` via `shapeAppearanceSmallComponent`,
    `shapeAppearanceMediumComponent`, `shapeAppearanceLargeComponent`, so
    Material buttons / cards / dialogs / sheets that read the theme shape
    appearances pick up the rounding app-wide.
  - Rule-1 note: those three are Material3 theme attributes declared by
    AppTheme's parent (`Theme.Material3.Dark.NoActionBar`); the project itself
    already uses the shapeAppearance family (`shapeAppearance`,
    `shapeAppearanceOverlay` items in `AppModalStyle`). The new styles only use
    project-verified items. CI will confirm the theme attrs link.

## 2. Bottom-nav selected pill red — SKIPPED (per rule)

`colorSecondaryContainer` has **zero** references anywhere in `res/` or `java/`
(grep-verified). Per the Phase 2 rule ("if never referenced, skip and
document"), it was not set: there is no project-side evidence the nav pill in
this Material version is driven by that theme attr, and Phase 1 already showed
this Material build drops/renames nav attrs (`itemActiveIndicatorColor` does
not exist in it). Setting an unreferenced attr would be a no-op at best.

## 3. Preview bottom sheet rounded — DONE (phone), sort sheet skipped

- `app/src/main/res/drawable/bg_sheet_rounded_top.xml` — NEW. Shape, solid
  #1F1F1F, top-left/top-right corners 24dp, bottom corners 0.
- `app/src/main/res/layout/bottom_resultview_preview.xml` — the content
  container `@+id/resultview_preview_result` now has
  `android:background="@drawable/bg_sheet_rounded_top"`. All IDs and structure
  unchanged. (The loading state keeps the dialog theme's own
  `rounded_dialog` background, 16dp top corners — pre-existing.)
- `sort_bottom_sheet.xml` — NOT changed: different pattern (root has
  `android:background="@null"`, full-height list sheet), not the same sheet
  pattern as the preview sheet.
- `bottom_resultview_preview_tv.xml` — not changed (TV variant out of scope
  for this phone-first pass).

## 4. Details page Play button red — DONE

- `app/src/main/res/layout/fragment_result.xml` — `@+id/result_play_movie`
  (the "Play Movie" button, previously white via the shared `WhiteButton`
  style) now carries instance-only overrides copied 1:1 from the Phase 1 hero
  Play button in `fragment_home_head.xml`:
  `android:textColor="@color/white"`, `app:backgroundTint="@color/colorPrimary"`
  (#E50914), `app:iconTint="@color/white"`,
  `app:strokeColor="@color/transparent"`. The shared `WhiteButton` style is
  untouched. TV/swipe result layouts not changed.

## 5. Hero auto-slide — DONE (Kotlin)

- `app/src/main/java/com/lagradost/cloudstream3/ui/home/HomeParentItemAdapterPreview.kt`
  - The hero `ViewPager2` (`R.id.home_preview_viewpager` in
    `fragment_home_head.xml`) is owned by this adapter's `HeaderViewHolder`:
    it sets `previewViewpager.adapter = previewAdapter` in `init` and already
    registers/unregisters its page callback in
    `onViewAttachedToWindow()` / `onViewDetachedFromWindow()`. The auto-slide
    was therefore implemented in this class, on that exact lifecycle.
  - Added: a `Handler(Looper.getMainLooper())` + `autoSlideRunnable` that every
    5 s calls `setCurrentItem((currentItem + 1) % itemCount, true)` — only when
    `previewAdapter.itemCount > 1` and the user is not dragging. Drag state
    comes from a new `onPageScrollStateChanged` override on the existing
    `previewCallback` (`SCROLL_STATE_DRAGGING`). The runnable is posted in
    `onViewAttachedToWindow()` and removed in `onViewDetachedFromWindow()`,
    so it cannot leak or run off-screen. Imports added: `android.os.Handler`,
    `android.os.Looper` (same `android.os` package the file already imports).
  - Note: this also drives the TV header variant (same holder class) — a slow
    5 s hero rotation there is consistent with TV home screens; focus is not
    stolen because `setCurrentItem` on the pager does not move D-pad focus.

## 6. Settings main menu icons — DONE

The settings start screen (`res/layout/main_settings.xml`) builds its entries
as plain `TextView`s with style `@style/SettingsItem` (wired by view binding in
`SettingsFragment.kt`). The style already shows a chevron via
`drawableEndCompat`, and the matching start slot `app:drawableStartCompat` is
used elsewhere in the project (`sort_bottom_footer_add_choice.xml`,
`sort_bottom_single_choice_double_text.xml`) — so icons were added per item,
in the layout only (shared `SettingsItem` style untouched; it is also used by
7 other layouts). Each entry also got `android:drawablePadding="14dp"`
(attr already used in the project).

- New stroke-style vectors (24dp viewport, strokeWidth 1.8, round caps,
  `strokeColor="?attr/white"` — the same pattern as `netflix_download.xml`;
  lines/circles/rects/arcs only, no fills):
  - `res/drawable/ic_settings_general.xml` — gear (ring + 8 spokes)
  - `res/drawable/ic_settings_player.xml` — play triangle in a circle
  - `res/drawable/ic_settings_providers.xml` — globe (circle + meridian
    ellipse + equator)
  - `res/drawable/ic_settings_layout.xml` — 2×2 grid
  - `res/drawable/ic_settings_update.xml` — circular arrow
  - `res/drawable/ic_settings_account.xml` — person (head + shoulders);
    person chosen over shield to stay within simple arc/line geometry
  - `res/drawable/ic_settings_extensions.xml` — square block with a plus
    (puzzle substitute)
- Wiring in `main_settings.xml`: settings_general → general icon,
  settings_player → player, settings_providers → providers,
  settings_ui → layout, settings_updates → update,
  settings_credits (Accounts and Security) → account,
  settings_extensions → extensions.

## 7. Bottom nav icon pack — DONE for nav-exclusive icons, 2 skipped

Menu: `res/menu/bottom_nav_menu.xml` (used by both the bottom nav and the nav
rail in `activity_main.xml`). The home/library/settings items resolve through
selectors to vectors that are referenced **only** by those selectors, which are
referenced only by this menu (grep-verified) — those six files were rewritten
in place (same filenames, now 24dp viewport, bolder/rounder):

- `res/drawable/home_icon_filled_24.xml` — bold filled house with door cutout
- `res/drawable/home_icon_outline_24.xml` — stroked house outline (round caps)
- `res/drawable/library_icon_filled.xml` — bold filled folder
- `res/drawable/library_icon.xml` — stroked folder outline
- `res/drawable/settings_icon_filled.xml` — bold sliders (thick lines + solid
  knobs) — replaces the gear art for the nav settings item
- `res/drawable/settings_icon_outline.xml` — sliders outline (hollow knobs)

Skipped, deliberately:
- `search_icon` (Search item) — shared by ~20 files (home, search, result,
  library layouts incl. TV variants, menus, `settings_ui.xml`). Restyling it
  would silently change search glyphs on every screen, outside nav scope.
- `netflix_download` (Downloads item) — shared beyond the nav (home,
  home TV, result layouts, repository menu, `settings_general.xml`,
  `settings_updates.xml`).

Both still render fine in the nav (they are the app's existing stroke-style
glyphs at the same 24dp size); they just keep their current artwork.

## 8. Window blur — NOT attempted (per instructions)

Real background blur behind dialogs/sheets needs API 31+ window blur APIs;
the user's phone is API 27. Not implemented. The sheets instead get the
rounded, solid #1F1F1F treatment from item 3 on every API level.

## Validation

- Every edited/created XML file was parsed with Python minidom after editing
  (see parent report for the list/result).
- Kotlin change is confined to one file, ~35 added lines, no new dependencies,
  no new packages imported.
