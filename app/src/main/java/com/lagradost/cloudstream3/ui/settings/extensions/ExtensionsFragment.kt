package com.lagradost.cloudstream3.ui.settings.extensions

import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.os.Build
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.marginBottom
import androidx.core.view.marginTop
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity.Companion.afterRepositoryLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.AddRepoInputBinding
import com.lagradost.cloudstream3.databinding.FragmentExtensionsBinding
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.mvvm.observeNullable
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setSystemBarsPadding
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setToolBarScrollFlags
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.AppContextUtils.addRepositoryDialog
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import com.lagradost.cloudstream3.utils.UIHelper.hideProgress
import com.lagradost.cloudstream3.utils.UIHelper.showProgress
import com.lagradost.cloudstream3.utils.setText

class ExtensionsFragment : BaseFragment<FragmentExtensionsBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentExtensionsBinding::inflate)
) {

    private val extensionViewModel: ExtensionsViewModel by activityViewModels()
    private val pluginViewModel: PluginsViewModel by activityViewModels()

    // 8.81: the Repositories and Installed lists live inside the
    // ViewPager2 now; the pager creates their views, so the fragment
    // keeps nullable refs plus the latest data for (re)submission.
    private var repoRecycler: RecyclerView? = null
    private var installedRecycler: RecyclerView? = null
    private var installedEmptyView: TextView? = null
    private var latestRepos: List<RepositoryData> = emptyList()
    private var latestPlugins: List<PluginViewData> = emptyList()

    private fun View.setLayoutWidth(weight: Int) {
        val param = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            weight.toFloat()
        )
        this.layoutParams = param
    }

    override fun onResume() {
        super.onResume()
        afterRepositoryLoadedEvent += ::reloadRepositories
    }

    override fun onStop() {
        super.onStop()
        afterRepositoryLoadedEvent -= ::reloadRepositories
    }

    private fun reloadRepositories(success: Boolean = true) {
        extensionViewModel.loadStats()
        extensionViewModel.loadRepositories()
    }

    /**
     * 8.81: Repositories tab page setup. This is the pre-8.81
     * repoRecyclerView wiring, unchanged — it just runs on the
     * pager's page view instead of a binding field.
     */
    private fun setupRepoPage(list: RecyclerView) {
        repoRecycler = list
        // BaseFragment.binding is nullable outside onBindingCreated.
        val fragmentBinding = binding ?: return
        list.apply {
            setLinearListLayout(
                isHorizontal = false,
                nextUp = R.id.settings_toolbar, // FOCUS_SELF, // back has no id so we cant :pensive:
                nextDown = R.id.plugin_storage_appbar,
                nextRight = FOCUS_SELF,
                nextLeft = R.id.nav_rail_view
            )

            if (!isLayout(TV))
                fragmentBinding.addRepoButton.let { button ->
                    button.post {
                        setPadding(
                            paddingLeft,
                            paddingTop,
                            paddingRight,
                            button.measuredHeight + button.marginTop + button.marginBottom
                        )
                    }
                }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                    val dy = scrollY - oldScrollY
                    if (dy > 0) { // check for scroll down
                        fragmentBinding.addRepoButton.shrink() // hide
                    } else if (dy < -5) {
                        fragmentBinding.addRepoButton.extend() // show
                    }
                }
            }
            adapter = RepoAdapter(false, {
                findNavController().navigate(
                    R.id.navigation_settings_extensions_to_navigation_settings_plugins,
                    PluginsFragment.newInstance(it)
                )
            }, { repo ->
                // Prompt user before deleting repo
                main {
                    val uiContext = context ?: fragmentBinding.root.context
                    val builder = AlertDialog.Builder(uiContext, R.style.AlertDialogCustom)
                    val dialogClickListener =
                        DialogInterface.OnClickListener { _, which ->
                            when (which) {
                                DialogInterface.BUTTON_POSITIVE -> {
                                    ioSafe {
                                        RepositoryManager.removeRepository(
                                            uiContext.applicationContext,
                                            repo
                                        )
                                        extensionViewModel.loadStats()
                                        extensionViewModel.loadRepositories()
                                    }
                                }

                                DialogInterface.BUTTON_NEGATIVE -> {}
                            }
                        }

                    builder.setTitle(R.string.delete_repository)
                        .setMessage(uiContext.getString(R.string.delete_repository_plugins))
                        .setPositiveButton(R.string.delete, dialogClickListener)
                        .setNegativeButton(R.string.cancel, dialogClickListener)
                        .show().setDefaultFocus()
                }
            })
        }
        (list.adapter as? RepoAdapter)?.submitList(latestRepos)
        list.isVisible = latestRepos.isNotEmpty()
    }

    /**
     * 8.81: Installed tab page setup — all downloaded plugins across
     * repositories, using the same PluginAdapter rows and the same
     * handlePluginAction flow (download/delete, details sheet,
     * settings gear) as the search results list.
     */
    private fun setupInstalledPage(root: View) {
        val list = root.findViewById<RecyclerView>(R.id.installed_recycler_view)
        installedRecycler = list
        installedEmptyView = root.findViewById(R.id.installed_empty_view)
        list.apply {
            setLinearListLayout(
                isHorizontal = false,
                nextDown = FOCUS_SELF,
                nextRight = FOCUS_SELF,
            )
            setRecycledViewPool(PluginAdapter.sharedPool)
            adapter =
                PluginAdapter(true) {
                    val urls = extensionViewModel.repositories.value?.toList() ?: emptyList()
                    pluginViewModel.handlePluginAction(activity, urls, it, false)
                }
        }
        submitInstalledPlugins()
    }

    private fun submitInstalledPlugins() {
        val list = installedRecycler ?: return
        val pluginAdapter = list.adapter as? PluginAdapter ?: return
        // Grouped by repository (the rows show the repository name),
        // alphabetical inside each repository.
        val installed = latestPlugins
            .filter { it.isDownloaded }
            .sortedWith(
                compareBy(
                    { it.pluginWrapper.repositoryData.name.lowercase() },
                    { it.pluginWrapper.plugin.name.lowercase() },
                )
            )
        pluginAdapter.submitList(installed)
        installedEmptyView?.isVisible = installed.isEmpty()
    }

    override fun fixLayout(view: View) {
        setSystemBarsPadding()
    }

    override fun onBindingCreated(binding: FragmentExtensionsBinding) {
        setUpToolbar(R.string.extensions)
        setToolBarScrollFlags()

        // Phase 8.10: on phone the floating nav pill overlays the bottom
        // 88dp (70dp pill + 18dp margin — Phase 7.5 let content run
        // full-height under it), and this screen's bottom-anchored storage
        // bar, with its "Downloaded / Disabled / Not downloaded" legend,
        // rested half behind the pill. Lift the bar and everything stacked
        // on it by the pill zone, restoring the pre-overlay geometry
        // pixel-for-pixel: bar at 88–168dp from the bottom, both lists
        // ending on its top edge, the Add-repository FAB floating just
        // above it. Gated to PHONE in code because this layout is shared —
        // TV/emulator use the rail and keep the XML geometry untouched
        // (the same pattern as BasePreferenceFragmentCompat's clearance).
        if (isLayout(PHONE)) {
            val pillZone = 88.toPx
            (binding.pluginStorageAppbar.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = pillZone
                binding.pluginStorageAppbar.layoutParams = lp
            }
            // 8.81: the pager (which hosts both tab lists) takes the
            // repo list's place in this lift; geometry is unchanged.
            for (list in listOf(binding.extensionsPager, binding.pluginRecyclerView)) {
                (list.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.bottomMargin += pillZone
                    list.layoutParams = lp
                }
            }
            binding.addRepoButton.translationY = (-168).toPx.toFloat()
        }

        // 8.81: two tabs — Installed (page 0) and Repositories
        // (page 1, selected by default: the view this screen has
        // always opened on). Page views are created by the pager;
        // each page's setup runs in setupInstalledPage/setupRepoPage.
        binding.extensionsPager.offscreenPageLimit = 1
        binding.extensionsPager.adapter =
            object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount(): Int = 2

                override fun getItemViewType(position: Int): Int = position

                override fun onCreateViewHolder(
                    parent: ViewGroup,
                    viewType: Int,
                ): RecyclerView.ViewHolder {
                    val layoutRes = if (viewType == 0) {
                        R.layout.extensions_tab_installed
                    } else {
                        R.layout.extensions_tab_repos
                    }
                    val page = LayoutInflater.from(parent.context)
                        .inflate(layoutRes, parent, false)
                    page.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    return object : RecyclerView.ViewHolder(page) {}
                }

                override fun onBindViewHolder(
                    holder: RecyclerView.ViewHolder,
                    position: Int,
                ) {
                    if (position == 0) {
                        setupInstalledPage(holder.itemView)
                    } else {
                        setupRepoPage(holder.itemView as RecyclerView)
                    }
                }
            }
        TabLayoutMediator(binding.extensionsTabs, binding.extensionsPager) { tab, position ->
            tab.setText(
                if (position == 0) R.string.extensions_tab_installed
                else R.string.extensions_tab_repositories
            )
        }.attach()
        binding.extensionsPager.setCurrentItem(1, false)

        observe(extensionViewModel.repositories) { repos ->
            latestRepos = repos.toList()
            repoRecycler?.isVisible = repos.isNotEmpty()
            binding.blankRepoScreen.isVisible = repos.isEmpty()
            (repoRecycler?.adapter as? RepoAdapter)?.submitList(repos.toList())
            pluginViewModel.updatePluginList(binding.root.context, repos.toList())
        }

        observeNullable(extensionViewModel.pluginStats) { value ->
            binding.apply {
                if (value == null) {
                    pluginStorageAppbar.isVisible = false
                    return@observeNullable
                }

                pluginStorageAppbar.isVisible = true
                if (value.total == 0) {
                    pluginDownload.setLayoutWidth(1)
                    pluginDisabled.setLayoutWidth(0)
                    pluginNotDownloaded.setLayoutWidth(0)
                } else {
                    pluginDownload.setLayoutWidth(value.downloaded)
                    pluginDisabled.setLayoutWidth(value.disabled)
                    pluginNotDownloaded.setLayoutWidth(value.notDownloaded)
                }
                pluginNotDownloadedTxt.setText(value.notDownloadedText)
                pluginDisabledTxt.setText(value.disabledText)
                pluginDownloadTxt.setText(value.downloadedText)
            }
        }

        binding.pluginStorageAppbar.setOnClickListener {
            findNavController().navigate(
                R.id.navigation_settings_extensions_to_navigation_settings_plugins,
                PluginsFragment.newLocalInstance(
                    getString(R.string.extensions),
                )
            )
        }

        binding.pluginRecyclerView.apply {
            setLinearListLayout(
                isHorizontal = false,
                nextDown = FOCUS_SELF,
                nextRight = FOCUS_SELF,
            )
            setRecycledViewPool(PluginAdapter.sharedPool)
            adapter =
                PluginAdapter(true) {
                    val urls = extensionViewModel.repositories.value?.toList() ?: emptyList()
                    pluginViewModel.handlePluginAction(activity, urls, it, false)
                }
        }

        observe(pluginViewModel.filteredPlugins) { (scrollToTop, list) ->
            latestPlugins = list
            (binding.pluginRecyclerView.adapter as? PluginAdapter)?.submitList(list)
            submitInstalledPlugins()
            if (scrollToTop) {
                binding.pluginRecyclerView.scrollToPosition(0)
            }
        }

        binding.settingsToolbar.apply {
            val searchItem = menu?.findItem(R.id.search_button)
            val searchView = searchItem?.actionView as? SearchView

            searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionCollapse(p0: MenuItem): Boolean {
                    binding.pluginRecyclerView.isVisible = false
                    // 8.81: the pager stands in for the repo list here.
                    binding.extensionsPager.isVisible = true
                    return true

                }

                override fun onMenuItemActionExpand(p0: MenuItem): Boolean {
                    binding.pluginRecyclerView.isVisible = true
                    binding.extensionsPager.isVisible = false
                    return true
                }
            })

            // Don't go back if active query
            setNavigationOnClickListener {
                if (searchView?.isIconified == false) {
                    searchView.isIconified = true
                } else {
                    dispatchBackPressed()
                }
            }

            searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    pluginViewModel.search(query)
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    pluginViewModel.search(newText)
                    return true
                }
            })
        }


        val addRepositoryClick = View.OnClickListener {
            val ctx = context ?: return@OnClickListener
            val binding = AddRepoInputBinding.inflate(LayoutInflater.from(ctx), null, false)
            val builder =
                AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                    .setView(binding.root)

            val dialog = builder.create()
            dialog.show()
            (activity?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip?.getItemAt(
                0
            )?.text?.toString()?.let { copiedText ->
                if (copiedText.contains(RepoAdapter.SHAREABLE_REPO_SEPARATOR)) {
                    // text is of format <repository name> : <repository url>
                    val (name, url) = copiedText.split(
                        RepoAdapter.SHAREABLE_REPO_SEPARATOR,
                        limit = 2
                    )
                    binding.repoUrlInput.setText(url.trim())
                    binding.repoNameInput.setText(name.trim())
                } else {
                    binding.repoUrlInput.setText(copiedText)
                }
            }

            binding.applyBtt.setOnClickListener secondListener@{
                val name = binding.repoNameInput.text?.toString()
                val urlInput = binding.repoUrlInput.text?.toString()
                if (urlInput.isNullOrEmpty()) {
                    showToast(R.string.error_invalid_url, Toast.LENGTH_SHORT)
                    return@secondListener
                }
                binding.applyBtt.showProgress()
                ioSafe {
                    try {
                        val url = RepositoryManager.parseRepoUrl(urlInput)
                        if (url.isNullOrBlank()) {
                            showToast(R.string.error_invalid_data, Toast.LENGTH_SHORT)
                            return@ioSafe
                        }
                        val repository = RepositoryManager.parseRepository(url)

                        // Exit if wrong repository
                        if (repository == null) {
                            showToast(R.string.no_repository_found_error, Toast.LENGTH_LONG)
                            return@ioSafe
                        }

                        val fixedName = if (!name.isNullOrBlank()) name
                        else repository.name
                        val newRepo = RepositoryData(repository.iconUrl, fixedName, url)
                        RepositoryManager.addRepository(newRepo)
                        extensionViewModel.loadStats()
                        extensionViewModel.loadRepositories()

                        dialog.dismissSafe(activity) // Only dismiss if the repo was added

                        val plugins = RepositoryManager.getRepoPlugins(newRepo)
                        if (plugins.isNullOrEmpty()) {
                            showToast(R.string.no_plugins_found_error, Toast.LENGTH_LONG)
                            return@ioSafe
                        }

                        this@ExtensionsFragment.activity?.addRepositoryDialog(
                            newRepo
                        )
                    } finally {
                        binding.applyBtt.hideProgress()
                    }
                }
            }
            binding.cancelBtt.setOnClickListener {
                dialog.dismissSafe(activity)
            }
        }


        val isTv = isLayout(TV)
        binding.apply {
            addRepoButton.isGone = isTv
            addRepoButtonImageviewHolder.isVisible = isTv

            // Band-aid for Fire TV
            pluginStorageAppbar.isFocusableInTouchMode = isTv
            addRepoButtonImageview.isFocusableInTouchMode = isTv

            addRepoButton.setOnClickListener(addRepositoryClick)
            addRepoButtonImageview.setOnClickListener(addRepositoryClick)
        }
        reloadRepositories()
    }
}
