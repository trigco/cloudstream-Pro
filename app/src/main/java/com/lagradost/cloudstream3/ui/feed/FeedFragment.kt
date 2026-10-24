package com.lagradost.cloudstream3.ui.feed

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
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

/**
 * Phase 8.18 — the Feed tab: every provider's content in one endless
 * vertical scroll, no categories. Cards play directly (see
 * FeedWatchFragment); the heart teaches the feed the viewer's taste.
 */
class FeedFragment : BaseFragment<FragmentFeedBinding>(
    BindingCreator.Inflate(FragmentFeedBinding::inflate)
) {
    private val viewModel: FeedViewModel by viewModels()
    private val adapter = FeedAdapter(onPlay = { entry -> openWatch(entry) })

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )
    }

    override fun onBindingCreated(binding: FragmentFeedBinding) {
        binding.feedRecycler.layoutManager = LinearLayoutManager(context)
        binding.feedRecycler.adapter = adapter

        binding.feedSwipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }

        observe(viewModel.feed) { resource ->
            when (resource) {
                is Resource.Success -> {
                    binding.feedSwipeRefresh.isRefreshing = false
                    binding.feedLoading.isVisible = false
                    binding.feedEmpty.isVisible = resource.value.isEmpty()
                    adapter.submitList(resource.value)
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
