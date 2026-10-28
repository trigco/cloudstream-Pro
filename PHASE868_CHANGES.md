# Phase 8.68 — repo add defaults, scraper validity check, browser UA for fetches

Branch: `nuvio-plugins` (PR #10, stacked on `stremio-bridge`).

Context: the 8.67 device verdict was a breakthrough — video PLAYS from
Nuvio scrapers (Kisskh played a Stremio-catalog title AND Nuvio titles;
Inception merged Kisskh 1 + 4KHDHub 7 + DahmerMovies 23 + Streamflix 4
streams). His two requests, plus one question answered by a code change:

## 1. New repositories no longer switch every scraper ON (his fix request)

`NuvioRepository.addRepo` now seeds a brand-new repo with ALL its scraper
ids in `disabledScrapers` — everything starts OFF and the user enables only
what he wants (his own curation of the Michat88 repo was Kisskh-only, done
by hand). Re-adding an existing repo (refresh) keeps his choices. The
add-toast now says so (`nuvio_repo_added_off`).

## 2. Scraper validity check (his request: "যেইগুলো ভ্যালিড চেক করার ব্যবস্থা")

- `NuvioEngine.probe()` + `Mode.PROBE`: evaluates polyfill + scraper and
  reports whether `getStreams` / `onSettings` exist — a pure load test, no
  stream fetching. Syntax errors (e.g. the Myflixer scraper's unsupported
  `#private` names) surface as the error text.
- `NuvioValidity` (in NuvioRepository.kt, prefs `nuvio_validity_v1`):
  per-scraper probe result + last real fetch outcome. `resolve()` records
  the last outcome ("N streams" / "error (...)") after every play attempt.
- UI: each repo header gains a ✓ check button (`item_repo_check`) running
  the probe over the whole repo with a progress dialog and a summary toast;
  each scraper row gains a status line — "✓ loads · has settings",
  "✗ SyntaxError: ...", "last fetch: 23 streams" — so working and broken
  scrapers are visible at a glance.

## 3. 403s: not CORS — a client-identity problem, partly ours

He asked whether the 403s are a cross-origin problem. They are not: CORS is
a browser/WebView policy, and scraper fetches run through the app's native
HTTP client, which can set any headers. A 403 is the source site refusing
this client. One concrete cause was ours: **the bridge sent no default
User-Agent**, so requests went out with the HTTP library's plain UA, which
many source sites reject on sight. SkyStream's engine sets a browser UA by
default (nuvio_engine.dart:698). The bridge now does the same
(`NuvioHostBridge.DEFAULT_USER_AGENT`, Chrome 124 desktop string — the same
value SkyStream uses) whenever the scraper does not set its own UA.
Remaining 403s are per-site IP/fingerprint/geo rules, which no client-side
change can guarantee against.

Cross-service state (his other question): Stremio addons already supply
subtitles for any title (dedicated subtitles fan-out incl. OpenSubtitles),
Nuvio scrapers supply streams for Stremio-catalog titles, TMDB/Cinemeta
supply descriptions — services stay active side by side and enrich each
other at play/details time.

## Files
- nuvio/NuvioEngine.kt — PROBE mode + probe() + default browser UA.
- nuvio/NuvioRepository.kt — add-defaults-off + NuvioValidity store.
- nuvio/NuvioStreamService.kt — record last fetch outcome per scraper.
- ui/settings/nuvio/NuvioPluginsFragment.kt — check button wiring,
  checkRepo(), row status line, add-toast.
- res/layout/item_nuvio_repo.xml — check button.
- res/layout/item_nuvio_scraper.xml — status line.
- res/values/strings.xml — check/added-off strings.
