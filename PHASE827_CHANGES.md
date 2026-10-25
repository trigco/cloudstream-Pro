# Phase 8.27 — diagnostic build: on-screen playback log (PR #7)

Owner verdict on 8.26: still no cold play without the main
player's help. Five rounds (8.22 transport, 8.23 wait-on-error,
8.24 watchdog/order, 8.25 completed-set, 8.26 Cronet/interceptor
parity) each fixed a real difference and none fixed the bug —
so this round changes no behaviour at all. It instruments the
feed player so the failure explains itself:

- FeedPlayer keeps a short playback log: the play request, the
  extraction result (ok flag + every link's name, quality, type
  and host), which transport each attempt actually used
  (cronet / okhttp+interceptor / okhttp fallback), every player
  error with its full cause chain (error code, exception class,
  message — e.g. an HTTP status), watchdog firings, and failover
  steps.
- The watch screen's error state now shows the last lines of
  that log in a small monospace box above the buttons, ready to
  be screenshotted.

With that screenshot the next fix can target the proven cause
instead of the next hypothesis. (The owner's own guess —
session/cookie state established by the main player — is one of
the things the log can confirm or rule out: it shows whether
extraction itself succeeds cold and how each link then dies.)

## Files
- ui/feed/FeedPlayer.kt (debug log + instrumentation)
- ui/feed/FeedWatchFragment.kt (shows the log in the error state)
- res/layout/fragment_feed_watch.xml (error detail text view)
