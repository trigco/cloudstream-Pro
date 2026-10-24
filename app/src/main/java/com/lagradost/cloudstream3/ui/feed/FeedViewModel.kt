package com.lagradost.cloudstream3.ui.feed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import kotlinx.coroutines.launch

/** One feed card: a provider search result plus the provider it came from. */
data class FeedEntry(val response: SearchResponse, val apiName: String)

/** The last built feed, shared with the watch page's "Up next" list. */
object FeedPool {
    @Volatile
    var entries: List<FeedEntry> = emptyList()
}

/**
 * Phase 8.18/8.19 — builds the Feed: the homepages of every installed
 * provider with a main page, flattened, interleaved round-robin so no
 * provider dominates, deduplicated by URL. Liked types/providers (see
 * FeedLikes) are woven in more densely, which is how the feed learns
 * taste. Pull-to-refresh advances to the NEXT homepage page of every
 * provider, so refreshing surfaces genuinely different content; when a
 * page comes back empty the cycle wraps to page 1. The pool is kept in
 * memory for the watch page.
 */
class FeedViewModel(application: Application) : AndroidViewModel(application) {
    val feed = MutableLiveData<Resource<List<FeedEntry>>>()
    private var building = false
    private var page = 1

    fun loadIfNeeded() {
        if (building || feed.value is Resource.Success) return
        build()
    }

    /** Next batch: advance every provider to its next homepage page. */
    fun refresh() {
        page += 1
        build()
    }

    private fun build() {
        if (building) return
        building = true
        feed.postValue(Resource.Loading())
        viewModelScope.launch {
            try {
                var entries = buildFeed(page)
                if (entries.isEmpty() && page > 1) {
                    // Providers ran out of pages — wrap around to page 1.
                    page = 1
                    entries = buildFeed(page)
                }
                FeedPool.entries = entries
                feed.postValue(
                    if (entries.isEmpty()) {
                        Resource.Failure(false, "No feed content")
                    } else {
                        Resource.Success(entries)
                    }
                )
            } catch (e: Exception) {
                feed.postValue(Resource.Failure(false, e.message ?: "Feed failed"))
            } finally {
                building = false
            }
        }
    }

    /** Phase 8.19: search every provider at once (used by the feed search). */
    suspend fun searchAll(query: String): List<FeedEntry> {
        val providers = apis.filter { it.hasMainPage && it.name != "None" }
        val results: List<List<FeedEntry>> = providers.amap { api ->
            try {
                when (val res = APIRepository(api).search(query, 1)) {
                    is Resource.Success -> res.value.items.map { FeedEntry(it, api.name) }
                    else -> emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        val seen = mutableSetOf<String>()
        return results.flatten().filter { seen.add(it.response.url) }
    }

    private suspend fun buildFeed(page: Int): List<FeedEntry> {
        val providers = apis.filter { it.hasMainPage && it.name != "None" }
        val perProvider: List<List<FeedEntry>> = providers.amap { api ->
            try {
                when (val res = APIRepository(api).getMainPage(page, null)) {
                    is Resource.Success -> res.value
                        .filterNotNull()
                        .flatMap { it.items }
                        .flatMap { it.list }
                        .take(30)
                        .map { FeedEntry(it, api.name) }

                    else -> emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        // Round-robin interleave so the feed mixes providers evenly.
        val interleaved = mutableListOf<FeedEntry>()
        val seen = mutableSetOf<String>()
        var index = 0
        var added = true
        while (added) {
            added = false
            for (list in perProvider) {
                val item = list.getOrNull(index) ?: continue
                added = true
                if (seen.add(item.response.url)) interleaved.add(item)
            }
            index++
        }

        // Taste boost: liked types/providers get every third slot.
        val liked = FeedLikes.getAll(getApplication())
        if (liked.isEmpty()) return interleaved
        val likedTypes = liked.map { it.typeName }.toSet()
        val likedApis = liked.map { it.apiName }.toSet()
        fun boosted(e: FeedEntry) =
            e.response.type?.name in likedTypes || e.apiName in likedApis
        val hot = interleaved.filter { boosted(it) }
        if (hot.isEmpty()) return interleaved
        val rest = interleaved.filter { !boosted(it) }
        val out = mutableListOf<FeedEntry>()
        var h = 0
        var r = 0
        while (h < hot.size || r < rest.size) {
            if (h < hot.size) out.add(hot[h++])
            repeat(2) { if (r < rest.size) out.add(rest[r++]) }
        }
        return out
    }
}
