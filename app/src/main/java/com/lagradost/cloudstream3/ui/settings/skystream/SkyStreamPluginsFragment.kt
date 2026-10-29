package com.lagradost.cloudstream3.ui.settings.skystream

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.skystream.SkyStreamProviders
import com.lagradost.cloudstream3.skystream.SkyStreamRepository
import com.lagradost.cloudstream3.skystream.StoredSkyStreamPlugin
import com.lagradost.cloudstream3.skystream.StoredSkyStreamRepo
import kotlinx.coroutines.launch

class SkyStreamPluginsFragment : Fragment() {
    private lateinit var adapter: SkyStreamRowsAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_skystream_plugins, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val baseTop = view.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, baseTop + top, v.paddingRight, v.paddingBottom)
            insets
        }
        view.requestApplyInsets()

        view.findViewById<ImageButton>(R.id.skystream_back).setOnClickListener {
            findNavController().navigateUp()
        }
        view.findViewById<MaterialButton>(R.id.skystream_add_button).setOnClickListener {
            showAddRepoDialog()
        }
        recycler = view.findViewById(R.id.skystream_recycler)
        emptyView = view.findViewById(R.id.skystream_empty)
        adapter = SkyStreamRowsAdapter(
            onToggle = { plugin, enabled ->
                SkyStreamRepository.setEnabled(requireContext(), plugin.packageName, enabled)
                SkyStreamProviders.sync(requireContext())
                refreshRows()
            },
            onRefresh = { repo -> installRepository(repo.url, isRefresh = true) },
            onRemove = { repo -> confirmRemove(repo) },
            errorFor = { packageName -> SkyStreamRepository.lastError(requireContext(), packageName) },
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        refreshRows()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) refreshRows()
    }

    private fun refreshRows() {
        if (!::adapter.isInitialized || !isAdded) return
        val repos = SkyStreamRepository.getRepos(requireContext())
        adapter.submit(repos)
        emptyView.visibility = if (repos.isEmpty()) View.VISIBLE else View.GONE
        SkyStreamProviders.sync(requireContext())
    }

    private fun showAddRepoDialog() {
        val context = requireContext()
        val input = EditText(context).apply {
            hint = getString(R.string.skystream_repo_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText("universe")
            selectAll()
        }
        val container = FrameLayout(context).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.skystream_add_repo)
            .setMessage(R.string.skystream_repo_dialog_message)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                if (raw.isNotBlank()) installRepository(raw, isRefresh = false)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun installRepository(input: String, isRefresh: Boolean) {
        val context = requireContext()
        Toast.makeText(
            context,
            if (isRefresh) R.string.skystream_refreshing else R.string.skystream_checking_repo,
            Toast.LENGTH_SHORT,
        ).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = SkyStreamRepository.installRepo(context, input)
            result.fold(
                onSuccess = { repo ->
                    SkyStreamProviders.sync(context)
                    refreshRows()
                    Toast.makeText(
                        context,
                        getString(R.string.skystream_repo_installed, repo.name, repo.plugins.size),
                        Toast.LENGTH_LONG,
                    ).show()
                },
                onFailure = { error ->
                    Toast.makeText(
                        context,
                        getString(R.string.skystream_repo_failed, error.message ?: "Unknown error"),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    private fun confirmRemove(repo: StoredSkyStreamRepo) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.skystream_remove_repo)
            .setMessage(getString(R.string.skystream_remove_repo_message, repo.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                SkyStreamRepository.removeRepo(requireContext(), repo.url)
                SkyStreamProviders.sync(requireContext())
                refreshRows()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

private sealed class SkyStreamRow {
    data class Header(val repo: StoredSkyStreamRepo) : SkyStreamRow()
    data class Plugin(val repoUrl: String, val plugin: StoredSkyStreamPlugin) : SkyStreamRow()
}

private class SkyStreamRowsAdapter(
    private val onToggle: (StoredSkyStreamPlugin, Boolean) -> Unit,
    private val onRefresh: (StoredSkyStreamRepo) -> Unit,
    private val onRemove: (StoredSkyStreamRepo) -> Unit,
    private val errorFor: (String) -> String?,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var rows: List<SkyStreamRow> = emptyList()

    fun submit(repos: List<StoredSkyStreamRepo>) {
        rows = buildList {
            repos.forEach { repo ->
                add(SkyStreamRow.Header(repo))
                repo.plugins.forEach { plugin -> add(SkyStreamRow.Plugin(repo.url, plugin)) }
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is SkyStreamRow.Header) TYPE_HEADER else TYPE_PLUGIN

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_skystream_repo, parent, false))
        } else {
            PluginHolder(inflater.inflate(R.layout.item_skystream_plugin, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is SkyStreamRow.Header -> (holder as HeaderHolder).bind(row.repo)
            is SkyStreamRow.Plugin -> (holder as PluginHolder).bind(row.plugin)
        }
    }

    private inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.skystream_repo_name)
        private val meta: TextView = view.findViewById(R.id.skystream_repo_meta)
        private val refresh: ImageButton = view.findViewById(R.id.skystream_repo_refresh)
        private val remove: ImageButton = view.findViewById(R.id.skystream_repo_remove)

        fun bind(repo: StoredSkyStreamRepo) {
            name.text = repo.name
            meta.text = repo.url + " · " + repo.plugins.size + " plugins"
            refresh.setOnClickListener { onRefresh(repo) }
            remove.setOnClickListener { onRemove(repo) }
        }
    }

    private inner class PluginHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.skystream_plugin_name)
        private val meta: TextView = view.findViewById(R.id.skystream_plugin_meta)
        private val error: TextView = view.findViewById(R.id.skystream_plugin_error)
        private val toggle: MaterialSwitch = view.findViewById(R.id.skystream_plugin_switch)

        fun bind(plugin: StoredSkyStreamPlugin) {
            name.text = plugin.name
            meta.text = "v" + plugin.version + " · " + plugin.packageName
            val lastError = errorFor(plugin.packageName)
            error.text = lastError.orEmpty()
            error.visibility = if (lastError.isNullOrBlank()) View.GONE else View.VISIBLE
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = plugin.enabled
            toggle.setOnCheckedChangeListener { _, checked -> onToggle(plugin, checked) }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_PLUGIN = 1
    }
}
