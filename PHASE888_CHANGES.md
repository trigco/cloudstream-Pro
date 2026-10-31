# Phase 8.88 — Metadata-powered Feed categories

Goal (his order): organize Feed content with REAL categories instead of
only title keywords — K-Drama, C-Drama and more — using free metadata
sources. Plus a folded-in device finding: the Filters sheet's Genre tab
showed only "All".

## Files

Edited:
- `app/src/main/java/com/lagradost/cloudstream3/ui/search/MetadataResolver.kt`
- `app/src/main/java/com/lagradost/cloudstream3/ui/feed/FeedFragment.kt`
- `app/src/main/java/com/lagradost/cloudstream3/ui/feed/FeedAdapter.kt`
- `app/src/main/res/values/strings.xml` (one new string: `feed_category_count`)

New:
- `app/src/main/java/com/lagradost/cloudstream3/ui/feed/FeedCategories.kt`
- `app/src/main/java/com/lagradost/cloudstream3/ui/feed/FeedCategoriesAdapter.kt`
- `app/src/main/res/layout/feed_categories_header.xml`

## PART 1 — Resolver extension (MetadataResolver)

- New `Meta(genres, language, country, anime)` record per resolved item.
  Persisted in the SAME prefs file (`universal_ratings_v1`) under
  `"meta:" + <rating cache key>` as compact JSON
  (`{"g":"Action|Drama","l":"ko","c":"KR","a":1}`). The rating value
  itself is byte-for-byte untouched, so every existing consumer
  (Search cards, Home adapters, details page) behaves exactly as
  before; old caches simply lack `meta:` rows until an item
  re-resolves. Misses are session-only, like rating misses.
- No parallel network stack: metadata resolution reuses the resolver's
  scope, its `Semaphore(3)`, `cleanPosterTitle`/`normalize`/
  `titlesMatch`, and the `app` HTTP client.
- The rating pipeline now returns `Pair<rating, Meta?>` internally
  (`lookup`/`tmdb`/`tmdbOnce`/`omdb`/`omdbOnce`): the TMDB and OMDb
  passes HARVEST genres/language/country from the first search
  candidate that passes the existing title + year gates, out of the
  very responses they already fetch (TMDB `genre_ids` mapped through
  the standard movie/TV genre tables hardcoded in the resolver;
  OMDb `Genre`/`Language`/`Country` full names mapped to ISO via
  small tables). The vote gate still judges only the rating. Zero
  extra round-trips on the rating path; `resolve()` stores harvested
  meta next to the rating.
- New `resolveMeta(context, response)` for everything the rating path
  skips (provider score present, rating cached, or NO key at all).
  Source order (only existing key paths or keyless endpoints — no new
  keys anywhere):
  1. TMDB (user key set): reuses the rating pipeline's own `tmdb()`
     search + harvest, both kinds (tv then movie, strict then loose).
  2. TVmaze (NO key, TV types): `api.tvmaze.com/search/shows` → first
     `titlesMatch` hit's `genres[]`, `language` (full name → ISO),
     `network/webChannel.country.code`; an "Anime" genre or Animation
     type flags anime.
  3. AniList (NO key, Anime/OVA types): GraphQL form-post in the same
     shape as the app's own AniList sync provider → `genres` +
     `countryOfOrigin`; language derived from country (JP→ja, CN→zh,
     KR→ko).
  4. OMDb only via the user's existing `omdb_api_key` pref.
- New meta listeners (`addMetaListener`/`removeMetaListener`, keyed by
  the rating cache key) fire on the main thread when meta lands;
  `metaCached` / `metaCachedFor` read memory → prefs like ratings do.

## PART 2 — Category derivation (FeedCategories.kt)

Single shared derivation `categoriesOf(type, meta)`; the type is the
details-page type where TitleResolver learned one (the same preference
the cards use), else the listing's declared type:

- K-Drama: ko/KR + series · C-Drama: zh or CN/TW/HK + series ·
  J-Drama: ja/JP + series + NOT anime · Thai Drama: th/TH + series ·
  Turkish Dizi: tr/TR + series · Anime: Anime/OVA type, an AniList
  match, or ja + Animation genre · Bollywood: hi/IN + movie ·
  Hollywood: en + US/GB (movie or series) · plus every resolved
  genre name as its own category. Items can carry several;
  unresolved items carry none.
- `canonicalGenres(meta)` maps resolved genres onto the 8.87 chip
  vocabulary (Science Fiction / Science-Fiction → Sci-Fi;
  Action & Adventure → Action + Adventure; Sci-Fi & Fantasy →
  Sci-Fi + Fantasy; War & Politics → War; Kids → Family;
  Romantic → Romance).

## PART 3 — Feed UI

- **Categories tile row**: new ConcatAdapter member
  (`FeedCategoriesAdapter` + `feed_categories_header.xml`) between the
  hero header and the cards — a "Categories" heading in the page's
  section style (reuses the existing `home_custom_categories_row`
  string) over a horizontal scroll of tiles. Tiles = smart categories
  with ≥3 items in the current pool (the selected one stays visible
  from 1 so it can always be tapped off); each tile shows name +
  item count on a plain dark surface (the brief's simple option — no
  poster art). Tap toggles `filterCategory`, applied through the same
  `applyFeedFilters` path as chips/sheet (it also tints the filter
  button red and hides the hero, like other active filters; the
  empty-state reset clears it via `clearFilters`). Hidden while
  searching, like the hero. The grid span lookup now spans both
  header adapters' items full-width.
- **Refresh wiring**: when a pool lands, the fragment kicks
  `resolveMeta` for its first 60 entries; deeper items resolve on
  card bind (FeedAdapter, next to the existing rating resolve, both
  self-guarded). Meta landings debounce (900 ms, wait-for-idle — the
  title-resolution pattern) into: tile-row recompute, chip-alpha
  recompute, and a filter re-apply when a genre/category filter is
  active. The feed never blocks on resolution.
- **Union genre rule** (`genreMatchesEntry`): an item matches a genre
  chip / Filters-sheet genre when its canonical RESOLVED genres
  contain it (checked first — real data) OR the 8.87 title-keyword
  match hits (fallback). Used by the chip row (incl. its dimming),
  the sheet's Genre tab, and the genre filter itself.
- **Filters sheet Genre tab fix (device finding)**: the tab used to
  list only keyword-"present" genres — since real titles almost
  never contain genre words, it showed only "All". It now lists the
  FULL 18-genre vocabulary like the chips, with zero-match entries
  dimmed to 0.45 alpha (the Phase 8.36 rule). Presence is computed
  with the union rule above (resolved metadata first). The sheet's
  style/colours are untouched, per his praise of them.
- Family Mode: categories, counts and filtering all run over the
  fragment pool, which FeedViewModel already Family-Mode/hidden
  filtered — nothing new can surface through a tile.

## Deviations

- The AniList language is derived from `countryOfOrigin` (AniList
  exposes no language field); a non-JP/CN/KR origin leaves language
  null, in which case the country-based rules (and the anime flag)
  still apply.
- Tile art is the plain-dark option the brief allowed; poster-collage
  tiles were not attempted.
- The rating flow's `resolve()` still early-returns for items with a
  provider score / a cached rating, so their metadata comes from
  `resolveMeta` (one search through the same pipeline) rather than
  the harvest — by design, to keep rating behaviour untouched.
- `resolveMeta`'s TMDB step inherits the rating search's
  strict→loose, tv→movie passes, so a keyed meta lookup for an
  uncached item can cost up to the same 4 search calls a rating
  lookup would — bounded by the shared semaphore and done at most
  once per title (persistent cache + session misses).

## Coverage notes

- With a TMDB key: near-full coverage of matched items — genres,
  language and origin country ride on search results, so K-Drama /
  C-Drama / Bollywood / Hollywood all resolve from one source.
- Keyless: series are covered by TVmaze (strong for international
  TV incl. K/C-dramas; genres + language + country), anime by
  AniList (+ TVmaze). Keyless MOVIES have no source in the chain —
  they stay keyword-only until a key is set or OMDb is configured;
  that is the honest limit of the free/keyless set the user picked.
- TVmaze language is a full name ("Korean"); obscure language names
  outside the ~45-entry table yield a null language (country rules
  still fire). TVmaze/AniList low-confidence (no title match) → no
  data, never a guess.
- Everything is cached persistently after first resolution, so the
  tile row fills in progressively on first use and is instant after.

## Verification

- Kotlin delimiter balance exact on all five touched/new .kt files
  (MetadataResolver 147/147 braces, FeedFragment 241/241, etc.).
- `feed_categories_header.xml` + `strings.xml` parse as XML; no
  duplicate string keys (one addition only).
- All new references resolve: `FeedCategoriesHeaderBinding` (from the
  new layout), `@string/home_custom_categories_row` (pre-existing),
  `@string/feed_category_count` (added), `@drawable/feed_search_bg` /
  `@drawable/bg_feed_filter_selected` (pre-existing sheet drawables).
- No `android:tint` added; all new LinearLayouts declare
  `android:orientation`; the AniList query is a regular string with
  `\$` escapes (no `${` in any raw string); no code inserted between
  annotations and classes.
- Search-page consumers untouched: public resolver API
  (`cached`, `cachedFor`, `resolve`, `keyFor`, `yearOf`, listeners)
  keeps signatures and behaviour; the cache schema change is purely
  additive (`meta:` keys alongside rating strings).
