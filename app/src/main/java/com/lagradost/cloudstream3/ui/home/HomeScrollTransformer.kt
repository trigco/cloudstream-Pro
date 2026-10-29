package com.lagradost.cloudstream3.ui.home

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * Phase 8.84: the hero pager's transformer is now a pure CROSSFADE
 * (the old padding-based slide was disliked). Each page's layout
 * offset is cancelled with translationX so pages stack in place
 * instead of travelling horizontally, and alpha follows the scroll
 * position: the settled page is fully opaque, its neighbours fade
 * out completely by one page-width away. Padding is explicitly
 * zeroed because pages are recycled and the pre-8.84 transformer
 * wrote padding into them.
 */
class HomeScrollTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        page.setPadding(0, 0, 0, 0)
        page.translationX = -position * page.width
        page.alpha = (1f - abs(position)).coerceIn(0f, 1f)
    }
}
