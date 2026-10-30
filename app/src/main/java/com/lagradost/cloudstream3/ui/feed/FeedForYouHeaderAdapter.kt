package com.lagradost.cloudstream3.ui.feed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R

/**
 * Phase 8.97 — the "For You" section header as an ITEM of
 * the Feed's ConcatAdapter, sitting directly above the card
 * grid (the last adapter). Until 8.96 the header lived in
 * the floating page header (title bar + chips), while its
 * grid sat at the very bottom of the list, after every
 * category row: the header floated over whatever happened
 * to be at the top of the list, and in the loaded state a
 * tall blank band showed under it before the first row
 * (his 12:01/12:08 reports — "For You blank দেখায় + extra
 * গ্যাপ নেয়"). As a list item, header and grid are one
 * contiguous unit by construction: the header is in the
 * adapter chain only while the grid has content to show
 * (FeedFragment drives [setShowing] from the same submit
 * that feeds the grid), so no state — loading, loaded,
 * filtered, empty — can show the header over a void.
 */
class FeedForYouHeaderAdapter : RecyclerView.Adapter<FeedForYouHeaderAdapter.Holder>() {

    var showing: Boolean = false
        private set

    fun setShowing(value: Boolean) {
        if (showing == value) return
        showing = value
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = if (showing) 1 else 0

    class Holder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.feed_for_you_header, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = Unit
}
