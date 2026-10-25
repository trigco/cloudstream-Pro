package com.lagradost.cloudstream3.ui.feed

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.exoplayer.DefaultLoadControl
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
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.player.CS3IPlayer
import com.lagradost.cloudstream3.ui.player.FixedNextRenderersFactory
import com.lagradost.cloudstream3.ui.player.UpdatedDefaultExtractorsFactory
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Phase 8.20/8.21 — the Feed's shared playback engine. The ExoPlayer
 * lives here, not in a fragment, so playback survives the trip between
 * the watch page and the Feed's mini player: surfaces come and go, the
 * player (and its position) stays.
 *
 * It mirrors the app's real player where it matters: the same
 * FixedNextRenderersFactory (nextlib extension decoders), the same
 * episode-id scheme for resume positions, and a fallback walk down
 * the link list when a source fails — preferring other links of the
 * chosen resolution first. Playback starts from the COMPLETED link
 * set, exactly like the main player: starting from the first link
 * that happened to arrive (the 8.21 fast start) kept failing on
 * multi-source providers, whose early links are not the playable
 * ones — the main player only ever plays early when the user
 * presses Skip Loading himself.
 *
 * Every load carries a generation stamp: starting another video bumps
 * it, and any stale load that finishes late drops its results instead
 * of overwriting the video the viewer actually picked.
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

    /** The loaded page behind the generator (kept for handoffs). */
    var currentPage: LoadResponse? = null
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

    /** Distinct resolutions on offer, best first (the quality picker). */
    val qualities: List<Int>
        get() = links.map { it.quality }.distinct().sortedDescending()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    private var generation = 0
    private var resumeSeekDone = false
    private var extracting = false
    private var pendingRetry = false
    private var preferredQuality = 0
    private val triedLinks = mutableSetOf<Int>()
    private var appContext: Context? = null
    private val listeners = mutableSetOf<() -> Unit>()

    private val handler = Handler(Looper.getMainLooper())
    private var watchdog: Runnable? = null
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
        // The main player's buffering, unchanged: the lighter preview
        // load control from the fast-start experiment is gone — with
        // multi-source providers, parity beats shaving a second.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setBackBuffer(30_000, true)
            .build()
        val player = ExoPlayer.Builder(appContext)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    if (!resumeSeekDone) {
                        resumeSeekDone = true
                        // Read the saved position from the store directly
                        // (the episode objects' fields can be stale),
                        // with the same 5–95% rule getRealPosition uses.
                        val resume = currentEpisode?.id?.let { id ->
                            DataStoreHelper.getViewPos(id)
                        }?.let { pd ->
                            if (pd.duration <= 0) {
                                0L
                            } else {
                                val pct = pd.position * 100 / pd.duration
                                if (pct <= 5 || pct >= 95) 0L else pd.position
                            }
                        } ?: 0L
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
                failOver()
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
        val gen = ++generation
        minimized = false
        currentUrl = url
        currentApiName = apiName
        currentName = name
        currentPoster = posterUrl
        status = Status.LOADING
        resumeSeekDone = false
        extracting = true
        pendingRetry = false
        links = emptyList()
        subs = emptyList()
        linkIndex = 0
        triedLinks.clear()
        notifyListeners()
        player.stop()
        player.clearMediaItems()

        loadJob = scope.launch {
            val api = getApiFromNameNull(apiName)
            if (api == null) {
                if (gen == generation) {
                    status = Status.ERROR
                    notifyListeners()
                }
                return@launch
            }
            val response = when (val res = APIRepository(api).load(url)) {
                is Resource.Success -> res.value
                else -> {
                    if (gen == generation) {
                        status = Status.ERROR
                        notifyListeners()
                    }
                    return@launch
                }
            }
            if (gen != generation) return@launch
            episodes = buildResultEpisodes(response)
            if (episodes.isEmpty()) {
                status = Status.ERROR
                notifyListeners()
                return@launch
            }
            currentPage = response
            generator = RepoLinkGenerator(episodes, page = response)
            extractAndPlay(0, gen)
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
        val gen = ++generation
        status = Status.LOADING
        notifyListeners()
        loadJob?.cancel()
        loadJob = scope.launch { extractAndPlay(index, gen) }
    }

    fun nextEpisode() {
        if (hasNextEpisode) playEpisode(episodeIndex + 1)
    }

    private fun playableOf(all: List<ExtractorLink>): List<ExtractorLink> =
        all.filter {
            it.type == ExtractorLinkType.VIDEO ||
                it.type == ExtractorLinkType.M3U8 ||
                it.type == ExtractorLinkType.DASH
        }.sortedWith(
            // Best resolution first; within one resolution prefer
            // adaptive formats (DASH, then HLS) over a progressive
            // file — the main player's source list orders them the
            // same way, and adaptive starts far faster on mobile data.
            compareByDescending<ExtractorLink> { it.quality }
                .thenBy {
                    when (it.type) {
                        ExtractorLinkType.DASH -> 0
                        ExtractorLinkType.M3U8 -> 1
                        else -> 2
                    }
                }
        )

    private suspend fun extractAndPlay(index: Int, gen: Int) = coroutineScope {
        episodeIndex = index
        resumeSeekDone = false
        extracting = true
        pendingRetry = false
        triedLinks.clear()
        val gen2 = generator ?: run {
            status = Status.ERROR
            notifyListeners()
            return@coroutineScope
        }
        val newLinks = mutableListOf<ExtractorLink>()
        val newSubs = mutableListOf<SubtitleData>()
        val deferred = async {
            gen2.generateLinks(
                clearCache = false,
                sourceTypes = LOADTYPE_INAPP,
                callback = { pair ->
                    pair.first?.let { synchronized(newLinks) { newLinks.add(it) } }
                },
                subtitleCallback = { synchronized(newSubs) { newSubs.add(it) } },
                offset = index,
                isCasting = false
            )
        }

        // Wait for extraction to complete, like the main player
        // does behind its loading screen — the feed just shows the
        // poster and spinner meanwhile, no skip button to press.
        // Playing from a partial list kept failing on multi-source
        // providers: the first links to arrive are not necessarily
        // playable, and picking from the finished, sorted set is
        // what makes the main player reliable on the same title.
        val ok = try {
            deferred.await()
        } catch (e: Exception) {
            false
        }
        extracting = false
        if (gen != generation) return@coroutineScope
        val full = synchronized(newLinks) { playableOf(ArrayList(newLinks)) }
        links = full
        subs = synchronized(newSubs) { ArrayList(newSubs) }
        pendingRetry = false
        if (full.isEmpty()) {
            status = Status.ERROR
            notifyListeners()
            return@coroutineScope
        }
        // Even when extraction reports failure, whatever links were
        // collected are worth trying (the main player's Skip Loading
        // plays with a partial set the same way).
        linkIndex = 0
        preferredQuality = full.first().quality
        playCurrentLink(keepPosition = false)
        notifyListeners()
    }

    /**
     * Moves to the next untried link — other links of the chosen
     * resolution first, then anything. While extraction is still
     * running in the background this waits instead of failing: the
     * pickup in extractAndPlay continues when the rest arrive.
     */
    private fun failOver() {
        val nextSame = links.indices.firstOrNull {
            it != linkIndex && it !in triedLinks &&
                links[it].quality == preferredQuality
        }
        val nextAny = links.indices.firstOrNull {
            it != linkIndex && it !in triedLinks
        }
        val next = nextSame ?: nextAny
        if (next != null) {
            linkIndex = next
            playCurrentLink(keepPosition = true)
        } else if (extracting) {
            pendingRetry = true
            status = Status.LOADING
        } else {
            status = Status.ERROR
        }
        notifyListeners()
    }

    /**
     * A bad link does not always error — some just hang in BUFFERING
     * forever (a frame at the resume point, zero data flowing). If
     * 12 s after starting a link the player still has essentially
     * nothing buffered ahead and is not playing, treat the link as a
     * dud and walk on, exactly like an error.
     */
    private fun armWatchdog(gen: Int, idx: Int) {
        watchdog?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            if (gen != generation || idx != linkIndex) return@Runnable
            val player = exo ?: return@Runnable
            val stuck = player.playbackState == Player.STATE_BUFFERING ||
                (player.playbackState == Player.STATE_READY &&
                    player.playWhenReady && !player.isPlaying &&
                    player.bufferedPosition <= player.currentPosition + 1_000L)
            if (stuck) failOver()
        }
        watchdog = runnable
        handler.postDelayed(runnable, 12_000L)
    }

    /**
     * The main player's transport, replicated from
     * CS3IPlayer.createVideoSource (Phase 8.26): a provider may
     * supply a custom interceptor for its links — then playback
     * goes through OkHttp with that interceptor applied. Otherwise
     * it goes through a Cronet engine (Chromium's network stack:
     * HTTP/2, QUIC, Chrome TLS fingerprint, its own cookie
     * handling) when one can be created, falling back to the app's
     * OkHttp client. The feed previously always used plain OkHttp,
     * which is the one remaining way its requests differed from the
     * main player's — some providers' CDNs stall or refuse it
     * while serving the main player fine.
     */
    private fun createVideoDataSource(context: Context, link: ExtractorLink): DataSource.Factory {
        val userAgent = link.headers.entries.find {
            it.key.equals("User-Agent", ignoreCase = true)
        }?.value ?: USER_AGENT
        val interceptor = getApiFromNameNull(link.source)?.getVideoInterceptor(link)
        val source: HttpDataSource.Factory = if (interceptor != null) {
            val client = app.baseClient.newBuilder()
                .addInterceptor(interceptor)
                .build()
            OkHttpDataSource.Factory(client).setUserAgent(userAgent)
        } else {
            val engine = CS3IPlayer.tryCreateEngine(context, 0L)
            if (engine != null) {
                CronetDataSource.Factory(engine, Executors.newSingleThreadExecutor())
                    .setUserAgent(userAgent)
                    .setConnectionTimeoutMs(15_000)
                    .setReadTimeoutMs(15_000)
                    .setResetTimeoutOnRedirects(true)
                    .setHandleSetCookieRequests(true)
            } else {
                OkHttpDataSource.Factory(app.baseClient).setUserAgent(userAgent)
            }
        }
        val refererMap = if (link.referer.isBlank()) {
            emptyMap()
        } else {
            mapOf("referer" to link.referer)
        }
        return source.apply {
            setDefaultRequestProperties(refererMap + link.headers)
        }
    }

    private fun playCurrentLink(keepPosition: Boolean) {
        val link = links.getOrNull(linkIndex) ?: return
        val player = exo ?: return
        val context = appContext ?: return
        triedLinks.add(linkIndex)
        val keepPos = if (keepPosition) player.currentPosition else 0L
        val dataSourceFactory = createVideoDataSource(context, link)
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
            DefaultMediaSourceFactory(
                dataSourceFactory,
                UpdatedDefaultExtractorsFactory()
                    .setFragmentedMp4ExtractorFlags(
                        FragmentedMp4Extractor.FLAG_MERGE_FRAGMENTED_SIDX
                    )
            ).createMediaSource(item)
        )
        player.prepare()
        player.playWhenReady = true
        if (keepPos > 0) player.seekTo(keepPos)
        if (keepPosition) resumeSeekDone = true
        armWatchdog(generation, linkIndex)
    }

    /** Plays one specific link (the source picker's rows). */
    fun selectLink(index: Int) {
        if (index !in links.indices || index == linkIndex) return
        linkIndex = index
        preferredQuality = links[index].quality
        playCurrentLink(keepPosition = true)
        notifyListeners()
    }

    /**
     * Switches resolution. Several links can share one resolution;
     * picking it starts at its first link and the error fallback
     * stays inside the same resolution before moving on.
     */
    fun selectQuality(quality: Int) {
        if (links.none { it.quality == quality }) return
        preferredQuality = quality
        val idx = links.indices.firstOrNull {
            links[it].quality == quality && it !in triedLinks
        } ?: links.indexOfFirst { it.quality == quality }
        if (idx < 0 || idx == linkIndex) return
        linkIndex = idx
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
        generation++
        extracting = false
        pendingRetry = false
        watchdog?.let { handler.removeCallbacks(it) }
        watchdog = null
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
