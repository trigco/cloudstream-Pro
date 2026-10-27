package com.lagradost.cloudstream3.ui.search

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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

    private fun lookup(context: Context, title: String, year: Int?, tv: Boolean): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val tmdbKey = prefs.getString(TMDB_KEY_PREF, null)?.trim().orEmpty()
        if (tmdbKey.isNotEmpty()) {
            tmdb(tmdbKey, title, year, tv)?.let { return it }
        }
        val omdbKey = prefs.getString(OMDB_KEY_PREF, null)?.trim().orEmpty()
        if (omdbKey.isNotEmpty()) {
            omdb(omdbKey, title, year)?.let { return it }
        }
        return null
    }

    private fun getJson(url: String): JSONObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("Accept", "application/json")
        return try {
            if (conn.responseCode !in 200..299) return null
            JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun titlesMatch(candidate: String, wanted: String): Boolean {
        val a = normalize(candidate)
        val b = normalize(wanted)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    private fun tmdb(apiKey: String, title: String, year: Int?, tv: Boolean): String? {
        val kind = if (tv) "tv" else "movie"
        val query = URLEncoder.encode(title, "UTF-8")
        val yearParam = when {
            year == null -> ""
            tv -> "&first_air_date_year=$year"
            else -> "&year=$year"
        }
        val json = getJson(
            "https://api.themoviedb.org/3/search/$kind" +
                "?api_key=$apiKey&query=$query$yearParam"
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
            if (year != null && candidateYear != null &&
                kotlin.math.abs(candidateYear - year) > 1
            ) continue
            val votes = item.optInt("vote_count", 0)
            val average = item.optDouble("vote_average", 0.0)
            if (votes < 10 || average <= 0.0) continue
            return String.format(Locale.US, "%.1f", average)
        }
        return null
    }

    private fun omdb(apiKey: String, title: String, year: Int?): String? {
        val query = URLEncoder.encode(title, "UTF-8")
        val yearParam = if (year != null) "&y=$year" else ""
        val json = getJson(
            "https://www.omdbapi.com/?apikey=$apiKey&t=$query$yearParam"
        ) ?: return null
        if (json.optString("Response") != "True") return null
        val candidate = json.optString("Title")
        if (!titlesMatch(candidate, title)) return null
        val rating = json.optString("imdbRating")
        if (rating.isBlank() || rating == "N/A") return null
        return rating
    }
}
