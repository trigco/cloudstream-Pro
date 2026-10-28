package com.lagradost.cloudstream3.ui.settings.nuvio

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
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.nuvio.NuvioEngine
import com.lagradost.cloudstream3.nuvio.NuvioDiagnostics
import com.lagradost.cloudstream3.nuvio.NuvioValidity
import com.lagradost.cloudstream3.nuvio.NuvioParse
import com.lagradost.cloudstream3.nuvio.NuvioProviders
import com.lagradost.cloudstream3.nuvio.NuvioRepository
import com.lagradost.cloudstream3.nuvio.NuvioScraperInfo
import com.lagradost.cloudstream3.nuvio.NuvioSettingsField
import com.lagradost.cloudstream3.nuvio.NuvioTmdb
import com.lagradost.cloudstream3.nuvio.StoredNuvioRepo
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Settings -> Nuvio Plugins: install JS scraper repositories. Each
 * enabled scraper adds streams to Stremio addon titles (bridge
 * step 2), resolved through the TMDB id of the title.
 */
class NuvioPluginsFragment : Fragment() {

    private lateinit var adapter: NuvioRepoAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var diagView: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_nuvio_plugins, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Status-bar inset (same fix as HomeCustomListsFragment 8.47).
        val baseTop = view.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, baseTop + top, v.paddingRight, v.paddingBottom)
            insets
        }
        view.requestApplyInsets()
        view.findViewById<ImageButton>(R.id.nuvio_back).setOnClickListener {
            findNavController().navigateUp()
        }
        view.findViewById<MaterialButton>(R.id.nuvio_add_button).setOnClickListener {
            showAddDialog()
        }
        recycler = view.findViewById(R.id.nuvio_recycler)
        emptyView = view.findViewById(R.id.nuvio_empty)
        diagView = view.findViewById(R.id.nuvio_diagnostics)
        adapter = NuvioRepoAdapter(
            onToggle = { repo, scraper, enabled ->
                NuvioRepository.setScraperEnabled(
                    requireContext(), repo.manifestUrl, scraper, enabled
                )
                refresh()
            },
            onRefreshRepo = { repo -> fetchAndStoreRepo(repo.manifestUrl) },
            onRemoveRepo = { repo -> confirmRemove(repo) },
            onSettings = { repo, scraper -> openScraperSettings(repo, scraper) },
            onCheckRepo = { repo -> checkRepo(repo) },
            onToggleCollapse = { repo ->
                val ctx = requireContext()
                val collapsed = NuvioRepository.collapsedRepos(ctx)
                    .contains(repo.manifestUrl)
                NuvioRepository.setRepoCollapsed(ctx, repo.manifestUrl, !collapsed)
                refresh()
            },
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        refresh()
    }

    /**
     * Validity check for one repository (8.68): downloads each scraper's
     * code and load-tests it in the engine (no stream fetch — just "does
     * this code load and export getStreams"). Results land in NuvioValidity
     * and show on the scraper rows, so broken scrapers (syntax errors,
     * missing exports, dead code URLs) are visible before pressing Play.
     */
    private fun checkRepo(repo: StoredNuvioRepo) {
        val manifest = repo.manifest() ?: return
        val ctx = requireContext()
        val progress = TextView(ctx).apply {
            setPadding(48, 24, 48, 8)
            text = "…"
        }
        var cancelled = false
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.nuvio_check_title)
            .setView(progress)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
            .create()
        dialog.show()
        lifecycleScope.launch {
            var ok = 0
            var bad = 0
            val scrapers = manifest.scrapers
            for ((index, scraper) in scrapers.withIndex()) {
                if (cancelled) break
                progress.text = "${index + 1} / ${scrapers.size} · ${scraper.name}"
                val code = NuvioRepository.fetchCode(ctx, repo, scraper)
                if (code == null) {
                    NuvioValidity.setProbe(
                        ctx, repo.manifestUrl, scraper.id, false, "code download failed"
                    )
                    bad++
                    continue
                }
                val result = NuvioEngine.probe(
                    ctx, repo.manifestUrl, scraper, code,
                    NuvioRepository.scraperSettings(ctx, repo.manifestUrl, scraper.id),
                    NuvioTmdb.apiKey(ctx),
                )
                when {
                    result.has("error") -> {
                        NuvioValidity.setProbe(
                            ctx, repo.manifestUrl, scraper.id, false,
                            result.optString("error").take(120)
                        )
                        bad++
                    }
                    !result.optBoolean("gs") -> {
                        NuvioValidity.setProbe(
                            ctx, repo.manifestUrl, scraper.id, false, "no getStreams export"
                        )
                        bad++
                    }
                    else -> {
                        NuvioValidity.setProbe(
                            ctx, repo.manifestUrl, scraper.id, true,
                            if (result.optBoolean("st")) "loads · has settings" else "loads"
                        )
                        ok++
                    }
                }
            }
            if (dialog.isShowing) dialog.dismiss()
            if (!cancelled && isAdded) {
                Toast.makeText(
                    ctx,
                    getString(R.string.nuvio_check_done, ok, bad),
                    Toast.LENGTH_LONG
                ).show()
            }
            if (isAdded) refresh()
        }
    }

    private fun refresh() {
        val diag = NuvioDiagnostics.lastSummary
        diagView.visibility = if (diag.isNullOrBlank()) View.GONE else View.VISIBLE
        if (!diag.isNullOrBlank()) {
            diagView.text = "Last stream check · $diag"
        }
        if (!::adapter.isInitialized) return
        val ctx = requireContext()
        val repos = NuvioRepository.getRepos(ctx)
        adapter.submit(repos, NuvioRepository.collapsedRepos(ctx))
        emptyView.visibility = if (repos.isEmpty()) View.VISIBLE else View.GONE
        // Keep the browsable Nuvio provider registered exactly while
        // it can work (TMDB key set + at least one scraper enabled).
        NuvioProviders.sync(ctx)
    }

    private fun toast(message: Int) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    // ---- add / refresh repository ----

    private fun showAddDialog() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = getString(R.string.nuvio_repo_url_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val container = FrameLayout(ctx).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.nuvio_add_repo)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                val url = when {
                    raw.startsWith("http://") || raw.startsWith("https://") -> raw
                    raw.isNotBlank() -> "https://$raw"
                    else -> ""
                }
                if (url.isNotBlank()) fetchAndStoreRepo(url)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fetchAndStoreRepo(url: String) {
        val ctx = requireContext()
        val existed = NuvioRepository.getRepos(ctx).any { it.manifestUrl == url }
        toast(R.string.nuvio_repo_checking)
        lifecycleScope.launch {
            val fetched = runCatching {
                val response = app.get(url)
                if (response.code !in 200..299) return@runCatching null
                val raw = response.text
                val manifest = NuvioParse.parseRepoManifest(JSONObject(raw))
                if (manifest.scrapers.isEmpty()) null else raw
            }.getOrNull()
            if (!isAdded) return@launch
            if (fetched == null) {
                toast(R.string.nuvio_repo_add_failed)
                return@launch
            }
            NuvioRepository.addRepo(ctx, url, fetched)
            toast(
                if (existed) R.string.nuvio_repo_refreshed else R.string.nuvio_repo_added_off
            )
            refresh()
            // Prefetch scraper code so the first play is not slowed by it.
            val repo = NuvioRepository.getRepos(ctx)
                .firstOrNull { it.manifestUrl == url } ?: return@launch
            repo.manifest()?.scrapers?.forEach { scraper ->
                runCatching { NuvioRepository.fetchCode(ctx, repo, scraper) }
            }
        }
    }

    private fun confirmRemove(repo: StoredNuvioRepo) {
        val ctx = requireContext()
        val name = repo.manifest()?.name ?: repo.manifestUrl
        AlertDialog.Builder(ctx)
            .setTitle(R.string.nuvio_repo_remove_title)
            .setMessage(getString(R.string.nuvio_repo_remove_message, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                NuvioRepository.removeRepo(ctx, repo.manifestUrl)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- scraper settings ----

    private fun openScraperSettings(repo: StoredNuvioRepo, scraper: NuvioScraperInfo) {
        val ctx = requireContext()
        val loading = AlertDialog.Builder(ctx)
            .setMessage(R.string.nuvio_settings_loading)
            .setCancelable(false)
            .create()
        loading.show()
        lifecycleScope.launch {
            val code = runCatching {
                NuvioRepository.fetchCode(ctx, repo, scraper)
            }.getOrNull()
            val saved = NuvioRepository.scraperSettings(ctx, repo.manifestUrl, scraper.id)
            val fields = if (code == null) {
                null
            } else {
                runCatching {
                    NuvioEngine.fetchSettingsLayout(
                        ctx, repo.manifestUrl, scraper, code, saved,
                        NuvioTmdb.apiKey(ctx)
                    )
                }.getOrNull()
            }
            loading.dismiss()
            if (!isAdded) return@launch
            when {
                fields == null -> toast(R.string.nuvio_settings_load_failed)
                fields.none { it.key.isNotBlank() } ->
                    toast(R.string.nuvio_settings_no_fields)
                else -> showSettingsForm(repo, scraper, fields, saved)
            }
        }
    }

    private fun showSettingsForm(
        repo: StoredNuvioRepo,
        scraper: NuvioScraperInfo,
        fields: List<NuvioSettingsField>,
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

        fun currentRaw(field: NuvioSettingsField): Any? {
            if (saved.has(field.key) && !saved.isNull(field.key)) {
                return saved.opt(field.key)
            }
            return field.defaultRaw
        }

        for (field in fields) {
            when (field.type) {
                "header" -> {
                    column.addView(TextView(ctx).apply {
                        text = field.label
                        setTextAppearance(android.R.style.TextAppearance_Material_Headline)
                        textSize = 16f
                        setPadding(0, dp(12), 0, dp(4))
                    })
                    field.description?.let { desc ->
                        column.addView(TextView(ctx).apply { text = desc })
                    }
                }
                "info" -> {
                    val text = listOf(field.label, field.description)
                        .filter { !it.isNullOrBlank() }
                        .joinToString("\n")
                    if (text.isNotBlank()) {
                        column.addView(TextView(ctx).apply {
                            this.text = text
                            setPadding(0, dp(6), 0, dp(6))
                        })
                    }
                }
                "toggle" -> {
                    if (field.key.isBlank()) continue
                    val raw = currentRaw(field)
                    val switch = SwitchCompat(ctx).apply {
                        text = field.label.ifBlank { field.key }
                        isChecked = when (raw) {
                            is Boolean -> raw
                            is String -> raw.equals("true", ignoreCase = true)
                            else -> false
                        }
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
                    if (field.key.isBlank() || field.options.isEmpty()) continue
                    column.addView(TextView(ctx).apply {
                        text = field.label.ifBlank { field.key }
                        setPadding(0, dp(10), 0, 0)
                    })
                    val raw = currentRaw(field)?.toString()
                    val spinner = Spinner(ctx).apply {
                        adapter = ArrayAdapter(
                            ctx,
                            android.R.layout.simple_spinner_dropdown_item,
                            field.options.map { it.first }
                        )
                        val index = field.options.indexOfFirst { it.second == raw }
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
                            values.put(key, options[index].second)
                        }
                    }
                }
                else -> { // text / number
                    if (field.key.isBlank()) continue
                    column.addView(TextView(ctx).apply {
                        text = field.label.ifBlank { field.key }
                        setPadding(0, dp(10), 0, 0)
                    })
                    val raw = currentRaw(field)
                    val edit = EditText(ctx).apply {
                        setSingleLine(true)
                        hint = field.placeholder
                        inputType = when {
                            field.isPassword ->
                                InputType.TYPE_CLASS_TEXT or
                                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                            field.type == "number" ->
                                InputType.TYPE_CLASS_NUMBER or
                                    InputType.TYPE_NUMBER_FLAG_DECIMAL or
                                    InputType.TYPE_NUMBER_FLAG_SIGNED
                            else -> InputType.TYPE_CLASS_TEXT
                        }
                        setText(
                            when (raw) {
                                null, JSONObject.NULL -> ""
                                is String -> raw
                                else -> raw.toString()
                            }
                        )
                    }
                    column.addView(edit)
                    field.description?.let { desc ->
                        column.addView(TextView(ctx).apply { text = desc })
                    }
                    val key = field.key
                    val isNumber = field.type == "number"
                    getters += {
                        val text = edit.text?.toString()?.trim().orEmpty()
                        if (isNumber && text.isNotEmpty()) {
                            val number = text.toLongOrNull()
                                ?: text.toDoubleOrNull()
                            if (number != null) {
                                values.put(key, number)
                            } else {
                                values.put(key, text)
                            }
                        } else {
                            values.put(key, text)
                        }
                    }
                }
            }
        }

        val scroll = ScrollView(ctx).apply { addView(column) }
        AlertDialog.Builder(ctx)
            .setTitle(scraper.name)
            .setView(scroll)
            .setPositiveButton(R.string.nuvio_settings_save) { _, _ ->
                getters.forEach { it() }
                NuvioRepository.saveScraperSettings(
                    ctx, repo.manifestUrl, scraper.id, values
                )
                toast(R.string.nuvio_settings_saved)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

// ---- list (repo headers + scraper rows in one RecyclerView) ----

private sealed interface NuvioRow {
    data class RepoHeader(
        val repo: StoredNuvioRepo,
        val name: String,
        val collapsed: Boolean,
    ) : NuvioRow
    data class ScraperRow(
        val repo: StoredNuvioRepo,
        val scraper: NuvioScraperInfo,
        val enabled: Boolean,
    ) : NuvioRow
}

private class NuvioRepoAdapter(
    private val onToggle: (StoredNuvioRepo, NuvioScraperInfo, Boolean) -> Unit,
    private val onRefreshRepo: (StoredNuvioRepo) -> Unit,
    private val onRemoveRepo: (StoredNuvioRepo) -> Unit,
    private val onSettings: (StoredNuvioRepo, NuvioScraperInfo) -> Unit,
    private val onToggleCollapse: (StoredNuvioRepo) -> Unit,
    private val onCheckRepo: (StoredNuvioRepo) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<NuvioRow> = emptyList()

    fun submit(repos: List<StoredNuvioRepo>, collapsed: Set<String> = emptySet()) {
        val out = mutableListOf<NuvioRow>()
        for (repo in repos) {
            val manifest = repo.manifest()
            val isCollapsed = collapsed.contains(repo.manifestUrl)
            out += NuvioRow.RepoHeader(
                repo, manifest?.name ?: repo.manifestUrl, isCollapsed
            )
            if (!isCollapsed) {
                manifest?.scrapers?.forEach { scraper ->
                    out += NuvioRow.ScraperRow(
                        repo, scraper, repo.isScraperEnabled(scraper)
                    )
                }
            }
        }
        rows = out
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is NuvioRow.RepoHeader -> TYPE_REPO
        is NuvioRow.ScraperRow -> TYPE_SCRAPER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_REPO) {
            RepoHolder(inflater.inflate(R.layout.item_nuvio_repo, parent, false))
        } else {
            ScraperHolder(inflater.inflate(R.layout.item_nuvio_scraper, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is NuvioRow.RepoHeader -> {
                val h = holder as RepoHolder
                h.name.text = row.name
                h.url.text = row.repo.manifestUrl
                h.expand.rotation = if (row.collapsed) -90f else 0f
                h.itemView.setOnClickListener { onToggleCollapse(row.repo) }
                h.check.setOnClickListener { onCheckRepo(row.repo) }
                h.refresh.setOnClickListener { onRefreshRepo(row.repo) }
                h.remove.setOnClickListener { onRemoveRepo(row.repo) }
            }
            is NuvioRow.ScraperRow -> {
                val h = holder as ScraperHolder
                val scraper = row.scraper
                h.name.text = scraper.name
                val version = h.itemView.context.getString(
                    R.string.nuvio_version_format, scraper.version
                )
                h.desc.text = listOf(scraper.description, version)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                // Circle-masked logos, same treatment as the main
                // Extensions list (PluginAdapter, phase 8.9).
                val logo = scraper.logo
                if (!logo.isNullOrBlank()) {
                    h.logo.visibility = View.VISIBLE
                    h.logo.loadImage(logo) {
                        transformations(CircleCropTransformation())
                        error(
                            getImageFromDrawable(
                                h.itemView.context,
                                R.drawable.ic_baseline_extension_24,
                            )
                        )
                    }
                } else {
                    h.logo.visibility = View.GONE
                }
                h.settings.visibility = if (scraper.hasSettings) View.VISIBLE else View.GONE
                h.settings.setOnClickListener { onSettings(row.repo, scraper) }
                // Validity line (8.68): last load-test result and/or last
                // real fetch outcome for this scraper, when known.
                val validity = NuvioValidity.get(
                    h.itemView.context, row.repo.manifestUrl, scraper.id
                )
                val statusParts = mutableListOf<String>()
                validity?.probe?.let { probe ->
                    statusParts += if (validity.probeOk) "✓ $probe" else "✗ $probe"
                }
                validity?.last?.let { statusParts += "last fetch: $it" }
                if (statusParts.isEmpty()) {
                    h.status.visibility = View.GONE
                } else {
                    h.status.visibility = View.VISIBLE
                    h.status.text = statusParts.joinToString(" · ")
                }
                h.enabled.setOnCheckedChangeListener(null)
                h.enabled.isChecked = row.enabled
                h.enabled.setOnCheckedChangeListener { _, checked ->
                    onToggle(row.repo, scraper, checked)
                }
            }
        }
    }

    private class RepoHolder(view: View) : RecyclerView.ViewHolder(view) {
        val expand: ImageView = view.findViewById(R.id.item_repo_expand)
        val name: TextView = view.findViewById(R.id.item_repo_name)
        val url: TextView = view.findViewById(R.id.item_repo_url)
        val check: ImageButton = view.findViewById(R.id.item_repo_check)
        val refresh: ImageButton = view.findViewById(R.id.item_repo_refresh)
        val remove: ImageButton = view.findViewById(R.id.item_repo_remove)
    }

    private class ScraperHolder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.item_scraper_logo)
        val name: TextView = view.findViewById(R.id.item_scraper_name)
        val desc: TextView = view.findViewById(R.id.item_scraper_desc)
        val status: TextView = view.findViewById(R.id.item_scraper_status)
        val settings: ImageButton = view.findViewById(R.id.item_scraper_settings)
        val enabled: SwitchCompat = view.findViewById(R.id.item_scraper_enabled)
    }

    companion object {
        private const val TYPE_REPO = 0
        private const val TYPE_SCRAPER = 1
    }
}
