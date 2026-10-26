package com.lagradost.cloudstream3.utils

import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType

/**
 * Phase 8.50: Family Mode. When on, adult (18+) providers and
 * NSFW content are hidden across Home, Feed, Search and the
 * provider picker, and an NSFW title opened directly shows no
 * poster art. The signal is the type the providers themselves
 * declare (TvType.NSFW), plus a name check for providers that
 * forget to declare it.
 */
object FamilyMode {
    const val KEY = "family_mode_v1"

    private val ADULT_WORDS = listOf(
        "18+", "nsfw", "porn", "adult", "xxx", "erotic", "hentai"
    )

    fun isEnabled(): Boolean = try {
        val ctx = CloudStreamApp.context ?: return false
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .getBoolean(KEY, false)
    } catch (t: Throwable) {
        false
    }

    fun isAdultApi(api: MainAPI): Boolean {
        if (api.supportedTypes.contains(TvType.NSFW)) return true
        val name = api.name.lowercase()
        return ADULT_WORDS.any { name.contains(it) }
    }

    fun isAdultItem(type: TvType?): Boolean = type == TvType.NSFW
}
