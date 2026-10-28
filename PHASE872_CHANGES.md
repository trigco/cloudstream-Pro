# Phase 8.72 — MaterialSwitch + real Monet colors (his 2026-10-28 report)

STAGED LOCALLY — push after the 8.71 verdict (stacks on feed-polish).

Diagnosis (code-verified):
- AppTheme is Material3, but every switch in the app was the old
  AppCompat SwitchCompat: settings rows, Stremio/Nuvio rows, and the
  Nuvio settings-form toggles built in code — small thumb, plain slide,
  none of Google's M3 switch animation.
- MonetMode mapped every surface attr to material_dynamic_NEUTRAL*
  (the gray palette by definition); the colorful dynamic primary /
  secondary palettes were wired only to colorPrimary/colorAccent,
  which the redesign's monochrome surfaces never show. Hence Android
  12+ "Material You" rendered as wallpaper-tinted grays only.

Changes:
1. SwitchCompat -> MaterialSwitch (subclass of SwitchCompat, so all
   Kotlin bindings keep compiling) in main_settings.xml,
   item_stremio_addon.xml, item_nuvio_scraper.xml, and the programmatic
   settings-form toggle in NuvioPluginsFragment (MaterialSwitch(ctx)).
   M3 switch animation (thumb grow + check morph + track fill) comes
   from the Material3 theme automatically.
2. MonetMode surfaces tinted: primaryGrayBackground and
   boxItemBackground -> material_dynamic_primary20; iconGrayBackground
   -> material_dynamic_secondary20. Page background + text stay
   neutral for contrast. Dark theme untouched.
3. His choice (2026-10-28): Dark-mode switch ON stays white/light;
   color appears only under Monet (where the overlay already sets
   colorPrimary = dynamic primary80, which the M3 switch follows).

Files: styles.xml, main_settings.xml, item_stremio_addon.xml,
item_nuvio_scraper.xml, NuvioPluginsFragment.kt.
