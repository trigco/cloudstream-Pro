package com.lagradost.cloudstream3.ui.home

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import com.lagradost.cloudstream3.R
import kotlin.math.abs

class HomeScrollTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        //page.translationX = -position * page.width / 2.0f

        //val params = RecyclerView.LayoutParams(
        //    RecyclerView.LayoutParams.MATCH_PARENT,
        //    0
        //)
        //page.layoutParams = params
        //progressBar?.layoutParams = params

        val padding = (-position * page.width / 2).toInt()
        page.setPadding(
            padding, 0,
            -padding, 0
        )

        // Phase 8A: carousel depth for the PHONE hero only. Off-centre pages
        // shrink slightly and dim so the settled card reads as the hero and
        // the peeking neighbours (revealed by the pager's side padding in
        // fragment_home_head.xml) melt away under the edge masks.
        // Phone pages are detected by @id/home_scroll_card, which exists only
        // in home_scroll_view.xml — TV pages keep the exact pre-Phase-8
        // behaviour (no scale/alpha is ever applied to them).
        if (page.findViewById<View>(R.id.home_scroll_card) != null) {
            val distance = abs(position).coerceIn(0f, 1f)
            val scale = 1f - 0.08f * distance
            page.scaleX = scale
            page.scaleY = scale
            page.alpha = 1f - 0.45f * distance
        }
    }
}
