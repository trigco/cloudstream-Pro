package com.lagradost.cloudstream3.ui.feed

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.TvType

/**
 * Phase 8.18 — the Feed's taste memory. A like stores the item's provider
 * and type; the feed builder boosts later items that share them, so liking
 * is how the viewer teaches the feed what to surface. Kept in the default
 * shared preferences as one joined string (unit-separator fields,
 * record-separator entries).
 */
object FeedLikes {
    data class Liked(val url: String, val apiName: String, val type: TvType)

    private const val KEY = "feed_likes_v1"
    private const val FIELD = "\u001F"
    private const val RECORD = "\u001E"

    fun getAll(context: Context): List<Liked> {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY, null) ?: return emptyList()
        return raw.split(RECORD).mapNotNull { entry ->
            val parts = entry.split(FIELD)
            if (parts.size != 3) return@mapNotNull null
            val type = runCatching { TvType.valueOf(parts[2]) }.getOrNull()
                ?: return@mapNotNull null
            Liked(parts[0], parts[1], type)
        }
    }

    fun isLiked(context: Context, url: String): Boolean =
        getAll(context).any { it.url == url }

    fun setLiked(context: Context, entry: FeedEntry, liked: Boolean) {
        val current = getAll(context).toMutableList()
        current.removeAll { it.url == entry.response.url }
        if (liked) {
            current.add(
                Liked(entry.response.url, entry.apiName, entry.response.type)
            )
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(
                KEY,
                current.joinToString(RECORD) {
                    listOf(it.url, it.apiName, it.type.name).joinToString(FIELD)
                }
            )
            .apply()
    }
}
