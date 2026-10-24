# Phase 8.15 — Player polish + settings grouping (branch player-modern)

User ask (2026-10-24 13:37, re-confirmed for CloudStream Pro at 13:58 after
an initial mix-up where the work was mistakenly done on the SkyStream fork —
that branch stays unpushed and parked): YouTube-style player icons + timing
bar, a cache auto-delete option, and settings sections as one card each
instead of a separate card per row. The provider pre-install item from the
same message stays declined (pirate providers are never bundled).

## Icons
- `netflix_play.xml`: outline triangle → solid white triangle (the play
  state of the centre button; pause was already solid pill bars, 8.14a).
- Centre ±10s buttons (`player_rew` / `player_ffwd` in
  `player_custom_layout.xml`): padding 10dp → 6dp so the filled Material
  arrows read at full weight inside the 70dp buttons.
- Tracks button (`player_tracks_btt`, phone + TV layouts): equalizer bars
  icon → filled `ic_baseline_subtitles_24`.

## Timing bar (YouTube-style)
- `LivePreviewTimeBar` in phone, trailer and TV layouts: played colour and
  scrubber colour now `@color/colorPrimaryRed` (#E50914 — set deliberately
  for the bar at the user's explicit YouTube-style request; the rest of the
  UI accent stays monochrome), new `buffered_color` `@color/videoBuffered`
  (#80FFFFFF). Phone + trailer also: bar height 2dp → 4dp, scrubber
  24dp → 15dp (21dp while dragging). TV keeps its 10-foot sizes.

## Cache auto-delete
- New SwitchPreference "Auto-clear cache" in Settings → Player → Cache,
  directly under the manual "Clear video and image cache" row
  (key `auto_clear_cache_key`, default off).
- `CloudStreamApp.onCreate`: when the switch is on, the app cache dir is
  deleted on a background thread at startup — the same clear the manual
  row runs. Best-effort: any failure is swallowed and never blocks startup.

## Settings grouping
- `main_settings.xml`: each section (APP / CONTENT / SYSTEM) is now ONE
  card (`bg_settings_group`) holding its rows separated by hairline
  dividers, instead of one card per row. Accordion behaviour is unchanged:
  every header and container keeps its ID, and each container now sits
  inside the card directly under its own row, so an expanded section still
  opens under the row that was tapped. SettingsFragment needed no changes.

## Verification
- All edited XML files parse. No local Gradle build (dropped on this VM);
  the Artifact Build workflow on the PR is the compile check, and the
  merge gate is the user's device verdict, as with 8.12–8.14.
