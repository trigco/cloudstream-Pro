package com.lagradost.cloudstream3.utils

import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

/**
 * Phase 8.50/8.51: Family Mode. When on, adult (18+) providers
 * and adult content are hidden across Home, Feed, Search and
 * the provider picker, and an adult title opened directly
 * shows no poster art.
 *
 * Signals, strongest first:
 * - the TvType.NSFW the provider itself declares;
 * - the provider/section NAME (dedicated adult providers and
 *   adult shelves on mixed providers label themselves);
 * - the item TITLE / metadata (phase 8.51: mixed providers
 *   like MovieBox file porn under the plain "Movie" type, so
 *   the title is the only signal — explicit phrases match as
 *   substrings of the normalised title, explicit words match
 *   whole tokens, so innocent titles that merely contain
 *   letters are safe).
 *
 * Phase 8.52 (his call: "metadata use korte hobe — 18+,
 * adult, porn, sex, xxx, xx etc"): the net is deliberately
 * wide. Standalone sex/adult/xx are keywords, and so are the
 * 18+ OTT platform names that ride inside titles as metadata
 * (a title tagged Ullu / Vivamax / Kooku / PrimePlay /
 * Shemaroome IS an adult web series, whatever its words say).
 * The cost — a rare mainstream title containing one of these
 * words is hidden too — is the trade he asked for: power over
 * precision.
 */
object FamilyMode {
    const val KEY = "family_mode_v1"

    private val ADULT_API_WORDS = listOf(
        "18+", "nsfw", "porn", "adult", "xxx", "erotic", "hentai"
    )

    private val ADULT_TITLE_PHRASES = listOf(
        "18+", "anal sex", "oral sex", "group sex", "rough sex",
        "hot sex", "sex tape", "sex with", "big tits",
        "big boobs", "big dick", "big cock", "cum shot",
        "cumshot", "blow job", "blowjob", "hand job",
        "handjob", "deep throat", "deepthroat", "gang bang",
        "gangbang", "double penetration", "golden shower",
        "piss facial", "cream pie"
    )

    private val ADULT_TITLE_WORDS = setOf(
        "porn", "porno", "xxx", "xx", "sex", "sexy", "adult",
        "creampie", "orgy", "threesome", "incest", "milf",
        "hentai", "erotic", "erotica", "pussy", "cock",
        "cocks", "cum", "dildo", "vibrator", "busty", "horny",
        "lesbian", "uncut", "unrated", "lust",
        // 18+ OTT platforms whose names ride in titles.
        "ullu", "vivamax", "kooku", "primeplay",
        "rabbitmovies", "shemaroome", "nuefliks", "flizmovies",
        "hothit", "gupchup"
    )

    private val ADULT_TITLE_STEMS = listOf(
        "fuck", "masturbat", "squirt"
    )

    private val TOKEN_SPLIT = Regex("[^a-z0-9]+")

    fun isEnabled(): Boolean = try {
        val ctx = CloudStreamApp.context ?: return false
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .getBoolean(KEY, false)
    } catch (t: Throwable) {
        false
    }

    fun isAdultApi(api: MainAPI): Boolean {
        if (api.supportedTypes.contains(TvType.NSFW)) return true
        val name = api.name.lowercase()
        return ADULT_API_WORDS.any { name.contains(it) }
    }

    fun isAdultTitle(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.lowercase()
        if (ADULT_TITLE_PHRASES.any { lower.contains(it) }) return true
        val tokens = lower.split(TOKEN_SPLIT).filter { it.isNotEmpty() }
        return tokens.any { token ->
            token in ADULT_TITLE_WORDS ||
                ADULT_TITLE_STEMS.any { token.startsWith(it) }
        }
    }

    /** A whole homepage/feed section (shelf) labelled adult. */
    fun isAdultSection(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        if (isAdultTitle(name)) return true
        val lower = name.lowercase()
        if (lower.contains("18+") || lower.contains("nsfw")) return true
        return lower.split(TOKEN_SPLIT).any { it == "adult" }
    }

    fun isAdultItem(response: SearchResponse): Boolean =
        response.type == TvType.NSFW || isAdultTitle(response.name)

    fun isAdultItem(type: TvType?): Boolean = type == TvType.NSFW
}
