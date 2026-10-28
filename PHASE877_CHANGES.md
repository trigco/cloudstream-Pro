# Phase 8.77 — SkyStream design language, Phase A (foundation + Extensions family)

His call (2026-10-28): adopt the FULL SkyStream design language,
in stages with his verdict between them. Tokens taken from the
SkyStream app's own theme source, not eyeballed: primary #448AFF,
surface #18181F, surface highlight #22222E, pure-black background,
AnimatedSize 300 ms expansions, Outfit font (font swap deferred
to Phase B — it shifts metrics on every screen).

This phase:
1. **Foundation.** Dark background #121212 → pure black #000000
   (darkThemeBackground); Dark card surface #1F1F1F → SkyStream
   surface #18181F (amoledModeLight); chip surface → #22222E
   (iconGrayBackground). New tokens skyAccent #448AFF,
   skyAccentLight #A8C7FA, skyOnAccent #0B57D0, skySurface,
   skyOutline #22222E. Repository/settings cards gain the 1dp
   outline stroke SkyStream's cards carry (bg_repository_card) —
   this also lifts the Stremio page and Extensions rows, which
   share the drawable.
2. **Nuvio Plugins: the dropdown the GitHub comment asked for.**
   Repo groups now open/close with RecyclerView insert/remove
   animations (260/200 ms, item-animator tuned) instead of a full
   list rebuild, and the chevron animates over 250 ms. Header +
   scraper rows form ONE grouped card while expanded: header
   takes bg_repository_card_top (rounded top, square bottom, no
   bottom margin), rows take bg_group_mid / bg_group_bottom
   (last row), gaps inside the group removed — one surface, one
   set of corners, exactly the SkyStream Repositories pattern.

Phase B (next, on his verdict): Home + Feed surfaces (section
headers with accent underline + View All pill, card corners,
bottom nav, Outfit font). Phase C: the SkyStream details page
(Premium Details).

Files: colors.xml, bg_repository_card.xml,
bg_repository_card_top.xml (new), bg_group_mid.xml (new),
bg_group_bottom.xml (new), NuvioPluginsFragment.kt.
