# Phase 8.21 — Feed, third polish round (owner's device feedback)

8.20 confirmed broadly working; the owner sent five annotated
screenshots with eight specific complaints. Same branch/PR.

## Skeleton matches the real cards now
The placeholders were plain rectangles. They now mirror feed_card:
poster block with the card's rounded top corners, a circular
placeholder where the heart button sits, and rounded title/meta bars.

## Quality picker: resolution tags only
The list showed full release file names. It now shows one row per
resolution ("1080p", "720p", …). Links sharing a resolution stay
grouped under that tag: picking a resolution starts at its first
link, and the error fallback walks the other links OF THE SAME
resolution before moving on (`FeedPlayer.selectQuality` +
`preferredQuality`/`triedLinks`).

## Two videos can no longer mix
Playing a second video while the first was still extracting could
interleave: the first load's late results overwrote the second
video's links/state. Every load now carries a generation stamp;
stale loads drop their results at each checkpoint.

## Landscape handoff without the portrait flash
The handoff now sets `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` before
navigating, and the watch page no longer restores the orientation
on the way out when it handed off — the main player owns it.

## No more rectangle seam on the video
The empty/letterboxed video surface showed as a floating black
rectangle over the poster. The surface now stays hidden until the
first frame is ready (the poster + spinner carry the loading
state), and the player uses `resize_mode="zoom"` so the video fills
the 16:9 box edge-to-edge while playing.

## Faster start
- Extraction starts playback on the FIRST playable link (like the
  main player) instead of waiting for every extractor; when
  extraction completes, the full link list is swapped in for the
  picker/fallback with the playing link kept selected.
- Load control tuned for a preview player: playback starts after
  ~1 s buffered (defaults: 15 s min / 50 s max / 1 s start / 2 s
  rebuffer, time-over-size priority).

## Likes now fetch
Reordering alone was too weak to notice. On load/refresh, when the
viewer has likes, the feed additionally searches all providers for
the most-liked type (Anime/Drama/Series/Movie/Cartoon/Documentary)
and weaves the fresh hits through the front of the feed.

## Missing titles
Some providers return blank names, leaving an empty title slot.
Cards and the watch header now fall back to a readable slug derived
from the item URL (`FeedAdapter.displayTitle`), so a title always
shows.
