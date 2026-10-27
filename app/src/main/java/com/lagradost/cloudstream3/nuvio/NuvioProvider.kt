package com.lagradost.cloudstream3.nuvio

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
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
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.stremio.StremioAddonProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * Phase 8.66: a browsable provider whose catalogue is TMDB and whose
 * streams come from the user's Nuvio scrapers.
 *
 * Until now the scrapers only fired inside the Stremio bridge (on
 * bridge titles). This provider closes the loop the user asked for:
 * open a title under "Nuvio" — search or the home rows — pick an
 * episode, and Play gathers streams from every enabled scraper via
 * [NuvioStreamService], exactly like opening the title in the Nuvio
 * app itself. Requires the TMDB key from Settings → Account; without
 * it (or with no scraper enabled) the provider is not registered
 * (see [NuvioProviders]).
 */
class NuvioProvider : MainAPI() {

    override var name: String = "Nuvio"
    override var mainUrl: String = "https://www.themoviedb.org"
    override var lang: String = "en"

    override val hasMainPage: Boolean = true

    override val mainPage: List<MainPageData> = listOf(
        MainPageData(
            name = "Trending Movies",
            data = "trending/movie/week",
            horizontalImages = false,
        ),
        MainPageData(
            name = "Trending Series",
            data = "trending/tv/week",
            horizontalImages = false,
        ),
        MainPageData(
            name = "Popular Movies",
            data = "movie/popular",
            horizontalImages = false,
        ),
        MainPageData(
            name = "Popular Series",
            data = "tv/popular",
            horizontalImages = false,
        ),
    )

    override val supportedTypes: Set<TvType> = setOf(TvType.Movie, TvType.TvSeries)

    private fun appContext(): Context? =
        runCatching { CloudStreamApp.context }.getOrNull()

    /** TMDB GET with the user's key (Bearer for JWT keys, api_key otherwise). */
    private suspend fun tmdbGet(
        context: Context,
        path: String,
        params: Map<String, String> = emptyMap(),
    ): JSONObject? {
        val key = NuvioTmdb.apiKey(context) ?: return null
        val allParams = params.toMutableMap()
        val headers = mutableMapOf<String, String>()
        if (key.startsWith("eyJ")) {
            headers["Authorization"] = "Bearer $key"
        } else {
            allParams["api_key"] = key
        }
        val response = runCatching {
            app.get(
                "https://api.themoviedb.org/3/$path",
                headers = headers,
                params = allParams,
            )
        }.getOrNull() ?: return null
        if (response.code !in 200..299) return null
        return runCatching { JSONObject(response.text) }.getOrNull()
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse? {
        val context = appContext() ?: return null
        val json = tmdbGet(
            context, request.data,
            mapOf("page" to page.toString()),
        ) ?: return null
        val isTv = request.data.contains("/tv") ||
            request.data.startsWith("tv/")
        val items = parseResults(json, forceTv = isTv)
        val totalPages = json.optInt("total_pages", 1)
        return newHomePageResponse(request.name, items, page < totalPages)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val context = appContext() ?: return null
        val (movies, series) = coroutineScope {
            val m = async {
                tmdbGet(context, "search/movie", mapOf("query" to query))
                    ?.let { parseResults(it, forceTv = false) }
                    ?: emptyList()
            }
            val s = async {
                tmdbGet(context, "search/tv", mapOf("query" to query))
                    ?.let { parseResults(it, forceTv = true) }
                    ?: emptyList()
            }
            m.await() to s.await()
        }
        val merged = (movies + series).take(60)
        return merged.ifEmpty { null }
    }

    private fun parseResults(
        json: JSONObject,
        forceTv: Boolean,
    ): List<SearchResponse> {
        val results = json.optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optInt("id").takeIf { it > 0 } ?: continue
            val title = (if (forceTv) {
                item.optString("name")
            } else {
                item.optString("title")
            }).ifBlank {
                if (forceTv) item.optString("title") else item.optString("name")
            }
            if (title.isBlank()) continue
            val date = if (forceTv) {
                item.optString("first_air_date")
            } else {
                item.optString("release_date")
            }
            val year = date.take(4).toIntOrNull()
            val poster = item.optString("poster_path")
                .takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/w500$it" }
            val payload = payloadJson(if (forceTv) "series" else "movie", id)
            out += if (forceTv) {
                newTvSeriesSearchResponse(title, payload, TvType.TvSeries, fix = false) {
                    this.posterUrl = poster
                    this.year = year
                }
            } else {
                newMovieSearchResponse(title, payload, TvType.Movie, fix = false) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        val context = appContext() ?: return null
        val payload = parsePayload(url) ?: return null
        val isTv = payload.type == "series"
        val endpoint = if (isTv) "tv" else "movie"
        val details = tmdbGet(
            context, "$endpoint/${payload.tmdbId}",
            mapOf("append_to_response" to "credits"),
        ) ?: return null
        val title = (if (isTv) {
            details.optString("name")
        } else {
            details.optString("title")
        }).ifBlank { return null }
        val poster = details.optString("poster_path")
            .takeIf { it.isNotBlank() }
            ?.let { "https://image.tmdb.org/t/p/w500$it" }
        val backdrop = details.optString("backdrop_path")
            .takeIf { it.isNotBlank() }
            ?.let { "https://image.tmdb.org/t/p/w1280$it" }
        val plot = details.optString("overview").takeIf { it.isNotBlank() }
        val year = (if (isTv) {
            details.optString("first_air_date")
        } else {
            details.optString("release_date")
        }).take(4).toIntOrNull()
        val score = Score.from(
            details.optDouble("vote_average").takeIf { it > 0.0 }, 10
        )
        val genres = mutableListOf<String>()
        val genreArr = details.optJSONArray("genres")
        if (genreArr != null) {
            for (i in 0 until genreArr.length()) {
                genreArr.optJSONObject(i)?.optString("name")
                    ?.takeIf { it.isNotBlank() }?.let { genres += it }
            }
        }
        val selfPayload = payloadJson(payload.type, payload.tmdbId)

        return if (isTv) {
            val episodes = loadEpisodes(context, payload.tmdbId, details)
            newTvSeriesLoadResponse(title, selfPayload, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = genres.takeIf { it.isNotEmpty() }
                this.score = score
            }
        } else {
            newMovieLoadResponse(title, selfPayload, TvType.Movie, selfPayload) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = genres.takeIf { it.isNotEmpty() }
                this.score = score
                this.duration = details.optInt("runtime").takeIf { it > 0 }
            }
        }
    }

    private suspend fun loadEpisodes(
        context: Context,
        tmdbId: Int,
        details: JSONObject,
    ): List<com.lagradost.cloudstream3.Episode> {
        val seasons = details.optJSONArray("seasons") ?: return emptyList()
        val seasonNumbers = mutableListOf<Int>()
        for (i in 0 until seasons.length()) {
            val season = seasons.optJSONObject(i) ?: continue
            if (season.optInt("episode_count") > 0) {
                seasonNumbers += season.optInt("season_number")
            }
        }
        val perSeason = coroutineScope {
            seasonNumbers.map { seasonNumber ->
                async {
                    val json = tmdbGet(context, "tv/$tmdbId/season/$seasonNumber")
                    val episodes = json?.optJSONArray("episodes")
                        ?: return@async emptyList()
                    val out =
                        mutableListOf<com.lagradost.cloudstream3.Episode>()
                    for (i in 0 until episodes.length()) {
                        val ep = episodes.optJSONObject(i) ?: continue
                        val episodeNumber = ep.optInt("episode_number")
                        if (episodeNumber <= 0) continue
                        out += newEpisode(
                            url = payloadJson(
                                "series", tmdbId, seasonNumber, episodeNumber
                            ),
                            fix = false,
                            initializer = {
                                this.name = ep.optString("name")
                                this.season = seasonNumber
                                this.episode = episodeNumber
                                this.posterUrl = ep.optString("still_path")
                                    .takeIf { it.isNotBlank() }
                                    ?.let { "https://image.tmdb.org/t/p/w500$it" }
                                this.description = ep.optString("overview")
                                addDate(ep.optString("air_date"))
                            },
                        )
                    }
                    out
                }
            }.awaitAll()
        }
        return perSeason.flatten()
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val context = appContext() ?: return false
        val payload = parsePayload(data) ?: return false
        val streams = runCatching {
            NuvioStreamService.resolve(
                context,
                payload.tmdbId.toString(),
                payload.type,
                payload.season,
                payload.episode,
            )
        }.getOrElse { emptyList() }
        val seenUrls = mutableSetOf<String>()
        val seenSubs = mutableSetOf<String>()
        var emitted = 0
        for (stream in streams) {
            for (sub in stream.subtitles) {
                if (seenSubs.add(sub.url)) {
                    subtitleCallback(newSubtitleFile(sub.lang, sub.url))
                }
            }
            if (!stream.isDirect) continue
            val streamUrl = stream.url!!
            if (!seenUrls.add(streamUrl)) continue
            val headers = stream.proxyRequestHeaders
            val label = stream.name
                ?: stream.title?.lineSequence()?.firstOrNull()
                ?: "Nuvio"
            callback(
                newExtractorLink(
                    source = stream.name ?: "Nuvio",
                    name = label,
                    url = streamUrl,
                    type = StremioAddonProvider.inferLinkType(streamUrl),
                ) {
                    this.referer = headers.entries.firstOrNull {
                        it.key.equals("referer", ignoreCase = true)
                    }?.value ?: ""
                    this.headers = headers
                    this.quality = StremioAddonProvider.parseQuality(
                        listOfNotNull(stream.name, stream.title, stream.filename)
                            .joinToString(" ")
                    )
                }
            )
            emitted++
        }
        return emitted > 0
    }

    private data class Payload(
        val type: String,
        val tmdbId: Int,
        val season: Int?,
        val episode: Int?,
    )

    companion object {
        fun payloadJson(
            type: String,
            tmdbId: Int,
            season: Int? = null,
            episode: Int? = null,
        ): String {
            val obj = JSONObject()
            obj.put("src", "nuvio")
            obj.put("t", type)
            obj.put("i", tmdbId)
            if (season != null) obj.put("s", season)
            if (episode != null) obj.put("e", episode)
            return obj.toString()
        }

        fun parsePayload(raw: String): Payload? = runCatching {
            val obj = JSONObject(raw)
            val type = obj.optString("t")
            if (type != "movie" && type != "series") return null
            val id = obj.optInt("i").takeIf { it > 0 } ?: return null
            Payload(
                type = type,
                tmdbId = id,
                season = if (obj.has("s")) obj.optInt("s") else null,
                episode = if (obj.has("e")) obj.optInt("e") else null,
            )
        }.getOrNull()
    }
}

/**
 * Keeps the single [NuvioProvider] registered in APIHolder exactly
 * while it can work: a TMDB key is set AND at least one scraper is
 * enabled. Synced at app start (MainActivity) and whenever the
 * Nuvio Plugins page changes something.
 */
object NuvioProviders {

    @Volatile
    private var registered: NuvioProvider? = null

    @Synchronized
    fun sync(context: Context) {
        val usable = !NuvioTmdb.apiKey(context).isNullOrBlank() &&
            runCatching { NuvioRepository.activeScrapers(context) }
                .getOrElse { emptyList() }
                .isNotEmpty()
        val current = registered
        if (usable && current == null) {
            val provider = NuvioProvider()
            APIHolder.allProviders.add(provider)
            APIHolder.addPluginMapping(provider)
            registered = provider
        } else if (!usable && current != null) {
            APIHolder.allProviders.remove(current)
            APIHolder.removePluginMapping(current)
            registered = null
        }
    }
}
