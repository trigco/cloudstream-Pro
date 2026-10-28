package com.lagradost.cloudstream3.ui.search

import android.content.Context
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 8.33: some provider families ship poster items whose name
 * is blank or just a placeholder like "Id 82706180" — the real
 * title only exists on the details page. This resolves those
 * titles with the same load() call the player uses, in the
 * background, a few at a time, and caches them persistently so
 * each item is only ever fetched once. Home cards and feed cards
 * both read through here: bind shows the cached title when one
 * exists and kicks off resolution when it doesn't.
 */
object TitleResolver {
    private const val PREFS = "resolved_titles_v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(3)
    private val inFlight = mutableSetOf<String>()
    private val memory = ConcurrentHashMap<String, String>()
    private val typeMemory = ConcurrentHashMap<String, String>()
    private val listeners = mutableListOf<(String, String) -> Unit>()

    fun addListener(listener: (String, String) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: (String, String) -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    /** Names that are placeholders, not titles. */
    fun isIdLike(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return true
        if (n.all { it.isDigit() }) return true
        if (Regex("(?i)^id\\s+\\S+$").matches(n)) return true
        // A single long token of id characters (hex / base keys).
        if (n.length >= 10 && !n.contains(' ') &&
            n.all { it.isLetterOrDigit() || it == '-' || it == '_' } &&
            n.any { it.isDigit() }
        ) {
            return true
        }
        return false
    }

    fun cached(context: Context?, url: String): String? {
        memory[url]?.let { return it }
        if (context == null) return null
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(url, null)
        if (!stored.isNullOrBlank()) memory[url] = stored
        return stored
    }

    /**
     * Phase 8.71: the TvType learned from an item's details page. Feed
     * cards prefer this over the SearchResponse's declared type — some
     * providers tag every animation "Anime" on the listing while the
     * details page (and the player) correctly say "Movie", which made
     * the card tag and the watch-screen tag disagree.
     */
    fun cachedType(context: Context?, url: String): com.lagradost.cloudstream3.TvType? {
        val raw = typeMemory[url]
            ?: if (context == null) {
                null
            } else {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("type:$url", null)
                    ?.also { typeMemory[url] = it }
            }
        return raw?.let {
            runCatching { com.lagradost.cloudstream3.TvType.valueOf(it) }.getOrNull()
        }
    }

    fun storeType(context: Context, url: String, type: com.lagradost.cloudstream3.TvType) {
        typeMemory[url] = type.name
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("type:$url", type.name).apply()
    }

    fun resolve(context: Context, apiName: String, url: String, currentName: String) {
        if (!isIdLike(currentName)) return
        if (cached(context, url) != null) return
        synchronized(inFlight) {
            if (!inFlight.add(url)) return
        }
        scope.launch {
            try {
                semaphore.withPermit {
                    val api = getApiFromNameNull(apiName) ?: return@withPermit
                    val loaded = withTimeoutOrNull(20_000) {
                        APIRepository(api).load(url)
                    }
                    val loadedValue = (loaded as? Resource.Success)?.value
                    val name = loadedValue?.name
                    if (loadedValue != null) {
                        storeType(context, url, loadedValue.type)
                    }
                    if (!name.isNullOrBlank() && !isIdLike(name)) {
                        memory[url] = name
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putString(url, name).apply()
                        val snapshot = synchronized(listeners) { listeners.toList() }
                        withContext(Dispatchers.Main) {
                            snapshot.forEach { it(url, name) }
                        }
                    }
                }
            } catch (e: Exception) {
                // A failed lookup leaves the raw name; a later bind
                // can retry.
            } finally {
                synchronized(inFlight) { inFlight.remove(url) }
            }
        }
    }
}
