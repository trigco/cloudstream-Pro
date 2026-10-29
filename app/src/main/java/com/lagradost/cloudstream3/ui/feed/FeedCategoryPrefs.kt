package com.lagradost.cloudstream3.ui.feed

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray

/**
 * Phase 8.91 — the user's Feed Categories choices (Settings →
 * Content → Feed Categories): which canonical tags are hidden
 * from the Feed, and the order the section-driven rows follow.
 * Stored under `feed_category_prefs_v1`: the hidden set as a
 * string set, the order as a JSON array (tag names can carry
 * spaces/punctuation, so no delimiter joining). An empty saved
 * order means "use the curated default" (FeedSectionTags
 * .CANONICAL_ORDER), not "no order".
 */
object FeedCategoryPrefs {
    private const val KEY = "feed_category_prefs_v1"
    private const val KEY_HIDDEN = KEY + "_hidden"
    private const val KEY_ORDER = KEY + "_order"

    fun hidden(context: Context): Set<String> =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()

    fun setHidden(context: Context, tags: Set<String>) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putStringSet(KEY_HIDDEN, tags)
            .apply()
    }

    fun order(context: Context): List<String> {
        val raw = PreferenceManager
            .getDefaultSharedPreferences(context)
            .getString(KEY_ORDER, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.optString(it) }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setOrder(context: Context, tags: List<String>) {
        val array = JSONArray()
        tags.forEach { array.put(it) }
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY_ORDER, array.toString())
            .apply()
    }
}
