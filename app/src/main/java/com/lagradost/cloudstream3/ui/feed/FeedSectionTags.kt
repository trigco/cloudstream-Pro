package com.lagradost.cloudstream3.ui.feed

import java.util.Locale

/**
 * Phase 8.91 — provider homepage SECTIONS as the Feed's primary
 * category source. The providers themselves curate their shelves
 * (MovieBox declares 39, MovieLinkBD 18, Rtally 8, Mlsbd 7 — and
 * the Netflix / Prime Video / Hotstar / Disney plugins build
 * theirs inside getMainPage), so a shelf's name is exact data,
 * not a guess: "Ongoing Series", "Bangla Dubbed" and "Dual Audio"
 * are labels no metadata resolver could ever derive. The pool
 * keeps the raw shelf names on every entry (see FeedViewModel);
 * this object normalises them onto ONE canonical vocabulary so
 * the same shelf from different providers merges into one Feed
 * row ("Korean Drama" from MovieBox, "Korean" from Rtally and
 * MovieLinkBD all become K-Drama).
 *
 * Anything a rule cannot place passes through under the
 * provider's own label — it is still curated data, and the Feed
 * Categories settings page lets the user hide or reorder it.
 * Only the generic buckets in [JUNK] are dropped as tags (their
 * items stay in the For You grid).
 */
object FeedSectionTags {

    const val TRENDING = "Trending"
    const val TOP_SERIES = "Top Series"
    const val ONGOING = "Ongoing Series"
    const val ASIAN_DRAMA = "Asian Drama"
    const val K_DRAMA = "K-Drama"
    const val C_DRAMA = "C-Drama"
    const val J_DRAMA = "J-Drama"
    const val THAI_DRAMA = "Thai Drama"
    const val TURKISH = "Turkish Dizi"
    const val BOLLYWOOD = "Bollywood"
    const val HOLLYWOOD = "Hollywood"
    const val SOUTH_INDIAN = "South Indian"
    const val BANGLA = "Bangla"
    const val BANGLA_DUBBED = "Bangla Dubbed"
    const val ANIME = "Anime"
    const val DUAL_AUDIO = "Dual Audio"
    const val REALITY = "Reality TV"
    const val WWE = "WWE"
    const val INDIAN_DRAMA = "Indian Drama"
    const val INDIAN_SERIES = "Indian Series"
    const val FAMILY = "Family"

    /** The 18 canonical genre names — mirrors FeedFragment's
     *  genreKeywords vocabulary (the chip / Filters-sheet list). */
    val GENRES: List<String> = listOf(
        "Action", "Adventure", "Animation", "Comedy", "Crime",
        "Documentary", "Drama", "Family", "Fantasy", "History",
        "Horror", "Music", "Mystery", "Romance", "Sci-Fi",
        "Thriller", "War", "Western"
    )

    /** Curated region / format tags above the genres. */
    val CURATED: List<String> = listOf(
        TRENDING, ONGOING, TOP_SERIES, K_DRAMA, C_DRAMA, J_DRAMA,
        ASIAN_DRAMA, TURKISH, THAI_DRAMA, ANIME, BOLLYWOOD,
        HOLLYWOOD, SOUTH_INDIAN, BANGLA, BANGLA_DUBBED,
        INDIAN_DRAMA, INDIAN_SERIES, DUAL_AUDIO, REALITY, WWE
    )

    /** Every tag this mapping can produce by rule (passthrough
     *  labels excluded — they are discovered from the pool). */
    val KNOWN_TAGS: List<String> = CURATED + GENRES

    /** Default display order for section-driven rows when the
     *  user has not saved their own (Feed Categories settings). */
    val CANONICAL_ORDER: List<String> = listOf(
        TRENDING, ONGOING, TOP_SERIES, K_DRAMA, C_DRAMA, J_DRAMA,
        ASIAN_DRAMA, TURKISH, THAI_DRAMA, ANIME, BOLLYWOOD,
        HOLLYWOOD, SOUTH_INDIAN, BANGLA, BANGLA_DUBBED,
        INDIAN_DRAMA, INDIAN_SERIES, DUAL_AUDIO, REALITY, WWE,
        "Action", "Adventure", "Comedy", "Crime", "Documentary",
        "Drama", "Family", "Fantasy", "History", "Horror",
        "Music", "Mystery", "Romance", "Sci-Fi", "Thriller",
        "War", "Western", "Animation"
    )

    /** Generic buckets that name no real category. Their items
     *  stay in the pool; only the tag is dropped. */
    private val JUNK: Set<String> = setOf(
        "", "all", "all categories", "all movies", "all web series",
        "movies", "movie", "series", "tv shows", "tv show", "shows",
        "recently updated", "home", "categories"
    )

    private val GENRE_LOOKUP: Map<String, String> = run {
        val map = mutableMapOf<String, String>()
        GENRES.forEach { map[it.lowercase(Locale.US)] = it }
        map["sci fi"] = "Sci-Fi"
        map["science fiction"] = "Sci-Fi"
        map["science-fiction"] = "Sci-Fi"
        map
    }

    private fun normalize(raw: String): String = raw
        .lowercase(Locale.US)
        .replace(Regex("[\\s_]+"), " ")
        .trim()
        .trimEnd('.', ':', '-', '•', '|', '/')
        .trim()

    /**
     * The canonical tag for one raw provider shelf name, or
     * null for the generic buckets in [JUNK]. Rules run in
     * order; the specific mixed/region buckets are tested
     * before the generic genre rules so "Korean Drama" can
     * never collapse into the Drama genre.
     */
    fun canonicalTag(raw: String): String? {
        val n = normalize(raw)
        if (n in JUNK) return null
        // The mixed K/J/C bucket is its own shelf, NOT K-Drama.
        if (n.contains("k/j/c") || n.contains("kjc") ||
            (n.contains("korean") && n.contains("chinese"))
        ) {
            return ASIAN_DRAMA
        }
        if (n.contains("trending") || n.contains("featured")) {
            return TRENDING
        }
        if (n.contains("top series")) return TOP_SERIES
        if (n.contains("ongoing")) return ONGOING
        if (n.contains("bangla") && n.contains("dubbed")) {
            return BANGLA_DUBBED
        }
        if (n.contains("dual audio")) return DUAL_AUDIO
        if (n.contains("south indian")) return SOUTH_INDIAN
        if (n.contains("korean") || n == "k-drama" || n == "kdrama" ||
            n.contains("k drama")
        ) {
            return K_DRAMA
        }
        if (n.contains("chinese") || n == "c-drama" || n == "cdrama" ||
            n.contains("c drama")
        ) {
            return C_DRAMA
        }
        if (n.contains("japanese") || n == "j-drama" || n == "jdrama" ||
            n.contains("j drama")
        ) {
            return J_DRAMA
        }
        if (n.contains("turkish")) return TURKISH
        if (n.contains("thai")) return THAI_DRAMA
        if (n.contains("bollywood") || n.contains("hindi movie") ||
            n == "hindi" ||
            (n.contains("indian") && n.contains("movie"))
        ) {
            return BOLLYWOOD
        }
        if (n.contains("indian") && n.contains("drama")) {
            return INDIAN_DRAMA
        }
        if (n.contains("indian") && n.contains("series")) {
            return INDIAN_SERIES
        }
        if (n.contains("hollywood") || n == "english" ||
            n.contains("usa")
        ) {
            return HOLLYWOOD
        }
        if (n.contains("bangla") || n.contains("bengali")) {
            return BANGLA
        }
        if (n.contains("anime") || n == "animation") return ANIME
        if (n.contains("kids")) return FAMILY
        if (n.contains("reality")) return REALITY
        if (n.contains("wwe") || n.contains("wrestling")) return WWE
        // Genre shelves: exact name, or the name carrying a
        // generic "Movies" / "Series" tail ("Action Movies").
        GENRE_LOOKUP[n]?.let { return it }
        val stripped = n
            .removeSuffix(" movies").removeSuffix(" movie")
            .removeSuffix(" series").removeSuffix(" shows")
            .trim()
        GENRE_LOOKUP[stripped]?.let { return it }
        // Passthrough: the provider's own label, kept as-is.
        return raw.trim().takeIf { it.length >= 2 }
    }
}
