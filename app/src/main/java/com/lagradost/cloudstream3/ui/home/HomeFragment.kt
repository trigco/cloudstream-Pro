package com.lagradost.cloudstream3.ui.home

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.navigation.fragment.findNavController
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.preference.PreferenceManager
import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FragmentHomeBinding
import com.lagradost.cloudstream3.databinding.HomeEpisodesExpandedBinding
import com.lagradost.cloudstream3.databinding.HomeSelectMainpageBinding
import com.lagradost.cloudstream3.databinding.TvtypesChipsBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.mvvm.observeNullable
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi
import com.lagradost.cloudstream3.ui.APIRepository.Companion.randomApi
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.SectionEntranceAnimator
import com.lagradost.cloudstream3.ui.search.TitleResolver
import com.lagradost.cloudstream3.ui.account.AccountHelper.showAccountSelectLinear
import com.lagradost.cloudstream3.ui.account.AccountViewModel
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchHelper.handleSearchClickCallback
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.hapticTick
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.isNetworkAvailable
import com.lagradost.cloudstream3.utils.AppContextUtils.isRecyclerScrollable
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.AppContextUtils.openBrowser
import com.lagradost.cloudstream3.utils.AppContextUtils.ownHide
import com.lagradost.cloudstream3.utils.AppContextUtils.ownShow
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.FamilyMode
import com.lagradost.cloudstream3.utils.EmptyEvent
import com.lagradost.cloudstream3.utils.SubtitleHelper.getFlagFromIso
import com.lagradost.cloudstream3.utils.TvChannelUtils
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getResourceColor
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.UIHelper.popupMenuNoIconsAndNoStringRes
import com.lagradost.cloudstream3.utils.UIHelper.toPx

private const val TAG = "HomeFragment"

class HomeFragment : BaseFragment<FragmentHomeBinding>(
    BindingCreator.Bind(FragmentHomeBinding::bind)
) {
    companion object {
        // Used for configuration changed events to fix any popups that are not attached to a fragment
        val configEvent = EmptyEvent()
        var currentSpan = 1

        private val errorProfilePics = listOf(
            R.drawable.monke_benene,
            R.drawable.monke_burrito,
            R.drawable.monke_coco,
            R.drawable.monke_cookie,
            R.drawable.monke_flusdered,
            R.drawable.monke_funny,
            R.drawable.monke_like,
            R.drawable.monke_party,
            R.drawable.monke_sob,
            R.drawable.monke_drink,
        )

        val errorProfilePic = errorProfilePics.random()

        fun Context.getDisplayName(apiName: String?): String? {
            return when (apiName) {
                noneApi.name -> getString(R.string.none)
                randomApi.name -> getString(R.string.home_random)
                else -> apiName
            }
        }

        //fun Activity.loadHomepageList(
        //    item: HomePageList,
        //    deleteCallback: (() -> Unit)? = null,
        //) {
        //    loadHomepageList(
        //        expand = HomeViewModel.ExpandableHomepageList(item, 1, false),
        //        deleteCallback = deleteCallback,
        //        expandCallback = null
        //    )
        //}

        // returns a BottomSheetDialog that will be hidden with OwnHidden upon hide, and must be saved to be able call ownShow in onCreateView

        fun Activity.loadHomepageList(
            expand: HomeViewModel.ExpandableHomepageList,
            deleteCallback: (() -> Unit)? = null,
            expandCallback: (suspend (String) -> HomeViewModel.ExpandableHomepageList?)? = null,
            dismissCallback: (() -> Unit),
        ): BottomSheetDialog {
            val context = this
            val bottomSheetDialogBuilder = BottomSheetDialog(context)
            val binding: HomeEpisodesExpandedBinding = HomeEpisodesExpandedBinding.inflate(
                bottomSheetDialogBuilder.layoutInflater,
                null,
                false
            )
            bottomSheetDialogBuilder.setContentView(binding.root)
            //val title = bottomSheetDialogBuilder.findViewById<TextView>(R.id.home_expanded_text)!!

            //title.findViewTreeLifecycleOwner().lifecycle.addObserver()

            val item = expand.list
            binding.homeExpandedText.text = item.name
            // val recycle =
            //    bottomSheetDialogBuilder.findViewById<AutofitRecyclerView>(R.id.home_expanded_recycler)!!
            //val titleHolder =
            //    bottomSheetDialogBuilder.findViewById<FrameLayout>(R.id.home_expanded_drag_down)!!

            // main {
            //(bottomSheetDialogBuilder.ownerActivity as androidx.fragment.app.FragmentActivity?)?.supportFragmentManager?.fragments?.lastOrNull()?.viewLifecycleOwner?.apply {
            //    println("GOT LIFE: lifecycle $this")
            //    this.lifecycle.addObserver(object : DefaultLifecycleObserver {
            //        override fun onResume(owner: LifecycleOwner) {
            //            super.onResume(owner)
            //            println("onResume!!!!")
            //            bottomSheetDialogBuilder?.ownShow()
            //        }

            //        override fun onStop(owner: LifecycleOwner) {
            //            super.onStop(owner)
            //            bottomSheetDialogBuilder?.ownHide()
            //        }
            //    })
            //}
            // }
            //val delete = bottomSheetDialogBuilder.home_expanded_delete
            binding.homeExpandedDelete.isGone = deleteCallback == null
            if (deleteCallback != null) {
                binding.homeExpandedDelete.setOnClickListener {
                    try {
                        val builder: AlertDialog.Builder = AlertDialog.Builder(context)
                        val dialogClickListener =
                            DialogInterface.OnClickListener { _, which ->
                                when (which) {
                                    DialogInterface.BUTTON_POSITIVE -> {
                                        deleteCallback.invoke()
                                        bottomSheetDialogBuilder.dismissSafe(this)
                                    }

                                    DialogInterface.BUTTON_NEGATIVE -> {}
                                }
                            }

                        builder.setTitle(R.string.clear_history)
                            .setMessage(
                                context.getString(R.string.delete_message).format(
                                    item.name
                                )
                            )
                            .setPositiveButton(R.string.delete, dialogClickListener)
                            .setNegativeButton(R.string.cancel, dialogClickListener)
                            .show().setDefaultFocus()
                    } catch (e: Exception) {
                        logError(e)
                        // ye you somehow fucked up formatting did you?
                    }
                }
            }
            binding.homeExpandedDragDown.setOnClickListener {
                bottomSheetDialogBuilder.dismissSafe(this)
            }


            // Span settings
            binding.homeExpandedRecycler.spanCount = context.getSpanCount(item.isHorizontalImages)
            binding.homeExpandedRecycler.setRecycledViewPool(SearchAdapter.sharedPool)
            binding.homeExpandedRecycler.adapter =
                SearchAdapter(binding.homeExpandedRecycler,item.isHorizontalImages) { callback ->
                    handleSearchClickCallback(callback)
                    if (callback.action == SEARCH_ACTION_LOAD || callback.action == SEARCH_ACTION_PLAY_FILE) {
                        bottomSheetDialogBuilder.ownHide() // we hide here because we want to resume it later
                        //bottomSheetDialogBuilder.dismissSafe(this)
                    }
                }.apply {
                    submitList(item.list)
                    hasNext = expand.hasNext
                }

            binding.homeExpandedRecycler.addOnScrollListener(object :
                RecyclerView.OnScrollListener() {
                var expandCount = 0
                val name = expand.list.name

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)

                    val adapter = recyclerView.adapter
                    if (adapter !is SearchAdapter) return

                    val count = adapter.itemCount
                    val currentHasNext = adapter.hasNext
                    //!recyclerView.canScrollVertically(1)
                    if (!recyclerView.isRecyclerScrollable() && currentHasNext && expandCount != count) {
                        expandCount = count
                        ioSafe {
                            expandCallback?.invoke(name)?.let { newExpand ->
                                (recyclerView.adapter as? SearchAdapter?)?.apply {
                                    hasNext = newExpand.hasNext
                                    submitList(newExpand.list.list)
                                }
                            }
                        }
                    }
                }
            })

            val spanListener = Runnable {
                binding.homeExpandedRecycler.spanCount = context.getSpanCount(item.isHorizontalImages)
                // We want to rebind everything to update the UI, however we also want to avoid
                // any animations ect, this is the easiest way to do this, and the most correct
                @SuppressLint("NotifyDataSetChanged")
                binding.homeExpandedRecycler.adapter?.notifyDataSetChanged()
            }

            configEvent += spanListener

            bottomSheetDialogBuilder.setOnDismissListener {
                dismissCallback.invoke()
                configEvent -= spanListener
            }

            //(recycle.adapter as SearchAdapter).notifyDataSetChanged()

            bottomSheetDialogBuilder.show()
            return bottomSheetDialogBuilder
        }

        private fun getPairList(
            anime: Chip?,
            cartoons: Chip?,
            tvs: Chip?,
            docs: Chip?,
            movies: Chip?,
            asian: Chip?,
            livestream: Chip?,
            torrent: Chip?,
            nsfw: Chip?,
            others: Chip?,
        ): List<Pair<Chip?, List<TvType>>> {
            // This list should be same order as home screen to aid navigation
            return listOf(
                Pair(movies, listOf(TvType.Movie)),
                Pair(tvs, listOf(TvType.TvSeries)),
                Pair(anime, listOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie)),
                Pair(asian, listOf(TvType.AsianDrama)),
                Pair(cartoons, listOf(TvType.Cartoon)),
                Pair(docs, listOf(TvType.Documentary)),
                Pair(livestream, listOf(TvType.Live)),
                Pair(torrent, listOf(TvType.Torrent)),
                Pair(nsfw, listOf(TvType.NSFW)),
                Pair(others, listOf(TvType.Others)),
            )
        }

        private fun getPairList(header: TvtypesChipsBinding) = getPairList(
            header.homeSelectAnime,
            header.homeSelectCartoons,
            header.homeSelectTvSeries,
            header.homeSelectDocumentaries,
            header.homeSelectMovies,
            header.homeSelectAsian,
            header.homeSelectLivestreams,
            header.homeSelectTorrents,
            header.homeSelectNsfw,
            header.homeSelectOthers
        )

        fun validateChips(header: TvtypesChipsBinding?, validTypes: List<TvType>) {
            if (header == null) return
            val pairList = getPairList(header)
            for ((button, types) in pairList) {
                val isValid = validTypes.any { types.contains(it) }
                button?.isVisible = isValid
            }
        }

        fun updateChips(header: TvtypesChipsBinding?, selectedTypes: List<TvType>) {
            if (header == null) return
            val pairList = getPairList(header)
            for ((button, types) in pairList) {
                button?.isChecked =
                    button.isVisible && selectedTypes.any { types.contains(it) }
            }
        }

        fun bindChips(
            header: TvtypesChipsBinding?,
            selectedTypes: List<TvType>,
            validTypes: List<TvType>,
            callback: (List<TvType>) -> Unit
        ) {
            bindChips(header, selectedTypes, validTypes, callback, null, null)
        }

        fun bindChips(
            header: TvtypesChipsBinding?,
            selectedTypes: List<TvType>,
            validTypes: List<TvType>,
            callback: (List<TvType>) -> Unit,
            nextFocusDown: Int?,
            nextFocusUp: Int?
        ) {
            if (header == null) return
            val pairList = getPairList(header)
            for ((button, types) in pairList) {
                val isValid = validTypes.any { types.contains(it) }
                button?.isVisible = isValid
                button?.isChecked = isValid && selectedTypes.any { types.contains(it) }
                button?.isFocusable = true
                if (isLayout(TV)) {
                    button?.isFocusableInTouchMode = true
                }

                if (nextFocusDown != null)
                    button?.nextFocusDownId = nextFocusDown

                if (nextFocusUp != null)
                    button?.nextFocusUpId = nextFocusUp

                button?.setOnCheckedChangeListener { _, _ ->
                    val list = ArrayList<TvType>()
                    for ((sbutton, vvalidTypes) in pairList) {
                        if (sbutton?.isChecked == true)
                            list.addAll(vvalidTypes)
                    }
                    callback(list)
                }
            }
        }

        fun Context.selectHomepage(
            selectedApiName: String?,
            onCustomSelect: ((HomeCustomLists.CustomList) -> Unit)? = null,
            callback: (String) -> Unit
        ) {
            val validAPIs = filterProviderByPreferredMedia().toMutableList()

            validAPIs.add(0, randomApi)
            validAPIs.add(0, noneApi)
            //val builder: AlertDialog.Builder = AlertDialog.Builder(this)
            //builder.setView(R.layout.home_select_mainpage)
            val builder =
                BottomSheetDialog(this)

            builder.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            val binding: HomeSelectMainpageBinding = HomeSelectMainpageBinding.inflate(
                builder.layoutInflater,
                null,
                false
            )

            builder.setContentView(binding.root)
            // Phase 8.47: his custom lists are appended to this
            // same picker (his request) so providers and custom
            // lists are managed in one place.
            val customLists = HomeCustomLists.getLists(this)
            builder.show()
            builder.let { dialog ->
                val isMultiLang = getApiProviderLangSettings().let { set ->
                    set.size > 1 || set.contains(AllLanguagesName)
                }
                //dialog.window?.setGravity(Gravity.BOTTOM)

                var currentApiName = selectedApiName

                var currentValidApis: MutableList<MainAPI> = mutableListOf()
                val preSelectedTypes = DataStoreHelper.homePreference.toMutableList()

                binding.cancelBtt.setOnClickListener {
                    dialog.dismissSafe()
                }

                binding.applyBtt.setOnClickListener {
                    if (currentApiName != selectedApiName) {
                        currentApiName?.let(callback)
                    }
                    dialog.dismissSafe()
                }

                var pinnedphashset = DataStoreHelper.pinnedProviders.toHashSet()

                val listView = dialog.findViewById<ListView>(R.id.listview1)

                val arrayAdapter = object : ArrayAdapter<String>(
                    this, R.layout.sort_bottom_single_provider_choice,
                    mutableListOf()
                ) {
                    override fun getView(
                        position: Int,
                        convertView: View?,
                        parent: ViewGroup
                    ): View {
                        val view = convertView ?: LayoutInflater.from(context)
                            .inflate(R.layout.sort_bottom_single_provider_choice, parent, false)
                        val titleText = view.findViewById<TextView>(R.id.text1)
                        val pinIcon = view.findViewById<ImageView>(R.id.pinicon)
                        val settingsIcon = view.findViewById<ImageView>(R.id.action_settings)

                        val name = getItem(position)
                        titleText?.text = name

                        // Phase 8.11-E1: the row's CheckLabel check is a static
                        // compound drawable whose stateful tint never
                        // discriminated on device (every row rendered
                        // checked). Drive it from the ListView's checked
                        // state explicitly instead — updateList() seeds it
                        // via setItemChecked before filling the adapter, and
                        // both branches run on every bind so recycled rows
                        // never keep a stale check. Same explicit-tint
                        // pattern as SingleSelectionHelper's IconArrayAdapter.
                        val isChecked =
                            (parent as? ListView)?.isItemChecked(position) == true
                        if (isChecked) {
                            val checkDrawable = ContextCompat.getDrawable(
                                view.context, R.drawable.ic_baseline_check_24_listview
                            )?.mutate()
                            if (checkDrawable != null) {
                                DrawableCompat.setTint(
                                    checkDrawable,
                                    view.context.getResourceColor(R.attr.textColor)
                                )
                                titleText?.setCompoundDrawablesRelativeWithIntrinsicBounds(
                                    checkDrawable, null, null, null
                                )
                            }
                            titleText?.setTextColor(
                                view.context.getResourceColor(R.attr.textColor)
                            )
                        } else {
                            titleText?.setCompoundDrawablesRelativeWithIntrinsicBounds(
                                0, 0, 0, 0
                            )
                            titleText?.setTextColor(
                                view.context.getResourceColor(R.attr.grayTextColor)
                            )
                        }

                        val providerApi =
                            currentValidApis.getOrNull(position)
                        val isPinned =
                            providerApi != null &&
                                pinnedphashset.contains(providerApi.name)
                        pinIcon.visibility = if (isPinned) View.VISIBLE else View.GONE

                        val pluginInstance = providerApi?.sourcePlugin?.let { PluginManager.plugins[it] } as? Plugin
                        val isDownloadedPluginWithSettings = pluginInstance?.openSettings != null && !isLayout(TV)

                        settingsIcon.visibility = if (isDownloadedPluginWithSettings) View.VISIBLE else View.GONE
                        if (isDownloadedPluginWithSettings) {
                            settingsIcon.setOnClickListener {
                                try {
                                    val activityContext = it.context.getActivity() ?: it.context
                                    pluginInstance.openSettings?.invoke(activityContext)
                                } catch (e: Throwable) {
                                    logError(e)
                                }
                            }
                        }

                        return view
                    }
                }
                listView?.adapter = arrayAdapter
                listView?.choiceMode = AbsListView.CHOICE_MODE_SINGLE

                listView?.setOnItemClickListener { _, _, i, _ ->
                    if (i in currentValidApis.indices) {
                        currentApiName = currentValidApis[i].name
                        //to switch to apply simply remove this
                        currentApiName.let(callback)
                        dialog.dismissSafe()
                    } else if (onCustomSelect != null) {
                        val list = customLists.getOrNull(
                            i - currentValidApis.size
                        )
                        if (list != null) {
                            onCustomSelect(list)
                            dialog.dismissSafe()
                        }
                    }
                }

                fun updateList() {
                    DataStoreHelper.homePreference = preSelectedTypes
                    val pinnedp = DataStoreHelper.pinnedProviders.toList()
                    pinnedphashset = pinnedp.toHashSet()
                    arrayAdapter.clear()
                    val sortedApis = validAPIs
                        .filter {
                            val isPinned = pinnedphashset.contains(it.name)

                            // Hide pinned NSFW when NSFW not selected. NSFW is distracting when not chosen.
                            if (isPinned && !preSelectedTypes.contains(TvType.NSFW)) {
                                if (it.supportedTypes.all { type -> type == TvType.NSFW }) return@filter false
                            }

                            it.hasMainPage && (isPinned || it.supportedTypes.any(
                                preSelectedTypes::contains
                            ))
                        }
                        .sortedBy { it.name.lowercase() }

                    val sortedApiMap = LinkedHashMap<String, MainAPI>().apply {
                        sortedApis.forEach { put(it.name, it) }
                    }

                    val pinnedApis = pinnedp.asReversed().mapNotNull { name ->
                        sortedApiMap[name]
                    }

                    val remainingApis = sortedApis.filterNot { pinnedphashset.contains(it.name) }

                    currentValidApis = mutableListOf<MainAPI>().apply {
                        addAll(validAPIs.take(2))
                        addAll(pinnedApis)
                        addAll(remainingApis)
                    }

                    // Phase 8.50: Family Mode hides adult providers
                    // from the picker entirely.
                    if (FamilyMode.isEnabled()) {
                        currentValidApis.removeAll {
                            FamilyMode.isAdultApi(it)
                        }
                    }

                    val names = currentValidApis.map {
                        val displayName = getDisplayName(it.name)
                        if (isMultiLang) "${getFlagFromIso(it.lang)?.plus(" ") ?: ""}$displayName" else displayName
                    }
                    val customNames = customLists.map { "\u2605 " + it.name }
                    var index = currentValidApis.map { it.name }
                        .indexOf(currentApiName)
                    val activeCustomPos =
                        HomeCustomLists.activeList(this@selectHomepage)
                            ?.let { active ->
                                customLists.indexOfFirst {
                                    it.id == active.id
                                }
                            } ?: -1
                    if (activeCustomPos >= 0) {
                        index = currentValidApis.size + activeCustomPos
                    }
                    listView?.setItemChecked(index, true)
                    arrayAdapter.addAll(names + customNames)
                    arrayAdapter.notifyDataSetChanged()
                }
                // pin provider on hold
                listView?.setOnItemLongClickListener { _, _, i, _ ->
                    if (currentValidApis.isNotEmpty() && i > 1 &&
                        i in currentValidApis.indices
                    ) {
                        val pinnedp = DataStoreHelper.pinnedProviders.toMutableList()
                        val thisapi = currentValidApis[i].name
                        if (pinnedp.contains(thisapi)) {
                            pinnedp.remove(thisapi)
                        } else {
                            pinnedp.add(thisapi)
                        }
                        DataStoreHelper.pinnedProviders = pinnedp.toTypedArray()
                        updateList()
                    }
                    true
                }

                bindChips(
                    binding.tvtypesChipsScroll.tvtypesChips,
                    preSelectedTypes,
                    validAPIs.flatMap { it.supportedTypes }.distinct()
                ) { list ->
                    preSelectedTypes.clear()
                    preSelectedTypes.addAll(list)
                    updateList()
                }
                updateList()
            }
        }
    }

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val accountViewModel: AccountViewModel by activityViewModels()

    fun addMovies(cards: List<SearchResponse>) {
        val ctx = context ?: run {
            Log.e(TAG, "Context is null, aborting addMovies")
            return
        }

        try {
            val existingId = TvChannelUtils.getChannelId(ctx, getString(R.string.app_name))
            if (existingId != null) {
                Log.d(TAG, "Channel ID: $existingId")

                val programCards = cards

                TvChannelUtils.addPrograms(
                    context = ctx,
                    channelId = existingId,
                    items = programCards
                )
            } else {
                Log.d(TAG, "Channel does not exist")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding movies: $e")
        }
    }

    private fun deleteAll() {
        val ctx = context ?: run {
            Log.e(TAG, "Context is null, aborting deleteAll")
            return
        }

        try {
            val existingId = TvChannelUtils.getChannelId(ctx, getString(R.string.app_name))
            if (existingId != null) {
                Log.d(TAG, "Channel ID: $existingId")
                TvChannelUtils.deleteStoredPrograms(ctx)
            } else {
                Log.d(TAG, "Channel does not exist")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting programs: ${e.message}")
        }
    }

    override fun pickLayout(): Int? =
        if (isLayout(PHONE)) R.layout.fragment_home else R.layout.fragment_home_tv

    /** Phase 8.33: a background-resolved title refreshes the rows.
     *  Phase 8.34: DEBOUNCED — titles resolve in bursts while
     *  scrolling, and a full rebind per title made home scroll
     *  shake (কাপাকাপি). One rebind after a quiet gap is enough. */
    private val titleNotifyRunnable = object : Runnable {
        override fun run() {
            // Phase 8.41: a full rebind mid-scroll still shudders
            // (posters reload + crossfade) -- wait for the list to
            // come to rest, then refresh once.
            val recycler = binding?.homeMasterRecycler
            if (recycler != null &&
                recycler.scrollState != RecyclerView.SCROLL_STATE_IDLE
            ) {
                homeAutoRotateHandler.postDelayed(this, 600L)
                return
            }
            homeMasterAdapter?.notifyDataSetChanged()
        }
    }

    private val titleResolveListener: (String, String) -> Unit = { _, _ ->
        homeAutoRotateHandler.removeCallbacks(titleNotifyRunnable)
        homeAutoRotateHandler.postDelayed(titleNotifyRunnable, 1_200L)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        bottomSheetDialog?.ownShow()
        TitleResolver.addListener(titleResolveListener)
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onDestroyView() {
        TitleResolver.removeListener(titleResolveListener)
        homeAutoRotateHandler.removeCallbacks(titleNotifyRunnable)
        (activity as? ComponentActivity)?.detachBackPressedCallback("HomeFragment_BackPress")
        bottomSheetDialog?.ownHide()
        // Phase 8.17: the auto-rotation timer must not outlive the view.
        homeAutoRotateHandler.removeCallbacks(homeAutoRotateRunnable)
        // Never leave a pull-to-refresh spinner behind on a destroyed view
        binding?.homeSwipeRefresh?.isRefreshing = false
        super.onDestroyView()
    }

    private val apiChangeClickListener = View.OnClickListener { view ->
        // Phase 8.46: back to the plain provider picker (his
        // correction — custom lists live in Settings → Content →
        // Categories and show on Home when active, not here).
        view.context.selectHomepage(
            currentApiName,
            onCustomSelect = { list ->
                homeViewModel.activateCustomList(list)
            }
        ) { api ->
            // Picking a provider leaves custom mode (the custom
            // list itself is managed in Settings, not here).
            homeViewModel.exitCustomMode()
            homeViewModel.loadAndCancel(api, forceReload = true, fromUI = true)
        }
        /*val validAPIs = view.context?.filterProviderByPreferredMedia()?.toMutableList() ?: mutableListOf()

        validAPIs.add(0, randomApi)
        validAPIs.add(0, noneApi)
        view.popupMenuNoIconsAndNoStringRes(validAPIs.mapIndexed { index, api -> Pair(index, api.name) }) {
            homeViewModel.loadAndCancel(validAPIs[itemId].name)
        }*/
    }

    private var currentApiName: String? = null
    private var toggleRandomButton = false

    private var bottomSheetDialog: BottomSheetDialog? = null
    private var homeMasterAdapter: HomeParentItemAdapterPreview? = null

    // Phase 8.17: the category rows as last submitted (fragment-side
    // copies), in display order — drag-reorder swaps these, and the saved
    // order is re-applied to every fresh load. Plus the live auto-rotation
    // timer that steps the visible rows while the home screen sits open.
    private var homeCategoryItems: MutableList<HomeViewModel.ExpandableHomepageList> =
        mutableListOf()
    private val homeCategoryOrderKey = "home_category_order"

    // Phase 8.35: categories the viewer hid (e.g. adult shelves).
    // Hidden names persist; lastHomeCopies keeps the unfiltered
    // list so hide/unhide re-applies instantly, with no reload.
    private val homeHiddenCategoriesKey = "home_hidden_categories"
    private var lastHomeCopies: List<HomeViewModel.ExpandableHomepageList> = emptyList()
    private var categoryDragSwapped = false

    private fun hiddenCategoryNames(): Set<String> {
        val ctx = context ?: return emptySet()
        val raw = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(homeHiddenCategoriesKey, null) ?: return emptySet()
        return raw.split('\u001F').filter { it.isNotEmpty() }.toSet()
    }

    private fun saveHiddenCategoryNames(names: Set<String>) {
        val ctx = context ?: return
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(homeHiddenCategoriesKey, names.joinToString("\u001F"))
            .apply()
    }

    private fun applyHomeCategories() {
        // Phase 8.44: hidden-category rules belong to the
        // per-provider home; a custom list shows what it holds.
        val visible = if (homeViewModel.isCustomActive) {
            lastHomeCopies
        } else {
            val hidden = hiddenCategoryNames()
            lastHomeCopies.filterNot { it.list.name in hidden }
        }
        // Phase 8.50: Family Mode strips NSFW items from every
        // row (rows left empty disappear) on all home variants.
        // Phase 8.51: adult-labelled shelves go entirely, and
        // items are judged by title as well as declared type —
        // mixed providers file porn under plain "Movie".
        val visibleFiltered = if (FamilyMode.isEnabled()) {
            visible
                .filterNot { FamilyMode.isAdultSection(it.list.name) }
                .mapNotNull { row ->
                    val items = row.list.list.filterNot {
                        FamilyMode.isAdultItem(it)
                    }
                    if (items.isEmpty()) null
                    else row.copy(list = row.list.copy(list = items))
                }
        } else {
            visible
        }
        homeCategoryItems = visibleFiltered.toMutableList()
        (binding?.homeMasterRecycler?.adapter as? ParentItemAdapter)
            ?.submitList(visibleFiltered)
    }

    private fun showCategoryMenu(item: HomeViewModel.ExpandableHomepageList) {
        val act = activity ?: return
        val name = item.list.name
        val options = mutableListOf(getString(R.string.home_hide_category))
        if (hiddenCategoryNames().isNotEmpty()) {
            options.add(getString(R.string.home_unhide_categories))
        }
        AlertDialog.Builder(act)
            .setTitle(name)
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    saveHiddenCategoryNames(hiddenCategoryNames() + name)
                    applyHomeCategories()
                    showToast(R.string.home_category_hidden, Toast.LENGTH_SHORT)
                } else {
                    showUnhideCategoriesDialog()
                }
            }
            .show()
    }

    private fun showUnhideCategoriesDialog() {
        val act = activity ?: return
        val hidden = hiddenCategoryNames().sorted()
        if (hidden.isEmpty()) return
        val checked = BooleanArray(hidden.size)
        AlertDialog.Builder(act)
            .setTitle(R.string.home_unhide_categories)
            .setMultiChoiceItems(hidden.toTypedArray(), checked) { _, index, isChecked ->
                checked[index] = isChecked
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                val restore = hidden.filterIndexed { index, _ -> checked[index] }
                if (restore.isNotEmpty()) {
                    saveHiddenCategoryNames(hiddenCategoryNames() - restore.toSet())
                    applyHomeCategories()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    private val homeAutoRotateHandler = Handler(Looper.getMainLooper())
    private val homeAutoRotateRunnable = object : Runnable {
        override fun run() {
            rotateVisibleHomeRows()
            homeAutoRotateHandler.postDelayed(this, 20_000L)
        }
    }

    private fun savedCategoryOrderIndex(name: String): Int {
        val idx = savedCategoryOrder().indexOf(name)
        return if (idx >= 0) idx else Int.MAX_VALUE
    }

    private fun savedCategoryOrder(): List<String> {
        val ctx = context ?: return emptyList()
        val raw = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(homeCategoryOrderKey, null) ?: return emptyList()
        return raw.split('\u001F').filter { it.isNotEmpty() }
    }

    private fun saveCategoryOrder() {
        val ctx = context ?: return
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(
                homeCategoryOrderKey,
                homeCategoryItems.joinToString("\u001F") { it.list.name }
            )
            .apply()
    }

    /**
     * Phase 8.17: one auto-rotation step — every category row currently on
     * screen drops its first poster to the back of the row, animated by
     * the row's own item animator. Skipped while anything is scrolling or
     * being dragged, and while the fragment is paused (e.g. a details page
     * is open above the home screen).
     */
    private fun rotateVisibleHomeRows() {
        if (!isResumed) return
        val recycler = binding?.homeMasterRecycler ?: return
        if (recycler.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i)
            if (recycler.getChildAdapterPosition(child) <= 0) continue
            val row = child.findViewById<RecyclerView>(R.id.home_child_recyclerview)
                ?: continue
            if (row.scrollState != RecyclerView.SCROLL_STATE_IDLE) continue
            val adapter = row.adapter as? HomeChildItemAdapter ?: continue
            val current = adapter.immutableCurrentList
            if (current.size < 4) continue
            adapter.submitList(current.drop(1) + current.first())
        }
    }

    var lastSavedHomepage: String? = null

    fun saveHomepageToTV(page: Map<String, HomeViewModel.ExpandableHomepageList>) {
        // No need to update for phone
        if (isLayout(PHONE)) {
            return
        }
        val (name, data) = page.entries.firstOrNull() ?: return
        // Modifying homepage is an expensive operation, and therefore we avoid it at all cost
        if (name == lastSavedHomepage) {
            return
        }
        Log.i(TAG, "Adding programs $name to TV")
        lastSavedHomepage = name
        ioSafe {
            // empty the channel
            deleteAll()
            // insert the program from first array
            addMovies(data.list.list)
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padTop = false,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )

        // Fix grid
        configEvent.invoke()

        // Phase 8.5C: re-anchor the provider switcher (rotation lands here)
        anchorHomeApiFab()
    }

    /** Phase 8.5C: anchor the provider switcher (home_api_fab) from the
     * activity's REAL bottom-nav view instead of a hand-computed dp constant.
     * The switcher's XML margins were already right on paper twice (7.5, 8C)
     * and the on-device failure turned out not to be the margin at all: in
     * the top-of-home state the FAB was not drawn at all (pixel-verified in
     * PHASE85C_CHANGES.md). This makes the placement self-correcting:
     * bottom margin = the nav view's measured height + the nav's own bottom
     * margin + the original 16dp gap, recomputed on binding creation, on
     * every successful page load, and on every fixLayout (rotation) — so it
     * also tracks the API 30+ inset growth MainActivity applies to the pill.
     * The XML 104dp margin remains as the pre-layout fallback. */
    private fun anchorHomeApiFab() {
        if (!isLayout(PHONE)) return
        val fab = binding?.homeApiFab ?: return
        val nav = activity?.findViewById<View>(R.id.nav_view) ?: return
        val apply = {
            if (nav.isVisible && nav.height > 0) {
                val navBottomMargin =
                    (nav.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
                val desired = nav.height + navBottomMargin + 16.toPx
                val lp = fab.layoutParams as? FrameLayout.LayoutParams
                if (lp != null && lp.bottomMargin != desired) {
                    lp.gravity = Gravity.BOTTOM or Gravity.END
                    lp.bottomMargin = desired
                    lp.marginEnd = 16.toPx
                    fab.layoutParams = lp
                }
            }
        }
        if (nav.height > 0) apply() else nav.post { apply() }
    }

    /**
     * Phase 8.10: hero scroll-collapse (phone only; wired in
     * [onBindingCreated]). As the home list scrolls, the hero frame
     * (`home_preview_viewpager_text` — photo, bottom fade, search row and
     * the None/Play/Info row as one visual unit) continuously shrinks,
     * fades and lags slightly behind the scroll, dissolving into the
     * page; scrolling back to the top restores it exactly, because every
     * value is a pure function of the current scroll offset. Visual
     * transforms only — no layout heights change, so there is no
     * re-layout jank, and the 8.8 bottom fade / clear photo / auto-slide
     * are untouched.
     *
     * This never fights [HomeScrollTransformer]: the transformer runs on
     * the pager's *horizontal* page changes and only writes page padding,
     * while this writes scale/alpha/translationY on the outer frame from
     * the *vertical* list scroll — disjoint properties on disjoint views.
     */
    private fun setupHeroScrollCollapse(recyclerView: RecyclerView) {
        fun applyHeroCollapse() {
            val header = recyclerView.findViewHolderForAdapterPosition(0)?.itemView ?: return
            val frame = header.findViewById<View>(R.id.home_preview_viewpager_text) ?: return
            // No preview loaded: the frame is hidden and the alternate
            // account row shows instead — nothing to collapse.
            if (!frame.isVisible) return
            val height = frame.height
            if (height <= 0) return
            // Layout positions only (itemView top within the list plus the
            // frame's top within the header); transforms never feed back
            // into them, so the mapping stays exact in both directions.
            val scrolled = -(header.top + frame.top)
            val ratio = (scrolled.toFloat() / height).coerceIn(0f, 1f)
            val scale = 1f - 0.08f * ratio
            frame.scaleX = scale
            frame.scaleY = scale
            frame.alpha = 1f - 0.75f * ratio
            // Slight parallax lag: the frame drifts down against the
            // scroll by 20% of the scrolled distance while it dissolves.
            frame.translationY = scrolled * 0.2f
        }

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                applyHeroCollapse()
            }
        })
        // Re-apply when the header scrolls back into view: while it is
        // detached there is no holder to transform, and the frame may
        // still carry the values from when it left the screen.
        recyclerView.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    if (recyclerView.getChildAdapterPosition(view) == 0) {
                        view.post { applyHeroCollapse() }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            })
    }

    @SuppressLint("SetTextI18n")
    // Phase 8.50: returning from Settings re-applies the row
    // filters, so a Family Mode toggle takes effect at once.
    override fun onResume() {
        super.onResume()
        if (lastHomeCopies.isNotEmpty()) {
            applyHomeCategories()
        }
    }

    override fun onBindingCreated(binding: FragmentHomeBinding) {
        context?.let { HomeChildItemAdapter.updatePosterSize(it) }
        (activity as? ComponentActivity)?.attachBackPressedCallback("HomeFragment_BackPress") {
            handleTvBackPress(this)
        }
        binding.apply {
            //homeChangeApiLoading.setOnClickListener(apiChangeClickListener)
            //homeChangeApiLoading.setOnClickListener(apiChangeClickListener)
            homeApiFab.setOnClickListener(apiChangeClickListener)
            homeApiFab.setOnLongClickListener {
                if (currentApiName == noneApi.name) return@setOnLongClickListener false
                homeViewModel.loadAndCancel(currentApiName, forceReload = true, fromUI = true)
                showToast(R.string.action_reload, Toast.LENGTH_SHORT)
                true
            }
            homeChangeApi.setOnClickListener(apiChangeClickListener)
            homeSwitchAccount.setOnClickListener {
                activity?.showAccountSelectLinear()
            }

            homeMasterAdapter = HomeParentItemAdapterPreview(
                homeViewModel, accountViewModel
            )
            homeMasterRecycler.setRecycledViewPool(ParentItemAdapter.sharedPool)
            homeMasterRecycler.adapter = homeMasterAdapter

            // Phase 8.10 (phone only): provider sections glide in as they
            // scroll into view instead of popping, and the hero shrinks /
            // dissolves as it scrolls off. TV and emulator layouts keep
            // the stock behaviour.
            if (isLayout(PHONE)) {
                SectionEntranceAnimator(firstAnimatedPosition = 1)
                    .attachTo(homeMasterRecycler)
                setupHeroScrollCollapse(homeMasterRecycler)

                // Phase 8.17: long-press a category's title strip and drag
                // it up or down to reorder the home categories; the order
                // is saved on drop and re-applied on every load. The hero
                // header (position 0) never moves. Poster touches belong
                // to the rows' own lists, so poster long-press menus are
                // untouched — the drag only starts from the row itself.
                ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
                ) {
                    override fun isLongPressDragEnabled(): Boolean = true

                    override fun getMovementFlags(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder
                    ): Int {
                        if (viewHolder.bindingAdapterPosition <= 0) return 0
                        return super.getMovementFlags(recyclerView, viewHolder)
                    }

                    override fun onMove(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                        target: RecyclerView.ViewHolder
                    ): Boolean {
                        val from = viewHolder.bindingAdapterPosition
                        val to = target.bindingAdapterPosition
                        if (from <= 0 || to <= 0) return false
                        // Adapter positions include the header at 0.
                        val fromIndex = from - 1
                        val toIndex = to - 1
                        if (fromIndex !in homeCategoryItems.indices ||
                            toIndex !in homeCategoryItems.indices
                        ) return false
                        Collections.swap(homeCategoryItems, fromIndex, toIndex)
                        (recyclerView.adapter as? ParentItemAdapter)
                            ?.notifyItemMoved(from, to)
                        categoryDragSwapped = true
                        return true
                    }

                    override fun onSwiped(
                        viewHolder: RecyclerView.ViewHolder,
                        direction: Int
                    ) = Unit

                    override fun onSelectedChanged(
                        viewHolder: RecyclerView.ViewHolder?,
                        actionState: Int
                    ) {
                        super.onSelectedChanged(viewHolder, actionState)
                        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                            categoryDragSwapped = false
                            viewHolder?.itemView?.hapticTick()
                        }
                    }

                    override fun clearView(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder
                    ) {
                        super.clearView(recyclerView, viewHolder)
                        saveCategoryOrder()
                        // Phase 8.35: a hold that never became a drag
                        // (press, no move, release) opens the category
                        // menu instead — the reorder gesture itself
                        // is exactly as before.
                        if (!categoryDragSwapped) {
                            val pos = viewHolder.bindingAdapterPosition
                            if (pos > 0) {
                                homeCategoryItems.getOrNull(pos - 1)?.let {
                                    showCategoryMenu(it)
                                }
                            }
                        }
                    }
                }).attachToRecyclerView(homeMasterRecycler)

                // Phase 8.17: while the home screen sits open, the visible
                // category rows step one poster forward every 20 seconds —
                // a viewer watching the screen sees the shelves change on
                // their own, animated by the rows' item animators.
                homeAutoRotateHandler.removeCallbacks(homeAutoRotateRunnable)
                homeAutoRotateHandler.postDelayed(homeAutoRotateRunnable, 20_000L)
            }

            homeApiFab.isVisible = isLayout(PHONE)
            anchorHomeApiFab()

            homePreviewReloadProvider.setOnClickListener { view ->
                view.hapticTick()
                homeViewModel.loadAndCancel(
                    homeViewModel.apiName.value ?: noneApi.name,
                    forceReload = true,
                    fromUI = true
                )
                showToast(R.string.action_reload, Toast.LENGTH_SHORT)
            }

            // Phase 6A-2: pull-to-refresh on the home feed (phone only; the TV
            // layout mirrors the wrapper for the shared binding but is never
            // wired). A pull runs the exact same reload as the reload-provider
            // button above, and the spinner is stopped by the page observer
            // below, in the same places that hide the loading shimmer.
            if (isLayout(PHONE)) {
                homeSwipeRefresh.apply {
                    setColorSchemeColors(
                        ContextCompat.getColor(context, R.color.white),
                        ContextCompat.getColor(context, R.color.grayTextColor)
                    )
                    setProgressBackgroundColorSchemeColor(
                        ContextCompat.getColor(context, R.color.primaryGrayBackground)
                    )
                    // Soft, unhurried feel: the spinner starts slightly above
                    // the top edge, settles lower, and needs a deep pull
                    // before it lets go.
                    setProgressViewOffset(false, (-40).toPx, 72.toPx)
                    setDistanceToTriggerSync(120.toPx)
                    setOnRefreshListener {
                        homeSwipeRefresh.hapticTick()
                        homeViewModel.loadAndCancel(
                            homeViewModel.apiName.value ?: noneApi.name,
                            forceReload = true,
                            fromUI = true
                        )
                    }
                }
            }

            homePreviewSearchButton.setOnClickListener { _ ->
                // Open blank screen.
                homeViewModel.queryTextSubmit("")
            }

            homePreviewSettingsButton.setOnClickListener { view ->
                val apiName = homeViewModel.apiName.value
                val plugin = APIHolder.getApiFromNameNull(apiName)
                    ?.sourcePlugin?.let { PluginManager.plugins[it] } as? Plugin
                val openSettings = plugin?.openSettings
                if (openSettings != null) {
                    try {
                        val activityContext = view.context.getActivity() ?: view.context
                        openSettings.invoke(activityContext)
                    } catch (e: Throwable) {
                        logError(e)
                    }
                }
            }

            // Load value for toggling Tv layout real time clock. Hide by default at startup
            // set visibility first, to apply a scroll effect later
            context?.let {
                if (isLayout(TV)) {
                    val settingsManager = PreferenceManager.getDefaultSharedPreferences(it)
                    val toggleClock =
                        settingsManager.getBoolean(
                            getString(R.string.tv_layout_clock_key),
                            false
                        )
                    binding.homeClock.isVisible = toggleClock
                } else {
                    binding.homeClock.isVisible = false
                }
            }


            homeMasterRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (isLayout(PHONE)) {
                        // Fab is only relevant to Phone
                        // Phase 8.5C: the provider switcher (homeApiFab) no
                        // longer shrink()es. The RecyclerView also reports
                        // dy > 0 while it first populates, so a shrink could
                        // land before the FAB's extended size was ever
                        // measured — leaving the switcher undrawn at the top
                        // of the feed until a scroll-up happened to extend()
                        // it (see PHASE85C_CHANGES.md). As the primary
                        // provider control it now stays extended; the Random
                        // FAB keeps the original shrink/extend behaviour.
                        if (dy > 0) { //check for scroll down
                            homeRandom.shrink()
                        } else if (dy < -5) {
                            if (isLayout(PHONE)) {
                                homeRandom.extend()
                            }
                        }
                    } else {
                        // Header scrolling is only relevant to TV/Emulator

                        val view = recyclerView.findViewHolderForAdapterPosition(0)?.itemView
                        val scrollParent = binding.homeApiHolder

                        if (view == null) {
                            // The first view is not visible, so we can assume we have scrolled past it
                            scrollParent.isVisible = false
                        } else {
                            // A bit weird, but this is a major limitation we are working around here
                            // 1. We cant have a real parent to the recyclerview as android cant layout that without lagging
                            // 2. We cant put the view in the recyclerview, as it should always be shown
                            // 3. We cant mirror the view in the recyclerview as then it causes focus issues when swaping out the mirror view
                            //
                            // This means that if we want to have a parent view to the recyclerview we are out of luck
                            // Instead this uses getLocationInWindow to calculate how much the view should be scrolled
                            // as recyclerView has no scrollY (always 0)
                            //
                            // Then it manually "scrolls" it to the correct position
                            //
                            // Hopefully getLocationInWindow acts correctly on all devices
                            val rect = IntArray(2)
                            view.getLocationInWindow(rect)
                            scrollParent.isVisible = true
                            scrollParent.translationY = rect[1].toFloat() - 60.toPx

                            // Move the TV layout real time clock out of the way too
                            // We check if we have the correct layout and if the clock is enabled
                            if(isLayout(TV) && binding.homeClock.isVisible) {
                                val scrollParent = binding.homeClock

                                val rect = IntArray(2)
                                view.getLocationInWindow(rect)
                                scrollParent.isVisible = true
                                scrollParent.translationY = rect[1].toFloat() - 60.toPx
                            }
                        }
                    }
                    super.onScrolled(recyclerView, dx, dy)
                }
            })

        }

        //Load value for toggling Random button. Hide at startup
        context?.let {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(it)
            toggleRandomButton =
                settingsManager.getBoolean(
                    getString(R.string.random_button_key),
                    false
                )
            binding.homeRandom.visibility = View.GONE
            binding.homeRandomButtonTv.visibility = View.GONE
        }

        observe(homeViewModel.apiName) { apiName ->
            currentApiName = apiName
            val displayApiName = context?.getDisplayName(apiName) ?: apiName
            binding.apply {
                homeApiFab.text = displayApiName
                homeChangeApi.text = displayApiName
                homePreviewReloadProvider.isGone = (apiName == noneApi.name)
                homePreviewSearchButton.isGone = (apiName == noneApi.name)
            }
        }

        observe(homeViewModel.page) { data ->
            binding.apply {
                if (isLayout(TV or EMULATOR)) {
                    val plugin = APIHolder.getApiFromNameNull(homeViewModel.apiName.value)
                        ?.sourcePlugin?.let { PluginManager.plugins[it] } as? Plugin
                    homePreviewSettingsButton.isGone = plugin?.openSettings == null
                }
                
                when (data) {
                    is Resource.Success -> {
                        val d = data.value
                        // Phase 8.17: re-apply the viewer's saved category
                        // order; categories the save does not know keep
                        // their load order after the known ones.
                        val copies = d.values.map {
                            it.copy(
                                list = it.list.copy(list = it.list.list.toMutableList())
                            )
                        }
                        // Phase 8.44: a custom list keeps ITS
                        // order — the saved-order sort belongs to
                        // the per-provider home.
                        val orderedCopies =
                            if (homeViewModel.isCustomActive) {
                                copies
                            } else {
                                copies.sortedBy {
                                    savedCategoryOrderIndex(it.list.name)
                                }
                            }
                        lastHomeCopies = orderedCopies
                        applyHomeCategories()

                        saveHomepageToTV(d)

                        // Phase 8.16: the skeleton crossfades into the
                        // content instead of cutting, when it was on screen.
                        if (homeLoading.isVisible) {
                            homeLoading.animate().alpha(0f).setDuration(250)
                                .withEndAction {
                                    homeLoading.isVisible = false
                                    homeLoading.alpha = 1f
                                }.start()
                            homeMasterRecycler.alpha = 0f
                            homeMasterRecycler.isVisible = true
                            homeMasterRecycler.animate().alpha(1f)
                                .setDuration(250).start()
                        } else {
                            homeLoading.isVisible = false
                            homeMasterRecycler.alpha = 1f
                            homeMasterRecycler.isVisible = true
                        }
                        homeLoadingError.isVisible = false
                        homeLoadingShimmer.stopShimmer()
                        homeSwipeRefresh.isRefreshing = false

                        // Phase 8.5C: whenever content is on screen the
                        // provider switcher must be too — re-assert its
                        // visibility and re-anchor it above the real nav
                        // view on every successful load, so no earlier
                        // state can leave it missing (see PHASE85C_CHANGES.md).
                        homeApiFab.isVisible = isLayout(PHONE)
                        anchorHomeApiFab()

                        //home_loaded?.isVisible = true
                        if (toggleRandomButton) {
                            val distinct = d.values
                                .flatMap { it.list.list }
                                .distinctBy { it.url }
                            val hasItems = distinct.isNotEmpty()
                            val isPhone = isLayout(PHONE)
                            val randomClickListener = View.OnClickListener {
                                distinct.randomOrNull()?.let { activity.loadSearchResult(it) }
                            }

                            homeRandom.isVisible = isPhone && hasItems
                            homeRandom.setOnClickListener(randomClickListener)
                            homeRandomButtonTv.isVisible = !isPhone && hasItems
                            homeRandomButtonTv.setOnClickListener(randomClickListener)
                        } else {
                            homeRandom.isGone = true
                            homeRandomButtonTv.isGone = true
                        }
                    }
                    //Open browser directly, without a menu.
                    is Resource.Failure -> {
                        homeLoadingShimmer.stopShimmer()
                        homeReloadConnectionerror.setOnClickListener(apiChangeClickListener)
                        homeReloadConnectionOpenInBrowser.setOnClickListener {
                            val currentApi = currentApiName?.let { getApiFromNameNull(it) }
                                ?: homeViewModel.apiName.value?.let { getApiFromNameNull(it) }
                            val mainUrl = currentApi?.mainUrl
                            if (!mainUrl.isNullOrBlank()) {
                                context?.openBrowser(mainUrl)
                            }
                        }

                        homeLoading.isVisible = false
                        homeLoadingError.isVisible = true
                        homeMasterRecycler.isInvisible = true
                        homeSwipeRefresh.isRefreshing = false

                        // Based on https://github.com/recloudstream/cloudstream/pull/1438
                        val hasNoNetworkConnection = context?.isNetworkAvailable() == false
                        val isNetworkError = data.isNetworkError

                        // Show the downloads button if we have any sort of network shenanigans
                        homeReloadConnectionGoToDownloads.isVisible =
                            hasNoNetworkConnection || isNetworkError

                        // Only hide the open in browser button if we know this is not network shenanigans related to cs3
                        homeReloadConnectionOpenInBrowser.isGone = hasNoNetworkConnection

                        resultErrorText.text = if (hasNoNetworkConnection) {
                            getString(R.string.no_internet_connection)
                        } else {
                            data.errorString
                        }

                        homeReloadConnectionGoToDownloads.setOnClickListener {
                            activity.navigate(R.id.navigation_downloads)
                        }

                        (homeMasterRecycler.adapter as? ParentItemAdapter)?.apply {
                            submitList(null)
                            clearState()
                        }
                    }

                    is Resource.Loading -> {
                        homeLoadingShimmer.startShimmer()
                        homeLoading.isVisible = true
                        homeLoadingError.isVisible = false
                        homeMasterRecycler.isInvisible = true
                        (homeMasterRecycler.adapter as? ParentItemAdapter)?.apply {
                            submitList(null)
                            clearState()
                        }
                        //home_loaded?.isVisible = false
                    }
                }
            }
        }

        observeNullable(homeViewModel.popup) { item ->
            if (item == null) {
                bottomSheetDialog?.dismissSafe()
                bottomSheetDialog = null
                return@observeNullable
            }

            // don't recreate
            if (bottomSheetDialog != null) {
                return@observeNullable
            }

            val (items, delete) = item

            bottomSheetDialog = activity?.loadHomepageList(items, expandCallback = {
                homeViewModel.expandAndReturn(it)
            }, dismissCallback = {
                homeViewModel.popup(null)
                bottomSheetDialog = null
            }, deleteCallback = delete)
        }

        homeViewModel.reloadStored()
        homeViewModel.loadAndCancel(DataStoreHelper.currentHomePage, false)
        //loadHomePage(false)

        // nice profile pic on homepage
        //home_profile_picture_holder?.isVisible = false
        // just in case

        //TODO READD THIS
        /*for (syncApi in OAuth2Apis) {
            val login = SyncAPI2.loginInfo()
            val pic = login?.profilePicture
            if (home_profile_picture?.setImage(
                    pic,
                    errorImageDrawable = errorProfilePic
                ) == true
            ) {
                home_profile_picture_holder?.isVisible = true
                break
            }
        }*/
    }

    private fun handleTvBackPress(helper: BackPressedCallbackHelper.CallbackHelper) {
        // Only apply custom behavior on TV interface
        if (!isLayout(TV)) {
            helper.runDefault()
            return
        }
        val currentFocus = activity?.currentFocus ?: run {
            helper.runDefault()
            return
        }
        // isInsideRecycle is true when focus is inside home_master_recycler
        var parent = currentFocus.parent
        var isInsideRecycler = false
        while (parent != null) {
            if (parent is View && parent.id == R.id.home_master_recycler) {
                isInsideRecycler = true
                break
            }
            parent = parent.parent
        }
        when {
            // Case 1: Focus is within plugin content -> Move to plugin selector
            isInsideRecycler -> {
                binding?.homeMasterRecycler?.scrollToPosition(0)
                // Defer focus request until after scroll ends
                binding?.homeChangeApi?.post {
                    binding?.homeChangeApi?.requestFocus()
                }
            }
            // Case 2: Focus is on plugin selector or nearby buttons -> Move to home navigation
            currentFocus.id == R.id.home_change_api ||
            currentFocus.id == R.id.home_preview_reload_provider ||
            currentFocus.id == R.id.home_preview_search_button -> {
                activity?.findViewById<View>(R.id.navigation_home)?.requestFocus()
            }
            // Case 3: Any other location -> Use default back behavior
            else -> helper.runDefault()
        }
    }
}
