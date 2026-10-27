# Phase 8.64 — Nuvio plugins (addon bridge step 2)

JS scraper plugins in the Nuvio format, hosted natively. Stacked on
PR #9 (stremio-bridge): Nuvio streams merge into the Stremio bridge
provider's `loadLinks` fan-out.

## How it works

- A Nuvio **repository** is a JSON manifest listing scrapers
  (id, name, version, filename, supportedTypes, hasSettings, …).
  Each scraper is a JS file exporting
  `getStreams(tmdbId, mediaType, season, episode)` (mediaType
  `movie`|`tv`) and returning Stremio-shaped link objects.
- Titles reach scrapers by **numeric TMDB id**: the bridge payload's
  IMDb id is converted via TMDB `/find` using the TMDB API key from
  Settings → Account (the same key MetadataResolver uses). Series
  episode ids (`tt…:S:E`) pass season/episode through.
- Every enabled scraper runs in parallel (max 4 at once, 60s budget
  each, 10-minute result cache). Direct-URL results merge with the
  addon streams; `infoHash`-only results are still skipped (torrent
  playback is a later step).

## Engine: dokar3 quickjs-kt (io.github.dokar3:quickjs-kt-android 1.0.15)

Chosen after verifying the alternatives against the actual artifacts:

- app.cash.quickjs 0.9.2 (and the taoweiji binding): their JNI never
  calls `JS_ExecutePendingJob`, so promise continuations of
  async/await scrapers would never run.
- Rhino 1.9.2: has `processMicrotasks()` but its parser has no
  `async function` or `class` tokens at all (checked Token.java).
- Javet (V8): ~100MB of native code per ABI — not shippable.
- quickjs-kt: coroutine-native, pumps the job queue inside
  `evaluate`, offers sync `FunctionBinding` and async
  `AsyncFunctionBinding` host functions, ~1MB native lib per ABI,
  ES2023 QuickJS underneath.

## Host environment (NuvioPolyfill, ~1300 lines of JS)

Modelled on SkyStream's verified Nuvio host surface:
`SCRAPER_SETTINGS` (+ SETTINGS/settings/NUVIO_SETTINGS aliases),
`TMDB_API_KEY` (global + `process.env`), fetch/XHR on a native async
binding (OkHttp, per-run cookie jar, 15MB cap), timers on a native
sleep binding, URL/URLSearchParams, Buffer, TextEncoder/Decoder,
atob/btoa, localStorage persisted per scraper, WebCrypto subset
(digest/getRandomValues/randomUUID), a CryptoJS-compatible shim
backed by native MD5/SHA/AES (CBC/ECB, Pkcs7/NoPadding/ZeroPadding,
OpenSSL salted format + EVP_BytesToKey), a minimal axios, `require()`
for cheerio/crypto-js/axios/node-fetch/buffer/url/querystring/util/
events, and a minimal cheerio (own HTML parser + selector engine:
tag/.class/#id/[attr]/descendant/child/:first-child/:nth-child…,
text/html/attr/find/children/parent/closest/each/map…). Cheerio and
CryptoJS fidelity are the known limits — exotic scrapers may fail
individually; failures are per-scraper and never break playback.

## Files

- `nuvio/NuvioModels.kt` — manifest/scraper/settings-field/stream
  models + tolerant parsers (loose stream shapes, embedded url
  objects, proxyHeaders fallback, zero-width title cleanup).
- `nuvio/NuvioRepository.kt` — installed repos + enabled state
  (SharedPreferences), scraper code store (files under
  filesDir/nuvio_code), per-scraper settings + localStorage.
- `nuvio/NuvioPolyfill.kt` — the JS environment above.
- `nuvio/NuvioEngine.kt` — host bridge (crypto/storage/log/result,
  fetch) + the runner (polyfill → wrapped scraper → call harness,
  result via the harness return value or the result binding).
- `nuvio/NuvioStreamService.kt` — TMDB id normalization + parallel
  scraper orchestration + cache + StremioStream mapping.
- `stremio/StremioAddonProvider.kt` — loadLinks emission refactored
  into one local emit function; Nuvio fan-out appended.
- `ui/settings/nuvio/NuvioPluginsFragment.kt` + layouts — Settings →
  Nuvio Plugins page (sibling of Stremio Addons): add/refresh/remove
  repositories, per-scraper toggles, settings forms generated from
  the scraper's own `onSettings()` layout (text/number/password/
  toggle/select/header/info).
- Gradle: quickjs-kt-android 1.0.15 via the version catalog.

## Limits kept from step 1

- No bundled repositories or scrapers — the user installs their own
  repository links.
- Torrent streams (infoHash) are not played yet.
- Nuvio streams attach to bridge (Stremio addon) titles; regular
  provider titles are not routed through scrapers in this step.

## Fix round (run #201 failed, fixed same day)

Run #201 (head 36b24f09) failed with 2 failure classes:

1. `mergePrereleaseDebugNativeLibs`: `libquickjs.so` collision —
   `app.cash.zipline:zipline-android` bundles the same filename as
   quickjs-kt. Nothing in this tree references zipline (repo-wide
   grep: only the dependency line), so `implementation(libs.zipline)`
   was removed from app/build.gradle.kts (catalog entry kept, with a
   comment on how to restore it). Dokar loads `System.loadLibrary(
   "quickjs")`, so the filename itself cannot be renamed away.
2. Kotlin compile errors, all from the dokar builder call in
   `NuvioEngine.run`: the `quickJs(dispatcher) {}` block is a
   `QuickJs.() -> T` RECEIVER lambda — passing `{ qjs -> ... }`
   matched neither overload, which cascaded into unresolved
   defineBinding/evaluate references and suspend-context errors.
   Verified against the dokar3/quickjs-kt v1.0.15 source (the builder
   is inline, so suspend evaluate/await calls splice into the calling
   coroutine). Block rewritten in receiver form.
3. `StremioAddonProvider.loadLinks`: the 8.64 refactor extracted the
   emit loop into a plain local `fun emitStream`, which can no longer
   call the suspend builders `newSubtitleFile`/`newExtractorLink`.
   Marked `suspend fun`.

This push also carries the 8.65 device-feedback fixes (status-bar
inset on both settings pages, listing-page manifest resolver,
/configure URL handling) so the test APK contains everything.
