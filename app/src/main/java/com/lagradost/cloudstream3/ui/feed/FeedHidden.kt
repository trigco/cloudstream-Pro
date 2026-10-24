package com.lagradost.cloudstream3.ui.feed

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Phase 8.22 — content the viewer chose to hide from the Feed (long-
 * press a card). Stored as a set of item URLs; hidden items are
 * filtered out of the feed, search results and Up next, for good.
 */
object FeedHidden {
    private const val KEY = "feed_hidden_v1"

    fun getAll(context: Context): Set<String> =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(KEY, emptySet()) ?: emptySet()

    fun isHidden(context: Context, url: String): Boolean =
        getAll(context).contains(url)

    fun hide(context: Context, url: String) {
        val updated = getAll(context).toMutableSet()
        updated.add(url)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putStringSet(KEY, updated)
            .apply()
    }
}
