# Phase 8.60 — Glass UI reverted (his verdict), buffer kept, seek FX + compact feed pills

His verdict on 8.58 (2026-10-27): "একদমই ভালো লাগে না" — no glassy
vibe; restore the previous clean UI; keep the buffer but no on-screen
download details; pause icon too thick (the feed's rendering is the
nice one); no YouTube-style 10 s seek effect; feed pills too big/ugly.

## Reverted to the pre-8.58 state (files restored from master e60a4e0d)
- player_custom_layout.xml / player_custom_layout_tv.xml: glass title
  pill, top clock pill, mpv mid row + info pill (the on-screen
  clock/speed/battery details), glass center circles, episode flank
  buttons, Screenshot and A-B Repeat buttons — all gone.
- FullScreenPlayer.kt / GeneratorPlayer.kt: all 8.58 code removed.
- Settings: the "Player controls" category and its 8 keys removed;
  the glass drawables/icons from 8.58 remain in the repo unreferenced.

## Kept from 8.59 (unchanged)
- Feed cross-provider fallback repair (language-tag titles, playback
  fallback, cleaned query + wider scan).
- Preload buffer everywhere: main player 5 min default read-ahead +
  192 MB cap + 90 s back-buffer + 512 MB disk floor, feed 120 s /
  96 MB, Settings -> Player -> Cache "Preload buffer" option.

## New polish
- Slim pause/play in the main player: player_pause_play gets 14 dp
  padding, so the glyph renders at feed size instead of a full 70 dp
  (the thick 3.6-stroke pause bars were the complaint).
- YouTube-style double-tap seek effect: soft disc + triple chevrons +
  "N seconds" blooms on the tapped side (player_seek_fx_left/right in
  the phone layout, PlayerView.showSeekFx, fired from
  PlayerGestureHelper.rewind/fastForward). Double-tap seek now
  defaults ON (Settings toggle + code fallback), like YouTube.
- Feed pills compacted: Sources / Episodes / Download and Add to list
  — padding 6 -> 4 dp vertical, icons 20 -> 16 dp, text 13 -> 12 sp;
  Add to list is wrap-width instead of full-width.
