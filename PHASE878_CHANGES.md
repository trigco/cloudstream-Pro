# Phase 8.78 — SkyStream plugin bridge (S1 + S3 scope)

Third bridge after Stremio (8.63) and Nuvio (8.64): SkyStream's native
JS plugins (the `lib/core/extensions` format distributed by
sky-universe) now run in CloudStream Pro as full providers — home
sections, search, details and streams — on the same dokar QuickJS
engine, behind a persistent per-plugin context (the S2 spike's model).
Spec: ~/workspace/cloudstream/SKYSTREAM_BRIDGE_ANALYSIS.md. The page
ships empty: no default repository, the user adds his own (e.g. the
`universe` shortcode) and installs plugins himself.

## Created

- `app/src/main/java/com/lagradost/cloudstream3/sky/SkyModels.kt` — tolerant org.json parsers: mega-repo/repo docs, plugins.json entries, plugin.json manifests (packageName mandatory; categories/types/tvTypes and languages/language/lang aliases; `providers[]` sub-providers; settings schema subset toggle/select/toggleGroup/text/url with isBaseUrl detection), plus the runtime shapes (SkyItem/SkyEpisode/SkyStreamResult) returned by plugin JS.
- `app/src/main/java/com/lagradost/cloudstream3/sky/SkyRepository.kt` — prefs `sky_repos_v1`, `sky_installed_v1` (source repo URL, int version, sha256 of plugin.js), `sky_settings_v1`, `sky_storage_v1` (getPreference store namespaced packageName+subId, with settings-value and schema-default fallback), `sky_collapsed_v1`, `sky_sections_v1`; code store `filesDir/sky_code/<repoKey>/<packageName>/plugin.js + plugin.json`; `resolveRepoInput` (full URL + repo.json/mega_repo.json siblings, github.com/user/repo spelling → raw repo.json then mega_repo.json on main then master, shortcode → cutt.ly/sky-<code> then cutt.ly/<code> following redirects); `fetchRepoChain` (mega expands to child repos, each repo expands its pluginLists); `installPlugin` (.sky download, java.util.zip extraction with path-escape guard, plugin.json+plugin.js required, packageName verified against the listing, sha256 recorded); `removePlugin`; `updateAvailable` (int compare). Includes the `SkyHttp` OkHttp helper used for the chain.
- `app/src/main/java/com/lagradost/cloudstream3/sky/SkyPolyfill.kt` — the spike's SKY_POLYFILL ported byte-identically: console, timers, model-class bags, http_get/http_post/http_parallel, getPreference/setPreference, `_fetch`, and the `__sky_call` callback-envelope invoker with the Reflect.get export harvest.
- `app/src/main/java/com/lagradost/cloudstream3/sky/SkyEngine.kt` — `SkyHostBridge` (OkHttp modelled on NuvioHostBridge: per-session cookie jar, Chrome/124 default UA, `{status, body, headers}` lowercased response shape, 8 MB cap), `SkyPluginSession` (one persistent QuickJS context per plugin instance owned by a worker coroutine consuming a job channel; bindings + polyfill + manifest injection with effective baseUrl and providerId + plugin.js evaluate + the spike's sacrificial drain evaluate; invokes serialized by a per-plugin Mutex; envelope awaited inside the quickJs block; 60 s invoke timeout), and the `SkyEngine` registry (keyed packageName+subId, LRU eviction beyond 4 live contexts, context recreated when the effective baseUrl/providerId stamp changes, evict/retainOnly support).
- `app/src/main/java/com/lagradost/cloudstream3/sky/SkyPluginProvider.kt` — MainAPI per installed+enabled plugin (and per `providers[]` sub-provider, named "<Plugin> • <Sub>"): categories → supportedTypes; getHome → getMainPage sections (JS object order, hasNext=false, section names persisted for the synchronous mainPage getter, 5-minute in-memory home cache); search → search(); load() → load() mapped to newMovieLoadResponse/newTvSeriesLoadResponse (poster, banner→backgroundPosterUrl, logo, plot, year, Score.from(.,10), tags, cast→actors, trailers, recommendations, syncData, contentRating, duration) with episodes as opaque payload URLs and the movie rule (type movie, or the single synthetic S0E0 episode → movie response whose dataUrl is that episode's payload); loadLinks → loadStreams() → newExtractorLink (M3U8/DASH/VIDEO typing, quality from int height or text, headers with referer lifted) + newSubtitleFile subtitles; DRM streams (drmKid/drmKey/licenseUrl) skipped with a log line; Family Mode filters adult items; errors resolve to empty/false, never throw.
- `app/src/main/java/com/lagradost/cloudstream3/sky/SkyProviders.kt` — registry sync in the StremioProviders pattern (stale registrations removed, name-clash suffix " (Sky)", per-plugin runCatching, Family Mode keeps adult-flagged/adult-named plugins unregistered, dead engine contexts evicted via retainOnly) plus the `refreshSkyProviders(ctx)` helper.
- `app/src/main/java/com/lagradost/cloudstream3/ui/settings/sky/SkyPluginsFragment.kt` — Settings page mirroring NuvioPluginsFragment: repositories section (add by URL/github spelling/code dialog, refresh, remove with confirm, collapse groups), per-repo plugin lists (child-repo sub-headers for mega-repos; Install/Update/Remove buttons driven by installed state and int-version update check), installed-plugins section (icon, enable MaterialSwitch, remove), and a settings form dialog for manifest-schema plugins (toggle/select/toggleGroup/text/url; saving evicts the plugin's context so base-url changes recreate it).
- `app/src/main/res/layout/fragment_sky_plugins.xml` — page skeleton (back, title, add button, empty state, recycler).
- `app/src/main/res/layout/item_sky_repo.xml` — repository group header row (chevron, name, URL, error line, refresh, remove).
- `app/src/main/res/layout/item_sky_plugin.xml` — browsable plugin row (name, description/version/categories, action button).
- `app/src/main/res/layout/item_sky_installed.xml` — installed plugin row (icon, name/description, settings gear, enable switch, remove).
- `app/src/main/res/layout/item_sky_section.xml` — section/listing header + info text row.

## Modified

- `app/src/main/res/values/strings.xml` — added the `sky_*` string set (page title "SkyStream Plugins", repo add/resolve/install/remove/settings copy); no duplicates (grepped first).
- `app/src/main/res/layout/main_settings.xml` — "SkyStream Plugins" settings row between Nuvio Plugins and Categories, with the focus chain re-threaded (nuvio → sky → categories).
- `app/src/main/res/navigation/mobile_navigation.xml` — `navigation_settings_sky` fragment destination + `action_navigation_global_to_navigation_settings_sky` global action, mirroring the Stremio/Nuvio entries.
- `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsFragment.kt` — sky row click wiring (navigate via the new global action).
- `app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt` — `SkyProviders.sync` inside its own runCatching in the init block, alongside the Stremio/Nuvio syncs (8.70 isolation pattern).

## Verification (no push, per instructions)

- SkyPolyfill's JS is byte-identical to the spike's SKY_POLYFILL (diffed programmatically).
- SkyModels.kt + SkyPolyfill.kt compile cleanly with the harness Kotlin compiler (org.json only).
- SkyRepository.kt + SkyEngine.kt compile cleanly against the real dokar quickjs jar, kotlinx-coroutines, android.jar and okhttp-shaped stubs — the dokar binding API, quickJs builder shape and coroutine plumbing are type-verified.
- SkyPluginProvider/SkyProviders/SkyPluginsFragment were compiler-scanned with the app classpath absent: the only diagnostics are unresolved references to app/androidx classes and their inference cascades (the two non-cascade-looking errors were traced to unresolved receivers — Episode.name is String?, matching the assignment).
- All new/modified XML parses; every referenced drawable/string id exists; delimiter balance verified on all touched Kotlin files. Full app build is left to CI, as usual on this VM.

## Deviations / notes

- The JS `getSettings()` export is not consulted for the settings form; the manifest schema (available without running JS) is the only form source in this build. Plugins whose schema exists only in JS will show no settings gear.
- There is no `Qualities.getByHeight` in this codebase; an int quality is used directly because `Qualities.value` IS the height, with `StremioAddonProvider.parseQuality` as the text fallback.
- A fresh single-plugin install starts enabled (the Nuvio "everything starts OFF" rule targets bulk scraper repos; an explicit per-plugin install is a deliberate act). Re-installing as an update keeps the previous enabled state.
- Removing a repository does not uninstall plugins installed from it (install records carry their own source URL, like SkyStream's meta.json); update checks for those plugins simply stop appearing.
- The settings page uses a plain refresh for repo expand/collapse instead of the Nuvio page's 8.77 animated single-group toggle.
- Settings-schema values are also readable from JS through getPreference (plugin's own writes first, then user settings, then schema defaults) — this mirrors SkyStream, where settings live in the same store plugins read.
- First open of a provider (before any getHome) shows a single combined entry whose response returns every section at once; afterwards the persisted section list drives the main page.
- Deliberate v1 omissions carried from the analysis: native-assist channels (dom_parse/crypto_*/js_unpack), the .qbc bytecode cache, the Cloudflare clearance broker, DRM stream fields, and SkyStream's local proxy.
