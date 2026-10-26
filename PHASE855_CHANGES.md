# Phase 8.55 — Feed/search/theme upgrade (his reference batch)
# + Phase 8.54 Family Mode vocabulary (pushed together)

## His brief (screenshots of a reference app + a listing site)
- Feed cards like theirs: quality, rating, Movie/Series/Anime
  and Dual Audio labels; gray card background.
- Title bar like theirs; the feed title bar has too much space.
- Feed search: Material bar, suggestions, skeleton shimmer,
  recent searches, filters — Year, Provider, Movie/Series/Anime.
- Pure-black theme breaks sections up: manage black with gray
  and gradients.
- App font: Baloo Da 2.
- "সব high ডিমান্ড কাজ একটু ভালো করে করিও" — do it properly.

## Feed cards (feed_card.xml, FeedAdapter)
- Badges on the poster, each shown only when data exists:
  ★ rating (score, top-start, amber on dark pill), quality
  (SearchQuality label, top-end), type (MOVIE/SERIES/ANIME…,
  bottom-start), language (green pill: DUAL AUDIO, else the
  first language named in the raw title).
- Card background is now an explicit warm gray #232329.

## Watch title bar (fragment_feed_watch.xml)
- Title box top margin 12 -> 6dp, action row gap 12 -> 8dp.

## Feed search (fragment_feed.xml, FeedFragment)
- Material bar: 28dp-radius tonal gray (#2B2B2B), taller
  touch height.
- Filter row (visible during search): type chips All / Movie
  / Series / Anime + Provider and Year chips opening menus
  built from the actual results (providers present, years
  present, max 15). Applied client-side over merged results.
- Suggestions drop-down under the header: pool titles
  matching the typed text (max 8) + matching recents; tapping
  one runs it as the search. Scrolling dismisses it.
- Recent searches (last 10, feed_recent_searches_v1): saved
  on deep search, on IME search, and when a result is
  opened; shown when the box is focused and empty, with a
  Clear all row.
- Deep search with no local hits now shows the skeleton
  shimmer until results land (the skeleton also gained the
  visible shimmer config: base 0.3 / highlight 0.85 / 1400ms).

## Theme + font
- bg_main_gradient (dark gray #1E1E26 -> black) on the Feed
  root + header and the Home root, replacing flat black.
- Baloo Da 2 (variable TTF, OFL) added at
  res/font/baloo_da_2.ttf via web upload; family file
  res/font/baloo_da_2_family.xml; every @font/inter reference
  in styles.xml (10) now points at it — the whole app renders
  in Baloo Da 2. (fragment_actor/toast keep Inter.)

## Phase 8.54 (from the previous round, same push)
Family Mode scene vocabulary: phrases hot babe / casting
couch / cheating wife+husband / body massage / full body
massage / happy ending massage; ~50 scene words (babe, bbc,
pov, inappropriate, pervert, masseur/masseuse, nuru, tantric,
sensual, pornstar, step-* family, bhabhi, aunty…); stems
seduc/stripper; non-Latin substrings (セックス, おまんこ,
エッチ, ポルノ, 色情, 成人电影, 无码, 有码).

## For You (added at his request while the batch waited)
- The watch page's "Up next" heading is now "For You"
  (feed_up_next string).
- refreshUpNext ranks the pool before showing it
  (forYouRanked): same type as the playing title (+3), same
  provider (+2), types/providers of his feed Likes (+2/+1.5),
  genre/tag affinity — the playing page's tags plus genre
  words in its title (thriller, korean, horror…) matched
  against candidate titles (+2 each) — and rating as the
  top/trending proxy. Pull-to-refresh still rotates unseen
  items first, now within the ranked order.
