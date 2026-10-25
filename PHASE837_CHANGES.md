# Phase 8.37 — downloads done properly, audio = language, cross-provider fallback (PR #7)

Owner verdict on the combined 8.35/8.36 build: everything OK,
then a full download spec plus three refinements.

- Cross-provider fallback: when a title has no playable links
  on its own provider (or its page/episodes fail to load), the
  feed searches the other providers for the same title in
  parallel and continues playback there — the error page only
  appears after every candidate fails. One attempt per play;
  episode picks never fall back. The watch meta line now names
  the provider that is actually playing.
- Audio button = language only. It no longer opens the source
  (quality) list: with multiple audio tracks it lists track
  languages; on link-per-language sources it lists the links'
  languages and switches to the best link of the chosen one.
- Title-row pills get an outline (feed_pill_bg stroke) so they
  read as buttons instead of floating text.
- Single download now hands the downloader the SELECTED link
  (source + quality + its audio) and the extracted subtitles
  via DownloadQueueItem.links/subs — the service downloads
  given links as-is. The pill shows a spinning ring while the
  episode is queued/downloading and a check once downloaded.
- Series: the Download pill opens a batch page — resolution
  chips (Any / 1080p / 720p / SD) and audio-language chips on
  top, per-episode multi-select with Select all below. On
  confirm, each selected episode's links are extracted
  (3-parallel, progress shown) and queued with its chosen link;
  episodes with no extractable links are queued bare and the
  service extracts them itself.
- Feed pull-refresh ring now rests below the floating header
  (setProgressViewOffset) instead of hiding behind it.

## Files
- ui/feed/FeedPlayer.kt, ui/feed/FeedWatchFragment.kt,
  ui/feed/FeedBatchDownloadFragment.kt (new), ui/feed/FeedFragment.kt
- res/layout/fragment_feed_watch.xml,
  res/layout/fragment_feed_download.xml (new),
  res/layout/feed_download_episode_item.xml (new),
  res/drawable/feed_pill_bg.xml (new),
  res/navigation/mobile_navigation.xml, res/values/strings.xml
