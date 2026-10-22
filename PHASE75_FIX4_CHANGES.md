# Phase 7.5 — FIX-4: Details genre/tag chips get their own pill border

## Where chip styling lives

- Phone details calls `populateChips(resultTag, d.tags)` in
  `app/src/main/java/com/lagradost/cloudstream3/ui/result/ResultFragmentPhone.kt`.
- The chips themselves are built by the shared helper
  `UIHelper.populateChips(...)`, which creates each Material `Chip` from the
  shared style `R.style.ChipFilled` (in `res/values/styles.xml`) and then sets a
  100dp corner radius — i.e. the shape was already a full pill.
- `ChipFilled` gets its colors from existing ColorStateList resources:
  - Fill: `res/color/chip_color.xml`
  - Stroke: `res/color/white_transparent_toggle.xml`
  - Text: `res/color/chip_color_text.xml`

No edits were made to `styles.xml` or `fragment_result.xml`.

## Root cause (old look)

- For an unchecked chip, `chip_color.xml` resolves to
  `?attr/primaryGrayBackground`.
- Phase 7's details card (`res/drawable/bg_details_card.xml`) also fills with
  `?attr/primaryGrayBackground` — `#121212` in the base dark theme.
- Therefore the chip fill and card fill were the same color.
- The shared stroke selector, `white_transparent_toggle.xml`, is transparent
  in the normal/unfocused state (white only when focused), despite
  `ChipFilled` declaring a 2dp stroke width.
- Net effect on the phone details page: white genre text sitting directly on
  the card, with no visible pill fill or border. In Phase 6 the same chips sat
  on the black page background, so the `primaryGrayBackground` fill read as a
  dark pill.

## Fix (new look)

The shared `ChipFilled` style is also used elsewhere (home-head chips,
TV-type chips and the TV result page via the same helper), so it was **not**
changed. Instead, `ResultFragmentPhone` restyles only the chips it just built
for the phone details page, immediately after `populateChips(...)`:

- Each child `Chip` gets:
  - `setChipBackgroundColorResource(R.color.bg_details_chip)`
  - `setChipStrokeColorResource(R.color.bg_details_chip_stroke)`
  - `chipStrokeWidth = 1dp`
- The full-pill shape is unchanged: `UIHelper.populateChips` already sets a
  100dp chip corner radius.
- Text color is untouched — `populateChips` still applies `R.attr.white`.

### New resources

Material `ChipDrawable` consumes ColorStateLists (not a shape drawable) for
its internal fill/stroke, and this project's existing chip colors already live
in `res/color/`, so the new resources follow that architecture:

- `app/src/main/res/color/bg_details_chip.xml`
  - Fill in all relevant states: `@color/boxItemBackground`
    (`#232323` in the base theme — noticeably lighter than the card's
    `#121212` `primaryGrayBackground`).
- `app/src/main/res/color/bg_details_chip_stroke.xml`
  - Stroke in all relevant states: `@color/iconColor`
    (`#B3B3B3` — an existing mid-grey), so every tag has its own visible
    rounded border on the card.

## Files changed

- `app/src/main/java/com/lagradost/cloudstream3/ui/result/ResultFragmentPhone.kt`
  — imports `Chip` + `UIHelper.toPx`; after `populateChips(resultTag, d.tags)`,
  applies the details-only fill/stroke/1dp width to each generated chip.
- `app/src/main/res/color/bg_details_chip.xml` — new.
- `app/src/main/res/color/bg_details_chip_stroke.xml` — new.
- `PHASE75_FIX4_CHANGES.md` — this file.

## Validation

- Both new XML resources parse cleanly with `xml.dom.minidom`.
- No follow-up in `styles.xml` or `fragment_result.xml` is required for this
  fix; the override is fully contained in the phone-details code path, so
  search/home/TV chip styling is unchanged.
