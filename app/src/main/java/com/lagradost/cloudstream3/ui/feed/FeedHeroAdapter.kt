package com.lagradost.cloudstream3.ui.feed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil3.request.crossfade
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FeedHeroPageBinding
import com.lagradost.cloudstream3.ui.search.TitleResolver
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.getImageFromDrawable

/**
 * Phase 8.85: pages of the compact Feed hero carousel (220dp, see
 * fragment_feed). One page = one feed entry: its poster fills the
 * card (centerCrop via the layout), with the title and the
 * provider • type line over the bottom scrim. A tap is exactly the
 * feed card's tap — direct play via [onPlay].
 */
class FeedHeroAdapter(
    private val onPlay: (FeedEntry) -> Unit
) : RecyclerView.Adapter<FeedHeroAdapter.HeroHolder>() {

    var items: List<FeedEntry> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    inner class HeroHolder(val binding: FeedHeroPageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: FeedEntry) {
            val context = binding.root.context
            val response = entry.response
            binding.feedHeroPoster.loadImage(response.posterUrl, response.posterHeaders) {
                crossfade(false)
                error { getImageFromDrawable(context, R.drawable.poster_placeholder) }
            }
            val override = TitleResolver.cached(context, response.url)
            val shownTitle = override
                ?: FeedAdapter.displayTitle(response.name, response.url)
            binding.feedHeroTitle.text =
                if (TitleResolver.isIdLike(shownTitle)) "" else shownTitle
            binding.feedHeroMeta.text = buildString {
                append(entry.apiName)
                append(" • ")
                append(FeedAdapter.typeLabel(response.type))
            }
            binding.root.setOnClickListener { onPlay(entry) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeroHolder {
        val binding = FeedHeroPageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return HeroHolder(binding)
    }

    override fun onBindViewHolder(holder: HeroHolder, position: Int) {
        if (items.isEmpty()) return
        holder.bind(items[position % items.size])
    }

    override fun getItemCount(): Int = items.size
}
