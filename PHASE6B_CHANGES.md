# Phase 6B — Hugeicons icon-pack conversion

All artwork was fetched live from the Iconify API (`https://api.iconify.design/hugeicons/<name>.svg`, Hugeicons free set, 24×24 viewBox, stroke style) and written INTO the existing drawable files — same filenames, so every screen that references them updates with zero layout/Kotlin changes.

## Conversion rules applied

- Each file keeps its original root `android:width/height` (e.g. `play_button.xml` stays 842dp, `ic_baseline_star_24.xml` stays 12dp) and its original root `android:tint` where one existed; viewport is now 24×24 everywhere.
- SVG stroke attributes map to `android:strokeColor="#FFFFFF"`, `android:fillColor="@android:color/transparent"`, the SVG's own stroke width (1.5), round caps/joins — the same attribute vocabulary as the project's existing stroke icons (`home_icon_outline_24`, `settings_icon_outline`). NO `?attr/*` is used as a path color (resource-merger rule); runtime tinting still happens through the app's existing tint mechanisms (`drawableTint`, root tints, layout tints).
- 'Filled'/active variants (Hugeicons free is stroke-only): `home_icon_filled_24`, `library_icon_filled`, `settings_icon_filled`, `ic_baseline_bookmark_24`, `ic_baseline_favorite_24`, `ic_baseline_star_24` use the same artwork at strokeWidth 2.2.
- SVG `<circle>`/`<ellipse>` elements (10 files: bells, users, clocks, play-circle, database…) were converted to equivalent arc path data; no icon needed `<rect>`/`<line>` conversion. No stroke-dasharray artwork was encountered.
- Every converted file was validated with `xml.dom.minidom` after writing (93/93 pass).

## Set 1 — Bottom navigation (8 files)

| Drawable file | Hugeicons icon | Stroke width | Source |
|---|---|---|---|
| `res/drawable/home_icon_filled_24.xml` | `home-01` | 2.2 | https://api.iconify.design/hugeicons/home-01.svg |
| `res/drawable/home_icon_outline_24.xml` | `home-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/home-01.svg |
| `res/drawable/library_icon.xml` | `folder-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/folder-01.svg |
| `res/drawable/library_icon_filled.xml` | `folder-01` | 2.2 | https://api.iconify.design/hugeicons/folder-01.svg |
| `res/drawable/netflix_download.xml` | `download-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/download-01.svg |
| `res/drawable/search_icon.xml` | `search-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/search-01.svg |
| `res/drawable/settings_icon_filled.xml` | `sliders-horizontal` | 2.2 | https://api.iconify.design/hugeicons/sliders-horizontal.svg |
| `res/drawable/settings_icon_outline.xml` | `sliders-horizontal` | 1.5 (SVG) | https://api.iconify.design/hugeicons/sliders-horizontal.svg |

## Set 2 — Settings main menu (7 files)

| Drawable file | Hugeicons icon | Stroke width | Source |
|---|---|---|---|
| `res/drawable/ic_settings_account.xml` | `user` | 1.5 (SVG) | https://api.iconify.design/hugeicons/user.svg |
| `res/drawable/ic_settings_extensions.xml` | `puzzle` | 1.5 (SVG) | https://api.iconify.design/hugeicons/puzzle.svg |
| `res/drawable/ic_settings_general.xml` | `settings-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/settings-01.svg |
| `res/drawable/ic_settings_layout.xml` | `grid-view` | 1.5 (SVG) | https://api.iconify.design/hugeicons/grid-view.svg |
| `res/drawable/ic_settings_player.xml` | `play-circle` | 1.5 (SVG) | https://api.iconify.design/hugeicons/play-circle.svg |
| `res/drawable/ic_settings_providers.xml` | `earth` | 1.5 (SVG) | https://api.iconify.design/hugeicons/earth.svg |
| `res/drawable/ic_settings_update.xml` | `refresh` | 1.5 (SVG) | https://api.iconify.design/hugeicons/refresh.svg |

## Set 3 — Details / episode actions (21 files)

| Drawable file | Hugeicons icon | Stroke width | Source |
|---|---|---|---|
| `res/drawable/baseline_notifications_none_24.xml` | `bell-off` | 1.5 (SVG) | https://api.iconify.design/hugeicons/bell-off.svg |
| `res/drawable/hourglass_24.xml` | `hourglass` | 1.5 (SVG) | https://api.iconify.design/hugeicons/hourglass.svg |
| `res/drawable/ic_baseline_add_24.xml` | `plus` | 1.5 (SVG) | https://api.iconify.design/hugeicons/plus.svg |
| `res/drawable/ic_baseline_arrow_back_24.xml` | `arrow-left-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/arrow-left-01.svg |
| `res/drawable/ic_baseline_autorenew_24.xml` | `reload` | 1.5 (SVG) | https://api.iconify.design/hugeicons/reload.svg |
| `res/drawable/ic_baseline_bookmark_24.xml` | `bookmark-01` | 2.2 | https://api.iconify.design/hugeicons/bookmark-01.svg |
| `res/drawable/ic_baseline_bookmark_border_24.xml` | `bookmark-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/bookmark-01.svg |
| `res/drawable/ic_baseline_favorite_24.xml` | `favourite` | 2.2 | https://api.iconify.design/hugeicons/favourite.svg |
| `res/drawable/ic_baseline_favorite_border_24.xml` | `favourite` | 1.5 (SVG) | https://api.iconify.design/hugeicons/favourite.svg |
| `res/drawable/ic_baseline_film_roll_24.xml` | `film-roll-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/film-roll-01.svg |
| `res/drawable/ic_baseline_keyboard_arrow_down_24.xml` | `chevron-down` | 1.5 (SVG) | https://api.iconify.design/hugeicons/chevron-down.svg |
| `res/drawable/ic_baseline_notifications_active_24.xml` | `notification-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/notification-01.svg |
| `res/drawable/ic_baseline_open_in_new_24.xml` | `external-link` | 1.5 (SVG) | https://api.iconify.design/hugeicons/external-link.svg |
| `res/drawable/ic_baseline_play_arrow_24.xml` | `play` | 1.5 (SVG) | https://api.iconify.design/hugeicons/play.svg |
| `res/drawable/ic_baseline_playlist_play_24.xml` | `play-list` | 1.5 (SVG) | https://api.iconify.design/hugeicons/play-list.svg |
| `res/drawable/ic_baseline_public_24.xml` | `earth` | 1.5 (SVG) | https://api.iconify.design/hugeicons/earth.svg |
| `res/drawable/ic_baseline_sort_24.xml` | `sorting-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/sorting-01.svg |
| `res/drawable/ic_outline_share_24.xml` | `share-02` | 1.5 (SVG) | https://api.iconify.design/hugeicons/share-02.svg |
| `res/drawable/netflix_download_batch.xml` | `download-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/download-01.svg |
| `res/drawable/outline_bookmark_add_24.xml` | `bookmark-add-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/bookmark-add-01.svg |
| `res/drawable/play_button.xml` | `play-circle` | 1.5 (SVG) | https://api.iconify.design/hugeicons/play-circle.svg |

## Set 4 — Settings sub-page preference icons (42 files)

| Drawable file | Hugeicons icon | Stroke width | Source |
|---|---|---|---|
| `res/drawable/arrows_input_24px.xml` | `full-screen` | 1.5 (SVG) | https://api.iconify.design/hugeicons/full-screen.svg |
| `res/drawable/baseline_description_24.xml` | `file-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/file-01.svg |
| `res/drawable/baseline_grid_view_24.xml` | `grid-view` | 1.5 (SVG) | https://api.iconify.design/hugeicons/grid-view.svg |
| `res/drawable/baseline_network_ping_24.xml` | `activity-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/activity-01.svg |
| `res/drawable/baseline_restore_page_24.xml` | `history` | 1.5 (SVG) | https://api.iconify.design/hugeicons/history.svg |
| `res/drawable/baseline_save_as_24.xml` | `save` | 1.5 (SVG) | https://api.iconify.design/hugeicons/save.svg |
| `res/drawable/baseline_sync_24.xml` | `refresh-ccw` | 1.5 (SVG) | https://api.iconify.design/hugeicons/refresh-ccw.svg |
| `res/drawable/baseline_theaters_24.xml` | `film-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/film-01.svg |
| `res/drawable/ic_baseline_aspect_ratio_24.xml` | `aspect-ratio` | 1.5 (SVG) | https://api.iconify.design/hugeicons/aspect-ratio.svg |
| `res/drawable/ic_baseline_clock_24.xml` | `clock-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/clock-01.svg |
| `res/drawable/ic_baseline_color_lens_24.xml` | `palette` | 1.5 (SVG) | https://api.iconify.design/hugeicons/palette.svg |
| `res/drawable/ic_baseline_construction_24.xml` | `wrench-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/wrench-01.svg |
| `res/drawable/ic_baseline_developer_mode_24.xml` | `code` | 1.5 (SVG) | https://api.iconify.design/hugeicons/code.svg |
| `res/drawable/ic_baseline_discord_24.xml` | `discord` | 1.5 (SVG) | https://api.iconify.design/hugeicons/discord.svg |
| `res/drawable/ic_baseline_dns_24.xml` | `server-stack-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/server-stack-01.svg |
| `res/drawable/ic_baseline_exit_24.xml` | `logout-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/logout-01.svg |
| `res/drawable/ic_baseline_extension_24.xml` | `puzzle` | 1.5 (SVG) | https://api.iconify.design/hugeicons/puzzle.svg |
| `res/drawable/ic_baseline_filter_list_24.xml` | `filter` | 1.5 (SVG) | https://api.iconify.design/hugeicons/filter.svg |
| `res/drawable/ic_baseline_folder_open_24.xml` | `folder-open` | 1.5 (SVG) | https://api.iconify.design/hugeicons/folder-open.svg |
| `res/drawable/ic_baseline_hd_24.xml` | `hd` | 1.5 (SVG) | https://api.iconify.design/hugeicons/hd.svg |
| `res/drawable/ic_baseline_language_24.xml` | `translate` | 1.5 (SVG) | https://api.iconify.design/hugeicons/translate.svg |
| `res/drawable/ic_baseline_ondemand_video_24.xml` | `video-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/video-01.svg |
| `res/drawable/ic_baseline_people_24.xml` | `users` | 1.5 (SVG) | https://api.iconify.design/hugeicons/users.svg |
| `res/drawable/ic_baseline_picture_in_picture_alt_24.xml` | `picture-in-picture` | 1.5 (SVG) | https://api.iconify.design/hugeicons/picture-in-picture.svg |
| `res/drawable/ic_baseline_skip_next_24.xml` | `skip-forward` | 1.5 (SVG) | https://api.iconify.design/hugeicons/skip-forward.svg |
| `res/drawable/ic_baseline_speed_24.xml` | `dashboard-speed-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/dashboard-speed-01.svg |
| `res/drawable/ic_baseline_storage_24.xml` | `database` | 1.5 (SVG) | https://api.iconify.design/hugeicons/database.svg |
| `res/drawable/ic_baseline_text_format_24.xml` | `text` | 1.5 (SVG) | https://api.iconify.design/hugeicons/text.svg |
| `res/drawable/ic_baseline_touch_app_24.xml` | `touch-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/touch-01.svg |
| `res/drawable/ic_baseline_tv_24.xml` | `tv-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/tv-01.svg |
| `res/drawable/ic_baseline_warning_24.xml` | `alert-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/alert-01.svg |
| `res/drawable/ic_battery.xml` | `battery` | 1.5 (SVG) | https://api.iconify.design/hugeicons/battery.svg |
| `res/drawable/ic_fingerprint.xml` | `finger-print` | 1.5 (SVG) | https://api.iconify.design/hugeicons/finger-print.svg |
| `res/drawable/ic_github_logo.xml` | `github` | 1.5 (SVG) | https://api.iconify.design/hugeicons/github.svg |
| `res/drawable/ic_outline_account_circle_24.xml` | `user-circle` | 1.5 (SVG) | https://api.iconify.design/hugeicons/user-circle.svg |
| `res/drawable/ic_outline_subtitles_24.xml` | `subtitle` | 1.5 (SVG) | https://api.iconify.design/hugeicons/subtitle.svg |
| `res/drawable/netflix_pause.xml` | `pause` | 1.5 (SVG) | https://api.iconify.design/hugeicons/pause.svg |
| `res/drawable/netflix_play.xml` | `play` | 1.5 (SVG) | https://api.iconify.design/hugeicons/play.svg |
| `res/drawable/quick_novel_icon.xml` | `book-open-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/book-open-01.svg |
| `res/drawable/screen_rotation.xml` | `screen-rotation` | 1.5 (SVG) | https://api.iconify.design/hugeicons/screen-rotation.svg |
| `res/drawable/sun_7_24.xml` | `sun-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/sun-01.svg |
| `res/drawable/title_24px.xml` | `heading` | 1.5 (SVG) | https://api.iconify.design/hugeicons/heading.svg |

## Set 5 — Shared UI & player icons (15 files)

| Drawable file | Hugeicons icon | Stroke width | Source |
|---|---|---|---|
| `res/drawable/baseline_skip_previous_24.xml` | `skip-back` | 1.5 (SVG) | https://api.iconify.design/hugeicons/skip-back.svg |
| `res/drawable/ic_baseline_arrow_back_ios_24.xml` | `chevron-left` | 1.5 (SVG) | https://api.iconify.design/hugeicons/chevron-left.svg |
| `res/drawable/ic_baseline_check_24.xml` | `tick-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/tick-01.svg |
| `res/drawable/ic_baseline_clear_24.xml` | `cancel-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/cancel-01.svg |
| `res/drawable/ic_baseline_close_24.xml` | `cancel-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/cancel-01.svg |
| `res/drawable/ic_baseline_delete_outline_24.xml` | `delete-01` | 1.5 (SVG) | https://api.iconify.design/hugeicons/delete-01.svg |
| `res/drawable/ic_baseline_edit_24.xml` | `pencil` | 1.5 (SVG) | https://api.iconify.design/hugeicons/pencil.svg |
| `res/drawable/ic_baseline_fast_forward_24.xml` | `fast-forward` | 1.5 (SVG) | https://api.iconify.design/hugeicons/fast-forward.svg |
| `res/drawable/ic_baseline_more_vert_24.xml` | `more-vertical` | 1.5 (SVG) | https://api.iconify.design/hugeicons/more-vertical.svg |
| `res/drawable/ic_baseline_pause_24.xml` | `pause` | 1.5 (SVG) | https://api.iconify.design/hugeicons/pause.svg |
| `res/drawable/ic_baseline_replay_24.xml` | `undo` | 1.5 (SVG) | https://api.iconify.design/hugeicons/undo.svg |
| `res/drawable/ic_baseline_skip_next_rounded_24.xml` | `skip-forward` | 1.5 (SVG) | https://api.iconify.design/hugeicons/skip-forward.svg |
| `res/drawable/ic_baseline_star_24.xml` | `star` | 2.2 | https://api.iconify.design/hugeicons/star.svg |
| `res/drawable/ic_baseline_star_border_24.xml` | `star` | 1.5 (SVG) | https://api.iconify.design/hugeicons/star.svg |
| `res/drawable/ic_refresh.xml` | `refresh` | 1.5 (SVG) | https://api.iconify.design/hugeicons/refresh.svg |

## Mapping notes

- `netflix_download_batch.xml` intentionally uses the same `download-01` artwork as `netflix_download.xml`: the original differed only by one extra tray line and the free set has no distinct 'batch download' glyph.
- `ic_outline_share_24.xml` uses `share-02`, not `share-01`: share-01 is a square-with-arrow glyph, while share-02 is the three-node share glyph Android uses (verified: it is the variant containing the node circles).
- The settings nav icon stays a sliders glyph (`sliders-horizontal`), matching the Phase 2 design; the Settings *menu* General row uses the gear (`settings-01`).
- There is no file named `ic_baseline_skip_previous_24.xml` in the project; the real previous-track file is `baseline_skip_previous_24.xml`, which was converted (`skip-back`). `ic_baseline_skip_next_rounded_24.xml` (player) was converted too (`skip-forward`).
- `ic_settings_providers.xml` and `ic_baseline_public_24.xml` use `earth` (clean sphere-with-continents glyph). Hugeicons' `globe` was rejected for these two: it is a desk-globe on a stand, and its SVG positions the sphere through a mirrored `<circle transform>` that does not survive SVG-to-vector conversion cleanly (verified: it was the only transform attribute in all 78 fetched icons). The DNS preference icon uses `server-stack-01` (no DNS-specific icon exists in the free set); the network-test preference uses `activity-01` (diagnostics pulse).

## Skipped — not converted (with reasons)

- `benene.xml` — Colored banana/meme artwork with multicolor fills — a brand joke asset, not a UI glyph; no Hugeicons equivalent intended.
- Brand/service logos: `mal_logo.xml`, `kitsu_icon.xml`, `ic_anilist_icon.xml`, `simkl_logo.xml`, `open_subtitles_icon.xml`, `subdl_logo_big.xml`, `animeskip.xml` — Third-party service logos (settings_account page, Kitsu pref). The free Hugeicons set has no faithful marks for these services; replacing them with generic glyphs would mislabel the accounts. (`ic_github_logo` and `ic_baseline_discord_24` WERE converted — Hugeicons ships real `github` and `discord` marks.)
- `speedup.xml` — Literal '1x/2x' text glyph; no equivalent concept in the set.
- `preview_seekbar_24.xml` — Bespoke feature glyph (video frame + play + scrubber bar) for the seekbar-preview setting; no faithful equivalent.
- `metadata_overlay_icon.xml` — Bespoke two-tone alpha overlay glyph. Candidate if wanted: `layers-01`.
- `arrow_or_edge_24px.xml`, `arrow_and_edge_24px.xml` — Ambiguous split/merge arrow glyphs for 'Parallel downloads' vs 'Concurrent connections' — no single Hugeicons glyph separates the two meanings cleanly. Candidates: `split`, `arrow-data-transfer-vertical`.
- `clear_all_24px.xml` — Stacked-bars glyph used by the library menu; ambiguous mapping. Candidates: `delete-01`, `left-to-right-list-bullet`.
- `ic_outline_voice_over_off_24.xml` — No voice-over-off equivalent exists in the free set (subbed/dubbed display pref).
- `download_icon_done/error/load/pause.xml`, `rddone/rderror/rdload/rdpause.xml` — Composite phone-frame and animated download-state glyphs (load is a spinner); converting would break their state semantics.
- `netflix_skip_back.xml`, `netflix_skip_forward.xml` — Matched pair of ~270° seek-arc glyphs for ±seek in the player. Closest Hugeicons options (`rotate-01/02`, `undo/redo`) would materially change the established seek design; left as a documented pair.
- `ic_baseline_resume_arrow.xml`, `ic_baseline_resume_arrow2.xml` — Bespoke resume-play glyphs used by the TV result screen; no faithful equivalent.
- Cast icon (`cast_ic_mini_controller_skip_next`, details cast button) — Supplied by the mediarouter library — no vector file exists in the app's own `res/` to convert.
- Unreferenced legacy files: `ic_filled_notifications_24dp.xml`, `ic_notifications_black_24dp.xml`, `ic_outline_notifications_24dp.xml`, `home_alt.xml`, `ic_outline_home_24.xml`, `ic_home_black_24dp.xml`, `ic_dashboard_black_24dp.xml` — Zero references found in layouts/menus/preference XMLs — skipped to avoid churn on dead resources. Candidates if re-enabled: `notification-01`, `home-01`.
- `ic_baseline_check_24_listview.xml` — List-row variant of the converted check (`ic_baseline_check_24` → `tick-01`); left as a candidate to avoid changing list selection rendering sight-unseen.
- `sun_1.xml`…`sun_6.xml`, `sun_7.xml` — Graded brightness-ramp series for the player brightness control; only `sun_7_24.xml` (the one the settings page references) was converted to `sun-01`, so the ramp series keeps its internal gradation.
- Selector drawables (`home_icon_selector.xml`, `library_icon_selector.xml`, `settings_icon_selector.xml`, `notifications_icon_selector.xml`) — Not vectors — they reference the converted filled/outline files, so they pick up the new artwork automatically.
- Scope-excluded UI backgrounds (not icons, untouched): `bg_circle_overlay.xml`, `bg_topbar_floating.xml`, `bg_nav_floating.xml`, `bg_settings_group.xml`, `bg_ripple_rounded.xml` — Per phase instructions.

## Result

93 drawable files converted in place across the 5 sets (8 nav + 7 settings-main + 21 details/episodes + 42 preference + 15 shared/player). No layouts, menus, preference XMLs or Kotlin files were edited in this phase.
