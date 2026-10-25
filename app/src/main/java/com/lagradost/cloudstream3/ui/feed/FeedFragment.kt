package com.lagradost.cloudstream3.ui.feed

import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentFeedBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder.cleanPosterTitle
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
        onHide = { entry -> confirmHide(entry) }
    )

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
        binding.feedRecycler.layoutManager = GridLayoutManager(context, 2)
        binding.feedRecycler.adapter = adapter
        // Fixed size + no change animations: the grid must never
        // relayout or flash mid-scroll (Phase 8.23 jitter fix).
        binding.feedRecycler.setHasFixedSize(true)
        binding.feedRecycler.itemAnimator = null
        buildChips(binding)
        setupHeaderCollapse(binding)
        setupMiniBar(binding)

        binding.feedSwipeRefresh.setOnRefreshListener {
            // A refresh means "show me something else": drop any active
            // search and load the providers' next homepage page.
            if (binding.feedSearchInput.text.isNotEmpty()) {
                binding.feedSearchInput.text.clear()
            }
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
                    binding.feedSwipeRefresh.isRefreshing = false
                    binding.feedLoading.isVisible = false
                    hideSkeleton(binding)
                    // Hidden items stay hidden even in a replayed value.
                    val hidden = context?.let { FeedHidden.getAll(it) } ?: emptySet()
                    pool = resource.value.filterNot { it.response.url in hidden }
                    val query = binding.feedSearchInput.text.toString().trim()
                    if (query.isEmpty()) {
                        binding.feedEmpty.isVisible = pool.isEmpty()
                        adapter.submitList(pool) {
                            listState?.let { state ->
                                binding.feedRecycler.layoutManager
                                    ?.onRestoreInstanceState(state)
                                listState = null
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
                    binding.feedEmpty.isVisible = true
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
        searchJob?.cancel()
        headerAnimator?.cancel()
        FeedPlayer.removeListener(miniListener)
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
                val query = binding?.feedSearchInput?.text?.toString()?.trim().orEmpty()
                if (query.isEmpty()) {
                    binding?.feedEmpty?.isVisible = pool.isEmpty()
                    adapter.submitList(pool)
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

    private fun buildChips(binding: FragmentFeedBinding) {
        val tags = listOf(
            "All", "Horror", "Comedy", "Romance", "Thriller",
            "Action", "Drama", "Family", "Sci-Fi", "Animation"
        )
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
            // The header floats over the grid now: push the content
            // down by its height so the first row starts below it.
            val extra = (6 * resources.displayMetrics.density).toInt()
            binding.feedRecycler.setPadding(
                binding.feedRecycler.paddingLeft, headerFullHeight + extra,
                binding.feedRecycler.paddingRight, binding.feedRecycler.paddingBottom
            )
            binding.feedSkeleton.setPadding(
                binding.feedSkeleton.paddingLeft, headerFullHeight + extra,
                binding.feedSkeleton.paddingRight, binding.feedSkeleton.paddingBottom
            )
        }
        binding.feedRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 12) {
                    animateHeader(binding, show = false)
                } else if (dy < -12) {
                    animateHeader(binding, show = true)
                }
            }
        })
    }

    private fun animateHeader(binding: FragmentFeedBinding, show: Boolean) {
        if (show == headerShown || headerFullHeight == 0) return
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
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        searchJob?.cancel()
        if (query.isEmpty()) {
            // Back to the full pool — also reset the chips to "All".
            selectedChip?.let { styleChip(it, selected = false) }
            val first = binding.feedChips.getChildAt(0) as? TextView
            selectedChip = first
            first?.let { styleChip(it, selected = true) }
            binding.feedEmpty.isVisible = pool.isEmpty()
            adapter.submitList(pool)
            return
        }
        val local = pool.filter {
            it.response.name.contains(query, ignoreCase = true)
        }
        binding.feedEmpty.isVisible = local.isEmpty()
        adapter.submitList(local)
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
                val merged = local + remote.filter {
                    it.response.url !in hidden && seen.add(it.response.url)
                }
                binding.feedEmpty.isVisible = merged.isEmpty()
                adapter.submitList(merged)
            }
        }
        pendingSearch = task
        searchHandler.postDelayed(task, 600L)
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
