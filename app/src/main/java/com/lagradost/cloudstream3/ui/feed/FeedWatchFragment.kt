package com.lagradost.cloudstream3.ui.feed

import android.animation.ValueAnimator
import android.content.pm.ActivityInfo
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
import androidx.media3.common.C.TRACK_TYPE_AUDIO
import androidx.media3.common.C.TRACK_TYPE_TEXT
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentFeedWatchBinding
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder.cleanPosterTitle
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.launch
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.hapticTick

/**
 * Phase 8.18–8.20 — the Feed watch page (YouTube-style, portrait).
 * Playback itself lives in [FeedPlayer] (shared with the mini player);
 * this fragment is the full-screen UI over it: custom controls,
 * double-tap skip, quality/subtitle/audio pickers, next episode,
 * drag-down to minimise into the Feed, and the landscape/fullscreen
 * handoff to the main player.
 */
@UnstableApi
class FeedWatchFragment : BaseFragment<FragmentFeedWatchBinding>(
    BindingCreator.Inflate(FragmentFeedWatchBinding::inflate)
) {
    private var handedOff = false
    private var seeking = false
    private var tickCount = 0
    private var lastShownUrl: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500L)
        }
    }
    private val hideControlsRunnable = Runnable { binding?.feedWatchControls?.isVisible = false }
    private val skipHideRunnable = Runnable {
        binding?.feedWatchSkipBack?.isVisible = false
        binding?.feedWatchSkipForward?.isVisible = false
    }

    // Phase 8.38: navigating to the batch download page must not
    // kill the video being watched; onDestroyView checks this.
    private var goingToBatch = false

    private var titleFullHeight = 0
    private var titleShown = true
    private var titleAnimator: ValueAnimator? = null
    private var titleScrollAccum = 0

    private var orientationListener: OrientationEventListener? = null
    private var landscapeSince = 0L

    private val upNextAdapter = FeedAdapter(
        onPlay = { entry ->
            context?.let {
                FeedPlayer.play(
                    it, entry.response.url, entry.apiName,
                    entry.response.name, entry.response.posterUrl
                )
            }
            binding?.feedWatchUpnext?.scrollToPosition(0)
        },
        onHide = { entry -> confirmHide(entry) }
    )

    /** Phase 8.22: long-press a card → offer to hide it for good. */
    private fun confirmHide(entry: FeedEntry) {
        val context = context ?: return
        AlertDialog.Builder(context)
            .setTitle(FeedAdapter.displayTitle(entry.response.name, entry.response.url))
            .setMessage(R.string.feed_hide_message)
            .setPositiveButton(R.string.feed_hide) { _, _ ->
                FeedHidden.hide(context, entry.response.url)
                FeedPool.hide(entry.response.url)
                FeedPlayer.currentUrl?.let { refreshUpNext(it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val stateListener: () -> Unit = { updatePlaybackUi() }

    /**
     * Phase 8.23 — the title bar gets out of the way while the viewer
     * scrolls Up next (YouTube-style) and comes back on scroll up.
     */
    private fun setupTitleAutoHide(binding: FragmentFeedWatchBinding) {
        binding.feedWatchTitleBox.post {
            titleFullHeight = binding.feedWatchTitleBox.height
        }
        binding.feedWatchUpnext.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                titleScrollAccum += dy
                if (titleScrollAccum > 48 && titleShown) {
                    animateTitleBox(binding, show = false)
                    titleScrollAccum = 0
                } else if (titleScrollAccum < -24 && !titleShown) {
                    animateTitleBox(binding, show = true)
                    titleScrollAccum = 0
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (!recyclerView.canScrollVertically(-1) && !titleShown) {
                    animateTitleBox(binding, show = true)
                    titleScrollAccum = 0
                }
            }
        })
    }

    private fun animateTitleBox(binding: FragmentFeedWatchBinding, show: Boolean) {
        if (show == titleShown || titleFullHeight == 0) return
        titleShown = show
        val box = binding.feedWatchTitleBox
        val from = box.height
        val to = if (show) titleFullHeight else 0
        titleAnimator?.cancel()
        titleAnimator = ValueAnimator.ofInt(from, to).apply {
            duration = 200L
            addUpdateListener { animator ->
                val params = box.layoutParams
                params.height = animator.animatedValue as Int
                box.layoutParams = params
                box.alpha = if (to == 0) {
                    1f - animator.animatedFraction
                } else {
                    animator.animatedFraction
                }
            }
            start()
        }
    }

    private var lastPlayerStatus: FeedPlayer.Status = FeedPlayer.Status.IDLE

    private val iconListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val binding = binding ?: return
            binding.feedWatchPlayer.keepScreenOn = isPlaying
            binding.feedWatchPlayPause.setImageResource(
                if (isPlaying) R.drawable.netflix_pause else R.drawable.netflix_play
            )
            if (isPlaying) {
                scheduleHideControls()
            } else if (FeedPlayer.exo?.playWhenReady == false) {
                // A real pause keeps the controls up. A transient
                // not-playing moment (seeking, buffering) must NOT
                // flash them — Phase 8.34: double-tap seeks used to
                // pop every icon on screen.
                showControls()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            updateBuffering()
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )
    }

    override fun onBindingCreated(binding: FragmentFeedWatchBinding) {
        goingToBatch = false
        FeedPlayer.warmUp(requireContext())
        binding.feedWatchPlayer.player = FeedPlayer.exo
        FeedPlayer.exo?.addListener(iconListener)
        FeedPlayer.addListener(stateListener)

        binding.feedWatchUpnext.layoutManager = GridLayoutManager(context, 2)
        binding.feedWatchUpnext.adapter = upNextAdapter
        setupTitleAutoHide(binding)

        val url = arguments?.getString("url") ?: return
        val apiName = arguments?.getString("apiName") ?: return
        val name = arguments?.getString("name") ?: url
        val poster = arguments?.getString("posterUrl")
        refreshUpNext(url)
        FeedPlayer.play(requireContext(), url, apiName, name, poster)

        // Single tap toggles controls; double tap skips ∓10 s; a long
        // downward drag minimises into the Feed's mini player.
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
        var downY = 0f
        var dragHandled = false
        binding.feedWatchPlayer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    dragHandled = false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragHandled && event.y - downY > 160f && FeedPlayer.isPlaying) {
                        dragHandled = true
                        minimizeToFeed()
                    }
                }
            }
            gestureDetector.onTouchEvent(event)
            true
        }

        // Phase 8.34: quick actions beside the title — source/dub,
        // episodes, download — the Premium Details pill row idea.
        binding.feedWatchSourceBtn.setOnClickListener { view ->
            view.hapticTick()
            showQuality()
        }
        binding.feedWatchEpisodesBtn.setOnClickListener { view ->
            view.hapticTick()
            showEpisodePicker()
        }
        binding.feedWatchDownloadBtn.setOnClickListener { view ->
            view.hapticTick()
            if (FeedPlayer.episodes.size > 1) {
                // Series: the batch page (resolution + audio
                // chips, per-episode multi-select).
                goingToBatch = true
                findNavController().navigate(R.id.navigation_feed_download)
            } else if (FeedPlayer.downloadCurrentEpisode()) {
                Toast.makeText(requireContext(), R.string.download_started, Toast.LENGTH_SHORT)
                    .show()
                updateDownloadPill()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            DownloadQueueManager.queue.collect { updateDownloadPill() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            VideoDownloadManager.currentDownloads.collect { updateDownloadPill() }
        }

        binding.feedWatchPlayPause.setOnClickListener { FeedPlayer.togglePlay() }
        binding.feedWatchFullscreen.setOnClickListener { view ->
            view.hapticTick()
            openInMainPlayer()
        }
        binding.feedWatchNext.setOnClickListener { view ->
            view.hapticTick()
            FeedPlayer.nextEpisode()
        }
        binding.feedWatchSubtitles.setOnClickListener { showSubtitlePicker() }
        binding.feedWatchAudio.setOnClickListener { showAudioPicker() }
        binding.feedWatchSeek.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val exo = FeedPlayer.exo ?: return
                    val duration = exo.duration.takeIf { it > 0 } ?: return
                    binding.feedWatchTimeCurrent.text = formatTime(duration * progress / 1000)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seeking = false
                val exo = FeedPlayer.exo ?: return
                val duration = exo.duration.takeIf { it > 0 } ?: return
                exo.seekTo(duration * (seekBar?.progress ?: 0) / 1000)
            }
        })
        binding.feedWatchRetry.setOnClickListener {
            context?.let { FeedPlayer.replayCurrent(it) }
        }
        binding.feedWatchPlayMain.setOnClickListener { openInMainPlayer() }
        binding.feedWatchOpenDetails.setOnClickListener {
            val current = FeedPlayer.currentUrl ?: return@setOnClickListener
            FeedPool.entries.firstOrNull { it.response.url == current }?.let { entry ->
                activity.loadSearchResult(entry.response)
            }
        }
        setupOrientationHandoff()
        updatePlaybackUi()
    }

    override fun onResume() {
        super.onResume()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        handler.post(progressRunnable)
        orientationListener?.enable()
        if (handedOff) {
            handedOff = false
            context?.let { FeedPlayer.replayCurrent(it) }
        }
    }

    override fun onPause() {
        FeedPlayer.savePosition()
        handler.removeCallbacks(progressRunnable)
        orientationListener?.disable()
        super.onPause()
    }

    override fun onDestroyView() {
        FeedPlayer.removeListener(stateListener)
        FeedPlayer.exo?.removeListener(iconListener)
        handler.removeCallbacksAndMessages(null)
        orientationListener?.disable()
        orientationListener = null
        binding?.feedWatchPlayer?.player = null
        // Leaving for good (not minimised, not handed off) stops playback.
        // Phase 8.38: the batch download page is a child of this
        // screen — keep the video (paused) so Back returns to the
        // same video at the same position.
        if (goingToBatch) {
            FeedPlayer.exo?.pause()
        } else if (!handedOff && !FeedPlayer.minimized) {
            FeedPlayer.stop()
        }
        // After a handoff the main player owns the orientation; only
        // restore it when this page is really being left behind.
        if (!handedOff) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
        }
        super.onDestroyView()
    }

    // ---------- UI state ----------

    private fun updatePlaybackUi() {
        val binding = binding ?: return
        val exo = FeedPlayer.exo
        when (FeedPlayer.status) {
            FeedPlayer.Status.LOADING -> {
                binding.feedWatchErrorBox.isVisible = false
                binding.feedWatchControls.isVisible = false
                // The poster carries the loading state; the (empty) video
                // surface stays hidden until the first frame is ready.
                binding.feedWatchPlayer.isVisible = false
                binding.feedWatchPoster.isVisible = FeedPlayer.currentPoster != null
                FeedPlayer.currentPoster?.let {
                    binding.feedWatchPoster.loadImage(it)
                }
                binding.feedWatchLoadingBox.isVisible = true
                // Phase 8.35: the "Finding sources" counter is
                // hidden by request — just the spinner.
                binding.feedWatchSourceCount.isVisible = false
            }

            FeedPlayer.Status.READY -> {
                binding.feedWatchLoadingBox.isVisible = false
                binding.feedWatchPoster.isVisible = false
                binding.feedWatchPlayer.isVisible = true
                binding.feedWatchErrorBox.isVisible = false
                // Phase 8.35: show the controls only when playback
                // (re)starts — this state callback also fires on
                // every seek/buffer churn, and re-showing the
                // controls there is what kept popping every icon
                // over the video on double-tap seeks.
                if (lastPlayerStatus != FeedPlayer.Status.READY) {
                    showControls()
                }
            }

            FeedPlayer.Status.ERROR -> {
                binding.feedWatchLoadingBox.isVisible = false
                binding.feedWatchPoster.isVisible = false
                binding.feedWatchPlayer.isVisible = false
                binding.feedWatchControls.isVisible = false
                binding.feedWatchErrorBox.isVisible = true
                binding.feedWatchPlayMain.isVisible = FeedPlayer.generator != null
            }

            FeedPlayer.Status.IDLE -> Unit
        }
        lastPlayerStatus = FeedPlayer.status
        updateDownloadPill()
        if (exo != null) {
            binding.feedWatchPlayPause.setImageResource(
                if (exo.isPlaying) R.drawable.netflix_pause else R.drawable.netflix_play
            )
        }
        binding.feedWatchNext.isVisible = FeedPlayer.hasNextEpisode
        binding.feedWatchEpisodesBtn.isVisible = FeedPlayer.episodes.size > 1
        updateHeader()
        val current = FeedPlayer.currentUrl
        if (current != null && current != lastShownUrl) {
            lastShownUrl = current
            refreshUpNext(current)
        }
    }

    private fun updateBuffering() {
        val binding = binding ?: return
        val exo = FeedPlayer.exo ?: return
        if (FeedPlayer.status == FeedPlayer.Status.READY &&
            exo.playbackState == Player.STATE_BUFFERING
        ) {
            binding.feedWatchLoadingBox.isVisible = true
        } else if (FeedPlayer.status == FeedPlayer.Status.READY) {
            binding.feedWatchLoadingBox.isVisible = false
        }
    }

    private fun updateHeader() {
        val binding = binding ?: return
        val name = FeedPlayer.currentName ?: return
        binding.feedWatchTitle.text = FeedAdapter.displayTitle(
            name, FeedPlayer.currentUrl ?: ""
        )
        val ep = FeedPlayer.currentEpisode
        val apiName = FeedPlayer.currentApiName ?: ""
        val typeText = ep?.let { FeedAdapter.typeLabel(it.tvType) } ?: ""
        val epText = if (ep != null && ep.episode > 0) {
            " • S${ep.season ?: 1} E${ep.episode}"
        } else {
            ""
        }
        binding.feedWatchMeta.text =
            listOf(FeedPlayer.currentApiName ?: apiName, typeText)
                .filter { it.isNotBlank() }.joinToString(" • ") + epText
    }

    private fun refreshUpNext(exceptUrl: String) {
        upNextAdapter.submitList(
            FeedPool.entries.filter { it.response.url != exceptUrl }
        )
    }

    // ---------- controls ----------

    private fun updateProgress() {
        val exo = FeedPlayer.exo ?: return
        val binding = binding ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: 0L
        if (!seeking && duration > 0) {
            binding.feedWatchSeek.progress =
                (exo.currentPosition * 1000 / duration).toInt()
        }
        binding.feedWatchTimeCurrent.text = formatTime(exo.currentPosition)
        binding.feedWatchTimeTotal.text = formatTime(duration)
        if (++tickCount % 10 == 0) FeedPlayer.savePosition()
    }

    private fun toggleControls() {
        val binding = binding ?: return
        if (FeedPlayer.status != FeedPlayer.Status.READY) return
        if (binding.feedWatchControls.isVisible) {
            binding.feedWatchControls.isVisible = false
            handler.removeCallbacks(hideControlsRunnable)
        } else {
            showControls()
        }
    }

    private fun showControls() {
        val binding = binding ?: return
        if (FeedPlayer.status != FeedPlayer.Status.READY) return
        binding.feedWatchControls.isVisible = true
        scheduleHideControls()
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (FeedPlayer.isPlaying) {
            handler.postDelayed(hideControlsRunnable, 3_000L)
        }
    }

    private fun skipBy(deltaMs: Long, back: Boolean) {
        val exo = FeedPlayer.exo ?: return
        val binding = binding ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0L, duration))
        binding.feedWatchPlayer.hapticTick()
        binding.feedWatchSkipBack.isVisible = back
        binding.feedWatchSkipForward.isVisible = !back
        handler.removeCallbacks(skipHideRunnable)
        handler.postDelayed(skipHideRunnable, 600L)
    }

    private fun minimizeToFeed() {
        FeedPlayer.minimized = true
        FeedPlayer.savePosition()
        binding?.feedWatchPlayer?.hapticTick()
        findNavController().popBackStack()
    }

    // ---------- pickers ----------

    /**
     * Phase 8.24 — the source list, like the main player's: one row
     * per link, but labelled compactly (resolution + audio language
     * or format) instead of the full release name the owner rejected
     * in 8.21. Languages on multi-audio sources are separate links,
     * so this list is also where the audio language is chosen.
     */
    private fun showQuality() {
        val context = context ?: return
        val links = FeedPlayer.links
        if (links.size < 2) return
        val labels = links.map { linkLabel(it) }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.feed_sources)
            .setSingleChoiceItems(labels, FeedPlayer.linkIndex) { dialog, which ->
                FeedPlayer.selectLink(which)
                dialog.dismiss()
            }
            .show()
    }

    private fun linkLabel(link: com.lagradost.cloudstream3.utils.ExtractorLink): String {
        val res = if (link.quality > 0) "${link.quality}p" else "Auto"
        val audio = Regex("\\(([^)]+)\\)").find(link.name)?.groupValues?.get(1)
        val kind = when (link.type) {
            com.lagradost.cloudstream3.utils.ExtractorLinkType.DASH -> "DASH"
            com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8 -> "HLS"
            else -> "MP4"
        }
        return if (audio != null) "$res • $audio • $kind" else "$res • $kind"
    }

    private data class TrackOption(
        val label: String,
        val group: Tracks.Group,
        val index: Int,
        val selected: Boolean
    )

    private fun trackOptions(type: Int): List<TrackOption> {
        val exo = FeedPlayer.exo ?: return emptyList()
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

    /** Phase 8.34: episode list for the title-row Episodes button. */
    /**
     * Phase 8.37: YouTube-style download affordance on the pill —
     * a spinning ring while the current episode is queued or
     * downloading, a check once it is downloaded.
     */
    private fun updateDownloadPill() {
        val binding = binding ?: return
        val epId = FeedPlayer.currentEpisode?.id
        val done = epId != null &&
            VideoDownloadManager.downloadStatus[epId] ==
            VideoDownloadManager.DownloadType.IsDone
        val active = epId != null && (
            VideoDownloadManager.currentDownloads.value.contains(epId) ||
                DownloadQueueManager.queue.value.any {
                    it.downloadItem?.episode?.id == epId
                }
            )
        binding.feedWatchDownloadProgress.isVisible = active && !done
        binding.feedWatchDownloadIcon.isVisible = !active || done
        binding.feedWatchDownloadIcon.setImageResource(
            if (done) R.drawable.ic_baseline_check_24
            else R.drawable.baseline_downloading_24
        )
    }

    private fun showEpisodePicker() {
        val context = context ?: return
        val episodes = FeedPlayer.episodes
        if (episodes.size < 2) return
        val labels = episodes.map { ep ->
            val num = if (ep.episode > 0) {
                "S${ep.season ?: 1} E${ep.episode}"
            } else {
                "Episode"
            }
            if (ep.name.isNullOrBlank()) num else "$num • ${ep.name}"
        }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.episodes)
            .setSingleChoiceItems(labels, FeedPlayer.episodeIndex) { dialog, which ->
                FeedPlayer.playEpisode(which)
                dialog.dismiss()
            }
            .show()
    }

    private fun showSubtitlePicker() {
        val context = context ?: return
        val exo = FeedPlayer.exo ?: return
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

    private fun linkLanguage(
        link: com.lagradost.cloudstream3.utils.ExtractorLink
    ): String? =
        Regex("""\(([^)]+)\)""").find(link.name)?.groupValues?.get(1)

    private fun showAudioPicker() {
        val context = context ?: return
        val exo = FeedPlayer.exo ?: return
        val options = trackOptions(TRACK_TYPE_AUDIO)
        if (options.size <= 1) {
            // Phase 8.37: the audio button is for LANGUAGE only —
            // it no longer opens the (quality) source list. On
            // multi-language sources the language is the link, so
            // offer the languages and switch to the best link of
            // the chosen one; quality stays under Sources.
            val languages = FeedPlayer.links
                .mapNotNull { linkLanguage(it) }
                .distinct()
            if (languages.size <= 1) {
                Toast.makeText(context, R.string.feed_single_audio, Toast.LENGTH_SHORT).show()
                return
            }
            val current = FeedPlayer.links
                .getOrNull(FeedPlayer.linkIndex)
                ?.let { linkLanguage(it) }
            AlertDialog.Builder(context)
                .setTitle(R.string.feed_audio)
                .setSingleChoiceItems(
                    languages.toTypedArray(),
                    languages.indexOf(current).coerceAtLeast(0)
                ) { dialog, which ->
                    val target = FeedPlayer.links.indexOfFirst {
                        linkLanguage(it) == languages[which]
                    }
                    if (target >= 0) FeedPlayer.selectLink(target)
                    dialog.dismiss()
                }
                .show()
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

    // ---------- handoff to the main player ----------

    private fun setupOrientationHandoff() {
        val context = context ?: return
        if (orientationListener != null) return
        orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                val isLandscape = orientation in 70..110 || orientation in 250..290
                if (isLandscape && FeedPlayer.isPlaying) {
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
        val gen = FeedPlayer.generator ?: return
        if (handedOff) return
        handedOff = true
        FeedPlayer.pause()
        // Hand over a generator whose episodes carry the LIVE saved
        // positions, exactly as GeneratorPlayer refreshes them itself —
        // the feed's copies were stamped when playback started.
        val page = FeedPlayer.currentPage
        val liveGen = if (page != null) {
            val refreshed = FeedPlayer.episodes.map { ep ->
                DataStoreHelper.getViewPos(ep.id)
                    ?.let { pd -> ep.copy(position = pd.position, duration = pd.duration) }
                    ?: ep
            }
            RepoLinkGenerator(refreshed, page = page)
        } else {
            gen
        }
        // Unlock into landscape BEFORE navigating, so the main player
        // never flashes its portrait layout on the way over.
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity.navigate(
            R.id.global_to_navigation_player,
            GeneratorPlayer.newInstance(liveGen, FeedPlayer.episodeIndex)
        )
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
