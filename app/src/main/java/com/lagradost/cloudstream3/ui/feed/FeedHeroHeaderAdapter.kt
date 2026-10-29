package com.lagradost.cloudstream3.ui.feed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.databinding.FeedHeroHeaderBinding
import com.lagradost.cloudstream3.ui.home.HomeScrollTransformer
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.getImageFromDrawable

/**
 * Phase 8.87: the compact Feed hero as the grid's first list item
 * (ConcatAdapter position 0), replacing the 8.85 root-level overlay
 * box — see feed_hero_header.xml for why. This adapter owns the
 * pager, the settled-poster backdrop and the next-poster pre-warm
 * (all moved out of FeedFragment unchanged); the fragment only
 * submits entries + visibility and calls [advance] on its
 * auto-advance timer.
 */
class FeedHeroHeaderAdapter(
    private val pagesAdapter: FeedHeroAdapter
) : RecyclerView.Adapter<FeedHeroHeaderAdapter.HeroHeaderHolder>() {

    private var entries: List<FeedEntry> = emptyList()
    private var showing = false

    private var pagerRef: ViewPager2? = null
    private var backdropRef: android.widget.ImageView? = null

    /** True while the user is dragging the pager (auto-advance pauses). */
    var userDragging = false
        private set

    fun isShowing(): Boolean = showing && entries.isNotEmpty()

    override fun getItemCount(): Int = if (isShowing()) 1 else 0

    /**
     * New hero state from the fragment. Visibility flips re-create
     * the single item; entry changes refresh the pages and re-pin
     * the backdrop to the settled page, exactly as the 8.85 code in
     * FeedFragment.updateHeroItems did.
     */
    fun submit(newEntries: List<FeedEntry>, show: Boolean) {
        val wasShowing = isShowing()
        entries = newEntries
        showing = show
        pagesAdapter.items = newEntries
        if (isShowing() != wasShowing) {
            notifyDataSetChanged()
        } else if (isShowing()) {
            notifyItemChanged(0)
        }
        if (isShowing()) {
            val pager = pagerRef ?: return
            pager.post {
                val pos = pager.currentItem
                    .coerceIn(0, newEntries.size - 1)
                if (pager.currentItem != pos) {
                    pager.setCurrentItem(pos, false)
                }
                onSettled(pos)
            }
        }
    }

    /** One auto-advance step; a no-op unless it can actually move. */
    fun advance() {
        val pager = pagerRef ?: return
        if (!isShowing() || entries.size < 2 || userDragging) return
        pager.setCurrentItem((pager.currentItem + 1) % entries.size, true)
    }

    /**
     * A hero page settled: pin its poster into the backdrop under
     * the pager (the crossfade then never flashes black) and
     * pre-warm the next poster's Coil cache entry. Moved verbatim
     * from the 8.85 FeedFragment.onHeroSettled.
     */
    private fun onSettled(position: Int) {
        val backdrop = backdropRef ?: return
        val entry = entries.getOrNull(position) ?: return
        backdrop.loadImage(
            entry.response.posterUrl, entry.response.posterHeaders
        ) {
            crossfade(false)
            error {
                getImageFromDrawable(
                    backdrop.context, R.drawable.poster_placeholder
                )
            }
        }
        if (entries.size < 2) return
        val next = entries[(position + 1) % entries.size]
        val nextUrl = next.response.posterUrl ?: return
        val ctx = backdrop.context
        val request = ImageRequest.Builder(ctx)
            .data(nextUrl)
            .httpHeaders(NetworkHeaders.Builder().also { hb ->
                hb["User-Agent"] = USER_AGENT
                next.response.posterHeaders?.forEach { (key, value) ->
                    hb[key] = value
                }
            }.build())
            .build()
        SingletonImageLoader.get(ctx).enqueue(request)
    }

    inner class HeroHeaderHolder(val binding: FeedHeroHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeroHeaderHolder {
        val binding = FeedHeroHeaderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        val pager = binding.feedHeroPager
        pager.adapter = pagesAdapter
        // Same crossfade + pop-out transformer as the Home hero.
        pager.setPageTransformer(HomeScrollTransformer())
        // Phase 8.87: the transformer stacks every page at the same
        // slot (translationX cancelled), so containment is entirely
        // the ancestors' clipping. The XML sets clipChildren /
        // clipToPadding on the pager; the pager's own inner
        // RecyclerView (child 0) is only reachable from code — clip
        // it too. offscreenPageLimit stays at the effective default
        // of 1: only the settling pair is ever attached, and both
        // now stay inside the card.
        pager.offscreenPageLimit = 1
        (pager.getChildAt(0) as? RecyclerView)?.let { inner ->
            inner.clipChildren = true
            inner.clipToPadding = true
        }
        pager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    onSettled(position)
                }

                override fun onPageScrollStateChanged(state: Int) {
                    userDragging =
                        state == ViewPager2.SCROLL_STATE_DRAGGING
                }
            }
        )
        return HeroHeaderHolder(binding)
    }

    override fun onBindViewHolder(holder: HeroHeaderHolder, position: Int) {
        pagerRef = holder.binding.feedHeroPager
        backdropRef = holder.binding.feedHeroBackdrop
        val pos = holder.binding.feedHeroPager.currentItem
            .coerceIn(0, maxOf(entries.size - 1, 0))
        onSettled(pos)
    }
}
