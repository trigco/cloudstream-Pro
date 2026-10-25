# Phase 8.30 — second extraction pass when the first returns zero (PR #7)

The 8.29 screenshot (Bethlehem Kudumba Unit, MovieBox):

    url=api3.aoneroom.com/2355442796397591760
    extract ok=true links=0 dur=2067ms
    data=2355442796397591760        (a bare id — the provider's dataUrl)
    gcache=0 sat=false types=

So the provider's loadLinks receives the same data the main
player flow uses (its API id), runs ~2 s, returns true, and
delivers nothing — while the main player, reaching the same
call only after the details page, gets the full link set. The
difference left standing is call order/warm-up inside the
provider: the first cold call primes whatever the second call
then serves (the same reason Retry and Play-in-main-player —
both simply call generateLinks again — can succeed after a
feed failure).

Fix: when the feed's first extraction pass completes with zero
links, run ONE more generateLinks pass on the same generator
after a short pause, accumulating into the same lists, and log
it as "pass2". This only fires in a state that is otherwise a
guaranteed error screen, and mirrors exactly what the working
paths do by hand.

## Files
- ui/feed/FeedPlayer.kt (automatic second extraction pass)
