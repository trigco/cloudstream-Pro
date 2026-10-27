package com.lagradost.cloudstream3.stremio

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.nuvio.NuvioStreamService
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * One MainAPI instance per installed Stremio addon, registered
 * dynamically in APIHolder (the same lists plugin providers use).
 *
 * Catalogs surface as Home rows + Search; meta drives the details page;
 * loadLinks fans the stream request out to EVERY enabled addon (that is
 * the whole point of the Stremio model: Cinemeta provides the catalog,
 * a stream addon provides the links). Step 1 plays direct URLs only —
 * torrent (infoHash), YouTube (ytId) and external links are skipped
 * until a later step adds an engine for them.
 */
class StremioAddonProvider(private val stored: StoredAddon) : MainAPI() {

    val manifestUrl: String = stored.manifestUrl

    @Volatile
    private var cachedManifest: StremioManifest? = stored.manifest()

    private suspend fun manifest(): StremioManifest? {
        cachedManifest?.let { return it }
        val fetched = StremioClient.fetchManifest(manifestUrl) ?: return null
        cachedManifest = fetched
        return fetched
    }

    /** Synchronous best-known manifest (persisted at install time). */
    fun knownManifest(): StremioManifest? = cachedManifest

    override var name: String =
        stored.manifest()?.name
            ?: if (stored.builtIn) "Cinemeta" else hostLabel(manifestUrl)

    override var mainUrl: String = StremioTransport.baseOf(manifestUrl)

    override var lang: String = "en"

    override val hasMainPage: Boolean
        get() = mainPage.isNotEmpty()

    override val mainPage: List<MainPageData>
        get() {
            val manifest = cachedManifest
            val catalogs = when {
                manifest != null -> manifest.catalogs
                stored.builtIn -> CINEMETA_DEFAULT_CATALOGS
                else -> emptyList()
            }
            return catalogs.map { catalog ->
                MainPageData(
                    data = catalogKey(catalog.type, catalog.id),
                    name = catalog.name,
                    horizontalImages = false,
                )
            }
        }

    override val supportedTypes: Set<TvType>
        get() {
            val types = cachedManifest?.types?.takeIf { it.isNotEmpty() }
                ?: listOf("movie", "series")
            val mapped = types.mapNotNull { tvTypeOf(StremioTransport.canonicalType(it)) }
                .toSet()
            return mapped.ifEmpty { setOf(TvType.Movie, TvType.TvSeries) }
        }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse? {
        val manifest = manifest() ?: return null
        val (type, catalogId) = parseCatalogKey(request.data) ?: return null
        val catalog = manifest.catalogs.firstOrNull {
            it.id == catalogId && StremioTransport.canonicalType(it.type) ==
                StremioTransport.canonicalType(type)
        } ?: manifest.catalogs.firstOrNull { it.id == catalogId } ?: return null

        val extras = mutableMapOf<String, String>()
        if (page > 1 && catalog.supportsExtra("skip")) {
            val pageSize = lastPageSizes[catalogKey(catalog.type, catalog.id)] ?: 20
            extras["skip"] = ((page - 1) * pageSize).toString()
        }
        val metas = StremioClient.fetchCatalog(manifestUrl, catalog, extras)
        if (metas.isNotEmpty()) {
            lastPageSizes[catalogKey(catalog.type, catalog.id)] = metas.size
        }
        val items = metas.map { it.toSearchResponse() }
        val hasNext = catalog.supportsExtra("skip") && items.isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val manifest = manifest() ?: return null
        val searchable = manifest.catalogs.filter { it.supportsExtra("search") }
        if (searchable.isEmpty()) return null
        val results = coroutineScope {
            searchable.map { catalog ->
                async {
                    StremioClient.fetchCatalog(
                        manifestUrl, catalog, mapOf("search" to query)
                    )
                }
            }.awaitAll()
        }
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<SearchResponse>()
        for (metas in results) {
            for (meta in metas) {
                val key = StremioTransport.canonicalType(meta.type) + ":" + meta.id
                if (seen.add(key)) merged += meta.toSearchResponse()
            }
        }
        return merged.take(60)
    }

    override suspend fun load(url: String): LoadResponse? {
        val payload = parsePayload(url) ?: return null
        var meta = fetchMetaWithFallback(payload)
        if (meta == null && payload.id.startsWith("tt") &&
            manifestUrl != AddonRepository.CINEMETA_URL
        ) {
            // Catalog-only addons never implement meta; Cinemeta does.
            meta = StremioClient.fetchMeta(
                AddonRepository.CINEMETA_URL, payload.type, payload.id
            ) ?: StremioClient.fetchMeta(
                AddonRepository.CINEMETA_URL, otherType(payload.type), payload.id
            )
        }
        val resolved = meta ?: return null
        val type = StremioTransport.canonicalType(resolved.type)
        val selfPayload = payloadJson(manifestUrl, type, resolved.id)

        return if (type == "series" || type == "anime") {
            val episodes = resolved.videos
                .sortedWith(
                    compareBy(
                        { it.season ?: Int.MAX_VALUE },
                        { it.episode ?: Int.MAX_VALUE },
                    )
                )
                .map { video ->
                    newEpisode(
                        url = payloadJson(manifestUrl, type, video.id),
                        fix = false,
                        initializer = {
                            this.name = video.title
                            this.season = video.season
                            this.episode = video.episode
                            this.posterUrl = video.thumbnail
                            this.description = video.overview
                            addDate(video.released)
                        },
                    )
                }
            newTvSeriesLoadResponse(
                resolved.name, selfPayload, tvTypeOf(type) ?: TvType.TvSeries, episodes
            ) {
                fillDetails(resolved)
            }
        } else {
            newMovieLoadResponse(
                resolved.name, selfPayload,
                tvTypeOf(type) ?: TvType.Movie, selfPayload,
            ) {
                fillDetails(resolved)
                this.duration = parseRuntimeMinutes(resolved.runtime)
            }
        }
    }

    private suspend fun fetchMetaWithFallback(payload: Payload): StremioMeta? {
        val manifest = manifest()
        if (manifest != null &&
            !manifest.supportsResource("meta", payload.type, payload.id)
        ) {
            // This addon does not serve meta for it; the caller tries
            // Cinemeta next.
            if (manifestUrl != AddonRepository.CINEMETA_URL) return null
        }
        return StremioClient.fetchMeta(manifestUrl, payload.type, payload.id)
            ?: StremioClient.fetchMeta(manifestUrl, otherType(payload.type), payload.id)
    }

    private fun LoadResponse.fillDetails(meta: StremioMeta) {
        this.posterUrl = meta.poster
        this.backgroundPosterUrl = meta.background
        this.plot = meta.description
        this.year = meta.releaseInfo
            ?.substringBefore("-")
            ?.filter { it.isDigit() }
            ?.take(4)
            ?.toIntOrNull()
        this.tags = meta.genres.takeIf { it.isNotEmpty() }
        this.score = Score.from(meta.imdbRating, 10)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = parsePayload(data) ?: return false
        val type = StremioTransport.canonicalType(payload.type)
        val candidates = StremioProviders.enabledAddons()

        // Fan out to every enabled addon that can serve streams for this
        // id, in install order; subtitles come along for the ride.
        val perAddon = coroutineScope {
            candidates.map { addon ->
                async {
                    val manifest = addon.manifest()
                        ?: StremioClient.fetchManifest(addon.manifestUrl)
                    if (manifest == null) {
                        addon to emptyList<StremioStream>()
                    } else {
                        val streams = if (
                            manifest.supportsResource("stream", type, payload.id)
                        ) {
                            StremioClient.fetchStreams(addon.manifestUrl, type, payload.id)
                        } else {
                            emptyList()
                        }
                        addon to streams
                    }
                }
            }.awaitAll()
        }

        var emitted = 0
        val seenUrls = mutableSetOf<String>()
        val seenSubs = mutableSetOf<String>()

        suspend fun emitStream(sourceName: String, stream: StremioStream) {
            for (sub in stream.subtitles) {
                if (seenSubs.add(sub.url)) {
                    subtitleCallback(newSubtitleFile(sub.lang, sub.url))
                }
            }
            if (!stream.isDirect) return // torrent/YouTube/external: not playable yet
            val streamUrl = stream.url!!
            if (!seenUrls.add(streamUrl)) return
            val headers = stream.proxyRequestHeaders
            val label = stream.name
                ?: stream.title?.lineSequence()?.firstOrNull()
                ?: sourceName
            callback(
                newExtractorLink(
                    source = sourceName,
                    name = label,
                    url = streamUrl,
                    type = inferLinkType(streamUrl),
                ) {
                    this.referer = headers.entries.firstOrNull {
                        it.key.equals("referer", ignoreCase = true)
                    }?.value ?: ""
                    this.headers = headers
                    this.quality = parseQuality(
                        listOfNotNull(stream.name, stream.title, stream.filename)
                            .joinToString(" ")
                    )
                }
            )
            emitted++
        }

        for ((addon, streams) in perAddon) {
            val addonName = addon.manifest()?.name ?: name
            for (stream in streams) {
                emitStream(addonName, stream)
            }
        }

        // Nuvio plugins (bridge step 2): JS scrapers keyed by TMDB id,
        // resolved from this payload's IMDb id. Their streams merge
        // into the same emission path as addon streams.
        val appContext = runCatching { CloudStreamApp.context }.getOrNull()
        if (appContext != null) {
            val idParts = payload.id.split(':')
            val baseId = idParts.firstOrNull() ?: payload.id
            val seasonNum = if (type == "series" && idParts.size >= 3) {
                idParts[1].toIntOrNull()
            } else {
                null
            }
            val episodeNum = if (type == "series" && idParts.size >= 3) {
                idParts[2].toIntOrNull()
            } else {
                null
            }
            val nuvioStreams = runCatching {
                NuvioStreamService.resolve(
                    appContext, baseId, type, seasonNum, episodeNum
                )
            }.getOrElse { emptyList() }
            for (stream in nuvioStreams) {
                emitStream(stream.name ?: "Nuvio", stream)
            }
        }

        // Dedicated subtitles resource fan-out.
        coroutineScope {
            candidates.map { addon ->
                async {
                    val manifest = addon.manifest()
                        ?: StremioClient.fetchManifest(addon.manifestUrl)
                    if (manifest != null &&
                        manifest.supportsResource("subtitles", type, payload.id)
                    ) {
                        StremioClient.fetchSubtitles(addon.manifestUrl, type, payload.id)
                            .forEach { sub ->
                                if (seenSubs.add(sub.url)) {
                                    subtitleCallback(newSubtitleFile(sub.lang, sub.url))
                                }
                            }
                    }
                }
            }.awaitAll()
        }
        return emitted > 0
    }

    private fun StremioMeta.toSearchResponse(): SearchResponse {
        val type = StremioTransport.canonicalType(this.type)
        val payload = payloadJson(manifestUrl, type, this.id)
        val year = this.releaseInfo
            ?.substringBefore("-")
            ?.filter { it.isDigit() }
            ?.take(4)
            ?.toIntOrNull()
        return if (type == "series" || type == "anime") {
            newTvSeriesSearchResponse(
                this.name, payload, tvTypeOf(type) ?: TvType.TvSeries, fix = false
            ) {
                this.posterUrl = this@toSearchResponse.poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(
                this.name, payload, tvTypeOf(type) ?: TvType.Movie, fix = false
            ) {
                this.posterUrl = this@toSearchResponse.poster
                this.year = year
            }
        }
    }

    companion object {
        private val lastPageSizes = ConcurrentHashMap<String, Int>()

        private val CINEMETA_DEFAULT_CATALOGS = listOf(
            StremioCatalog("movie", "top", "Movies", emptyList(), emptyList()),
            StremioCatalog("series", "top", "Series", emptyList(), emptyList()),
        )

        fun hostLabel(manifestUrl: String): String =
            manifestUrl.removePrefix("https://").removePrefix("http://")
                .substringBefore('/').ifBlank { "Stremio Addon" }

        fun tvTypeOf(canonicalType: String): TvType? = when (canonicalType) {
            "movie" -> TvType.Movie
            "series" -> TvType.TvSeries
            "anime" -> TvType.Anime
            "channel" -> TvType.Live
            else -> null
        }

        fun otherType(type: String): String =
            if (StremioTransport.canonicalType(type) == "movie") "series" else "movie"

        fun parseRuntimeMinutes(runtime: String?): Int? {
            if (runtime.isNullOrBlank()) return null
            val lower = runtime.lowercase()
            val hours = Regex("(\\d+)\\s*h").find(lower)?.groupValues?.get(1)?.toIntOrNull()
            val minutes = Regex("(\\d+)\\s*m").find(lower)?.groupValues?.get(1)?.toIntOrNull()
            return when {
                hours != null || minutes != null -> (hours ?: 0) * 60 + (minutes ?: 0)
                else -> lower.filter { it.isDigit() }.toIntOrNull()
            }
        }

        fun parseQuality(text: String): Int {
            val t = text.lowercase()
            return when {
                t.contains("2160") || t.contains("4k") -> Qualities.P2160.value
                t.contains("1440") -> Qualities.P1440.value
                t.contains("1080") -> Qualities.P1080.value
                t.contains("720") -> Qualities.P720.value
                t.contains("480") -> Qualities.P480.value
                t.contains("360") -> Qualities.P360.value
                t.contains("240") -> Qualities.P240.value
                else -> Qualities.Unknown.value
            }
        }

        fun inferLinkType(url: String): ExtractorLinkType {
            val clean = url.substringBefore('?').lowercase()
            return when {
                clean.endsWith(".m3u8") || clean.contains(".m3u8") ->
                    ExtractorLinkType.M3U8
                clean.endsWith(".mpd") -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
        }

        // ---- payload plumbing: the "url" of a bridge item is a small
        // JSON object {a: addon manifest url, t: type, i: id}. fixUrl()
        // leaves {"..."} strings untouched, and load()/loadLinks() parse
        // it back. ----

        data class Payload(val addonUrl: String, val type: String, val id: String)

        fun payloadJson(addonUrl: String, type: String, id: String): String {
            val obj = JSONObject()
            obj.put("a", addonUrl)
            obj.put("t", type)
            obj.put("i", id)
            return obj.toString()
        }

        fun parsePayload(raw: String): Payload? = runCatching {
            val obj = JSONObject(raw)
            val a = obj.optString("a")
            val i = obj.optString("i")
            if (a.isBlank() || i.isBlank()) return@runCatching null
            Payload(a, obj.optString("t", "movie"), i)
        }.getOrNull()

        fun catalogKey(type: String, catalogId: String): String {
            val obj = JSONObject()
            obj.put("t", type)
            obj.put("c", catalogId)
            return obj.toString()
        }

        fun parseCatalogKey(raw: String): Pair<String, String>? = runCatching {
            val obj = JSONObject(raw)
            val t = obj.optString("t")
            val c = obj.optString("c")
            if (t.isBlank() || c.isBlank()) null else t to c
        }.getOrNull()
    }
}

/**
 * Registry that keeps APIHolder in step with the stored addon list.
 * Mutations (add/remove/toggle in Settings) and app start both funnel
 * through [sync]. Registration uses the exact same calls plugin
 * providers use (allProviders + addPluginMapping), so name lookup,
 * the provider picker, Home and Search all treat bridge providers
 * like any other provider.
 */
object StremioProviders {

    private val registered = ConcurrentHashMap<String, StremioAddonProvider>()

    @Volatile
    private var enabledSnapshot: List<StoredAddon> = emptyList()

    fun enabledAddons(): List<StoredAddon> = enabledSnapshot

    @Synchronized
    fun sync(context: android.content.Context) {
        val addons = AddonRepository.getAddons(context)
        val familyMode = com.lagradost.cloudstream3.utils.FamilyMode.isEnabled()
        // Family Mode: addons whose manifest declares itself adult are
        // kept out entirely, same as adult providers.
        enabledSnapshot = addons.filter {
            it.enabled && !(familyMode && it.manifest()?.adult == true)
        }
        val wanted = enabledSnapshot.map { it.manifestUrl }.toSet()

        // Unregister providers that were removed or disabled.
        for ((url, provider) in registered.entries.toList()) {
            if (url !in wanted) {
                APIHolder.allProviders.remove(provider)
                APIHolder.removePluginMapping(provider)
                registered.remove(url)
            }
        }
        // Register new ones.
        for (addon in enabledSnapshot) {
            if (registered.containsKey(addon.manifestUrl)) continue
            val provider = StremioAddonProvider(addon)
            // Name lookup is by provider name; keep ours unique.
            val clash = APIHolder.getApiFromNameNull(provider.name)
            if (clash != null && clash !== provider) {
                provider.name = provider.name + " (Stremio)"
            }
            APIHolder.allProviders.add(provider)
            APIHolder.addPluginMapping(provider)
            registered[addon.manifestUrl] = provider
        }
    }
}
