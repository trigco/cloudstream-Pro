# Phase 8.58 — MPV-style main player UI + player customization settings

Direction (his, 2026-10-27): bring the mpv player's UI style to the main player,
engine stays ExoPlayer; add Settings options so users choose placement and
which controls to keep. Accent stays CloudStream Pro red; the style borrow is
layout + glass surfaces, not mpv's purple.

## Player UI (player_custom_layout.xml, phone)
- Top: back button is now a 48dp glass circle; the title block becomes a
  glass pill (player_title_pill) — title (1 line, ellipsized) + a small
  rez/codec line under it; a glass clock pill sits top-end (player_top_clock).
- Center: play / rewind / fast-forward get glass-circle backgrounds; new
  glass prev/next EPISODE buttons flank the trio (player_prev/next_episode_btt),
  wired to the existing CSPlayerEvent.Prev/NextEpisode (GeneratorPlayer
  toggles them by hasPrev/hasNext in playerPositionChanged).
- Mid row (player_mpv_mid_row, mpv-style): Prev pill | live info pill
  (TextClock + network speed from TrafficStats deltas + battery %) | Next pill.
  Row joins the existing show/hide + fade/slide control animations;
  container visibility gated in FullScreenPlayer.updateUIVisibility.
- Bottom: seekbar row unchanged; the button row gains two buttons —
  Screenshot (PixelCopy of the SurfaceView, API 24+, saved as PNG to
  Pictures/CloudStreamPro in the app-specific external dir + media scan)
  and A-B Repeat (tap: set A, tap: set B -> loops B->A every 400ms, icon
  tints red; tap again: off). Row restructured with a spacer + right
  holder for the Split-clusters placement.
- TV layout: hidden stubs for all new phone-binding ids (binding is shared).

## Settings -> Player -> new "Player controls" category
- MPV-style player UI (mpv_player_ui_key, default ON): OFF restores the
  classic labeled button row exactly as before (glass styling, top clock,
  info pill and mid row all switch off).
- Button row placement (player_controls_placement_key, ListPreference):
  "scroll" (classic scrollable row) / "clusters" (row spans full width,
  sources/tracks/skip OP/screenshot/A-B/next-episode move to a right
  cluster, lock/rotate/resize/speed stay left).
- Per-control switches (all default ON): Screenshot button, A-B repeat
  button, Info pill, Episode pills row, Center episode buttons, Top clock.

## Files
player_custom_layout.xml, player_custom_layout_tv.xml (stubs),
drawable/player_glass_circle.xml, drawable/player_glass_pill.xml,
drawable/ic_player_screenshot.xml, drawable/ic_player_ab_repeat.xml,
values/strings.xml, values/donottranslate-strings.xml,
values/player_ui_arrays.xml, xml/settings_player.xml,
FullScreenPlayer.kt (style/placement/info pill/A-B/screenshot),
GeneratorPlayer.kt (episode-button visibility in playerPositionChanged).
