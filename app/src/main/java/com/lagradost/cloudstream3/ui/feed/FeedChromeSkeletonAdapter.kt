package com.lagradost.cloudstream3.ui.feed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.facebook.shimmer.ShimmerFrameLayout
import com.lagradost.cloudstream3.R

/**
 * Phase 8.96 — placeholder category rows in the Feed's
 * ConcatAdapter, shown from the first frame until the phase-1
 * chrome pass posts the real rows (see FeedFragment's two-phase
 * chrome). Each item renders feed_category_row_skeleton.xml —
 * a structural mirror of a real category row (same margins,
 * header paddings, poster size and text line heights), so the
 * skeleton → real swap does not shift anything vertically.
 * The shimmer is the app's existing facebook-shimmer treatment,
 * the same one the full-screen feed skeleton uses.
 */
class FeedChromeSkeletonAdapter : RecyclerView.Adapter<FeedChromeSkeletonAdapter.Holder>() {

    var showing: Boolean = false
        private set

    fun setShowing(value: Boolean) {
        if (showing == value) return
        showing = value
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = if (showing) ROWS else 0

    class Holder(val shimmer: ShimmerFrameLayout) : RecyclerView.ViewHolder(shimmer)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.feed_chrome_skeleton_row, parent, false)
                as ShimmerFrameLayout
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.shimmer.startShimmer()
    }

    override fun onViewRecycled(holder: Holder) {
        holder.shimmer.stopShimmer()
        super.onViewRecycled(holder)
    }

    private companion object {
        /** Mirrors the final page's opening: three category
         *  rows above the For You grid. */
        const val ROWS = 3
    }
}
