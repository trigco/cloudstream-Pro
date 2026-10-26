# Phase 8.44 — Custom Home Lists (PR #7, branch feed-section)

His idea (2026-10-26, chosen "build it now as 8.44"): home
categories should be fully customizable — Settings fetches every
provider's categories; the user builds a named list mixing
categories from DIFFERENT providers; Home switches to that list.

Design note: ~/workspace/cloudstream/CUSTOM_HOME_LISTS_PLAN.md

## Pieces

- **Model/storage** (`ui/home/HomeCustomLists.kt`): a list is
  (id, name, ordered entries of (apiName, pageName)); the page
  index/data is resolved against the provider at load time, so
  saved lists survive provider updates. JSON in default
  SharedPreferences; the active list id is stored separately.
  `allProviderCategories()` enumerates every installed
  provider's declared main-page categories without fetching
  content.
- **Manager** (`HomeCustomListsFragment`): the user's lists with
  category counts, Edit / Use on Home / Delete; "＋ New list".
- **Builder** (`HomeCustomListEditFragment`): name field; the
  list's entries on top (drag to reorder, ✕ removes); below,
  every provider's categories grouped by provider with ＋ Add.
- **Home integration** (`HomeViewModel`): `loadCustomList`
  builds rows entry-by-entry, each through its OWN provider
  (a dead provider's row is skipped, never blocks the rest);
  rows keyed by a unique display name — a page name that appears
  from 2+ providers gets a " • Provider" suffix; expand
  (load-more) routes per row through `customRoutes`; the hero
  is enriched per provider from the rows' posters. While a
  custom list is active, `loadAndCancel` routes every home
  load to it (signature-checked, so tab switches don't reload
  but Settings edits do); leaving happens only via the
  switcher. Default per-provider home is untouched when no
  custom list is active; the saved-order sort and hidden
  categories apply only to the default home.
- **Switcher** (`HomeFragment`): the provider FAB now opens a
  chooser — Providers… / each custom list (✓ on the active
  one) / Manage custom lists….
- **Settings**: Settings → UI → "Custom Home Lists" opens the
  manager (global nav action shared with the switcher).
