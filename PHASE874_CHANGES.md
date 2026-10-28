# Phase 8.74 — fixes from his last-build verdict (2026-10-28 evening)

1. **Home shake — root cause found (his hero theory was right).**
   HeaderViewHolder.updatePreview hid the whole 500dp hero frame
   while the preview loaded/failed, collapsing it to a 50dp padding
   strip — every category below jumped ~450dp, on every load. The
   frame now keeps its height and shows a shimmer skeleton
   (home_hero_skeleton in fragment_home_head.xml) until real preview
   items land; only the None provider (no hero by design) keeps the
   compact padding.
2. **Gold rating star.** Poster badge star (RatingButton drawableTint)
   is gold #FFC107; the ★ character in feed-card meta and the feed
   watch meta is gold-spanned via goldStarText() (MetadataResolver.kt).
3. **Every toggle = his reference.** New switch_track_tint /
   switch_thumb_tint selectors (ON: light-blue track #A8C7FA, deep-
   blue thumb #0B57D0; OFF: #3F4448 / #BDC1C6) applied app-wide via
   Widget.CloudStreamPro.Switch / .MaterialSwitch, wired as the
   switchStyle + materialSwitchStyle of AppTheme, AmoledMode and
   MonetMode — so MaterialSwitch rows AND the SwitchCompat inside
   preference screens (player settings etc.) all match his shot.
   This supersedes his earlier "Dark ON stays white" pick, per his
   "টগল টা আমি যেমন দিয়েছি তেমন হবে সব খানে".
4. **Feed watch header Id.** updateHeader never prints a raw
   "Id …" title again: cached resolved title wins; an id-like name
   kicks off TitleResolver and stays blank until the new
   watchTitleListener refreshes the header on resolution.
5. **Auto-hide lag (both directions).** The scroll accumulator kept
   the opposite direction's backlog, so reversing direction had to
   pay it off first. It now resets on any direction flip, thresholds
   dropped (hide +24, show −12), animations 200→150 ms, accumulator
   clears when scrolling idles.
6. **Custom providers in the switcher (his asks).** The ★ prefix
   beside custom list names is removed. Custom lists pin/unpin on
   long-press exactly like providers (same pinned store, keys
   "custom:<id>", pin icon shown, pinned customs join the top group
   most-recent-first); click/check/pin indices all run through the
   new displayCustoms list.
7. **Feed player controls on bright videos.** feed_center_button_bg
   is a light black oval again (#33000000, ~20%) — cast, audio,
   subtitles, play/pause and the fullscreen (his "rotate") button all
   use it, so white icons read on light scenes without the old heavy
   disc. ONLY cast/audio/subtitles got smaller (40dp → 36dp), per his
   correction; play/pause and the rest keep their sizes.

OPEN QUESTION for him: which "custom provider page" shows blank —
the switcher sheet, Settings → Categories, or Home with a list
active? Not diagnosable from the report alone; no code changed for
it in this round.

Files: HomeParentItemAdapterPreview.kt, fragment_home_head.xml,
styles.xml, colors.xml, color/switch_track_tint.xml (new),
color/switch_thumb_tint.xml (new), MetadataResolver.kt, FeedAdapter.kt,
FeedWatchFragment.kt, fragment_feed_watch.xml,
drawable/feed_center_button_bg.xml, HomeFragment.kt.
