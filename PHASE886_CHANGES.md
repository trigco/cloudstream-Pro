# Phase 8.86 — Details page, SkyStream-style

Reference: the user's SkyStream title-page screenshots ("Nayi Naveli" /
"The Paradise" / "Young Sheldon") — pure-black page, full-bleed backdrop
fading into black, poster + chip meta, big Play/Download pills, Synopsis
with Read more, genre chips, Cast row, season chips + episode cards.
This is a restyle + regroup of the existing phone details screen
(`fragment_result.xml` / `ResultFragmentPhone`); every existing
behaviour is preserved. TV (`fragment_result_tv.xml` / ResultFragmentTv)
is a separate class + binding and is untouched.

## Layout — `fragment_result.xml` (rewritten, ids preserved)

- **Backdrop**: the Phase 7–8.6 rounded-card construction is gone —
  `result_top_holder` is now full-bleed (match_parent × 280dp, black,
  no margins/elevation) with a 120dp bottom fade (`bg_result_fade`)
  inside `result_poster_background_holder`. The trailer holder
  (`result_smallscreen_holder` + `fragment_trailer`) lives in the same
  holder exactly as before. The content column overlaps the fade zone
  by 28dp (negative top margin; the column is transparent, root paints
  the page colour). Watermark badge bottom margin 12dp → 34dp so the
  overlap never covers it.
  - Removed ids: `result_top_card_wrap`, `result_top_card_frame`,
    `result_top_corner_mask` — verified zero references in all Kotlin
    (they were pure layout scaffolding for the card mask).
- **Header** (new `result_header_row`): poster visible again on phone
  (92×138dp card) with the title/meta column beside it.
  - Meta row 1: `result_meta_site` as a chip with the puzzle icon
    (`ic_sky_plugin_placeholder`), `result_meta_type` as an uppercase
    pill (`bg_result_type_chip`), `result_meta_content_rating` chip,
    `result_meta_year` with a calendar glyph (new `ic_result_calendar`).
  - Meta row 2: `result_meta_duration` (clock icon — still only when
    `durationText` exists), `result_meta_rating` (gold star via
    `drawableTint=@color/ratingStarGold`; text still the view model's
    `ratingText`), `result_meta_status`.
  - All meta views keep the `setText(UiText?)` self-hiding behaviour.
- **Play cluster** (new wrapper `result_play_area`) moved directly
  under the header: resume progress holder + next-episode button +
  movie Play/Download row.
  - `result_play_movie`: 52dp, full-pill corners (26dp), red (as
    before). `download_button` sits beside it at weight 1 (was stacked
    full-width below).
  - `result_resume_series_button` / `result_next_series_button`: same
    red-pill treatment (52dp / 26dp / white text+icon). The resume
    overlay (`progress_group`) texts and progress ring switched from
    dark-gray/black to white so they read on red.
  - Coming-soon rule preserved: the page observer now also sets
    `resultPlayArea.isGone = d.comingSoon` (the cluster used to be
    inside `result_data_holder`, which already had that rule).
- **Synopsis** (new `result_synopsis_block`): "Synopsis" heading
  (`@string/synopsis`, pre-existing), description flat on the page
  background (the `bg_details_card` surface removed), clamp 3 lines
  (was 5), new accent `result_description_toggle` ("Read more" /
  "Read less", new strings) sharing the toggle with tapping the text.
- **Info lines** (`result_vpn`, `result_info`, `result_no_episodes`)
  relocated to directly under the header (were below the cast).
  `result_coming_soon` sits above the play area.
- **Tags**: `result_tag` + `result_tag_holder` unchanged (chips still
  restyled in code after `populateChips`).
- **Cast**: new `result_cast_header` heading ("Cast", new string
  `result_cast_header`); its visibility is synced in code with
  `result_cast_items`. `cast_item.xml` already matched the reference
  (circular photos + names) — untouched.
- **Episodes section** (inside `result_data_holder`, after
  `result_add_sync`):
  - New season chips: `result_season_chips_scroll` + `result_season_chips`
    (single-selection ChipGroup), built in code.
  - New `result_episodes_header`: "Episodes" + `result_episodes_text`
    (count) left; `result_sort_button` + `result_batch_download_button`
    moved here from the old FlowLayout. Header visibility is driven by
    the episodes observer (Success only).
  - The old selectors FlowLayout keeps `result_episode_select` and
    `result_dub_select`; `result_season_button` stays in it for binding
    parity (see deviations).
  - `result_next_airing_holder`, episode shimmer and `result_episodes`
    unchanged.

## Layout — `fragment_result_swipe.xml`

- New `result_bookmark_top`: bookmark circle in the top bar (dark
  circle, next to the MORE toggle), shown on page success. It proxies
  the bookmark FAB via `performClick()` (same watch-status dialog) and
  its icon (outline/filled) mirrors `viewModel.watchStatus`. The FAB
  itself is unchanged (it also carries the watch-status label).

## Layout — episodes + download

- `result_episode_large.xml`: centered play badge (dark circle + white
  triangle, decorative/non-clickable) added over the thumbnail; the
  Phase 6A bottom watch-progress bar stays. No id changes.
- `result_episode.xml` (no-poster variant): card surface
  (`?attr/boxItemBackground`) + 64dp height so it matches the large
  cards. No id changes.
- `download_button_layout.xml`: the movie Download button is now an
  outline pill (52dp, 26dp corners, transparent fill, 1dp stroke) to
  pair with the red Play pill; `xmlns:app` added to the root (the new
  `app:` attrs require it). Also used by the standalone
  `download_button.xml` — same pill treatment applies there.

## Kotlin — `ResultFragmentPhone.kt`

- Season chips: `rebuildSeasonChips()` + observers on
  `viewModel.seasonSelections` / `viewModel.selectedSeasonIndex`.
  Chips call `viewModel.changeSeason(Int)` — the identical selection
  logic the dialog used (selected index alignment verified: both lists
  derive from the same `seasonsSelection` set order). Selected chip =
  filled `#232323` + white text; others transparent + stroke
  (`@color/bg_details_chip_stroke`) + gray text. Row shows only with
  2+ seasons.
- `resultSeasonButton` is force-hidden after every `selectedSeason`
  emission (its text + dialog click wiring remain intact).
- Episodes observer also toggles `resultEpisodesHeader`.
- Description: clamp 5 → 3; `resultDescriptionToggle` wired to the
  same expand/collapse state as tapping the text.
- Page observer: `resultPlayArea.isGone = d.comingSoon`; poster badge
  (`resultPosterBadge`) bound to `d.comingSoon`; cast header sync;
  `resultBookmarkTop.isVisible = true` on success.
- Entrance animation (once per view creation, first page Success):
  `result_poster_background` scale 1.08 → 1.0 over 600ms; header row,
  play area, synopsis block, tag group, cast row and data holder
  fade/slide up (alpha + translationY 14dp → 0, 300ms, 70ms stagger).
  `ViewPropertyAnimator` transforms only — API 23-safe.
- Watch-status observer mirrors the bookmark icon onto
  `resultBookmarkTop`.
- Imports added: `android.graphics.Color`,
  `androidx.core.content.ContextCompat`,
  `com.lagradost.cloudstream3.utils.UiText`.

## New resources

- Drawables: `bg_result_fade.xml`, `bg_result_type_chip.xml`,
  `bg_result_poster_badge.xml`, `ic_result_calendar.xml` (Material
  "event" glyph).
- Strings (grep-verified absent before): `result_cast_header`,
  `result_read_more`, `result_read_less`. Headings reuse the existing
  `@string/synopsis` and `@string/episodes`.

## Deviations / honesty notes

1. **Poster status badge**: SkyStream's "Recently Added" / "New
   Season" badges have NO equivalent signal in `LoadResponse` /
   `ResultData` — nothing in the data says a title is recently added
   or has a new season. The badge view exists on the poster but is
   driven only by the one honest status flag available,
   `comingSoon` (text = `@string/coming_soon`). Otherwise hidden.
   Nothing was invented.
2. **Season dialog button suppressed on phone**: `result_season_button`
   remains in the layout and fully wired (text + dialog), but is
   force-hidden; the chips are the phone's season selector. TV is
   unaffected (separate layout/class).
3. **Recommendations stay in the swipe end panel** (existing
   click-through + provider filter behaviour preserved); they were
   not inlined as a "More Like This" row.
4. **Rating text format unchanged** (`ratingText`, e.g. "Rated: 8.5"):
   only the gold star icon was added beside it — the view model's
   format string was not touched.
5. Relocated, not dropped: provider info / VPN / no-episodes lines
   moved under the header; next-airing, sync (`result_add_sync`),
   dub + episode-range selectors all remain in the episodes section.
6. The FAB's long-press / shrink-on-scroll behaviours, the top-bar
   collapsed actions row (Phase 8B), trailer handling and the logo
   (`bindLogo`) flow are untouched. The backdrop zoom animates the
   poster ImageView only, so it cannot fight the trailer's holder
   alpha animation in `loadTrailer`.

## Verification performed

- All touched XML parses (incl. the `xmlns:app` fix above).
- Active-id diff (comments stripped) before/after: `fragment_result.xml`
  removed only the 3 unreferenced wrapper ids listed above, added the
  9 new chrome ids; `fragment_result_swipe.xml` +`result_bookmark_top`;
  episode + download layouts: zero id changes.
- Zero remaining references to the removed ids in Kotlin/layouts.
- Kotlin delimiter balance verified programmatically on
  `ResultFragmentPhone.kt`.
- Lint traps checked: no `android:tint` (all `app:tint`), every new
  LinearLayout declares `android:orientation`, no code between class
  annotations, no `${` in raw strings, new strings grep-verified.
