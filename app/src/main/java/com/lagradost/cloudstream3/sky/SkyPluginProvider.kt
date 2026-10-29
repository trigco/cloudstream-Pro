package com.lagradost.cloudstream3.sky

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
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
import com.lagradost.cloudstream3.TrailerData
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.stremio.StremioAddonProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.FamilyMode
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * One MainAPI per installed SkyStream plugin instance — the plugin
 * itself, or one instance per manifest sub-provider (all sharing the
 * same plugin.js, told apart by manifest.providerId).
 *
 * Mapping: getHome() -> main page sections (object order preserved,
 * no paging in the contract), search() -> search, load() -> details
 * + episodes, loadStreams() -> ExtractorLinks + subtitles. Item and
 * episode URLs are opaque payload JSON ({src:"sky", pkg, sub, u}),
 * because plugins store anything in them — one real plugin stores a
 * JSON array of page URLs as an episode's url.
 */
class SkyPluginProvider(
    val stored: StoredSkyPlugin,
    val sub: SkySubProvider?,
) : MainAPI() {

    private val manifest: SkyPluginManifest? = stored.manifest()

    val instanceKey: String
        get() = SkyEngine.instanceKey(stored.packageName, sub?.id)

    override var name: String = when {
        sub != null -> (manifest?.name ?: stored.name) + " • " + sub.name
        else -> manifest?.name ?: stored.name
    }

    override var mainUrl: String = manifest?.baseUrl ?: ""

    override var lang: String = manifest?.languages?.firstOrNull() ?: "en"

    override val hasMainPage: Boolean = true

    override val supportedTypes: Set<TvType>
        get() {
            val cats = manifest?.categories.orEmpty()
            val mapped = cats.mapNotNull { categoryTvType(it) }.toSet()
            return mapped.ifEmpty { setOf(TvType.Movie, TvType.TvSeries) }
        }

    /**
     * Sections are learned from getHome() and persisted by the
     * provider (SkyRepository sky_sections), because mainPage must
     * answer synchronously while getHome is an async engine call.
     * Before the first successful fetch a single "all sections" entry
     * stands in; its response carries every section at once.
     */
    override val mainPage: List<MainPageData>
        get() {
            val ctx = appContext()
            val sections = ctx?.let {
                SkyRepository.getSections(it, instanceKey)
            }.orEmpty()
            if (sections.isEmpty()) {
                return listOf(
                    MainPageData(
                        name = DEFAULT_SECTION_NAME,
                        data = sectionData(null),
                        horizontalImages = false,
                    )
                )
            }
            return sections.map { section ->
                MainPageData(
                    name = section,
                    data = sectionData(section),
                    horizontalImages = false,
                )
            }
        }

    private fun appContext(): Context? =
        runCatching { CloudStreamApp.context }.getOrNull()

    // ---- getHome ----

    private data class CachedHome(
        val at: Long,
        val sections: List<Pair<String, List<SkyItem>>>,
    )

    @Volatile
    private var homeCache: CachedHome? = null

    private suspend fun fetchHome(ctx: Context): List<Pair<String, List<SkyItem>>>? {
        val cached = homeCache
        if (cached != null &&
            System.currentTimeMillis() - cached.at < HOME_CACHE_MS
        ) {
            return cached.sections
        }
        val envelope = SkyEngine.invoke(ctx, stored, sub?.id, "getHome")
            ?: return cached?.sections
        if (!envelope.optBoolean("success")) {
            Log.d(
                "SkyJS",
                "[${stored.packageName}] getHome failed: " +
                    envelope.optString("errorCode") + " " +
                    envelope.optString("message").take(160)
            )
            return cached?.sections
        }
        val data = envelope.optJSONObject("data") ?: return cached?.sections
        val familyMode = FamilyMode.isEnabled()
        val sections = mutableListOf<Pair<String, List<SkyItem>>>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val section = keys.next()
            val arr = data.optJSONArray(section) ?: continue
            val items = mutableListOf<SkyItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val item = SkyParse.parseItem(obj) ?: continue
                if (familyMode && item.isAdult) continue
                items += item
            }
            sections += section to items
        }
        SkyRepository.setSections(
            ctx, instanceKey, sections.map { it.first }
        )
        val result = CachedHome(System.currentTimeMillis(), sections)
        homeCache = result
        return result.sections
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse? {
        val ctx = appContext() ?: return null
        val home = fetchHome(ctx) ?: return null
        val wanted = parseSectionData(request.data)
        if (wanted == null) {
            val lists = home.map { (section, items) ->
                HomePageList(section, items.map { toSearchResponse(it) })
            }
            return newHomePageResponse(lists, false)
        }
        val items = home.firstOrNull { it.first == wanted }?.second
            ?: return null
        return newHomePageResponse(
            request.name, items.map { toSearchResponse(it) }, false
        )
    }

    // ---- search ----

    override suspend fun search(query: String): List<SearchResponse>? {
        val ctx = appContext() ?: return null
        val envelope = SkyEngine.invoke(ctx, stored, sub?.id, "search", listOf(query))
            ?: return null
        if (!envelope.optBoolean("success")) return null
        val arr = envelope.optJSONArray("data") ?: return null
        val familyMode = FamilyMode.isEnabled()
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val item = SkyParse.parseItem(obj) ?: continue
            if (familyMode && item.isAdult) continue
            out += toSearchResponse(item)
        }
        return out.take(60).ifEmpty { null }
    }

    // ---- load ----

    override suspend fun load(url: String): LoadResponse? {
        val ctx = appContext() ?: return null
        val payload = parsePayload(url) ?: return null
        val envelope = SkyEngine.invoke(
            ctx, stored, sub?.id, "load", listOf(payload.url)
        ) ?: return null
        if (!envelope.optBoolean("success")) {
            Log.d(
                "SkyJS",
                "[${stored.packageName}] load failed: " +
                    envelope.optString("errorCode") + " " +
                    envelope.optString("message").take(160)
            )
            return null
        }
        val data = envelope.optJSONObject("data") ?: return null
        val item = SkyParse.parseItem(data) ?: return null
        val selfPayload = payloadJson(item.url)
        val typeText = item.type?.lowercase().orEmpty()
        val singleSynthetic = item.episodes.size == 1 &&
            (item.episodes[0].season ?: -1) == 0 &&
            (item.episodes[0].episode ?: -1) == 0
        val isMovie = when {
            typeText.contains("movie") -> true
            typeText.contains("series") || typeText.contains("anime") ||
                typeText.contains("tv") || typeText.contains("show") -> false
            singleSynthetic -> true
            item.episodes.isEmpty() -> true
            else -> false
        }
        val tvType = itemTvType(item)
        return if (isMovie) {
            // Movie rule: the playable data is the (single synthetic)
            // episode's payload, or the item's own url when the
            // plugin returned no episodes at all.
            val dataUrl = if (item.episodes.isNotEmpty()) {
                payloadJson(item.episodes[0].url)
            } else {
                selfPayload
            }
            newMovieLoadResponse(
                item.title, selfPayload,
                if (tvType == TvType.Anime) TvType.Anime else TvType.Movie,
                dataUrl,
            ) {
                fillDetails(item)
            }
        } else {
            val episodes = item.episodes
                .sortedWith(
                    compareBy(
                        { it.season ?: Int.MAX_VALUE },
                        { it.episode ?: Int.MAX_VALUE },
                    )
                )
                .map { ep ->
                    newEpisode(
                        url = payloadJson(ep.url),
                        fix = false,
                        initializer = {
                            this.name = ep.name
                            this.season = ep.season
                            this.episode = ep.episode
                            this.posterUrl = ep.posterUrl
                            this.description = ep.description
                            addDate(ep.airDate)
                        },
                    )
                }
            newTvSeriesLoadResponse(
                item.title, selfPayload,
                tvType ?: TvType.TvSeries, episodes,
            ) {
                fillDetails(item)
            }
        }
    }

    private fun LoadResponse.fillDetails(item: SkyItem) {
        this.posterUrl = item.posterUrl
        this.backgroundPosterUrl = item.bannerUrl
        this.logoUrl = item.logoUrl
        this.plot = item.description
        this.year = item.year
        this.score = Score.from(item.score, 10)
        this.tags = item.tags.takeIf { it.isNotEmpty() }
        this.actors = item.cast.takeIf { it.isNotEmpty() }?.map { cast ->
            ActorData(
                actor = Actor(cast.name, cast.image),
                roleString = cast.role,
            )
        }
        this.trailers = item.trailerUrls.map { trailer ->
            TrailerData(trailer, null, false)
        }.toMutableList()
        this.recommendations = item.recommendations
            .takeIf { it.isNotEmpty() }
            ?.map { toSearchResponse(it) }
        item.syncData.forEach { (key, value) -> this.syncData[key] = value }
        this.contentRating = item.contentRating
        this.duration = StremioAddonProvider.parseRuntimeMinutes(item.durationText)
    }

    // ---- loadLinks ----

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ctx = appContext() ?: return false
        val payload = parsePayload(data) ?: return false
        val envelope = runCatching {
            SkyEngine.invoke(
                ctx, stored, sub?.id, "loadStreams", listOf(payload.url)
            )
        }.getOrNull() ?: return false
        if (!envelope.optBoolean("success")) {
            Log.d(
                "SkyJS",
                "[${stored.packageName}] loadStreams failed: " +
                    envelope.optString("errorCode") + " " +
                    envelope.optString("message").take(160)
            )
            return false
        }
        val arr = envelope.optJSONArray("data") ?: return false
        var emitted = 0
        val seenUrls = mutableSetOf<String>()
        val seenSubs = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val stream = SkyParse.parseStream(obj) ?: continue
            if (stream.hasDrm) {
                // No DRM representation in our link model (v1): drop,
                // but leave a trace so the gap is diagnosable.
                Log.d(
                    "SkyJS",
                    "[${stored.packageName}] dropping DRM stream: ${stream.url}"
                )
                continue
            }
            for ((label, subUrl) in stream.subtitles) {
                if (seenSubs.add(subUrl)) {
                    subtitleCallback(newSubtitleFile(label, subUrl))
                }
            }
            if (!seenUrls.add(stream.url)) continue
            callback(
                newExtractorLink(
                    source = name,
                    name = stream.name ?: stream.source
                        ?: manifest?.name ?: stored.name,
                    url = stream.url,
                    type = linkType(stream),
                ) {
                    this.referer = stream.headers.entries.firstOrNull {
                        it.key.equals("referer", ignoreCase = true)
                    }?.value ?: ""
                    this.headers = stream.headers
                    this.quality = qualityOf(stream)
                }
            )
            emitted++
        }
        return emitted > 0
    }

    private fun linkType(stream: SkyStreamResult): ExtractorLinkType {
        val typeText = stream.type?.lowercase()
        val clean = stream.url.substringBefore('?').lowercase()
        return when {
            stream.isM3U8 || typeText == "m3u8" -> ExtractorLinkType.M3U8
            typeText == "dash" || typeText == "mpd" || clean.endsWith(".mpd") ->
                ExtractorLinkType.DASH
            else -> StremioAddonProvider.inferLinkType(stream.url)
        }
    }

    private fun qualityOf(stream: SkyStreamResult): Int {
        return when (val raw = stream.qualityRaw) {
            is Number -> {
                val value = raw.toInt()
                if (value in KNOWN_HEIGHTS) value
                else StremioAddonProvider.parseQuality(raw.toString())
            }
            is String -> StremioAddonProvider.parseQuality(raw)
            else -> StremioAddonProvider.parseQuality(
                listOfNotNull(stream.name, stream.source).joinToString(" ")
            )
        }
    }

    // ---- item mapping ----

    private fun categoryTvType(category: String): TvType? =
        when (category.lowercase()) {
            "movie", "movies", "film" -> TvType.Movie
            "tvseries", "tv series", "series", "tv", "show", "shows" ->
                TvType.TvSeries
            "anime" -> TvType.Anime
            "live", "channel", "channels" -> TvType.Live
            else -> null
        }

    private fun itemTvType(item: SkyItem): TvType? {
        val typeText = item.type?.lowercase() ?: return null
        return when {
            typeText.contains("anime") -> TvType.Anime
            typeText.contains("movie") -> TvType.Movie
            typeText.contains("series") || typeText.contains("tv") ||
                typeText.contains("show") -> TvType.TvSeries
            typeText.contains("live") || typeText.contains("channel") ->
                TvType.Live
            else -> null
        }
    }

    private fun toSearchResponse(item: SkyItem): SearchResponse {
        val tvType = itemTvType(item)
            ?: if (supportedTypes.size == 1) supportedTypes.first()
            else TvType.Movie
        val payload = payloadJson(item.url)
        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(item.title, payload, TvType.Movie, fix = false) {
                this.posterUrl = item.posterUrl
                this.year = item.year
            }
        } else {
            newTvSeriesSearchResponse(item.title, payload, tvType, fix = false) {
                this.posterUrl = item.posterUrl
                this.year = item.year
            }
        }
    }

    // ---- payload plumbing ----

    data class Payload(val packageName: String, val subId: String?, val url: String)

    fun payloadJson(itemUrl: String): String {
        val obj = JSONObject()
        obj.put("src", "sky")
        obj.put("pkg", stored.packageName)
        sub?.id?.let { obj.put("sub", it) }
        obj.put("u", itemUrl)
        return obj.toString()
    }

    companion object {
        private const val HOME_CACHE_MS = 5 * 60 * 1000L
        private const val DEFAULT_SECTION_NAME = "Home"

        private val KNOWN_HEIGHTS = setOf(
            Qualities.P144.value, Qualities.P240.value, Qualities.P360.value,
            Qualities.P480.value, Qualities.P720.value, Qualities.P1080.value,
            Qualities.P1440.value, Qualities.P2160.value,
        )

        private fun sectionData(section: String?): String {
            val obj = JSONObject()
            obj.put("src", "sky")
            section?.let { obj.put("sec", it) }
            return obj.toString()
        }

        private fun parseSectionData(raw: String): String? = runCatching {
            JSONObject(raw).optString("sec").takeIf { it.isNotBlank() }
        }.getOrNull()

        fun parsePayload(raw: String): Payload? = runCatching {
            val obj = JSONObject(raw)
            val u = obj.optString("u")
            if (u.isBlank()) return@runCatching null
            Payload(
                packageName = obj.optString("pkg"),
                subId = obj.optString("sub").takeIf { it.isNotBlank() },
                url = u,
            )
        }.getOrNull()
    }
}
