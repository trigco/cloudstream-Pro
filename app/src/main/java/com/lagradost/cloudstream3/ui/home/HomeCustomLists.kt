package com.lagradost.cloudstream3.ui.home

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 8.44: user-built home lists that mix categories from
 * DIFFERENT providers (his idea, 2026-10-26). A category is a
 * provider's declared main-page entry, so a list entry is just
 * (apiName, pageName); the page data/index is resolved against
 * the provider at load time, which keeps saved lists valid when
 * a provider updates its page order. Stored as JSON in the
 * default SharedPreferences.
 */
object HomeCustomLists {

    data class Entry(
        val apiName: String,
        val pageName: String,
        /** True when the provider declares no mainPage and the
         *  category was discovered by fetching its homepage. */
        val dynamic: Boolean = false
    )

    data class CustomList(
        val id: String,
        val name: String,
        val entries: List<Entry>
    )

    private const val KEY_LISTS = "home_custom_lists_v1"
    private const val KEY_ACTIVE = "home_custom_active_id"

    private fun prefs(ctx: Context) =
        PreferenceManager.getDefaultSharedPreferences(ctx)

    fun getLists(ctx: Context): List<CustomList> = runCatching {
        val raw = prefs(ctx).getString(KEY_LISTS, null) ?: return emptyList()
        val root = JSONObject(raw)
        val arr = root.optJSONArray("lists") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val entriesArr = obj.optJSONArray("entries") ?: JSONArray()
            val entries = (0 until entriesArr.length()).mapNotNull { j ->
                val e = entriesArr.optJSONObject(j) ?: return@mapNotNull null
                val api = e.optString("a")
                val page = e.optString("p")
                val dyn = e.optInt("d", 0) == 1
                if (api.isBlank() || page.isBlank()) null
                else Entry(api, page, dyn)
            }
            val id = obj.optString("id")
            val name = obj.optString("name")
            if (id.isBlank() || name.isBlank()) null
            else CustomList(id, name, entries)
        }
    }.getOrElse { emptyList() }

    fun saveLists(ctx: Context, lists: List<CustomList>) {
        val arr = JSONArray()
        lists.forEach { list ->
            val entriesArr = JSONArray()
            list.entries.forEach { e ->
                entriesArr.put(JSONObject().apply {
                    put("a", e.apiName)
                    put("p", e.pageName)
                    put("d", if (e.dynamic) 1 else 0)
                })
            }
            arr.put(JSONObject().apply {
                put("id", list.id)
                put("name", list.name)
                put("entries", entriesArr)
            })
        }
        val root = JSONObject().apply { put("lists", arr) }
        prefs(ctx).edit().putString(KEY_LISTS, root.toString()).apply()
    }

    fun upsert(ctx: Context, list: CustomList) {
        val lists = getLists(ctx).toMutableList()
        val idx = lists.indexOfFirst { it.id == list.id }
        if (idx >= 0) lists[idx] = list else lists += list
        saveLists(ctx, lists)
    }

    fun delete(ctx: Context, id: String) {
        saveLists(ctx, getLists(ctx).filterNot { it.id == id })
        if (activeId(ctx) == id) setActiveId(ctx, null)
    }

    fun activeId(ctx: Context): String? =
        prefs(ctx).getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }

    fun setActiveId(ctx: Context, id: String?) {
        prefs(ctx).edit().apply {
            if (id == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id)
        }.apply()
    }

    fun activeList(ctx: Context): CustomList? {
        val id = activeId(ctx) ?: return null
        return getLists(ctx).firstOrNull { it.id == id }
    }

    /**
     * Phase 8.46: providers that declare no mainPage (several
     * multi-provider plugins) expose their categories only by
     * fetching the homepage — discover them on demand.
     */
    suspend fun fetchDynamicCategories(apiName: String): List<String> {
        val api = APIHolder.getApiFromNameNull(apiName) ?: return emptyList()
        val repo = com.lagradost.cloudstream3.ui.APIRepository(api)
        if (!repo.hasMainPage) return emptyList()
        val res = repo.getMainPage(1, null)
        if (res !is com.lagradost.cloudstream3.mvvm.Resource.Success) {
            return emptyList()
        }
        return res.value.filterNotNull()
            .flatMap { it.items }
            .map { it.name }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /** Providers whose categories can only be discovered by fetching. */
    fun dynamicProviders(): List<String> =
        APIHolder.apis
            .filter { it.name.isNotBlank() && it.name != "None" }
            .filter { it.mainPage.isEmpty() }
            .map { it.name }

    fun newId(): String =
        System.currentTimeMillis().toString(36) +
            (0..999).random().toString(36)

    /**
     * Every installed provider's declared homepage categories,
     * grouped by provider. Reading the declarations is cheap —
     * no content is fetched.
     */
    fun allProviderCategories(): List<Pair<String, List<String>>> =
        APIHolder.apis
            .filter { it.name.isNotBlank() && it.name != "None" }
            .map { api -> api.name to api.mainPage.map { it.name } }
            .filter { it.second.isNotEmpty() }
}
