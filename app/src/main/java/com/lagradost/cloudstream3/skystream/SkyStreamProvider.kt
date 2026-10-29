package com.lagradost.cloudstream3.skystream

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.stremio.StremioAddonProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * One SkyStream MainAPI per installed provider/export namespace.
 * The extension's own JS engine is used for getHome/search/load/loadStreams;
 * it is not treated as a Stremio addon or as a Nuvio scraper.
 */
class SkyStreamProvider(
    private val rootRepoUrl: String,
    private val storedPlugin: StoredSkyStreamPlugin,
    private val skyProviderId: String?,
    displayName: String,
) : MainAPI() {
    private val pluginName = displayName.ifBlank { storedPlugin.name }
    override var name: String = "SkyStream · " + pluginName
    override var mainUrl: String = storedPlugin.sourceUrl.substringBeforeLast("/")
    override var lang: String = "en"
    override val hasMainPage: Boolean = storedPlugin.manifest().optJSONArray("providers")?.length() != 0 &&
        (SkyStreamRepository.script(CloudStreamApp.context!!, rootRepoUrl, storedPlugin.packageName)
            ?.contains("getHome") == true) || (SkyStreamRepository.script(CloudStreamApp.context!!, rootRepoUrl, storedPlugin.packageName)
            ?.contains("getHome") == true)
    override val mainPage: List<MainPageData>
        get() = if (hasMainPage) listOf(MainPageData(name = "Home", data = "home", horizontalImages = false)) else emptyList()
    override val supportedTypes: Set<TvType> = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val context = appContext() ?: return null
        val result = runCatching {
            SkyStreamEngine.invoke(context, rootRepoUrl, storedPlugin, skyProviderId, "getHome", emptyList())
        }.getOrElse { error ->
            SkyStreamRepository.setError(context, storedPlugin.packageName, error.message ?: "getHome failed")
            return null
        }
        val home = result as? JSONObject ?: return null
        val items = mutableListOf<SearchResponse>()
        val keys = home.keys()
        while (keys.hasNext() && items.size < MAX_HOME_ITEMS) {
            val section = home.optJSONArray(keys.next()) ?: continue
            for (i in 0 until minOf(section.length(), MAX_HOME_ITEMS - items.size)) {
                val item = section.optJSONObject(i) ?: continue
                makeSearchResponse(item)?.let { items += it }
            }
        }
        return newHomePageResponse(request.name, items, false)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val context = appContext() ?: return null
        val result = runCatching {
            SkyStreamEngine.invoke(context, rootRepoUrl, storedPlugin, skyProviderId, "search", listOf(query))
        }.getOrElse { error ->
            SkyStreamRepository.setError(context, storedPlugin.packageName, error.message ?: "search failed")
            return null
        }
        val array = result as? JSONArray ?: return null
        return (0 until minOf(array.length(), MAX_SEARCH_ITEMS)).mapNotNull { i ->
            makeSearchResponse(array.optJSONObject(i) ?: return@mapNotNull null)
        }
    }

    private fun makeSearchResponse(item: JSONObject): SearchResponse? {
        val title = mediaTitle(item).takeIf { it.isNotBlank() } ?: return null
        val url = item.optString("url", item.optString("id")).takeIf { it.isNotBlank() } ?: return null
        val type = mediaType(item)
        val poster = mediaPoster(item)
        val year = item.optString("year").take(4).toIntOrNull()
            ?: item.optString("releaseDate").take(4).toIntOrNull()
        val payload = payloadJson(rootRepoUrl, storedPlugin.packageName, skyProviderId, item, url)
        return if (type == "series" || type == "anime") {
            newTvSeriesSearchResponse(title, payload, if (type == "anime") TvType.Anime else TvType.TvSeries, fix = false) {
                posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, payload, TvType.Movie, fix = false) {
                posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val context = appContext() ?: return null
        val payload = parsePayload(url) ?: return null
        val detail = runCatching {
            SkyStreamEngine.invoke(
                context, payload.rootRepoUrl, payload.plugin, payload.providerId, "load", listOf(payload.itemUrl)
            )
        }.getOrElse { error ->
            SkyStreamRepository.setError(context, payload.plugin.packageName, error.message ?: "load failed")
            return null
        } as? JSONObject ?: payload.item
        if (detail.has("__sky_error")) {
            SkyStreamRepository.setError(context, payload.plugin.packageName, detail.optString("__sky_error"))
            return null
        }
        val title = mediaTitle(detail).ifBlank { mediaTitle(payload.item) }.ifBlank { return null }
        val type = mediaType(detail).ifBlank { mediaType(payload.item) }
        val poster = mediaPoster(detail) ?: mediaPoster(payload.item)
        val background = detail.optString("backgroundPosterUrl", detail.optString("backdrop"))
            .takeIf { it.startsWith("http") }
        val plot = detail.optString("description", detail.optString("plot", detail.optString("overview")))
            .takeIf { it.isNotBlank() }
        val year = detail.optString("year").take(4).toIntOrNull()
            ?: detail.optString("releaseDate").take(4).toIntOrNull()
        val payloadJson = payloadJson(
            payload.rootRepoUrl, payload.plugin.packageName, payload.providerId, detail, payload.itemUrl
        )
        return if (type == "series" || type == "anime") {
            val episodes = parseEpisodes(
                payload.rootRepoUrl, payload.plugin, payload.providerId, detail
            )
            newTvSeriesLoadResponse(title, payloadJson, if (type == "anime") TvType.Anime else TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.tags = stringList(detail.optJSONArray("genres")).ifEmpty { stringList(detail.optJSONArray("tags")) }.takeIf { it.isNotEmpty() }
                this.score = Score.from(detail.optDouble("rating").takeIf { it > 0 }, 10)
            }
        } else {
            newMovieLoadResponse(title, payloadJson, TvType.Movie, payloadJson) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.tags = stringList(detail.optJSONArray("genres")).ifEmpty { stringList(detail.optJSONArray("tags")) }.takeIf { it.isNotEmpty() }
                this.score = Score.from(detail.optDouble("rating").takeIf { it > 0 }, 10)
                this.duration = detail.optInt("duration").takeIf { it > 0 }
            }
        }
    }

    private fun parseEpisodes(
        root: String,
        plugin: StoredSkyStreamPlugin,
        providerId: String?,
        detail: JSONObject,
    ): List<Episode> {
        val direct = detail.optJSONArray("episodes")
        val output = mutableListOf<Episode>()
        if (direct != null) {
            for (i in 0 until direct.length()) {
                val episode = direct.optJSONObject(i) ?: continue
                val epUrl = episode.optString("url", episode.optString("id"))
                if (epUrl.isBlank()) continue
                val epTitle = mediaTitle(episode).ifBlank { "Episode " + (i + 1) }
                val season = episode.optInt("season", 1).takeIf { it >= 0 }
                val episodeNo = episode.optInt("episode", i + 1).takeIf { it > 0 }
                val epPayload = payloadJson(root, plugin.packageName, providerId, episode, epUrl)
                output += newEpisode(epPayload, fix = false) {
                    name = epTitle
                    this.season = season
                    episode = episodeNo
                    posterUrl = mediaPoster(episode)
                    description = episode.optString("description").takeIf { it.isNotBlank() }
                    addDate(episode.optString("releaseDate"))
                }
            }
        }
        val seasons = detail.optJSONArray("seasons")
        if (output.isEmpty() && seasons != null) {
            for (si in 0 until seasons.length()) {
                val season = seasons.optJSONObject(si) ?: continue
                val eps = season.optJSONArray("episodes") ?: continue
                for (ei in 0 until eps.length()) {
                    val episode = eps.optJSONObject(ei) ?: continue
                    val epUrl = episode.optString("url", episode.optString("id"))
                    if (epUrl.isBlank()) continue
                    val number = episode.optInt("episode", ei + 1)
                    val epPayload = payloadJson(root, plugin.packageName, providerId, episode, epUrl)
                    output += newEpisode(epPayload, fix = false) {
                        name = mediaTitle(episode).ifBlank { "Episode " + number }
                        this.season = season.optInt("season", season.optInt("number", si + 1))
                        episode = number
                        posterUrl = mediaPoster(episode)
                        description = episode.optString("description").takeIf { it.isNotBlank() }
                    }
                }
            }
        }
        return output.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val context = appContext() ?: return false
        val payload = parsePayload(data) ?: return false
        val result = runCatching {
            SkyStreamEngine.invoke(
                context, payload.rootRepoUrl, payload.plugin, payload.providerId, "loadStreams", listOf(payload.itemUrl)
            )
        }.getOrElse { error ->
            SkyStreamRepository.setError(context, payload.plugin.packageName, error.message ?: "loadStreams failed")
            return false
        }
        val streams = when (result) {
            is JSONArray -> result
            is JSONObject -> result.optJSONArray("streams") ?: result.optJSONArray("sources") ?: JSONArray()
            else -> JSONArray()
        }
        val seen = mutableSetOf<String>()
        var emitted = 0
        for (i in 0 until minOf(streams.length(), MAX_STREAMS)) {
            val stream = streams.optJSONObject(i) ?: continue
            val streamUrl = stream.optString("url").ifBlank { stream.optString("link") }
            if (!streamUrl.startsWith("http") || !seen.add(streamUrl)) continue
            val subtitles = stream.optJSONArray("subtitles")
            if (subtitles != null) {
                for (j in 0 until subtitles.length()) {
                    val sub = subtitles.optJSONObject(j) ?: continue
                    val subUrl = sub.optString("url")
                    if (subUrl.startsWith("http")) {
                        subtitleCallback(newSubtitleFile(sub.optString("lang", "Unknown"), subUrl))
                    }
                }
            }
            val label = stream.optString("source", stream.optString("quality", stream.optString("title", pluginName)))
            val qualityText = stream.optString("quality") + " " + stream.optString("title")
            val headers = jsonObjectToMap(stream.optJSONObject("headers"))
            val linkType = StremioAddonProvider.inferLinkType(streamUrl)
            callback(
                newExtractorLink(
                    source = pluginName,
                    name = label.ifBlank { pluginName },
                    url = streamUrl,
                    type = linkType,
                ) {
                    this.referer = headers.entries.firstOrNull { it.key.equals("referer", true) }?.value ?: ""
                    this.headers = headers
                    this.quality = StremioAddonProvider.parseQuality(qualityText)
                }
            )
            emitted++
        }
        if (emitted > 0) SkyStreamRepository.setError(context, payload.plugin.packageName, null)
        return emitted > 0
    }

    private fun appContext(): Context? = runCatching { CloudStreamApp.context }.getOrNull()

    private data class Payload(
        val rootRepoUrl: String,
        val plugin: StoredSkyStreamPlugin,
        val providerId: String?,
        val item: JSONObject,
        val itemUrl: String,
    )

    companion object {
        private const val MAX_HOME_ITEMS = 150
        private const val MAX_SEARCH_ITEMS = 80
        private const val MAX_STREAMS = 150

        private fun mediaTitle(item: JSONObject): String =
            item.optString("title").ifBlank {
                item.optString("name").ifBlank { item.optString("label") }
            }

        private fun mediaType(item: JSONObject): String {
            val raw = item.optString("type", item.optString("mediaType", "movie")).lowercase()
            return when (raw) {
                "tv", "show", "series", "episode" -> "series"
                "anime" -> "anime"
                else -> "movie"
            }
        }

        private fun mediaPoster(item: JSONObject): String? =
            item.optString("posterUrl").ifBlank {
                item.optString("poster").ifBlank { item.optString("image") }
            }.takeIf { it.startsWith("http") }

        private fun stringList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }
        }

        private fun jsonObjectToMap(obj: JSONObject?): Map<String, String> {
            if (obj == null) return emptyMap()
            val result = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = obj.optString(key)
            }
            return result
        }

        private fun payloadJson(
            root: String,
            packageName: String,
            providerId: String?,
            item: JSONObject,
            url: String,
        ): String = JSONObject()
            .put("src", "skystream")
            .put("repo", root)
            .put("plugin", packageName)
            .put("provider", providerId ?: JSONObject.NULL)
            .put("item", item)
            .put("url", url)
            .toString()

        private fun parsePayload(raw: String): Payload? = runCatching {
            val obj = JSONObject(raw)
            if (obj.optString("src") != "skystream") return null
            val root = obj.optString("repo").takeIf { it.isNotBlank() } ?: return null
            val packageName = obj.optString("plugin").takeIf { it.isNotBlank() } ?: return null
            val repo = SkyStreamRepository.getRepos(CloudStreamApp.context!!).firstOrNull { it.url == root } ?: return null
            val plugin = repo.plugins.firstOrNull { it.packageName == packageName } ?: return null
            Payload(
                rootRepoUrl = root,
                plugin = plugin,
                providerId = obj.optString("provider").takeIf { it.isNotBlank() && it != "null" },
                item = obj.optJSONObject("item") ?: JSONObject(),
                itemUrl = obj.optString("url"),
            )
        }.getOrNull()
    }
}

/** Keeps SkyStream providers registered for enabled extensions only. */
object SkyStreamProviders {
    private val registered = ConcurrentHashMap<String, SkyStreamProvider>()

    @Synchronized
    fun sync(context: Context) {
        val wanted = linkedMapOf<String, Triple<StoredSkyStreamRepo, StoredSkyStreamPlugin, Pair<String?, String>>>()
        for (repo in SkyStreamRepository.getRepos(context)) {
            for (plugin in repo.plugins.filter { it.enabled }) {
                val manifest = plugin.manifest()
                val subProviders = manifest.optJSONArray("providers")
                if (subProviders != null && subProviders.length() > 0) {
                    for (i in 0 until subProviders.length()) {
                        val p = subProviders.optJSONObject(i) ?: continue
                        val id = p.optString("id").takeIf { it.isNotBlank() } ?: continue
                        val name = p.optString("name", id).ifBlank { id }
                        wanted[plugin.packageName + "|" + id] = Triple(repo, plugin, id to name)
                    }
                } else {
                    wanted[plugin.packageName] = Triple(repo, plugin, null to plugin.name)
                }
            }
        }
        val ids = wanted.keys
        registered.keys.toList().filterNot { it in ids }.forEach { id ->
            registered.remove(id)?.let { provider ->
                APIHolder.allProviders.remove(provider)
                APIHolder.removePluginMapping(provider)
            }
        }
        wanted.forEach { (key, entry) ->
            if (registered.containsKey(key)) return@forEach
            runCatching {
                val (repo, plugin, providerInfo) = entry
                val provider = SkyStreamProvider(repo.url, plugin, providerInfo.first, providerInfo.second)
                if (APIHolder.getApiFromNameNull(provider.name) != null) provider.name += " (SkyStream)"
                APIHolder.allProviders.add(provider)
                APIHolder.addPluginMapping(provider)
                registered[key] = provider
            }
        }
    }
}
