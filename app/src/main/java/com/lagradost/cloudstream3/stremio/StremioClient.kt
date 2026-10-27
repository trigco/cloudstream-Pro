package com.lagradost.cloudstream3.stremio

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * HTTP layer for the Stremio bridge: TTL cache + in-flight dedupe on top
 * of the app's own HTTP client. Stream calls get a generous timeout
 * (~45s): scraper-bridge addons routinely take 20-30s to answer.
 */
object StremioClient {

    const val MANIFEST_TTL_MS = 6L * 60 * 60 * 1000
    const val CATALOG_TTL_MS = 15L * 60 * 1000
    const val META_TTL_MS = 45L * 60 * 1000
    const val STREAM_TTL_MS = 5L * 60 * 1000
    const val SUBTITLE_TTL_MS = 30L * 60 * 1000

    private const val DEFAULT_TIMEOUT_MS = 45_000L

    private data class CacheEntry(val storedAt: Long, val json: JSONObject)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = ConcurrentHashMap<String, Deferred<JSONObject?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun fetchJson(
        url: String,
        ttlMs: Long,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): JSONObject? {
        val now = System.currentTimeMillis()
        cache[url]?.let { entry ->
            if (now - entry.storedAt < ttlMs) return entry.json
        }
        val deferred: Deferred<JSONObject?> = inFlight[url] ?: run {
            val created = scope.async {
                withTimeoutOrNull(timeoutMs) { doFetch(url) }
            }
            val raced = inFlight.putIfAbsent(url, created)
            if (raced != null) {
                created.cancel()
                raced
            } else {
                created
            }
        }
        val result = try {
            deferred.await()
        } finally {
            inFlight.remove(url, deferred)
        }
        if (result != null) {
            cache[url] = CacheEntry(System.currentTimeMillis(), result)
        }
        return result
    }

    private suspend fun doFetch(url: String): JSONObject? {
        return try {
            val response = app.get(url)
            if (response.code in 200..299) JSONObject(response.text) else null
        } catch (e: Exception) {
            null
        }
    }

    /** Raw text fetch (no JSON parse, no cache) for resolver pages. */
    suspend fun fetchText(
        url: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): String? = withTimeoutOrNull(timeoutMs) {
        runCatching {
            val response = app.get(url)
            if (response.code in 200..299) response.text else null
        }.getOrNull()
    }

    private val MANIFEST_LINK =
        Regex("""https?://[^\s"'<>]+?/manifest\.json""")

    /**
     * Some links users paste are listing pages (e.g. an addons-catalog
     * site) that link to the real manifest. Extract the first manifest
     * URL found on the page; "-dev" variants are deprioritised.
     */
    suspend fun findManifestUrlOnPage(pageUrl: String): String? {
        val text = fetchText(pageUrl) ?: return null
        val candidates = MANIFEST_LINK.findAll(text)
            .map { it.value }
            .distinct()
            .toList()
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { !it.contains("-dev") }
            ?: candidates.first()
    }

    fun invalidate(url: String) {
        cache.remove(url)
    }

    suspend fun fetchManifest(manifestUrl: String): StremioManifest? {
        val json = fetchJson(manifestUrl, MANIFEST_TTL_MS) ?: return null
        return runCatching { StremioParse.parseManifest(json) }.getOrNull()
    }

    suspend fun fetchCatalog(
        manifestUrl: String,
        catalog: StremioCatalog,
        extras: Map<String, String> = emptyMap(),
    ): List<StremioMeta> {
        val url = StremioTransport.resourceUrl(
            manifestUrl, "catalog", catalog.type, catalog.id, extras
        )
        val json = fetchJson(url, CATALOG_TTL_MS) ?: return emptyList()
        return runCatching { StremioParse.parseCatalogMetas(json) }
            .getOrDefault(emptyList())
    }

    suspend fun fetchMeta(
        manifestUrl: String,
        type: String,
        id: String,
    ): StremioMeta? {
        val url = StremioTransport.resourceUrl(manifestUrl, "meta", type, id)
        val json = fetchJson(url, META_TTL_MS) ?: return null
        val metaObj = json.optJSONObject("meta") ?: return null
        return runCatching { StremioParse.parseMeta(metaObj) }.getOrNull()
    }

    suspend fun fetchStreams(
        manifestUrl: String,
        type: String,
        videoId: String,
    ): List<StremioStream> {
        val url = StremioTransport.resourceUrl(manifestUrl, "stream", type, videoId)
        val json = fetchJson(url, STREAM_TTL_MS) ?: return emptyList()
        return runCatching { StremioParse.parseStreams(json) }
            .getOrDefault(emptyList())
    }

    suspend fun fetchSubtitles(
        manifestUrl: String,
        type: String,
        videoId: String,
    ): List<StremioSubtitle> {
        val url = StremioTransport.resourceUrl(manifestUrl, "subtitles", type, videoId)
        val json = fetchJson(url, SUBTITLE_TTL_MS) ?: return emptyList()
        return runCatching { StremioParse.parseSubtitles(json) }
            .getOrDefault(emptyList())
    }
}
