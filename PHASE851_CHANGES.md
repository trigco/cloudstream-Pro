# Phase 8.51 — Family Mode gets teeth, distinct settings icons,
# feed title junk cut

## His verdict on 8.50 (screenshots)
1. Family Mode ON, yet the Feed still showed explicit porn —
   every item labelled "MovieBox • Movie". MovieBox is a
   mainstream provider whose adult shelf is filed under the
   plain Movie type; nothing declares NSFW, so type-only
   filtering could never catch it.
2. Layout, Categories and Family Mode rows all shared one
   icon (ic_settings_layout).
3. The feed watch title showed the raw MLSBD release name
   ("Alaap Bengali Hoichoi – 480P | 720P | 1080P – x264 –
   250MB | 800MB | 1.8GB ESub – Do…").

## Family Mode v2 (utils/FamilyMode.kt)
- isAdultTitle(name): explicit phrases ("anal sex", "cumshot",
  "blowjob", "gang bang", …) match as substrings; explicit
  words (porn, xxx, creampie, milf, hentai, pussy, cum, …)
  match whole tokens only; stems (fuck*, masturbat*, squirt*)
  match token prefixes. Innocent lookalikes stay safe
  ("Puss in Boots", "Hancock", "Sex Education", "Naked Gun").
- isAdultItem(SearchResponse) = declared NSFW type OR adult
  title. All 8.50 call sites (Home rows, hero pools, Feed,
  feed search, Search + load-more, details poster gate)
  now use it.
- isAdultSection(name): whole homepage/feed shelves labelled
  adult (title signal, "18+", "nsfw", or the token "adult")
  are dropped as a unit — Home rows in applyHomeCategories
  and the Feed pool in buildFeed.

## Icons
- Categories row: baseline_list_alt_24 (list icon).
- Family Mode row: new ic_settings_family — a shield with a
  check, drawn in the same white-stroke style as the other
  settings icons. Layout keeps the grid icon; all three rows
  are now distinct.

## Title junk (ui/search/SearchResultBuilder.cleanPosterTitle)
- truncateReleaseJunk(): everything from the first
  quality/size/codec token (240p–4320p, x264/x265, h.264/265,
  HEVC, 10bit/8bit, NN MB/GB) on is cut before the existing
  cleanup runs. "Alaap Bengali Hoichoi – 480P | …" becomes
  "Alaap Bengali Hoichoi". Applies everywhere cleanPosterTitle
  is used (feed cards, feed watch header, home cards).
