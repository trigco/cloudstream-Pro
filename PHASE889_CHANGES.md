# Phase 8.89 — One sheet style everywhere (+ one dialog style)

User order (2026-10-29 19:06, after praising the 8.85 Feed Filters sheet):
"এই sheet টা সব খানেই দিও" — make the Filters sheet the app's single
bottom-sheet standard. Scope extended the same evening with his
SkyStream dialog screenshots ("Select network buffer", "Title Position",
"Add Repository"): ALL dialog boxes too — ours look "ব্যাকডেটেড" (dated);
SkyStream's floating-card dialogs are the reference.

No behaviour changes anywhere: same buttons, callbacks, data and ids,
except the id notes called out per layout below.

## The sheet standard (from the 8.85 Feed Filters sheet)

- `BottomSheetDialog` with `R.style.AppBottomSheetDialogTheme`; dialog
  frame forced transparent after show (the sheet root draws the surface).
- Sheet root: `@drawable/rounded_dialog` (?attr/boxItemBackground, 20dp
  top corners, 1dp white_attr_20 stroke), padding (20, 8, 20, 20)dp.
- Drag handle: 36×4dp View on `@drawable/bg_feed_drag_handle`
  (#FF4A4A4A, 2dp radius), centred, title 14dp below it.
- Title row: optional 22dp leading icon + title 18sp white,
  sans-serif-medium + BOLD, weight 1; close = 36×36dp ImageButton
  (`ic_baseline_close_24`, white tint, `@drawable/bg_feed_circle_button`
  #FF262626 oval, 9dp padding) → dismiss. Close buttons reuse the
  existing string `switcher_close` ("Close") as contentDescription —
  no new strings in this phase.
- Primary action: full-width MaterialButton, 52dp, cornerRadius 26dp,
  backgroundTint `@color/colorPrimaryRed`, white text, topMargin 16dp.
- Secondary action: dark pill on `extButtonBackground` (#FF262626).

Defined once in code as
`app/src/main/java/com/lagradost/cloudstream3/utils/SheetChrome.kt`
(`object SheetChrome`: the constants above + `dragHandle()`,
`titleText()`, `closeButton()`, `titleRow()`, `sheetRoot()`,
`secondaryButton()`, `primaryButton()`, `transparentSheetFrame(dialog)`
factories). XML sheets reproduce the same values inline.

## The dialog standard (scope extension)

Applied through the shared themes so every AlertDialog inherits it,
mirroring the sheet language + the SkyStream reference (floating card,
~24–28dp corners, bold title, generous padding, radio rows, filled-pill
primary bottom-right, plain-text Cancel):

- Window background: NEW `@drawable/dialog_card_background` — 24dp
  corners, fill `?attr/boxItemBackground` (#1F1F1F on the dark variants,
  adaptive under Monet), 1dp `@color/white_attr_20` stroke. Replaces
  `dialog__window_background` (20dp, pure-black fill) as the
  `android:windowBackground` of `Theme.AlertDialog` and
  `AlertDialogCustom` (values/ + the values-v31 copies) and of
  `DialogOverlayPlayer` (in-player overlay dialogs; values/ + v31).
- Buttons (in `Theme.AlertDialog`, both copies; `AlertDialogCustom`
  inherits): POSITIVE = NEW `@style/RedButton.Dialog` — red filled pill
  (`colorPrimaryRed`, white text/icon, transparent stroke, the existing
  `.Dialog` inset/foreground treatment), the dialog twin of the sheet
  standard's primary action. NEGATIVE/NEUTRAL = NEW
  `@style/PlainTextButton.Dialog` — plain borderless text button (the
  reference's Cancel). Supersedes the 8.11-E2 white-pill/positive +
  outlined-pill/negative pairing in the button bar only;
  `WhiteButton.Dialog` / `OutlinedButton.Dialog` themselves are
  unchanged and still used outside the button bar.
- Radio rows: `AlertDialogCustom` now sets
  `listChoiceIndicatorSingle` = NEW `@drawable/radio_dialog_selector`,
  which reuses the 8.83 switcher vectors (`ic_switcher_radio_checked`
  red / `ic_switcher_radio_unchecked` grey), so `setSingleChoiceItems`
  dialogs (FeedWatch source/episode/speed pickers, etc.) match the
  switcher sheet. Project finding (8.11-E1): CheckedTextView draws its
  checkMark only while checked, so in practice the filled red radio
  marks the selected row — the same selected-row treatment the app's
  selection sheets already use. Multi-choice lists are unchanged.
- Content padding standard 24dp sides for custom-view dialogs: the
  code-built add-repository/addon input containers on the Sky, Nuvio
  and Stremio pages went 20dp → 24dp; `confirm_exit_dialog.xml`
  16dp → 24dp horizontal; `lock_pin_dialog.xml` padding → 24dp
  horizontal / 16dp vertical. The bare EditTexts in those dialogs
  already render as rounded outline fields via the theme's
  `AppEditStyle` (`bg_dialog_input`), matching the reference's URL
  field. Builder titles keep the M3/AppCompat dialog title appearance;
  custom-layout titles were standardised in the sheet work below.
- `DialogHalfFullscreen` (parent `AlertDialogCustom`) explicitly
  re-pins `android:windowBackground` to `dialog__window_background`:
  it hosts the long-press preview surface (TV), not a card dialog.
  `DialogFullscreenPlayer` fullscreen player pages keep the pure-black
  background unchanged.

## Sheets changed (12)

1. `bottom_selection_dialog.xml` + `SingleSelectionHelper.showDialog` —
   the shared selection sheet (player sources/links, subtitles,
   settings single-selects, library, FeedWatch, Fcast…). Material
   `BottomSheetDragHandleView` replaced by the standard handle View
   (id `drag_handle` kept, starts `gone`); title row = `text1`
   (20sp → 18sp sans-serif-medium bold, 20dp side padding) + NEW
   `close_btt` (starts `gone`). Code reveals handle + close only when
   the host is a phone BottomSheetDialog — this layout also backs
   centred AlertDialogs (the showDialog/showMultiDialog funnels used
   by GeneratorPlayer, ResultFragmentPhone, Settings*), which keep
   both hidden and inherit only the new title typography + the red
   Apply pill. `apply_btt` → red-pill overrides (backgroundTint
   colorPrimaryRed, white text/icon, transparent stroke; WhiteButton
   style kept underneath); `cancel_btt` keeps BlackButton (already the
   dark pill). Close click → `dialog.dismissSafe(this)` (same
   dismissCallback path as an outside tap). xmlns:app added to root.
2. `bottom_input_dialog.xml` — chrome rewrite: handle + title row
   (`text1` 18sp medium bold + `close_btt`, always visible — this
   layout is sheets-only: nginx input + AccountHelper avatar URL);
   EditText marginHorizontal 16→20dp; apply = red pill. Wired in
   `showInputDialog` and in AccountHelper's avatar sheet
   (`closeBtt` → dismissSafe). Companion fix: AccountHelper inserts
   its avatar preset row into this root — child index changed 1 → 2
   because the root gained handle + title row above the field.
3. `bottom_text_dialog.xml` — handle + title row (`dialog_title` →
   18sp medium bold + `close_btt`); wired in `showBottomDialogText`
   (`dialog.dismiss()` → same dismissCallback).
4. `home_provider_switcher.xml` + HomeFragment `selectHomepage` —
   handle added; title row: `switcher_title` gains sans-serif-medium +
   NEW `switcher_close_btt` (✕); the bottom "Close" TextView was
   REMOVED (id `switcher_close` → `switcher_close_btt`; HomeFragment
   listener updated to `binding.switcherCloseBtt` → dismissSafe()).
   Recycler paddingBottom 2→12dp. Logic untouched (8.83 data rules,
   8.84 72% height cap preserved).
5. Sky settings sheet (`SkyPluginsFragment.showSettingsForm`,
   code-built) — `SheetChrome.dragHandle` + `SheetChrome.titleRow`
   (plugin name); sheet root gains 8dp top padding; Save →
   colorPrimaryRed pill, Cancel keeps the #262626 chip tint, both
   cornerRadius 20dp; close wired after dialog creation; the manual
   transparent-frame code replaced by
   `SheetChrome.transparentSheetFrame(dialog)`. Not built on
   `SheetChrome.sheetRoot` (the form column carries its own 20dp side
   padding — the root would double it).
6. Nuvio settings form (`NuvioPluginsFragment.showSettingsForm`) —
   CONVERTED from AlertDialog to the standard BottomSheetDialog (Sky
   parity): `SheetChrome.sheetRoot` + dragHandle + titleRow(scraper
   name) + button row (secondaryButton Cancel + red Save pill,
   cornerRadius 20dp, marginStart 8dp). The save body is byte-for-byte
   the old positive button's (getters →
   NuvioRepository.saveScraperSettings → toast → dismiss). The form
   column's own padding reset to (0, 4, 0, 0) because the sheet root
   now provides the 20dp sides; frame via
   `SheetChrome.transparentSheetFrame`.
7. `account_select_linear.xml` +
   `AccountHelper.showAccountSelectLinear` — handle + title row
   (`switch_account` 18sp medium bold + `close_btt` → dismissSafe());
   manage-accounts button gains 20dp side margins, bottom margin
   10→16dp (BlackButton dark pill kept).
8. `dialog_icon_pack.xml` + `AppIconHelper.showAppIconPicker` —
   handle + title row (`app_icon_settings` 18sp medium + `close_btt`
   → dismissSafe()); xmlns:app added.
9. `home_episodes_expanded.xml` (loadHomepageList "View All" sheet;
   also used by QuickSearchFragment) — root background
   ?attr/primaryGrayBackground → `@drawable/rounded_dialog`; handle
   added.
10. `bottom_resultview_preview.xml` (MainActivity long-press preview,
    phone sheet) — result container background `bg_sheet_rounded_top`
    → `@drawable/rounded_dialog` (old drawable now unused, file left
    in place); handle added at the top of the result container.
11. `home_select_mainpage.xml` (SearchFragment's provider picker
    sheet — the only remaining user of this layout) — root
    restructured FrameLayout → vertical LinearLayout: handle +
    weight-1 FrameLayout wrapping the ORIGINAL ListView + bottom
    chip/button overlay. All ids preserved (listview1, apply_btt,
    cancel_btt, apply_btt_holder, tvtypes_chips_scroll); apply_btt
    gets the red-pill overrides. No title → no ✕ (standard: handle +
    surface + padding only).
12. `fragment_plugin_details.xml` (native Extensions details sheet) —
    handle ONLY. The ✕/title restyle was deliberately skipped: this
    single layout also serves the TV form factor (no phone fork
    exists; the fragment binds one layout), and its header row already
    acts as the title row with its own settings/github action icons.

## Dialogs — inventory & treatment

Inherit the new card + buttons automatically via the themes above:
every `AlertDialog.Builder(..., R.style.AlertDialogCustom)` call
(19 files) — including the SingleSelectionHelper centred-dialog
funnels, the four extension pages' dialogs (Sky add-repo / remove
confirms, Nuvio add-repo / remove / loading / check-progress,
Stremio add-addon / remove, native Extensions repo delete + the
add-repository dialog over `add_repo_input.xml`, whose
listPreferredItemPadding sides are already ≈24dp and whose fields
ride `AppEditStyle`), Settings' framework ListPreference-style
dialogs (they ride the activity `alertDialogTheme`), HomeFragment's
hide-categories multi-choice dialog, FeedWatchFragment's
single-choice dialogs (radio rows via the new indicator), MainActivity's
exit confirm (`confirm_exit_dialog.xml`, padding aligned), the PIN
dialog (`lock_pin_dialog.xml`, padding aligned), the account edit
form (`account_edit_dialog.xml`, listPreferredItemPadding ≈24dp
already), the quality-profile dialog (`player_quality_profile_dialog.xml`,
≈24dp sides already; it is a centred `Dialog`, so it gains the card
window background where its theme routes through DialogOverlayPlayer/
AlertDialogCustom), and the online-subtitles dialog
(`dialog_online_subtitles.xml`, root background @null — surface comes
from the window).

Deliberately left alone, with reasons:

- The Feed Filters sheet itself — it IS the reference; internals
  untouched.
- `bottom_loading.xml` — transient auto-dismissing progress sheet in
  ResultFragmentPhone; a handle/✕ would imply interactivity it does
  not have. Its surface already comes from the sheet theme.
- `AlertDialogCustomBlack` / `DialogFullscreen` /
  `DialogFullscreenPlayer` — fullscreen surfaces (player/info pages),
  not card dialogs.
- `AlertDialogCustomTransparent` — transparent by design (overlays).
- TV-specific dialog layouts and flows — untouched; the two shared
  surfaces that could leak the phone chrome to TV are handled above
  (`fragment_plugin_details.xml` handle-only, `DialogHalfFullscreen`
  background re-pinned). Note the shared dialog THEMES are not
  form-factor-split in this codebase (AppThemeTvOverlay does not
  override alertDialogTheme), so generic TV AlertDialogs ride the
  same card theme — no TV dialog code or layout was edited.
- `HomeCustomListEditFragment` — a full Fragment, not a sheet/dialog.
- Small confirms stay AlertDialogs (remove-repo, delete, exit…) —
  they gain the card/buttons from the theme; none were converted to
  sheets, per the user's split (sheets for big surfaces, dialogs for
  confirms).

## Files changed

New:
- `app/src/main/java/com/lagradost/cloudstream3/utils/SheetChrome.kt`
- `app/src/main/res/drawable/dialog_card_background.xml`
- `app/src/main/res/drawable/radio_dialog_selector.xml`

Kotlin:
- `utils/SingleSelectionHelper.kt` (sheet chrome wiring for the
  selection/input/text sheets)
- `utils/AppIconHelper.kt` (icon-pack close button)
- `ui/account/AccountHelper.kt` (account-select close button, avatar
  sheet close button + preset-row index 1 → 2)
- `ui/home/HomeFragment.kt` (switcher close button id)
- `ui/settings/sky/SkyPluginsFragment.kt` (settings-sheet chrome;
  add-repo container padding 24dp)
- `ui/settings/nuvio/NuvioPluginsFragment.kt` (settings form →
  standard sheet; add-repo container padding 24dp)
- `ui/settings/stremio/StremioAddonsFragment.kt` (add-addon container
  padding 24dp)

Layouts:
- `bottom_selection_dialog.xml`, `bottom_input_dialog.xml`,
  `bottom_text_dialog.xml`, `home_provider_switcher.xml`,
  `account_select_linear.xml`, `dialog_icon_pack.xml`,
  `home_episodes_expanded.xml`, `bottom_resultview_preview.xml`,
  `home_select_mainpage.xml`, `fragment_plugin_details.xml`,
  `confirm_exit_dialog.xml`, `lock_pin_dialog.xml`

Styles:
- `res/values/styles.xml` — Theme.AlertDialog (card bg, RedButton.
  Dialog positive, PlainTextButton.Dialog negative/neutral),
  AlertDialogCustom (card bg, listChoiceIndicatorSingle),
  DialogHalfFullscreen (old bg re-pinned), DialogOverlayPlayer (card
  bg), NEW RedButton / RedButton.Dialog / PlainTextButton.Dialog.
- `res/values-v31/styles.xml` — Theme.AlertDialog and
  DialogOverlayPlayer copies mirrored (bg + buttons).

## Verification

- All touched XML (16 layouts + 2 style files + 2 new drawables)
  parses clean (ElementTree). One insert-time stray-tag in styles.xml
  was caught by this check and fixed before finishing.
- Id diffs per touched layout (baselines in /tmp/ids889): only the
  intended changes — `+close_btt` in bottom_selection_dialog,
  bottom_input_dialog, bottom_text_dialog, account_select_linear,
  dialog_icon_pack; `switcher_close` → `switcher_close_btt` in
  home_provider_switcher; zero id changes in home_select_mainpage,
  home_episodes_expanded, bottom_resultview_preview,
  fragment_plugin_details, confirm_exit_dialog, lock_pin_dialog.
- No stale references: no `switcherClose` binding remains, no
  `BottomSheetDragHandleView` remains in layouts.
- No `android:tint` introduced (app:tint only); no new strings added
  (close buttons reuse `switcher_close`).
- Kotlin delimiter balance exact on all touched .kt files
  (SheetChrome, SingleSelectionHelper, AppIconHelper, AccountHelper,
  HomeFragment, SkyPluginsFragment, NuvioPluginsFragment,
  StremioAddonsFragment). MainActivity.kt shows a +1 paren count
  under naive counting but was not modified in this phase (mtime
  predates it) — a pre-existing string artefact.
- Local Gradle verification is impossible on this VM (standing
  constraint) — CI is the compile check, as with every phase.
