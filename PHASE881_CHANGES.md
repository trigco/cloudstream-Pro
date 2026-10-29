# Phase 8.81 — Two-tab extension pages

SkyStream-style UX for all four extension-management pages: TWO swipeable
tabs — **Installed** (page 0) and the browse tab **Repositories / Discover**
(page 1) — with the browse tab selected by default on every page, exactly
like the reference. Installed items are no longer hunted inside long repo
lists. This is a re-partition of the existing UI: adapters, row layouts,
callbacks, dialogs and repository/engine code are unchanged in behaviour.

Shared mechanics (Sky / Nuvio / Stremio): each fragment hosts a
`TabLayout` + `ViewPager2` (swipe enabled, `offscreenPageLimit = 1`) wired
with `TabLayoutMediator`. Pages are plain views created by a small pager
adapter in the fragment — no child fragments. The generic page layout
`tab_page_list.xml` (RecyclerView + empty-state TextView) serves the list
tabs. Extensions (upstream code) uses two dedicated page layouts because
its pages need their original focus attributes.

---

## Build / dependency

- `gradle/libs.versions.toml` — added `viewpager2 = "1.1.0"` version and
  the `androidx.viewpager2:viewpager2` library entry.
- `app/build.gradle.kts` — `implementation(libs.viewpager2)`.

## Shared resources (new)

- `res/layout/tab_page_list.xml` — generic pager page: `tab_recycler`
  RecyclerView + `tab_empty` overlay TextView.
- `res/drawable/ic_sky_plugin_placeholder.xml` — white-stroke puzzle-piece
  vector (same style as the existing settings icons / SkyStream's
  fallback for icon-less plugins).
- `res/drawable/bg_sky_icon.xml` — rounded-square tile (skySurface fill,
  skyOutline stroke, 12dp corners) behind plugin icons.
- `res/drawable/bg_sky_chip.xml` — small rounded chip background for
  language codes.
- `res/values/strings.xml` — new per-page strings:
  `extensions_tab_installed`, `extensions_tab_repositories`,
  `extensions_installed_empty`; `stremio_tab_installed`,
  `stremio_tab_discover`, `stremio_discover_hint`, `stremio_quick_title`,
  `stremio_quick_cinemeta_name`, `stremio_quick_cinemeta_desc`,
  `stremio_quick_install`, `stremio_quick_enable`,
  `stremio_quick_installed`; `nuvio_tab_installed`,
  `nuvio_tab_repositories`, `nuvio_master_title`, `nuvio_master_summary`,
  `nuvio_repos_empty`; `sky_tab_installed`, `sky_tab_repositories`,
  `sky_installed_empty`, `sky_base_url_label`, `sky_base_url_hint`,
  `sky_installed_by_format`, `sky_installed_providers_format`.

---

## Page 1 — SkyStream Plugins

- `res/layout/fragment_sky_plugins.xml` — header unchanged; the single
  list + empty view replaced by `sky_tabs` TabLayout + `sky_pager`
  ViewPager2.
- `ui/settings/sky/SkyPluginsFragment.kt`:
  - Two `SkyRepoAdapter` instances (same callback set via `makeAdapter()`),
    one per tab. `SkyRepoAdapter.submit(...)` split into
    `submitInstalled(...)` (installed rows only) and `submitRepos(...)`
    (repo headers + listings + plugin rows; the in-list section headers
    are gone — the tabs replace them). Per-tab empty states:
    `sky_installed_empty` / `sky_plugins_empty`.
  - Icons: new `bindSkyIcon()` — manifest/listing `iconUrl` via the
    project's Coil loader, error/placeholder =
    `ic_sky_plugin_placeholder`; no icon → placeholder. Applied to browse
    rows and installed rows. The installed row's old circle-crop is gone
    (rounded-square tile now, SkyStream-style).
  - Installed rows enriched: description line is description only; new
    meta line `v<version> • By <authors> • <n> providers` (parts only
    when present); language chips (uppercase codes on `bg_sky_chip`)
    built programmatically in the bind.
  - Settings gear now shows on EVERY installed row.
  - Settings form moved from `AlertDialog` to a **BottomSheetDialog**
    (title + scrollable form + Cancel/Save row; same getters, same
    save → `savePluginSettings` → `SkyEngine.evictPackage` → toast flow).
    The sheet always carries a **Base URL** field: if the manifest schema
    has an `isBaseUrl` field, that field is prefilled with
    `SkyRepository.effectiveBaseUrl(...)`; otherwise a dedicated field is
    appended, prefilled with the effective URL and hinted with the
    manifest default. Saving a non-default value writes the `base_url`
    settings key (the exact override path `effectiveBaseUrl` reads);
    clearing it / re-entering the default drops the override because the
    settings object is replaced on save. `openPluginSettings` no longer
    returns early when a plugin has no schema.
- `res/layout/item_sky_plugin.xml` — added the icon ImageView
  (44dp, `bg_sky_icon` tile, placeholder src).
- `res/layout/item_sky_installed.xml` — icon now always visible with the
  tile + placeholder; added `item_sky_inst_meta` TextView and
  `item_sky_inst_langs` chip row; settings button no longer starts GONE.

## Page 2 — Nuvio Plugins

- `res/layout/fragment_nuvio_plugins.xml` — header + diagnostics line
  unchanged; list replaced by `nuvio_tabs` + `nuvio_pager`.
- `res/layout/item_nuvio_master.xml` (new) — master card: puzzle tile,
  "Nuvio Scrapers" title, enabled-count summary.
- `ui/settings/nuvio/NuvioPluginsFragment.kt`:
  - Installed tab: existing `NuvioRepoAdapter` (repo groups, per-scraper
    toggles, validity lines, settings, 8.77 collapse animation + item
    animator — all preserved) with a new first row `NuvioRow.Master`
    fed by counts computed in `refresh()`. `submit()` gained
    enabled/total count parameters (stored on the adapter so
    `toggleRepo()` rebuilds keep the card).
  - Repositories tab: new `NuvioRepoListAdapter` — simple repo list
    reusing `item_nuvio_repo.xml` (chevron hidden): name, URL, and the
    same check / refresh / remove callbacks.
  - Empty states per tab: `nuvio_plugins_empty` (Installed),
    `nuvio_repos_empty` (Repositories). Diagnostics line unchanged.
- **Deviation (by necessity):** there is no master enable/disable switch
  for all scrapers anywhere in the repository layer (the brief assumed
  the card existed "as today" — it exists only in SkyStream's app). The
  master card is therefore a summary card (title + "X of Y scrapers
  enabled"); inventing a global toggle would have meant new persisted
  state + provider-registration behaviour changes, which the brief
  forbids. Per-scraper toggles remain the control, as before.

## Page 3 — Stremio Add-ons

- `res/layout/fragment_stremio_addons.xml` — header unchanged; list
  replaced by `stremio_tabs` + `stremio_pager`.
- `res/layout/fragment_stremio_discover.xml` (new) — Discover tab:
  hint text, a full-width "Add addon" button (same add-by-URL dialog as
  the header button, which also stays), and the Quick install card.
- `ui/settings/stremio/StremioAddonsFragment.kt`:
  - Installed tab: the existing `StremioAddonAdapter` list unchanged
    (toggles, remove, built-in handling). The explainer/empty text keeps
    its old rule (visible while only the built-in row exists) as the
    tab's overlay empty view.
  - Discover tab (default): add-by-URL + **Quick install Cinemeta**
    card driven by `updateQuickInstall()`: absent → "Install" →
    `installAddon(CINEMETA_URL)` (the single existing install path);
    present but disabled → "Enable" → `AddonRepository.setEnabled(true)`
    + sync; present and enabled → "Installed", disabled button.
    (Because `AddonRepository.getAddons()` always surfaces the built-in
    Cinemeta row, the Enable/Installed states are the ones seen in
    practice; the Install state covers a stored-row-less future.)
  - No catalog or backend was invented.

## Page 4 — CloudStream native Extensions (upstream — surgical)

- `res/layout/fragment_extensions.xml` — TabLayout (`extensions_tabs`)
  added inside the AppBarLayout under the toolbar; `repo_recycler_view`
  replaced by `extensions_pager` ViewPager2 carrying its 80dp bottom
  margin + appbar-scrolling behaviour. Everything else (search overlay
  list, blank-repo screen, storage bar, FAB, TV holder) untouched.
- `res/layout/extensions_tab_repos.xml` (new) — the pre-8.81
  `repo_recycler_view` moved verbatim (same id, focus attributes,
  layoutManager) into the pager. ViewBinding note: the id now lives in
  this layout, so the fragment reaches it through the pager callback
  instead of `binding.repoRecyclerView`.
- `res/layout/extensions_tab_installed.xml` (new) — Installed tab:
  `installed_recycler_view` + `installed_empty_view`.
- `ui/settings/extensions/ExtensionsFragment.kt` (fragment/layout layer
  only; `ExtensionsViewModel` untouched):
  - Pager setup with per-page setup functions. `setupRepoPage()` is the
    old repo-list wiring moved verbatim (focus chain, FAB padding post,
    FAB shrink/extend scroll listener, `RepoAdapter` with the same
    navigate + delete-confirm flows), run on the pager's page view
    (`BaseFragment.binding` is nullable there — guarded). Latest repos
    are cached in `latestRepos` so a page created after LiveData
    delivery still initialises; the repositories observer now writes
    through the nullable `repoRecycler` ref.
  - `setupInstalledPage()` + `submitInstalledPlugins()`: a
    `PluginAdapter(showRepositoryNames = true)` fed from the existing
    `PluginsViewModel.filteredPlugins` LiveData filtered to
    `isDownloaded`, sorted (grouped) by repository name then plugin
    name. Row taps open the existing `PluginDetailsFragment` sheet;
    the action button runs the existing `handlePluginAction`
    (delete for downloaded rows); downloaded rows keep their settings
    gear — identical management surface to the search-results list.
  - The 8.10 phone pill-lift now lifts `extensionsPager` in place of the
    repo list (same geometry). The toolbar search expand/collapse now
    toggles pager visibility where it used to toggle the repo list;
    the search overlay list itself is unchanged.
- **Deviation (upstream API limit):** upstream has NO per-plugin
  user enable/disable API — a downloaded plugin's "disabled" state is
  declared by its repository manifest (`PROVIDER_STATUS_DOWN`, already
  rendered by `PluginAdapter` as the "(Disabled)" name suffix and
  counted in the storage bar). The Installed tab therefore offers the
  management operations that exist — details, settings (when the plugin
  declares them), and delete — and shows the repo-declared disabled
  state; a user toggle would require new PluginManager state and load
  behaviour, i.e. the deep surgery the brief told me to avoid.

---

## Verification done (no CI run — parent pushes)

- All new/modified XML parses (ElementTree) and every LinearLayout in
  touched layouts declares `android:orientation`; no `android:tint`
  on ImageViews.
- Every `R.id` / `R.string` / `R.drawable` / `R.layout` referenced by
  the four fragments and the new layouts resolves in the tree (the only
  unresolved names are framework/navigation ids that pre-date 8.81).
- Kotlin delimiter balance verified on all four touched fragments;
  no raw strings with `${` introduced.
- Removed layout ids (`sky_recycler`, `sky_empty`, `nuvio_recycler`,
  `nuvio_empty`, `stremio_recycler`, `stremio_empty`) have no remaining
  references anywhere in code, layouts, or navigation.
