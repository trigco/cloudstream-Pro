# Phase 8.93 — Rich Feed Info sheet + in-app Actor filmography

His order (2026-10-30 07:49–07:54): the Feed's Info should be a
proper details sheet like the reference app's "More details" —
cast, description and trailer in one sheet — and tapping a cast
member should open real in-app details (photo + filmography),
not a Wikipedia search, which he disliked. Asked whether it can
be done FREE, without TMDB: yes — TVmaze + Wikidata, both
keyless. Approved with "oky" 07:54.

## A. Feed Info sheet (new `ui/feed/FeedInfoSheet.kt`)

The watch screen's ⓘ Info pill now opens a standard bottom
sheet (SheetChrome chrome, AppBottomSheetDialogTheme) instead of
expanding the plot inline under the title (the inline storyline
TextView is removed from `fragment_feed_watch.xml`).

- **Data:** if the player already loaded this item's page
  (`FeedPlayer.currentPage` for the current URL) it is used
  instantly; otherwise the page is fetched through the player's
  own load path (`APIHolder.getApiFromNameNull` +
  `APIRepository(api).load(url)`) on Dispatchers.IO with a
  loading row in the sheet.
- **Content:** header (poster + meta line: provider • type •
  ★ score • year • duration • content rating), genre line from
  the load response's tags (max 6), subtitle count — only from
  `FeedPlayer.subs` for the item the player actually loaded,
  never invented — **Description** (plot, or a muted "No
  description available"), **Starring** cast grid (3 columns,
  photo + name + character `roleString`, capped at 18), actions:
  **Open details** (secondary; the existing `loadSearchResult`
  path via the pool entry) and **Play** (red primary; resumes
  the current item or plays the sheet's item).
- **Trailer finding:** `LoadResponse` DOES carry trailers —
  `var trailers: MutableList<TrailerData>` (library MainAPI.kt),
  each with `extractorUrl`/`referer`/`raw`/`headers`. So the
  Trailer button appears exactly when the loaded page has at
  least one trailer. Tapping it pauses the feed video behind
  the sheet (never auto-resumed), extracts the link with the
  same `loadExtractor` flow as `ResultViewModel2.getTrailers`
  (raw links fall back to a direct INFER_TYPE link), and plays
  it in a small ExoPlayer inside the sheet (OkHttp data source
  with the link's headers/referer). Player is released on
  dismiss; all sheet coroutines are cancelled on dismiss.

## B. Actor filmography (new `ui/result/ActorFilmography.kt`)

Shared resolver used by the actor page; cast taps in the new
Feed sheet AND on the Details page both land on the existing
in-app `ActorFragment` (Phase 5B), which now grows a
**Filmography** section (header + spinner + 3-column grid +
muted empty line) between the overview and the search button.

**Finding on the "Wikipedia" complaint:** the Details cast tap
already opened the in-app actor page — but the page's only
content was a Wikipedia bio extract plus a search button, so
it read as "cast goes to Wikipedia". The bio fetch stays (it is
in-app text, not a redirect); the filmography is the new
substance. In the Feed there was no cast surface at all before
this phase.

Resolution flow (`ActorFilmography.fetch`, all keyless, app
HTTP client, off the main thread):

1. **TVmaze** — `GET /search/people?q=<name>` (top 5). Per
   candidate, `GET /people/{id}/castcredits`: the `_embed=show`
   form is ignored by the API today (verified live 2026-10-30),
   but every credit's `_links` carries the show name + character
   name, and the show id (from the href) feeds one
   `GET /shows/{id}` per credit (cap 24, parallel `amap`) for
   poster + premiere year. Talk-show self appearances (billed
   under the actor's own name) are dropped.
2. **Wikidata** — `GET www.wikidata.org/w/api.php?action=
   wbsearchentities` (top 5), then SPARQL at
   `query.wikidata.org/sparql` (User-Agent header set):
   works with `wdt:P161` (cast member) = the person, year from
   `P577`/`P580`, IMDb id from `P345`, English labels; deduped
   by title+year, QID-only labels dropped. Verified live with
   Omar Sy (Intouchables etc., 80 rows before dedupe).
3. **Verification:** the source title (the work the user tapped
   the actor from) is cleaned and compared against each
   candidate's works; a match verifies that candidate. TVmaze
   tries candidates in order until one verifies (fallback:
   first with credits). Wikidata accepts a verified candidate,
   or — when TVmaze already verified the person or an exact
   label match exists — that candidate as a supplement.
4. **Merge:** keyed by cleaned title + year (a year-less TVmaze
   entry merges with its dated Wikidata twin); TVmaze keeps
   poster + character, Wikidata fills year/IMDb and adds the
   movies. Sorted year-desc, capped at 48. Family Mode drops
   adult-titled entries via the existing `FamilyMode` helpers.
5. **Cache:** per-actor JSON in SharedPreferences
   `actor_filmo_v1` (key = cleaned name); reopens are instant.
6. **Fallbacks:** no person/works anywhere → the grid section
   shows "No filmography found" and the page's existing
   actor-name search button remains the way forward; tapping a
   filmography entry opens the main Search tab with that title
   as the query (`SearchFragment.SEARCH_QUERY`, which the
   search screen auto-submits — no deviation needed here).

## C. Wiring changes

- `FeedWatchFragment`: Info pill is always visible on the watch
  screen (the sheet loads missing data itself); its click opens
  `FeedInfoSheet`. The storyline expand/collapse code and the
  `titleFullHeight` re-measure inside it are removed.
- `ActorFragment`: starts the filmography fetch alongside the
  existing Wikipedia overview fetch; a TVmaze/Wikidata person
  photo upgrades the header photo only when the provider gave
  none (same rule as the Wikipedia upgrade).
- Layouts: filmography block added to `fragment_actor.xml`;
  new shared grid-cell layout `filmo_item.xml` (used by both
  the filmography grid and the sheet's cast grid).
- Strings (all new keys, duplicate-checked): `feed_info_
  description`, `feed_info_starring`, `feed_info_no_
  description`, `feed_info_subtitles`, `feed_info_open_
  details`, `feed_info_play`, `feed_info_load_failed`,
  `actor_filmography`, `actor_filmography_none`. The trailer
  button reuses the existing `play_trailer_button`.

## Deviations / limits

- The actor page keeps its Phase 5B Wikipedia **bio text**
  (in-app, best-effort); what was replaced is the absence of
  real actor content — filmography is now in-app data, and no
  cast tap anywhere leaves the app or opens a web search.
- TVmaze covers TV only and its per-person credit lists can be
  short for film actors; Wikidata coverage depends on the
  person's notability — regional/less-documented actors may
  legitimately return little. The in-app name search remains
  as the fallback, as agreed.
- Subtitle count is shown only for the currently-loaded item
  (from the player's loaded subtitle list); LoadResponse has
  no subtitle-count field, so no count is fabricated for
  freshly fetched pages.
- Phone only; TV layouts and behaviour untouched.
