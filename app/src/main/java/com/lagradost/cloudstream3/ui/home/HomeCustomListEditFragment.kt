package com.lagradost.cloudstream3.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 8.44/8.46: build/edit one custom home list. One scrolling
 * sectioned list (his UI feedback): selected entries on top with
 * drag-reorder, then every provider's categories under clear
 * section bars. Providers that declare no mainPage (Netflix-type
 * multi-provider plugins) get a "tap to load" row — their
 * categories are discovered by fetching the homepage once.
 */
class HomeCustomListEditFragment : Fragment() {

    private var binding: FragmentHomeCustomListEditBinding? = null
    private var listId: String? = null
    private val selected = mutableListOf<HomeCustomLists.Entry>()
    private var declared: List<Pair<String, List<String>>> = emptyList()
    private var dynamicProviders: List<String> = emptyList()
    private val dynamicFetched = mutableMapOf<String, List<String>>()
    private val fetching = mutableSetOf<String>()
    private lateinit var adapter: EditAdapter

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
        declared = HomeCustomLists.allProviderCategories()
        dynamicProviders = HomeCustomLists.dynamicProviders()

        adapter = EditAdapter(
            isAdded = { entry -> selected.contains(entry) },
            onRemove = { entry ->
                selected.remove(entry)
                rebuild()
            },
            onAdd = { entry ->
                if (!selected.contains(entry)) selected += entry
                rebuild()
            },
            onFetch = { api -> fetchDynamic(api) }
        )
        b.customEditList.layoutManager = LinearLayoutManager(ctx)
        b.customEditList.adapter = adapter
        ItemTouchHelper(object :
            ItemTouchHelper.SimpleCallback(0, 0) {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                // Only selected rows drag (they sit right under
                // the section header, so selected index = pos - 1).
                return if (viewHolder is EditAdapter.SelHolder) {
                    makeMovementFlags(
                        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
                    )
                } else 0
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (target !is EditAdapter.SelHolder) return false
                val from = viewHolder.bindingAdapterPosition - 1
                val to = target.bindingAdapterPosition - 1
                if (from !in selected.indices || to !in selected.indices) {
                    return false
                }
                Collections.swap(selected, from, to)
                adapter.notifyItemMoved(
                    viewHolder.bindingAdapterPosition,
                    target.bindingAdapterPosition
                )
                return true
            }

            override fun onSwiped(
                viewHolder: RecyclerView.ViewHolder, direction: Int
            ) = Unit
        }).attachToRecyclerView(b.customEditList)
        rebuild()

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

    private fun fetchDynamic(api: String) {
        if (!fetching.add(api)) return
        rebuild()
        viewLifecycleOwner.lifecycleScope.launch {
            val names = withContext(Dispatchers.IO) {
                HomeCustomLists.fetchDynamicCategories(api)
            }
            fetching.remove(api)
            if (binding == null) return@launch
            if (names.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    R.string.home_custom_fetch_empty,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                dynamicFetched[api] = names
            }
            rebuild()
        }
    }

    private fun rebuild() {
        adapter.rows = buildRows()
        adapter.notifyDataSetChanged()
    }

    private fun buildRows(): List<Row> {
        val rows = mutableListOf<Row>()
        rows += Row.SelHeader
        selected.forEach { rows += Row.SelItem(it) }
        rows += Row.AllHeader
        declared.forEach { (api, pages) ->
            rows += Row.ProvHeader(api, pages.size)
            pages.forEach {
                rows += Row.Cat(HomeCustomLists.Entry(api, it))
            }
        }
        dynamicProviders.forEach { api ->
            val fetched = dynamicFetched[api]
            if (fetched == null) {
                rows += Row.Fetch(api, api in fetching)
            } else {
                rows += Row.ProvHeader(api, fetched.size)
                fetched.forEach {
                    rows += Row.Cat(
                        HomeCustomLists.Entry(api, it, dynamic = true)
                    )
                }
            }
        }
        return rows
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private sealed interface Row {
        data object SelHeader : Row
        data object AllHeader : Row
        data class SelItem(val entry: HomeCustomLists.Entry) : Row
        data class ProvHeader(val apiName: String, val count: Int) : Row
        data class Cat(val entry: HomeCustomLists.Entry) : Row
        data class Fetch(val apiName: String, val loading: Boolean) : Row
    }

    private class EditAdapter(
        private val isAdded: (HomeCustomLists.Entry) -> Boolean,
        private val onRemove: (HomeCustomLists.Entry) -> Unit,
        private val onAdd: (HomeCustomLists.Entry) -> Unit,
        private val onFetch: (String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var rows: List<Row> = emptyList()

        override fun getItemViewType(position: Int): Int =
            when (rows[position]) {
                is Row.SelHeader, is Row.AllHeader,
                is Row.ProvHeader -> 0
                is Row.SelItem -> 1
                is Row.Cat -> 2
                is Row.Fetch -> 3
            }

        override fun onCreateViewHolder(
            parent: ViewGroup, viewType: Int
        ): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                1 -> SelHolder(
                    HomeCustomSelectedRowBinding.inflate(
                        inflater, parent, false
                    )
                )
                2 -> CatHolder(
                    HomeCustomCategoryRowBinding.inflate(
                        inflater, parent, false
                    )
                )
                3 -> FetchHolder(
                    HomeCustomProviderHeaderBinding.inflate(
                        inflater, parent, false
                    )
                )
                else -> HeaderHolder(
                    HomeCustomProviderHeaderBinding.inflate(
                        inflater, parent, false
                    )
                )
            }
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder, position: Int
        ) {
            val ctx = holder.itemView.context
            when (val row = rows[position]) {
                is Row.SelHeader ->
                    (holder as HeaderHolder).b.customHeaderText.setText(
                        R.string.home_custom_in_list
                    )
                is Row.AllHeader ->
                    (holder as HeaderHolder).b.customHeaderText.setText(
                        R.string.home_custom_all_categories
                    )
                is Row.ProvHeader ->
                    (holder as HeaderHolder).b.customHeaderText.text =
                        ctx.getString(
                            R.string.home_custom_section_count,
                            row.apiName, row.count
                        )
                is Row.SelItem -> {
                    val b = (holder as SelHolder).b
                    b.customSelName.text = row.entry.pageName
                    b.customSelApi.text = row.entry.apiName
                    b.customSelRemove.setOnClickListener {
                        onRemove(row.entry)
                    }
                }
                is Row.Cat -> {
                    val b = (holder as CatHolder).b
                    b.customCatName.text = row.entry.pageName
                    val added = isAdded(row.entry)
                    b.customCatAdd.setText(
                        if (added) R.string.home_custom_added
                        else R.string.home_custom_add
                    )
                    b.customCatAdd.isEnabled = !added
                    b.customCatAdd.alpha = if (added) 0.45f else 1f
                    b.root.setOnClickListener {
                        if (!added) onAdd(row.entry)
                    }
                    b.customCatAdd.setOnClickListener {
                        if (!added) onAdd(row.entry)
                    }
                }
                is Row.Fetch -> {
                    val b = (holder as FetchHolder).b
                    val label = if (row.loading) {
                        ctx.getString(R.string.home_custom_loading)
                    } else {
                        ctx.getString(
                            R.string.home_custom_fetch_categories
                        )
                    }
                    b.customHeaderText.text = "${row.apiName} — $label"
                    b.customHeaderText.alpha =
                        if (row.loading) 0.5f else 1f
                    b.root.setOnClickListener {
                        if (!row.loading) onFetch(row.apiName)
                    }
                }
            }
        }

        class HeaderHolder(val b: HomeCustomProviderHeaderBinding) :
            RecyclerView.ViewHolder(b.root)

        class SelHolder(val b: HomeCustomSelectedRowBinding) :
            RecyclerView.ViewHolder(b.root)

        class CatHolder(val b: HomeCustomCategoryRowBinding) :
            RecyclerView.ViewHolder(b.root)

        class FetchHolder(val b: HomeCustomProviderHeaderBinding) :
            RecyclerView.ViewHolder(b.root)
    }
}
