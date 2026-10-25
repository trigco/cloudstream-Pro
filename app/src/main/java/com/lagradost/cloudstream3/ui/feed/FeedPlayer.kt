package com.lagradost.cloudstream3.ui.feed

import android.content.Context
import android.net.Uri
import android.util.Log
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
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.amap
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
import com.lagradost.cloudstream3.ui.player.LOADTYPE_ALL
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.buildResultEpisode
import com.lagradost.cloudstream3.LoadResponse.Companion.isMovie
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
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
import kotlinx.coroutines.withContext
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

    // Phase 8.27 diagnostic: an on-screen playback log. After five
    // rounds of inferred fixes for "feed will not cold-play DASH",
    // this records exactly what happens — which links extraction
    // returned, which transport each attempt used, and the precise
    // player error — and the watch screen shows it in the error
    // state so it can be screenshotted instead of guessed at.
    private val debugLog = mutableListOf<String>()

    fun debugSnapshot(): String = debugLog.takeLast(14).joinToString("\n")

    /** Links collected so far by the running extraction (loading UI). */
    var extractedCount: Int = 0
        private set

    @Volatile
    private var earlyStarted = false

    private fun dbg(line: String) {
        debugLog.add(line)
        if (debugLog.size > 80) debugLog.removeAt(0)
        Log.d("FeedPlayer", line)
    }
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

    // Phase 8.37: one cross-provider attempt per play() — when
    // this provider has no playable links for the title, try the
    // same title on other providers before showing the error page.
    private var fallbackAllowed = false
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
                var chain = error.errorCodeName
                var cause: Throwable? = error.cause
                var depth = 0
                while (cause != null && depth < 3) {
                    chain += " <- " + cause.javaClass.simpleName + ": " +
                        (cause.message ?: "").take(110)
                    cause = cause.cause
                    depth++
                }
                dbg("ERR L$linkIndex $chain")
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
        debugLog.clear()
        dbg("PLAY " + name.take(40))
        dbg("url=" + hostOf(url) + pathOf(url))
        minimized = false
        currentUrl = url
        currentApiName = apiName
        currentName = name
        currentPoster = posterUrl
        status = Status.LOADING
        resumeSeekDone = false
        extracting = true
        pendingRetry = false
        fallbackAllowed = true
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
            val response = when (
                val res = withContext(Dispatchers.IO) { APIRepository(api).load(url) }
            ) {
                is Resource.Success -> res.value
                else -> {
                    if (gen == generation && fallbackAllowed) {
                        fallbackAllowed = false
                        if (tryCrossProviderFallback(gen, null)) return@launch
                    }
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
                if (fallbackAllowed) {
                    fallbackAllowed = false
                    if (tryCrossProviderFallback(gen, null)) return@launch
                }
                if (gen == generation) {
                    status = Status.ERROR
                    notifyListeners()
                }
                return@launch
            }
            currentPage = response
            // Some providers hand the feed items whose "name" is
            // just a numeric id — the loaded page knows the real
            // title; prefer it for the watch header.
            val passedName = currentName
            if ((passedName.isNullOrBlank() || passedName.all { it.isDigit() }) &&
                response.name.isNotBlank() && !response.name.all { it.isDigit() }
            ) {
                currentName = response.name
            }
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
        fallbackAllowed = false
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

    /**
     * Phase 8.34: queue the current episode in the app's download
     * manager — the same DownloadQueueItem the details page builds.
     */
    fun downloadCurrentEpisode(): Boolean {
        val page = currentPage ?: return false
        val episode = currentEpisode ?: return false
        // Phase 8.37: hand the downloader the link that is
        // actually selected (source + quality + its audio) and
        // the extracted subtitles, instead of letting it pick
        // its own — the service downloads given links as-is.
        val chosen = links.getOrNull(linkIndex)
        DownloadQueueManager.addToQueue(
            DownloadObjects.DownloadQueueItem(
                episode,
                page.isMovie(),
                page.name,
                page.type,
                page.posterUrl,
                page.apiName,
                page.getId(),
                page.url,
                links = chosen?.let { listOf(it) },
                subs = subs.ifEmpty { null }
            ).toWrapper()
        )
        return true
    }

    private fun normalizedTitle(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\s+"), " ")
            .trim()

    private fun titlesMatch(candidate: String, wanted: String): Boolean {
        val a = normalizedTitle(candidate)
        val b = normalizedTitle(wanted)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || (b.length >= 3 && a.contains(b)) ||
            (a.length >= 3 && b.contains(a))
    }

    /**
     * Phase 8.37: the title is not playable on its own provider —
     * search the other providers for the same title and continue
     * playback there, without ever showing the error page. One
     * level only; the caller consumes fallbackAllowed first.
     * Returns true when a candidate took over (its extraction is
     * already running as this call returns).
     */
    private suspend fun tryCrossProviderFallback(
        gen: Int,
        origEp: ResultEpisode?
    ): Boolean {
        val title = currentName?.takeIf { it.isNotBlank() } ?: return false
        val others = APIHolder.apis.filter {
            it.name != currentApiName && it.name != "None"
        }
        if (others.isEmpty()) return false
        dbg("cross-provider search: " + title.take(40))
        val candidates = others.amap { api ->
            try {
                when (val r = withContext(Dispatchers.IO) { APIRepository(api).search(title, 1) }) {
                    is Resource.Success -> r.value.list.firstOrNull {
                        titlesMatch(it.name, title)
                    }?.let { hit -> hit to api }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }.filterNotNull()
        for ((hit, api) in candidates.take(3)) {
            if (gen != generation) return false
            val page = when (
                val res = withContext(Dispatchers.IO) { APIRepository(api).load(hit.url) }
            ) {
                is Resource.Success -> res.value
                else -> continue
            }
            val eps = buildResultEpisodes(page)
            if (eps.isEmpty()) continue
            val target = if (origEp != null && (origEp.season != null || origEp.episode > 0)) {
                eps.indexOfFirst {
                    it.season == origEp.season && it.episode == origEp.episode
                }.takeIf { it >= 0 } ?: 0
            } else {
                0
            }
            currentUrl = hit.url
            currentApiName = api.name
            currentPage = page
            episodes = eps
            generator = RepoLinkGenerator(eps, page = page)
            dbg("cross-provider -> " + api.name)
            extractAndPlay(target, gen)
            return true
        }
        return false
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
        earlyStarted = false
        extractedCount = 0
        triedLinks.clear()
        val gen2 = generator ?: run {
            status = Status.ERROR
            notifyListeners()
            return@coroutineScope
        }
        val newLinks = mutableListOf<ExtractorLink>()
        val newSubs = mutableListOf<SubtitleData>()
        val extractStart = System.currentTimeMillis()
        val deferred = async(Dispatchers.IO) {
            gen2.generateLinks(
                clearCache = false,
                sourceTypes = LOADTYPE_ALL,
                callback = { pair ->
                    pair.first?.let {
                        synchronized(newLinks) { newLinks.add(it) }
                        scope.launch {
                            extractedCount = synchronized(newLinks) { newLinks.size }
                            notifyListeners()
                            tryEarlyStart(gen, newLinks)
                        }
                    }
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
        var ok = try {
            deferred.await()
        } catch (e: Exception) {
            false
        }
        // Phase 8.30: a cold first pass can come back ok=true with
        // ZERO links in ~2 s (the provider's loadLinks returns
        // before its real work — the main player only ever meets
        // this state warmed, because reaching it goes through the
        // details page first). One immediate second pass on the
        // same generator is what pressing Retry / Play in main
        // player effectively does; do it automatically instead.
        if (synchronized(newLinks) { newLinks.isEmpty() } && gen == generation) {
            delay(700)
            val retryStart = System.currentTimeMillis()
            val ok2 = try {
                withContext(Dispatchers.IO) {
                    gen2.generateLinks(
                    clearCache = false,
                    sourceTypes = LOADTYPE_ALL,
                    callback = { pair ->
                        pair.first?.let { synchronized(newLinks) { newLinks.add(it) } }
                    },
                    subtitleCallback = { synchronized(newSubs) { newSubs.add(it) } },
                    offset = index,
                    isCasting = false
                    )
                }
            } catch (e: Exception) {
                false
            }
            ok = ok || ok2
            dbg(
                "pass2 ok=" + ok2 + " links=" +
                    synchronized(newLinks) { newLinks.size } +
                    " dur=" + (System.currentTimeMillis() - retryStart) + "ms"
            )
        }
        extracting = false
        dbg(
            "extract ok=" + ok + " links=" + newLinks.size +
                " dur=" + (System.currentTimeMillis() - extractStart) + "ms"
        )
        episodes.getOrNull(index)?.let { ep ->
            dbg("data=" + hostOf(ep.data) + pathOf(ep.data))
            val entry = synchronized(RepoLinkGenerator.cache) {
                RepoLinkGenerator.cache[ep.apiName to ep.id]
            }
            if (entry == null) {
                dbg("gcache=none")
            } else {
                val info = synchronized(entry) {
                    "gcache=" + entry.linkCache.size + " sat=" + entry.saturated +
                        " types=" + entry.linkCache.map { it.type }.distinct()
                        .joinToString(",")
                }
                dbg(info)
            }
        }
        newLinks.forEachIndexed { i, l ->
            dbg(
                "L$i " + l.name.take(44) + " q=" + l.quality +
                    " " + l.type + " @" + hostOf(l.url)
            )
        }
        if (gen != generation) return@coroutineScope
        val full = synchronized(newLinks) { playableOf(ArrayList(newLinks)) }
        subs = synchronized(newSubs) { ArrayList(newSubs) }
        if (full.isEmpty()) {
            if (fallbackAllowed && gen == generation) {
                fallbackAllowed = false
                if (tryCrossProviderFallback(gen, episodes.getOrNull(index))) {
                    return@coroutineScope
                }
            }
            pendingRetry = false
            status = Status.ERROR
            notifyListeners()
            return@coroutineScope
        }
        if (earlyStarted) {
            // Already playing from the early set: fold the full
            // sorted list in (source picker, failover) without
            // disturbing playback — unless the early link died
            // while extraction was still running.
            val current = links.getOrNull(linkIndex)
            links = full
            val newIdx = full.indexOfFirst { it.url == current?.url }
            if (pendingRetry) {
                pendingRetry = false
                linkIndex = 0
                preferredQuality = full.first().quality
                playCurrentLink(keepPosition = true)
            } else if (newIdx >= 0) {
                linkIndex = newIdx
            }
            notifyListeners()
            return@coroutineScope
        }
        pendingRetry = false
        // Even when extraction reports failure, whatever links were
        // collected are worth trying (the main player's Skip Loading
        // plays with a partial set the same way).
        linkIndex = 0
        preferredQuality = full.first().quality
        playCurrentLink(keepPosition = false)
        notifyListeners()
    }

    /**
     * Phase 8.32: start playing as soon as the first playable link
     * arrives instead of waiting out the full extraction — on
     * multi-source providers that wait can be ~18 s, which is what
     * made the loading screen feel endless. Extraction keeps
     * running in the background; when it completes, extractAndPlay
     * folds the full sorted set in. This is the main player's
     * Skip Loading, automated.
     */
    private fun tryEarlyStart(gen: Int, collected: MutableList<ExtractorLink>) {
        if (gen != generation || earlyStarted || !extracting) return
        val playable = synchronized(collected) { playableOf(ArrayList(collected)) }
        if (playable.isEmpty()) return
        earlyStarted = true
        links = playable
        linkIndex = 0
        preferredQuality = playable.first().quality
        dbg("early start with " + playable.size + " links")
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
        // Phase 8.34: never declare an error over live playback —
        // the owner saw the error buttons (with "Play in main
        // player") while the video was still playing behind them.
        if (exo?.isPlaying == true) {
            dbg("failover skipped: still playing")
            notifyListeners()
            return
        }
        dbg("failover from L$linkIndex")
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
            // Truly giving up: silence the player so sound never
            // plays behind the error screen.
            try {
                exo?.stop()
            } catch (e: Exception) {
                // player already gone
            }
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
    private var watchdogStartPos = 0L

    private fun armWatchdog(gen: Int, idx: Int) {
        watchdog?.let { handler.removeCallbacks(it) }
        watchdogStartPos = exo?.currentPosition ?: 0L
        val runnable = Runnable {
            if (gen != generation || idx != linkIndex) return@Runnable
            val player = exo ?: return@Runnable
            if (player.isPlaying) return@Runnable
            // Phase 8.34: judge by PROGRESS, not by state alone.
            // On a slow connection a healthy link can sit in
            // BUFFERING well past 12 s — failing over then (and
            // eventually erroring) while the video later played
            // anyway is what put the error screen over live video.
            val advanced = player.currentPosition - watchdogStartPos
            val ahead = player.bufferedPosition - player.currentPosition
            val stateStuck = player.playbackState == Player.STATE_BUFFERING ||
                (player.playbackState == Player.STATE_READY &&
                    player.playWhenReady && !player.isPlaying &&
                    ahead <= 1_000L)
            if (stateStuck && advanced < 2_000L && ahead <= 1_000L) {
                dbg(
                    "watchdog state=" + player.playbackState +
                        " buffered=" + player.bufferedPosition
                )
                failOver()
            } else if (player.playbackState == Player.STATE_BUFFERING && ahead > 1_000L) {
                // Slow, but the buffer is growing — look again later.
                armWatchdog(gen, idx)
            }
        }
        watchdog = runnable
        handler.postDelayed(runnable, 12_000L)
    }

    private fun hostOf(url: String): String {
        return try {
            java.net.URI(url).host ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }

    private fun pathOf(url: String): String {
        return try {
            (java.net.URI(url).path ?: "").take(52)
        } catch (e: Exception) {
            "?"
        }
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
    private var lastTransport: String = "?"

    private fun createVideoDataSource(context: Context, link: ExtractorLink): DataSource.Factory {
        val userAgent = link.headers.entries.find {
            it.key.equals("User-Agent", ignoreCase = true)
        }?.value ?: USER_AGENT
        val interceptor = getApiFromNameNull(link.source)?.getVideoInterceptor(link)
        val source: HttpDataSource.Factory = if (interceptor != null) {
            lastTransport = "okhttp+interceptor"
            val client = app.baseClient.newBuilder()
                .addInterceptor(interceptor)
                .build()
            OkHttpDataSource.Factory(client).setUserAgent(userAgent)
        } else {
            val engine = CS3IPlayer.tryCreateEngine(context, 0L)
            if (engine != null) {
                lastTransport = "cronet"
                CronetDataSource.Factory(engine, Executors.newSingleThreadExecutor())
                    .setUserAgent(userAgent)
                    .setConnectionTimeoutMs(15_000)
                    .setReadTimeoutMs(15_000)
                    .setResetTimeoutOnRedirects(true)
                    .setHandleSetCookieRequests(true)
            } else {
                lastTransport = "okhttp(no cronet)"
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
        dbg("try L$linkIndex via $lastTransport")
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
