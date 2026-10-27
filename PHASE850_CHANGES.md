# Phase 8.50 — feed resume fix, first-run page fix, Family Mode

## 1. Feed video restarted after a main-player handoff (his report)
Root cause, found in code: the main player saves its position
under the episode id, but on return FeedPlayer.play() first
called savePosition() — stamping the feed player's STALE paused
position (the moment of the rotate/handoff) over the main
player's newer one, so the resume seek landed near the start.
- replayCurrent() now sets preserveStoredPosition (play() skips
  the stale save) and pendingStartIndex (the handed-off episode
  reopens instead of episode 0). Normal plays are unchanged.

## 2. New users landed on the Extensions page first (his report)
MainActivity sends users with zero installed plugins straight
to the repository page at startup. On a fresh install the
bundled default plugins are still downloading in the
background at that moment, so new users hit the raw 31-repo
page first. The redirect is now skipped while the default
plugin download is pending — Home opens, providers arrive
on their own.

## 3. Family Mode (his feature ask)
New switch in Settings → Content: "Family Mode"
(pref family_mode_v1, default off). Helper:
utils/FamilyMode.kt — a provider counts as adult when it
declares TvType.NSFW or its name matches 18+/nsfw/porn/adult/
xxx/erotic/hentai; an item counts as adult when its type is
TvType.NSFW. When ON:
- Provider picker: adult providers are not listed.
- Home (normal + custom lists): NSFW items are stripped from
  every row, empty rows disappear; the hero pool and random
  strip are filtered at the source in HomeViewModel. Toggling
  the switch re-applies to Home immediately on return.
- Feed: adult providers are excluded from the pool and from
  feed search; NSFW items are dropped.
- Search: adult providers are excluded and NSFW results are
  dropped, including on load-more pages.
- Details: an NSFW title opened directly (bookmark, history)
  shows no poster/backdrop art — the placeholder is used.
