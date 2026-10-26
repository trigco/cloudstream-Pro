# Phase 8.53 — collapsible builder sections, visible shimmer,
# Family Mode regex pass

## His verdict on 8.52 (screenshots)
1. Builder works, but with many providers scrolling the
   category list is painful: he asked for an icon beside each
   provider — tap ONLY the icon to show that provider's
   categories, tap again to hide them (provider names only).
2. Home skeleton placeholders show but the SHIMMER never
   plays — flat dark cards. Root cause (code): the
   ShimmerFrameLayout ran base_alpha 0.2 / highlight_alpha 0.3
   over a 2s duration on a black theme — the moving band
   peaks only 0.1 alpha above the base, effectively invisible.
3. Family Mode still misses titles: "XXX" glued at the end
   of a name, "sex" in the middle with lots of text around.
   His instruction: use the regex method, and add known
   18-rated movie/series lists from IMDb and other sources.

## Builder collapse (HomeCustomListEditFragment)
- Provider sections start COLLAPSED; buildRows emits category
  rows only for expanded providers (declared + dynamic).
- home_custom_provider_header.xml is now a bar (LinearLayout)
  with the title + a chevron (ic_baseline_keyboard_arrow_down,
  40dp tap target) at the end. ONLY the chevron toggles
  (rotation 180° when open); tapping the bar does nothing, so
  scrolling never opens a section by accident. Plain section
  headers and Fetch rows hide the chevron.

## Shimmer (fragment_home.xml, bottom_loading.xml)
base_alpha 0.2 -> 0.3, highlight_alpha 0.3 -> 0.85,
duration 2000 -> 1400ms. The sweep is now a clearly visible
bright band over the skeleton cards.

## Family Mode regex pass (utils/FamilyMode.kt)
- unglue(): splits case transitions and letter/digit
  boundaries before matching — "MovieXXX" -> "Movie XXX",
  "SexStory" -> "Sex Story", "XXXMovie" -> "XXX Movie".
- Glued substrings: a token containing "xxx" is adult; a
  token containing "sex" is adult unless it is an innocent
  lookalike (essex, sussex, middlesex, sexton, sextant).
- KNOWN_18_TITLES: well-known 18+ titles with no keyword in
  the name, matched on the normalised title — fifty shades,
  365 days, 365 dni, dark desire, lust stories, gandii baat,
  charmsukh, mastram, rasbhari, palang tod, basic instinct,
  showgirls, euphoria, toy boy, sex life.
