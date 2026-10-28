# Phase 8.76 — the shake, round three (his timing report)

His answer to "when does it shake": while Home categories open
and posters load, while scrolling, and sometimes on its own. All
three point at the same mechanism, now traced end to end:

- After Home opens, TitleResolver + MetadataResolver keep
  resolving titles/ratings for minutes. Every burst fired
  titleNotifyRunnable, which rebound the ENTIRE master adapter —
  hero, strips and every row — and every poster load runs a
  150 ms crossfade, so each burst washed a visible shimmer over
  the whole screen. Idle gaps between scroll flings let it fire
  mid-browsing ("while scrolling"), and bursts landing while he
  just watched read as "on its own".

Fixes:
1. The debounced refresh no longer touches the master adapter.
   HomeFragment.refreshVisibleHomeLists walks only the master
   list's attached (visible) children and notifies the row child
   adapters + header strip adapters actually on screen. The hero
   is never rebound from here; off-screen rows cost nothing.
2. Grid/strip posters no longer crossfade: SearchResultBuilder
   and HomeScrollAdapter pass crossfade(false) (the loader sets
   its 150 ms fade before the caller builder runs, so the
   caller's value wins). Posters cut in instantly, and the 8.73
   URL guard still skips reloads of posters already showing.

Files: HomeFragment.kt, SearchResultBuilder.kt,
HomeScrollAdapter.kt.
