# Phase 8.75 — fixes from his 8.74 verdict (2026-10-28 evening)

1. **Toggles finally everywhere — root cause found.** The settings
   screens (Player, UI, General, Account, Providers, Updates) use
   plain `SwitchPreference`, NOT SwitchPreferenceCompat — a
   framework Switch widget the AppTheme switchStyle never reaches,
   which is why 8.74's theme work changed nothing there. All 34
   entries across the six settings XMLs are converted to
   SwitchPreferenceCompat (drop-in), and a new app-level override
   `res/layout/preference_widget_switch_compat.xml` (same resource
   name beats the preference AAR's bare default) renders the widget
   as a MaterialSwitch with the reference tints — so every settings
   toggle is now the M3 switch in his light-blue/navy colours,
   exactly like Settings home, Stremio and Nuvio already were.
2. **Provider switcher blank on custom lists — root cause found.**
   HomeViewModel.loadCustomList never posted _apiName, so while a
   custom list (his "✨ Default") was active the switcher label had
   no name to show and sat blank. It now posts the list's name; the
   existing observer + 8.73 re-assert paint it on the FAB and the
   head label.
3. **Double-tap seek works while the player is locked** (his ask,
   screenshot of the lock icon). PlayerGestureHelper.onTapDetected
   no longer lets the lock suppress the double-tap branch — seek
   (and the centre double-tap play/pause) fire while locked; single
   taps, swipe seek, brightness/volume and hold-speed stay
   suppressed, and the seek wedge still shows as feedback.
4. **Feed title bar: "Info ›" beside the title** (his ask). The
   title row is now a packed ConstraintLayout chain: the label
   renamed More → Info with a chevron icon, sitting immediately
   after the title instead of at the far right edge; the title is
   width-constrained so long titles ellipsize instead of pushing
   Info off-screen. Expanded state still reads "Less".

Files: res/layout/preference_widget_switch_compat.xml (new),
res/xml/settings_{account,general,player,providers,ui,updates}.xml,
HomeViewModel.kt, PlayerGestureHelper.kt, fragment_feed_watch.xml,
FeedWatchFragment.kt, strings.xml (feed_info).
