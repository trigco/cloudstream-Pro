# Phase 8.82 — Extension-page verdict fixes

Device verdict on 8.81 (two-tab pages) reported: white tab bars with
a flat full-width underline on all four pages, white button
backgrounds, light/white popups, the Sky mega-repo still showing as
one card with flat "Stars"/"ZORO" section labels, text install
buttons where the reference uses icons, a Stremio page with content
hidden under an overlay, and Sky settings sheets with no way to
switch sub-providers (Netmirror class). This build fixes all of it.
Behaviour of providers/engines is unchanged except D (what gets
stored) and G (a registration filter), as specified.

Shared new colors (`res/values/colors.xml`):
`extButtonBackground` #FF262626 (dark chip), `extTabUnselected`
#FF9E9E9E, `extRippleLight` #33FFFFFF. New strings
(`res/values/strings.xml`): `sky_plugin_state_installed`,
`sky_settings_providers`.

---

## A — Tab bars styled dark on all four pages

Every TabLayout now declares its styling explicitly in XML:
background = the page's dark background, unselected text
`@color/extTabUnselected` (#9E9E9E), selected text white, indicator
`@color/colorPrimaryRed` with `tabIndicatorFullWidth="false"` and
`tabIndicatorHeight="3dp"` (short red bar under the active label,
SkyStream-style), ripple `@color/extRippleLight`.

- `res/layout/fragment_sky_plugins.xml` (`sky_tabs`),
  `res/layout/fragment_nuvio_plugins.xml` (`nuvio_tabs`),
  `res/layout/fragment_stremio_addons.xml` (`stremio_tabs`) —
  background `@color/primaryBlackBackground` (#000000, the window
  background these fragment roots show).
- `res/layout/fragment_extensions.xml` (`extensions_tabs`) — keeps
  `?attr/primaryGrayBackground`, which IS that fragment root's /
  AppBar's background; indicator/text/ripple now match the other
  three pages (previously the indicator was the text colour at full
  width and unselected text was also white).

## B — Dark chip buttons

Header/primary MaterialButtons on the four pages get
`app:backgroundTint="@color/extButtonBackground"` (#262626) +
`android:textColor="@color/white"`:

- `fragment_sky_plugins.xml` — `sky_add_button` (Add Repository).
- `fragment_nuvio_plugins.xml` — `nuvio_add_button`.
- `fragment_stremio_addons.xml` — `stremio_add_button`.
- `fragment_stremio_discover.xml` — `stremio_discover_add_button`,
  `stremio_quick_install_button`.
- `ui/settings/sky/SkyPluginsFragment.kt` — the settings sheet's
  Cancel/Save buttons are built in code; they get the same #262626
  tint + white text in code.
- Extensions has no MaterialButton text buttons of its own (its FAB
  and the add-repo dialog's buttons are the app's upstream themed
  ones) — nothing to restyle there.

## C — Dark popups/sheets everywhere on the four pages

The app's dark dialog theme is `R.style.AlertDialogCustom` (parent
`Theme.AlertDialog`: `dialog__window_background` surface, white
positive pill, outlined negative pill) — the one the Extensions
add-repo dialog already used. All four pages now use it for every
AlertDialog, and add-dialog EditTexts get explicit white text /
#9E9E9E hints (they are built with the activity context):

- Sky (`SkyPluginsFragment.kt`): add-repo, remove-repo and
  remove-plugin dialogs.
- Nuvio (`NuvioPluginsFragment.kt`): add-repo, remove-repo,
  validity-check progress, settings-loading and the scraper
  settings form dialogs. The settings form's programmatic labels /
  switches / inputs are additionally forced to white text (gray
  hints) by a `styleFormText` tree walk, same as the Sky sheet.
- Stremio (`StremioAddonsFragment.kt`): add-addon and remove-addon
  dialogs.
- Extensions (`ExtensionsFragment.kt`): the delete-repository
  confirmation now uses AlertDialogCustom too (the add-repo dialog
  already did).

Sky settings bottom sheet (`SkyPluginsFragment.kt`): constructed
with the app's `R.style.AppBottomSheetDialogTheme`, the sheet root
carries the app modal surface `@drawable/rounded_dialog` (the
AppModalStyle background), the dialog's own `design_bottom_sheet`
frame is set transparent after show so no other surface can show
through, the title is white, and the whole form is colour-forced by
the `styleFormText` walk (EditTexts white + gray hints, all other
text white).

## D — Sky mega-repo flattening

`sky/SkyRepository.kt`:

- New `addRepoResolved(ctx, url, input, doc)`: when the resolved
  document is a mega-repo, the mega URL itself is removed/never
  stored and EACH sub-repo is stored as its own `StoredSkyRepo`
  (key = the sub-repo's final repo.json URL after redirects,
  docJson = the sub-repo's own document, so name/description come
  from it). Already-stored sub-repos refresh in place; a sub-repo
  whose document cannot be fetched at add time is stored doc-less
  (its document loads live on the next chain fetch). Plain repos
  store exactly as `addRepo` did. Returns the newly-added count.
- New `migrateMegaRepos(ctx)`: one-time migration — any stored
  entry whose document `isMega` is replaced by its sub-repo
  entries (same shape, original input/addedAt kept, URLs deduped)
  and persisted. No-op once nothing stored is a mega.
- `fetchRepoChain` is unchanged (it still expands a mega document
  if one is ever handed to it).

`ui/settings/sky/SkyPluginsFragment.kt`:

- `onViewCreated` runs the migration on repository load, then
  refreshes and loads chains for the flattened list.
- `resolveAndAdd` stores via `addRepoResolved` (toast: added when
  at least one entry was new, else refreshed), clears cached
  listings and reloads all chains.
- `refreshRepo` flattens too if a stored repo's document has turned
  into a mega-repo.
- The Repositories tab now shows one collapsible card per
  (sub-)repo — Stars, ZORO, MiraiExt, TJ-Plugins as separate cards
  with their own collapse/delete/refresh, exactly like single
  repos. The flat `ListingHeader` rows only remain as a transient
  fallback while a not-yet-migrated chain is on screen.

## E — Sky browse rows: install buttons become icons

- `res/layout/item_sky_plugin.xml`: the INSTALL/UPDATE/REMOVE
  MaterialButton is now a 48dp borderless `ImageButton`
  (`item_sky_plugin_action`, 12dp padding).
- `SkyPluginsFragment.kt` (`SkyRepoAdapter`): NOT_INSTALLED →
  `@drawable/ic_sheet_download` (white-stroke download arrow),
  enabled, tap installs as before; UPDATE →
  `@drawable/ic_baseline_system_update_24`, enabled, tap updates as
  before; INSTALLED → `@drawable/ic_baseline_check_24` at 55%
  alpha, disabled — a subtle "installed" marker; removal stays in
  the Installed tab (see Deviations). Content descriptions use the
  existing Install/Update strings + new
  `sky_plugin_state_installed`. `PluginHolder.action` is now an
  ImageButton.

## F — Stremio page overlap cleanup

Inspection of `fragment_stremio_addons.xml`,
`fragment_stremio_discover.xml` and the pager wiring found exactly
one overlap source in our code: the Installed tab's empty TextView
(the generic `tab_page_list` overlay) was set VISIBLE whenever only
the built-in Cinemeta row existed — drawn on top of that row. Our
page has no management/debrid blocks (those exist only in the
reference app); header (title + Add button) and Discover (hint +
Add button + Quick-install card) were already single-copy.

- `StremioAddonsFragment.refresh()`: the empty text now shows ONLY
  when the addon list is truly empty (`addons.isEmpty()`).
- `res/layout/tab_page_list.xml`: `tab_empty` is now
  `android:clickable="false"` / `android:focusable="false"` so it
  can never intercept touches on any of the three pages using it.

## G — Sky settings sheet: sub-provider switches

- `sky/SkyRepository.kt`: reserved settings key
  `DISABLED_PROVIDERS_KEY = "disabled_providers"` (JSON array of
  sub-provider ids inside the plugin's existing settings object) +
  `disabledSubProviders(ctx, packageName)` reader.
- `sky/SkyProviders.kt`: `sync()` skips sub-providers whose id is
  in the disabled set (unregisters them like any unwanted
  instance); a plugin whose sub-providers are all off registers
  nothing.
- `SkyPluginsFragment.kt` (`showSettingsForm`): sheet order is now
  Base URL field → schema form → Providers section. The Providers
  section (only when the manifest has `providers[]`) lists one
  MaterialSwitch per sub-provider (name, default ON from the stored
  disabled set); its getter writes the off-ids array on save, and
  the save handler now also calls `SkyProviders.sync(ctx)` after
  the existing evict, so switches apply immediately. This covers
  schema-less plugins like Netmirror, whose sheet previously had
  nothing but the Base URL field.

---

## Deviations / notes

1. (A) Extensions keeps its `?attr/primaryGrayBackground` tab
   background instead of #000000: the instruction was to match
   each fragment root's background, and that root/AppBar is
   primaryGrayBackground. All other styling is identical.
2. (C) PluginDetailsFragment — opened from the Extensions
   Installed tab — already uses the app's
   BaseBottomSheetDialogFragment + AppModalStyle and was left
   untouched; PluginsViewModel/PluginAdapter build no dialogs of
   their own (verified), so every dialog the Extensions tabs open
   is now on the app's dark theme.
3. (D) If a sub-repo's document fetch fails during add/migration
   (e.g. offline), the entry is stored doc-less rather than
   keeping the mega entry: the card shows the repo URL until the
   next chain fetch loads the document live (the header name falls
   back to the listing's repo name). Keeping the mega entry would
   have re-run migration on every load and kept the old flat UI.
4. (E) For the INSTALLED state the browse row no longer offers
   remove-on-tap: the brief's chosen option (subtle disabled check;
   removal in the Installed tab). The fragment's `onPluginAction`
   INSTALLED branch is therefore unreachable from browse rows but
   was kept.
5. (G) Base URL field refinement: when a schema declares its own
   base-url field, that field is now rendered by the dedicated
   first section (writing the schema's key) instead of in-loop,
   and — like the 8.81 dedicated field — writes only a non-blank,
   non-default value. `effectiveBaseUrl` semantics are unchanged
   (blank/default → manifest base URL; a stored override drops
   when the field is cleared, because saving replaces the
   settings object).

## Verification done (no CI run — parent pushes)

- All touched XML parses (ElementTree). One catch found and fixed
  during verification: `fragment_stremio_discover.xml` needed
  `xmlns:app` added for the new `app:backgroundTint` attributes.
- Every LinearLayout in touched layouts declares
  `android:orientation`; no `android:tint` anywhere in them.
- Every new reference resolves in the tree: colors
  `extButtonBackground` / `extTabUnselected` / `extRippleLight`
  (defined in colors.xml, 13 layout references), drawables
  `ic_sheet_download` / `ic_baseline_check_24` /
  `ic_baseline_system_update_24` / `rounded_dialog`, styles
  `AlertDialogCustom` / `AppBottomSheetDialogTheme`, strings
  `sky_plugin_state_installed` / `sky_settings_providers`,
  `com.google.android.material.R.id.design_bottom_sheet`.
- No unthemed `AlertDialog.Builder(...)` remains in the four
  pages' fragments.
- Kotlin delimiter balance verified on all six touched Kotlin
  files. (SkyRepository's raw `[` count differs by one due to the
  pre-existing `"["` string literal at the JSON-sniffing line —
  code brackets balance; untouched by 8.82.)
- Removed/renamed references checked: `PluginHolder.action` type
  change matches the layout; the old `h.action.setText(...)` block
  is gone; no other code referenced the browse-row text button.

## H — Redirect-chain cookies (parent, from the user's cookie hint)

Netmirror's session handshake (POST /verify.php) sets its t_hash_t
cookie on the 301 redirect response; the plugin reads it from the
response's set-cookie headers, but the bridge only exposed the
FINAL response's cookies, so the handshake always failed in our
app while working in SkyStream. SkyHostBridge (SkyEngine.kt) now
runs each call through a derived client whose network interceptor
collects Set-Cookie values from every redirect hop (final hop
included) and exposes the merged list to the plugin (the host jar
is fed from the same list). Spike verification with the fix:
Netmirror search returned 13 results and load returned a full
series (10 episodes) where everything previously failed at the
handshake; home succeeded on retry (the token caches in-plugin).
