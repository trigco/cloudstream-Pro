package com.lagradost.cloudstream3.sky

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.utils.FamilyMode
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry that keeps APIHolder in step with the installed-plugin
 * list, following the StremioProviders pattern: one MainAPI per
 * plugin instance (the plugin, or one per sub-provider), stale
 * registrations removed, each registration individually guarded so
 * one bad plugin cannot take the others down. While Family Mode is
 * on, adult-flagged plugins are kept out entirely, exactly like
 * adult Stremio addons. Live engine contexts for instances that are
 * no longer wanted are evicted with them.
 */
object SkyProviders {

    private val registered = ConcurrentHashMap<String, SkyPluginProvider>()

    /** Currently registered instances: instanceKey -> (plugin, sub). */
    @Volatile
    private var enabledSnapshot: Map<String, Pair<StoredSkyPlugin, SkySubProvider?>> =
        emptyMap()

    fun enabledInstances(): Map<String, Pair<StoredSkyPlugin, SkySubProvider?>> =
        enabledSnapshot

    @Synchronized
    fun sync(context: Context) {
        val familyMode = FamilyMode.isEnabled()
        val wanted = LinkedHashMap<String, Pair<StoredSkyPlugin, SkySubProvider?>>()
        for (plugin in SkyRepository.getInstalled(context)) {
            if (!plugin.enabled) continue
            val manifest = plugin.manifest() ?: continue
            if (familyMode && manifest.isAdult) continue
            if (familyMode && FamilyMode.isAdultTitle(manifest.name)) continue
            if (manifest.subProviders.isEmpty()) {
                wanted[SkyEngine.instanceKey(plugin.packageName, null)] =
                    plugin to null
            } else {
                // 8.82: sub-providers switched off in the plugin's
                // settings sheet are not registered.
                val disabledSubs = SkyRepository.disabledSubProviders(
                    context, plugin.packageName
                )
                for (sub in manifest.subProviders) {
                    if (sub.id in disabledSubs) continue
                    wanted[SkyEngine.instanceKey(plugin.packageName, sub.id)] =
                        plugin to sub
                }
            }
        }
        enabledSnapshot = wanted

        // Unregister providers that were removed, disabled or
        // Family-Mode filtered since the last sync.
        for ((key, provider) in registered.entries.toList()) {
            if (key !in wanted) {
                APIHolder.allProviders.remove(provider)
                APIHolder.removePluginMapping(provider)
                registered.remove(key)
            }
        }
        // Register new ones.
        for ((key, pair) in wanted) {
            if (registered.containsKey(key)) continue
            runCatching {
                val provider = SkyPluginProvider(pair.first, pair.second)
                // Name lookup is by provider name; keep ours unique.
                val clash = APIHolder.getApiFromNameNull(provider.name)
                if (clash != null && clash !== provider) {
                    provider.name = provider.name + " (Sky)"
                }
                APIHolder.allProviders.add(provider)
                APIHolder.addPluginMapping(provider)
                registered[key] = provider
            }
        }
        // Warm contexts belong to live instances only.
        SkyEngine.retainOnly(wanted.keys)
    }
}

/**
 * Refresh helper for callers (Settings page, MainActivity init):
 * drops and re-adds registrations so installs, removals, toggles
 * and settings changes take effect immediately.
 */
fun refreshSkyProviders(context: Context) {
    SkyProviders.sync(context)
}
