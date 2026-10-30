# Phase 8.99d — Floating blob nav + Lucide icon sweep

User's settled orders (2026-10-30 evening): (1) the phone bottom bar
becomes the floating bar from the approved preview
(`mockups/floating-nav-preview.html` v6) — floating dark pill, the
tapped item expands with an organic-blob highlight and its NAME slides
in, the rest stay icon-only; highlight colour = GREY (tonal grey
family). (2) Icon pack = LUCIDE applied EVERYWHERE, including
CloudStream's own custom SVGs; where Lucide has no equivalent, a
hand-designed modern Lucide-style SVG (24 grid, 2-unit stroke, round
caps/joins) replaces it.

## Job 1 — Floating nav (phone only; TV byte-identical)

### Design (mirrors preview v6)
- Pill: near-black `#141414` fill, 1dp `#2C2C2C` stroke, 40dp corners
  (`bg_floating_nav_pill.xml`), 6dp inner padding, 8dp elevation.
- Items: 50dp tall; collapsed width 50dp (icon only, tint `#A8A8A8`);
  the selected item expands to `13 + 24 + 8 + label + 13` dp with a
  white icon + white bold 14sp label.
- Highlight: hand-drawn organic blob vector (`bg_nav_blob.xml`,
  fill `@color/extButtonBackground` #262626 — the settled GREY; red
  stays Play-only). The blob is positioned behind the selected item
  every layout pass and stretches with the item's animated width,
  which is what makes it read organic. It settles to a pill.
- Animation: one 420ms `ValueAnimator`, `OvershootInterpolator(1.1)`
  (spring-like settle), driving all item widths together; the incoming
  label fades/slides in over the last 70% of the animation, the
  outgoing label fades fast.
- Bar footprint: 6dp top breathing room + 62dp pill + 14dp bottom
  lift (+ system-bar bottom inset on API 30+, see audit) = 82dp +
  inset, centred horizontally, floating above the content.

### Implementation (why it is built this way)
`MainActivity` binds `ActivityMainBinding` even onto the TV root, and
the TV layout carries the same ids — so `activity_main.xml`'s
`nav_view` MUST remain a `BottomNavigationView` or TV binding breaks.
Therefore:

- `activity_main.xml` (phone): `nav_view` gains
  `android:visibility="invisible"` and nothing else. It stays in the
  layout with its unchanged 80dp box, so (a) every existing listener,
  `setupWithNavController`, the checked-state bookkeeping in
  `updateNavBar`, the icon-pop animation and the deep-link
  `selectedItemId` write keep working verbatim, and (b)
  `cast_mini_controller_holder` (constrained above `nav_view`) and
  `fixSystemBarsPadding` keep their exact geometry.
- The visible bar is a NEW `FloatingNavView`
  (`ui/FloatingNavView.kt`), created in code in the phone inflate
  branch only and added to the root `ConstraintLayout` (bottom-centred).
  Its id comes from a new `res/values/ids.xml` entry
  (`floating_nav_view`), so no view-binding field is generated and the
  TV path never sees it. TV layout file: untouched, byte-identical.
- Wiring (`MainActivity.setupFloatingNav`): a tap/scrub on the
  floating bar is pushed into the holder
  (`navView.selectedItemId = id`) and the TRUE result is read back
  (`syncSelection(navView.selectedItemId)`) — the same read-back
  semantics `setupNavScrub` relies on. If the 400ms debounce listener
  rejects a change, the bar keeps showing the old tab. The reselection
  side effect was extracted verbatim into
  `MainActivity.onNavItemReselected` and is now shared by the BNV, the
  rail, and the floating bar (Feed re-tap = scroll to top).
- Selection also follows the `NavController` (back-stack pops, deep
  links) via `FloatingNavView.setupWithNavController`, using the same
  downloads/settings destination groups as `updateNavBar`'s checked
  mapping.
- Visibility: `updateNavBar` now drives the floating bar on the phone
  layout with the exact same `isNavVisible && !isLandscape()` result
  (destination visibility lists untouched) and pins the holder
  invisible; on TV (holder present, no floating view) the original
  line runs. `setupFloatingNav` re-runs `updateNavBar` once for the
  start destination, since the driving listener was registered before
  the view existed.
- Scrub: re-implemented inside `FloatingNavView` (the invisible holder
  cannot receive touches): same threshold (8dp), same semantics —
  crossing into another item selects it through the owner, and a scrub
  that changed the selection consumes the release so no click fires.
- Long-press scroll-to-top: the existing loop now also iterates the
  floating bar (its item roots carry the destination ids).
- Library icon swap: the sync-provider icon observer now also calls
  `floatingNavView.setItemIcon(R.id.navigation_library, icon)`.
- Home chrome auto-hide: `HomeFragment.applyHomeChromeVisibility` and
  the `onDestroyView` reset now target `R.id.floating_nav_view` first
  and fall back to `R.id.nav_view` (TV/landscape). The shift uses the
  view's own height, so the taller floating footprint slides fully
  away exactly like the flat bar did.

### Inset audit (per screen)
Fragments span the full screen height (`nav_host_fragment` bottom is
constrained to the PARENT bottom), so per-screen bottom paddings are
the only clearance — same as under the flat bar. The flat bar's
obstruction was its top edge at 80dp + inset (API 30+). The floating
pill's top edge sits at 76dp + inset (62dp pill + 14dp lift). **The
floating bar obstructs 4dp LESS than the flat bar did, so no screen's
compensation needed to change:**

| Screen | Bottom clearance today | vs pill top (76dp + inset) |
|---|---|---|
| Home (`fragment_home.xml`) | 92dp | clears by 16dp |
| Feed (`fragment_feed.xml`) | 80dp | clears by 4dp (tightest) |
| Search (`fragment_search.xml`) | 92dp (three lists) | clears by 16dp |
| Library (`fragment_library.xml`) | 112dp / 132dp | clears |
| Downloads (`fragment_downloads.xml`) | 192dp | clears |
| Settings (`main_settings.xml`) | 92dp | clears |
| Feed watch / Feed search / category pages, player, details | bar hidden by the existing visibility rules | n/a |

On API 30+ the floating bar adds the system-bar bottom inset to its
lift (mirroring `fixSystemBarsPadding`'s growth of the flat bar), so
the +inset terms cancel in the comparison on every API level. The
holder keeps its 80dp box, so the cast mini-controller's anchor is
unchanged.

## Job 2 — Lucide icon sweep

### Inventory
- 408 drawable files under `res/drawable*` in total.
- 257 are `<vector>` files (255 unique names; `ic_banner_foreground`
  and `ic_launcher_foreground` exist in both `drawable/` and
  `drawable-v24/`). All 257 are accounted for in the table below:
  **206 replaced, 51 excluded.**
- Non-vector files (151) are untouched by design: 91 `<shape>`
  backgrounds/dividers, 16 `<selector>` state lists (they reference
  the replaced vectors, so the selector PAIRS inherit the sweep —
  see below), 11 binary images, 11 `<ripple>`, 11 `<layer-list>`,
  4 `<rotate>`, 3 `<inset>`, 1 `<scale>`
  (`circular_progress_bar_small_to_large`), 1 `<solid>`-root shape
  (`bg_button_outlined`), and 2 `<animated-vector>`
  (`play_to_pause`, `pause_to_play` — a deliberate exception: their
  morph paths are a matched pair of interpolated endpoints, so their
  geometry is retained for morph compatibility; they are play/pause
  family glyphs whose endpoints must stay in register).

### Conversion rules applied
- Vector CONTENT replaced in place: same filename, same references,
  same width/height/`android:name`, same root tint/alpha and
  `autoMirrored` (15 RTL-mirrored files kept it). Only the viewport
  (normalised to 24) and the path geometry/styles changed.
- Stroke icons: Lucide SVG geometry converted 1:1 (path/circle/rect/
  line/polyline/polygon/ellipse → pathData), stroke width 2, round
  caps/joins, fill none. The colour ROLE was carried from the
  original: wherever the original drew with a dominant fill (or the
  glyph is tint-driven), that token became the stroke colour; a root
  `android:tint` was kept as-is and the paths use white, exactly like
  the originals did.
- Filled family (play/pause/skip/star/heart/bookmark/bell/check
  glyphs): Lucide geometry filled, stroke dropped; straight
  single-segment elements (e.g. skip-forward's bar) were expanded to
  filled rects so they survive without a stroke. Semantic colours
  were kept (star yellow via the original colour token, resume
  arrow's black, cast avatar's grey).
- Selector pairs stayed valid pairs: e.g. `home_icon_selector`
  (filled state = hand-filled Lucide house, outline state = Lucide
  house stroke), `library_icon_selector` (filled = solid book spines,
  outline = Lucide library stroke), `settings_icon_selector`
  (outline = Lucide sliders-horizontal at stroke 2; "filled" state =
  the same geometry at stroke 2.75 — filling sliders reads wrong, so
  the state reads as weight instead), favourite/star/bookmark pairs
  follow the same filled-vs-stroke rule.
- Hand-designed customs (no Lucide equivalent), all drawn on the
  24 grid at 2-unit stroke with round caps/joins in Lucide's language:
  phone-frame download states (done/error/load/pause — used by both
  the `download_icon_*` and `rd*` sets), block-digit seek ±30
  (Lucide rotate arc + block "30"), HD badge (rounded rect + stroke
  letters), sort (lines + descending arrow), system-update handset,
  resume-arrow-2 (filled play + bar), cast avatar (filled person),
  metadata overlay (two overlapping rounded rects), seekbar preview,
  screen rotation, voice-over-off (person + waves + slash),
  offline pin (Lucide pin + filled centre dot), batch download
  (arrow + tray + ground line), arrows-input (opposing arrows),
  speed-up (double chevron), radio checked/unchecked, and the two
  progressive 7-level families (sun_1..7 and
  ic_baseline_brightness_1..7) that ramp disc → short rays → long
  rays and end at the verbatim Lucide sun.
- Exclusions (51, each listed in the table): brand/provider marks
  (AniList, Kitsu, MAL, Simkl, SubDL, Discord, GitHub, AnimeSkip),
  the CloudStream cloud logo family and launcher/banner artwork —
  brand logos are never redrawn — and multi-colour illustrations
  (monke_* set, benene, feed_empty_art, default_cover,
  profile_preset_*).
- Care taken: no `android:tint` was added to any ImageView (the sweep
  only edits drawable files); no `?attr/*` fill/stroke colours were
  introduced (colour tokens are the ones the originals already used);
  CRLF files kept their line endings (the sweep rewrites vector files
  wholesale in their original ending style; no Kotlin/XML layout
  files with CRLF were touched by hand edits).

### Complete icon mapping table
Treatment key: **replaced** = content swapped (detail names the
Lucide icon + stroke/filled mode, or the custom design); **excluded**
= left byte-identical (detail gives the reason). "role" in a replaced
detail is the colour token the glyph draws with, carried from the
original.

| Resource | Treatment | Detail |
|---|---|---|
| `drawable-v24/ic_banner_background` | excluded | launcher banner background artwork |
| `drawable-v24/ic_banner_foreground` | excluded | launcher/banner brand artwork |
| `drawable-v24/ic_launcher_foreground` | excluded | launcher icon foreground (brand cloud) |
| `drawable/animeskip` | excluded | AnimeSkip third-party service logo (multicolour) |
| `drawable/arrow_and_edge_24px` | replaced | Lucide expand (stroke) [role @android:color/white] |
| `drawable/arrow_or_edge_24px` | replaced | Lucide shrink (stroke) [role @android:color/white] |
| `drawable/arrows_input_24px` | replaced | custom Lucide-style (arrows-input) [role #FFFFFF] |
| `drawable/baseline_description_24` | replaced | Lucide file-text (stroke) [role #FFFFFF] |
| `drawable/baseline_downloading_24` | replaced | Lucide circle-arrow-down (stroke) [role @android:color/white] |
| `drawable/baseline_fullscreen_24` | replaced | Lucide maximize (stroke) [role @android:color/white] |
| `drawable/baseline_fullscreen_exit_24` | replaced | Lucide minimize (stroke) [role @android:color/white] |
| `drawable/baseline_grid_view_24` | replaced | Lucide layout-grid (stroke) [role #FFFFFF] |
| `drawable/baseline_headphones_24` | replaced | Lucide headphones (stroke) [role @android:color/white] |
| `drawable/baseline_help_outline_24` | replaced | Lucide circle-help (stroke) [role @android:color/white] |
| `drawable/baseline_list_alt_24` | replaced | Lucide clipboard-list (stroke) [role @android:color/white] |
| `drawable/baseline_network_ping_24` | replaced | Lucide activity (stroke) [role #FFFFFF] |
| `drawable/baseline_notifications_none_24` | replaced | Lucide bell (stroke) [role #FFFFFF] |
| `drawable/baseline_remove_24` | replaced | Lucide minus (stroke) [role @android:color/white] |
| `drawable/baseline_restore_page_24` | replaced | Lucide history (stroke) [role #FFFFFF] |
| `drawable/baseline_save_as_24` | replaced | Lucide save (stroke) [role #FFFFFF] |
| `drawable/baseline_skip_previous_24` | replaced | Lucide skip-back (filled) [role #FFFFFF] |
| `drawable/baseline_stop_24` | replaced | Lucide square (filled) [role @android:color/white] |
| `drawable/baseline_sync_24` | replaced | Lucide refresh-cw (stroke) [role #FFFFFF] |
| `drawable/baseline_text_snippet_24` | replaced | Lucide file-text (stroke) [role ?attr/white] |
| `drawable/baseline_theaters_24` | replaced | Lucide clapperboard (stroke) [role #FFFFFF] |
| `drawable/benene` | excluded | multicolour monkey illustration (easter-egg art) |
| `drawable/bookmark_star_24px` | replaced | Lucide bookmark (filled) [role @android:color/white] |
| `drawable/clear_all_24px` | replaced | Lucide list-x (stroke) [role @android:color/white] |
| `drawable/cloud_2` | excluded | CloudStream brand logo artwork |
| `drawable/cloud_2_gradient` | excluded | CloudStream brand logo (gradient variant) |
| `drawable/cloud_2_gradient_beta` | excluded | CloudStream brand logo (gradient variant) |
| `drawable/cloud_2_gradient_beta_old` | excluded | CloudStream brand logo (gradient variant) |
| `drawable/cloud_2_gradient_debug` | excluded | CloudStream brand logo (gradient variant) |
| `drawable/cloud_2_solid` | excluded | CloudStream brand logo silhouette — brand marks are not redrawn |
| `drawable/default_cover` | excluded | placeholder cover illustration (large canvas art) |
| `drawable/delete_all` | replaced | Lucide trash-2 (stroke) [role @android:color/white] |
| `drawable/download_icon_done` | replaced | custom Lucide-style (phone-done) [role @android:color/white] |
| `drawable/download_icon_error` | replaced | custom Lucide-style (phone-error) [role @android:color/white] |
| `drawable/download_icon_load` | replaced | custom Lucide-style (phone-load) [role @android:color/white] |
| `drawable/download_icon_pause` | replaced | custom Lucide-style (phone-pause) [role @android:color/white] |
| `drawable/feed_audio` | replaced | Lucide audio-lines (stroke) [role @android:color/white] |
| `drawable/feed_empty_art` | excluded | multi-colour empty-state illustration |
| `drawable/go_back_30` | replaced | custom seek-30 (back) [role #ffffff] |
| `drawable/go_forward_30` | replaced | custom seek-30 (fwd) [role #ffffff] |
| `drawable/home_alt` | replaced | Lucide house (stroke) [role @android:color/white] |
| `drawable/home_icon_filled_24` | replaced | custom Lucide-style (house-filled) [role #FFFFFF] |
| `drawable/home_icon_outline_24` | replaced | Lucide house (stroke) [role #FFFFFF] |
| `drawable/hourglass_24` | replaced | Lucide hourglass (stroke) [role #FFFFFF] |
| `drawable/ic_anilist_icon` | excluded | AniList brand logo |
| `drawable/ic_banner_foreground` | excluded | launcher/banner brand artwork |
| `drawable/ic_baseline_add_24` | replaced | Lucide plus (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_arrow_back_24` | replaced | Lucide arrow-left (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_arrow_back_ios_24` | replaced | Lucide chevron-left (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_arrow_forward_24` | replaced | Lucide arrow-right (stroke) [role @android:color/white] |
| `drawable/ic_baseline_aspect_ratio_24` | replaced | Lucide proportions (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_autorenew_24` | replaced | Lucide refresh-cw (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_bookmark_24` | replaced | Lucide bookmark (filled) [role #FFFFFF] |
| `drawable/ic_baseline_bookmark_border_24` | replaced | Lucide bookmark (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_brightness_1_24` | replaced | custom progressive brightness L1 [role @android:color/white] |
| `drawable/ic_baseline_brightness_2_24` | replaced | custom progressive brightness L2 [role @android:color/white] |
| `drawable/ic_baseline_brightness_3_24` | replaced | custom progressive brightness L3 [role @android:color/white] |
| `drawable/ic_baseline_brightness_4_24` | replaced | custom progressive brightness L4 [role @android:color/white] |
| `drawable/ic_baseline_brightness_5_24` | replaced | custom progressive brightness L5 [role @android:color/white] |
| `drawable/ic_baseline_brightness_6_24` | replaced | custom progressive brightness L6 [role @android:color/white] |
| `drawable/ic_baseline_brightness_7_24` | replaced | custom progressive brightness L7 [role @android:color/white] |
| `drawable/ic_baseline_check_24` | replaced | Lucide check (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_check_24_listview` | replaced | Lucide check (stroke) [role @android:color/white] |
| `drawable/ic_baseline_clear_24` | replaced | Lucide x (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_clock_24` | replaced | Lucide clock (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_close_24` | replaced | Lucide x (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_collections_bookmark_24` | replaced | Lucide book-copy (stroke) [role @android:color/white] |
| `drawable/ic_baseline_color_lens_24` | replaced | Lucide palette (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_construction_24` | replaced | Lucide hammer (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_delete_outline_24` | replaced | Lucide trash-2 (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_developer_mode_24` | replaced | Lucide code (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_discord_24` | excluded | Discord brand mark |
| `drawable/ic_baseline_dns_24` | replaced | Lucide server (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_edit_24` | replaced | Lucide pencil (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_equalizer_24` | replaced | Lucide audio-lines (stroke) [role #5f6368] |
| `drawable/ic_baseline_exit_24` | replaced | Lucide log-out (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_extension_24` | replaced | Lucide puzzle (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_fast_forward_24` | replaced | Lucide fast-forward (filled) [role #FFFFFF] |
| `drawable/ic_baseline_favorite_24` | replaced | Lucide heart (filled) [role #FFFFFF] |
| `drawable/ic_baseline_favorite_border_24` | replaced | Lucide heart (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_film_roll_24` | replaced | Lucide film (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_filter_list_24` | replaced | Lucide list-filter (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_folder_open_24` | replaced | Lucide folder-open (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_hd_24` | replaced | custom Lucide-style (hd) [role #FFFFFF] |
| `drawable/ic_baseline_hearing_24` | replaced | Lucide ear (stroke) [role @android:color/white] |
| `drawable/ic_baseline_keyboard_arrow_down_24` | replaced | Lucide chevron-down (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_keyboard_arrow_left_24` | replaced | Lucide chevron-left (stroke) [role @android:color/white] |
| `drawable/ic_baseline_keyboard_arrow_right_24` | replaced | Lucide chevron-right (stroke) [role @android:color/white] |
| `drawable/ic_baseline_language_24` | replaced | Lucide languages (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_more_vert_24` | replaced | Lucide ellipsis-vertical (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_north_west_24` | replaced | Lucide arrow-up-left (stroke) [role @android:color/white] |
| `drawable/ic_baseline_notifications_active_24` | replaced | Lucide bell-ring (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_ondemand_video_24` | replaced | Lucide video (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_open_in_new_24` | replaced | Lucide square-arrow-out-up-right (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_pause_24` | replaced | Lucide pause (filled) [role #FFFFFF] |
| `drawable/ic_baseline_people_24` | replaced | Lucide users (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_picture_in_picture_alt_24` | replaced | Lucide picture-in-picture-2 (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_play_arrow_24` | replaced | Lucide play (filled) [role #FFFFFF] |
| `drawable/ic_baseline_playlist_play_24` | replaced | Lucide list-video (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_public_24` | replaced | Lucide globe (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_remove_red_eye_24` | replaced | Lucide eye (stroke) [role @android:color/white] |
| `drawable/ic_baseline_replay_24` | replaced | Lucide rotate-ccw (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_restart_24` | replaced | Lucide rotate-ccw (stroke) [role #e8eaed] |
| `drawable/ic_baseline_resume_arrow` | replaced | Lucide play (filled) [role #000000] |
| `drawable/ic_baseline_resume_arrow2` | replaced | custom Lucide-style (resume2) [role @android:color/white] |
| `drawable/ic_baseline_skip_next_24` | replaced | Lucide skip-forward (filled) [role #FFFFFF] |
| `drawable/ic_baseline_skip_next_24_big` | replaced | Lucide skip-forward (filled) [role #e8eaed] |
| `drawable/ic_baseline_skip_next_rounded_24` | replaced | Lucide skip-forward (filled) [role #FFFFFF] |
| `drawable/ic_baseline_sort_24` | replaced | custom Lucide-style (sort) [role #FFFFFF] |
| `drawable/ic_baseline_speed_24` | replaced | Lucide gauge (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_star_24` | replaced | Lucide star (filled) [role #FFFFFF] |
| `drawable/ic_baseline_star_border_24` | replaced | Lucide star (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_storage_24` | replaced | Lucide database (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_subtitles_24` | replaced | Lucide captions (stroke) [role @android:color/white] |
| `drawable/ic_baseline_system_update_24` | replaced | custom Lucide-style (sysupdate) [role @android:color/white] |
| `drawable/ic_baseline_text_format_24` | replaced | Lucide type (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_thumb_down_24` | replaced | Lucide thumbs-down (stroke) [role @android:color/white] |
| `drawable/ic_baseline_thumb_up_24` | replaced | Lucide thumbs-up (stroke) [role @android:color/white] |
| `drawable/ic_baseline_touch_app_24` | replaced | Lucide mouse-pointer-click (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_tune_24` | replaced | Lucide sliders-horizontal (stroke) [role @android:color/white] |
| `drawable/ic_baseline_tv_24` | replaced | Lucide tv (stroke) [role #FFFFFF] |
| `drawable/ic_baseline_visibility_off_24` | replaced | Lucide eye-off (stroke) [role @android:color/white] |
| `drawable/ic_baseline_volume_down_24` | replaced | Lucide volume-1 (stroke) [role @android:color/white] |
| `drawable/ic_baseline_volume_mute_24` | replaced | Lucide volume (stroke) [role @android:color/white] |
| `drawable/ic_baseline_volume_up_24` | replaced | Lucide volume-2 (stroke) [role @android:color/white] |
| `drawable/ic_baseline_warning_24` | replaced | Lucide triangle-alert (stroke) [role #FFFFFF] |
| `drawable/ic_battery` | replaced | Lucide battery-medium (stroke) [role #FFFFFF] |
| `drawable/ic_cast_avatar` | replaced | custom Lucide-style (avatar-filled) [role #8A8A8A] |
| `drawable/ic_cloudstream_monochrome` | excluded | CloudStream brand logo (monochrome) |
| `drawable/ic_cloudstream_monochrome_big` | excluded | CloudStream brand logo (monochrome) |
| `drawable/ic_cloudstreamlogotv` | excluded | CloudStream brand logo (TV) |
| `drawable/ic_cloudstreamlogotv_2` | excluded | CloudStream brand logo (TV) |
| `drawable/ic_cloudstreamlogotv_pre` | excluded | CloudStream brand logo (TV) |
| `drawable/ic_cloudstreamlogotv_pre_2` | excluded | CloudStream brand logo (TV) |
| `drawable/ic_dashboard_black_24dp` | replaced | Lucide layout-dashboard (stroke) [role #FF000000] |
| `drawable/ic_drag_handle_24` | replaced | Lucide grip-horizontal (stroke) [role @android:color/white] |
| `drawable/ic_favorite_filled_24` | replaced | Lucide heart (filled) [role #FFFFFF] |
| `drawable/ic_feed_chevron_right` | replaced | Lucide chevron-right (stroke) [role #FF9E9E9E] |
| `drawable/ic_feed_download` | replaced | Lucide download (stroke) [role @android:color/white] |
| `drawable/ic_filled_notifications_24dp` | replaced | Lucide bell (filled) [role #e8eaed] |
| `drawable/ic_fingerprint` | replaced | Lucide fingerprint (stroke) [role #FFFFFF] |
| `drawable/ic_github_logo` | excluded | GitHub brand mark |
| `drawable/ic_home_black_24dp` | replaced | Lucide house (stroke) [role #FF000000] |
| `drawable/ic_launcher_background` | excluded | launcher icon background artwork |
| `drawable/ic_launcher_fg_carbon` | excluded | launcher icon foreground variant |
| `drawable/ic_launcher_fg_crimson` | excluded | launcher icon foreground variant |
| `drawable/ic_launcher_fg_inverse` | excluded | launcher icon foreground variant |
| `drawable/ic_launcher_fg_midnight` | excluded | launcher icon foreground variant |
| `drawable/ic_launcher_foreground` | excluded | launcher icon foreground (brand cloud) |
| `drawable/ic_mic` | replaced | Lucide mic (stroke) [role @android:color/white] |
| `drawable/ic_network_stream` | replaced | Lucide link-2 (stroke) [role ?attr/white] |
| `drawable/ic_notifications_black_24dp` | replaced | Lucide bell (stroke) [role #FF000000] |
| `drawable/ic_offline_pin_24` | replaced | custom Lucide-style (offline-pin) [role #e3e3e3] |
| `drawable/ic_outline_account_circle_24` | replaced | Lucide circle-user-round (stroke) [role #FFFFFF] |
| `drawable/ic_outline_home_24` | replaced | Lucide house (stroke) [role @android:color/white] |
| `drawable/ic_outline_info_24` | replaced | Lucide info (stroke) [role @android:color/white] |
| `drawable/ic_outline_notifications_24dp` | replaced | Lucide bell (stroke) [role #e8eaed] |
| `drawable/ic_outline_remove_red_eye_24` | replaced | Lucide eye (stroke) [role @android:color/white] |
| `drawable/ic_outline_settings_24` | replaced | Lucide settings (stroke) [role @android:color/white] |
| `drawable/ic_outline_share_24` | replaced | Lucide share-2 (stroke) [role #FFFFFF] |
| `drawable/ic_outline_subtitles_24` | replaced | Lucide captions (stroke) [role #FFFFFF] |
| `drawable/ic_outline_voice_over_off_24` | replaced | custom Lucide-style (voice-off) [role @android:color/white] |
| `drawable/ic_player_ab_repeat` | replaced | Lucide repeat (stroke) [role #FFFFFF] |
| `drawable/ic_player_screenshot` | replaced | Lucide camera (stroke) [role #FFFFFF] |
| `drawable/ic_poster_placeholder` | replaced | Lucide image (stroke) [role #2EFFFFFF] |
| `drawable/ic_refresh` | replaced | Lucide refresh-cw (stroke) [role #FFFFFF] |
| `drawable/ic_result_calendar` | replaced | Lucide calendar (stroke) [role #FFFFFF] |
| `drawable/ic_seek_triangles` | replaced | Lucide fast-forward (filled) [role #FFFFFF] |
| `drawable/ic_settings_account` | replaced | Lucide circle-user-round (stroke) [role #FFFFFF] |
| `drawable/ic_settings_extensions` | replaced | Lucide puzzle (stroke) [role #FFFFFF] |
| `drawable/ic_settings_family` | replaced | Lucide users (stroke) [role #FFFFFF] |
| `drawable/ic_settings_general` | replaced | Lucide settings (stroke) [role #FFFFFF] |
| `drawable/ic_settings_layout` | replaced | Lucide layout-dashboard (stroke) [role #FFFFFF] |
| `drawable/ic_settings_player` | replaced | Lucide circle-play (stroke) [role #FFFFFF] |
| `drawable/ic_settings_providers` | replaced | Lucide server (stroke) [role #FFFFFF] |
| `drawable/ic_settings_update` | replaced | Lucide arrow-down-to-line (stroke) [role #FFFFFF] |
| `drawable/ic_sheet_browser` | replaced | Lucide globe (stroke) [role #FFFFFF] |
| `drawable/ic_sheet_cast` | replaced | Lucide cast (stroke) [role #FFFFFF] |
| `drawable/ic_sheet_check_double` | replaced | Lucide check-check (stroke) [role #FFFFFF] |
| `drawable/ic_sheet_copy` | replaced | Lucide copy (stroke) [role #FFFFFF] |
| `drawable/ic_sheet_download` | replaced | Lucide download (stroke) [role #FFFFFF] |
| `drawable/ic_sheet_download_mirror` | replaced | Lucide hard-drive-download (stroke) [role #FFFFFF] |
| `drawable/ic_sheet_eye` | replaced | Lucide eye (stroke) [role #FFFFFF] |
| `drawable/ic_sheet_eye_off` | replaced | Lucide eye-off (stroke) [role #FFFFFF] |
| `drawable/ic_sheet_play` | replaced | Lucide play (filled) [role #FFFFFF] |
| `drawable/ic_sheet_reload` | replaced | Lucide rotate-cw (stroke) [role #FFFFFF] |
| `drawable/ic_sheet_subtitles` | replaced | Lucide captions (stroke) [role #FFFFFF] |
| `drawable/ic_sky_plugin_placeholder` | replaced | Lucide puzzle (stroke) [role #FFFFFF] |
| `drawable/ic_switcher_radio_checked` | replaced | custom Lucide-style (radio-checked) [role @color/colorPrimaryRed] |
| `drawable/ic_switcher_radio_unchecked` | replaced | custom Lucide-style (radio-unchecked) [role #9E9E9E] |
| `drawable/ic_whatshot_24` | replaced | Lucide flame (stroke) [role @android:color/white] |
| `drawable/kid_star_24px` | replaced | Lucide star (filled) [role @android:color/white] |
| `drawable/kitsu_icon` | excluded | Kitsu brand logo |
| `drawable/library_icon` | replaced | Lucide library (stroke) [role #FFFFFF] |
| `drawable/library_icon_filled` | replaced | custom Lucide-style (library-filled) [role #FFFFFF] |
| `drawable/mal_logo` | excluded | MyAnimeList brand logo |
| `drawable/metadata_overlay_icon` | replaced | custom Lucide-style (overlay) [role ?android:attr/colorControlNormal] |
| `drawable/monke_benene` | excluded | multicolour monkey illustration |
| `drawable/monke_burrito` | excluded | multicolour monkey illustration |
| `drawable/monke_coco` | excluded | multicolour monkey illustration |
| `drawable/monke_cookie` | excluded | multicolour monkey illustration |
| `drawable/monke_drink` | excluded | multicolour monkey illustration |
| `drawable/monke_flusdered` | excluded | multicolour monkey illustration |
| `drawable/monke_funny` | excluded | multicolour monkey illustration |
| `drawable/monke_like` | excluded | multicolour monkey illustration |
| `drawable/monke_party` | excluded | multicolour monkey illustration |
| `drawable/monke_sob` | excluded | multicolour monkey illustration |
| `drawable/netflix_download` | replaced | Lucide download (stroke) [role #FFFFFF] |
| `drawable/netflix_download_batch` | replaced | custom Lucide-style (batch-download) [role #FFFFFF] |
| `drawable/netflix_pause` | replaced | Lucide pause (filled) [role #FFFFFF] |
| `drawable/netflix_play` | replaced | Lucide play (filled) [role #FFFFFF] |
| `drawable/netflix_skip_back` | replaced | Lucide rotate-ccw (stroke) [role #fff] |
| `drawable/netflix_skip_forward` | replaced | Lucide rotate-cw (stroke) [role #FFFFFF] |
| `drawable/open_subtitles_icon` | replaced | Lucide captions (stroke) [role @color/white] |
| `drawable/outline_bookmark_add_24` | replaced | Lucide bookmark-plus (stroke) [role #FFFFFF] |
| `drawable/pin_ic` | replaced | Lucide pin (stroke) [role #e3e3e3] |
| `drawable/play_button` | replaced | Lucide circle-play (stroke) [role #FFFFFF] |
| `drawable/play_button_transparent` | replaced | Lucide play (filled) [role #ffffff] |
| `drawable/preview_seekbar_24` | replaced | custom Lucide-style (seekbar-preview) [role @android:color/white] |
| `drawable/profile_preset_aurora` | excluded | profile avatar preset artwork |
| `drawable/profile_preset_bloom` | excluded | profile avatar preset artwork |
| `drawable/profile_preset_dusk` | excluded | profile avatar preset artwork |
| `drawable/profile_preset_ember` | excluded | profile avatar preset artwork |
| `drawable/profile_preset_forest` | excluded | profile avatar preset artwork |
| `drawable/profile_preset_graphite` | excluded | profile avatar preset artwork |
| `drawable/profile_preset_lagoon` | excluded | profile avatar preset artwork |
| `drawable/profile_preset_nebula` | excluded | profile avatar preset artwork |
| `drawable/question_mark_24` | replaced | Lucide circle-help (stroke) [role @android:color/white] |
| `drawable/quick_novel_icon` | replaced | Lucide book-open (stroke) [role #FFFFFF] |
| `drawable/rddone` | replaced | custom Lucide-style (phone-done) [role @color/white] |
| `drawable/rderror` | replaced | custom Lucide-style (phone-error) [role @color/white] |
| `drawable/rdload` | replaced | custom Lucide-style (phone-load) [role @android:color/white] |
| `drawable/rdpause` | replaced | custom Lucide-style (phone-pause) [role @color/white] |
| `drawable/round_keyboard_arrow_up_24` | replaced | Lucide chevron-up (stroke) [role @android:color/white] |
| `drawable/screen_rotation` | replaced | custom Lucide-style (screen-rotation) [role #FFFFFF] |
| `drawable/search_icon` | replaced | Lucide search (stroke) [role #FFFFFF] |
| `drawable/settings_alt` | replaced | Lucide settings (stroke) [role @android:color/white] |
| `drawable/settings_icon_filled` | replaced | Lucide sliders-horizontal (stroke) [role #FFFFFF] |
| `drawable/settings_icon_outline` | replaced | Lucide sliders-horizontal (stroke) [role #FFFFFF] |
| `drawable/simkl_logo` | excluded | Simkl brand logo |
| `drawable/speedup` | replaced | custom Lucide-style (speedup) [role #e3e3e3] |
| `drawable/subdl_logo_big` | excluded | SubDL brand logo |
| `drawable/sun_1` | replaced | custom progressive sun L1 [role #ffffff] |
| `drawable/sun_2` | replaced | custom progressive sun L2 [role #ffffff] |
| `drawable/sun_3` | replaced | custom progressive sun L3 [role #ffffff] |
| `drawable/sun_4` | replaced | custom progressive sun L4 [role #ffffff] |
| `drawable/sun_5` | replaced | custom progressive sun L5 [role #ffffff] |
| `drawable/sun_6` | replaced | custom progressive sun L6 [role #ffffff] |
| `drawable/sun_7` | replaced | custom progressive sun L7 [role #ffffff] |
| `drawable/sun_7_24` | replaced | Lucide sun (stroke) [role #FFFFFF] |
| `drawable/title_24px` | replaced | Lucide type (stroke) [role #FFFFFF] |
| `drawable/video_locked` | replaced | Lucide lock (stroke) [role #FF000000] |
| `drawable/video_unlocked` | replaced | Lucide lock-open (stroke) [role #FF000000] |

### Notes / watch-outs
- `sun_7_24` is a separate extra file alongside the `sun_1..7`
  family; every level-7 sun (including `ic_baseline_brightness_7_24`)
  is the verbatim Lucide sun now.
- The nav bar's six icons come from the sweep like everything else:
  house (`home_icon_outline_24`), search (`search_icon`), video
  (`ic_baseline_ondemand_video_24`), library (`library_icon`),
  download (`netflix_download`), sliders-horizontal
  (`settings_icon_outline`).
- Every replaced file's pathData was machine-validated (strict
  tokenizer) and every XML re-parsed; Kotlin files were syntax-scanned
  with the embeddable compiler (parse-level only — local Gradle
  verification is impossible on this VM, per standing rule).
