package com.lagradost.cloudstream3.skystream

import org.json.JSONObject

/**
 * SkyStream plugins are not Stremio addons or Nuvio scrapers. Their manifest
 * uses packageName/name/url/version and the JS entrypoints getHome/search/load/loadStreams.
 * This model intentionally preserves the raw manifest for forward compatibility.
 */
data class SkyStreamPluginManifest(
    val packageName: String,
    val name: String,
    val version: Int,
    val sourceUrl: String,
    val raw: JSONObject,
)

data class SkyStreamPluginState(
    val manifest: SkyStreamPluginManifest,
    val enabled: Boolean,
    val lastError: String? = null,
)

object SkyStreamManifestParser {
    fun parsePlugin(raw: JSONObject, repositoryBaseUrl: String): SkyStreamPluginManifest? {
        val packageName = raw.optString("packageName").trim()
        if (packageName.isBlank() || !PACKAGE_NAME.matches(packageName)) return null
        val source = raw.optString("url").trim()
        if (source.isBlank()) return null
        return SkyStreamPluginManifest(
            packageName = packageName,
            name = raw.optString("name", packageName).ifBlank { packageName },
            version = raw.optInt("version", 1),
            sourceUrl = resolveUrl(repositoryBaseUrl, source),
            raw = JSONObject(raw.toString()),
        )
    }

    fun resolveUrl(baseUrl: String, path: String): String {
        if (path.startsWith("https://") || path.startsWith("http://")) return path
        return baseUrl.trimEnd('/') + "/" + path.removePrefix("./").removePrefix("/")
    }

    private val PACKAGE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
}
