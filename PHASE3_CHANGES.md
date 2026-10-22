# Phase 3 — Polish Round (on top of Phase 1 + 2, branch netflix-phase1 @ 3d30111)

User feedback addressed: rounding too timid, settings icons invisible (thin strokes),
details chips squarish, description not justified / "not collapsible", episode rows flat,
provider titles cluttered with quality/source junk.

## Changed files

### 1. More rounding
- `res/values/dimens.xml` — `rounded_image_radius` 16dp → **20dp** (posters, episode cards,
  preview sheet poster, badges — everything already referenced this dimen).
- `res/values/styles.xml` — `ShapeAppearanceNetflixSmall` 12→**16dp**, `Medium` 16→**20dp**,
  `Large` 24→**28dp** (styles already wired into AppTheme in Phase 2; values only).

### 2. Settings menu icons → filled bold (`res/drawable/ic_settings_*.xml`, all 7 rewritten)
Root cause of the "invisible icons" report: the Phase 2 icons were 1.8-wide **strokes**
coloured `?attr/white`. All 7 are now **filled** shapes with `android:fillColor="#FFFFFF"` —
the exact recipe of the bottom-nav icons (e.g. `home_icon_filled_24.xml`) that provably
render on the user's phone. No strokeColor / `?attr` tricks anywhere.
- `ic_settings_general` — filled gear: disc with centre hole + 8 teeth (3 paths, evenOdd hole)
- `ic_settings_player` — filled disc with punched play-triangle hole (evenOdd)
- `ic_settings_providers` — filled globe: disc with punched meridian ellipse + equator band (evenOdd)
- `ic_settings_layout` — 4 filled rounded squares (2×2 grid)
- `ic_settings_update` — filled thick arc band + arrow head
- `ic_settings_account` — filled head circle + shoulders arc
- `ic_settings_extensions` — filled rounded block + top knob circle (puzzle substitute)
Wiring unchanged (Phase 2's `app:drawableStartCompat` in `main_settings.xml`).

### 3. Details description (`res/layout/fragment_result.xml` + `ResultFragmentPhone.kt`)
- Layout `@+id/result_description`: `android:maxLines` 10 → **5**,
  added `android:justificationMode="inter_word"`.
  - Attribute note: `android:justificationMode` is a **framework** attribute (API 26+),
    not previously used in this project. AAPT validates `android:` attrs against the
    compileSdk (37) framework, so it links; on API 23–25 the TextView simply ignores it
    (no crash, text just isn't justified there). The user's phone is API 27 → justified.
    If strict rule-1 compliance is preferred, delete this one attribute — the rest stands.
- Code discovery: a tap-to-expand listener **already existed** in `ResultFragmentPhone.kt`
  (toggles `maxLines` between `Integer.MAX_VALUE` and `10`) — with the page showing 10
  lines by default it never *felt* collapsible. Changed the collapsed value `10` → `5`
  to match the layout, so tap now visibly collapses to 5 lines / expands to full.

### 4. Genre chips → full pills (`utils/UIHelper.kt`, `populateChips`)
Genre/tag chips are created in exactly one place: `UIHelper.populateChips`
(Chip + ChipDrawable from `R.style.ChipFilled`). Added after `setChipDrawable`:
`chip.chipCornerRadius = 100dp in px` (via `TypedValue.applyDimension`; new import
`android.util.TypedValue`, already a framework class used across the module).
Blast radius (deliberate): `populateChips` also feeds the TV result screen and the
home preview sheet — their tag chips become pills too, which is the same design language.

### 5. Season button (`res/layout/fragment_result.xml`)
`@+id/result_season_button` is a `MaterialButton` (style `MultiSelectButton`).
Added instance-only `app:cornerRadius="24dp"`.
Grep evidence: `app:cornerRadius` is already used on MaterialButtons in
`res/layout/fragment_player.xml` (4dp), `res/layout/player_custom_layout.xml`,
and at 100dp in `res/layout/result_tag.xml`.

### 6. Episode row tap ripple — NO CHANGE (already present)
Both phone episode layouts already carry the ripple on their content holder:
- `res/layout/result_episode.xml` — inner LinearLayout has
  `android:foreground="?android:attr/selectableItemBackgroundBorderless"`
- `res/layout/result_episode_large.xml` — inner LinearLayout has the same foreground.
Adding another layer would double the effect. (The CardView root's foreground slot is
taken by `@drawable/outline_drawable`, a focus-outline selector, in both files.)

### 7. Clean title on details — phone only (`ResultFragmentPhone.kt`)
- New `private fun cleanDisplayTitle(raw: String)` (~20 lines) in `ResultFragmentPhone`,
  applied at the single phone display point:
  `resultTitle.text = cleanDisplayTitle(d.titleText.asString(resultTitle.context))`
  (was `resultTitle.setText(d.titleText)`; `bindLogo` only toggles visibility, never text,
  so this is the only place the phone title is written).
- Rules (conservative, exactly as specced):
  - Remove bracketed quality tags `[...]`/`(...)` containing 480p/720p/1080p/2160p/4K/
    x264/x265/HEVC anywhere (the year in "Eyes (2026)" is untouched).
  - If the title contains `" | "` and the tail contains any junk token (resolutions,
    x264/x265, HEVC, WEB-DL, WEBRip, BluRay, HDRip, CAMRip, "Complete"), cut from the
    first `" | "` onward.
  - Collapse double spaces; trim trailing spaces/`|`/`-`.
- Worked example: `Eyes (2026) Hindi S01 Complete SONYLIV | 720p x265`
  → `Eyes (2026) Hindi S01 Complete SONYLIV`.
- Stored data, search results, home rows and the TV screen (`ResultFragmentTv` +
  `fragment_result_tv.xml`) are untouched.

## Validation
- All 10 edited XML files parse cleanly (Python minidom): dimens, styles,
  fragment_result, and the 7 ic_settings_* drawables.
- No new XML attributes introduced except `android:justificationMode` (framework,
  see §3) and `app:cornerRadius` on the season button (grep-verified existing usage, §5).
- Kotlin edits are minimal, no new dependencies; only new import is
  `android.util.TypedValue` in UIHelper.kt.
- Not compile-verified locally (no Android SDK on this VM) — CI is the check, as before.

## Skips
- Episode ripple (§6): already implemented upstream — nothing to add.
- Nothing else skipped.
