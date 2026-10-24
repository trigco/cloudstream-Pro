package com.lagradost.cloudstream3.ui.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.databinding.PlayerPortraitRecItemBinding
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage

/**
 * Phase 8.14c: horizontal poster row for the portrait player's
 * "More like this" section.
 */
class PortraitRecsAdapter(
    private val items: List<SearchResponse>,
    private val onClick: (SearchResponse) -> Unit
) : RecyclerView.Adapter<PortraitRecsAdapter.Holder>() {

    class Holder(val binding: PlayerPortraitRecItemBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = PlayerPortraitRecItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        with(holder.binding) {
            recPoster.loadImage(item.posterUrl)
            recTitle.text = item.name
            recProvider.text = item.apiName
            root.setOnClickListener { onClick(item) }
        }
    }

    override fun getItemCount(): Int = items.size
}
