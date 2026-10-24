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
 * Phase 8.18 — builds the Feed: the homepages of every installed provider
 * with a main page, flattened, interleaved round-robin so no provider
 * dominates, deduplicated by URL. Liked types/providers (see FeedLikes)
 * are woven in more densely, which is how the feed learns taste. One page
 * per provider, capped per provider, so the first build stays quick; the
 * pool is kept in memory for the watch page.
 */
class FeedViewModel(application: Application) : AndroidViewModel(application) {
    val feed = MutableLiveData<Resource<List<FeedEntry>>>()
    private var building = false

    fun loadIfNeeded() {
        if (building || feed.value is Resource.Success) return
        build()
    }

    fun refresh() = build()

    private fun build() {
        if (building) return
        building = true
        feed.postValue(Resource.Loading())
        viewModelScope.launch {
            try {
                val entries = buildFeed()
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

    private suspend fun buildFeed(): List<FeedEntry> {
        val providers = apis.filter { it.hasMainPage && it.name != "None" }
        val perProvider: List<List<FeedEntry>> = providers.amap { api ->
            try {
                when (val res = APIRepository(api).getMainPage(1, null)) {
                    is Resource.Success -> res.value
                        .filterNotNull()
                        .flatMap { it.items }
                        .flatMap { it.list }
                        .take(10)
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
        val likedTypes = liked.map { it.type }.toSet()
        val likedApis = liked.map { it.apiName }.toSet()
        fun boosted(e: FeedEntry) =
            e.response.type in likedTypes || e.apiName in likedApis
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
