package com.lagradost.cloudstream3.skystream

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class StoredSkyStreamPlugin(
    val packageName: String,
    val name: String,
    val sourceUrl: String,
    val version: Int,
    val manifestJson: String,
    val enabled: Boolean,
) {
    fun manifest(): JSONObject = runCatching { JSONObject(manifestJson) }.getOrDefault(JSONObject())
}

data class StoredSkyStreamRepo(
    val url: String,
    val name: String,
    val manifestJson: String,
    val plugins: List<StoredSkyStreamPlugin>,
    val addedAt: Long,
)

object SkyStreamRepository {
    private const val KEY_REPOS = "skystream_repositories_v1"
    private const val KEY_ENABLED = "skystream_plugins_enabled_v1"
    private const val KEY_ERRORS = "skystream_plugin_errors_v1"
    private val SAFE_PACKAGE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun getRepos(ctx: Context): List<StoredSkyStreamRepo> = runCatching {
        val arr = JSONArray(prefs(ctx).getString(KEY_REPOS, "[]"))
        val enabled = prefs(ctx).getStringSet(KEY_ENABLED, emptySet()) ?: emptySet()
        (0 until arr.length()).mapNotNull { i ->
            val repoObj = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = repoObj.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val pluginArr = repoObj.optJSONArray("plugins") ?: JSONArray()
            val plugins = (0 until pluginArr.length()).mapNotNull { j ->
                val item = pluginArr.optJSONObject(j) ?: return@mapNotNull null
                val id = item.optString("packageName")
                if (!SAFE_PACKAGE.matches(id)) return@mapNotNull null
                StoredSkyStreamPlugin(
                    packageName = id,
                    name = item.optString("name", id),
                    sourceUrl = item.optString("sourceUrl"),
                    version = item.optInt("version", 1),
                    manifestJson = item.optJSONObject("manifest")?.toString() ?: "{}",
                    enabled = enabled.contains(id),
                )
            }
            StoredSkyStreamRepo(
                url = url,
                name = repoObj.optString("name", "SkyStream repository"),
                manifestJson = repoObj.optJSONObject("manifest")?.toString() ?: "{}",
                plugins = plugins,
                addedAt = repoObj.optLong("addedAt"),
            )
        }
    }.getOrElse { emptyList() }

    fun setEnabled(ctx: Context, packageName: String, enabled: Boolean) {
        require(SAFE_PACKAGE.matches(packageName)) { "Invalid plugin package name" }
        val next = (prefs(ctx).getStringSet(KEY_ENABLED, emptySet()) ?: emptySet()).toMutableSet()
        if (enabled) next.add(packageName) else next.remove(packageName)
        prefs(ctx).edit().putStringSet(KEY_ENABLED, next).apply()
    }

    fun lastError(ctx: Context, packageName: String): String? =
        (prefs(ctx).getStringSet(KEY_ERRORS, emptySet()) ?: emptySet())
            .firstOrNull { it.startsWith(packageName + "|") }?.substringAfter("|")

    fun setError(ctx: Context, packageName: String, error: String?) {
        val next = (prefs(ctx).getStringSet(KEY_ERRORS, emptySet()) ?: emptySet())
            .filterNot { it.startsWith(packageName + "|") }.toMutableSet()
        if (!error.isNullOrBlank()) next.add(packageName + "|" + error)
        prefs(ctx).edit().putStringSet(KEY_ERRORS, next).apply()
    }

    fun removeRepo(ctx: Context, url: String) {
        val existing = getRepos(ctx)
        val removedIds = existing.firstOrNull { it.url == url }?.plugins?.map { it.packageName }?.toSet() ?: emptySet()
        persist(ctx, existing.filterNot { it.url == url })
        File(ctx.filesDir, "skystream/" + digest(url)).deleteRecursively()
        val enabled = (prefs(ctx).getStringSet(KEY_ENABLED, emptySet()) ?: emptySet()) - removedIds
        val errors = (prefs(ctx).getStringSet(KEY_ERRORS, emptySet()) ?: emptySet())
            .filterNot { entry -> removedIds.any { entry.startsWith(it + "|") } }.toSet()
        prefs(ctx).edit().putStringSet(KEY_ENABLED, enabled).putStringSet(KEY_ERRORS, errors).apply()
    }

    fun lastErrorByPackage(ctx: Context, packageName: String): String? = lastError(ctx, packageName)

    /**
     * Installs a SkyStream repository and its included repositories. This handles the
     * official Universe mega-repository's "repos" list as well as normal pluginLists.
     */
    suspend fun installRepo(ctx: Context, input: String): Result<StoredSkyStreamRepo> = runCatching {
        val rootUrl = resolveRepoUrl(input)
        val current = getRepos(ctx)
        val old = current.firstOrNull { it.url == rootUrl }
        val previouslyEnabled = old?.plugins?.filter { it.enabled }?.map { it.packageName }?.toSet() ?: emptySet()
        val rootResponse = app.get(rootUrl)
        check(rootResponse.code in 200..299) { "Repository returned HTTP " + rootResponse.code }
        val rootManifest = JSONObject(rootResponse.text)
        val discovered = linkedMapOf<String, Pair<JSONObject, String>>()
        val visited = mutableSetOf<String>()

        suspend fun visit(repoUrl: String, depth: Int) {
            require(depth <= 4) { "Repository nesting is too deep" }
            val normalized = repoUrl.substringBefore("#")
            if (!visited.add(normalized)) return
            val response = if (normalized == rootUrl) rootResponse else app.get(normalized)
            check(response.code in 200..299) {
                "Included repository returned HTTP " + response.code + ": " + normalized
            }
            val manifest = JSONObject(response.text)
            val baseUrl = normalized.substringBefore("?").substringBeforeLast("/") + "/"
            val included = manifest.optJSONArray("repos")
            if (included != null) {
                for (i in 0 until included.length()) {
                    val child = included.optString(i).trim()
                    if (child.isNotBlank()) {
                        visit(SkyStreamManifestParser.resolveUrl(baseUrl, child), depth + 1)
                    }
                }
            }
            val lists = manifest.optJSONArray("pluginLists") ?: JSONArray()
            for (i in 0 until lists.length()) {
                val listPath = lists.optString(i).trim()
                if (listPath.isBlank()) continue
                val listUrl = SkyStreamManifestParser.resolveUrl(baseUrl, listPath)
                val listResponse = app.get(listUrl)
                check(listResponse.code in 200..299) {
                    "Plugin list returned HTTP " + listResponse.code + ": " + listUrl
                }
                val listText = listResponse.text.trim()
                val listArray = runCatching { JSONArray(listText) }.getOrNull()
                    ?: runCatching { JSONObject(listText).optJSONArray("plugins") }.getOrNull()
                    ?: throw IllegalStateException("Plugin list is not an array: " + listUrl)
                for (j in 0 until listArray.length()) {
                    val entry = listArray.optJSONObject(j) ?: continue
                    val parsed = SkyStreamManifestParser.parsePlugin(entry, baseUrl) ?: continue
                    if (!discovered.containsKey(parsed.packageName)) discovered[parsed.packageName] = entry to baseUrl
                }
            }
        }

        visit(rootUrl, 0)
        require(discovered.isNotEmpty()) { "No SkyStream plugins found in the repository" }
        val installed = mutableListOf<JSONObject>()
        for ((id, pair) in discovered) {
            require(SAFE_PACKAGE.matches(id)) { "Invalid plugin package name" }
            val parsed = SkyStreamManifestParser.parsePlugin(pair.first, pair.second) ?: continue
            val sourceResponse = app.get(parsed.sourceUrl)
            check(sourceResponse.code in 200..299) {
                "Could not download " + parsed.name + ": HTTP " + sourceResponse.code
            }
            val script = sourceResponse.text
            check(script.isNotBlank()) { "Downloaded script is empty: " + parsed.name }
            val file = pluginFile(ctx, rootUrl, id)
            file.parentFile?.mkdirs()
            file.writeText(script)
            installed.add(
                JSONObject()
                    .put("packageName", id)
                    .put("name", parsed.name)
                    .put("sourceUrl", parsed.sourceUrl)
                    .put("version", parsed.version)
                    .put("manifest", parsed.raw)
            )
        }
        require(installed.isNotEmpty()) { "No valid plugin scripts could be installed" }
        val nextEnabled = (prefs(ctx).getStringSet(KEY_ENABLED, emptySet()) ?: emptySet()).toMutableSet()
        val installedIds = installed.map { it.optString("packageName") }.toSet()
        nextEnabled.removeAll(installedIds - previouslyEnabled)
        val retainedIds = current.filterNot { it.url == rootUrl }.flatMap { it.plugins.map { p -> p.packageName } }.toSet() + installedIds
        nextEnabled.retainAll(retainedIds)
        prefs(ctx).edit().putStringSet(KEY_ENABLED, nextEnabled).apply()
        val storedPlugins = installed.mapNotNull { p ->
            val id = p.optString("packageName")
            if (!SAFE_PACKAGE.matches(id)) return@mapNotNull null
            StoredSkyStreamPlugin(
                packageName = id,
                name = p.optString("name", id),
                sourceUrl = p.optString("sourceUrl"),
                version = p.optInt("version", 1),
                manifestJson = p.optJSONObject("manifest")?.toString() ?: "{}",
                enabled = nextEnabled.contains(id),
            )
        }
        val repo = StoredSkyStreamRepo(
            url = rootUrl,
            name = rootManifest.optString("name", "SkyStream repository"),
            manifestJson = rootManifest.toString(),
            plugins = storedPlugins,
            addedAt = System.currentTimeMillis(),
        )
        persist(ctx, current.filterNot { it.url == rootUrl } + repo)
        getRepos(ctx).first { it.url == rootUrl }
    }

    private fun resolveRepoUrl(input: String): String {
        val raw = input.trim()
        require(raw.isNotEmpty()) { "Enter a repository URL or shortcode" }
        if (raw.equals("universe", ignoreCase = true)) {
            return "https://raw.githubusercontent.com/akashdh11/sky-universe/main/mega_repo.json"
        }
        var url = raw
        if (url.startsWith("skystream://")) url = "https://" + url.removePrefix("skystream://")
        if (!url.startsWith("https://") && !url.startsWith("http://")) url = "https://" + url
        require(url.startsWith("https://")) { "Use HTTPS for repository URLs" }
        return url
    }

    private fun persist(ctx: Context, repos: List<StoredSkyStreamRepo>) {
        val arr = JSONArray()
        for (repo in repos) {
            val plugins = JSONArray()
            for (p in repo.plugins) {
                plugins.put(
                    JSONObject()
                        .put("packageName", p.packageName)
                        .put("name", p.name)
                        .put("sourceUrl", p.sourceUrl)
                        .put("version", p.version)
                        .put("manifest", p.manifest())
                )
            }
            arr.put(
                JSONObject()
                    .put("url", repo.url)
                    .put("name", repo.name)
                    .put("manifest", JSONObject(repo.manifestJson))
                    .put("plugins", plugins)
                    .put("addedAt", repo.addedAt)
            )
        }
        prefs(ctx).edit().putString(KEY_REPOS, arr.toString()).apply()
    }

    fun script(ctx: Context, repoUrl: String, packageName: String): String? =
        runCatching { pluginFile(ctx, repoUrl, packageName).takeIf { it.isFile }?.readText() }.getOrNull()

    private fun pluginFile(ctx: Context, repoUrl: String, packageName: String): File {
        require(SAFE_PACKAGE.matches(packageName)) { "Invalid packageName" }
        return File(ctx.filesDir, "skystream/" + digest(repoUrl) + "/" + packageName + ".js")
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(20)
}
