package com.lagradost.cloudstream3.nuvio

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.stremio.StremioStream
import com.lagradost.cloudstream3.ui.search.MetadataResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/**
 * Turns the app's identifiers into the numeric TMDB id Nuvio
 * scrapers are written against, runs every enabled scraper in
 * parallel, and maps the results onto the Stremio stream shape so
 * they merge with the addon fan-out in the bridge provider.
 */
object NuvioTmdb {

    fun apiKey(context: Context): String? =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(MetadataResolver.TMDB_KEY_PREF, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Accepts "tt…", "tmdb:123", "tmdb/123", "123", "tt…:1:2" and
     * returns the numeric TMDB id, converting IMDb ids through
     * TMDB's /find endpoint. Null when it cannot be resolved (most
     * often: no TMDB key set in Settings → Account).
     */
    suspend fun normalizeToTmdbId(
        context: Context,
        rawId: String,
        canonicalType: String,
    ): String? {
        var id = rawId.trim()
        if (id.isEmpty()) return null
        if (id.startsWith("tmdb:")) id = id.substring(5)
        if (id.startsWith("tmdb/")) id = id.substring(5)
        id = id.split('/').first()
        val colon = id.indexOf(':')
        if (colon > 0) id = id.substring(0, colon)
        if (id.all { it.isDigit() }) return id
        if (!id.startsWith("tt")) return null
        val key = apiKey(context) ?: return null
        return runCatching {
            val url = "https://api.themoviedb.org/3/find/$id?external_source=imdb_id"
            val response = if (key.startsWith("eyJ")) {
                app.get(url, headers = mapOf("Authorization" to "Bearer $key"))
            } else {
                app.get(url, params = mapOf("api_key" to key))
            }
            if (response.code !in 200..299) return@runCatching null
            val json = JSONObject(response.text)
            val results = if (canonicalType == "series") {
                json.optJSONArray("tv_results")
            } else {
                json.optJSONArray("movie_results")
            }
            val first = results?.optJSONObject(0) ?: return@runCatching null
            first.optInt("id").takeIf { it > 0 }?.toString()
        }.getOrNull()
    }
}

object NuvioStreamService {

    private const val CACHE_TTL_MS = 10 * 60 * 1000L
    private const val MAX_CACHE_ENTRIES = 120
    private const val MAX_CONCURRENT = 4

    private data class CacheEntry(
        val createdAt: Long,
        val results: List<StremioStream>,
    )

    private val cache = LinkedHashMap<String, CacheEntry>()
    private val gate = Semaphore(MAX_CONCURRENT)

    /**
     * Runs all enabled scrapers for one title. [id] is the bridge
     * payload id (usually an IMDb tt id, possibly tt:S:E for series
     * episodes — season/episode are passed separately when known).
     * Returns Stremio-shaped streams with direct URLs only; torrent
     * (infoHash) results are dropped in this step, same as addons.
     */
    suspend fun resolve(
        context: Context,
        id: String,
        canonicalType: String,
        season: Int? = null,
        episode: Int? = null,
    ): List<StremioStream> {
        val targets = NuvioRepository.activeScrapers(context)
            .filter { (_, scraper) -> scraper.supportsType(canonicalType) }
        if (targets.isEmpty()) return emptyList()
        val mediaType = NuvioScraperInfo.normalizeType(canonicalType)
        val tmdbId = NuvioTmdb.normalizeToTmdbId(context, id, canonicalType)
            ?: return emptyList()
        val tmdbKey = NuvioTmdb.apiKey(context)

        return coroutineScope {
            targets.map { (repo, scraper) ->
                async {
                    val settings = NuvioRepository.scraperSettings(
                        context, repo.manifestUrl, scraper.id
                    )
                    val cacheKey = listOf(
                        repo.manifestUrl, scraper.id, tmdbId, mediaType,
                        season?.toString() ?: "", episode?.toString() ?: "",
                        settings.toString()
                    ).joinToString("|")
                    lookupCache(cacheKey)?.let { return@async it }
                    val results = gate.withPermit {
                        runCatching {
                            val code = NuvioRepository.fetchCode(context, repo, scraper)
                                ?: return@runCatching emptyList()
                            val payload = NuvioEngine.run(
                                context = context,
                                repoUrl = repo.manifestUrl,
                                scraper = scraper,
                                code = code,
                                settings = settings,
                                tmdbKey = tmdbKey,
                                mode = NuvioEngine.Mode.STREAMS,
                                tmdbId = tmdbId,
                                mediaType = mediaType,
                                season = season,
                                episode = episode,
                            )
                            if (payload.has("error")) {
                                emptyList()
                            } else {
                                parseNuvioStreams(payload, scraper)
                                    .mapNotNull { it.toStremioStream() }
                            }
                        }.getOrElse { emptyList() }
                    }
                    storeCache(cacheKey, results)
                    results
                }
            }.awaitAll().flatten()
        }
    }

    private fun NuvioStreamResult.toStremioStream(): StremioStream? {
        val directUrl = url ?: return null // infoHash-only: next step
        return StremioStream(
            name = scraperName,
            title = title,
            description = null,
            url = directUrl,
            ytId = null,
            infoHash = null,
            externalUrl = null,
            bingeGroup = null,
            notWebReady = false,
            proxyRequestHeaders = headers,
            videoSize = null,
            filename = null,
            subtitles = subtitles,
        )
    }

    @Synchronized
    private fun lookupCache(key: String): List<StremioStream>? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.createdAt >= CACHE_TTL_MS) {
            cache.remove(key)
            return null
        }
        cache.remove(key)
        cache[key] = entry
        return entry.results
    }

    @Synchronized
    private fun storeCache(key: String, results: List<StremioStream>) {
        val now = System.currentTimeMillis()
        cache.entries.removeAll { now - it.value.createdAt >= CACHE_TTL_MS }
        cache.remove(key)
        while (cache.size >= MAX_CACHE_ENTRIES) {
            cache.remove(cache.keys.first())
        }
        cache[key] = CacheEntry(now, results)
    }
}
