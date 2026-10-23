# Phase 5B — In-app actor overview page

Local only, nothing pushed (parent reviews, pushes, CI-verifies).
No new dependencies, no new libraries, no vector drawables created.

---

## What the user gets

Tapping a cast member on the details screen (phone AND TV) now opens a
dedicated **Actor page inside the app** instead of just flipping the
name/role text on the cast card:

- Big actor photo, name, and a role line (character • role category •
  "in <source title>").
- An **Overview** section with the actor's biography extract, fetched live
  from Wikipedia — shown only when Wikipedia actually has one; silently
  absent otherwise.
- A **"Search titles: <actor name>"** button that opens the app's regular
  search screen with the search already running for that actor.
- Long-press on a cast card keeps its old behaviour (web search) — untouched.

## Data model (verified in code)

`library/.../MainAPI.kt` lines 1534–1555:

- `Actor(name: String, image: String? = null)` — that is ALL a provider
  supplies per actor. There are **no ids, no biography, no external links**
  anywhere in the model.
- `ActorData(actor: Actor, role: ActorRole?, roleString: String?,
  voiceActor: Actor?)` — `role` is the Main/Supporting/Background category;
  `roleString` is the free-text character name when the provider has one.

Consequence (by design, told to the user up front): the overview text cannot
come from the app's own data — it is fetched from Wikipedia at runtime and
may be missing for obscure actors. The page is still fully useful without it
(photo, role, one-tap in-app search).

## Files created

### `app/.../ui/result/ActorFragment.kt`
- `BaseFragment<FragmentActorBinding>(BindingCreator.Inflate(...))` — the
  exact template of `ui/download/queue/DownloadQueueFragment.kt`, including
  the toolbar back-arrow block (`isLayout(PHONE or EMULATOR)` →
  `ic_baseline_arrow_back_24` → `dispatchBackPressed()`) and
  `fixLayout` → `fixSystemBarsPadding(view)` (same as QuickSearchFragment).
- Companion `openActor(activity, item, sourceTitle)` mirrors
  `QuickSearchFragment.pushSearch`: builds a `Bundle` and calls
  `UIHelper.navigate` (`Activity?.navigate`) with a **global action id**.
  Argument keys are plain strings, the same way ResultFragment reads
  `url`/`apiName`/`name` in `getStoredData()`.
- Image loading: `actorPhoto.loadImage(url)` — the same
  `utils.ImageLoader.loadImage` extension the cast list uses
  (ActorAdaptor: `binding.actorImage.loadImage(mainImg)`).
- Role label resolution copies ActorAdaptor's own `when (role)` →
  `R.string.actor_main/supporting/background` mapping; the enum travels as
  its `name` string and is parsed back with a guarded `valueOf`.
- Wikipedia fetch (Step 2):
  - `ioSafe { ... }` (`utils.Coroutines`, the receiver-style launcher
    fragments use) + `main { ... }` to return to the UI — the same pair
    VotingApi uses around its network call.
  - `app.get(url).parsedSafe<WikipediaSummary>()` — byte-for-byte the
    `plugins/VotingApi.kt` pattern (`com.lagradost.cloudstream3.app` +
    nicehttp `parsedSafe`), including the dual
    `@JsonProperty` + `@SerialName` + `@Serializable` data-class style of
    its `CountifyResult`. The project's Jackson mapper tolerates the many
    extra fields in a Wikipedia response (proven by CountifyResult/Kitsu).
  - URL: `https://en.wikipedia.org/api/rest_v1/page/summary/<name>` with
    spaces → underscores and `Uri.encode` for the rest.
  - The Overview header+text start `gone` in the layout and only become
    visible with a non-empty `extract`. Any failure (404, no extract,
    offline, parse error) leaves them hidden: `parsedSafe` returns null and
    `ioSafe`'s `launchSafe` swallows/logs throwables — no crash path.
  - Photo upgrade: only when the provider supplied NO image, the
    Wikipedia `originalimage` (else `thumbnail`) replaces it.
- Search button (Step 3 — see below): navigates to `R.id.navigation_search`
  with `SearchFragment.SEARCH_QUERY` in the bundle.

### `app/src/main/res/layout/fragment_actor.xml`
- Chrome copied from `fragment_download_queue.xml`: root `FrameLayout`
  `?attr/primaryBlackBackground`, `AppBarLayout` + `MaterialToolbar`
  (`?attr/primaryGrayBackground`, `app:navigationIconTint="?attr/iconColor"`,
  `app:titleTextColor="?attr/textColor"`, `paddingTop=@dimen/navbar_height`).
- Content: `NestedScrollView` → photo in a `CardView`
  (`@dimen/rounded_image_radius`, `outline_drawable` foreground — the
  cast_item recipe), name (24sp bold, `?attr/textColor` — result_title is
  20sp; the page title is one step larger), role line (`?attr/grayTextColor`),
  Overview header/body (both start `gone`), and a `MaterialButton`
  `@style/WhiteButton` (the style's verified carrier, see fragment_result).
- Every attribute used already appears in the project's own layouts —
  no first-use attrs, no `?attr` inside any drawable (none created).

## Files modified

### `app/src/main/res/navigation/mobile_navigation.xml`
- New global action `global_to_navigation_actor` → `@id/navigation_actor`
  with the results actions' animation set and argument declarations:
  `actor_name` (string), `actor_image` / `actor_role` / `actor_role_string`
  / `actor_title` (string, `defaultValue="@null"` — the exact nullable
  pattern `global_to_navigation_player` uses for `data`/`uriData`).
- New destination `navigation_actor` → `ui.result.ActorFragment`,
  declared like the other simple destinations
  (`tools:layout="@layout/fragment_actor"`, label `@string/actor_overview`).

### `app/src/main/res/values/strings.xml`
Three additions next to the existing `actor_*` strings (mirroring
`cast_format`'s `formatted="true"` style):
`actor_overview` "Overview", `actor_search_titles` "Search titles: %s",
`actor_in_format` "in %s".

### `app/.../ui/result/ActorAdaptor.kt`
- New optional constructor parameter
  `clickCallback: ((ActorData) -> Unit)? = null` (appended after the
  existing params, so existing call sites are source-compatible).
- Click listener: when a callback is wired it is invoked with the item;
  otherwise the ORIGINAL inversion toggle runs unchanged. Long-press web
  search is byte-identical. The `inverted` map/animation path is untouched
  for unwired consumers.

### `app/.../ui/result/ResultFragmentPhone.kt`
- `ActorAdaptor()` → `ActorAdaptor(clickCallback = { ... })` opening
  `ActorFragment.openActor(activity, item, sourceTitle)`, where
  `sourceTitle = (viewModel.page.value as? Resource.Success)?.value?.title`
  — an idiom this same file already uses at two other call sites.

### `app/.../ui/result/ResultFragmentTv.kt`
- Same wiring. The adaptor call was restructured from trailing-lambda to
  named arguments (`nextFocusUpId = aboveCast?.id`,
  `focusCallback = { toggleEpisodes(false) }`) because a named
  `clickCallback` cannot follow a trailing lambda; behaviour of the focus
  callback is unchanged. Same `viewModel.page` title idiom (already used
  twice in this file).

## Step 3 decision — FALLBACK taken (button), deliberately

First choice (embed provider-search results in the page) was rejected after
reading the code: provider search lives in `SearchViewModel` +
`SearchFragment`'s master-adapter pipeline (paging via expand callbacks,
provider filtering, history/suggestion side effects). There is no reusable
"search by query → list" entry point below that UI layer; lifting results
into the actor page would mean copy-pasting SearchFragment internals —
explicitly out of scope.

The fallback is NOT a plain navigate, though: `navigation_search` declares
a `search_query` argument and SearchFragment **auto-submits it on open**
(SearchFragment.kt: "Automatically search the specified query, this allows
the app search to launch from intent" — it calls `mainSearch.setQuery(query,
true)` and clears the arg). So the button lands the user on the normal
search screen with the actor search already running — one tap from the
actor page, results in the app's best UI, zero duplicated machinery.

## Validation
- minidom parse OK: fragment_actor.xml, mobile_navigation.xml, strings.xml.
- Kotlin is diff-reviewed only (no Android SDK here — CI is the check,
  as in Phases 1–5A). Every import in ActorFragment is exercised by at
  least one existing file (listed above); no new imports were needed in
  either result fragment (ActorFragment is in their own package, and both
  already import `Resource`).

## Risks / uncertainties for CI
1. **ViewBinding generation**: `FragmentActorBinding` is generated from
   `fragment_actor.xml`; all ids referenced (`actorToolbar`, `actorPhoto`,
   `actorName`, `actorRole`, `actorOverviewHeader`, `actorOverview`,
   `actorSearchButton`) exist in the layout with matching view types
   (MaterialToolbar → `actorToolbar` is a MaterialToolbar in the layout —
   DownloadQueue does the same and binds `downloadQueueToolbar`).
2. **`Uri.encode` on the Wikipedia path**: underscores survive encoding
   (unreserved), spaces are pre-replaced; non-Latin names are UTF-8
   percent-encoded, which the REST endpoint accepts. If a name contains
   characters like `/`, encode escapes them — worst case is a 404 →
   section stays hidden.
3. **Wikipedia REST v1** occasionally returns a "disambiguation" page
   extract for ambiguous names (e.g. common first names) — acceptable for
   a best-effort bio; no crash risk.
4. **TV focus**: the actor page follows the Queue template (no explicit
   focus chain); the toolbar hides its back icon on TV and system Back
   returns, same as DownloadQueue on TV. The search button is focusable
   by default as a MaterialButton.
5. **Back-stack**: navigating actor → `navigation_search` pushes Search
   as a top-level destination; Back returns to the actor page. Matches
   how the app already deep-links into search from other screens.
6. The two Wikipedia data classes are `private` nested classes — the
   same shape as VotingApi's `private data class CountifyResult` (inside
   an object), which the project's Jackson setup already parses in
   production code; if the mapper ever balked, `parsedSafe` would return
   null and the Overview would simply stay hidden, not crash.
7. The old cast-tap inversion is gone **on phone and TV result screens**
   (the only two ActorAdaptor consumers — verified by grep; the preview
   sheet does not use this adaptor). Anywhere the adaptor might be reused
   later without a callback, the inversion behaviour is preserved by the
   null default.

## Post-build fix (parent): Wikipedia User-Agent
Wikimedia's API access policy makes an identifying User-Agent mandatory (requests without one may be blocked without notice, which would make every overview silently empty). The bare app.get() relied on the HTTP library's generic default UA. fetchWikipediaOverview now passes headers = mapOf("User-Agent" to "CloudStreamPro/1.0 (myself@nitrogen.bond)") — the headers argument pattern is the one already used by KitsuApi/FcastAction/Addic7ed.
