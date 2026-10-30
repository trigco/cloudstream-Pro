# Phase 8.98 — Cast rounding, opposite-side HUD, watch animation + trailer, details skeleton, year filter, sheet rollout finished

Base: sky-bridge @ cd41bc65 (8.97). One commit. No merges.

Device evidence behind the batch (8.97 build, his screenshots
14:53–14:58 + notes): Info sheet cast photos square-cornered;
brightness/volume rails hidden under the gesturing hand; watch
page video static while scrolling; no trailer on the watch page;
details page skeleton = only episode rows shimmering; Filters
Year tab shows only "All"; and his question — is the Filters
sheet style (SheetChrome, 8.89) actually everywhere yet?

## Job 1 — Info sheet cast photos rounded (FeedInfoSheet.kt)

The Starring grid cells (filmo_item.xml, shared layout) showed
square photos. Home rounds its posters with a MaterialCardView
`cardCornerRadius = @dimen/home_poster_radius` (13dp, 8.84).
The sheet's SheetCastAdapter now applies that same 13dp radius
as an outline clip (ViewOutlineProvider + clipToOutline) on the
cell's poster frame in onCreateViewHolder — the photo and the
skeleton placeholder behind it round identically. The Actor
page's own filmography adapter (same layout, different
adapter) is deliberately untouched; name/character text
unchanged.

## Job 2 — Gesture HUD on the OPPOSITE side (fragment_feed_watch.xml)

The 8.97 rails stood on the same side as the gesture, so the
hand covered them (his note). Only the holders' screen sides
swapped: the brightness holder (feed_gesture_left) now renders
on the RIGHT edge, the volume holder (feed_gesture_right) on
the LEFT. Gesture zones, icon/%/bar content, 1.5 s + 300 ms
fade — all unchanged. (Holder ids keep their 8.97 names; the
Kotlin doc comment says so explicitly.)

## Job 3 — Watch page: collapsing player with spring (FeedWatchFragment.kt)

Wired to the page's actual scroll container: the Up next
RecyclerView (the only scrolling content under the player;
the title/meta/pills column above it is fixed). Mechanics:

- Full height = the box's natural 16:9 height; compact height
  = 45% of full (chosen).
- A scroll listener maps accumulated dy onto a collapse
  amount (0..full−compact): scrolling up shrinks the player,
  scrolling down restores it, and reaching the list top
  forces full height.
- On scroll IDLE, a mid-way collapse snaps to the nearer end
  with a ValueAnimator + OvershootInterpolator(1.1) — the
  value overshoots slightly past the end state before
  settling (the "ঠেকে গেলে হালকা স্প্রিং" bounce). A new
  drag cancels the snap.
- Height ownership: after first measure, the box's
  ConstraintLayout dimensionRatio is nulled in code and the
  height is written directly (with the ratio live, a fixed
  height would let ConstraintLayout re-derive the WIDTH from
  the ratio and narrow the video). Re-applied on every view
  creation (a recreated view brings the XML ratio back).
- Writes are change-guarded and scroll-driven only — no
  frame loop (8.97 ANR lesson). A fresh Up next list resets
  the collapse to full.
- Playback is never re-attached: only the box's layout height
  moves (PlayerView resize_mode=zoom crops), so position,
  state, the middle-zone minimise drag, double-tap seek and
  the Job 2 gesture zones are all unaffected.

## Job 4 — Trailer on the watch page (FeedInfoSheet.kt + FeedWatchFragment.kt)

The sheet's trailer code path is now shared, behaviour
unchanged in the sheet:

- FeedInfoSheet.buildTrailerSlot(ctx) builds the identical
  16:9 slot (rounded 16dp outline clip, backdrop image, hero
  scrim, centred 52dp play badge, "Trailer" label) and
  returns TrailerSlotViews(slot, banner, image); the sheet's
  show() uses it with its own layout params as before.
  playTrailer / extractTrailerLink are public now (were
  private); the sheet calls them exactly as before.

Watch page: the slot is inserted into the title box between
the action pills and the "For You" header. syncTrailer()
runs from updatePlaybackUi and binds from
FeedPlayer.currentPage for the current url — the sheet's own
data path: banner art = backgroundPosterUrl ?: posterUrl ?:
currentPoster, visible only when page.trailers is non-empty.
Tap → FeedInfoSheet.playTrailer (pauses the feed video,
swaps the banner for the inline player in the same slot,
restores the banner if extraction fails). Item change or
onDestroyView releases the trailer player and resets the
slot to banner-only/hidden.

## Job 5 — Details page: full-page skeleton (fragment_result.xml + ResultFragmentPhone.kt)

result_loading's content replaced: it no longer shows the
upstream poster + lines + episode rows (which reads as
"only episodes shimmer"). The new skeleton mirrors the 8.86
page inside the same ShimmerFrameLayout (same shimmer attrs):

- 280dp backdrop block (result_top_holder's height)
- content column at the page's −28dp overlap with
  result_padding sides: poster placeholder (92×138,
  rounded_image_radius) + title line + two meta chip
  placeholders + short meta line
- Play + Download pill placeholders (52dp, 26dp radius)
- synopsis block (short header line + 3 lines)
- cast row (4 × 56dp circles)
- series variant only: season chip placeholders (3 × 72×32)
  + the existing loading_episode includes ×3, in a section
  with id result_loading_episodes

Variant choice: the real type hasn't loaded while the
skeleton shows, so ResultFragmentPhone picks from the best
pre-load hint — the Feed pool entry for the url (its
SearchResponse type), else TitleResolver.cachedType(url).
Movie/AnimeMovie → episode section hidden; anything else or
unknown → shown (the upstream always-episodes behaviour).
The swap to real content is the pre-existing one-step
Resource observer (resultFinishLoading visible on Success,
entrance animation untouched). TV uses fragment_result_tv.xml
— untouched.

## Job 6 — Filters Year tab (FeedFragment.kt)

Diagnosis: buildYearTab already existed, but both it and the
year filter read ONLY MetadataResolver.yearOf (the response's
own year field). His providers leave that field null on
homepage items, so the distinct-years list was empty and the
tab rendered just "All" (his screenshot).

Fix: one shared source, feedYearOf(entry) = response year,
else a year embedded in the title — "(YYYY)" first, else a
bare 19xx/20xx word, sanity-banded to 1950..next year
(titleYearOf). The tab lists feedYearOf years, distinct,
newest first, capped at 24 chips (pool years cluster recent;
the sheet scrolls regardless). The filter in
applyFeedFilters now compares feedYearOf too — tab and
filter can never disagree (the 8.96 empty-category lesson:
options built from one rule, filtered by another, select to
nothing). filterYear stays a single Int? — chips are
single-select, as before. Genre/Rating/Source untouched.

## Job 7 — Sheet-style audit + rollout finished

Standard = SheetChrome (8.89): rounded_dialog surface via
AppBottomSheetDialogTheme/AppModalStyle, 36×4 drag handle,
18sp medium-bold title, 36dp circular ✕, red primary pill.

| Sheet (phone) | Host | Status before 8.98 |
|---|---|---|
| Feed Filters | FeedFragment.showFeedFilters | ✓ standard (the reference) |
| Feed Info (details/cast/trailer) | FeedInfoSheet (SheetChrome factories) | ✓ standard |
| Selection sheets — incl. the Details ⋮ episode-options sheet (selectPopup → showBottomDialogInstant, title + icon rows) and FeedWatch "Set watch status" (showBottomDialog) | SingleSelectionHelper over bottom_selection_dialog.xml | ✓ standard (8.89: handle + title + ✕ revealed for phone BottomSheet hosts; frame = rounded_dialog via the app theme's bottomSheetDialogTheme) |
| Input sheet (nginx, avatar URL) | SingleSelectionHelper.showInputDialog / AccountHelper | ✓ standard (8.89) |
| Text sheet | showBottomDialogText | ✓ standard (8.89) |
| Provider switcher | HomeFragment.selectHomepage | ✓ standard (8.89) |
| Homepage "View All" list | HomeFragment.loadHomepageList (also QuickSearchFragment) | ✓ standard (8.89) |
| Long-press preview popup | MainActivity (bottom_resultview_preview) | ✓ standard (8.89) |
| SearchFragment provider picker | home_select_mainpage.xml | ✓ standard (8.89) |
| Sky plugin settings form | SkyPluginsFragment.showSettingsForm | ✓ standard (SheetChrome) |
| Nuvio scraper settings form | NuvioPluginsFragment.showSettingsForm | ✓ standard (8.89, SheetChrome) |
| Account switcher | AccountHelper.showAccountSelectLinear | ✓ standard (8.89) |
| App icon picker | AppIconHelper | ✓ standard (8.89) |
| **Native plugin details** | **PluginDetailsFragment (fragment_plugin_details.xml)** | **✗ handle only (8.89 deferred: layout is TV-shared)** → **CONVERTED (phone part)** |
| Loading progress sheet | ResultFragmentPhone (bottom_loading.xml) | deliberately not a chrome sheet — transient, auto-dismissing, non-interactive (8.89 decision, stands) |

Conversion (the one leftover): fragment_plugin_details.xml
gains the standard ✕ (36dp, bg_feed_circle_button, 9dp
padding, ic_baseline_close_24, id plugin_details_close_btt)
in the header row, starting gone; PluginDetailsFragment
reveals + wires it (dismiss()) only when isLayout(PHONE or
EMULATOR). TV never sees it — the 8.89 constraint is kept,
the phone rollout is finished. Content/actions identical.

Not sheets (out of scope, unchanged): AlertDialogs ride the
8.89 dialog standard via themes; showOptionSelect is a
PopupMenu anchor, not a sheet; HomeCustomListEditFragment is
a full fragment page.

## Verification

- All edited XML parses (ElementTree): fragment_feed_watch,
  fragment_result, fragment_plugin_details.
- Kotlin syntax scan (harness embeddable compiler, parse
  level): FeedInfoSheet, FeedWatchFragment, FeedFragment,
  ResultFragmentPhone, PluginDetailsFragment — 0 syntax
  errors (remaining diagnostics are the expected
  no-classpath unresolved references).
- The title-year regexes were verified byte-wise after
  writing (single backslashes in the raw strings).
- Local Gradle remains impossible on this VM — CI is the
  compile check, per the standing loop.

## Device checks that decide this build

1. Info sheet: cast photos lightly rounded like Home posters.
2. Feed player: brightness swipe (left hand) → rail on the
   RIGHT edge; volume swipe → rail on the LEFT.
3. Watch page: scroll Up next up — video shrinks gradually;
   scroll back — it grows; a half-way stop snaps with a small
   spring bounce; playback position never jumps.
4. Watch page: an item whose provider ships a trailer shows
   the 16:9 Trailer banner under the pills; tap plays it in
   place; switching items removes it.
5. Details: opening a movie and a series shows the full-page
   skeleton (backdrop/header/pills/cast; series adds season
   chips + episode rows), replaced in one step by the page.
6. Feed Filters → Year: real years from the pool, selecting
   one filters to matching items (never to an empty grid).
7. Extensions → a plugin's details sheet now has the ✕ close
   button (phone); everything else about it unchanged.
