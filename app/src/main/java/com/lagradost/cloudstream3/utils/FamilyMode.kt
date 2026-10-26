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
        "piss facial", "cream pie", "hot babe",
        "casting couch", "cheating wife", "cheating husband",
        "rated x", "x rated", "body massage",
        "full body massage", "happy ending massage"
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
        "hothit", "gupchup",
        // Phase 8.54: scene-title vocabulary, from the titles
        // that leaked through on his device (his call: "HOT
        // babe, BBC, inappropriate — these ARE adult words").
        "babe", "babes", "bbc", "pov", "inappropriate",
        "pervert", "perverted", "masseur", "masseuse",
        "nuru", "tantric", "sensual",
        "pornstar", "pornhub", "naughty", "kinky", "fetish",
        "bdsm", "bondage", "dominatrix", "gloryhole",
        "hardcore", "softcore", "gonzo", "ecchi", "oppai",
        "sekkusu", "jav", "onlyfans", "cuckold", "swinger",
        "swingers", "foursome", "uncensored", "lewd",
        "boobs", "tits", "nipples", "panties", "hookup",
        "bhabhi", "aunty", "stepmom", "stepdad", "stepsis",
        "stepbro", "stepbrother", "stepsister", "stepfather",
        "stepmother", "stepdaughter", "stepson", "stepfamily"
    )

    private val ADULT_TITLE_STEMS = listOf(
        "fuck", "masturbat", "squirt", "seduc", "stripper"
    )

    /**
     * Phase 8.54: non-Latin adult markers, matched as raw
     * substrings — his leaked feed had a pure-Japanese title
     * (セックス = "sex" in katakana), which no Latin keyword
     * list can ever see.
     */
    private val ADULT_SCRIPT_SUBSTRINGS = listOf(
        "セックス", "おまんこ", "エッチ", "ポルノ",
        "色情", "成人电影", "无码", "有码"
    )

    /**
     * Phase 8.53: well-known 18+ titles (IMDb/TV-parental
     * rated, erotic franchises) whose names carry NO keyword —
     * matched against the normalised title (lowercase, every
     * non-alphanumeric run collapsed to one space).
     */
    private val KNOWN_18_TITLES = listOf(
        "fifty shades", "365 days", "365 dni", "dark desire",
        "lust stories", "gandii baat", "charmsukh", "mastram",
        "rasbhari", "palang tod", "basic instinct", "showgirls",
        "euphoria", "toy boy", "sex life"
    )

    /** Innocent words that merely contain "sex". */
    private val SEX_SAFE_TOKENS = setOf(
        "essex", "sussex", "middlesex", "sexton", "sextant",
        "sexton", "sexagesimal"
    )

    private val TOKEN_SPLIT = Regex("[^a-z0-9]+")
    private val NORMALISE = Regex("[^a-z0-9]+")

    /**
     * Phase 8.53 (his instruction: "regex method use koro"):
     * providers glue keywords onto other words — "MovieXXX",
     * "SexStory", "XXXMovie". Split case transitions and
     * letter/digit boundaries first so the tokens surface.
     */
    private fun unglue(name: String): String = name
        .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
        .replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), " ")
        .replace(Regex("(?<=[a-zA-Z])(?=[0-9])"), " ")
        .replace(Regex("(?<=[0-9])(?=[a-zA-Z])"), " ")

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
        if (ADULT_SCRIPT_SUBSTRINGS.any { name.contains(it) }) {
            return true
        }
        val spaced = unglue(name)
        val lower = spaced.lowercase()
        if (ADULT_TITLE_PHRASES.any { lower.contains(it) }) return true
        val normalised = lower.replace(NORMALISE, " ").trim()
        if (KNOWN_18_TITLES.any { normalised.contains(it) }) return true
        val tokens = lower.split(TOKEN_SPLIT).filter { it.isNotEmpty() }
        return tokens.any { token ->
            token in ADULT_TITLE_WORDS ||
                ADULT_TITLE_STEMS.any { token.startsWith(it) } ||
                // Phase 8.53: keywords glued INSIDE a word
                // ("hotsex", "sexcam", "moviexxx") — tokenised
                // matching alone missed them (his report).
                token.contains("xxx") ||
                (token.contains("sex") && token !in SEX_SAFE_TOKENS)
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
