# Phase 8.43 — his 8.42 verdict fixes (PR #7, branch feed-section)

From his 2026-10-26 device report on 8.42: downloads now work
(incl. cross-provider), but (1) the fallback ignored the chosen
resolution + audio, (2) the download icon read as a clock,
(3) a still-loading video would not download, (4) pull-refresh
flung the list toward the end.

1. **Fallback honours the pick (DownloadManager).** The
   cross-provider fallback now ranks its links by the primary
   attempt's signature: same language first (token parsed from
   the link name/source: Hindi, English, Bengali, Tamil, …),
   then closest quality, then higher quality. Signature = the
   first primary link (the feed queues the selected link
   first; the details flow tries its links in order), so
   "1080p Hindi" no longer lands as "720p English".
2. **Clear download icon.** New vector `ic_feed_download`
   (arrow into a tray) replaces `baseline_downloading_24` on
   the feed watch pill (layout default + updateDownloadPill);
   the circular-arrow glyph read as a clock at pill size.
3. **Download while loading (FeedWatchFragment).** Tapping
   Download while the video is still extracting now waits for
   the playback extraction already in flight (up to ~30 s,
   aborts on ERROR) and queues the download with THOSE links,
   instead of a blind second extraction. Toast:
   feed_download_preparing.
4. **Refresh scroll (FeedFragment + FeedWatchFragment).**
   The scroll-to-top now happens in the submitList commit
   callback. Scrolling before the async diff committed let
   the layout manager re-anchor to the old first item —
   which a prepend pushes far down — flinging the list
   toward the end on every refresh. Same fix for the
   Up-next rotation.
