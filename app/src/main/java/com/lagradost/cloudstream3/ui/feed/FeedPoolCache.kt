package com.lagradost.cloudstream3.ui.feed

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import java.io.File

/**
 * Phase 8.99 — disk snapshot of the feed pool, the stale half of
 * stale-while-revalidate. The pool used to live only in the
 * in-memory [FeedPool] object, so every cold start (process
 * death, force-stop, reboot) repainted the Feed from zero — on
 * his ~33 KB/s link that meant 30 s+ of skeleton while every
 * provider's homepage re-downloaded.
 *
 * On each completed build the pool's minimal DISPLAY data is
 * written here: per entry the provider name, url, title, poster,
 * type, score and the section provenance — enough to paint the
 * grid and run a chrome (categories) pass immediately on the
 * next cold open. The fresh build then merges in behind it
 * exactly as page 2 already does.
 *
 * Encoding follows the FeedLikes precedent: one joined string,
 * unit-separator fields, record-separator entries (plus a
 * group separator inside the sections field), kept in a file
 * under filesDir instead of SharedPreferences because a few
 * hundred records outgrow prefs. Writes and reads happen on
 * Dispatchers.IO (the ViewModel guarantees this); a torn or
 * corrupt file degrades to skipped records or an empty result,
 * never a crash — a missing cache is just the old cold path.
 *
 * Entries whose provider is no longer installed are dropped at
 * read time (they could not play anyway), and NO content
 * filtering happens here: hidden/Family-Mode filtering is
 * applied by the ViewModel at paint time, exactly as it is
 * applied to a fresh build at publish time — so a cache written
 * while Family Mode was off cannot leak adult items into a
 * later Family-Mode-on session.
 */
object FeedPoolCache {
    private const val FILE_NAME = "feed_pool_cache_v1.txt"

    /** First paint + categories need the head of the pool, not
     *  all of it; the cap keeps the file (and the read) small. */
    private const val MAX_ENTRIES = 400
    private const val MAX_CHARS = 512 * 1024

    private const val FIELD = "\u001F"
    private const val RECORD = "\u001E"
    private const val SECTION_SEP = "\u001D"

    /** Best-effort snapshot; failures are swallowed (cache is
     *  an optimisation, never a data path). */
    fun save(context: Context, entries: List<FeedEntry>) {
        try {
            val sb = StringBuilder()
            var count = 0
            for (entry in entries) {
                if (count >= MAX_ENTRIES || sb.length > MAX_CHARS) break
                val r = entry.response
                if (count > 0) sb.append(RECORD)
                sb.append(
                    listOf(
                        r.url,
                        entry.apiName,
                        r.name,
                        r.posterUrl ?: "",
                        r.type?.name ?: "",
                        r.score?.toDouble()?.toString() ?: "",
                        entry.sections.joinToString(SECTION_SEP)
                    ).joinToString(FIELD)
                )
                count++
            }
            if (count == 0) return
            File(context.filesDir, FILE_NAME).writeText(sb.toString())
        } catch (e: Exception) {
            // Cache write failed (disk full, IO error) — the
            // feed itself is unaffected.
        }
    }

    /** Rebuild the cached entries. SearchResponses are
     *  reconstructed through the provider's own builders (the
     *  concrete class follows the stored type), so a painted
     *  entry behaves like a fresh one for cards, categories and
     *  opening the watch page. */
    fun read(context: Context): List<FeedEntry> {
        val raw = try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return emptyList()
            file.readText()
        } catch (e: Exception) {
            return emptyList()
        }
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<FeedEntry>()
        for (record in raw.split(RECORD)) {
            val parts = record.split(FIELD)
            if (parts.size != 7) continue
            val url = parts[0]
            val apiName = parts[1]
            val name = parts[2]
            val posterUrl = parts[3].ifEmpty { null }
            val type = parts[4].takeIf { it.isNotEmpty() }?.let {
                runCatching { TvType.valueOf(it) }.getOrNull()
            }
            val score = parts[5].toDoubleOrNull()?.let { Score.from(it, 10) }
            if (url.isBlank() || apiName.isBlank()) continue
            val api = APIHolder.getApiFromNameNull(apiName) ?: continue
            val response = when (type) {
                TvType.Anime, TvType.OVA -> api.newAnimeSearchResponse(
                    name, url, type, fix = false
                ) {
                    this.posterUrl = posterUrl
                    this.score = score
                }

                TvType.Live -> api.newLiveSearchResponse(
                    name, url, type, fix = false
                ) {
                    this.posterUrl = posterUrl
                    this.score = score
                }

                TvType.TvSeries, TvType.Cartoon, TvType.AsianDrama ->
                    api.newTvSeriesSearchResponse(name, url, type, fix = false) {
                        this.posterUrl = posterUrl
                        this.score = score
                    }

                else -> api.newMovieSearchResponse(
                    name, url, type ?: TvType.Movie, fix = false
                ) {
                    this.posterUrl = posterUrl
                    this.score = score
                }
            }
            val sections = if (parts[6].isEmpty()) {
                emptyList()
            } else {
                parts[6].split(SECTION_SEP)
            }
            out += FeedEntry(response, apiName, sections)
        }
        return out
    }
}
