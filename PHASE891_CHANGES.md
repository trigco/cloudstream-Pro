# Phase 8.91 — Feed categories from provider sections + customisation

His idea (2026-10-29 20:51, approved 20:55): "আমাদের হাতে তো
ক্যাটাগরির অভাব হবে না just feed এ মিক্স করতে পারলেই চমৎকার" —
the providers already curate exact homepage categories
(MovieBox 39 shelves, MovieLinkBD 18, Rtally 8, Mlsbd 7, and the
Netflix / Prime Video / Hotstar / Disney plugins build theirs
inside getMainPage). Use THOSE as the Feed's category source
instead of metadata guessing alone, and reuse the category
customisation idea for the Feed. Metadata (8.88) stays as the
fallback and keeps owning the rating-based rows.

## 1. Section provenance (FeedViewModel)

The pool build used to flatten `HomePageList.list` and throw
the shelf name away. Now:

- `FeedEntry` gains `sections: List<String>` — the RAW shelf
  names the item arrived under. An item shelved by several
  providers/sections carries all of them.
- `buildFeed` pairs every item with its shelf name before the
  Family-Mode item filter and the per-provider take(40).
- Every dedupe point now UNIONS provenance instead of dropping
  the later copy: the round-robin interleave (first slot kept,
  sections merged), the pool append merge, and the
  pull-to-refresh prepend merge (`unionSections`).
- Search-built entries (searchAll, liked boost) have no
  sections — search results were never shelved.

## 2. Canonical mapping (new: FeedSectionTags)

Raw names are normalised (lowercase, whitespace collapsed,
trailing punctuation stripped) and mapped, rules in order,
contains-based where safe:

| Raw shelf name(s) | Canonical tag |
| --- | --- |
| contains "k/j/c" / "kjc", or Korean+Chinese together | Asian Drama (mixed bucket — never mislabelled K-Drama) |
| contains "trending" / "featured" | Trending |
| contains "top series" ("Top Series This Week") | Top Series |
| contains "ongoing" ("Ongoing Series") | Ongoing Series |
| "bangla" + "dubbed" | Bangla Dubbed (kept distinct from Bangla — volume allows it) |
| contains "dual audio" | Dual Audio |
| contains "south indian" | South Indian |
| contains "korean", "k-drama", "kdrama", "k drama" | K-Drama |
| contains "chinese", "c-drama", "cdrama", "c drama" | C-Drama |
| contains "japanese", "j-drama", "jdrama", "j drama" | J-Drama |
| contains "turkish" | Turkish Dizi |
| contains "thai" | Thai Drama |
| "bollywood", "hindi movie…", bare "hindi", Indian+movie ("Hindi Movies", "Indian (Movies)") | Bollywood |
| Indian+drama ("Indian Drama") | Indian Drama |
| Indian+series ("Indian (Series)") | Indian Series |
| "hollywood", bare "english", contains "usa" ("USA (Movies)") | Hollywood |
| contains "bangla" / "bengali" ("Bangla", "Bangla Movies") | Bangla |
| contains "anime" ("Anime Zone"), bare "animation" | Anime |
| contains "kids" | Family |
| contains "reality" | Reality TV |
| contains "wwe" / "wrestling" | WWE |
| exact genre name, or genre + "Movies"/"Series" tail ("Action", "Action Movies") | that genre (all 18; "Sci Fi"/"Science Fiction" → Sci-Fi) |
| anything else | passthrough — the provider's own label as its own tag |
| "Recently Updated", "All Movies", "All Web Series", "All categories", bare "Movies"/"Series"/"Tv Shows", "Home", "Categories" | dropped as tags (items stay in For You) |

Passthrough is deliberate: a provider's own label is still
curated data (ShortTV, FightZone…), and the settings page
below lets the user hide whatever they don't want.

## 3. Rows v2 + chips (FeedFragment)

One shared rule, now union: a simple tag matches an entry when
its provider sections claim it OR the old metadata rule does
(sections read first — exact data wins ties by construction).

- **Trending is section-based**: when ≥4 pool items sit on a
  provider Trending shelf, the row IS that shelf (pool order);
  otherwise the 8.90 pool-head fallback remains.
- **"Ongoing Series" exists now** — provider-labelled, solving
  the 8.90 deviation honestly (nothing faked from metadata).
- Slot order: 🔥 Trending → ⭐ Top 250 → 🏆 Must-watch Drama →
  ✨ Must-watch Series → **section-tag rows in the user's
  order** → ⚡/🔥/🆕 Top & New Release region rows (rating
  world) → metadata-fallback rows ONLY for smart categories /
  genres with no section data at all (Emotional rides here
  unless a provider shelves it). The 8.90 ≥80% region-variant
  dedupe and the ≥4-items / 12-row / 15-per-row caps are
  unchanged.
- Chips use the same vocabulary and order (section tags in the
  user's order between the curated tags and the metadata
  remainder); chip dimming now reads the same memoised
  tagItems for every tag kind.

## 4. Feed Categories settings (Settings → Content)

New row under Categories in the Content group →
`FeedCategorySettingsFragment` (nav destination
`navigation_feed_categories`):

- One row per canonical tag: every known tag (mapping table +
  smart categories + genres), the derived tags (Top 250,
  Must-watch pair, Top/New Release rows, Emotional), and any
  passthrough label discovered in the current pool.
- Provider-section tags show their live pool count ("NN in the
  current Feed"); section tags with no pool data are greyed
  (0.45 alpha) with "Not in the current Feed".
- Each row: MaterialSwitch (show/hide) + drag handle
  (ItemTouchHelper, handle-driven, UP/DOWN). Both persist
  immediately to prefs `feed_category_prefs_v1` (hidden = string
  set, order = JSON array).
- The Feed honours it: hidden tags produce no row and no chip;
  the saved order drives the section-row block (and the section
  chips); tags the order doesn't mention append by item count.
  Default: nothing hidden, order = FeedSectionTags
  .CANONICAL_ORDER.
- No dialogs were needed, so the 8.89 sheet/dialog standard
  had nothing to apply to here; the switch is the same
  MaterialSwitch the settings screens already use.

## 5. Netflix / Prime Video / Hotstar / Disney — do their sections reach the pool?

Yes, by construction, verified statically: the custom-list
"tap to load" fetch (`HomeCustomLists.fetchDynamicCategories`)
reads those plugins' section names from the very same call the
Feed pool makes — `APIRepository(api).getMainPage(page, null)`
over every provider with `hasMainPage`. Those plugins declare
a single blank-named mainPage entry, so they pass the pool's
`hasMainPage` filter, and `getMainPage(page, null)` walks all
of an api's declared pages exactly like Home's own load. Their
shelves therefore land in the pool WITH provenance and surface
as canonical/passthrough rows and chips, same as MovieBox's.
SkyStream plugins too (SkyPluginProvider returns named
HomePageLists). Caveat: if such a plugin's getMainPage ever
returns empty without an interaction only its own UI performs,
its items are absent from the pool entirely — identical to
Home; 8.91 adds no Feed-specific fetch path.

## Deviations / notes

- The settings list shows exact per-tag counts ONLY for
  provider-section tags. Derived/metadata tags have no cheap
  per-tag count outside FeedFragment's full rule set;
  duplicating that ruleset in the settings screen would risk
  the two diverging, so their rows are listed plain (switch +
  drag still apply — FeedFragment honours hidden for every
  tag, and the section block's order).
- The saved order drives the section-row block; the fixed
  slots (Trending / Top 250 / must-watch head, Top / New
  Release block) keep their curated positions, per the
  approved slot order.
- A shelf named "Animation" canonicalises to Anime (per the
  approved mapping), so the Animation genre row remains
  metadata-only.
- FeedEntry is a data class, so equality now includes
  sections; all dedupe in the pool is by URL explicitly, and
  the differ rebinding on a provenance-only change is
  harmless.
- Adult-labelled shelves are still dropped whole at ingestion
  under Family Mode (8.51 behaviour, untouched); passthrough
  labels are the providers' own text, unedited.
- TV untouched; Filters sheet untouched; player untouched.

## 8.91b — CI fix (run #270)

CI run #270 failed with one compile error: `TagsAdapter` in
`FeedCategorySettingsFragment.kt` had a primary constructor
taking `MutableList<TagRow>` plus a secondary taking
`List<TagRow>` — identical after JVM type erasure (platform
declaration clash). Fix: secondary constructor removed; the
single call site now passes `buildRows().toMutableList()` to
the one remaining `MutableList<TagRow>` constructor. Behaviour
unchanged (drag-reorder still mutates the list).
