package com.lagradost.cloudstream3.ui.search

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 8.56 — universal ratings (his ask): many providers ship no
 * score, so cards and the details page show no rating at all. When
 * the user has entered a TMDB and/or IMDb (OMDb) API key under
 * Settings → Account, this resolves a rating by cleaned title +
 * year in the background and caches it persistently, TitleResolver
 * style. Matching is deliberately conservative — a wrong rating is
 * worse than none: the title must match (normalised) and, when
 * both years are known, agree within a year. No key, no lookup.
 */
object MetadataResolver {
    private const val PREFS = "universal_ratings_v1"
    const val TMDB_KEY_PREF = "tmdb_api_key"
    const val OMDB_KEY_PREF = "omdb_api_key"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(3)
    private val inFlight = mutableSetOf<String>()
    private val misses = mutableSetOf<String>()
    private val memory = ConcurrentHashMap<String, String>()
    private val listeners = mutableListOf<(String, String) -> Unit>()

    fun addListener(listener: (String, String) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: (String, String) -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun hasAnyKey(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return !prefs.getString(TMDB_KEY_PREF, null).isNullOrBlank() ||
            !prefs.getString(OMDB_KEY_PREF, null).isNullOrBlank()
    }

    private fun cacheKey(title: String, year: Int?): String =
        normalize(title) + "|" + (year?.toString() ?: "")

    /** The listener/cache key for a raw provider title + year. */
    fun keyFor(rawTitle: String, year: Int?): String =
        cacheKey(SearchResultBuilder.cleanPosterTitle(rawTitle), year)

    private fun normalize(title: String): String =
        title.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    /** The year a search response carries, where the class has one. */
    fun yearOf(response: SearchResponse): Int? = when (response) {
        is MovieSearchResponse -> response.year
        is TvSeriesSearchResponse -> response.year
        is AnimeSearchResponse -> response.year
        else -> null
    }

    private fun isTv(response: SearchResponse): Boolean = when (response.type) {
        TvType.TvSeries, TvType.AsianDrama, TvType.Anime, TvType.OVA -> true
        else -> false
    }

    fun cached(context: Context?, rawTitle: String, year: Int?): String? {
        val key = cacheKey(SearchResultBuilder.cleanPosterTitle(rawTitle), year)
        memory[key]?.let { return it }
        if (context == null) return null
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)
        if (!stored.isNullOrBlank()) memory[key] = stored
        return stored
    }

    /** Convenience for card binds: provider score wins, else cache. */
    fun cachedFor(context: Context?, response: SearchResponse): String? {
        response.score?.toStringNull(0.1, 10, 1)?.let { return it }
        return cached(context, response.name, yearOf(response))
    }

    fun resolve(context: Context, response: SearchResponse) {
        if (response.score != null) return
        resolve(context, response.name, yearOf(response), isTv(response))
    }

    fun resolve(context: Context, rawTitle: String, year: Int?, tv: Boolean) {
        val title = SearchResultBuilder.cleanPosterTitle(rawTitle)
        if (title.isBlank() || TitleResolver.isIdLike(title)) return
        if (!hasAnyKey(context)) return
        val key = cacheKey(title, year)
        if (memory.containsKey(key)) return
        synchronized(misses) { if (key in misses) return }
        synchronized(inFlight) { if (!inFlight.add(key)) return }
        val appContext = context.applicationContext
        scope.launch {
            try {
                semaphore.withPermit {
                    val rating = lookup(appContext, title, year, tv)
                    if (rating != null) {
                        memory[key] = rating
                        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putString(key, rating).apply()
                        val snapshot = synchronized(listeners) { listeners.toList() }
                        withContext(Dispatchers.Main) {
                            snapshot.forEach { it(key, rating) }
                        }
                    } else {
                        synchronized(misses) { misses.add(key) }
                    }
                }
            } catch (e: Exception) {
                synchronized(misses) { misses.add(key) }
            } finally {
                synchronized(inFlight) { inFlight.remove(key) }
            }
        }
    }

    private suspend fun lookup(context: Context, title: String, year: Int?, tv: Boolean): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val tmdbKey = prefs.getString(TMDB_KEY_PREF, null)?.trim().orEmpty()
        if (tmdbKey.isNotEmpty()) {
            // Phase 8.71: providers regularly mislabel the kind (an
            // anime-tagged film, a series listed as a movie) — if the
            // requested kind misses, try the other one before giving
            // up. His report: key set, yet many providers showed no
            // rating at all.
            tmdb(tmdbKey, title, year, tv)?.let { return it }
            tmdb(tmdbKey, title, year, !tv)?.let { return it }
        }
        val omdbKey = prefs.getString(OMDB_KEY_PREF, null)?.trim().orEmpty()
        if (omdbKey.isNotEmpty()) {
            omdb(omdbKey, title, year)?.let { return it }
        }
        return null
    }

    /**
     * Phase 8.57: fetches go through the app's own HTTP client
     * (the same stack the providers use successfully) instead of
     * a raw HttpURLConnection — his report: a valid key fetched
     * nothing at all, and the raw connection path is the one
     * thing that differs from every working request in the app.
     * A TMDB "Read Access Token" (starts with eyJ) is sent as a
     * Bearer header; a v3 API key goes in the query string.
     */
    private suspend fun getJson(url: String, bearer: String?): JSONObject? {
        return try {
            val response = app.get(
                url,
                headers = if (bearer != null) {
                    mapOf("Authorization" to "Bearer $bearer")
                } else {
                    emptyMap()
                }
            )
            if (response.code !in 200..299) null else JSONObject(response.text)
        } catch (e: Exception) {
            null
        }
    }

    private fun tmdbAuth(key: String): Pair<String, String?> =
        if (key.startsWith("eyJ")) "" to key else key to null

    /** Settings key test: 200 = works, 401/403 = rejected,
     *  anything else / -1 = could not verify. */
    suspend fun testTmdbKey(key: String): Int {
        val (apiKey, bearer) = tmdbAuth(key.trim())
        val url = if (bearer != null) {
            "https://api.themoviedb.org/3/configuration"
        } else {
            "https://api.themoviedb.org/3/configuration?api_key=$apiKey"
        }
        return try {
            val response = app.get(
                url,
                headers = if (bearer != null) {
                    mapOf("Authorization" to "Bearer $bearer")
                } else {
                    emptyMap()
                }
            )
            response.code
        } catch (e: Exception) {
            -1
        }
    }

    /** Settings key test for OMDb: true = the key answers. */
    suspend fun testOmdbKey(key: String): Boolean {
        val json = getJson(
            "https://www.omdbapi.com/?apikey=${key.trim()}&t=Inception",
            null
        ) ?: return false
        return json.optString("Response") == "True"
    }

    private fun titlesMatch(candidate: String, wanted: String): Boolean {
        val a = normalize(candidate)
        val b = normalize(wanted)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    private suspend fun tmdb(apiKey: String, title: String, year: Int?, tv: Boolean): String? {
        // Strict pass first: year-filtered search, year must agree.
        tmdbOnce(apiKey, title, year, tv, enforceYear = true)?.let { return it }
        // Phase 8.71: a wrong provider year makes TMDB's own year
        // filter return zero results - retry unfiltered and accept
        // the best title match regardless of year.
        if (year != null) {
            tmdbOnce(apiKey, title, null, tv, enforceYear = false)?.let { return it }
        }
        return null
    }

    private suspend fun tmdbOnce(
        apiKey: String,
        title: String,
        year: Int?,
        tv: Boolean,
        enforceYear: Boolean
    ): String? {
        val kind = if (tv) "tv" else "movie"
        val query = URLEncoder.encode(title, "UTF-8")
        val yearParam = when {
            year == null -> ""
            tv -> "&first_air_date_year=$year"
            else -> "&year=$year"
        }
        val (keyParam, bearer) = tmdbAuth(apiKey)
        val authParam = if (bearer != null) "" else "api_key=$keyParam&"
        val json = getJson(
            "https://api.themoviedb.org/3/search/$kind" +
                "?$authParam" + "query=$query$yearParam",
            bearer
        ) ?: return null
        val results = json.optJSONArray("results") ?: return null
        for (i in 0 until minOf(results.length(), 5)) {
            val item = results.optJSONObject(i) ?: continue
            val candidate = item.optString(
                if (tv) "name" else "title",
                item.optString(if (tv) "original_name" else "original_title")
            )
            if (!titlesMatch(candidate, title)) continue
            val dateText = item.optString(
                if (tv) "first_air_date" else "release_date"
            )
            val candidateYear = dateText.take(4).toIntOrNull()
            if (enforceYear && year != null && candidateYear != null &&
                kotlin.math.abs(candidateYear - year) > 1
            ) continue
            val votes = item.optInt("vote_count", 0)
            val average = item.optDouble("vote_average", 0.0)
            if (votes < 10 || average <= 0.0) continue
            return String.format(Locale.US, "%.1f", average)
        }
        return null
    }

    private suspend fun omdb(apiKey: String, title: String, year: Int?): String? {
        omdbOnce(apiKey, title, year)?.let { return it }
        // Phase 8.71: same wrong-year trap as TMDB - retry yearless.
        if (year != null) omdbOnce(apiKey, title, null)?.let { return it }
        return null
    }

    private suspend fun omdbOnce(apiKey: String, title: String, year: Int?): String? {
        val query = URLEncoder.encode(title, "UTF-8")
        val yearParam = if (year != null) "&y=$year" else ""
        val json = getJson(
            "https://www.omdbapi.com/?apikey=$apiKey&t=$query$yearParam",
            null
        ) ?: return null
        if (json.optString("Response") != "True") return null
        val candidate = json.optString("Title")
        if (!titlesMatch(candidate, title)) return null
        val rating = json.optString("imdbRating")
        if (rating.isBlank() || rating == "N/A") return null
        return rating
    }
}
