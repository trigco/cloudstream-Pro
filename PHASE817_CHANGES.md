# Phase 8.17 — Clean titles, living categories (owner requests, 2026-10-24)

Pushed onto the animation-suite branch, so PR #6 now carries 8.15 + 8.16 +
8.17 in the same single build. Merge gate unchanged: owner's verdict.

## 1. Poster titles: clean title only, season kept for series

Provider names arrive packed: "DeSu7 (2026) [HDTS Best Quality]",
"Bachelor Point (2026) S05 [Ep 121-128 on Bongo]". New
`SearchResultBuilder.cleanPosterTitle()` (used for the poster label in
`bind()`, so home rows, the expanded grid and search all match):

- drops everything in `[...]`, the `(year)`, and quality words
  (HDTS/HDTC/HDCAM/Full HD/WEB-DL/4K/…)
- keeps a season marker and normalises it to the end: "Bachelor Point
  S05", "MobLand S02", "Naruto Shippūden S17"
- display only — the underlying response keeps its full name for search
  matching and the details page; if cleaning would empty the label, the
  raw name is used.

## 2. Category content changes on every refresh

`HomeViewModel.load()` now rotates each category's items by a random
offset when a homepage is built (lists of 4+), so a refresh leads with
different posters. Expansions append to the same list afterwards, so
order stays stable while browsing deeper.

## 3. Live auto-rotation, animated

While the home screen sits open (phone), every 20 s each **visible**
category row steps one poster forward (first item moves to the back of
the row) via the row adapter's differ, so the row's item animator plays
the change — someone watching the screen sees the shelves move on their
own. Guards: fragment resumed, both lists idle (nobody scrolling or
dragging), rows with fewer than 4 items skipped, timer removed in
`onDestroyView`.

## 4. Drag to reorder categories, saved

Phone home: long-press a category row's title strip and drag it up/down
(`ItemTouchHelper`, long-press drag; haptic tick on pickup). The hero
header (position 0) never moves; poster touches belong to the rows' own
lists, so poster long-press menus are untouched. On drop the category
name order is persisted (SharedPreferences, unit-separator joined) and
re-applied to every fresh load; unknown categories keep their load order
after the known ones.

Files: `ui/search/SearchResultBuilder.kt`, `ui/home/HomeViewModel.kt`,
`ui/home/HomeFragment.kt`.
