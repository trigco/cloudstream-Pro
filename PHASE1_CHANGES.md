# Phase 1 — Netflix-style redesign (resources only)

Design direction: Netflix black + red. Base `#141414`, pure black `#000000` where it
already fits, elevated surfaces `#1F1F1F` / `#232323`, accent `#E50914`,
primary text `#FFFFFF`, secondary text `#B3B3B3`. No Kotlin/Java code was changed.
No resource was renamed or deleted. Edits are in the working tree only (not pushed).

## Architecture findings (inspected before editing)

- **Theme system is centralized — the palette approach is safe.** `AppTheme`
  (Material3 Dark, `res/values/styles.xml`) binds custom attributes
  (`primaryBlackBackground`, `primaryGrayBackground`, `boxItemBackground`,
  `iconGrayBackground`, `textColor`, `grayTextColor`, `iconColor`, …) to named
  colors in `res/values/colors.xml`. At runtime `CommonActivity.loadThemes()`
  applies two overlays on top: a base variant (setting `app_theme_key`, default
  **"AmoledLight"** → `AmoledModeLight`/`AmoledMode`; "Black" → plain `AppTheme`)
  and a primary-color overlay (setting `primary_color_key`, default
  `OverlayPrimaryColorNormal`) which maps `colorPrimary`/`colorAccent` attrs to
  `@color/colorPrimary` / `@color/colorAccent`. Layouts reference `?attr/…` and
  `@color/…` names — no hard-coded hex in the home/navigation path — so changing
  color *values* propagates app-wide without touching layouts.
- **Default experience note:** because the default base variant is Amoled, the
  default background stays pure black (`AmoledMode` maps backgrounds to
  `@color/black`) while its surfaces come from `@color/amoledModeLight`. The
  #141414 base applies to the `AppTheme`/"Black" variant. Both were restyled.
- **Bottom navigation:** Material `BottomNavigationView` + `NavigationRailView`
  in `activity_main.xml`; background `?attr/primaryGrayBackground`; icon/text
  tint from `res/color/item_select_color.xml` (previously: checked →
  `?attr/colorPrimary`, unchecked → `?attr/textColor`). Material library is
  1.14.0, so `itemActiveIndicatorColor` is supported.
- **Home screen:** `fragment_home.xml` → `home_master_recycler` (vertical list of
  `homepage_parent.xml` rows: `WatchHeaderText` section header + horizontal
  RecyclerView of `home_result_grid.xml` posters). Posters are `CardView`s whose
  radius is `@dimen/rounded_image_radius` = **already 10dp**. The hero is
  `fragment_home_head.xml`: a 500dp `ViewPager2` backdrop with a bottom control
  bar (Bookmark / Play / Info). There is **no title TextView** in the hero — the
  "title holder" contains only the three controls — and there was **no gradient
  scrim** over the backdrop.
- **Launcher icon / splash:** adaptive icon background is
  `@color/ic_launcher_background` = **already `#000000`**; foreground glyph kept
  untouched. Splash is `AppTheme`'s `windowBackground` =
  `drawable/splash_background.xml` (layer-list: `?attr/primaryBlackBackground` +
  centered logo), i.e. attribute-driven and theme-adaptive.
- **Fonts:** the app already bundles fonts (`res/font/google_sans` is the global
  `AppTextViewStyle` font; `netflix_sans.ttf` also exists in-repo). Per Phase 1
  constraints no fonts were added or switched; headings were strengthened by
  size only.

## Files changed

### `app/src/main/res/values/colors.xml` (values only + 1 addition)
- `colorPrimary` `#3d50fa` → `#E50914` · `colorAccent` `#3b65f5` → `#E50914` ·
  `colorPrimaryDark` `#3700B3` → `#B20710` — default accent becomes Netflix red
  everywhere via `AppTheme` and `OverlayPrimaryColorNormal`.
- `colorPrimaryRed` `#D50000` → `#E50914` — the user-selectable "Red" primary
  option is now the exact Netflix red.
- `primaryBlackBackground` `#111111` → `#141414` (page background),
  `primaryGrayBackground` `#2B2C30` → `#1F1F1F` (bars / bottom nav / surfaces),
  `iconGrayBackground` `#1C1C20` → `#1F1F1F`,
  `boxItemBackground` `#161616` → `#232323` (cards).
- `amoledModeLight` `#121213` → `#1F1F1F` — surfaces of the *default* AmoledLight
  theme (its background remains pure black, which fits the palette).
- `textColor` `#e9eaee` → `#FFFFFF`, `grayTextColor` `#9ba0a4` → `#B3B3B3`,
  `iconColor` `#9ba0a6` → `#B3B3B3`.
- `colorSearch` `#303135` → `#232323` (search box surface).
- **Added** `navIndicatorRed` `#4DE50914` (translucent Netflix red, bottom-nav
  active indicator).
- Untouched: Light / Dracula / Lavender / Silent Blue palettes and all other
  user-selectable primary colors — alternate themes keep their identity.

### `app/src/main/res/values/styles.xml`
- `AppTheme`: `android:statusBarColor` `?attr/iconGrayBackground` →
  `?attr/primaryBlackBackground`, so status bar + navigation bar (already
  `primaryBlackBackground`) blend with the page background in every theme.
- `WatchHeaderText` (home section headers): `textSize` 18sp → 20sp (already bold).

### `app/src/main/res/color/item_select_color.xml`
- Selected/checked/focused → `@color/white`; unselected → `@color/grayTextColor`
  (#B3B3B3). Previously selected was the primary color and unselected was full
  text color. Also affects the navigation rail and TV main layout, which share
  this selector — same semantics, consistent result.

### `app/src/main/res/layout/activity_main.xml`
- `BottomNavigationView`: added `app:itemActiveIndicatorColor="@color/navIndicatorRed"`
  (red accent pill behind the white selected icon). Background already
  `?attr/primaryGrayBackground` → now #1F1F1F / black depending on theme.

### `app/src/main/res/drawable/gradient_home_hero_scrim.xml` (new)
- Vertical gradient, transparent → `#141414`, for the hero backdrop.

### `app/src/main/res/layout/fragment_home_head.xml`
- Added a 220dp bottom-anchored scrim `View` over the hero `ViewPager2`
  (below the controls) using the new gradient — backdrop now fades into the
  page background behind Bookmark / Play / Info.
- Hero Play button (`home_preview_play`): instance-level overrides only —
  `app:backgroundTint="@color/colorPrimary"` (#E50914), white text, white icon
  tint, transparent stroke. The shared `WhiteButton` style was **not** modified
  (it is used in ~37 layouts incl. dialogs).
- Continue-watching / bookmarked row RecyclerViews: horizontal padding
  5dp → 8dp.

### `app/src/main/res/layout/homepage_parent.xml`
- Row RecyclerView horizontal padding 5dp → 8dp; root gained
  `android:paddingBottom="6dp"` for clearer row separation.

### `app/src/main/res/layout/fragment_home.xml` + `app/src/main/res/anim/` (2 new files)
- `home_master_recycler`: added `android:layoutAnimation="@anim/home_master_layout_animation"`
  (one attribute, zero code). New `home_master_layout_animation.xml`
  (staggered, 8% delay) + `home_row_fade_in.xml` (350ms fade, decelerate) give
  home rows a gentle fade-in on first layout.

## Skipped / not done (with reasons)

- **Poster corner radius** — already 10dp via `@dimen/rounded_image_radius` on
  the `CardView` in `home_result_grid.xml`; no change needed.
- **Hero title size** — no title TextView exists in the hero (title is part of
  the backdrop/metadata presentation); nothing safe to restyle in resources.
- **Launcher icon recolor** — adaptive-icon background is already `#000000`.
  Pre-API-26 PNG mipmaps were not regenerated (would require redrawing assets).
- **Splash background → pure black** — skipped: the splash drawable is
  attribute-driven (`?attr/primaryBlackBackground`) so it adapts per theme
  (white for Light theme); it now resolves to #141414 / black in dark themes.
  Hard-coding black would break the Light theme's splash.
- **Global Material shape appearances** — skipped: most surfaces are plain
  Views/`CardView` (radius already handled per-layout), and global
  `shapeAppearance*` attrs risk silently reshaping dialogs/buttons app-wide.
- **DUB/SUB/TYPE badge colors on posters** (`dubColorBg`, `subColorBg`,
  `typeColorBg` + their paired text colors) — still blue/pink/purple; changing
  them safely requires changing each bg+text pair together for contrast.
  Flagged as a Phase 2 candidate.
- **TV layouts** (`activity_main_tv.xml`, `fragment_home_head_tv.xml`, …) —
  out of Phase 1 scope; they inherit the new palette automatically, and the
  shared nav selector already restyles TV nav states.
- **`grayShimmer`** — left as-is: with the shimmer alphas (base 0.2) its
  effective on-screen tone is already dark; changing the raw value could make
  skeletons nearly invisible.

## Validation

- All 7 edited + 3 added XML files parse cleanly (Python `xml.dom.minidom`):
  colors.xml, styles.xml, item_select_color.xml, activity_main.xml,
  fragment_home.xml, fragment_home_head.xml, homepage_parent.xml,
  gradient_home_hero_scrim.xml, home_master_layout_animation.xml,
  home_row_fade_in.xml — **10/10 valid**.
- No Kotlin/Java files were modified. `.github/workflows` untouched. Nothing pushed.
- Not compile-verified (no Android SDK in this environment) — first CI build on
  the fork is the compile check.


## Build verification
Phase 1 is verified by the fork's "Artifact Build" (pull_request) workflow on the PR from `netflix-phase1` to `master` — it compiles `assemblePrereleaseDebug` and uploads a test APK.
