package com.lagradost.cloudstream3.stremio

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * One installed addon. The parsed manifest JSON is persisted with it so
 * provider names, logos and capability checks work instantly at app
 * start (and offline) without re-fetching every manifest.
 */
data class StoredAddon(
    val manifestUrl: String,
    val enabled: Boolean,
    val addedAt: Long,
    val manifestJson: String?,
    val builtIn: Boolean = false,
    val lastError: String? = null,
) {
    fun manifest(): StremioManifest? {
        val raw = manifestJson ?: return null
        return runCatching { StremioParse.parseManifest(JSONObject(raw)) }
            .getOrNull()
    }
}

object AddonRepository {

    private const val KEY = "stremio_addons_v1"

    const val CINEMETA_URL = "https://v3-cinemeta.strem.io/manifest.json"

    private fun prefs(ctx: Context) =
        PreferenceManager.getDefaultSharedPreferences(ctx)

    fun getAddons(ctx: Context): List<StoredAddon> {
        val stored = readStored(ctx)
        // The built-in Cinemeta addon is always present (it is the meta
        // fallback for catalog-only addons). It can be disabled but not
        // removed; its row is only persisted once the user touches it.
        if (stored.none { it.manifestUrl == CINEMETA_URL }) {
            return listOf(defaultCinemeta()) + stored
        }
        return stored
    }

    private fun defaultCinemeta(): StoredAddon = StoredAddon(
        manifestUrl = CINEMETA_URL,
        // Default OFF (his order, 2026-10-28): the built-in catalog stays
        // available in the list but does not activate itself; the user
        // turns it on if he wants it. Existing installs keep whatever
        // state they already stored.
        enabled = false,
        addedAt = 0L,
        manifestJson = null,
        builtIn = true,
    )

    private fun readStored(ctx: Context): List<StoredAddon> = runCatching {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = obj.optString("url")
            if (url.isBlank()) return@mapNotNull null
            StoredAddon(
                manifestUrl = url,
                enabled = obj.optBoolean("enabled", true),
                addedAt = obj.optLong("addedAt", 0L),
                manifestJson = obj.optString("manifest").takeIf { it.isNotBlank() },
                builtIn = obj.optBoolean("builtIn", false) || url == CINEMETA_URL,
                lastError = obj.optString("error").takeIf { it.isNotBlank() },
            )
        }
    }.getOrElse { emptyList() }

    private fun save(ctx: Context, addons: List<StoredAddon>) {
        val arr = JSONArray()
        addons.forEach { addon ->
            val obj = JSONObject()
            obj.put("url", addon.manifestUrl)
            obj.put("enabled", addon.enabled)
            obj.put("addedAt", addon.addedAt)
            addon.manifestJson?.let { obj.put("manifest", it) }
            if (addon.builtIn) obj.put("builtIn", true)
            addon.lastError?.let { obj.put("error", it) }
            arr.put(obj)
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Persists the full effective list (including the built-in row). */
    private fun saveEffective(ctx: Context, addons: List<StoredAddon>) =
        save(ctx, addons)

    /** @return false when the addon is already installed. */
    fun add(
        ctx: Context,
        manifestUrl: String,
        manifest: StremioManifest,
        rawManifestJson: String,
    ): Boolean {
        val current = getAddons(ctx).toMutableList()
        if (current.any { it.manifestUrl == manifestUrl }) return false
        current += StoredAddon(
            manifestUrl = manifestUrl,
            enabled = true,
            addedAt = System.currentTimeMillis(),
            manifestJson = rawManifestJson,
            builtIn = false,
        )
        saveEffective(ctx, current)
        return true
    }

    fun remove(ctx: Context, manifestUrl: String) {
        val current = getAddons(ctx)
        if (current.firstOrNull { it.manifestUrl == manifestUrl }?.builtIn == true) {
            return // built-in can be disabled, never removed
        }
        saveEffective(ctx, current.filterNot { it.manifestUrl == manifestUrl })
    }

    fun setEnabled(ctx: Context, manifestUrl: String, enabled: Boolean) {
        val current = getAddons(ctx).map {
            if (it.manifestUrl == manifestUrl) it.copy(enabled = enabled) else it
        }
        saveEffective(ctx, current)
    }

    fun updateManifest(ctx: Context, manifestUrl: String, rawManifestJson: String) {
        val current = getAddons(ctx).map {
            if (it.manifestUrl == manifestUrl) {
                it.copy(manifestJson = rawManifestJson, lastError = null)
            } else {
                it
            }
        }
        saveEffective(ctx, current)
    }

    fun setError(ctx: Context, manifestUrl: String, error: String?) {
        val current = getAddons(ctx).map {
            if (it.manifestUrl == manifestUrl) it.copy(lastError = error) else it
        }
        saveEffective(ctx, current)
    }
}
