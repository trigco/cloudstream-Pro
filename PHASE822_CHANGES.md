# Phase 8.22 — Feed, fourth polish round (owner's device feedback)

Seven items from the owner's latest screenshots and notes.

## Skeleton grid gutters
The two skeleton columns touched (GridLayout + include dropped the
cards' margins). Rebuilt as rows of two cards with the margins on
the include tags, so placeholders sit apart exactly like real cards.

## DASH parity, for real this time
Some videos still only played in the feed after the main player had
warmed them, DASH especially. The remaining difference: transport.
The main player fetches through the app's OkHttp client with the
app's browser user-agent (`OkHttpDataSource` + `USER_AGENT`, or the
link's own UA header); the feed used plain URLConnection with no
browser UA, which some CDNs refuse. `FeedPlayer` now builds its
data source the same way CS3IPlayer does.

## Resume across the portrait↔landscape handoff
Playback restarted from the beginning in both directions. Two
fixes, mirroring how the app itself does it:
- The feed player now reads the resume position from the store
  (`DataStoreHelper.getViewPos`, same 5–95% rule) instead of the
  episode objects' fields, which are stamped at build time.
- The handoff hands the main player a freshly built generator whose
  episodes are copies carrying the LIVE saved positions — the same
  refresh GeneratorPlayer performs on its own metadata.

## Feed keeps its place
Opening a video and coming back rebuilt the feed view and lost the
scroll position (which read as "content moved/changed by itself"
while the mini player was up). The list's layout state is now saved
on exit and restored on return.

## Loading is an animation, not a caption
The "Loading video..." text over the poster is gone — just the
spinner animation over the poster, as requested.

## Bottom edge blend
A 56dp black gradient now sits at the player box's bottom edge, so
the video melts into the page instead of ending on a hard flat line.

## Hide content
Long-press any card (feed or Up next) → "Hide" — the item is stored
in `FeedHidden` and filtered out of the feed build, search merges
and Up next, permanently. For content the viewer dislikes or
doesn't want shown.
