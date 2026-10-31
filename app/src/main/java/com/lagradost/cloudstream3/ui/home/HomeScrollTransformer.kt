package com.lagradost.cloudstream3.ui.home

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * Phase 8.84: the hero pager's transformer is a pure CROSSFADE
 * (the old padding-based slide was disliked). Each page's layout
 * offset is cancelled with translationX so pages stack in place
 * instead of travelling horizontally, and alpha follows the scroll
 * position: the settled page is fully opaque, its neighbours fade
 * out completely by one page-width away. Padding is explicitly
 * zeroed because pages are recycled and the pre-8.84 transformer
 * wrote padding into them.
 *
 * Phase 8.85: a gentle POP-OUT joins the fade — the settling page
 * scales 0.96 -> 1.0 as |position| -> 0 and the outgoing page
 * shrinks back, so a page change reads as the new poster blooming
 * into place rather than two flat images dissolving. Smooth (the
 * scale is a pure function of scroll position), no bounce. Shared
 * by the Home hero and the compact Feed hero. The black flash the
 * fade used to show is covered by a settled-poster backdrop view
 * under each pager (see fragment_home_head / fragment_feed).
 */
class HomeScrollTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        page.setPadding(0, 0, 0, 0)
        page.translationX = -position * page.width
        val closeness = (1f - abs(position)).coerceIn(0f, 1f)
        page.alpha = closeness
        val scale = 0.96f + 0.04f * closeness
        page.scaleX = scale
        page.scaleY = scale
    }
}
