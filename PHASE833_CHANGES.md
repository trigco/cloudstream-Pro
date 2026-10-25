# Phase 8.33 — title resolution, badges, cast avatar, feed refresh (PR #7)

Owner report on 8.32 (2026-10-25): home poster cards show an
empty title band; dub/sub/rating missing; photo-less cast show
initials text; feed pull-to-refresh seems dead; feed cards still
show "Id <number>" titles.

Root cause for the titles: this provider family (aoneroom-backed
Hotstar/Disney/Netflix/Prime) ships homepage/search items whose
name is blank or a placeholder like "Id 82706180" — the real
title only exists on the details page. bind() faithfully showed
the blank, leaving the expanded card's reserved title area
empty. Rating on those rows is data-bound (their homepage items
carry no score; the details page has it via load()).

- NEW ui/search/TitleResolver: background load()-based title
  resolution (the player's mechanism), max 3 concurrent,
  20 s timeout, in-flight dedupe, persistent cache
  (resolved_titles_v1) so each item is fetched once, ever.
  SearchResultBuilder.bind and the feed cards read the cache
  first and trigger resolution for blank/ID-like names;
  listeners swap the real title in when it lands
  (HomeFragment rebinds, FeedAdapter updates the card).
- SearchResultBuilder: dub/sub badge for ALL response types —
  the language is read from the title's brackets ("[Hindi Dub]")
  instead of being stripped silently by the title cleaner
  (badges were anime-only). Stale badge text is cleared on bind.
- Cast: the initials circle is replaced by a person-silhouette
  avatar (ic_cast_avatar on the same circle surface); a loaded
  photo still covers it.
- Feed refresh: pull-to-refresh now fetches the next page and
  PREPENDS it (plus scroll-to-top), so a refresh visibly changes
  the feed; a pull during a build is queued instead of dropped.
  Endless scroll keeps appending via loadMore().
