# Phase 8.70 — bridge sync isolation + feed player seek wedge

Branch: `nuvio-plugins` (PR #10). Ships together with the 8.68 fix and
8.69 (grouped source list) — one build, per his batching rule.

## 1. "Cinemeta ON হলে Nuvio provider দেখায় না" — registration hardening

At app start both bridge syncs ran back-to-back inside one `ioSafe`
block in MainActivity: `StremioProviders.sync()` then
`NuvioProviders.sync()`. Any exception in the Stremio sync aborted the
block, so the Nuvio provider never registered for that session — and
inside the Stremio sync itself, one failing addon registration took the
remaining addons down with it.

Fixes:
- MainActivity: each sync now runs in its own `runCatching` — the two
  bridges can no longer starve each other.
- `StremioProviders.sync`: each addon's registration is individually
  guarded; one bad addon skips only itself.

## 2. Feed player gets the main player's seek wedge (his request)

The feed watch screen's double-tap seek used its own pill badges
(`feed_watch_skip_back/forward`). They are replaced by the main player's
YouTube-style wedge from 8.62 — same half-disc (`seek_wedge_bg`), double
triangles, fade timings (130 ms in, out at 620 ms, token-guarded), and
the same "N seconds" label. Repeated taps inside the burst window
accumulate the displayed seconds (10/20/30…), each tap still seeking
10 s. Old badge views and their hide-runnable are removed.
