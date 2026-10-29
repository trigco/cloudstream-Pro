package com.lagradost.cloudstream3.ui.settings.sky

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
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
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.sky.SkyEngine
import com.lagradost.cloudstream3.sky.SkyPluginManifest
import com.lagradost.cloudstream3.sky.SkyProviders
import com.lagradost.cloudstream3.sky.SkyRepository
import com.lagradost.cloudstream3.sky.SkyRepoListing
import com.lagradost.cloudstream3.sky.SkySettingDef
import com.lagradost.cloudstream3.sky.StoredSkyPlugin
import com.lagradost.cloudstream3.sky.StoredSkyRepo
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings -> SkyStream Plugins: install SkyStream JS plugin
 * repositories (mega-repo -> repo -> plugin lists). Each installed
 * plugin becomes a full provider in the app — home sections, search,
 * details and streams — running on the same QuickJS engine as the
 * Nuvio bridge, with a persistent per-plugin context.
 */
class SkyPluginsFragment : Fragment() {

    private lateinit var adapter: SkyRepoAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView

    /** Chain expansions per stored repo URL, filled in asynchronously. */
    private val listings = mutableMapOf<String, List<SkyRepoListing>>()
    private val loadingRepos = mutableSetOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_sky_plugins, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Status-bar inset (same fix as the other bridge pages).
        val baseTop = view.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, baseTop + top, v.paddingRight, v.paddingBottom)
            insets
        }
        view.requestApplyInsets()
        view.findViewById<ImageButton>(R.id.sky_back).setOnClickListener {
            findNavController().navigateUp()
        }
        view.findViewById<MaterialButton>(R.id.sky_add_button).setOnClickListener {
            showAddDialog()
        }
        recycler = view.findViewById(R.id.sky_recycler)
        emptyView = view.findViewById(R.id.sky_empty)
        adapter = SkyRepoAdapter(
            onToggleCollapse = { repo ->
                val ctx = requireContext()
                val collapsed = SkyRepository.collapsedRepos(ctx).contains(repo.url)
                SkyRepository.setRepoCollapsed(ctx, repo.url, !collapsed)
                refresh()
            },
            onRefreshRepo = { repo -> refreshRepo(repo) },
            onRemoveRepo = { repo -> confirmRemoveRepo(repo) },
            onPluginAction = { entry, listing, state ->
                when (state) {
                    SkyRow.PluginState.INSTALLED -> {
                        val installed = SkyRepository.installedByPackage(requireContext())
                        installed[entry.packageName]?.let { confirmRemovePlugin(it) }
                    }
                    else -> installPlugin(entry, listing.repoUrl)
                }
            },
            onToggleInstalled = { plugin, enabled ->
                val ctx = requireContext()
                SkyRepository.setPluginEnabled(ctx, plugin.packageName, enabled)
                if (!enabled) SkyEngine.evictPackage(plugin.packageName)
                SkyProviders.sync(ctx)
            },
            onRemoveInstalled = { plugin -> confirmRemovePlugin(plugin) },
            onInstalledSettings = { plugin -> openPluginSettings(plugin) },
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        SkyProviders.sync(requireContext())
        refresh()
        fetchAllChains()
    }

    private fun toast(message: Int) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        if (!::adapter.isInitialized) return
        val ctx = requireContext()
        val repos = SkyRepository.getRepos(ctx)
        val installed = SkyRepository.getInstalled(ctx)
        adapter.submit(
            repos = repos,
            listings = listings,
            loading = loadingRepos,
            installed = installed,
            collapsed = SkyRepository.collapsedRepos(ctx),
        )
        emptyView.visibility =
            if (repos.isEmpty() && installed.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---- repository chain loading ----

    private fun fetchAllChains() {
        val ctx = requireContext()
        for (repo in SkyRepository.getRepos(ctx)) {
            fetchChain(repo)
        }
    }

    private fun fetchChain(repo: StoredSkyRepo) {
        val ctx = requireContext()
        if (!loadingRepos.add(repo.url)) return
        refresh()
        lifecycleScope.launch {
            val chain = runCatching { SkyRepository.fetchRepoChain(repo) }
                .getOrElse {
                    listOf(
                        SkyRepoListing(
                            repoUrl = repo.url,
                            repoName = repo.displayName(),
                            plugins = emptyList(),
                            error = "Could not load this repository",
                        )
                    )
                }
            loadingRepos.remove(repo.url)
            if (!isAdded) return@launch
            listings[repo.url] = chain
            val allFailed = chain.isNotEmpty() &&
                chain.all { it.plugins.isEmpty() && it.error != null }
            SkyRepository.setRepoError(
                ctx, repo.url, if (allFailed) chain.first().error else null
            )
            refresh()
        }
    }

    private fun refreshRepo(repo: StoredSkyRepo) {
        val ctx = requireContext()
        toast(R.string.sky_repo_checking)
        lifecycleScope.launch {
            val doc = SkyRepository.fetchRepoDoc(repo.url)
            if (!isAdded) return@launch
            if (doc != null) {
                SkyRepository.addRepo(ctx, repo.url, repo.input, doc)
            }
            listings.remove(repo.url)
            refresh()
            fetchChain(
                SkyRepository.getRepos(ctx)
                    .firstOrNull { it.url == repo.url } ?: repo
            )
        }
    }

    // ---- add / remove repository ----

    private fun showAddDialog() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = getString(R.string.sky_repo_input_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val container = FrameLayout(ctx).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.sky_add_repo)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                if (raw.isNotBlank()) resolveAndAdd(raw)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun resolveAndAdd(raw: String) {
        val ctx = requireContext()
        toast(R.string.sky_repo_checking)
        lifecycleScope.launch {
            val resolved = SkyRepository.resolveRepoInput(raw)
            if (!isAdded) return@launch
            if (resolved == null) {
                toast(R.string.sky_repo_add_failed)
                return@launch
            }
            val (url, doc) = resolved
            val added = SkyRepository.addRepo(ctx, url, raw, doc)
            toast(if (added) R.string.sky_repo_added else R.string.sky_repo_refreshed)
            listings.remove(url)
            refresh()
            SkyRepository.getRepos(ctx)
                .firstOrNull { it.url == url }?.let { fetchChain(it) }
        }
    }

    private fun confirmRemoveRepo(repo: StoredSkyRepo) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.sky_repo_remove_title)
            .setMessage(getString(R.string.sky_repo_remove_message, repo.displayName()))
            .setPositiveButton(R.string.delete) { _, _ ->
                SkyRepository.removeRepo(ctx, repo.url)
                listings.remove(repo.url)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- install / remove plugin ----

    private fun installPlugin(entry: SkyPluginManifest, sourceRepoUrl: String) {
        val ctx = requireContext()
        val wasInstalled = SkyRepository.installedByPackage(ctx)
            .containsKey(entry.packageName)
        toast(R.string.sky_plugin_installing)
        lifecycleScope.launch {
            val record = runCatching {
                SkyRepository.installPlugin(ctx, entry, sourceRepoUrl)
            }.getOrNull()
            if (!isAdded) return@launch
            if (record == null) {
                toast(R.string.sky_plugin_install_failed)
                return@launch
            }
            // New code (and possibly a new base URL): drop any live
            // context so the next call rebuilds from the new files.
            SkyEngine.evictPackage(entry.packageName)
            SkyProviders.sync(ctx)
            toast(
                if (wasInstalled) R.string.sky_plugin_updated
                else R.string.sky_plugin_installed
            )
            refresh()
        }
    }

    private fun confirmRemovePlugin(plugin: StoredSkyPlugin) {
        val ctx = requireContext()
        val name = plugin.manifest()?.name ?: plugin.name
        AlertDialog.Builder(ctx)
            .setTitle(R.string.sky_plugin_remove_title)
            .setMessage(getString(R.string.sky_plugin_remove_message, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                SkyRepository.removePlugin(ctx, plugin.packageName)
                SkyEngine.evictPackage(plugin.packageName)
                SkyProviders.sync(ctx)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- plugin settings form ----

    private fun openPluginSettings(plugin: StoredSkyPlugin) {
        val ctx = requireContext()
        val manifest = plugin.manifest()
        val fields = manifest?.settings.orEmpty()
            .filter { it.key.isNotBlank() }
        if (manifest == null || fields.isEmpty()) return
        val saved = SkyRepository.pluginSettings(ctx, plugin.packageName)
        showSettingsForm(plugin, fields, saved)
    }

    private fun showSettingsForm(
        plugin: StoredSkyPlugin,
        fields: List<SkySettingDef>,
        saved: JSONObject,
    ) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val getters = mutableListOf<() -> Unit>()
        val values = JSONObject()

        fun currentRaw(field: SkySettingDef): Any? {
            if (saved.has(field.key) && !saved.isNull(field.key)) {
                return saved.opt(field.key)
            }
            return field.defaultRaw
        }

        fun rawBool(raw: Any?): Boolean = when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.equals("true", ignoreCase = true) || raw == "1"
            else -> false
        }

        fun rawString(raw: Any?): String = when (raw) {
            null, JSONObject.NULL -> ""
            is String -> raw
            else -> raw.toString()
        }

        fun rawStringSet(raw: Any?): Set<String> = when (raw) {
            is JSONArray -> (0 until raw.length())
                .mapNotNull { raw.optString(it).takeIf { s -> s.isNotBlank() } }
                .toSet()
            is String -> raw.split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
            else -> emptySet()
        }

        for (field in fields) {
            val label = field.title.ifBlank { field.key }
            when (field.type) {
                "toggle" -> {
                    val switch = MaterialSwitch(ctx).apply {
                        text = label
                        isChecked = rawBool(currentRaw(field))
                        setPadding(0, dp(8), 0, dp(8))
                    }
                    column.addView(switch)
                    field.description?.let { desc ->
                        column.addView(TextView(ctx).apply { text = desc })
                    }
                    val key = field.key
                    getters += { values.put(key, switch.isChecked) }
                }
                "select" -> {
                    if (field.options.isEmpty()) continue
                    column.addView(TextView(ctx).apply {
                        text = label
                        setPadding(0, dp(10), 0, 0)
                    })
                    val raw = currentRaw(field)?.toString()
                    val spinner = Spinner(ctx).apply {
                        adapter = ArrayAdapter(
                            ctx,
                            android.R.layout.simple_spinner_dropdown_item,
                            field.options.map { it.label }
                        )
                        val index = field.options.indexOfFirst { it.value == raw }
                        if (index >= 0) setSelection(index)
                    }
                    column.addView(spinner)
                    field.description?.let { desc ->
                        column.addView(TextView(ctx).apply { text = desc })
                    }
                    val key = field.key
                    val options = field.options
                    getters += {
                        val index = spinner.selectedItemPosition
                        if (index in options.indices) {
                            values.put(key, options[index].value)
                        }
                    }
                }
                "toggleGroup" -> {
                    column.addView(TextView(ctx).apply {
                        text = label
                        setPadding(0, dp(10), 0, 0)
                    })
                    val selected = rawStringSet(currentRaw(field)).toMutableSet()
                    val switches = mutableListOf<Pair<String, MaterialSwitch>>()
                    for (option in field.options) {
                        val switch = MaterialSwitch(ctx).apply {
                            text = option.label.ifBlank { option.value }
                            isChecked = selected.contains(option.value)
                            setPadding(dp(12), dp(4), 0, dp(4))
                        }
                        column.addView(switch)
                        switches += option.value to switch
                    }
                    field.description?.let { desc ->
                        column.addView(TextView(ctx).apply { text = desc })
                    }
                    val key = field.key
                    getters += {
                        val arr = JSONArray()
                        switches.forEach { (value, switch) ->
                            if (switch.isChecked) arr.put(value)
                        }
                        values.put(key, arr)
                    }
                }
                else -> { // text / url
                    column.addView(TextView(ctx).apply {
                        text = label
                        setPadding(0, dp(10), 0, 0)
                    })
                    val edit = EditText(ctx).apply {
                        setSingleLine(true)
                        inputType = if (field.type == "url") {
                            InputType.TYPE_CLASS_TEXT or
                                InputType.TYPE_TEXT_VARIATION_URI
                        } else {
                            InputType.TYPE_CLASS_TEXT
                        }
                        setText(rawString(currentRaw(field)))
                    }
                    column.addView(edit)
                    field.description?.let { desc ->
                        column.addView(TextView(ctx).apply { text = desc })
                    }
                    val key = field.key
                    getters += {
                        values.put(key, edit.text?.toString()?.trim().orEmpty())
                    }
                }
            }
        }

        val scroll = ScrollView(ctx).apply { addView(column) }
        AlertDialog.Builder(ctx)
            .setTitle(plugin.manifest()?.name ?: plugin.name)
            .setView(scroll)
            .setPositiveButton(R.string.nuvio_settings_save) { _, _ ->
                getters.forEach { it() }
                SkyRepository.savePluginSettings(ctx, plugin.packageName, values)
                // Settings feed manifest.baseUrl and getPreference:
                // rebuild the plugin's context so they take effect.
                SkyEngine.evictPackage(plugin.packageName)
                toast(R.string.sky_settings_saved)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

// ---- list (sections + repo headers + plugin rows + installed rows) ----

private sealed interface SkyRow {
    enum class PluginState { NOT_INSTALLED, INSTALLED, UPDATE }

    data class Section(val titleRes: Int) : SkyRow
    data class Info(val text: String) : SkyRow
    data class RepoHeader(
        val repo: StoredSkyRepo,
        val name: String,
        val collapsed: Boolean,
        val error: String?,
    ) : SkyRow
    data class ListingHeader(val name: String) : SkyRow
    data class PluginRow(
        val entry: SkyPluginManifest,
        val listing: SkyRepoListing,
        val state: PluginState,
    ) : SkyRow
    data class InstalledRow(val plugin: StoredSkyPlugin) : SkyRow
}

private class SkyRepoAdapter(
    private val onToggleCollapse: (StoredSkyRepo) -> Unit,
    private val onRefreshRepo: (StoredSkyRepo) -> Unit,
    private val onRemoveRepo: (StoredSkyRepo) -> Unit,
    private val onPluginAction: (SkyPluginManifest, SkyRepoListing, SkyRow.PluginState) -> Unit,
    private val onToggleInstalled: (StoredSkyPlugin, Boolean) -> Unit,
    private val onRemoveInstalled: (StoredSkyPlugin) -> Unit,
    private val onInstalledSettings: (StoredSkyPlugin) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<SkyRow> = emptyList()

    fun submit(
        repos: List<StoredSkyRepo>,
        listings: Map<String, List<SkyRepoListing>>,
        loading: Set<String>,
        installed: List<StoredSkyPlugin>,
        collapsed: Set<String>,
    ) {
        val out = mutableListOf<SkyRow>()
        val installedByPackage = installed.associateBy { it.packageName }
        if (installed.isNotEmpty()) {
            out += SkyRow.Section(R.string.sky_section_installed)
            installed.sortedBy { it.name.lowercase() }.forEach { plugin ->
                out += SkyRow.InstalledRow(plugin)
            }
        }
        if (repos.isNotEmpty()) {
            out += SkyRow.Section(R.string.sky_section_repositories)
            for (repo in repos) {
                val chain = listings[repo.url]
                val isCollapsed = collapsed.contains(repo.url)
                val chainError = chain
                    ?.firstOrNull { it.error != null && it.plugins.isEmpty() }
                    ?.error
                out += SkyRow.RepoHeader(
                    repo = repo,
                    name = repo.doc()?.name
                        ?: chain?.firstOrNull()?.repoName
                        ?: repo.url,
                    collapsed = isCollapsed,
                    error = repo.lastError ?: chainError,
                )
                if (isCollapsed) continue
                when {
                    chain == null -> {
                        out += SkyRow.Info(
                            if (repo.url in loading) "…" else ""
                        )
                    }
                    chain.size == 1 -> {
                        val listing = chain[0]
                        listing.error?.let { out += SkyRow.Info(it) }
                        listing.plugins.forEach { entry ->
                            out += pluginRow(entry, listing, installedByPackage)
                        }
                    }
                    else -> {
                        for (listing in chain) {
                            out += SkyRow.ListingHeader(listing.repoName)
                            listing.error?.let { out += SkyRow.Info(it) }
                            listing.plugins.forEach { entry ->
                                out += pluginRow(entry, listing, installedByPackage)
                            }
                        }
                    }
                }
            }
        }
        rows = out
        notifyDataSetChanged()
    }

    private fun pluginRow(
        entry: SkyPluginManifest,
        listing: SkyRepoListing,
        installedByPackage: Map<String, StoredSkyPlugin>,
    ): SkyRow.PluginRow {
        val installed = installedByPackage[entry.packageName]
        val state = when {
            installed == null -> SkyRow.PluginState.NOT_INSTALLED
            SkyRepository.updateAvailable(installed, entry) ->
                SkyRow.PluginState.UPDATE
            else -> SkyRow.PluginState.INSTALLED
        }
        return SkyRow.PluginRow(entry, listing, state)
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is SkyRow.Section, is SkyRow.Info, is SkyRow.ListingHeader -> TYPE_TEXT
        is SkyRow.RepoHeader -> TYPE_REPO
        is SkyRow.PluginRow -> TYPE_PLUGIN
        is SkyRow.InstalledRow -> TYPE_INSTALLED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_REPO -> RepoHolder(
                inflater.inflate(R.layout.item_sky_repo, parent, false)
            )
            TYPE_PLUGIN -> PluginHolder(
                inflater.inflate(R.layout.item_sky_plugin, parent, false)
            )
            TYPE_INSTALLED -> InstalledHolder(
                inflater.inflate(R.layout.item_sky_installed, parent, false)
            )
            else -> TextHolder(
                inflater.inflate(R.layout.item_sky_section, parent, false)
            )
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is SkyRow.Section -> {
                (holder as TextHolder).text.setText(row.titleRes)
            }
            is SkyRow.Info -> {
                (holder as TextHolder).text.text = row.text
            }
            is SkyRow.ListingHeader -> {
                (holder as TextHolder).text.text = row.name
            }
            is SkyRow.RepoHeader -> {
                val h = holder as RepoHolder
                h.name.text = row.name
                h.url.text = row.repo.url
                if (row.error.isNullOrBlank()) {
                    h.error.visibility = View.GONE
                } else {
                    h.error.visibility = View.VISIBLE
                    h.error.text = row.error
                }
                val targetRotation = if (row.collapsed) -90f else 0f
                if (h.expand.rotation != targetRotation) {
                    h.expand.animate().rotation(targetRotation)
                        .setDuration(250).start()
                }
                h.itemView.setOnClickListener { onToggleCollapse(row.repo) }
                h.refresh.setOnClickListener { onRefreshRepo(row.repo) }
                h.remove.setOnClickListener { onRemoveRepo(row.repo) }
            }
            is SkyRow.PluginRow -> {
                val h = holder as PluginHolder
                val entry = row.entry
                val ctx = h.itemView.context
                h.name.text = entry.name
                val version = ctx.getString(R.string.sky_version_format, entry.version)
                h.desc.text = listOf(entry.description, version)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ") +
                    if (entry.categories.isNotEmpty()) {
                        "\n" + entry.categories.joinToString(", ")
                    } else {
                        ""
                    }
                h.action.setText(
                    when (row.state) {
                        SkyRow.PluginState.NOT_INSTALLED -> R.string.sky_plugin_install
                        SkyRow.PluginState.UPDATE -> R.string.sky_plugin_update
                        SkyRow.PluginState.INSTALLED -> R.string.sky_plugin_remove
                    }
                )
                h.action.setOnClickListener {
                    onPluginAction(row.entry, row.listing, row.state)
                }
            }
            is SkyRow.InstalledRow -> {
                val h = holder as InstalledHolder
                val plugin = row.plugin
                val ctx = h.itemView.context
                val manifest = plugin.manifest()
                h.name.text = manifest?.name ?: plugin.name
                val version = ctx.getString(R.string.sky_version_format, plugin.version)
                val description = manifest?.description ?: plugin.sourceRepoUrl
                h.desc.text = listOf(description, version)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                val icon = manifest?.iconUrl
                if (!icon.isNullOrBlank()) {
                    h.icon.visibility = View.VISIBLE
                    h.icon.loadImage(icon) {
                        transformations(CircleCropTransformation())
                        error(
                            getImageFromDrawable(
                                ctx, R.drawable.ic_baseline_extension_24
                            )
                        )
                    }
                } else {
                    h.icon.visibility = View.GONE
                }
                h.settings.visibility =
                    if (manifest?.settings?.isNotEmpty() == true) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                h.settings.setOnClickListener { onInstalledSettings(plugin) }
                h.enabled.setOnCheckedChangeListener(null)
                h.enabled.isChecked = plugin.enabled
                h.enabled.setOnCheckedChangeListener { _, checked ->
                    onToggleInstalled(plugin, checked)
                }
                h.remove.setOnClickListener { onRemoveInstalled(plugin) }
            }
        }
    }

    private class TextHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.item_sky_section_title)
    }

    private class RepoHolder(view: View) : RecyclerView.ViewHolder(view) {
        val expand: ImageView = view.findViewById(R.id.item_sky_repo_expand)
        val name: TextView = view.findViewById(R.id.item_sky_repo_name)
        val url: TextView = view.findViewById(R.id.item_sky_repo_url)
        val error: TextView = view.findViewById(R.id.item_sky_repo_error)
        val refresh: ImageButton = view.findViewById(R.id.item_sky_repo_refresh)
        val remove: ImageButton = view.findViewById(R.id.item_sky_repo_remove)
    }

    private class PluginHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.item_sky_plugin_name)
        val desc: TextView = view.findViewById(R.id.item_sky_plugin_desc)
        val action: MaterialButton = view.findViewById(R.id.item_sky_plugin_action)
    }

    private class InstalledHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.item_sky_inst_icon)
        val name: TextView = view.findViewById(R.id.item_sky_inst_name)
        val desc: TextView = view.findViewById(R.id.item_sky_inst_desc)
        val settings: ImageButton = view.findViewById(R.id.item_sky_inst_settings)
        val enabled: MaterialSwitch = view.findViewById(R.id.item_sky_inst_enabled)
        val remove: ImageButton = view.findViewById(R.id.item_sky_inst_remove)
    }

    companion object {
        private const val TYPE_TEXT = 0
        private const val TYPE_REPO = 1
        private const val TYPE_PLUGIN = 2
        private const val TYPE_INSTALLED = 3
    }
}
