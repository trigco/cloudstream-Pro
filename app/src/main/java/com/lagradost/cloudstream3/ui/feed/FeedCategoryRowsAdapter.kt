package com.lagradost.cloudstream3.ui.feed

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FeedCategoryRowBinding
import com.lagradost.cloudstream3.databinding.FeedRowItemBinding
import com.lagradost.cloudstream3.ui.search.TitleResolver
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.getImageFromDrawable

/**
 * Phase 8.90 — the Feed's category sections as ConcatAdapter
 * items between the hero header and the For You grid, replacing
 * the 8.88 tile row (his verdict: the tiles were effectively
 * invisible — "ক্যাটাগরি কই make হইছে?"). Each row is a
 * Home-style section (8.84 header: title + red underline +
 * "View All ›" pill) over a horizontal poster strip, built
 * from the tag vocabulary he listed: Trending, Top 250, the
 * region categories, genres and Emotional — computed by
 * FeedFragment from the pool. "View All ›" (or the title)
 * applies that tag as the grid's filter and scrolls to the
 * grid; a poster tap plays directly, like a grid card.
 */
class FeedCategoryRowsAdapter(
    private val onPlay: (FeedEntry) -> Unit,
    private val onViewAll: (String) -> Unit
) : RecyclerView.Adapter<FeedCategoryRowsAdapter.RowHolder>() {

    /** [name] is the filter tag; [title] is what the header
     *  shows (curated rows carry the reference's emoji
     *  decoration, e.g. "🔥 Top K-Drama"). */
    data class CategoryRow(
        val name: String,
        val title: String,
        val items: List<FeedEntry>
    )

    private var rows: List<CategoryRow> = emptyList()

    fun submit(newRows: List<CategoryRow>) {
        // Phase 8.92: an identical resubmit (the background
        // chrome pass re-derives the same rows after a metadata
        // landing that changed nothing visible) must not
        // relayout the whole header block.
        if (rows == newRows) return
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    inner class RowHolder(val binding: FeedCategoryRowBinding) :
        RecyclerView.ViewHolder(binding.root) {
        val itemsAdapter = RowItemsAdapter(onPlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val binding = FeedCategoryRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        val holder = RowHolder(binding)
        binding.feedRowRecycler.layoutManager = LinearLayoutManager(
            parent.context, LinearLayoutManager.HORIZONTAL, false
        )
        binding.feedRowRecycler.adapter = holder.itemsAdapter
        binding.feedRowRecycler.setHasFixedSize(true)
        binding.feedRowRecycler.itemAnimator = null
        return holder
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val row = rows[position]
        holder.binding.feedRowTitle.text = row.title
        val open = View.OnClickListener { onViewAll(row.name) }
        holder.binding.feedRowViewAll.setOnClickListener(open)
        holder.binding.feedRowTitle.setOnClickListener(open)
        holder.itemsAdapter.submit(row.items)
        holder.binding.feedRowRecycler.scrollToPosition(0)
    }

    /** The horizontal poster strip inside one category row. */
    class RowItemsAdapter(
        private val onPlay: (FeedEntry) -> Unit
    ) : RecyclerView.Adapter<RowItemsAdapter.ItemHolder>() {

        private var items: List<FeedEntry> = emptyList()

        fun submit(newItems: List<FeedEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        inner class ItemHolder(val binding: FeedRowItemBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(
            parent: ViewGroup, viewType: Int
        ): ItemHolder {
            val binding = FeedRowItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ItemHolder(binding)
        }

        override fun onBindViewHolder(holder: ItemHolder, position: Int) {
            val entry = items[position]
            val context = holder.binding.root.context
            val response = entry.response
            holder.binding.feedRowPoster.loadImage(
                response.posterUrl, response.posterHeaders
            ) {
                error {
                    getImageFromDrawable(context, R.drawable.poster_placeholder)
                }
            }
            val override = TitleResolver.cached(context, response.url)
            val shownTitle = override
                ?: FeedAdapter.displayTitle(response.name, response.url)
            holder.binding.feedRowItemTitle.text =
                if (TitleResolver.isIdLike(shownTitle)) "" else shownTitle
            // Same type source as the grid cards (Phase 8.71).
            val shownType = TitleResolver.cachedType(context, response.url)
                ?: response.type
            holder.binding.feedRowItemMeta.text = buildString {
                append(entry.apiName)
                append(" • ")
                append(FeedAdapter.typeLabel(shownType))
            }
            holder.binding.root.setOnClickListener { onPlay(entry) }
        }
    }
}
