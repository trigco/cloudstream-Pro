# Phase 8.40 — main downloader DASH support + button visibility + storyline

1. DASH downloads (shared layer): VideoDownloadManager no longer
   hard-rejects DASH links. downloadSingleEpisode dispatches DASH
   to a new downloadDash() that exports the stream to a real MP4
   via media3 Transformer (new media3-transformer dependency),
   using the link's own headers/referer through an OkHttp data
   source (same client + user agent as the player), reports
   progress from the growing export file into the existing
   notification/metadata machinery, then copies the file into
   the download destination. Details page, feed and batch
   downloads all gain DASH support from this one change.
   Magnet/torrent remain rejected (not HTTP downloads).
   Pause/resume are not supported for exports; Stop cancels.
2. Button visibility on black: feed_pill_bg is now solid
   #262626 with a lighter #48484F edge (was near-transparent
   black), and the round player buttons (feed_center_button_bg)
   are #B3262626 — same gray family as the feed chips, so the
   buttons read clearly on the black theme.
3. Storyline on the watch screen: the loaded page's plot sits
   under the title/meta in the title box, hidden by default.
   A "More" label beside the title expands it ("Less"
   collapses) and the title box is re-measured for the
   collapse animation. "More" only appears when the page
   actually has a plot.
