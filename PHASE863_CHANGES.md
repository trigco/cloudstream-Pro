# Phase 8.63 — Stremio addon bridge, step 1

Branch `stremio-bridge`, stacked on `player-mpv-ui` (PR #8 head) so the
test APK contains everything current; the PR targets `player-mpv-ui`
and retargets to master once PR #8 merges.

## What the user gets

- Settings gains a separate **Stremio Addons** page (his pick over
  putting it inside Extensions). Paste a manifest link -> the addon is
  fetched, validated and installed; the page lists installed addons
  with logo, name, description, an enable switch and remove.
  **Cinemeta is built in** (disable-able, not removable) because
  catalog-only addons never implement meta — it is the details/info
  fallback, exactly like in Stremio itself.
- Each installed addon registers as a normal provider (same APIHolder
  calls plugins use), so it appears in the provider picker, Home and
  Search under its own manifest name.
- Catalogs -> Home rows (with `skip` pagination where the catalog
  declares it) and Search (catalogs declaring the `search` extra).
- Meta -> details page (poster, backdrop, plot, year, genres, IMDb
  score, runtime, episodes from `videos[]`).
- Streams -> **fan-out to every enabled addon** that serves streams
  for the title's id (the Stremio model: one addon catalogs, another
  streams). Direct URLs only in step 1; `behaviorHints.proxyHeaders`
  request headers are passed to the player (the #1 cause of addon
  403s when dropped). Quality parsed from name/title/filename.
- Subtitles: embedded per-stream subtitles + the dedicated
  `subtitles` resource, fanned out the same way.
- Family Mode: an addon whose manifest declares `adult` is not
  registered at all while Family Mode is on.

## Protocol details (verified against SkyStream's engine)

- URL normalization: stremio:// -> https, append /manifest.json,
  strip fragment, **query string preserved verbatim** (addon config
  lives there).
- Resource URLs: {base}/{resource}/{type}/{url-encoded id}.json,
  extras as a /k=v&k=v segment, original query re-appended at the end.
- Tolerant parsing everywhere (resources as strings or objects, type
  aliases series/tv/show and movie/film, `other` catch-all,
  legacy extraSupported/extraRequired, under-declared manifests are
  still asked for meta/stream/subtitles).
- Client: TTL cache (manifest 6h, catalog 15m, meta 45m, stream 5m,
  subtitles 30m), in-flight dedupe, ~45s stream timeout.
- Persistence: SharedPreferences JSON (`stremio_addons_v1`), parsed
  manifest stored per addon so providers register instantly at app
  start (MainActivity calls StremioProviders.sync after initAll).
- Bridge items carry a JSON payload as their url ({a,t,i}); load()
  retries the sibling type and falls back to Cinemeta meta for
  IMDb ids.

## Step 2 (not in this build)

Torrent (infoHash) playback via debrid/torrent engine, YouTube and
externalUrl streams, bingeGroup auto-next grouping, Nuvio JS plugins
(QuickJS host) — as sequenced in ADDON_IMPLEMENTATION_CHECK.md.

## Boundaries kept

The bridge ships no addons beyond Cinemeta and no curated lists;
users install their own addon URLs, like repository URLs today.
