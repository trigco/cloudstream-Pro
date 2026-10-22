# Phase 7 — Settings Inner Pages (local only, NOT pushed)

User feedback addressed: settings sub-pages (Player, General, UI, …) felt like plain
text + toggles with no animation, and the drag-line sliders ("Parallel downloads",
"Concurrent connections", …) felt backdated. The main settings menu was already
grouped in Phase 5A and is untouched here.

## Findings (investigation)

- **Sub-page structure:** Six `PreferenceFragmentCompat` subclasses under
  `ui/settings/` (SettingsGeneral, SettingsPlayer, SettingsUI, SettingsAccount,
  SettingsProviders, SettingsUpdates), each inflating one `res/xml/settings_*.xml`.
  ALL SIX extend a shared base class, `BasePreferenceFragmentCompat`, which lives in
  `app/src/main/java/com/lagradost/cloudstream3/ui/BaseFragment.kt` (line ~268) and
  already overrides `onViewCreated` to call `setSystemBarsPadding()`. → The shared
  hook for item 1 exists; one base-class edit covers every sub-page.
  (Scope note: the edit is confined to the `BasePreferenceFragmentCompat` class
  inside BaseFragment.kt; the other classes in that file are untouched.)
- **Sliders:** All 7 sliders are the **framework** `androidx.preference.SeekBarPreference`
  (settings_general.xml ×2, settings_player.xml ×3, settings_ui.xml ×2), all with
  `app:showSeekBarValue="true"`. There is NO custom preference class and NO Kotlin
  value handling to mirror — BUT the app already overrides the style:
  `CustomPreferenceThemeOverlay` → `seekBarPreferenceStyle` =
  `@style/CustomSeekBarPreferenceStyle` → `android:layout` =
  `@layout/custom_preference_widget_seekbar`. So the whole slider row is one
  app-owned layout file, and the SeekBar inside it simply had no explicit
  `progressDrawable`/`thumb` (hence the thin dated Material defaults).
  → Per the task's framework-class route (custom layout, thicker rounded track,
  larger thumb, always-visible value), restyling that one layout + new drawables
  modernizes all 7 sliders at once. A stepper (− value +) was NOT built against the
  framework class, per instructions.
- **Switches:** `SwitchPreferenceCompat` (custom_preference_material rows) — native
  switch animation + framework ripple already; unchanged.
- **Category headers:** A precedent exists but is DISABLED: styles.xml contains a
  commented-out `CustomPreferenceCategoryStyle` pointing at
  `@layout/custom_preference_category_material`, and the overlay has no
  `preferenceCategoryStyle` item. See "Skipped" below.

## Changes

### 1. List entrance animation (fade + rise, staggered)
- NEW `res/anim/settings_item_enter.xml` — alpha 0→1 + translateY 12%→0%, 280ms,
  decelerate, shared interpolator.
- NEW `res/anim/settings_list_layout_animation.xml` — `<layoutAnimation>` controller,
  delay 0.07, normal order.
- EDIT `ui/BaseFragment.kt` (`BasePreferenceFragmentCompat.onViewCreated`, +1 import
  `android.view.animation.AnimationUtils`): after `super` + `setSystemBarsPadding()`,
  `listView.layoutAnimation = AnimationUtils.loadLayoutAnimation(requireContext(),
  R.anim.settings_list_layout_animation)`. `listView` is PreferenceFragmentCompat's
  own RecyclerView; every sub-page fragment calls `super.onViewCreated` (verified
  for General/Player/UI; the base padding call already depends on this chain).

### 2. Modern sliders (one layout → all 7 SeekBarPreferences)
EDIT `res/layout/custom_preference_widget_seekbar.xml` (ids, structure, paddings,
`UnPressableLinearLayout` workaround and the 48dp touch area all preserved):
- SeekBar gains `android:progressDrawable="@drawable/settings_seekbar_progress"` and
  `android:thumb="@drawable/settings_seekbar_thumb"`.
- NEW `res/drawable/settings_seekbar_progress.xml` — layer-list: 8dp rounded track
  (`?attr/colorControlNormal`) + clipped 8dp rounded progress
  (`?attr/colorControlActivated`), items `center_vertical` (item gravity needs API 23
  = exactly minSdk). Theme attrs, not literals, so dark/light variants both resolve
  correctly — the app's styles map `colorControlActivated` → `?attr/colorPrimary`
  (white on dark themes, near-black on Light, the Phase 5A monochrome accent).
- NEW `res/drawable/settings_seekbar_thumb.xml` — 22dp oval in
  `?attr/colorControlActivated` (default thumb is ~16dp).
- Value bubble: the existing `@+id/seekbar_value` TextView (already always visible)
  gains `android:background="@drawable/settings_value_chip"` (NEW: 10dp-radius
  outlined pill, 1dp `?attr/colorControlNormal` stroke, transparent fill), 10dp side
  padding, `gravity="center"`, `textStyle="bold"` — the number now reads as a chip
  instead of bare text. minWidth dimen unchanged.
- **No preference XML, key, default, min/max, or increment was modified anywhere**
  (no `res/xml/settings_*.xml` file touched); value persistence is entirely the
  framework's, unchanged.

## Skipped + why

- **Category header restyle (item 3):** mechanism exists but is switched off
  (commented-out `CustomPreferenceCategoryStyle` in the shared `values/styles.xml`).
  Enabling it requires editing that shared styles file (outside this task's file
  scope; other Phase 7 agents are active) and the dormant
  `custom_preference_category_material.xml` has never rendered in this app's current
  theme, so its spacing/typography can't be verified locally. Recommended as a small
  follow-up: uncomment + wire `preferenceCategoryStyle` in
  `CustomPreferenceThemeOverlay`, then eyeball one sub-page per theme.
- **Per-group card backgrounds via ItemDecoration:** not attempted (per instructions;
  the grouped-card look already exists on the main settings menu from Phase 5A).
- **Extensions sub-pages** (`ui/settings/extensions/`, plugin lists): not
  PreferenceFragment screens, so the base-class animation doesn't reach them; out of
  the user's "text + toggles + sliders" complaint scope.

## Risks

- CI is the first real compile (no local SDK): the Kotlin change uses only
  `listView` (PreferenceFragmentCompat API), `AnimationUtils`, and the already-imported
  `R`; the new `android.view.animation.AnimationUtils` import was added.
- Layout animation runs on first layout of each settings page (and on TV builds too —
  same base class; the 280ms fade is subtle and harmless there).
- Layer-list item `android:gravity` requires API 23+; minSdk is 23, OK. On API 23 the
  seekbar thumb/track alignment with a 22dp thumb follows the standard framework
  thumb-offset behaviour (computed from intrinsic size); the layout's existing
  vertical padding (48dp touch zone) is unchanged.
- `?attr/*` colors are used in drawable `<solid>`/`<stroke>` values — precedented in
  this codebase (`bg_color_top.xml` etc. use `?attr/primaryGrayBackground` the same
  way); these are shape colors, NOT vector path fillColors (the merger trap from
  Phase 6 does not apply).
