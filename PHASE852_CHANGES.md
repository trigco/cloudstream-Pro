# Phase 8.52 — Family Mode: metadata keywords, his specification

## His verdict on 8.51 (screenshot)
Searching "18+" in the Feed (Family Mode on) still returned
adult web series: "Friends Sex and Games" (Mlsbd), "Bad Boyy
Part 2 Hindi Ullu – S01", "Dutdot Tagalong Vivamax",
"Dil Padosi Hindi Shemaroome – S01". His instruction:
"এভাবে হবে না, আমাদেরকে metadata ইউজ করা লাগবে — 18+, adult,
porn, sex, xxx, xx etc, তাইলে কাজে আসবে" (use metadata —
18+, adult, porn, sex, xxx, xx etc — then it will work).

The 8.51 list was too cautious: standalone "sex" was not a
keyword, and the adult-OTT platform tags embedded in titles
were not used at all.

## Change (utils/FamilyMode.kt only)
ADULT_TITLE_PHRASES gains "18+" (substring).
ADULT_TITLE_WORDS gains, exactly per his list: "sex", "sexy",
"adult", "xx" (alongside existing porn/xxx/etc) — plus the
metadata he pointed at: the 18+ OTT platform names that ride
inside titles (ullu, vivamax, kooku, primeplay,
rabbitmovies, shemaroome, nuefliks, flizmovies, hothit,
gupchup) and the descriptors uncut / unrated / lust.

All four titles in his screenshot now match: Sex (token),
Ullu, Vivamax, Shemaroome. Same single matcher serves Home,
hero pools, Feed, feed search, Search and the details poster
gate, so no call site changed.

Known trade (his choice): a mainstream title containing one
of these whole words (e.g. "Sex Education") is hidden too
while Family Mode is on.
