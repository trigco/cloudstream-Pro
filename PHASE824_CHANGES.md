# Phase 8.24 — hung links, link order, language choice (PR #7)

Owner verdict on 8.23 (2026-10-25, three screenshots): the feed
still does not play a MovieBox DASH title (Sardar 2) until the main
player has played it; his main-player observation refined — playback
there starts either when ALL links finish fetching (20 s+) or when
he stops the loading step manually. And on multi-language movies
the feed's audio button says "no track": the languages are not
audio tracks, they are separate links (the main player's source
dialog lists "MovieBox (Kannada Audio) DASH 1080p", "(Malayalam
Audio) DASH", "MP4" variants side by side).

## Why the feed hung: a silent stall is not an error

The feed screenshot shows a rendered frame at the resume position
with 0.00 KB/s flowing: the chosen link stalls in BUFFERING without
ever raising onPlayerError, so neither the error walk (8.20) nor
the background-fetch wait (8.23) ever fired. Once the main player
had run, its completed extraction was cached, the feed received the
full list instantly, and a working link was reachable — the same
warm/cold asymmetry as before, one level deeper.

- **Startup watchdog (FeedPlayer):** 12 s after a link starts, if
  the player is still in BUFFERING — or READY but not playing with
  essentially nothing buffered ahead — the link is treated as a
  dud and the shared `failOver()` walk moves on (other links of
  the chosen resolution first; while extraction still runs it
  waits, per 8.23). Error handling, the watchdog, and the
  post-extraction pickup now share the one failOver() path.
- **Link order:** within one resolution, adaptive formats now come
  before progressive files (DASH → HLS → MP4) — the main player's
  source list orders them the same way, and a 1080p progressive
  MP4 is the worst possible first pick on mobile data.

## Language choice (FeedWatchFragment + FeedPlayer)

- The quality button now opens the **source list** — one row per
  link, labelled compactly as "1080p • Kannada Audio" (audio
  descriptor taken from the link name) or "1080p • DASH" — never
  the full release names rejected in 8.21. Picking a row plays
  that exact link (`FeedPlayer.selectLink`), position kept.
- The audio button keeps the real track picker when a stream has
  multiple audio tracks; when it has only one, it opens the source
  list instead of dead-ending on "Only one audio track", because
  on these sources the language IS the source.
- The source button now appears whenever there is more than one
  link (was: more than one resolution).

## Files
- ui/feed/FeedPlayer.kt (failOver refactor, startup watchdog,
  adaptive-first ordering, selectLink)
- ui/feed/FeedWatchFragment.kt (source picker, link labels,
  audio-button fallback)
- res/values/strings.xml (feed_sources)
