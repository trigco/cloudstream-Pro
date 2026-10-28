# Phase 8.66 — Nuvio device-feedback round

Driven by the 8.64 device test (2026-10-27): repo add + scraper list
worked, but (1) every scraper settings popup failed with "Could not
load this scraper's settings", (2) the plugin list lacked the main
Extensions screen's card/rounded-logo treatment and got very long,
(3) the user asked how a video is actually watched through a scraper
(e.g. MovieBox).

## 1. Settings failure — root cause found and proven offline

The polyfill shipped with an unsubstituted angle-bracket placeholder
in two places (the `TMDB_API_KEY` global and `process.env`), which
`NuvioPolyfill.build()` never replaced. The whole polyfill therefore
threw a JS **SyntaxError on evaluation**, so every engine run —
settings AND streams — died before any scraper code executed.

Fix: both spots now assign the substituted `TMDBK` variable.
Verified by extracting the shipped polyfill and running it in Node
with stub host bindings: `node --check` passes, and all five
settings-bearing scrapers from the user's repo (Yoru's Repo)
reproduce their real layouts through the exact SETTINGS_CALL
harness — AnimePahe (domain select), DahmerMovies (quality toggles),
ShowBox (token form), HiAnime (sub/dub select); NetMirror and
MovieBox correctly resolve to an empty layout (NetMirror's manifest
claims hasSettings but its JS exports no onSettings — the app now
shows "This scraper has no settings", matching SkyStream).

Second engine bug found by a require-audit of that repo: most
scrapers `require('cheerio-without-node-native')` (23 uses) — the
polyfill's require() now aliases it to the cheerio shim.

## 2. Nuvio Plugins page — card UI + collapsible repos

- Repo headers and scraper rows are now cards in the main
  Extensions language (`bg_repository_card`, 20dp radius, margins).
- Scraper logos load with Coil `CircleCropTransformation` (the
  circle masking the Extensions list uses), with the extension
  icon as the error fallback.
- Tapping a repo header (or its new chevron) collapses/expands its
  scraper rows; the state persists per repo
  (`NuvioRepository.collapsedRepos/setRepoCollapsed`,
  prefs key `nuvio_collapsed_v1`).

## 3. "How do I watch a video?" — the Nuvio provider

New `nuvio/NuvioProvider.kt`: a first-class MainAPI named **Nuvio**.

- Catalogue/search/details come from **TMDB** (the user's own key
  from Settings → Account): trending + popular home rows, movie/TV
  search, full details, per-season episode lists.
- Playing any title/episode runs `NuvioStreamService.resolve` with
  the title's TMDB id directly — every enabled scraper (MovieBox,
  4KHDHub, …) contributes streams, merged into one source list,
  subtitles included. This is the same flow as the Nuvio app.
- `NuvioProviders.sync` registers the provider in APIHolder only
  while it can work (TMDB key set AND ≥1 enabled scraper) — synced
  from MainActivity startup and from the Plugins page on changes.
- The Stremio-bridge path keeps working too: Nuvio streams still
  merge into bridge titles' source lists.

Limits unchanged: torrent (infoHash) results are still skipped
(torrent plan is a separate track), and everything needs the TMDB
key — without it scrapers cannot resolve titles, by design.
