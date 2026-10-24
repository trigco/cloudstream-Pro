package com.lagradost.cloudstream3.ui.feed

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C.TRACK_TYPE_AUDIO
import androidx.media3.common.C.TRACK_TYPE_TEXT
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.GridLayoutManager
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.databinding.FragmentFeedWatchBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.LOADTYPE_INAPP
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.buildResultEpisode
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.ui.result.getRealPosition
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder.cleanPosterTitle
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.hapticTick
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Phase 8.18 — the Feed watch page (YouTube-style, portrait): a small
 * custom player on top, the feed continuing underneath as "Up next".
 *
 * Playback is direct: the tapped item's page is loaded in the background,
 * its links are extracted with the app's own RepoLinkGenerator, and the
 * best link plays in a plain ExoPlayer with custom controls. Positions
 * are saved with the same episode ids the main player uses, so handing
 * off to the main player (fullscreen button, or turning the phone to
 * landscape) resumes exactly where the feed player stopped.
 */
@UnstableApi
class FeedWatchFragment : BaseFragment<FragmentFeedWatchBinding>(
    BindingCreator.Inflate(FragmentFeedWatchBinding::inflate)
) {
    private var player: ExoPlayer? = null
    private var playJob: Job? = null
    private var generator: RepoLinkGenerator? = null
    private var episodes: List<ResultEpisode> = emptyList()
    private var currentIndex = 0
    private var currentLinks: List<ExtractorLink> = emptyList()
    private var currentSubs: List<SubtitleData> = emptyList()
    private var currentUrl: String? = null
    private var currentApiName: String? = null
    private var currentName: String? = null
    private var resumeSeekDone = false
    private var handedOff = false
    private var seeking = false
    private var tickCount = 0

    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500L)
        }
    }
    private val hideControlsRunnable = Runnable { binding?.feedWatchControls?.isVisible = false }

    private var orientationListener: OrientationEventListener? = null
    private var landscapeSince = 0L

    private val upNextAdapter = FeedAdapter(onPlay = { entry ->
        playFeedItem(entry.response.url, entry.apiName, entry.response.name)
        binding?.feedWatchUpnext?.scrollToPosition(0)
    })

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )
    }

    override fun onBindingCreated(binding: FragmentFeedWatchBinding) {
        binding.feedWatchUpnext.layoutManager = GridLayoutManager(context, 2)
        binding.feedWatchUpnext.adapter = upNextAdapter

        player = ExoPlayer.Builder(requireContext()).build().also { exo ->
            binding.feedWatchPlayer.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_BUFFERING) {
                        binding.feedWatchLoading.isVisible = true
                    } else {
                        binding.feedWatchLoading.isVisible = false
                    }
                    if (state == Player.STATE_READY && !resumeSeekDone) {
                        resumeSeekDone = true
                        val resume = episodes.getOrNull(currentIndex)?.getRealPosition() ?: 0L
                        if (resume > 0 && exo.currentPosition < 1_000) {
                            exo.seekTo(resume)
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    binding.feedWatchPlayer.keepScreenOn = isPlaying
                    binding.feedWatchPlayPause.setImageResource(
                        if (isPlaying) R.drawable.netflix_pause else R.drawable.netflix_play
                    )
                    if (isPlaying) scheduleHideControls() else showControls()
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    showError()
                }
            })
        }

        // Single tap toggles the controls; double tap skips ∓10 seconds
        // (left half back, right half forward), YouTube-style.
        val gestureDetector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    toggleControls()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val view = binding.feedWatchPlayer
                    if (e.x < view.width / 2) {
                        skipBy(-10_000L, back = true)
                    } else {
                        skipBy(10_000L, back = false)
                    }
                    return true
                }
            }
        )
        binding.feedWatchPlayer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
        binding.feedWatchPlayPause.setOnClickListener {
            val exo = player ?: return@setOnClickListener
            if (exo.isPlaying) exo.pause() else exo.play()
        }
        binding.feedWatchFullscreen.setOnClickListener { view ->
            view.hapticTick()
            openInMainPlayer()
        }
        binding.feedWatchQuality.setOnClickListener { showQuality() }
        binding.feedWatchSubtitles.setOnClickListener { showSubtitlePicker() }
        binding.feedWatchAudio.setOnClickListener { showAudioPicker() }
        binding.feedWatchSeek.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val exo = player ?: return
                    val duration = exo.duration.takeIf { it > 0 } ?: return
                    binding?.feedWatchTimeCurrent?.text =
                        formatTime(duration * progress / 1000)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seeking = false
                val exo = player ?: return
                val duration = exo.duration.takeIf { it > 0 } ?: return
                exo.seekTo(duration * (seekBar?.progress ?: 0) / 1000)
            }
        })
        binding.feedWatchRetry.setOnClickListener {
            val url = currentUrl ?: return@setOnClickListener
            val apiName = currentApiName ?: return@setOnClickListener
            playFeedItem(url, apiName, currentName ?: url, force = true)
        }

        val url = arguments?.getString("url") ?: return
        val apiName = arguments?.getString("apiName") ?: return
        val name = arguments?.getString("name") ?: url
        playFeedItem(url, apiName, name)
    }

    override fun onResume() {
        super.onResume()
        // The watch page lives in portrait; the main player restores its
        // own orientation when it takes over.
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        handler.post(progressRunnable)
        orientationListener?.enable()
        if (handedOff) {
            handedOff = false
            val url = currentUrl
            val apiName = currentApiName
            if (url != null && apiName != null) {
                playFeedItem(url, apiName, currentName ?: url, force = true)
            }
        }
    }

    override fun onPause() {
        savePosition()
        player?.pause()
        handler.removeCallbacks(progressRunnable)
        orientationListener?.disable()
        super.onPause()
    }

    override fun onDestroyView() {
        playJob?.cancel()
        savePosition()
        handler.removeCallbacksAndMessages(null)
        orientationListener?.disable()
        orientationListener = null
        player?.release()
        player = null
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
        super.onDestroyView()
    }

    // ---------- playback ----------

    private fun playFeedItem(url: String, apiName: String, name: String, force: Boolean = false) {
        if (!force && url == currentUrl && player?.playbackState != Player.STATE_IDLE) return
        currentUrl = url
        currentApiName = apiName
        currentName = name
        resumeSeekDone = false
        currentLinks = emptyList()
        playJob?.cancel()
        val binding = binding ?: return
        binding.feedWatchTitle.text = cleanPosterTitle(name)
        binding.feedWatchMeta.text = apiName
        binding.feedWatchErrorBox.isVisible = false
        binding.feedWatchControls.isVisible = false
        binding.feedWatchStatus.setText(R.string.feed_loading_video)
        binding.feedWatchStatus.isVisible = true
        binding.feedWatchLoading.isVisible = true
        player?.stop()
        player?.clearMediaItems()

        playJob = viewLifecycleOwner.lifecycleScope.launch {
            val api = getApiFromNameNull(apiName)
            if (api == null) {
                showError()
                return@launch
            }
            val repo = APIRepository(api)
            val response = when (val res = repo.load(url)) {
                is Resource.Success -> res.value
                else -> {
                    showError()
                    return@launch
                }
            }

            episodes = buildResultEpisodes(response)
            if (episodes.isEmpty()) {
                showError()
                return@launch
            }
            currentIndex = 0
            val gen = RepoLinkGenerator(episodes, page = response)
            generator = gen

            val links = mutableListOf<ExtractorLink>()
            val subs = mutableListOf<SubtitleData>()
            val ok = gen.generateLinks(
                clearCache = false,
                sourceTypes = LOADTYPE_INAPP,
                callback = { pair -> pair.first?.let { links.add(it) } },
                subtitleCallback = { subs.add(it) },
                offset = currentIndex,
                isCasting = false
            )
            val playable = links.filter {
                it.type == ExtractorLinkType.VIDEO ||
                    it.type == ExtractorLinkType.M3U8 ||
                    it.type == ExtractorLinkType.DASH
            }.sortedByDescending { it.quality }
            if (!ok || playable.isEmpty()) {
                showError()
                return@launch
            }
            currentLinks = playable
            currentSubs = subs
            binding.feedWatchStatus.isVisible = false
            binding.feedWatchQuality.isVisible = playable.size > 1
            updateHeaderMeta(apiName, response)
            refreshUpNext(url)
            setupOrientationHandoff()
            playLink(playable.first(), subs)
        }
    }

    private fun playLink(link: ExtractorLink, subs: List<SubtitleData>) {
        val context = context ?: return
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(link.getAllHeaders())
            .setAllowCrossProtocolRedirects(true)
        val exo = player ?: return
        val item = MediaItem.Builder()
            .setUri(link.url)
            .setMimeType(
                when (link.type) {
                    ExtractorLinkType.M3U8 -> MimeTypes.APPLICATION_M3U8
                    ExtractorLinkType.DASH -> MimeTypes.APPLICATION_MPD
                    else -> null
                }
            )
            .setSubtitleConfigurations(subs.mapNotNull { sub ->
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
        exo.setMediaSource(
            DefaultMediaSourceFactory(context)
                .setDataSourceFactory(dataSourceFactory)
                .createMediaSource(item)
        )
        exo.prepare()
        exo.playWhenReady = true
        showControls()
    }

    private fun showQuality() {
        val context = context ?: return
        if (currentLinks.size < 2) return
        val labels = currentLinks.map { link ->
            val quality = if (link.quality > 0) "${link.quality}p" else ""
            listOf(quality, link.name).filter { it.isNotBlank() }.joinToString(" • ")
                .ifBlank { link.url }
        }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.feed_quality)
            .setItems(labels) { _, which ->
                val exo = player ?: return@setItems
                val pos = exo.currentPosition
                playLink(currentLinks[which], currentSubs)
                exo.seekTo(pos)
            }
            .show()
    }

    private data class TrackOption(
        val label: String,
        val group: Tracks.Group,
        val index: Int,
        val selected: Boolean
    )

    private fun trackOptions(type: Int): List<TrackOption> {
        val exo = player ?: return emptyList()
        val options = mutableListOf<TrackOption>()
        for (group in exo.currentTracks.groups.filter { it.type == type }) {
            for (i in 0 until group.length) {
                val format = group.mediaTrackGroup.getFormat(i)
                val base = format.label ?: format.language ?: "Track ${i + 1}"
                val label = if (type == TRACK_TYPE_AUDIO && format.channelCount > 2) {
                    "$base • ${format.channelCount}ch"
                } else {
                    base
                }
                options.add(TrackOption(label, group, i, group.isTrackSelected(i)))
            }
        }
        return options
    }

    /** Subtitle picker: Off + every text track in the loaded media. */
    private fun showSubtitlePicker() {
        val context = context ?: return
        val exo = player ?: return
        val options = trackOptions(TRACK_TYPE_TEXT)
        if (options.isEmpty()) {
            Toast.makeText(context, R.string.feed_no_subtitles, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = arrayOf(
            getString(R.string.feed_off),
            *options.map { it.label }.toTypedArray()
        )
        val selectedIndex = options.indexOfFirst { it.selected }
        AlertDialog.Builder(context)
            .setTitle(R.string.feed_subtitles)
            .setSingleChoiceItems(labels, if (selectedIndex >= 0) selectedIndex + 1 else 0) { dialog, which ->
                val params = exo.trackSelectionParameters.buildUpon()
                if (which == 0) {
                    params.setTrackTypeDisabled(TRACK_TYPE_TEXT, true)
                        .clearOverridesOfType(TRACK_TYPE_TEXT)
                } else {
                    val option = options[which - 1]
                    params.setTrackTypeDisabled(TRACK_TYPE_TEXT, false)
                        .setOverrideForType(
                            TrackSelectionOverride(option.group.mediaTrackGroup, option.index)
                        )
                }
                exo.trackSelectionParameters = params.build()
                dialog.dismiss()
            }
            .show()
    }

    /** Audio picker: every audio track in the loaded media. */
    private fun showAudioPicker() {
        val context = context ?: return
        val exo = player ?: return
        val options = trackOptions(TRACK_TYPE_AUDIO)
        if (options.size <= 1) {
            Toast.makeText(context, R.string.feed_single_audio, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = options.map { it.label }.toTypedArray()
        val selectedIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle(R.string.feed_audio)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                val option = options[which]
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(option.group.mediaTrackGroup, option.index)
                    )
                    .build()
                dialog.dismiss()
            }
            .show()
    }

    private val skipHideRunnable = Runnable {
        binding?.feedWatchSkipBack?.isVisible = false
        binding?.feedWatchSkipForward?.isVisible = false
    }

    private fun skipBy(deltaMs: Long, back: Boolean) {
        val exo = player ?: return
        val binding = binding ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0L, duration))
        binding.feedWatchPlayer.hapticTick()
        binding.feedWatchSkipBack.isVisible = back
        binding.feedWatchSkipForward.isVisible = !back
        handler.removeCallbacks(skipHideRunnable)
        handler.postDelayed(skipHideRunnable, 600L)
    }

    // ---------- handoff to the main player ----------

    private fun setupOrientationHandoff() {
        val context = context ?: return
        if (orientationListener != null) return
        orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                val isLandscape = orientation in 70..110 || orientation in 250..290
                if (isLandscape && player?.isPlaying == true) {
                    if (landscapeSince == 0L) landscapeSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - landscapeSince > 700) {
                        landscapeSince = 0L
                        openInMainPlayer()
                    }
                } else {
                    landscapeSince = 0L
                }
            }
        }
        orientationListener?.enable()
    }

    private fun openInMainPlayer() {
        val gen = generator ?: return
        if (handedOff) return
        handedOff = true
        savePosition()
        player?.pause()
        activity.navigate(
            R.id.global_to_navigation_player,
            GeneratorPlayer.newInstance(gen, currentIndex)
        )
    }

    // ---------- helpers ----------

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

    private fun updateHeaderMeta(apiName: String, response: LoadResponse) {
        val binding = binding ?: return
        val ep = episodes.getOrNull(currentIndex)
        val epText = if (ep != null && ep.episode > 0) {
            " • S${ep.season ?: 1} E${ep.episode}"
        } else {
            ""
        }
        binding.feedWatchMeta.text =
            "$apiName • ${FeedAdapter.typeLabel(response.type)}$epText"
    }

    private fun refreshUpNext(exceptUrl: String) {
        upNextAdapter.submitList(
            FeedPool.entries.filter { it.response.url != exceptUrl }
        )
    }

    private fun showError() {
        val binding = binding ?: return
        binding.feedWatchLoading.isVisible = false
        binding.feedWatchStatus.isVisible = false
        binding.feedWatchControls.isVisible = false
        binding.feedWatchErrorBox.isVisible = true
        binding.feedWatchOpenDetails.setOnClickListener {
            val url = currentUrl ?: return@setOnClickListener
            FeedPool.entries.firstOrNull { it.response.url == url }?.let { entry ->
                activity.loadSearchResult(entry.response)
            }
        }
    }

    private fun savePosition() {
        val exo = player ?: return
        val ep = episodes.getOrNull(currentIndex) ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: return
        DataStoreHelper.setViewPos(ep.id, exo.currentPosition, duration)
    }

    private fun updateProgress() {
        val exo = player ?: return
        val binding = binding ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: 0L
        if (!seeking && duration > 0) {
            binding.feedWatchSeek.progress =
                (exo.currentPosition * 1000 / duration).toInt()
        }
        binding.feedWatchTimeCurrent.text = formatTime(exo.currentPosition)
        binding.feedWatchTimeTotal.text = formatTime(duration)
        if (++tickCount % 10 == 0) savePosition()
    }

    private fun toggleControls() {
        val binding = binding ?: return
        if (binding.feedWatchControls.isVisible) {
            binding.feedWatchControls.isVisible = false
            handler.removeCallbacks(hideControlsRunnable)
        } else {
            showControls()
        }
    }

    private fun showControls() {
        val binding = binding ?: return
        if (currentLinks.isEmpty()) return
        binding.feedWatchControls.isVisible = true
        scheduleHideControls()
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (player?.isPlaying == true) {
            handler.postDelayed(hideControlsRunnable, 3_000L)
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    companion object {
        fun newInstance(url: String, apiName: String, name: String): Bundle =
            Bundle().apply {
                putString("url", url)
                putString("apiName", apiName)
                putString("name", name)
            }
    }
}
