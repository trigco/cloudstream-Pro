package com.lagradost.cloudstream3.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentHomeCustomListEditBinding
import com.lagradost.cloudstream3.databinding.HomeCustomCategoryRowBinding
import com.lagradost.cloudstream3.databinding.HomeCustomProviderHeaderBinding
import com.lagradost.cloudstream3.databinding.HomeCustomSelectedRowBinding
import java.util.Collections

/** Phase 8.44: build/edit one custom home list. */
class HomeCustomListEditFragment : Fragment() {

    private var binding: FragmentHomeCustomListEditBinding? = null
    private var listId: String? = null
    private val selected = mutableListOf<HomeCustomLists.Entry>()
    private lateinit var selectedAdapter: SelectedAdapter
    private lateinit var allAdapter: AllAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentHomeCustomListEditBinding.inflate(
            inflater, container, false
        )
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return
        listId = arguments?.getString("listId")
        val ctx = requireContext()

        listId?.let { id ->
            HomeCustomLists.getLists(ctx).firstOrNull { it.id == id }
                ?.let { existing ->
                    b.customEditName.setText(existing.name)
                    selected += existing.entries
                }
        }

        selectedAdapter = SelectedAdapter(selected) { entry ->
            selected.remove(entry)
            selectedAdapter.notifyDataSetChanged()
            allAdapter.notifyDataSetChanged()
        }
        b.customEditSelected.layoutManager = LinearLayoutManager(ctx)
        b.customEditSelected.adapter = selectedAdapter
        ItemTouchHelper(object :
            ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
            ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                Collections.swap(selected, from, to)
                selectedAdapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(
                viewHolder: RecyclerView.ViewHolder, direction: Int
            ) = Unit
        }).attachToRecyclerView(b.customEditSelected)

        allAdapter = AllAdapter(
            groups = HomeCustomLists.allProviderCategories(),
            isAdded = { api, page ->
                selected.any { it.apiName == api && it.pageName == page }
            },
            onAdd = { api, page ->
                selected += HomeCustomLists.Entry(api, page)
                selectedAdapter.notifyDataSetChanged()
                allAdapter.notifyDataSetChanged()
            }
        )
        b.customEditAll.layoutManager = LinearLayoutManager(ctx)
        b.customEditAll.adapter = allAdapter

        b.customEditSave.setOnClickListener {
            val name = b.customEditName.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                Toast.makeText(
                    ctx, R.string.home_custom_need_name,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            if (selected.isEmpty()) {
                Toast.makeText(
                    ctx, R.string.home_custom_need_category,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            HomeCustomLists.upsert(
                ctx,
                HomeCustomLists.CustomList(
                    id = listId ?: HomeCustomLists.newId(),
                    name = name,
                    entries = selected.toList()
                )
            )
            Toast.makeText(
                ctx, R.string.home_custom_saved, Toast.LENGTH_SHORT
            ).show()
            findNavController().navigateUp()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private class SelectedAdapter(
        private val items: MutableList<HomeCustomLists.Entry>,
        private val onRemove: (HomeCustomLists.Entry) -> Unit
    ) : RecyclerView.Adapter<SelectedAdapter.Holder>() {

        class Holder(val b: HomeCustomSelectedRowBinding) :
            RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(
            parent: ViewGroup, viewType: Int
        ): Holder = Holder(
            HomeCustomSelectedRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.b.customSelName.text = entry.pageName
            holder.b.customSelApi.text = entry.apiName
            holder.b.customSelRemove.setOnClickListener { onRemove(entry) }
        }
    }

    private sealed class Row {
        data class Header(val apiName: String) : Row()
        data class Category(val apiName: String, val pageName: String) : Row()
    }

    private class AllAdapter(
        groups: List<Pair<String, List<String>>>,
        private val isAdded: (String, String) -> Boolean,
        private val onAdd: (String, String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val rows: List<Row> = groups.flatMap { (api, pages) ->
            listOf<Row>(Row.Header(api)) + pages.map {
                Row.Category(api, it)
            }
        }

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(
            parent: ViewGroup, viewType: Int
        ): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderHolder(
                    HomeCustomProviderHeaderBinding.inflate(
                        inflater, parent, false
                    )
                )
            } else {
                CategoryHolder(
                    HomeCustomCategoryRowBinding.inflate(
                        inflater, parent, false
                    )
                )
            }
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder, position: Int
        ) {
            when (val row = rows[position]) {
                is Row.Header ->
                    (holder as HeaderHolder).b.customHeaderText.text =
                        row.apiName
                is Row.Category -> {
                    val b = (holder as CategoryHolder).b
                    b.customCatName.text = row.pageName
                    val added = isAdded(row.apiName, row.pageName)
                    b.customCatAdd.setText(
                        if (added) R.string.home_custom_added
                        else R.string.home_custom_add
                    )
                    b.customCatAdd.isEnabled = !added
                    b.customCatAdd.alpha = if (added) 0.45f else 1f
                    b.root.setOnClickListener {
                        if (!added) onAdd(row.apiName, row.pageName)
                    }
                    b.customCatAdd.setOnClickListener {
                        if (!added) onAdd(row.apiName, row.pageName)
                    }
                }
            }
        }

        class HeaderHolder(val b: HomeCustomProviderHeaderBinding) :
            RecyclerView.ViewHolder(b.root)

        class CategoryHolder(val b: HomeCustomCategoryRowBinding) :
            RecyclerView.ViewHolder(b.root)
    }
}
