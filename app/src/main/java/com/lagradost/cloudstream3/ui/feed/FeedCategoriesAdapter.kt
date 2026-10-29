package com.lagradost.cloudstream3.ui.feed

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FeedCategoriesHeaderBinding

/**
 * Phase 8.88: the Categories tile row as one item of the feed
 * grid's ConcatAdapter, between the hero header and the cards
 * (mirrors FeedHeroHeaderAdapter's shape). Tiles are smart
 * categories with at least a few resolved items in the pool —
 * plain dark tiles with the category name + item count, built
 * in code like the genre chips. Tapping a tile toggles the
 * feed's category filter through the fragment.
 */
class FeedCategoriesAdapter(
    private val onSelect: (String) -> Unit
) : RecyclerView.Adapter<FeedCategoriesAdapter.CategoriesHolder>() {

    private var tiles: List<Pair<String, Int>> = emptyList()
    private var selected: String? = null

    fun isShowing(): Boolean = tiles.isNotEmpty()

    override fun getItemCount(): Int = if (isShowing()) 1 else 0

    fun submit(newTiles: List<Pair<String, Int>>, newSelected: String?) {
        val wasShowing = isShowing()
        tiles = newTiles
        selected = newSelected
        if (isShowing() != wasShowing) {
            notifyDataSetChanged()
        } else if (isShowing()) {
            notifyItemChanged(0)
        }
    }

    inner class CategoriesHolder(val binding: FeedCategoriesHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(
        parent: ViewGroup, viewType: Int
    ): CategoriesHolder {
        val binding = FeedCategoriesHeaderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CategoriesHolder(binding)
    }

    override fun onBindViewHolder(holder: CategoriesHolder, position: Int) {
        val row = holder.binding.feedCategoriesRow
        val ctx = row.context
        val density = ctx.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        row.removeAllViews()
        tiles.forEach { (name, count) ->
            val tile = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                minimumWidth = dp(108)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setBackgroundResource(
                    if (name == selected) {
                        R.drawable.bg_feed_filter_selected
                    } else {
                        R.drawable.feed_search_bg
                    }
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(10) }
                setOnClickListener { onSelect(name) }
            }
            tile.addView(TextView(ctx).apply {
                text = name
                textSize = 14f
                setTextColor(Color.WHITE)
                setTypeface(
                    android.graphics.Typeface.create(
                        "sans-serif-medium",
                        android.graphics.Typeface.BOLD
                    )
                )
            })
            tile.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.feed_category_count, count)
                textSize = 11f
                setTextColor(Color.rgb(158, 158, 158))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            })
            row.addView(tile)
        }
    }
}
