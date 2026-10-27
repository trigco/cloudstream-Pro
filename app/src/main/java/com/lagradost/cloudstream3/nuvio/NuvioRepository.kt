package com.lagradost.cloudstream3.nuvio

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Installed Nuvio repositories, their scrapers' enabled state, the
 * downloaded scraper code (files, not prefs — bundles can be ~1MB),
 * per-scraper settings and per-scraper localStorage.
 */
data class StoredNuvioRepo(
    val manifestUrl: String,
    val addedAt: Long,
    val manifestJson: String?,
    val disabledScrapers: Set<String>,
    val enabledOverrides: Set<String>,
    val lastError: String? = null,
) {
    fun manifest(): NuvioRepoManifest? {
        val raw = manifestJson ?: return null
        return runCatching { NuvioParse.parseRepoManifest(JSONObject(raw)) }
            .getOrNull()
    }

    /** Effective enabled state: manifest default, user overrides win. */
    fun isScraperEnabled(scraper: NuvioScraperInfo): Boolean = when {
        disabledScrapers.contains(scraper.id) -> false
        enabledOverrides.contains(scraper.id) -> true
        else -> scraper.manifestEnabled
    }

    /** Repository base: manifest URL minus its last path segment. */
    fun baseUrl(): String = manifestUrl.substringBeforeLast('/') + "/"

    fun scraperFileUrl(scraper: NuvioScraperInfo): String =
        if (scraper.filename.startsWith("http")) {
            scraper.filename
        } else {
            baseUrl() + scraper.filename.removePrefix("./").removePrefix("/")
        }
}

object NuvioRepository {

    private const val KEY_REPOS = "nuvio_repos_v1"
    private const val KEY_SETTINGS = "nuvio_settings_v1"
    private const val KEY_STORAGE = "nuvio_storage_v1"

    private fun prefs(ctx: Context) =
        PreferenceManager.getDefaultSharedPreferences(ctx)

    // ---- repositories ----

    fun getRepos(ctx: Context): List<StoredNuvioRepo> = runCatching {
        val raw = prefs(ctx).getString(KEY_REPOS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = obj.optString("url")
            if (url.isBlank()) return@mapNotNull null
            StoredNuvioRepo(
                manifestUrl = url,
                addedAt = obj.optLong("addedAt", 0L),
                manifestJson = obj.optString("manifest").takeIf { it.isNotBlank() },
                disabledScrapers = obj.optJSONArray("disabled").toStringSet(),
                enabledOverrides = obj.optJSONArray("overrides").toStringSet(),
                lastError = obj.optString("error").takeIf { it.isNotBlank() },
            )
        }
    }.getOrElse { emptyList() }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return (0 until length()).mapNotNull { i ->
            optString(i).takeIf { it.isNotBlank() }
        }.toSet()
    }

    private fun saveRepos(ctx: Context, repos: List<StoredNuvioRepo>) {
        val arr = JSONArray()
        repos.forEach { repo ->
            val obj = JSONObject()
            obj.put("url", repo.manifestUrl)
            obj.put("addedAt", repo.addedAt)
            repo.manifestJson?.let { obj.put("manifest", it) }
            obj.put("disabled", JSONArray(repo.disabledScrapers.toList()))
            obj.put("overrides", JSONArray(repo.enabledOverrides.toList()))
            repo.lastError?.let { obj.put("error", it) }
            arr.put(obj)
        }
        prefs(ctx).edit().putString(KEY_REPOS, arr.toString()).apply()
    }

    fun addRepo(ctx: Context, manifestUrl: String, rawManifestJson: String): Boolean {
        val current = getRepos(ctx).toMutableList()
        val index = current.indexOfFirst { it.manifestUrl == manifestUrl }
        if (index >= 0) {
            current[index] = current[index].copy(
                manifestJson = rawManifestJson, lastError = null
            )
            saveRepos(ctx, current)
            return false
        }
        current += StoredNuvioRepo(
            manifestUrl = manifestUrl,
            addedAt = System.currentTimeMillis(),
            manifestJson = rawManifestJson,
            disabledScrapers = emptySet(),
            enabledOverrides = emptySet(),
        )
        saveRepos(ctx, current)
        return true
    }

    fun removeRepo(ctx: Context, manifestUrl: String) {
        saveRepos(ctx, getRepos(ctx).filterNot { it.manifestUrl == manifestUrl })
        codeDir(ctx, manifestUrl).deleteRecursively()
    }

    fun setScraperEnabled(
        ctx: Context,
        manifestUrl: String,
        scraper: NuvioScraperInfo,
        enabled: Boolean,
    ) {
        val updated = getRepos(ctx).map { repo ->
            if (repo.manifestUrl != manifestUrl) return@map repo
            var disabled = repo.disabledScrapers
            var overrides = repo.enabledOverrides
            when {
                enabled && scraper.manifestEnabled -> overrides = overrides - scraper.id
                enabled && !scraper.manifestEnabled -> overrides = overrides + scraper.id
                !enabled && scraper.manifestEnabled -> disabled = disabled + scraper.id
                !enabled && !scraper.manifestEnabled -> disabled = disabled - scraper.id
            }
            if (enabled) disabled = disabled - scraper.id
            repo.copy(disabledScrapers = disabled, enabledOverrides = overrides)
        }
        saveRepos(ctx, updated)
    }

    fun activeScrapers(ctx: Context): List<Pair<StoredNuvioRepo, NuvioScraperInfo>> {
        val out = mutableListOf<Pair<StoredNuvioRepo, NuvioScraperInfo>>()
        for (repo in getRepos(ctx)) {
            val manifest = repo.manifest() ?: continue
            for (scraper in manifest.scrapers) {
                if (repo.isScraperEnabled(scraper)) out += repo to scraper
            }
        }
        return out
    }

    // ---- scraper code store (files) ----

    private fun codeDir(ctx: Context, manifestUrl: String): File {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(manifestUrl.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return File(ctx.filesDir, "nuvio_code/$digest")
    }

    private fun codeFile(ctx: Context, repo: StoredNuvioRepo, scraper: NuvioScraperInfo): File =
        File(codeDir(ctx, repo.manifestUrl), scraper.filename.substringAfterLast('/'))

    fun readCode(ctx: Context, repo: StoredNuvioRepo, scraper: NuvioScraperInfo): String? {
        val file = codeFile(ctx, repo, scraper)
        return if (file.exists()) runCatching { file.readText() }.getOrNull() else null
    }

    /** Downloads (or refreshes) one scraper's code. @return true on success. */
    suspend fun fetchCode(
        ctx: Context,
        repo: StoredNuvioRepo,
        scraper: NuvioScraperInfo,
    ): String? {
        val existing = readCode(ctx, repo, scraper)
        return try {
            val response = app.get(repo.scraperFileUrl(scraper))
            if (response.code !in 200..299) return existing
            val text = response.text
            if (text.isBlank()) return existing
            val file = codeFile(ctx, repo, scraper)
            file.parentFile?.mkdirs()
            file.writeText(text)
            text
        } catch (e: Exception) {
            existing
        }
    }

    // ---- per-scraper settings ----

    private fun settingsRoot(ctx: Context): JSONObject = runCatching {
        JSONObject(prefs(ctx).getString(KEY_SETTINGS, null) ?: "{}")
    }.getOrElse { JSONObject() }

    fun scraperSettings(ctx: Context, repoUrl: String, scraperId: String): JSONObject {
        val root = settingsRoot(ctx)
        return root.optJSONObject(scraperKey(repoUrl, scraperId)) ?: JSONObject()
    }

    fun saveScraperSettings(
        ctx: Context,
        repoUrl: String,
        scraperId: String,
        values: JSONObject,
    ) {
        val root = settingsRoot(ctx)
        root.put(scraperKey(repoUrl, scraperId), values)
        prefs(ctx).edit().putString(KEY_SETTINGS, root.toString()).apply()
    }

    fun settingsStamp(ctx: Context, repoUrl: String, scraperId: String): String =
        scraperSettings(ctx, repoUrl, scraperId).toString()

    // ---- per-scraper localStorage ----

    private fun storageRoot(ctx: Context): JSONObject = runCatching {
        JSONObject(prefs(ctx).getString(KEY_STORAGE, null) ?: "{}")
    }.getOrElse { JSONObject() }

    fun storageGet(ctx: Context, repoUrl: String, scraperId: String, key: String): String? {
        val store = storageRoot(ctx).optJSONObject(scraperKey(repoUrl, scraperId))
            ?: return null
        return if (store.has(key) && !store.isNull(key)) store.optString(key) else null
    }

    fun storageSet(ctx: Context, repoUrl: String, scraperId: String, key: String, value: String?) {
        synchronized(this) {
            val root = storageRoot(ctx)
            val sKey = scraperKey(repoUrl, scraperId)
            val store = root.optJSONObject(sKey) ?: JSONObject()
            if (value == null) store.remove(key) else store.put(key, value)
            root.put(sKey, store)
            prefs(ctx).edit().putString(KEY_STORAGE, root.toString()).apply()
        }
    }

    fun storageClear(ctx: Context, repoUrl: String, scraperId: String) {
        synchronized(this) {
            val root = storageRoot(ctx)
            root.remove(scraperKey(repoUrl, scraperId))
            prefs(ctx).edit().putString(KEY_STORAGE, root.toString()).apply()
        }
    }

    private fun scraperKey(repoUrl: String, scraperId: String): String =
        repoUrl + "|" + scraperId

    // ---- collapsed repo groups on the Nuvio Plugins page (8.66) ----

    private const val KEY_COLLAPSED = "nuvio_collapsed_v1"

    fun collapsedRepos(ctx: Context): Set<String> = runCatching {
        val raw = prefs(ctx).getString(KEY_COLLAPSED, null) ?: return emptySet()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { arr.optString(it) }
            .filter { it.isNotBlank() }
            .toSet()
    }.getOrElse { emptySet() }

    fun setRepoCollapsed(ctx: Context, repoUrl: String, collapsed: Boolean) {
        val current = collapsedRepos(ctx).toMutableSet()
        if (collapsed) current += repoUrl else current -= repoUrl
        val arr = JSONArray()
        current.forEach { arr.put(it) }
        prefs(ctx).edit().putString(KEY_COLLAPSED, arr.toString()).apply()
    }
}
