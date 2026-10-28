package com.lagradost.cloudstream3.nuvio

import com.lagradost.cloudstream3.stremio.StremioSubtitle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Nuvio plugin contracts, mirroring the shapes verified against
 * SkyStream's Nuvio engine (itself modelled on NuvioMobile):
 *
 * A repository is a JSON manifest listing scrapers. Each scraper is a
 * JS file that exports getStreams(tmdbId, mediaType, season, episode)
 * with mediaType "movie" | "tv" and returns Stremio-shaped link
 * objects. Scrapers with settings expose onSettings() returning a
 * form layout; the values are handed back as SCRAPER_SETTINGS.
 */

data class NuvioScraperInfo(
    val id: String,
    val name: String,
    val version: String,
    val filename: String,
    val description: String,
    val supportedTypes: List<String>,
    val manifestEnabled: Boolean,
    val hasSettings: Boolean,
    val logo: String?,
    val formats: List<String>,
) {
    /** Nuvio says movie/tv; Stremio and this app say movie/series. */
    fun supportsType(canonicalType: String): Boolean {
        val wanted = normalizeType(canonicalType)
        return supportedTypes.map { normalizeType(it) }.contains(wanted)
    }

    companion object {
        fun normalizeType(type: String): String = when (type.lowercase()) {
            "series", "show", "anime" -> "tv"
            "film" -> "movie"
            else -> type.lowercase()
        }
    }
}

data class NuvioRepoManifest(
    val name: String,
    val version: String?,
    val scrapers: List<NuvioScraperInfo>,
)

data class NuvioSettingsField(
    val type: String, // header | info | text | select | toggle | number
    val key: String,
    val label: String,
    val description: String?,
    val placeholder: String?,
    val isPassword: Boolean,
    /** Raw JSON value (string/boolean/number) or null. */
    val defaultRaw: Any?,
    val options: List<Pair<String, String>>,
)

data class NuvioStreamResult(
    val scraperId: String,
    val scraperName: String,
    val title: String,
    val url: String?,
    val quality: String?,
    val headers: Map<String, String>,
    val infoHash: String?,
    val subtitles: List<StremioSubtitle>,
)

object NuvioParse {

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            optString(i).takeIf { it.isNotBlank() }
        }
    }

    fun parseRepoManifest(json: JSONObject): NuvioRepoManifest {
        val scrapers = mutableListOf<NuvioScraperInfo>()
        val arr = json.optJSONArray("scrapers")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val id = s.optString("id")
                val filename = s.optString("filename")
                if (id.isBlank() || filename.isBlank()) continue
                val formats = s.optJSONArray("formats").toStringList()
                    .ifEmpty { s.optJSONArray("supportedFormats").toStringList() }
                scrapers += NuvioScraperInfo(
                    id = id,
                    name = s.optStringOrNull("name") ?: id,
                    version = s.optStringOrNull("version") ?: "0.0.0",
                    filename = filename,
                    description = s.optString("description"),
                    supportedTypes = s.optJSONArray("supportedTypes").toStringList()
                        .ifEmpty { listOf("movie", "tv") },
                    manifestEnabled = s.optBoolean("enabled", true),
                    hasSettings = s.optBoolean("hasSettings", false),
                    logo = s.optStringOrNull("logo"),
                    formats = formats,
                )
            }
        }
        return NuvioRepoManifest(
            name = json.optStringOrNull("name") ?: "Nuvio repository",
            version = json.optStringOrNull("version"),
            scrapers = scrapers,
        )
    }

    fun parseSettingsLayout(raw: JSONArray?): List<NuvioSettingsField> {
        if (raw == null) return emptyList()
        val known = setOf("header", "info", "text", "select", "toggle", "number")
        val out = mutableListOf<NuvioSettingsField>()
        for (i in 0 until raw.length()) {
            val f = raw.optJSONObject(i) ?: continue
            val type = f.optString("type", "info").lowercase()
            if (type !in known) continue
            val options = mutableListOf<Pair<String, String>>()
            val rawOptions = f.optJSONArray("options")
            if (rawOptions != null) {
                for (j in 0 until rawOptions.length()) {
                    when (val entry = rawOptions.opt(j)) {
                        is JSONObject -> {
                            val value = entry.optString("value")
                            if (value.isNotEmpty()) {
                                options += (entry.optStringOrNull("label") ?: value) to value
                            }
                        }
                        is String -> options += entry to entry
                    }
                }
            }
            val defaultRaw: Any? = when {
                f.has("defaultValue") && !f.isNull("defaultValue") -> f.opt("defaultValue")
                f.has("default") && !f.isNull("default") -> f.opt("default")
                else -> null
            }
            out += NuvioSettingsField(
                type = type,
                key = f.optStringOrNull("key") ?: f.optString("name"),
                label = f.optString("label"),
                description = f.optStringOrNull("description"),
                placeholder = f.optStringOrNull("placeholder"),
                isPassword = f.optBoolean("isPassword", false),
                defaultRaw = defaultRaw,
                options = options,
            )
        }
        return out
    }

    /** Loose stream-object parsing: url may be a string or a nested
     * object (under url/link/stream/file), headers may hide in
     * behaviorHints.proxyHeaders.request. */
    fun parseStreamResult(
        json: JSONObject,
        scraperId: String,
        scraperName: String,
    ): NuvioStreamResult? {
        var url: String? = null
        var embedded: JSONObject? = null
        for (candidateKey in listOf("url", "link", "stream", "file", "externalUrl")) {
            val candidate = json.opt(candidateKey) ?: continue
            if (candidate is String && candidate.isNotBlank()) {
                url = candidate.trim()
                break
            }
            if (candidate is JSONObject) {
                val nested = candidate.optString("url")
                if (nested.isNotBlank()) {
                    url = nested.trim()
                    embedded = candidate
                    break
                }
            }
        }
        val source = embedded ?: json
        val infoHash = source.optStringOrNull("infoHash")
        if (url == null && infoHash == null) return null

        val headers = mutableMapOf<String, String>()
        fun readHeaders(obj: JSONObject?) {
            if (obj == null) return
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                obj.optStringOrNull(k)?.let { headers[k] = it }
            }
        }
        readHeaders(source.optJSONObject("headers"))
        readHeaders(
            source.optJSONObject("behaviorHints")
                ?.optJSONObject("proxyHeaders")
                ?.optJSONObject("request")
                ?.optJSONObject("headers")
        )

        val subs = mutableListOf<StremioSubtitle>()
        val subArr = source.optJSONArray("subtitles")
        if (subArr != null) {
            for (i in 0 until subArr.length()) {
                val s = subArr.optJSONObject(i) ?: continue
                val subUrl = s.optString("url")
                if (subUrl.isBlank()) continue
                subs += StremioSubtitle(
                    id = s.optStringOrNull("id"),
                    url = subUrl,
                    lang = s.optString("lang", "Unknown"),
                )
            }
        }

        val title = (source.optStringOrNull("title")
            ?: source.optStringOrNull("name")
            ?: scraperName).replace(ZERO_WIDTH, "")
        return NuvioStreamResult(
            scraperId = scraperId,
            scraperName = scraperName,
            title = title,
            url = url,
            quality = source.optStringOrNull("quality"),
            headers = headers,
            infoHash = infoHash,
            subtitles = subs,
        )
    }

    // Some providers pad titles with zero-width characters to force
    // their own sort order; strip them.
    private val ZERO_WIDTH = Regex("[\\u200B-\\u200D\\uFEFF]")
}
