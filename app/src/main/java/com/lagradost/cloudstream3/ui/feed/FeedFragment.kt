package com.lagradost.cloudstream3.ui.feed

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentFeedBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Phase 8.18/8.19 — the Feed tab: every provider's content in one
 * category-free 2-column grid. Cards play directly (see
 * FeedWatchFragment); the heart teaches the feed the viewer's taste.
 * The search bar filters the loaded pool instantly and, for queries of
 * 3+ characters, also searches every provider and merges the results.
 */
class FeedFragment : BaseFragment<FragmentFeedBinding>(
    BindingCreator.Inflate(FragmentFeedBinding::inflate)
) {
    private val viewModel: FeedViewModel by viewModels()
    private val adapter = FeedAdapter(onPlay = { entry -> openWatch(entry) })

    private var pool: List<FeedEntry> = emptyList()
    private var searchJob: Job? = null
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

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
                    pool = resource.value
                    val query = binding.feedSearchInput.text.toString().trim()
                    if (query.isEmpty()) {
                        binding.feedEmpty.isVisible = resource.value.isEmpty()
                        adapter.submitList(resource.value)
                    } else {
                        applySearch(query)
                    }
                }

                is Resource.Failure -> {
                    binding.feedSwipeRefresh.isRefreshing = false
                    binding.feedLoading.isVisible = false
                    binding.feedEmpty.isVisible = true
                }

                is Resource.Loading -> {
                    if (adapter.itemCount == 0) {
                        binding.feedLoading.isVisible = true
                    }
                }

                else -> Unit
            }
        }

        viewModel.loadIfNeeded()
    }

    override fun onDestroyView() {
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        searchJob?.cancel()
        super.onDestroyView()
    }

    /**
     * Instant part: filter the loaded pool by title. Deep part (3+
     * chars, debounced): search all providers and merge new hits in.
     */
    private fun applySearch(query: String) {
        val binding = binding ?: return
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        searchJob?.cancel()
        if (query.isEmpty()) {
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
                val merged = local + remote.filter { seen.add(it.response.url) }
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
            }
        )
    }
}
