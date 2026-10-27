package com.lagradost.cloudstream3.stremio

import java.net.URLEncoder

/**
 * Stremio addon protocol URL handling, ported from the semantics verified
 * against SkyStream's addon engine (itself modelled on the reference
 * clients):
 *
 * - An addon is installed from its manifest URL. Normalization:
 *   stremio:// -> https://, append /manifest.json when missing, strip the
 *   fragment, and preserve the query string verbatim (addon configuration,
 *   e.g. Torrentio/Comet/MediaFusion, lives in that query string; dropping
 *   it breaks those addons).
 * - A resource URL is {base}/{resource}/{type}/{urlEncodedId}.json with
 *   optional extras as one extra path segment /k=v&k=v before the .json,
 *   and the manifest's original query string re-appended at the very end.
 */
object StremioTransport {

    fun normalizeManifestUrl(raw: String): String {
        var url = raw.trim()
        if (url.startsWith("stremio://")) {
            url = "https://" + url.removePrefix("stremio://")
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        val hash = url.indexOf('#')
        if (hash >= 0) url = url.substring(0, hash)
        val (path, query) = splitQuery(url)
        val fixedPath = when {
            path.endsWith("/manifest.json") -> path
            // Some addons serve the manifest itself at a custom .json path.
            path.endsWith(".json") -> path
            path.endsWith("/") -> path + "manifest.json"
            else -> "$path/manifest.json"
        }
        return fixedPath + query
    }

    fun splitQuery(url: String): Pair<String, String> {
        val q = url.indexOf('?')
        return if (q >= 0) {
            url.substring(0, q) to url.substring(q)
        } else {
            url to ""
        }
    }

    /** Manifest URL minus the trailing /manifest.json and minus the query. */
    fun baseOf(manifestUrl: String): String =
        splitQuery(manifestUrl).first
            .removeSuffix("/manifest.json")
            .trimEnd('/')

    fun resourceUrl(
        manifestUrl: String,
        resource: String,
        type: String,
        id: String,
        extras: Map<String, String> = emptyMap(),
    ): String {
        val (noQuery, query) = splitQuery(manifestUrl)
        val base = noQuery.removeSuffix("/manifest.json").trimEnd('/')
        val sb = StringBuilder(base)
        sb.append('/').append(resource)
            .append('/').append(type)
            .append('/').append(encode(id))
        if (extras.isNotEmpty()) {
            sb.append('/')
            sb.append(
                extras.entries.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
            )
        }
        sb.append(".json")
        sb.append(query)
        return sb.toString()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** series/tv/show are the same type; movie/film likewise. */
    fun canonicalType(type: String): String = when (type.lowercase()) {
        "movie", "film" -> "movie"
        "series", "tv", "show" -> "series"
        "anime" -> "anime"
        "channel", "channels", "tvchannel" -> "channel"
        else -> type.lowercase()
    }

    fun typeAliases(type: String): Set<String> = when (canonicalType(type)) {
        "movie" -> setOf("movie", "film")
        "series" -> setOf("series", "tv", "show")
        "channel" -> setOf("channel", "channels", "tvchannel")
        else -> setOf(canonicalType(type))
    }

    /** A prefix matches the id itself, ids starting with it, or prefix:id. */
    fun idMatches(id: String, prefixes: List<String>?): Boolean {
        if (prefixes.isNullOrEmpty()) return true
        return prefixes.any { p ->
            id == p || id.startsWith(p) || id.startsWith("$p:")
        }
    }
}
