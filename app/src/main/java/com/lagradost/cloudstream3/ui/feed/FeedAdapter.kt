package com.lagradost.cloudstream3.ui.feed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FeedCardBinding
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder.cleanPosterTitle
import com.lagradost.cloudstream3.ui.search.TitleResolver
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.addPressShrink
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import com.lagradost.cloudstream3.utils.hapticTick

/**
 * Phase 8.18 — one feed card: a big portrait poster, the clean title and
 * provider/type line under it, and a heart on the side. The same adapter
 * drives the Feed tab and the watch page's "Up next" list.
 */
class FeedAdapter(
    private val onPlay: (FeedEntry) -> Unit,
    private val onLikeChanged: (FeedEntry, Boolean) -> Unit = { _, _ -> },
    private val onHide: (FeedEntry) -> Unit = {},
    private val onTitleNeeded: (FeedEntry) -> Unit = {}
) : ListAdapter<FeedEntry, FeedAdapter.ViewHolder>(Diff) {

    /** Real titles resolved in the background (TitleResolver). */
    private val titleOverrides = mutableMapOf<String, String>()

    fun setTitleOverride(url: String, title: String) {
        titleOverrides[url] = title
        val idx = currentList.indexOfFirst { it.response.url == url }
        if (idx >= 0) notifyItemChanged(idx)
    }

    object Diff : DiffUtil.ItemCallback<FeedEntry>() {
        override fun areItemsTheSame(oldItem: FeedEntry, newItem: FeedEntry): Boolean =
            oldItem.response.url == newItem.response.url

        override fun areContentsTheSame(oldItem: FeedEntry, newItem: FeedEntry): Boolean =
            oldItem.response.url == newItem.response.url &&
                oldItem.apiName == newItem.apiName
    }

    inner class ViewHolder(val binding: FeedCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: FeedEntry) {
            val context = binding.root.context
            val response = entry.response
            binding.feedCardPoster.loadImage(response.posterUrl, response.posterHeaders) {
                error { getImageFromDrawable(context, R.drawable.poster_placeholder) }
            }
            // Phase 8.33: prefer a resolved real title; when the
            // raw name is only a placeholder ("Id <number>"), ask
            // for a background resolution — the card swaps its
            // title in when it lands.
            val override = titleOverrides[response.url]
                ?: TitleResolver.cached(context, response.url)
            binding.feedCardTitle.text =
                override ?: displayTitle(response.name, response.url)
            if (override == null && TitleResolver.isIdLike(response.name)) {
                onTitleNeeded(entry)
            }
            binding.feedCardMeta.text = "${entry.apiName} • ${typeLabel(response.type)}"
            bindBadges(entry)
            bindLike(entry)

            binding.root.setOnClickListener { onPlay(entry) }
            // Phase 8.22: long-press offers to hide the item for good.
            binding.root.setOnLongClickListener {
                binding.root.hapticTick()
                onHide(entry)
                true
            }
            binding.feedCardLike.setOnClickListener { view ->
                val nowLiked = !FeedLikes.isLiked(context, entry.response.url)
                FeedLikes.setLiked(context, entry, nowLiked)
                view.hapticTick()
                bindLike(entry)
                onLikeChanged(entry, nowLiked)
            }
        }

        /** Phase 8.55: reference-style badges — rating, quality,
         *  type, language — each shown only when the data exists. */
        private fun bindBadges(entry: FeedEntry) {
            val response = entry.response
            val ratingText = response.score?.toStringNull(0.1, 10, 1)
            binding.feedCardRating.isVisible = !ratingText.isNullOrBlank()
            if (!ratingText.isNullOrBlank()) {
                binding.feedCardRating.text = "★ $ratingText"
            }
            val quality = qualityLabel(response.quality)
            binding.feedCardQuality.isVisible = quality != null
            if (quality != null) binding.feedCardQuality.text = quality
            binding.feedCardType.text =
                typeLabel(response.type).uppercase()
            val lang = languageBadge(response.name)
            binding.feedCardLang.isVisible = lang != null
            if (lang != null) binding.feedCardLang.text = lang
        }

        private fun bindLike(entry: FeedEntry) {
            val context = binding.root.context
            val liked = FeedLikes.isLiked(context, entry.response.url)
            binding.feedCardLike.setImageResource(
                if (liked) R.drawable.ic_baseline_favorite_24
                else R.drawable.ic_baseline_favorite_border_24
            )
            binding.feedCardLike.imageTintList = ContextCompat.getColorStateList(
                context,
                if (liked) R.color.colorPrimaryRed else R.color.white
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = FeedCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        // Every card answers a touch, like the home posters do (Phase 8.16).
        binding.root.addPressShrink()
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        /**
         * The title a card/header shows. Some providers hand back a
         * blank (or all-decoration) name — fall back to a readable
         * slug from the URL so the title slot is never empty.
         */
        fun displayTitle(name: String, url: String): String {
            val cleaned = cleanPosterTitle(name)
            if (cleaned.isNotBlank()) return cleaned
            val slug = url.substringBefore('?').trimEnd('/')
                .substringAfterLast('/')
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
            return slug.replaceFirstChar { it.uppercase() }.ifBlank { "Untitled" }
        }

        fun typeLabel(type: TvType?): String = when (type) {
            TvType.Movie, TvType.AnimeMovie -> "Movie"
            TvType.TvSeries -> "Series"
            TvType.Anime, TvType.OVA -> "Anime"
            TvType.AsianDrama -> "Drama"
            TvType.Cartoon -> "Cartoon"
            TvType.Documentary -> "Documentary"
            TvType.Live -> "Live"
            else -> type?.name ?: ""
        }

        /** Phase 8.55: short quality label for the card badge. */
        fun qualityLabel(quality: SearchQuality?): String? = when (quality) {
            SearchQuality.BlueRay -> "BLURAY"
            SearchQuality.Cam -> "CAM"
            SearchQuality.CamRip -> "CAMRIP"
            SearchQuality.DVD -> "DVD"
            SearchQuality.HD -> "HD"
            SearchQuality.HQ -> "HQ"
            SearchQuality.HdCam -> "HDCAM"
            SearchQuality.Telecine -> "TC"
            SearchQuality.Telesync -> "TS"
            SearchQuality.WorkPrint -> "WP"
            SearchQuality.SD -> "SD"
            SearchQuality.FourK -> "4K"
            SearchQuality.UHD -> "UHD"
            SearchQuality.SDR -> "SDR"
            SearchQuality.HDR -> "HDR"
            SearchQuality.WebRip -> "WEBRIP"
            null -> null
        }

        private val BADGE_LANGUAGES = listOf(
            "hindi", "bengali", "bangla", "tamil", "telugu",
            "malayalam", "kannada", "punjabi", "english", "korean",
            "japanese", "chinese", "urdu", "marathi", "gujarati",
            "spanish", "french", "german", "russian", "turkish",
            "thai", "arabic"
        )

        /**
         * Phase 8.55: language badge parsed from the raw title,
         * the way his reference cards tag it ("DUAL AUDIO",
         * "HINDI", "BANGLA"…). Dual audio wins; otherwise the
         * first language named in the title.
         */
        fun languageBadge(name: String): String? {
            val lower = name.lowercase()
            if (lower.contains("dual audio")) return "DUAL AUDIO"
            val found = BADGE_LANGUAGES.firstOrNull { lower.contains(it) }
            return found?.uppercase()
        }
    }
}
