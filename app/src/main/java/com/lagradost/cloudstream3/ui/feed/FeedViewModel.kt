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
import com.lagradost.cloudstream3.utils.FamilyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One feed card: a provider search result plus the provider it
 * came from. Phase 8.91: [sections] keeps the RAW homepage shelf
 * names the item arrived under (provenance for the Feed's
 * category rows — see FeedSectionTags); an item shelved by
 * several providers/sections carries all of them. Search-built
 * entries have none.
 */
data class FeedEntry(
    val response: SearchResponse,
    val apiName: String,
    val sections: List<String> = emptyList()
)

/** Phase 8.91: fold [next] into the entry already stored for
 *  its URL, unioning the section provenance instead of dropping
 *  the later copy's shelves. */
private fun unionSections(
    map: LinkedHashMap<String, FeedEntry>,
    next: FeedEntry
) {
    val prev = map[next.response.url]
    map[next.response.url] = if (prev == null) {
        next
    } else {
        prev.copy(
            sections = (prev.sections + next.sections).distinct()
        )
    }
}

/** The last built feed, shared with the watch page's "Up next" list. */
object FeedPool {
    @Volatile
    var entries: List<FeedEntry> = emptyList()

    /** Phase 8.22: drop an item everywhere at once (hide action). */
    fun hide(url: String) {
        entries = entries.filterNot { it.response.url == url }
    }
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
    private var firstBuild = true

    fun loadIfNeeded() {
        if (building || feed.value is Resource.Success) return
        build()
    }

    private var prependNext = false
    private var pendingPrepend = false

    /** True while any feed build is running (spinner truthfulness). */
    val isBuilding: Boolean get() = building

    /** Whether the most recently finished build was a pull-refresh. */
    var lastBuildWasPrepend: Boolean = false
        private set

    /**
     * Pull-to-refresh: fetch the providers' next homepage page and
     * put it FIRST, so a refresh visibly changes the top of the
     * feed. (Phase 8.32's append-only refresh looked like nothing
     * happened.) A pull during a build is queued, not dropped.
     */
    fun refresh() {
        if (building) {
            pendingPrepend = true
            return
        }
        prependNext = true
        page += 1
        build()
    }

    /** Endless scroll: fetch the next page and append it. */
    fun loadMore() {
        if (building) return
        page += 1
        build()
    }

    private fun build() {
        if (building) return
        building = true
        feed.postValue(Resource.Loading())
        // Phase 8.92: the whole build runs on Dispatchers.Default.
        // It used to run in viewModelScope's Main dispatcher, and
        // buildFeed's amap fan-out inherits the caller's
        // dispatcher — so every provider's homepage PARSING (the
        // CPU-heavy half of getMainPage, after the suspending
        // network call returns) executed on the UI thread,
        // interleaved across all providers at once. On his
        // Android 8.1 phone the Feed froze for seconds during
        // every load (his "হ্যাং হয়ে আটকে থাকে" report).
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val prepend = prependNext
                prependNext = false
                if (firstBuild) {
                    // Phase 8.32: the pool ACCUMULATES — the
                    // first build pulls two homepage pages per
                    // provider. Phase 8.92: page 1 is PUBLISHED
                    // as soon as it lands instead of waiting for
                    // both pages of every provider (the old first
                    // paint waited for the slowest provider twice
                    // over); page 2 merges in behind it.
                    page = 2
                    publish(buildFeed(1), prepend, suppressEmpty = true)
                    publish(buildFeed(2), prepend, suppressEmpty = false)
                } else {
                    publish(buildFeed(page), prepend, suppressEmpty = false)
                }
            } catch (e: Exception) {
                feed.postValue(Resource.Failure(false, e.message ?: "Feed failed"))
            } finally {
                building = false
                // A pull that arrived mid-build runs now.
                if (pendingPrepend) {
                    pendingPrepend = false
                    refresh()
                }
            }
        }
    }

    /**
     * Merge [fresh] into the shared pool (unioning section
     * provenance per URL — Phase 8.91) and post the result.
     * [suppressEmpty] skips the post when the merge is still
     * empty, so an intermediate first-build page cannot flash
     * the failure/empty state before page 2 lands.
     */
    private suspend fun publish(
        fresh: List<FeedEntry>,
        prepend: Boolean,
        suppressEmpty: Boolean
    ) {
        var entries = if (prepend) {
            val merged = LinkedHashMap<String, FeedEntry>()
            (fresh + FeedPool.entries).forEach {
                unionSections(merged, it)
            }
            merged.values.toList()
        } else {
            val merged = LinkedHashMap<String, FeedEntry>()
            FeedPool.entries.forEach {
                unionSections(merged, it)
            }
            fresh.forEach { unionSections(merged, it) }
            merged.values.toList()
        }
        if (firstBuild) {
            entries = withLikedBoost(entries)
            firstBuild = false
        }
        // Phase 8.22: hidden items never surface.
        val hidden = FeedHidden.getAll(getApplication())
        if (hidden.isNotEmpty()) {
            entries = entries.filterNot { it.response.url in hidden }
        }
        FeedPool.entries = entries
        lastBuildWasPrepend = prepend
        if (entries.isEmpty() && suppressEmpty) return
        feed.postValue(
            if (entries.isEmpty()) {
                Resource.Failure(false, "No feed content")
            } else {
                Resource.Success(entries)
            }
        )
    }

    /**
     * Phase 8.21: liking should FETCH more of the liked kind, not just
     * reorder what the homepages happened to offer. Search providers
     * for the most-liked type and weave the fresh hits through the
     * front of the feed.
     */
    private suspend fun withLikedBoost(entries: List<FeedEntry>): List<FeedEntry> {
        if (entries.isEmpty()) return entries
        val liked = FeedLikes.getAll(getApplication())
        if (liked.isEmpty()) return entries
        val topType = liked.groupingBy { it.typeName }.eachCount()
            .maxByOrNull { it.value }?.key
        val label = when (topType) {
            "Anime" -> "Anime"
            "AsianDrama" -> "Drama"
            "TvSeries" -> "Series"
            "Movie" -> "Movie"
            "Cartoon" -> "Cartoon"
            "Documentary" -> "Documentary"
            else -> null
        } ?: return entries
        val have = entries.map { it.response.url }.toSet()
        val boosted = searchAll(label).filter { it.response.url !in have }
        if (boosted.isEmpty()) return entries
        val out = mutableListOf<FeedEntry>()
        var b = 0
        var r = 0
        while (b < boosted.size || r < entries.size) {
            if (b < boosted.size) out.add(boosted[b++])
            if (r < entries.size) out.add(entries[r++])
        }
        return out
    }

    /** Phase 8.19: search every provider at once (used by the feed search).
     *  Phase 8.92: runs on Dispatchers.Default — the caller is the
     *  fragment's Main lifecycleScope, and the amap fan-out would
     *  otherwise parse every provider's results on the UI thread
     *  (same hang class as the pool build). */
    suspend fun searchAll(query: String): List<FeedEntry> =
        withContext(Dispatchers.Default) { searchAllOnBackground(query) }

    private suspend fun searchAllOnBackground(query: String): List<FeedEntry> {
        val familyOn = FamilyMode.isEnabled()
        val providers = apis.filter {
            it.hasMainPage && it.name != "None" &&
                (!familyOn || !FamilyMode.isAdultApi(it))
        }
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
        return results.flatten()
            .filterNot {
                FamilyMode.isEnabled() &&
                    FamilyMode.isAdultItem(it.response)
            }
            .filter { seen.add(it.response.url) }
    }

    private suspend fun buildFeed(page: Int): List<FeedEntry> {
        val familyOn = FamilyMode.isEnabled()
        val providers = apis.filter {
            it.hasMainPage && it.name != "None" &&
                (!familyOn || !FamilyMode.isAdultApi(it))
        }
        val perProvider: List<List<FeedEntry>> = providers.amap { api ->
            try {
                when (val res = APIRepository(api).getMainPage(page, null)) {
                    is Resource.Success -> res.value
                        .filterNotNull()
                        .flatMap { it.items }
                        // Phase 8.51: drop adult-labelled shelves
                        // whole, then judge items by title too.
                        .filterNot {
                            familyOn && FamilyMode.isAdultSection(it.name)
                        }
                        // Phase 8.91: the shelf name rides along
                        // with each item (it used to be flattened
                        // away here) — it is the provenance the
                        // Feed's category rows are built from.
                        .flatMap { shelf ->
                            shelf.list.map { item -> item to shelf.name }
                        }
                        .filterNot {
                            familyOn && FamilyMode.isAdultItem(it.first)
                        }
                        .take(40)
                        .map { (item, shelf) ->
                            FeedEntry(item, api.name, listOf(shelf))
                        }

                    else -> emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        // Round-robin interleave so the feed mixes providers evenly.
        // Phase 8.91: a URL shelved twice keeps its FIRST slot but
        // unions the later copy's section provenance.
        val interleaved = mutableListOf<FeedEntry>()
        val positions = HashMap<String, Int>()
        var index = 0
        var added = true
        while (added) {
            added = false
            for (list in perProvider) {
                val item = list.getOrNull(index) ?: continue
                added = true
                val pos = positions[item.response.url]
                if (pos == null) {
                    positions[item.response.url] = interleaved.size
                    interleaved.add(item)
                } else {
                    val prev = interleaved[pos]
                    interleaved[pos] = prev.copy(
                        sections = (prev.sections + item.sections)
                            .distinct()
                    )
                }
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
