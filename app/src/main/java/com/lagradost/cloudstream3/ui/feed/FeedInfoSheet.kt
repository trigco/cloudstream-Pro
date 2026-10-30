package com.lagradost.cloudstream3.ui.feed

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TrailerData
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.databinding.FilmoItemBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.result.ActorFragment
import com.lagradost.cloudstream3.ui.result.initialsForActor
import com.lagradost.cloudstream3.ui.search.goldStarText
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SheetChrome
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 8.93: the Feed watch screen's Info, rebuilt as the rich
 * "More details" sheet he pointed at in the reference app: poster
 * + meta header, genres, subtitle count, description, a Trailer
 * button (only when the provider actually ships a trailer) that
 * plays inside the sheet, and a Starring cast grid whose taps open
 * the in-app actor page (see ActorFilmography — no Wikipedia).
 *
 * Data: the already-loaded page is reused when the sheet is opened
 * for the item the player is showing; otherwise the page is
 * fetched through the same provider load path the player uses
 * (APIRepository.load), off the main thread, with a loading state.
 */
@UnstableApi
object FeedInfoSheet {

    private const val MAX_CAST_SHOWN = 18

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    fun show(
        activity: Activity,
        url: String,
        apiName: String,
        fallbackName: String,
        fallbackPoster: String?,
        onPlay: () -> Unit,
        onOpenDetails: () -> Unit,
    ) {
        val ctx = activity
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        var trailerPlayer: ExoPlayer? = null

        val dialog = BottomSheetDialog(ctx, R.style.AppBottomSheetDialogTheme)
        val root = SheetChrome.sheetRoot(ctx)
        root.addView(SheetChrome.dragHandle(ctx))
        val (titleRow, closeBtn) = SheetChrome.titleRow(ctx, fallbackName)
        val titleView = titleRow.getChildAt(0) as TextView
        closeBtn.setOnClickListener { dialog.dismiss() }
        root.addView(titleRow)

        // --- scrollable content ---
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = androidx.core.widget.NestedScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (ctx.resources.displayMetrics.heightPixels * 0.58f).toInt()
            )
            addView(content)
        }
        root.addView(scroll)

        val loadingRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 18), 0, dp(ctx, 18))
            addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall))
            addView(TextView(ctx).apply {
                text = ctx.getString(R.string.loading)
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(ctx, 12), 0, 0, 0)
            })
        }
        content.addView(loadingRow)

        val errorText = TextView(ctx).apply {
            setText(R.string.feed_info_load_failed)
            setTextColor(ContextCompat.getColor(ctx, R.color.grayTextColor))
            textSize = 13.5f
            isGone = true
            setPadding(0, dp(ctx, 14), 0, dp(ctx, 6))
        }
        content.addView(errorText)

        // Header: poster + meta column.
        val posterView = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 62), dp(ctx, 93))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.feed_skeleton_poster)
        }
        val metaView = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setLineSpacing(0f, 1.3f)
        }
        val genreView = TextView(ctx).apply {
            setTextColor(ContextCompat.getColor(ctx, R.color.grayTextColor))
            textSize = 12f
            setPadding(0, dp(ctx, 4), 0, 0)
            isGone = true
        }
        val subsView = TextView(ctx).apply {
            setTextColor(ContextCompat.getColor(ctx, R.color.grayTextColor))
            textSize = 12f
            setPadding(0, dp(ctx, 2), 0, 0)
            isGone = true
        }
        val metaColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            setPadding(dp(ctx, 12), 0, 0, 0)
            addView(metaView)
            addView(genreView)
            addView(subsView)
        }
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            isGone = true
            setPadding(0, dp(ctx, 6), 0, 0)
            addView(posterView)
            addView(metaColumn)
        }
        content.addView(headerRow)

        // Trailer: button first; tapping swaps in an inline player.
        val trailerBox = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0
            )
            isGone = true
        }
        content.addView(trailerBox)
        val trailerButton = SheetChrome.secondaryButton(
            ctx, R.string.play_trailer_button
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 48)
            ).apply { topMargin = dp(ctx, 14) }
            isGone = true
        }
        content.addView(trailerButton)

        val descHeader = sectionHeader(ctx, R.string.feed_info_description)
        content.addView(descHeader)
        val descText = TextView(ctx).apply {
            setTextColor(Color.parseColor("#FFCCCCCC"))
            textSize = 13.5f
            setLineSpacing(0f, 1.3f)
            isGone = true
        }
        content.addView(descText)

        val castHeader = sectionHeader(ctx, R.string.feed_info_starring)
        content.addView(castHeader)
        val castGrid = RecyclerView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, 3)
            isNestedScrollingEnabled = false
            isGone = true
            setPadding(0, dp(ctx, 4), 0, 0)
        }
        content.addView(castGrid)

        // --- actions (pinned under the scroll area) ---
        val detailsButton = SheetChrome.secondaryButton(
            ctx, R.string.feed_info_open_details
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 48)
            ).apply { topMargin = dp(ctx, 14) }
        }
        detailsButton.setOnClickListener {
            dialog.dismiss()
            onOpenDetails()
        }
        root.addView(detailsButton)
        val playButton = SheetChrome.primaryButton(ctx, R.string.feed_info_play)
        playButton.setOnClickListener {
            dialog.dismiss()
            onPlay()
        }
        root.addView(playButton)

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            trailerPlayer?.release()
            trailerPlayer = null
            scope.cancel()
        }
        dialog.show()
        SheetChrome.transparentSheetFrame(dialog)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.maxHeight =
            (ctx.resources.displayMetrics.heightPixels * 0.88f).toInt()

        fun populate(page: LoadResponse?) {
            loadingRow.isGone = true
            if (page == null) {
                errorText.isVisible = true
                headerRow.isVisible = true
                if (!fallbackPoster.isNullOrBlank()) {
                    posterView.loadImage(fallbackPoster)
                }
                metaView.text = apiName
                return
            }
            // Prefer the loaded page's real title when the feed item
            // only carried a placeholder (same rule as the player).
            if (fallbackName.isBlank() || fallbackName.all { it.isDigit() }) {
                if (page.name.isNotBlank()) titleView.text = page.name
            }
            headerRow.isVisible = true
            val poster = page.posterUrl ?: fallbackPoster
            if (!poster.isNullOrBlank()) {
                posterView.loadImage(poster, page.posterHeaders)
            }
            val rating = page.score?.toStringNull(0.5, 10)
            val durationText = page.duration?.takeIf { it > 0 }?.let { minutes ->
                if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m"
                else "${minutes}m"
            }
            metaView.text = goldStarText(
                listOfNotNull(
                    page.apiName.takeIf { it.isNotBlank() },
                    FeedAdapter.typeLabel(page.type).takeIf { it.isNotBlank() },
                    rating?.let { "★ $it" },
                    page.year?.toString(),
                    durationText,
                    page.contentRating?.takeIf { it.isNotBlank() },
                ).joinToString(" • ")
            )
            val tags = page.tags?.filter { it.isNotBlank() }?.take(6).orEmpty()
            if (tags.isNotEmpty()) {
                genreView.text = tags.joinToString(" • ")
                genreView.isVisible = true
            }
            // Subtitle count only exists for the item the player
            // actually loaded (FeedPlayer.subs) — no count is shown
            // rather than a made-up one.
            if (FeedPlayer.currentUrl == url && FeedPlayer.subs.isNotEmpty()) {
                subsView.text = ctx.getString(
                    R.string.feed_info_subtitles, FeedPlayer.subs.size
                )
                subsView.isVisible = true
            }
            val trailer = page.trailers.firstOrNull()
            if (trailer != null) {
                trailerButton.isVisible = true
                trailerButton.setOnClickListener {
                    trailerButton.isGone = true
                    playTrailer(ctx, trailerBox, trailer, scope) { player ->
                        trailerPlayer = player
                    }
                }
            }
            val plot = page.plot?.takeIf { it.isNotBlank() }
            descHeader.isVisible = true
            descText.isVisible = true
            if (plot != null) {
                descText.text = plot
            } else {
                descText.setText(R.string.feed_info_no_description)
                descText.setTextColor(
                    ContextCompat.getColor(ctx, R.color.grayTextColor)
                )
            }
            val cast = page.actors?.filter {
                it.actor.name.isNotBlank()
            }?.take(MAX_CAST_SHOWN).orEmpty()
            if (cast.isNotEmpty()) {
                castHeader.isVisible = true
                castGrid.isVisible = true
                castGrid.adapter = SheetCastAdapter(cast) { actorData ->
                    dialog.dismiss()
                    ActorFragment.openActor(activity, actorData, page.name)
                }
            }
        }

        val readyPage = FeedPlayer.currentPage
            ?.takeIf { FeedPlayer.currentUrl == url }
        if (readyPage != null) {
            populate(readyPage)
        } else {
            scope.launch {
                populate(loadViaProvider(url, apiName))
            }
        }
    }

    /** The player's own load path, minus playback. */
    private suspend fun loadViaProvider(
        url: String,
        apiName: String,
    ): LoadResponse? {
        val api = APIHolder.getApiFromNameNull(apiName) ?: return null
        return try {
            when (
                val res = withContext(Dispatchers.IO) {
                    APIRepository(api).load(url)
                }
            ) {
                is Resource.Success -> res.value
                else -> null
            }
        } catch (e: Throwable) {
            logError(e)
            null
        }
    }

    /**
     * Extracts and plays the trailer in a small player inside the
     * sheet. The feed video behind the sheet is paused first so the
     * two never talk over each other; it is NOT auto-resumed on
     * dismiss (the user taps play when they want it back).
     */
    private fun playTrailer(
        ctx: Context,
        box: FrameLayout,
        trailer: TrailerData,
        scope: CoroutineScope,
        onPlayer: (ExoPlayer) -> Unit,
    ) {
        FeedPlayer.exo?.playWhenReady = false
        box.layoutParams = (box.layoutParams as LinearLayout.LayoutParams).apply {
            height = dp(ctx, 190)
            topMargin = dp(ctx, 14)
        }
        box.isVisible = true
        val playerView = PlayerView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }
        box.removeAllViews()
        box.addView(playerView)
        scope.launch {
            val link = extractTrailerLink(trailer)
            if (link == null) {
                box.isGone = true
                return@launch
            }
            val exo = ExoPlayer.Builder(ctx).build()
            onPlayer(exo)
            playerView.player = exo
            val userAgent = link.headers.entries.find {
                it.key.equals("User-Agent", ignoreCase = true)
            }?.value ?: com.lagradost.cloudstream3.USER_AGENT
            val dataSourceFactory = OkHttpDataSource.Factory(app.baseClient)
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(
                    (if (link.referer.isBlank()) emptyMap()
                    else mapOf("referer" to link.referer)) + link.headers
                )
            val mediaItem = MediaItem.Builder()
                .setUri(link.url)
                .setMimeType(
                    when (link.type) {
                        ExtractorLinkType.M3U8 -> MimeTypes.APPLICATION_M3U8
                        ExtractorLinkType.DASH -> MimeTypes.APPLICATION_MPD
                        else -> null
                    }
                )
                .build()
            exo.setMediaSource(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    dataSourceFactory
                ).createMediaSource(mediaItem)
            )
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    /** Mirrors ResultViewModel2.getTrailers' extraction for one trailer. */
    private suspend fun extractTrailerLink(trailer: TrailerData): ExtractorLink? {
        val links = mutableListOf<ExtractorLink>()
        val extracted = try {
            loadExtractor(
                trailer.extractorUrl,
                trailer.referer,
                { },
                { links.add(it) }
            )
        } catch (e: Throwable) {
            logError(e)
            false
        }
        if (links.isNotEmpty()) {
            return links.maxByOrNull { it.quality }
        }
        if (!extracted && trailer.raw) {
            return try {
                newExtractorLink(
                    "",
                    "Trailer",
                    trailer.extractorUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = trailer.referer ?: ""
                    this.quality = Qualities.Unknown.value
                    this.headers = trailer.headers
                }
            } catch (e: Throwable) {
                logError(e)
                null
            }
        }
        return null
    }

    private fun sectionHeader(ctx: Context, textRes: Int): TextView {
        return TextView(ctx).apply {
            setText(textRes)
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(ctx, 18), 0, dp(ctx, 6))
            isGone = true
        }
    }

    /** Cast cells reuse the filmography cell layout (poster, name,
     *  character line) so both grids read the same. */
    private class SheetCastAdapter(
        private val items: List<ActorData>,
        private val onClick: (ActorData) -> Unit,
    ) : RecyclerView.Adapter<SheetCastAdapter.CastHolder>() {

        inner class CastHolder(val binding: FilmoItemBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): CastHolder {
            return CastHolder(
                FilmoItemBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
        }

        override fun onBindViewHolder(holder: CastHolder, position: Int) {
            val item = items[position]
            val binding = holder.binding
            binding.filmoTitle.text = item.actor.name
            binding.filmoSub.text = item.roleString?.takeIf { it.isNotBlank() } ?: ""
            binding.filmoInitials.text = initialsForActor(item.actor.name)
            val image = item.actor.image
            if (image.isNullOrBlank()) {
                binding.filmoPoster.setImageDrawable(null)
            } else {
                binding.filmoPoster.loadImage(image)
            }
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
