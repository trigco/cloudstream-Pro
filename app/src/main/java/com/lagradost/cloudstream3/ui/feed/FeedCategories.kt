package com.lagradost.cloudstream3.ui.feed

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.search.MetadataResolver
import java.util.Locale

/**
 * Phase 8.88 — real Feed categories from resolved metadata
 * (MetadataResolver: TMDB / TVmaze / AniList / OMDb), replacing
 * guesswork where data exists. One shared derivation used by
 * the Categories tile row and the genre filtering:
 *
 * An item's categories come from its type (the details-page
 * type where TitleResolver learned one, else the listing's)
 * plus the resolved (genres, original language, origin
 * country). An item can carry several categories; an
 * unresolved item carries none — the 8.87 title-keyword
 * matcher still partially covers it for genre chips/filters
 * (union rule, see FeedFragment.genreMatchesEntry).
 */
object FeedCategories {

    /** Smart categories for the tile row, in display order. */
    val SMART_CATEGORIES: List<String> = listOf(
        "K-Drama", "C-Drama", "J-Drama", "Thai Drama",
        "Turkish Dizi", "Anime", "Bollywood", "Hollywood"
    )

    private val SERIES_TYPES = setOf(
        TvType.TvSeries, TvType.AsianDrama, TvType.Anime, TvType.OVA
    )
    private val MOVIE_TYPES = setOf(TvType.Movie, TvType.AnimeMovie)

    fun categoriesOf(
        type: TvType?,
        meta: MetadataResolver.Meta?
    ): Set<String> {
        val out = mutableSetOf<String>()
        if (meta == null) return out
        val lang = meta.language?.lowercase(Locale.US)
        val country = meta.country?.uppercase(Locale.US)
        val series = type in SERIES_TYPES
        val movie = type in MOVIE_TYPES
        val animationGenre = meta.genres.any {
            it.equals("Animation", ignoreCase = true)
        }
        val isAnime = type == TvType.Anime || type == TvType.OVA ||
            meta.anime || (lang == "ja" && animationGenre)

        if (isAnime) out += "Anime"
        if (series && (lang == "ko" || country == "KR")) {
            out += "K-Drama"
        }
        if (series && (lang == "zh" || country == "CN" ||
                country == "TW" || country == "HK")
        ) {
            out += "C-Drama"
        }
        if (series && !isAnime && (lang == "ja" || country == "JP")) {
            out += "J-Drama"
        }
        if (series && (lang == "th" || country == "TH")) {
            out += "Thai Drama"
        }
        if (series && (lang == "tr" || country == "TR")) {
            out += "Turkish Dizi"
        }
        if (movie && (lang == "hi" || country == "IN")) {
            out += "Bollywood"
        }
        if (lang == "en" && (country == "US" || country == "GB")) {
            out += "Hollywood"
        }
        // Every resolved genre is a category of its own.
        out += meta.genres
        return out
    }

    /**
     * The item's resolved genres mapped onto the 8.87 chip
     * vocabulary, so a chip matches resolved data with the same
     * names the keyword matcher uses: TMDB's "Science Fiction"
     * and TVmaze's "Science-Fiction" both become "Sci-Fi",
     * compound TMDB TV genres expand to their parts.
     */
    fun canonicalGenres(meta: MetadataResolver.Meta?): Set<String> {
        if (meta == null) return emptySet()
        val out = mutableSetOf<String>()
        meta.genres.forEach { name ->
            when (name.trim().lowercase(Locale.US)) {
                "science fiction", "science-fiction" -> out += "Sci-Fi"
                "sci-fi & fantasy" -> {
                    out += "Sci-Fi"
                    out += "Fantasy"
                }
                "action & adventure" -> {
                    out += "Action"
                    out += "Adventure"
                }
                "war & politics" -> out += "War"
                "kids" -> out += "Family"
                "romantic" -> out += "Romance"
                else -> out += name.trim()
            }
        }
        return out
    }
}
