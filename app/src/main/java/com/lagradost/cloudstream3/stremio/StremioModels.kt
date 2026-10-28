package com.lagradost.cloudstream3.stremio

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tolerant Stremio addon models. Real-world manifests are messy:
 * resources arrive as bare strings or as objects, types use aliases,
 * fields go missing. Every parser here is opt*-based and never throws.
 */

data class CatalogExtra(
    val name: String,
    val isRequired: Boolean,
    val options: List<String>,
)

data class StremioCatalog(
    val type: String,
    val id: String,
    val name: String,
    val extras: List<CatalogExtra>,
    val genres: List<String>,
) {
    fun supportsExtra(name: String): Boolean =
        extras.any { it.name.equals(name, ignoreCase = true) }
}

data class StremioResource(
    val name: String,
    /** Empty means "all of the manifest's types". */
    val types: List<String>,
    /** Empty means "no prefix restriction". */
    val idPrefixes: List<String>,
)

data class StremioManifest(
    val id: String,
    val name: String,
    val version: String?,
    val description: String?,
    val logo: String?,
    val background: String?,
    val types: List<String>,
    val resources: List<StremioResource>,
    val catalogs: List<StremioCatalog>,
    val idPrefixes: List<String>,
    val adult: Boolean,
) {
    fun resource(name: String): StremioResource? =
        resources.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * Whether this addon should be asked for [resource] of [type]/[id].
     * Declared object resources gate on their types + idPrefixes; a bare
     * string declaration falls back to the manifest-level types/prefixes;
     * an undeclared meta/stream/subtitles resource is still asked (many
     * manifests under-declare) and a 404 just yields nothing.
     */
    fun supportsResource(resourceName: String, type: String, id: String): Boolean {
        val declared = resource(resourceName)
        if (declared == null) {
            return resourceName == "meta" ||
                resourceName == "stream" ||
                resourceName == "subtitles"
        }
        val aliases = StremioTransport.typeAliases(type)
        val typeOk = declared.types.isEmpty() ||
            declared.types.any { aliases.contains(StremioTransport.canonicalType(it)) || aliases.contains(it.lowercase()) } ||
            // 'other' is the catch-all type: ask it for everything.
            declared.types.any { it.equals("other", ignoreCase = true) }
        if (!typeOk) return false
        val prefixes = declared.idPrefixes.ifEmpty { idPrefixes }
        return StremioTransport.idMatches(id, prefixes)
    }
}

data class StremioVideo(
    val id: String,
    val title: String?,
    val season: Int?,
    val episode: Int?,
    val released: String?,
    val thumbnail: String?,
    val overview: String?,
)

data class StremioMeta(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val description: String?,
    /** e.g. "2019" or "2019-2021"; some addons use a plain year field. */
    val releaseInfo: String?,
    val imdbRating: Double?,
    val genres: List<String>,
    val runtime: String?,
    val cast: List<String>,
    val videos: List<StremioVideo>,
)

data class StremioSubtitle(
    val id: String?,
    val url: String,
    val lang: String,
)

data class StremioStream(
    val name: String?,
    val title: String?,
    val description: String?,
    val url: String?,
    val ytId: String?,
    val infoHash: String?,
    val externalUrl: String?,
    val bingeGroup: String?,
    val notWebReady: Boolean,
    /** behaviorHints.proxyHeaders.request.headers — MUST reach the player. */
    val proxyRequestHeaders: Map<String, String>,
    val videoSize: Long?,
    val filename: String?,
    val subtitles: List<StremioSubtitle>,
) {
    /** Step 1 of the bridge: only direct URLs are playable in-app. */
    val isDirect: Boolean get() = !url.isNullOrBlank()
}

object StremioParse {

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            optString(i).takeIf { it.isNotBlank() }
        }
    }

    fun parseManifest(json: JSONObject): StremioManifest {
        val resources = mutableListOf<StremioResource>()
        val resArr = json.optJSONArray("resources")
        if (resArr != null) {
            for (i in 0 until resArr.length()) {
                val item = resArr.opt(i)
                when (item) {
                    is String -> resources += StremioResource(item, emptyList(), emptyList())
                    is JSONObject -> {
                        val name = item.optString("name")
                        if (name.isNotBlank()) {
                            resources += StremioResource(
                                name = name,
                                types = item.optJSONArray("types").toStringList(),
                                idPrefixes = item.optJSONArray("idPrefixes").toStringList(),
                            )
                        }
                    }
                }
            }
        }

        val catalogs = mutableListOf<StremioCatalog>()
        val catArr = json.optJSONArray("catalogs")
        if (catArr != null) {
            for (i in 0 until catArr.length()) {
                val cat = catArr.optJSONObject(i) ?: continue
                val type = cat.optString("type")
                val id = cat.optString("id")
                if (type.isBlank() || id.isBlank()) continue
                val extras = mutableListOf<CatalogExtra>()
                // Modern shape: extra[] ; legacy: extraSupported / extraRequired.
                val extraArr = cat.optJSONArray("extra")
                if (extraArr != null) {
                    for (j in 0 until extraArr.length()) {
                        val e = extraArr.optJSONObject(j) ?: continue
                        val eName = e.optString("name")
                        if (eName.isBlank()) continue
                        extras += CatalogExtra(
                            name = eName,
                            isRequired = e.optBoolean("isRequired", false),
                            options = e.optJSONArray("options").toStringList(),
                        )
                    }
                } else {
                    val supported = cat.optJSONArray("extraSupported").toStringList()
                    val required = cat.optJSONArray("extraRequired").toStringList()
                    (supported + required).distinct().forEach { eName ->
                        extras += CatalogExtra(eName, required.contains(eName), emptyList())
                    }
                }
                catalogs += StremioCatalog(
                    type = type,
                    id = id,
                    name = cat.optStringOrNull("name") ?: id,
                    extras = extras,
                    genres = cat.optJSONArray("genres").toStringList(),
                )
            }
        }

        val hints = json.optJSONObject("behaviorHints")
        return StremioManifest(
            id = json.optString("id"),
            name = json.optStringOrNull("name") ?: json.optString("id", "Addon"),
            version = json.optStringOrNull("version"),
            description = json.optStringOrNull("description"),
            logo = json.optStringOrNull("logo"),
            background = json.optStringOrNull("background"),
            types = json.optJSONArray("types").toStringList(),
            resources = resources,
            catalogs = catalogs,
            idPrefixes = json.optJSONArray("idPrefixes").toStringList(),
            adult = hints?.optBoolean("adult", false) ?: false,
        )
    }

    fun parseMeta(json: JSONObject): StremioMeta? {
        val id = json.optString("id")
        val name = json.optString("name")
        if (id.isBlank() || name.isBlank()) return null

        val videos = mutableListOf<StremioVideo>()
        val vidArr = json.optJSONArray("videos")
        if (vidArr != null) {
            for (i in 0 until vidArr.length()) {
                val v = vidArr.optJSONObject(i) ?: continue
                val vid = v.optString("id")
                if (vid.isBlank()) continue
                videos += StremioVideo(
                    id = vid,
                    title = v.optStringOrNull("title") ?: v.optStringOrNull("name"),
                    season = if (v.has("season")) v.optInt("season") else null,
                    episode = if (v.has("episode")) v.optInt("episode") else null,
                    released = v.optStringOrNull("released") ?: v.optStringOrNull("firstAired"),
                    thumbnail = v.optStringOrNull("thumbnail"),
                    overview = v.optStringOrNull("overview") ?: v.optStringOrNull("description"),
                )
            }
        }

        val cast = mutableListOf<String>()
        val castArr = json.optJSONArray("cast")
        if (castArr != null) {
            for (i in 0 until castArr.length()) {
                when (val c = castArr.opt(i)) {
                    is String -> if (c.isNotBlank()) cast += c
                    is JSONObject -> c.optStringOrNull("name")?.let { cast += it }
                }
            }
        }

        val ratingRaw = json.optStringOrNull("imdbRating")
        return StremioMeta(
            id = id,
            type = json.optString("type", "movie"),
            name = name,
            poster = json.optStringOrNull("poster"),
            background = json.optStringOrNull("background"),
            description = json.optStringOrNull("description") ?: json.optStringOrNull("overview"),
            releaseInfo = json.optStringOrNull("releaseInfo")
                ?: json.optStringOrNull("year")?.toString()
                ?: json.optStringOrNull("released")?.take(4),
            imdbRating = ratingRaw?.toDoubleOrNull(),
            genres = json.optJSONArray("genres").toStringList(),
            runtime = json.optStringOrNull("runtime"),
            cast = cast,
            videos = videos,
        )
    }

    fun parseCatalogMetas(json: JSONObject): List<StremioMeta> {
        val arr = json.optJSONArray("metas") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { parseMeta(it) }
        }
    }

    fun parseStreams(json: JSONObject): List<StremioStream> {
        val arr = json.optJSONArray("streams") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val hints = s.optJSONObject("behaviorHints")
            val proxyHeaders = mutableMapOf<String, String>()
            val reqHeaders = hints
                ?.optJSONObject("proxyHeaders")
                ?.optJSONObject("request")
                ?.optJSONObject("headers")
            if (reqHeaders != null) {
                val keys = reqHeaders.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    reqHeaders.optStringOrNull(k)?.let { proxyHeaders[k] = it }
                }
            }
            val subs = mutableListOf<StremioSubtitle>()
            val subArr = s.optJSONArray("subtitles")
            if (subArr != null) {
                for (j in 0 until subArr.length()) {
                    val sub = subArr.optJSONObject(j) ?: continue
                    val url = sub.optString("url")
                    if (url.isBlank()) continue
                    subs += StremioSubtitle(
                        id = sub.optStringOrNull("id"),
                        url = url,
                        lang = sub.optString("lang", "Unknown"),
                    )
                }
            }
            StremioStream(
                name = s.optStringOrNull("name"),
                title = s.optStringOrNull("title"),
                description = s.optStringOrNull("description"),
                url = s.optStringOrNull("url"),
                ytId = s.optStringOrNull("ytId"),
                infoHash = s.optStringOrNull("infoHash"),
                externalUrl = s.optStringOrNull("externalUrl"),
                bingeGroup = hints?.optStringOrNull("bingeGroup"),
                notWebReady = hints?.optBoolean("notWebReady", false) ?: false,
                proxyRequestHeaders = proxyHeaders,
                videoSize = if (hints != null && hints.has("videoSize")) {
                    hints.optLong("videoSize").takeIf { it > 0 }
                } else {
                    null
                },
                filename = hints?.optStringOrNull("filename"),
                subtitles = subs,
            )
        }
    }

    fun parseSubtitles(json: JSONObject): List<StremioSubtitle> {
        val arr = json.optJSONArray("subtitles") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = s.optString("url")
            if (url.isBlank()) return@mapNotNull null
            StremioSubtitle(
                id = s.optStringOrNull("id"),
                url = url,
                lang = s.optString("lang", "Unknown"),
            )
        }
    }
}
