# Phase 8.38 — device feedback round 2 (PR #7, branch feed-section)

1. Seekbar black bar: feed_controls_bar_bg is now a soft bottom
   gradient scrim instead of a hard #80000000 rectangle.
2. Batch page start button: explicit white pill background with
   black bold text (theme default left white text on white).
3. Back from the batch page killed the watched video:
   FeedWatchFragment.onDestroyView called FeedPlayer.stop() for
   ANY view destruction, including the watch -> batch navigation.
   A goingToBatch flag now pauses and preserves playback instead,
   so Back returns to the same video at the same position.
4. Download pill ring tinted white (it rendered near-invisible).
5. Downloads failing while playback works — diagnosis:
   VideoDownloadManager.downloadSingleEpisode hard-rejects DASH
   links (DOWNLOAD_INVALID_INPUT, tryNext=true), and the player
   prefers DASH. Feed downloads queued ONLY the selected link,
   so a DASH selection had no fallback. Both feed paths now
   queue every playable link (selected/chosen first); the
   service falls through to a downloadable sibling (M3U8/VIDEO).
   DASH-only titles still need a real DASH download path
   (media3 Transformer export) — planned as the next phase.
