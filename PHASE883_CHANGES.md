# Phase 8.83

## Part 1 — Provider switcher

The Home provider picker (`Context.selectHomepage` in
`ui/home/HomeFragment.kt`) was a plain ListView dialog — flag +
name rows. It is now a SkyStream-style **smart bottom sheet**,
modelled on their "Select Provider" popup. The public signature
`selectHomepage(selectedApiName, onCustomSelect, callback)` is
unchanged, so both callers (HomeFragment's api-change listener,
HomeParentItemAdapterPreview) work as before.

### New sheet (layout `home_provider_switcher.xml`, new)

- `BottomSheetDialog` with `R.style.AppBottomSheetDialogTheme`,
  root background `@drawable/rounded_dialog`, sheet state
  EXPANDED, and the dialog frame forced transparent after show —
  the exact 8.82 recipe, so no light/white surface can appear.
- **Title row**: "Select Provider" (`switcher_select_provider`).
- **Type filter chips** in a horizontal scroll under the title:
  a new **All** chip plus the picker's existing tv-type chips
  (the `tvtypes_chips` include: Movies, TV Series, Anime, Asian
  Dramas, Cartoons, Documentaries, Livestreams, Torrents, NSFW,
  Others), still bound by the existing `bindChips`/`updateChips`
  machinery to `DataStoreHelper.homePreference` — visibility and
  checked state derive from the providers' `supportedTypes`
  exactly as before. The All chip writes the same selection:
  on = every type present among the listed providers, off =
  none, and it re-syncs itself inside `updateList()` after any
  tv-type chip toggle.
- **Radio list** (`RecyclerView`, new row layout
  `home_provider_switcher_row.xml`): radio circle (new vectors
  `ic_switcher_radio_checked` in the app accent red /
  `ic_switcher_radio_unchecked` in grey), provider name
  (ellipsised, bold + full text colour when selected, grey
  otherwise), **source tag chips** on the right of the name, then
  the pinned marker and the plugin settings (tune) shortcut in
  their old places.
- **Close** text row at the bottom (dismisses, no callback),
  beside the usual tap-outside dismiss.

### Source tags (computed per row in `tagsFor`)

- **Sky plugins** (`SkyPluginProvider`): sub-provider rows now
  headline with the **sub name** ("Netflix") and carry the
  **plugin name** as a grey tag ("Netmirror") — the plugin name
  leaves the title; the api key (`api.name`) is untouched, so
  persisted selections/pins still match. A plugin without
  sub-providers keeps its name and gets a grey "SkyStream" tag.
- **Stremio addons** (`StremioAddonProvider`): grey "Stremio"
  tag (the provider name already is the addon name).
- **Nuvio** (`NuvioProvider`): no tag — the single provider is
  already named Nuvio; a tag would only repeat it.
- **Native providers**: grey tag with the **repository name**
  ("CNC Verse") when resolvable, plus the purple **"☁
  CloudStream"** chip (`bg_switcher_tag_cloud`). Resolution is
  synchronous and offline: a downloaded plugin's file lives in a
  folder named `PluginManager.getPluginSanitizedFileName(
  repositoryUrl)` (`getPluginPath`), so the parent folder of
  `api.sourcePlugin` is matched against
  `RepositoryManager.getRepositories()` +
  `PREBUILT_REPOSITORIES`. Built-in providers (no sourcePlugin)
  and local/unmatched plugins get the CloudStream chip only.
  None/Random rows get no tags.

### Behaviour preserved verbatim (data logic copied 1:1)

- Row set and order: None, Random, pinned providers
  (most-recent pin first), remaining providers alphabetical;
  custom lists after providers with pinned customs first.
- Tv-type filtering incl. the pinned-NSFW hide rule, the
  `homePreference` write-back on every `updateList()`, and the
  Family Mode adult-provider removal.
- Selection semantics: the old ListView seeded its checked row
  from the active custom list first and the current api second —
  rows now carry that same `selected` state. Tapping a provider
  fires `callback(api.name)` and dismisses immediately (the old
  Apply/Cancel row was already hidden and stays gone); tapping
  a custom list fires `onCustomSelect` and dismisses.
- Long-press pin/unpin for providers (None/Random excluded, as
  before) and for custom lists via the same `"custom:<id>"`
  keys in `DataStoreHelper.pinnedProviders`; pin marker shows
  on pinned rows.
- Plugin settings shortcut: shown only for downloaded plugins
  with `openSettings` and not on TV, invoking it with the
  activity context, errors swallowed via `logError` — identical
  to the old row logic.
- Language flag prefix on titles when multiple provider
  languages are configured (`isMultiLang`), as before.

### Shared layout left alone

`home_select_mainpage.xml` is **not** modified: SearchFragment's
search-filter picker inflates the same `HomeSelectMainpageBinding`
(ListView + chips + Apply/Cancel) and keeps working unchanged.
The old row layout `sort_bottom_single_provider_choice.xml` is
now unreferenced by code (only a `tools:listitem` preview
remains in that shared layout) and is left in place.

### Floating provider pill gap (fragment_home.xml + anchor)

The funnel pill (`home_api_fab`) crowded the floating bottom
nav. The runtime anchor (`anchorHomeApiFab`, 8.5C) now uses a
**24dp** gap above the measured nav (was 16dp), and the XML
pre-layout fallback margins for both bottom pills
(`home_api_fab`, `home_random`) went 104dp → **112dp** so the
pair stays aligned before the anchor runs. The TV layout has no
floating nav and is untouched.

### Files (Part 1)

- `ui/home/HomeFragment.kt` — `selectHomepage` rewritten
  (presentation only); imports adjusted (bindings, bridge
  provider classes, RepositoryManager, File/Color/Typeface in;
  ListView-era imports out); `anchorHomeApiFab` gap 16→24dp.
- `res/layout/home_provider_switcher.xml` (new),
  `res/layout/home_provider_switcher_row.xml` (new).
- `res/drawable/ic_switcher_radio_checked.xml` (new),
  `ic_switcher_radio_unchecked.xml` (new),
  `bg_switcher_tag.xml` (new), `bg_switcher_tag_cloud.xml` (new).
- `res/values/colors.xml` — `switcherTagBackground`,
  `switcherTagText`, `switcherTagCloudBackground`,
  `switcherTagCloudText`.
- `res/values/strings.xml` — `switcher_select_provider`,
  `switcher_close`, `switcher_tag_cloudstream`,
  `switcher_tag_stremio`, `switcher_tag_skystream`
  (the All chip reuses the existing `R.string.all`).
- `res/layout/fragment_home.xml` — the two FAB fallback
  margins 104dp → 112dp + comment.

### Verification (Part 1; no CI run — parent pushes)

- All new/modified XML parses (ElementTree); the row layout is
  a ConstraintLayout with constrained-width tags so long tag
  sets ellipsise the title instead of pushing the icons off.
- Every id referenced from code exists in the layouts
  (`switcher_chip_all`, `switcher_tvtypes_chips`,
  `switcher_recycler`, `switcher_close`, `switcher_row_radio`,
  `switcher_row_title`, `switcher_row_tags`,
  `switcher_row_settings`, `switcher_row_pin`); every new
  string/color/drawable resolves; no `android:tint`; the one
  LinearLayout in the sheet declares its orientation.
- HomeFragment.kt passes a syntax parse (Kotlin embeddable
  compiler): no syntax errors; string/comment-aware delimiter
  balance is 0/0/0. (One splice slip — the companion object's
  closing brace — was caught by that balance check and fixed.)

## Part 2 — Universal cookies

One app-owned, persistent cookie system, so providers that never
handle cookies themselves still get correct session behaviour —
including when a provider is swapped, evicted or the app restarts.

### New file

- `app/src/main/java/com/lagradost/cloudstream3/utils/AppCookieJar.kt`
  — `AppCookieJar(context | contextProvider, namespace)` implementing
  `okhttp3.CookieJar`:
  - RFC 6265 semantics delegated to OkHttp itself: Set-Cookie parsing
    happens inside OkHttp before `saveFromResponse`, and request
    matching uses `Cookie.matches` — host-only cookies only return to
    the exact host, Domain cookies match subdomains, paths
    prefix-match, expired cookies are evicted (an expired Set-Cookie
    deletes), Secure cookies only travel over https.
  - Persistence: the whole store (name, value, domain, path,
    expiresAt, secure, httpOnly, hostOnly, persistent) is serialized
    as one JSON array in the default SharedPreferences under
    `app_cookies_v1_<namespace-sanitized>`, loaded lazily on first
    use (in-memory until a Context exists), saved synchronously
    (`apply()`) on every change. Session cookies are persisted on
    purpose and restored with open-ended expiry — a plugin's
    handshake cookie must not die with the process.
  - Namespaces are fully isolated. Store access is synchronized;
    the cap is 300 cookies per namespace (expired evicted first,
    then oldest-inserted).

### Wiring (all additive — an explicit Cookie header always wins,
because OkHttp only consults the jar when the request has none)

- **Sky plugins** (`sky/SkyEngine.kt`, `SkyHostBridge`): the old
  in-memory per-session map jar is gone. Each bridge instance owns
  `AppCookieJar(appContext, "sky:<packageName>")`, set on the
  per-call derived client. The 8.82 network interceptor is kept
  but now only builds the plugin-visible merged set-cookie list;
  storing/sending is the jar's job (OkHttp feeds the jar from every
  redirect hop). The 8.80 response shape ({status, code, statusCode,
  body, headers incl. merged set-cookie, finalUrl, url}) is
  byte-for-byte unchanged.
- **Sky repository chain** (`sky/SkyRepository.kt`, `SkyHttp.client`):
  `AppCookieJar({ CloudStreamApp.context }, "sky:repo")` — the
  context is resolved lazily, as the object can be touched before
  the Application publishes it. Plugin traffic overrides this jar
  with its per-plugin one on the derived builder, so the two never
  mix.
- **Nuvio scrapers** (`nuvio/NuvioEngine.kt`, `NuvioHostBridge`):
  the per-run in-memory map is gone; each bridge lazily derives its
  client from the shared `HTTP` client with
  `AppCookieJar(appContext, "nuvio:<scraperId>")`. Per-scraper
  namespacing (the preferred option) was trivially possible because
  the bridge is constructed with the scraper id. Response shape
  unchanged (set-cookie still hidden from the headers object).
- **Native providers / NiceHttp** (`network/RequestsHelper.kt`,
  `buildDefaultClient`): `.cookieJar(AppCookieJar(context, "native"))`
  added to the builder — no timeout, interceptor, DNS, SSL or cache
  changes.

### Deviations

- NiceHttp is an external dependency (`com.github.Blatzar:NiceHttp`)
  — there is no `NiceHttp.kt` in this tree to edit. The OkHttp
  client that backs `Requests` (`app`) is constructed by
  `buildDefaultClient()` and installed via `Requests.initClient`,
  so the "native" jar is attached there; every native provider and
  extractor request flows through it.
- Ad-hoc `Requests()` instances created away from `initClient`
  (e.g. `DdosGuardKiller`) keep the library's own default client
  and are untouched — changing them was outside the additive scope.

### Verification

- Kotlin delimiter balance verified on all five touched files
  (AppCookieJar, SkyEngine, SkyRepository, NuvioEngine,
  RequestsHelper); no dangling references to the removed manual
  jars (`cookies`, `host`, `sawCookie` are gone from both bridges);
  every referenced symbol (`Cookie.Builder.hostOnlyDomain`,
  `Cookie.matches`, `CloudStreamApp.context`, ...) exists in the
  tree/OkHttp API. No CI run — the parent pushes.
