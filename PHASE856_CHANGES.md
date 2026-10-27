# Phase 8.56 — his full fix/feature list on top of the 8.53 state

## Universal ratings (IMDb + TMDB)
- New `ui/search/MetadataResolver.kt` (TitleResolver pattern):
  when the provider ships no score, resolve by cleaned title +
  year — TMDB first (search movie/tv, vote_average, >=10
  votes, title must match normalised, year within 1), then
  IMDb via OMDb (imdbRating). Conservative: no confident match
  = no rating shown. Hits cached persistently
  (universal_ratings_v1); misses are session-only.
- Keys are the user's own, entered on-device: Settings →
  Account gains a "Ratings (Universal)" category with TMDB
  API Key and IMDb API Key (OMDb) rows (SettingsAccount
  editor dialog; stored in default prefs; never in the repo).
- Surfaces filled: Home/Search cards (SearchResultBuilder
  rating view), Feed card meta line ("Provider • Type •
  ★ 8.4 • DUB"), details header (ResultViewModel2 ratingText
  fallback; rating_format is now "★ %s/10").

## Dub/Sub label
- Feed card meta appends DUB / SUB / DUB+SUB when the item
  declares a dub status (AnimeSearchResponse carries it).
  (Home/search cards already had their dub/sub views.)

## Details title bar (his MovieBox reference)
- Title and meta line left-aligned (were centered).
- Pill row under the meta: **Add to list** (delegates to the
  watch-status bookmark FAB), **Share** (delegates to the
  share action), **Download** (scrolls to the download
  button / episodes holder).

## Feed card gap
- Removed the title's minLines=2 — the reserved second line
  was the dead space between a short title ("DeSu7") and the
  provider line.

## Player loading animation
- Buffering state is re-evaluated on the 500ms progress tick
  and at the end of every playback-UI update: the spinner now
  shows during rebuffers, not only during initial extraction.

## Auto-hide reliability
- Feed header: height re-measured lazily — the one-time
  post{} measurement could stick at 0 and silently disable
  auto-hide forever.
- Watch controls: the scheduled hide only posted while the
  player already reported playing; the tick now force-hides
  controls that have been visible + playing for 3s.

## Speed
- Feed first build: homepage pages 1 and 2 load in parallel
  (were sequential).
- Custom-list hero: candidate detail loads run in parallel
  (amap) — was a sequential loop where one slow provider
  delayed the whole hero.

## Search empty-overlay
- The empty page no longer appears while a provider search
  is still running: local-zero + query >= 3 chars now shows
  the spinner; empty only when the merged result is empty.

## Scroll jitter (কাপাকাপি)
- Home auto-rotation now rotates only rows that are OFF
  screen (through the parent adapter) — visible rows no
  longer shuffle every 20s.
- Home master list: itemAnimator removed (change animations
  on rebinds shuddered).
- Feed title resolutions are queued and applied only when
  the grid is idle (same cure Home got in 8.41).
