package com.lagradost.cloudstream3.ui.feed

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil3.request.crossfade
import coil3.size.Size
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FeedHeroPageBinding
import com.lagradost.cloudstream3.ui.search.TitleResolver
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.getImageFromDrawable

/**
 * Phase 8.85: pages of the compact Feed hero carousel (220dp, see
 * fragment_feed). One page = one feed entry, with the title and
 * the provider • type line over the bottom scrim. A tap is
 * exactly the feed card's tap — direct play via [onPlay].
 * Phase 8.90: the page no longer center-crops the portrait
 * poster into the landscape frame (his screenshot: faces
 * sliced, artwork zoomed past recognition). SearchResponse
 * carries no landscape backdrop (only LoadResponse has
 * backgroundPosterUrl), so the page uses the two-layer
 * treatment: the poster heavily downsampled + darkened (plus
 * a real RenderEffect blur on API 31+) fills the frame, and
 * the sharp poster sits contained (fitCenter) on top.
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
            // Phase 8.90: backdrop layer — the same poster at a
            // tiny decode size (48x27), so upscaling it across
            // the frame reads as a soft blur on every API level;
            // API 31+ gets a genuine RenderEffect blur on top.
            // The layout darkens it (alpha + black overlay).
            binding.feedHeroBackdropImg.loadImage(
                response.posterUrl, response.posterHeaders
            ) {
                crossfade(false)
                size(Size(48, 27))
                error { getImageFromDrawable(context, R.drawable.poster_placeholder) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                binding.feedHeroBackdropImg.setRenderEffect(
                    RenderEffect.createBlurEffect(
                        16f, 16f, Shader.TileMode.CLAMP
                    )
                )
            }
            binding.feedHeroPoster.loadImage(response.posterUrl, response.posterHeaders) {
                crossfade(false)
                error { getImageFromDrawable(context, R.drawable.poster_placeholder) }
            }
            val override = TitleResolver.cached(context, response.url)
            val shownTitle = override
                ?: FeedAdapter.displayTitle(response.name, response.url)
            binding.feedHeroTitle.text =
                if (TitleResolver.isIdLike(shownTitle)) "" else shownTitle
            // Phase 8.90: the SAME type source as the grid cards
            // (Phase 8.71) — the details-page type learned by
            // TitleResolver wins over the listing's declared
            // type. The hero used to read response.type only,
            // so it said "Anime" where the card said "Movie".
            val shownType = TitleResolver.cachedType(context, response.url)
                ?: response.type
            binding.feedHeroMeta.text = buildString {
                append(entry.apiName)
                append(" • ")
                append(FeedAdapter.typeLabel(shownType))
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
