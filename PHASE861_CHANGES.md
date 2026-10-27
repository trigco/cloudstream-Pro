# Phase 8.61 — Feed-style skip badge, skip buttons hidden, theme cleanup

His verdict on 8.60 (2026-10-27, with video + screenshots): the giant
disc + chevrons seek FX is wrong — he wants the FEED player's skip
animation; the main player's center 10 s icons are annoying — hide
them; and theme rules: no Light theme, Dark must be a bit gray (not
pure black), keep Dark + Material.

## Player
- Center rewind/fast-forward buttons (player_rew_holder /
  player_ffwd_holder) are visibility=gone in the phone layout for
  good. Seeking is double-tap only, like YouTube. (TV layout keeps
  its buttons — remote navigation needs them.)
- PlayerGestureHelper.rewind()/fastForward() stripped of the old
  feedback machinery (center-menu flash, icon spin, sliding seconds
  text): they now only seek + fire the badge.
- The 8.60 disc/chevron FX is replaced by the feed's exact pattern
  (FeedWatchFragment.skipBy): a 40 dp netflix_skip icon with the
  seconds ("10s") under it, offset toward the tapped side, shown
  600 ms. PlayerView.showSeekFx drives it; seek_fx_circle /
  ic_seek_chevrons drawables and the fx format string removed.

## Themes
- Picker (themes_names/values) now lists only: Dark (AmoledLight)
  and Material You (Monet). Light, AMOLED and System entries removed.
- Dark is gray now: AmoledModeLight overrides primaryBlackBackground
  to the new @color/darkThemeBackground #121212 (was pure black
  #000000 via AmoledMode); cards stay #1F1F1F.
- Migration: stored prefs "Light" / "Amoled" / "System" resolve to
  AmoledModeLight in CommonActivity.loadThemes, so nobody is left
  on a removed theme. The LightMode/AmoledMode styles stay in
  styles.xml (AmoledMode is still AmoledModeLight's parent).
