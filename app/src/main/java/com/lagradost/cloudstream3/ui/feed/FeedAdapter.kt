package com.lagradost.cloudstream3.ui.feed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FeedCardBinding
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder.cleanPosterTitle
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
    private val onLikeChanged: (FeedEntry, Boolean) -> Unit = { _, _ -> }
) : ListAdapter<FeedEntry, FeedAdapter.ViewHolder>(Diff) {

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
            binding.feedCardTitle.text = cleanPosterTitle(response.name)
            binding.feedCardMeta.text = "${entry.apiName} • ${typeLabel(response.type)}"
            bindLike(entry)

            binding.root.setOnClickListener { onPlay(entry) }
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
        fun typeLabel(type: TvType): String = when (type) {
            TvType.Movie, TvType.AnimeMovie -> "Movie"
            TvType.TvSeries -> "Series"
            TvType.Anime, TvType.OVA -> "Anime"
            TvType.AsianDrama -> "Drama"
            TvType.Cartoon -> "Cartoon"
            TvType.Documentary -> "Documentary"
            TvType.Live -> "Live"
            else -> type.name
        }
    }
}
