package com.lagradost.cloudstream3.ui.feed

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.databinding.FragmentFeedBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.home.HomeScrollTransformer
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder.cleanPosterTitle
import com.lagradost.cloudstream3.ui.search.MetadataResolver
import com.lagradost.cloudstream3.ui.search.TitleResolver
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Implemented by the Feed tab so MainActivity can ask it to scroll
 * back to the top without referencing the fragment class itself
 * (the class is @UnstableApi-marked because of the player engine).
 */
interface FeedScrollTop {
    fun scrollToTop()
}

/**
 * Phase 8.18–8.20 — the Feed tab: every provider's content in one
 * category-free 2-column grid under a collapsible header (search +
 * genre chips that hide on scroll). Cards play directly in the shared
 * [FeedPlayer]; when a video is minimised it keeps playing in the mini
 * player floating over this grid.
 */
@UnstableApi
class FeedFragment : BaseFragment<FragmentFeedBinding>(
    BindingCreator.Inflate(FragmentFeedBinding::inflate)
), FeedScrollTop {
    private val viewModel: FeedViewModel by viewModels()
    private val adapter = FeedAdapter(
        onPlay = { entry -> openWatch(entry) },
        onHide = { entry -> confirmHide(entry) },
        onTitleNeeded = { entry ->
            context?.let { ctx ->
                TitleResolver.resolve(ctx, entry.apiName, entry.response.url, entry.response.name)
            }
        }
    )

    /** A resolved real title lands here and swaps into its card. */
    private val titleListener: (String, String) -> Unit = { url, title ->
        // Phase 8.56: titles resolve in bursts while scrolling and
        // a rebind per title shakes the grid — queue them and
        // apply once the list comes to rest (same cure as Home).
        pendingTitleOverrides[url] = title
        searchHandler.removeCallbacks(titleApplyRunnable)
        searchHandler.postDelayed(titleApplyRunnable, 900L)
    }

    private val pendingTitleOverrides = mutableMapOf<String, String>()

    private val titleApplyRunnable = object : Runnable {
        override fun run() {
            val rv = binding?.feedRecycler
            if (rv != null &&
                rv.scrollState != RecyclerView.SCROLL_STATE_IDLE
            ) {
                searchHandler.postDelayed(this, 600L)
                return
            }
            val pending = pendingTitleOverrides.toMap()
            pendingTitleOverrides.clear()
            pending.forEach { (url, title) ->
                adapter.setTitleOverride(url, title)
            }
        }
    }

    /** Phase 8.56: universal ratings land here as they resolve. */
    private val ratingListener: (String, String) -> Unit = { key, rating ->
        // Phase 8.85: the fragment keeps its own copy — the Filters
        // sheet's Rating tab reads resolved scores from here.
        fragmentRatings[key] = rating
        adapter.setRatingOverride(key, rating)
    }

    private val fragmentRatings = mutableMapOf<String, String>()

    // ---------- Phase 8.85: hero + advanced filters state ----------

    private val heroAdapter = FeedHeroAdapter(onPlay = { entry -> openWatch(entry) })
    private var heroEntries: List<FeedEntry> = emptyList()
    private var heroUserDragging = false
    private val heroHandler = Handler(Looper.getMainLooper())
    private val heroAutoRunnable = object : Runnable {
        override fun run() {
            val b = binding
            if (b != null && heroEntries.size > 1 &&
                b.feedHeroBox.isVisible && !heroUserDragging
            ) {
                b.feedHeroPager.setCurrentItem(
                    (b.feedHeroPager.currentItem + 1) % heroEntries.size, true
                )
            }
            heroHandler.postDelayed(this, 5_000L)
        }
    }

    /** Advanced filter selections (Filters sheet). Null = All. */
    private var filterGenre: String? = null
    private var filterYear: Int? = null
    private var filterRatingMin: Double? = null
    private var filterSource: String? = null

    private val filtersActive: Boolean
        get() = filterGenre != null || filterYear != null ||
            filterRatingMin != null || filterSource != null

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /** Set by a pull-to-refresh: the next feed value starts at the top. */
    private var scrollTopOnNextFeed = false

    /** RecyclerView position, kept across watch-page trips. */
    private var listState: android.os.Parcelable? = null

    private var pool: List<FeedEntry> = emptyList()
    private var searchJob: Job? = null
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

    private var headerFullHeight = 0
    private var headerShown = true
    private var headerAnimator: ValueAnimator? = null
    private var selectedChip: TextView? = null

    private val miniListener: () -> Unit = { updateMiniBar() }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )
    }

    override fun onBindingCreated(binding: FragmentFeedBinding) {
        // Phase 8.85: 3 columns like Home's grid (was 2).
        binding.feedRecycler.layoutManager = GridLayoutManager(context, 3)
        binding.feedRecycler.adapter = adapter
        // Fixed size + no change animations: the grid must never
        // relayout or flash mid-scroll (Phase 8.23 jitter fix).
        binding.feedRecycler.setHasFixedSize(true)
        binding.feedRecycler.itemAnimator = null
        buildChips(binding)
        setupTopBar(binding)
        setupHero(binding)
        binding.feedEmptyReset.setOnClickListener {
            // Back to the unfiltered feed: clear the advanced
            // filters (8.85), clear the search text (its watcher
            // re-runs the pool filter) and reselect the All chip.
            clearFilters()
            if (binding.feedSearchInput.text.toString().isNotEmpty()) {
                binding.feedSearchInput.setText("")
            } else {
                applySearch("")
            }
            (binding.feedChips.getChildAt(0) as? TextView)?.let { first ->
                selectChip(binding, first, "All")
            }
        }
        setupHeaderCollapse(binding)
        setupMiniBar(binding)

        TitleResolver.addListener(titleListener)
        MetadataResolver.addListener(ratingListener)

        // Phase 8.37: the refresh ring used to hide behind the
        // floating header — rest it below the search bar + chips.
        binding.feedSwipeRefresh.setProgressViewOffset(
            false, 0, (132 * resources.displayMetrics.density).toInt()
        )
        binding.feedSwipeRefresh.setOnRefreshListener {
            // A refresh means "show me something else": drop any active
            // search and load the providers' next homepage page — it
            // lands at the TOP of the feed (FeedViewModel.refresh).
            if (binding.feedSearchInput.text.isNotEmpty()) {
                binding.feedSearchInput.text.clear()
            }
            scrollTopOnNextFeed = true
            viewModel.refresh()
        }

        binding.feedSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                binding.feedSearchClear.isVisible = query.isNotEmpty()
                applySearch(query)
            }
        })
        binding.feedSearchClear.setOnClickListener {
            binding.feedSearchInput.text.clear()
        }

        observe(viewModel.feed) { resource ->
            when (resource) {
                is Resource.Success -> {
                    // Phase 8.35: the spinner belongs to the build
                    // chain, not to whichever build finishes first —
                    // a queued pull-refresh keeps it spinning until
                    // ITS build lands.
                    binding.feedSwipeRefresh.isRefreshing = viewModel.isBuilding
                    binding.feedLoading.isVisible = false
                    hideSkeleton(binding)
                    // Hidden items stay hidden even in a replayed value.
                    val hidden = context?.let { FeedHidden.getAll(it) } ?: emptySet()
                    pool = resource.value.filterNot { it.response.url in hidden }
                    updateHeroItems()
                    val query = binding.feedSearchInput.text.toString().trim()
                    if (query.isEmpty()) {
                        val filtered = applyFeedFilters(pool)
                        binding.setFeedEmpty( filtered.isEmpty() )
                        // Phase 8.43: scroll to the top AFTER the
                        // prepended list commits. Scrolling before
                        // submitList let the layout manager anchor
                        // to the old first item, which the prepend
                        // pushed far down — a refresh flung the
                        // list toward the end (his report).
                        val goTop = scrollTopOnNextFeed &&
                            viewModel.lastBuildWasPrepend
                        if (goTop) {
                            scrollTopOnNextFeed = false
                            listState = null
                        }
                        adapter.submitList(filtered) {
                            if (goTop) {
                                binding.feedRecycler.scrollToPosition(0)
                            } else {
                                listState?.let { state ->
                                    binding.feedRecycler.layoutManager
                                        ?.onRestoreInstanceState(state)
                                    listState = null
                                }
                            }
                        }
                    } else {
                        applySearch(query)
                    }
                }

                is Resource.Failure -> {
                    binding.feedSwipeRefresh.isRefreshing = false
                    binding.feedLoading.isVisible = false
                    hideSkeleton(binding)
                    binding.setFeedEmpty( true )
                }

                is Resource.Loading -> {
                    if (adapter.itemCount == 0 && pool.isEmpty()) {
                        showSkeleton(binding)
                    }
                }

                else -> Unit
            }
        }

        FeedPlayer.warmUp(requireContext())
        FeedPlayer.addListener(miniListener)
        updateMiniBar()
        viewModel.loadIfNeeded()
    }

    override fun onDestroyView() {
        // Remember where the list was scrolled to — opening a video and
        // coming back must not throw the viewer back to the top.
        listState = binding?.feedRecycler?.layoutManager?.onSaveInstanceState()
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        searchHandler.removeCallbacks(titleApplyRunnable)
        heroHandler.removeCallbacks(heroAutoRunnable)
        searchJob?.cancel()
        headerAnimator?.cancel()
        FeedPlayer.removeListener(miniListener)
        TitleResolver.removeListener(titleListener)
        MetadataResolver.removeListener(ratingListener)
        super.onDestroyView()
    }

    /** Phase 8.22: long-press a card → offer to hide it for good. */
    private fun confirmHide(entry: FeedEntry) {
        val context = context ?: return
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(FeedAdapter.displayTitle(entry.response.name, entry.response.url))
            .setMessage(R.string.feed_hide_message)
            .setPositiveButton(R.string.feed_hide) { _, _ ->
                FeedHidden.hide(context, entry.response.url)
                FeedPool.hide(entry.response.url)
                pool = pool.filterNot { it.response.url == entry.response.url }
                updateHeroItems()
                val query = binding?.feedSearchInput?.text?.toString()?.trim().orEmpty()
                if (query.isEmpty()) {
                    val filtered = applyFeedFilters(pool)
                    binding?.setFeedEmpty( filtered.isEmpty() )
                    adapter.submitList(filtered)
                } else {
                    applySearch(query)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Called by MainActivity when the Feed nav item is re-tapped. */
    override fun scrollToTop() {
        val binding = binding ?: return
        binding.feedRecycler.smoothScrollToPosition(0)
        animateHeader(binding, show = true)
    }

    // ---------- header: chips + collapse on scroll ----------

    /** Phase 8.36: the empty state is a designed box (art +
     *  message + a Show-all exit), not a lone grey line. */
    private fun FragmentFeedBinding.setFeedEmpty(visible: Boolean) {
        feedEmptyBox.isVisible = visible
    }

    /** Phase 8.36: genre chips with zero matches in the current
     *  pool render dimmed, so an empty tag is visible before it
     *  is tapped (provider search can still find more). */
    private fun refreshChipAlphas(binding: FragmentFeedBinding) {
        for (i in 0 until binding.feedChips.childCount) {
            val chip = binding.feedChips.getChildAt(i) as? TextView ?: continue
            val tag = chip.tag as? String ?: continue
            if (tag == "All") {
                chip.alpha = 1f
                continue
            }
            val any = pool.any {
                it.response.name.contains(tag, ignoreCase = true)
            }
            chip.alpha = if (any) 1f else 0.45f
        }
    }

    /**
     * Phase 8.85: the feed's genre vocabulary. SearchResponse has
     * no genre field, so genres have always been title-keyword
     * matches — the chip row and the Filters sheet's Genre tab
     * share this one list and this one matching rule.
     */
    private val genreTags = listOf(
        "Horror", "Comedy", "Romance", "Thriller",
        "Action", "Drama", "Family", "Sci-Fi", "Animation"
    )

    private fun buildChips(binding: FragmentFeedBinding) {
        val tags = listOf("All") + genreTags
        val context = binding.feedChips.context
        tags.forEachIndexed { index, tag ->
            val chip = TextView(context).apply {
                text = tag
                textSize = 13f
                setPadding(30, 14, 30, 14)
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 20 }
                setOnClickListener { selectChip(binding, this, tag) }
            }
            chip.tag = tag
            styleChip(chip, selected = index == 0)
            if (index == 0) selectedChip = chip
            binding.feedChips.addView(chip)
        }
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        val context = chip.context
        if (selected) {
            chip.setBackgroundResource(R.drawable.feed_chip_selected)
            chip.setTextColor(ContextCompat.getColor(context, android.R.color.black))
        } else {
            chip.setBackgroundResource(R.drawable.feed_search_bg)
            chip.setTextColor(ContextCompat.getColor(context, android.R.color.white))
        }
    }

    private fun selectChip(binding: FragmentFeedBinding, chip: TextView, tag: String) {
        selectedChip?.let { styleChip(it, selected = false) }
        selectedChip = chip
        styleChip(chip, selected = true)
        // Driving the search through the input keeps one code path:
        // pool filter first, provider search for 3+ characters.
        val target = if (tag == "All") "" else tag
        if (binding.feedSearchInput.text.toString() != target) {
            binding.feedSearchInput.setText(target)
            binding.feedSearchInput.setSelection(target.length)
        }
    }

    private fun setupHeaderCollapse(binding: FragmentFeedBinding) {
        binding.feedHeader.post {
            headerFullHeight = binding.feedHeader.height
            // The header floats over the grid: content starts below
            // it (plus the hero's space while the hero is visible).
            updateHeroChrome(binding)
        }
        binding.feedRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 12) {
                    animateHeader(binding, show = false)
                } else if (dy < -12) {
                    animateHeader(binding, show = true)
                }
                // Phase 8.32: endless scroll — near the end, pull
                // the providers' next homepage page into the pool.
                if (dy > 0) {
                    val lm = recyclerView.layoutManager as? GridLayoutManager
                    val total = recyclerView.adapter?.itemCount ?: 0
                    if (lm != null && total > 0 &&
                        lm.findLastVisibleItemPosition() >= total - 8
                    ) {
                        viewModel.loadMore()
                    }
                }
            }
        })
    }

    private fun animateHeader(binding: FragmentFeedBinding, show: Boolean) {
        // Phase 8.56: the height was measured once in a post{} at
        // setup; if that ran before layout it stayed 0 forever
        // and auto-hide silently never worked (his report).
        // Re-measure lazily whenever it is missing.
        if (headerFullHeight <= 0) {
            headerFullHeight = binding.feedHeader.height
            if (headerFullHeight <= 0) return
        }
        if (show == headerShown) return
        headerShown = show
        val header = binding.feedHeader
        // Slide, don't resize: animating the header's height forced a
        // full relayout of the grid on every frame — the scroll
        // jitter (কাপাকাপি) reported in Phase 8.23.
        val from = header.translationY
        val to = if (show) 0f else -headerFullHeight.toFloat()
        headerAnimator?.cancel()
        headerAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = 180L
            addUpdateListener { animator ->
                header.translationY = animator.animatedValue as Float
            }
            start()
        }
    }

    // ---------- skeleton ----------

    private fun showSkeleton(binding: FragmentFeedBinding) {
        binding.feedRecycler.isVisible = false
        binding.feedSkeleton.isVisible = true
        binding.feedSkeleton.startShimmer()
    }

    private fun hideSkeleton(binding: FragmentFeedBinding) {
        binding.feedSkeleton.stopShimmer()
        binding.feedSkeleton.isVisible = false
        binding.feedRecycler.isVisible = true
    }

    // ---------- mini player ----------

    private fun setupMiniBar(binding: FragmentFeedBinding) {
        val mini = binding.feedMini
        FeedPlayer.warmUp(requireContext())
        mini.feedMiniPlayerView.player = FeedPlayer.exo
        mini.feedMiniPlayPause.setOnClickListener { FeedPlayer.togglePlay() }
        mini.feedMiniClose.setOnClickListener { FeedPlayer.stop() }

        var downY = 0f
        val miniTouch = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> downY = event.rawY
                MotionEvent.ACTION_UP -> {
                    val dy = event.rawY - downY
                    when {
                        dy < -70f -> expandMiniPlayer()
                        dy > 70f -> FeedPlayer.stop()
                        else -> expandMiniPlayer()
                    }
                }
            }
            true
        }
        mini.feedMiniRoot.setOnTouchListener(miniTouch)
        // The video surface would otherwise swallow taps meant for the bar.
        mini.feedMiniPlayerView.setOnTouchListener(miniTouch)
    }

    private fun expandMiniPlayer() {
        val url = FeedPlayer.currentUrl ?: return
        FeedPlayer.minimized = false
        activity.navigate(
            R.id.navigation_feed_watch,
            Bundle().apply {
                putString("url", url)
                putString("apiName", FeedPlayer.currentApiName)
                putString("name", FeedPlayer.currentName)
                putString("posterUrl", FeedPlayer.currentPoster)
            }
        )
        updateMiniBar()
    }

    private fun updateMiniBar() {
        val binding = binding ?: return
        val mini = binding.feedMini
        val show = FeedPlayer.hasActiveItem && FeedPlayer.minimized
        mini.feedMiniRoot.isVisible = show
        if (show) {
            if (mini.feedMiniPlayerView.player == null) {
                mini.feedMiniPlayerView.player = FeedPlayer.exo
            }
            mini.feedMiniTitle.text =
                cleanPosterTitle(FeedPlayer.currentName ?: "")
            mini.feedMiniPlayPause.setImageResource(
                if (FeedPlayer.isPlaying) R.drawable.netflix_pause else R.drawable.netflix_play
            )
        }
    }

    // ---------- search ----------

    /**
     * Instant part: filter the loaded pool by title. Deep part (3+
     * chars, debounced): search all providers and merge new hits in.
     */
    private fun applySearch(query: String) {
        val binding = binding ?: return
        refreshChipAlphas(binding)
        updateHeroChrome(binding)
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        searchJob?.cancel()
        if (query.isEmpty()) {
            // Back to the full pool — also reset the chips to "All".
            // Phase 8.85: the advanced Filters still apply on top.
            binding.feedLoading.isVisible = false
            selectedChip?.let { styleChip(it, selected = false) }
            val first = binding.feedChips.getChildAt(0) as? TextView
            selectedChip = first
            first?.let { styleChip(it, selected = true) }
            val filtered = applyFeedFilters(pool)
            binding.setFeedEmpty( filtered.isEmpty() )
            adapter.submitList(filtered)
            return
        }
        val local = applyFeedFilters(pool.filter {
            it.response.name.contains(query, ignoreCase = true)
        })
        // Phase 8.56: the empty page used to appear the moment
        // LOCAL hits were zero — over the still-loading skeleton —
        // while the provider search was still running (his
        // screenshot). Empty is only true once every source has
        // answered; while a remote search is pending, the spinner
        // shows instead.
        if (local.isNotEmpty()) {
            binding.feedLoading.isVisible = false
            binding.setFeedEmpty( false )
            adapter.submitList(local)
        } else if (query.length >= 3) {
            binding.setFeedEmpty( false )
            binding.feedLoading.isVisible = true
            adapter.submitList(local)
        } else {
            binding.feedLoading.isVisible = false
            binding.setFeedEmpty( !viewModel.isBuilding )
            adapter.submitList(local)
            return
        }
        if (query.length < 3) return

        val task = Runnable {
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                val remote = viewModel.searchAll(query)
                // Drop stale results if the query moved on meanwhile.
                if (binding.feedSearchInput.text.toString().trim() != query) {
                    return@launch
                }
                val seen = local.map { it.response.url }.toMutableSet()
                val hidden = context?.let { FeedHidden.getAll(it) } ?: emptySet()
                val merged = applyFeedFilters(local + remote.filter {
                    it.response.url !in hidden && seen.add(it.response.url)
                })
                binding.feedLoading.isVisible = false
                binding.setFeedEmpty( merged.isEmpty() )
                adapter.submitList(merged)
            }
        }
        pendingSearch = task
        searchHandler.postDelayed(task, 600L)
    }

    // ---------- Phase 8.85: top bar, hero, filters ----------

    private fun setupTopBar(binding: FragmentFeedBinding) {
        binding.feedFilterButton.setOnClickListener { showFeedFilters() }
        binding.feedSearchButton.setOnClickListener {
            // The search field already lives in the header — make
            // sure it is on screen, then focus it and raise the
            // keyboard.
            animateHeader(binding, show = true)
            binding.feedSearchInput.requestFocus()
            val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? InputMethodManager
            imm?.showSoftInput(
                binding.feedSearchInput, InputMethodManager.SHOW_IMPLICIT
            )
        }
        updateFilterButtonState()
    }

    private fun setupHero(binding: FragmentFeedBinding) {
        binding.feedHeroPager.adapter = heroAdapter
        // Same crossfade + pop-out transformer as the Home hero.
        binding.feedHeroPager.setPageTransformer(HomeScrollTransformer())
        binding.feedHeroPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    onHeroSettled(position)
                }

                override fun onPageScrollStateChanged(state: Int) {
                    heroUserDragging =
                        state == ViewPager2.SCROLL_STATE_DRAGGING
                }
            }
        )
        heroHandler.removeCallbacks(heroAutoRunnable)
        heroHandler.postDelayed(heroAutoRunnable, 5_000L)
    }

    /** Hero items: the feed's own top entries that carry a poster. */
    private fun updateHeroItems() {
        heroEntries = pool
            .filter { !it.response.posterUrl.isNullOrBlank() }
            .take(6)
        heroAdapter.items = heroEntries
        val b = binding ?: return
        if (heroEntries.isNotEmpty()) {
            val pos = b.feedHeroPager.currentItem
                .coerceIn(0, heroEntries.size - 1)
            if (b.feedHeroPager.currentItem != pos) {
                b.feedHeroPager.setCurrentItem(pos, false)
            }
            onHeroSettled(pos)
        }
        updateHeroChrome(b)
    }

    /**
     * Positions the hero under the floating header and reserves its
     * space in the grid's top padding. The hero only shows over the
     * unfiltered, unsearched feed.
     */
    private fun updateHeroChrome(binding: FragmentFeedBinding) {
        val heroVisible = heroEntries.isNotEmpty() &&
            binding.feedSearchInput.text.toString().trim().isEmpty() &&
            !filtersActive
        binding.feedHeroBox.isVisible = heroVisible
        val heroSpace = if (heroVisible) dp(220) + dp(12) else 0
        (binding.feedHeroBox.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            val top = headerFullHeight + dp(12)
            if (lp.topMargin != top) {
                lp.topMargin = top
                binding.feedHeroBox.layoutParams = lp
            }
        }
        if (headerFullHeight > 0) {
            val topPad = headerFullHeight + dp(6) + heroSpace
            binding.feedRecycler.setPadding(
                binding.feedRecycler.paddingLeft, topPad,
                binding.feedRecycler.paddingRight,
                binding.feedRecycler.paddingBottom
            )
            binding.feedSkeleton.setPadding(
                binding.feedSkeleton.paddingLeft, topPad,
                binding.feedSkeleton.paddingRight,
                binding.feedSkeleton.paddingBottom
            )
            binding.feedSwipeRefresh.setProgressViewOffset(
                false, 0, headerFullHeight + dp(10)
            )
        }
    }

    /**
     * A hero page settled: pin its poster into the backdrop under
     * the pager (the crossfade then never flashes black) and
     * pre-warm the next poster's Coil cache entry.
     */
    private fun onHeroSettled(position: Int) {
        val b = binding ?: return
        val entry = heroEntries.getOrNull(position) ?: return
        b.feedHeroBackdrop.loadImage(
            entry.response.posterUrl, entry.response.posterHeaders
        ) {
            crossfade(false)
            error {
                getImageFromDrawable(
                    b.feedHeroBackdrop.context, R.drawable.poster_placeholder
                )
            }
        }
        if (heroEntries.size < 2) return
        val next = heroEntries[(position + 1) % heroEntries.size]
        val nextUrl = next.response.posterUrl ?: return
        val ctx = context ?: return
        val request = ImageRequest.Builder(ctx)
            .data(nextUrl)
            .httpHeaders(NetworkHeaders.Builder().also { hb ->
                hb["User-Agent"] = USER_AGENT
                next.response.posterHeaders?.forEach { (key, value) ->
                    hb[key] = value
                }
            }.build())
            .build()
        SingletonImageLoader.get(ctx).enqueue(request)
    }

    // ---------- Phase 8.85: advanced filters ----------

    /** The item's score for the Rating tab: its own provider score
     *  first, then the resolved universal rating (fragment cache,
     *  then MetadataResolver's cache). Null = no score known. */
    private fun scoreOf(entry: FeedEntry): Double? {
        entry.response.score?.toDouble(10)?.let {
            if (it > 0.0) return it
        }
        val key = MetadataResolver.keyFor(
            entry.response.name, MetadataResolver.yearOf(entry.response)
        )
        val text = fragmentRatings[key]
            ?: context?.let { MetadataResolver.cachedFor(it, entry.response) }
        return text?.toDoubleOrNull()
    }

    private fun applyFeedFilters(list: List<FeedEntry>): List<FeedEntry> {
        var out = list
        filterGenre?.let { genre ->
            out = out.filter {
                it.response.name.contains(genre, ignoreCase = true)
            }
        }
        filterYear?.let { year ->
            out = out.filter { MetadataResolver.yearOf(it.response) == year }
        }
        filterRatingMin?.let { min ->
            out = out.filter { scoreOf(it)?.let { s -> s >= min } == true }
        }
        filterSource?.let { source ->
            out = out.filter { it.apiName == source }
        }
        return out
    }

    private fun clearFilters() {
        filterGenre = null
        filterYear = null
        filterRatingMin = null
        filterSource = null
        updateFilterButtonState()
    }

    private fun updateFilterButtonState() {
        val b = binding ?: return
        val ctx = context ?: return
        b.feedFilterButton.imageTintList = ContextCompat.getColorStateList(
            ctx, if (filtersActive) R.color.colorPrimaryRed else R.color.white
        )
    }

    /**
     * The Filters sheet (SkyStream Explore reference): Genre /
     * Year / Rating / Source tabs over the options actually present
     * in the loaded feed, applied together by Done. The reference's
     * 4th tab is Lang — feed items carry no reliable content
     * language, so the 4th tab is the item's provider (Source).
     * Genre matching reuses the chip row's title-keyword rule;
     * Year reads the response's year field where the class has
     * one; Rating reads the provider score / resolved rating.
     */
    private fun showFeedFilters() {
        val ctx = context ?: return
        var selGenre = filterGenre
        var selYear = filterYear
        var selRating = filterRatingMin
        var selSource = filterSource

        val dialog = BottomSheetDialog(ctx, R.style.AppBottomSheetDialogTheme)

        fun styleChips(container: ViewGroup, selected: Any?) {
            for (i in 0 until container.childCount) {
                val chip = container.getChildAt(i) as? TextView ?: continue
                chip.setBackgroundResource(
                    if (chip.tag == selected) R.drawable.bg_feed_filter_selected
                    else R.drawable.feed_search_bg
                )
            }
        }

        fun styleRows(container: ViewGroup, selected: Any?) {
            for (i in 0 until container.childCount) {
                val row = container.getChildAt(i) as? ViewGroup ?: continue
                val isSel = row.tag == selected
                row.setBackgroundResource(
                    if (isSel) R.drawable.bg_feed_filter_selected
                    else R.drawable.feed_search_bg
                )
                for (j in 0 until row.childCount) {
                    val child = row.getChildAt(j)
                    if (child.tag == "check") child.isVisible = isSel
                }
            }
        }

        fun makeChip(text: String, tag: Any): TextView {
            return TextView(ctx).apply {
                this.text = text
                this.tag = tag
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(11), dp(8), dp(11))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
            }
        }

        fun makeRow(
            tag: Any, title: String, subtitle: String?, goldStar: Boolean?
        ): LinearLayout {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                this.tag = tag
                isClickable = true
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            if (goldStar != null) {
                row.addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_baseline_star_24)
                    imageTintList = ContextCompat.getColorStateList(
                        ctx,
                        if (goldStar) R.color.ratingStarGold else R.color.white
                    )
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                })
            }
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = if (goldStar != null) dp(12) else 0 }
            }
            texts.addView(TextView(ctx).apply {
                text = title
                textSize = 15f
                setTextColor(Color.WHITE)
            })
            if (subtitle != null) {
                texts.addView(TextView(ctx).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(Color.rgb(158, 158, 158))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(2) }
                })
            }
            row.addView(texts)
            row.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_baseline_check_24)
                imageTintList = ContextCompat.getColorStateList(ctx, R.color.white)
                this.tag = "check"
                isVisible = false
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            })
            return row
        }

        fun buildGenreTab(): View {
            val grid = GridLayout(ctx).apply {
                columnCount = 2
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val present = genreTags.filter { tag ->
                pool.any { it.response.name.contains(tag, ignoreCase = true) }
            }
            (listOf("") + present).forEach { genre ->
                val chip = makeChip(
                    if (genre.isEmpty()) getString(R.string.all) else genre,
                    genre
                )
                chip.setOnClickListener {
                    selGenre = genre.ifEmpty { null }
                    styleChips(grid, selGenre ?: "")
                }
                grid.addView(chip)
            }
            styleChips(grid, selGenre ?: "")
            return grid
        }

        fun buildYearTab(): View {
            val grid = GridLayout(ctx).apply {
                columnCount = 3
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val years = pool.mapNotNull { MetadataResolver.yearOf(it.response) }
                .distinct().sortedDescending()
            (listOf(-1) + years).forEach { year ->
                val chip = makeChip(
                    if (year == -1) getString(R.string.all) else year.toString(),
                    year
                )
                chip.setOnClickListener {
                    selYear = year.takeIf { it != -1 }
                    styleChips(grid, selYear ?: -1)
                }
                grid.addView(chip)
            }
            styleChips(grid, selYear ?: -1)
            return grid
        }

        fun buildRatingTab(): View {
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            listOf(-1.0, 5.0, 6.0, 7.0, 8.0).forEach { min ->
                val label = String.format(java.util.Locale.US, "%.1f", min)
                val row = makeRow(
                    tag = min,
                    title = if (min < 0) {
                        getString(R.string.feed_filter_any_rating)
                    } else {
                        getString(R.string.feed_filter_stars, label)
                    },
                    subtitle = if (min < 0) {
                        getString(R.string.feed_filter_any_sub)
                    } else {
                        getString(R.string.feed_filter_rating_sub, label)
                    },
                    goldStar = min >= 0
                )
                row.setOnClickListener {
                    selRating = min.takeIf { it >= 0 }
                    styleRows(col, selRating ?: -1.0)
                }
                col.addView(row)
            }
            styleRows(col, selRating ?: -1.0)
            return col
        }

        fun buildSourceTab(): View {
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val sources = pool.map { it.apiName }.distinct().sorted()
            (listOf("") + sources).forEach { source ->
                val row = makeRow(
                    tag = source,
                    title = if (source.isEmpty()) {
                        getString(R.string.all)
                    } else {
                        source
                    },
                    subtitle = null,
                    goldStar = null
                )
                row.setOnClickListener {
                    selSource = source.ifEmpty { null }
                    styleRows(col, selSource ?: "")
                }
                col.addView(row)
            }
            styleRows(col, selSource ?: "")
            return col
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.rounded_dialog)
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        root.addView(View(ctx).apply {
            setBackgroundResource(R.drawable.bg_feed_drag_handle)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
        })
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_baseline_tune_24)
            imageTintList = ContextCompat.getColorStateList(ctx, R.color.white)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        })
        titleRow.addView(TextView(ctx).apply {
            text = getString(R.string.feed_filters)
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(
                android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.BOLD
                )
            )
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(10) }
        })
        titleRow.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_baseline_close_24)
            imageTintList = ContextCompat.getColorStateList(ctx, R.color.white)
            setBackgroundResource(R.drawable.bg_feed_circle_button)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(titleRow)

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320)
            ).apply { topMargin = dp(14) }
        }

        var currentTab = 0
        val tabTexts = mutableListOf<TextView>()
        val tabLines = mutableListOf<View>()
        var renderTab: () -> Unit = {}
        renderTab = {
            tabTexts.forEachIndexed { i, tv ->
                tv.setTextColor(
                    if (i == currentTab) Color.WHITE
                    else Color.rgb(158, 158, 158)
                )
            }
            tabLines.forEachIndexed { i, line ->
                line.setBackgroundColor(
                    if (i == currentTab) {
                        ContextCompat.getColor(ctx, R.color.colorPrimaryRed)
                    } else {
                        Color.TRANSPARENT
                    }
                )
            }
            val content = when (currentTab) {
                0 -> buildGenreTab()
                1 -> buildYearTab()
                2 -> buildRatingTab()
                else -> buildSourceTab()
            }
            scroll.removeAllViews()
            scroll.addView(content)
            scroll.scrollTo(0, 0)
        }

        val tabLabels = listOf(
            getString(R.string.feed_filter_genre),
            getString(R.string.year),
            getString(R.string.rating),
            getString(R.string.feed_filter_source)
        )
        val tabsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        tabLabels.indices.forEach { i ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnClickListener {
                    currentTab = i
                    renderTab()
                }
            }
            val tv = TextView(ctx).apply {
                text = tabLabels[i]
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
            }
            val line = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(3))
            }
            cell.addView(tv)
            cell.addView(line)
            tabTexts += tv
            tabLines += line
            tabsRow.addView(cell)
        }
        root.addView(tabsRow)
        root.addView(scroll)

        root.addView(MaterialButton(ctx).apply {
            text = getString(R.string.feed_filter_done)
            setTextColor(Color.WHITE)
            backgroundTintList = ContextCompat.getColorStateList(
                ctx, R.color.colorPrimaryRed
            )
            cornerRadius = dp(26)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(16) }
            setOnClickListener {
                filterGenre = selGenre
                filterYear = selYear
                filterRatingMin = selRating
                filterSource = selSource
                dialog.dismiss()
                updateFilterButtonState()
                val query = binding?.feedSearchInput?.text
                    ?.toString()?.trim().orEmpty()
                applySearch(query)
            }
        })

        dialog.setContentView(root)
        dialog.show()
        // The dialog's own sheet frame stays transparent — the
        // sheet root's rounded_dialog background is the only surface
        // (the 8.82 recipe).
        dialog.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        )?.setBackgroundColor(Color.TRANSPARENT)
        renderTab()
    }

    private fun openWatch(entry: FeedEntry) {
        activity.navigate(
            R.id.navigation_feed_watch,
            Bundle().apply {
                putString("url", entry.response.url)
                putString("apiName", entry.apiName)
                putString("name", entry.response.name)
                putString("posterUrl", entry.response.posterUrl)
            }
        )
    }
}
