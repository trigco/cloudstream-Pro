package com.lagradost.cloudstream3.skystream

import org.json.JSONObject

/**
 * SkyStream uses its own repository + JavaScript provider contract. Keep the raw
 * manifest and separate it from Nuvio's and Stremio's data models.
 */
data class SkyStreamPluginManifest(
    val packageName: String,
    val name: String,
    val version: Int,
    val sourceUrl: String,
    val raw: JSONObject,
) {
    val description: String? get() = raw.optString("description").takeIf { it.isNotBlank() }
    val iconUrl: String? get() = raw.optString("iconUrl").takeIf { it.startsWith("http") }
    val providerIds: List<Pair<String, String>>
        get() {
            val providers = raw.optJSONArray("providers") ?: return emptyList()
            return (0 until providers.length()).mapNotNull { index ->
                val item = providers.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to item.optString("name", id).ifBlank { id }
            }
        }
}

object SkyStreamManifestParser {
    private val PACKAGE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    fun parsePlugin(raw: JSONObject, repositoryBaseUrl: String): SkyStreamPluginManifest? {
        val packageName = raw.optString("packageName").trim()
        val sourcePath = raw.optString("url").trim()
        if (!PACKAGE_NAME.matches(packageName) || sourcePath.isBlank()) return null
        return SkyStreamPluginManifest(
            packageName = packageName,
            name = raw.optString("name", packageName).ifBlank { packageName },
            version = raw.optInt("version", 1),
            sourceUrl = resolveUrl(repositoryBaseUrl, sourcePath),
            raw = JSONObject(raw.toString()),
        )
    }

    fun resolveUrl(baseUrl: String, path: String): String {
        val value = path.trim()
        if (value.startsWith("https://") || value.startsWith("http://")) return value
        val base = baseUrl.substringBefore('?').substringBeforeLast('/') + "/"
        return base + value.removePrefix("./").removePrefix("/")
    }
}
