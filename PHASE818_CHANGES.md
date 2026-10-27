# Phase 8.18 — The Feed (owner's feature, 2026-10-24)

A dedicated Feed section, YouTube/Facebook-style, per the owner's spec and
his two decisions: watch page = option A (player on top in portrait, feed
continues below), and the feed's player is a CUSTOM player — rotating to
landscape (or the fullscreen button) hands the same video to the main
player, resuming from the same position.

Branch `feed-section` (stacked on animation-suite / PR #6). New package
`ui/feed`.

## Feed tab

- New bottom-nav item (after Search) + `navigation_feed` destination;
  MainActivity's nav-visibility list includes it. The watch page is not
  in the list, so the nav bar hides there.
- `FeedViewModel` builds the pool from **every provider with a main
  page**: page 1 per provider, up to 10 items each, interleaved
  round-robin, deduplicated by URL — no categories anywhere. Pull to
  refresh rebuilds (and reshuffles).
- Cards (`feed_card.xml`, shared with the watch page): big portrait
  poster (2:3, 62% width), clean title (8.17 cleaner), provider • type
  line, heart on the side, press-shrink from 8.16.

## Likes = taste

`FeedLikes` stores liked items (url, provider, type) in preferences.
When likes exist, the builder weaves items sharing a liked type or
provider into every third slot, so the feed visibly leans toward what
was liked. Hearts persist across launches.

## Watch page + custom player

- `FeedWatchFragment`: portrait-locked page; 16:9 player box on top,
  title/meta under it, "Up next" (the rest of the pool, same cards)
  below. Tapping an up-next card swaps the video in place.
- Direct play, no details page: `APIRepository.load(url)` →
  ResultEpisodes built exactly as ResultViewModel2 builds them (Movie /
  Live / TvSeries / Anime branches, same id formulas) →
  `RepoLinkGenerator.generateLinks` collects links + subtitles
  headlessly → best non-torrent link plays in a plain media3 ExoPlayer
  with custom controls: centre play/pause, red seekbar (8.15 style),
  time labels, sources picker (when >1 link), fullscreen handoff.
- Positions are saved via `DataStoreHelper.setViewPos` under the same
  episode ids the main player uses, and resumed via `getRealPosition()` —
  so the main player (and the details page) continue where the feed
  stopped, and vice versa.
- **Landscape handoff**: an OrientationEventListener watches for a
  sustained landscape tilt (~0.7 s) while playing; the fullscreen button
  does the same manually. Both save the position and open the standard
  player with the same generator. Returning replays the item in the
  feed player, resuming from the saved position.
- Failures land on an error box with **Open details** (the classic page)
  and **Retry** — the feed never dead-ends.

Files: new `ui/feed/{FeedFragment,FeedWatchFragment,FeedViewModel,
FeedAdapter,FeedLikes}.kt`, `res/layout/{fragment_feed,feed_card,
fragment_feed_watch}.xml`, `res/drawable/feed_{center_button,controls_bar}_bg.xml`;
edited `res/navigation/mobile_navigation.xml`, `res/menu/bottom_nav_menu.xml`,
`MainActivity.kt`, `res/values/strings.xml`.
