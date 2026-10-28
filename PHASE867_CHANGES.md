# Phase 8.67 — Nuvio engine result fix (THE playback blocker) + Stremio page redesign + diagnostics

Branch: `nuvio-plugins` (PR #10, stacked on `stremio-bridge`).

## 1. ROOT CAUSE of "nothing plays / settings empty" — found and proven offline

The 8.66 device verdict: Nuvio catalogue browsing works (TMDB), but NO streams
from any scraper, and scraper settings forms come back empty. The engine,
polyfill, scrapers, bindings and async job pumping are all FINE — verified by
running the real dokar QuickJS (JVM build of quickjs-kt 1.0.15) with the app's
exact polyfill, bindings and call scripts against the real AnimePahe and
DahmerMovies scrapers (`~/workspace/cloudstream/jvm-test/`).

The break was in `NuvioEngine.run()`'s result selection. Both call scripts are
async IIFEs, so `evaluate<String>(callScript)` returns the **Promise object's
own string form** — the literal text `Promise { <state>: "fulfilled" }` —
never the resolved payload. That string is non-blank, so it won over
`bridge.resultJson` / `resultDeferred`, which held the CORRECT payload
delivered via `__nuvio_result`. `JSONObject("Promise {...}")` then failed →
`{"error":"Bad result payload"}` → settings layout empty, streams empty, for
every scraper, on every title. (Node testing passed because the harness read
the bridge payload directly; V8 was never the variable.)

Fix: prefer the bridge payload (`resultJson`, then the completed deferred);
use the eval text only when it actually parses as JSON (starts with `{`).
Comments in code record the mechanism so it is not reintroduced.

## 2. Visible diagnostics (failures are no longer silent)

`NuvioStreamService.resolve()` now records a per-scraper outcome
(`Name: count`, `Name: count (cached)`, or `Name: error (message)`) into the
new `NuvioDiagnostics` holder, and the Nuvio Plugins page shows it as a
"Last stream check · HH:mm · movie — …" line under the header after any
playback attempt. Engine error payloads now propagate their message instead of
being swallowed into an empty list; a failed code download is reported as
"code download failed".

## 3. Stremio Addons page redesign (his request)

Rows are now the same cards as the Nuvio/Extensions pages:
`bg_repository_card` background, 16dp side / 6dp vertical margins, 14dp
padding, 40dp logos with CircleCropTransformation and the extension-icon
error fallback. Name/description/error/switch/delete behaviour unchanged.

## Files
- nuvio/NuvioEngine.kt — result-selection fix.
- nuvio/NuvioStreamService.kt — outcome capture + `NuvioDiagnostics`.
- ui/settings/nuvio/NuvioPluginsFragment.kt — diagnostics line wiring.
- res/layout/fragment_nuvio_plugins.xml — diagnostics TextView.
- ui/settings/stremio/StremioAddonsFragment.kt — circle-crop logo loading.
- res/layout/item_stremio_addon.xml — card row.
