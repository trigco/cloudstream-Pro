package com.lagradost.cloudstream3.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentHomeCustomListsBinding
import com.lagradost.cloudstream3.databinding.HomeCustomListRowBinding

/** Phase 8.44: overview of the user's custom home lists. */
class HomeCustomListsFragment : Fragment() {

    private var binding: FragmentHomeCustomListsBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentHomeCustomListsBinding.inflate(
            inflater, container, false
        )
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.customListsRecycler?.layoutManager =
            LinearLayoutManager(requireContext())
        binding?.customListsNew?.setOnClickListener {
            findNavController().navigate(
                R.id.action_home_custom_lists_to_edit,
                bundleOf("listId" to null)
            )
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val b = binding ?: return
        val ctx = requireContext()
        val lists = HomeCustomLists.getLists(ctx)
        val activeId = HomeCustomLists.activeId(ctx)
        b.customListsEmpty.visibility =
            if (lists.isEmpty()) View.VISIBLE else View.GONE
        b.customListsRecycler.adapter = ListsAdapter(
            lists = lists,
            activeId = activeId,
            onEdit = { list ->
                findNavController().navigate(
                    R.id.action_home_custom_lists_to_edit,
                    bundleOf("listId" to list.id)
                )
            },
            onUse = { list ->
                if (HomeCustomLists.activeId(ctx) == list.id) {
                    HomeCustomLists.setActiveId(ctx, null)
                } else {
                    HomeCustomLists.setActiveId(ctx, list.id)
                    Toast.makeText(
                        ctx,
                        getString(R.string.home_custom_now_active, list.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                refresh()
            },
            onDelete = { list ->
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.home_custom_delete)
                    .setMessage(
                        getString(R.string.home_custom_delete_confirm, list.name)
                    )
                    .setPositiveButton(R.string.home_custom_delete) { _, _ ->
                        HomeCustomLists.delete(ctx, list.id)
                        refresh()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        )
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private class ListsAdapter(
        private val lists: List<HomeCustomLists.CustomList>,
        private val activeId: String?,
        private val onEdit: (HomeCustomLists.CustomList) -> Unit,
        private val onUse: (HomeCustomLists.CustomList) -> Unit,
        private val onDelete: (HomeCustomLists.CustomList) -> Unit
    ) : RecyclerView.Adapter<ListsAdapter.Holder>() {

        class Holder(val b: HomeCustomListRowBinding) :
            RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(
            parent: ViewGroup, viewType: Int
        ): Holder = Holder(
            HomeCustomListRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

        override fun getItemCount(): Int = lists.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val list = lists[position]
            val b = holder.b
            val ctx = b.root.context
            b.customRowName.text = list.name
            val count = ctx.getString(
                R.string.home_custom_count, list.entries.size
            )
            b.customRowMeta.text =
                if (list.id == activeId) {
                    count + " • " + ctx.getString(R.string.home_custom_active_badge)
                } else {
                    count
                }
            b.customRowUse.setText(
                if (list.id == activeId) R.string.home_custom_stop
                else R.string.home_custom_use
            )
            b.customRowEdit.setOnClickListener { onEdit(list) }
            b.customRowUse.setOnClickListener { onUse(list) }
            b.customRowDelete.setOnClickListener { onDelete(list) }
        }
    }
}
