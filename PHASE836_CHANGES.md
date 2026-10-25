# Phase 8.36 — feed empty state + chip honesty (PR #7)

Owner: genre tags under the feed search bar too often end at
"No content", and a blank feed should show premium vector art
instead of a lone line of text.

- Empty state redesigned: a centered box with a custom vector
  illustration (feed_empty_art: film frame + red play mark on a
  soft disc), the message, and a "Show all" button that clears
  the search and reselects the All chip — a dead end is now a
  designed state with an exit. All six empty toggles route
  through FeedFragment.setFeedEmpty.
- Genre chips with zero matches in the current pool render
  dimmed (alpha 0.45), recomputed on every render, so an empty
  tag is visible before it is tapped. Provider search can
  still surface results for a dimmed chip.

## Files
- res/layout/fragment_feed.xml, res/drawable/feed_empty_art.xml
- ui/feed/FeedFragment.kt, res/values/strings.xml
