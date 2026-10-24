package com.lagradost.cloudstream3.ui.feed

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.player.FixedNextRenderersFactory
import com.lagradost.cloudstream3.ui.player.LOADTYPE_INAPP
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.buildResultEpisode
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.ui.result.getRealPosition
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Phase 8.20 — the Feed's shared playback engine. The ExoPlayer lives
 * here, not in a fragment, so playback survives the trip between the
 * watch page and the Feed's mini player: surfaces come and go, the
 * player (and its position) stays.
 *
 * It mirrors the app's real player where it matters: the same
 * FixedNextRenderersFactory (nextlib extension decoders), the same
 * episode-id scheme for resume positions, and — when a link fails —
 * it walks down the link list before giving up, like the main player
 * does with its sources.
 */
@UnstableApi
object FeedPlayer {
    enum class Status { IDLE, LOADING, READY, ERROR }

    var status: Status = Status.IDLE
        private set
    var exo: ExoPlayer? = null
        private set

    var currentUrl: String? = null
        private set
    var currentApiName: String? = null
        private set
    var currentName: String? = null
        private set
    var currentPoster: String? = null
        private set

    var generator: RepoLinkGenerator? = null
        private set
    var episodes: List<ResultEpisode> = emptyList()
        private set
    var episodeIndex: Int = 0
        private set
    var links: List<ExtractorLink> = emptyList()
        private set
    var subs: List<SubtitleData> = emptyList()
        private set
    var linkIndex: Int = 0
        private set

    /** True while the video is parked in the Feed's mini player. */
    var minimized: Boolean = false

    val currentEpisode: ResultEpisode? get() = episodes.getOrNull(episodeIndex)
    val hasNextEpisode: Boolean get() = episodeIndex < episodes.lastIndex
    val isPlaying: Boolean get() = exo?.isPlaying == true
    val hasActiveItem: Boolean get() = currentUrl != null && status != Status.IDLE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    private var resumeSeekDone = false
    private var appContext: Context? = null
    private val listeners = mutableSetOf<() -> Unit>()

    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = object : Runnable {
        override fun run() {
            savePosition()
            if (isPlaying) handler.postDelayed(this, 5_000L)
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    /** Creates the player if needed so a surface can bind to it early. */
    fun warmUp(context: Context) {
        ensurePlayer(context)
    }

    private fun ensurePlayer(context: Context): ExoPlayer {
        exo?.let { return it }
        val appContext = context.applicationContext
        this.appContext = appContext
        val renderersFactory = FixedNextRenderersFactory(appContext).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }
        val player = ExoPlayer.Builder(appContext)
            .setRenderersFactory(renderersFactory)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    if (!resumeSeekDone) {
                        resumeSeekDone = true
                        val resume = currentEpisode?.getRealPosition() ?: 0L
                        if (resume > 0 && player.currentPosition < 1_000) {
                            player.seekTo(resume)
                        }
                    }
                    if (status == Status.LOADING) {
                        status = Status.READY
                    }
                    notifyListeners()
                } else if (state == Player.STATE_ENDED) {
                    savePosition()
                    if (hasNextEpisode) {
                        playEpisode(episodeIndex + 1)
                    }
                    notifyListeners()
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                handler.removeCallbacks(saveRunnable)
                if (playing) handler.post(saveRunnable)
                notifyListeners()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Walk down the link list before declaring failure — the
                // main player survives bad sources the same way.
                if (linkIndex < links.lastIndex) {
                    linkIndex++
                    playCurrentLink(keepPosition = true)
                } else {
                    status = Status.ERROR
                }
                notifyListeners()
            }
        })
        exo = player
        return player
    }

    /**
     * Loads and plays an item. Calling again with the URL that is
     * already loaded is a no-op (surface rebind) unless [force] is set.
     */
    fun play(
        context: Context,
        url: String,
        apiName: String,
        name: String,
        posterUrl: String?,
        force: Boolean = false
    ) {
        if (!force && url == currentUrl && status != Status.IDLE && status != Status.ERROR) {
            minimized = false
            notifyListeners()
            return
        }
        val player = ensurePlayer(context)
        savePosition()
        loadJob?.cancel()
        minimized = false
        currentUrl = url
        currentApiName = apiName
        currentName = name
        currentPoster = posterUrl
        status = Status.LOADING
        resumeSeekDone = false
        links = emptyList()
        subs = emptyList()
        linkIndex = 0
        notifyListeners()
        player.stop()
        player.clearMediaItems()

        loadJob = scope.launch {
            val api = getApiFromNameNull(apiName)
            if (api == null) {
                status = Status.ERROR
                notifyListeners()
                return@launch
            }
            val response = when (val res = APIRepository(api).load(url)) {
                is Resource.Success -> res.value
                else -> {
                    status = Status.ERROR
                    notifyListeners()
                    return@launch
                }
            }
            episodes = buildResultEpisodes(response)
            if (episodes.isEmpty()) {
                status = Status.ERROR
                notifyListeners()
                return@launch
            }
            generator = RepoLinkGenerator(episodes, page = response)
            extractAndPlay(0)
        }
    }

    /** Replays the current item from scratch (used after a handoff). */
    fun replayCurrent(context: Context) {
        val url = currentUrl ?: return
        val apiName = currentApiName ?: return
        play(context, url, apiName, currentName ?: url, currentPoster, force = true)
    }

    fun playEpisode(index: Int) {
        if (index !in episodes.indices) return
        savePosition()
        status = Status.LOADING
        notifyListeners()
        loadJob?.cancel()
        loadJob = scope.launch { extractAndPlay(index) }
    }

    fun nextEpisode() {
        if (hasNextEpisode) playEpisode(episodeIndex + 1)
    }

    private suspend fun extractAndPlay(index: Int) {
        episodeIndex = index
        resumeSeekDone = false
        val gen = generator ?: run {
            status = Status.ERROR
            notifyListeners()
            return
        }
        val newLinks = mutableListOf<ExtractorLink>()
        val newSubs = mutableListOf<SubtitleData>()
        val ok = gen.generateLinks(
            clearCache = false,
            sourceTypes = LOADTYPE_INAPP,
            callback = { pair -> pair.first?.let { newLinks.add(it) } },
            subtitleCallback = { newSubs.add(it) },
            offset = index,
            isCasting = false
        )
        val playable = newLinks.filter {
            it.type == ExtractorLinkType.VIDEO ||
                it.type == ExtractorLinkType.M3U8 ||
                it.type == ExtractorLinkType.DASH
        }.sortedByDescending { it.quality }
        if (!ok || playable.isEmpty()) {
            status = Status.ERROR
            notifyListeners()
            return
        }
        links = playable
        subs = newSubs
        linkIndex = 0
        playCurrentLink(keepPosition = false)
        notifyListeners()
    }

    private fun playCurrentLink(keepPosition: Boolean) {
        val link = links.getOrNull(linkIndex) ?: return
        val player = exo ?: return
        val context = appContext ?: return
        val keepPos = if (keepPosition) player.currentPosition else 0L
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(link.getAllHeaders())
            .setAllowCrossProtocolRedirects(true)
        val item = MediaItem.Builder()
            .setUri(link.url)
            .setMimeType(
                when (link.type) {
                    ExtractorLinkType.M3U8 -> MimeTypes.APPLICATION_M3U8
                    ExtractorLinkType.DASH -> MimeTypes.APPLICATION_MPD
                    else -> null
                }
            )
            .setSubtitleConfigurations(subs.map { sub ->
                val mime = when {
                    sub.mimeType.contains("/") -> sub.mimeType
                    sub.url.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
                    sub.url.endsWith(".srt", true) -> MimeTypes.APPLICATION_SUBRIP
                    sub.url.endsWith(".ass", true) || sub.url.endsWith(".ssa", true) ->
                        MimeTypes.TEXT_SSA

                    else -> MimeTypes.TEXT_VTT
                }
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                    .setMimeType(mime)
                    .setLanguage(sub.languageCode)
                    .build()
            })
            .build()
        player.setMediaSource(
            DefaultMediaSourceFactory(context)
                .setDataSourceFactory(dataSourceFactory)
                .createMediaSource(item)
        )
        player.prepare()
        player.playWhenReady = true
        if (keepPos > 0) player.seekTo(keepPos)
        if (keepPosition) resumeSeekDone = true
    }

    /** Switches to another extracted link (the quality picker). */
    fun selectLink(index: Int) {
        if (index !in links.indices || index == linkIndex) return
        linkIndex = index
        playCurrentLink(keepPosition = true)
        notifyListeners()
    }

    fun togglePlay() {
        val player = exo ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun pause() {
        savePosition()
        exo?.pause()
    }

    fun stop() {
        savePosition()
        loadJob?.cancel()
        handler.removeCallbacks(saveRunnable)
        exo?.stop()
        exo?.clearMediaItems()
        status = Status.IDLE
        currentUrl = null
        minimized = false
        notifyListeners()
    }

    fun savePosition() {
        val player = exo ?: return
        val episode = currentEpisode ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        DataStoreHelper.setViewPos(episode.id, player.currentPosition, duration)
    }

    // Same episode construction ResultViewModel2 uses, so ids (and with
    // them resume positions) match the main player and the details page.
    private fun buildResultEpisodes(response: LoadResponse): List<ResultEpisode> {
        val mainId = response.getId()
        return when (response) {
            is MovieLoadResponse -> listOf(
                buildResultEpisode(
                    response.name, response.name, null, 0, null, null,
                    response.dataUrl, response.apiName, mainId, 0,
                    null, null, null, response.type, mainId, null
                )
            )

            is LiveStreamLoadResponse -> listOf(
                buildResultEpisode(
                    response.name, response.name, null, 0, null, null,
                    response.dataUrl, response.apiName, mainId, 0,
                    null, null, null, response.type, mainId, null
                )
            )

            is TvSeriesLoadResponse -> {
                val sorted = response.episodes.sortedBy {
                    (it.season?.times(10_000) ?: 0) + (it.episode ?: 0)
                }
                sorted.mapIndexed { index, ep ->
                    val episodeIndex = ep.episode ?: (index + 1)
                    val id = mainId + (ep.season?.times(100_000) ?: 0) + episodeIndex + 1
                    buildResultEpisode(
                        response.name, ep.name, ep.posterUrl, episodeIndex,
                        ep.season, ep.season, ep.data, response.apiName, id, index,
                        ep.score, ep.description, null, response.type, mainId, null,
                        airDate = ep.date, runTime = ep.runTime
                    )
                }
            }

            is AnimeLoadResponse -> {
                val dub = if (response.episodes.containsKey(DubStatus.Subbed)) {
                    DubStatus.Subbed
                } else {
                    response.episodes.keys.firstOrNull()
                } ?: return emptyList()
                val list = response.episodes[dub].orEmpty()
                list.mapIndexed { index, ep ->
                    val episodeIndex = ep.episode ?: (index + 1)
                    val id = mainId + episodeIndex + dub.id * 1_000_000 +
                        (ep.season?.times(10_000) ?: 0)
                    buildResultEpisode(
                        response.name, ep.name, ep.posterUrl, episodeIndex,
                        ep.season, ep.season, ep.data, response.apiName, id, index,
                        ep.score, ep.description, null, response.type, mainId,
                        null, airDate = ep.date, runTime = ep.runTime
                    )
                }
            }

            else -> emptyList()
        }
    }
}
