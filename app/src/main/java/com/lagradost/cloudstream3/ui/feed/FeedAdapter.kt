package com.lagradost.cloudstream3.ui.feed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FeedCardBinding
import com.lagradost.cloudstream3.ui.search.MetadataResolver
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

    /** Phase 8.56: universal ratings resolved by MetadataResolver,
     *  keyed by its title+year cache key. */
    private val ratingOverrides = mutableMapOf<String, String>()

    fun setRatingOverride(key: String, rating: String) {
        ratingOverrides[key] = rating
        val idx = currentList.indexOfFirst {
            MetadataResolver.keyFor(
                it.response.name, MetadataResolver.yearOf(it.response)
            ) == key
        }
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
            val shownTitle = override ?: displayTitle(response.name, response.url)
            // Phase 8.71: never print the raw placeholder ("Id 1260107139")
            // as a title — while resolution is pending (or failed) the
            // card shows its poster + meta only.
            binding.feedCardTitle.text =
                if (TitleResolver.isIdLike(shownTitle)) "" else shownTitle
            if (override == null && TitleResolver.isIdLike(response.name)) {
                onTitleNeeded(entry)
            }
            // Phase 8.56: meta line gains the rating (provider
            // score, else the universal TMDB/IMDb cache) and a
            // DUB/SUB label when the provider declares one.
            val ratingKey = MetadataResolver.keyFor(
                response.name, MetadataResolver.yearOf(response)
            )
            val rating = ratingOverrides[ratingKey]
                ?: MetadataResolver.cachedFor(context, response)
            if (rating == null && response.score == null) {
                MetadataResolver.resolve(context, response)
            }
            // dubStatus is declared on AnimeSearchResponse only.
            val declaredDub = (response as? AnimeSearchResponse)?.dubStatus
            val dubSub = when {
                declaredDub.isNullOrEmpty() -> null
                declaredDub!!.contains(DubStatus.Dubbed) &&
                    declaredDub.contains(DubStatus.Subbed) -> "DUB+SUB"
                declaredDub.contains(DubStatus.Dubbed) -> "DUB"
                declaredDub.contains(DubStatus.Subbed) -> "SUB"
                else -> null
            }
            // Phase 8.71: prefer the type learned from the details page
            // (TitleResolver cache) over the listing's declared type, so
            // the card tag matches the watch screen's tag.
            val shownType = TitleResolver.cachedType(context, response.url)
                ?: response.type
            binding.feedCardMeta.text = buildString {
                append(entry.apiName)
                append(" • ")
                append(typeLabel(shownType))
                if (rating != null) append(" • ★ ").append(rating)
                if (dubSub != null) append(" • ").append(dubSub)
            }
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
                // Phase 8.57: providers ship names ending in a
                // dangling separator ("Dear Boss ... -").
                .replace(Regex("[-\\u2013\\u2014|\\u2022\\u00B7:\\s]+$"), "")
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
    }
}
