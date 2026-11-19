package com.xtreeroot.mb.plugin

import com.xtreeroot.mb.local.writeAtomic
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class PluginUpdateNotices(
    private val cache: File,
    private val latestVersion: suspend (String) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    suspend fun check(plugins: List<InstalledPlugin>): Map<UUID, String> = withContext(Dispatchers.IO) {
        lock.withLock {
            val checkedAt = now()
            val saved = runCatching { JSONObject(cache.readText()) }.getOrNull()
            val checked = saved?.optLong("checked", -1) ?: -1
            val versions = saved?.optJSONObject("versions")
            if (versions != null && checked >= 0 && checkedAt - checked in 0 until checkInterval) {
                return@withLock plugins.mapNotNull { plugin ->
                    val version = versions.opt(plugin.revision.toString()) as? String
                    version?.takeIf { plugin.repository != null && isNewerPluginVersion(it, plugin.manifest.version) }
                        ?.let { plugin.revision to it }
                }.toMap()
            }

            val repositories = plugins.filter { it.repository != null }
            if (repositories.isEmpty()) return@withLock emptyMap()
            val available = mutableMapOf<UUID, String>()
            for (plugin in repositories) {
                try {
                    val version = latestVersion(checkNotNull(plugin.repository))
                    ensureActive()
                    if (version != null && isNewerPluginVersion(version, plugin.manifest.version)) available[plugin.revision] = version
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ensureActive()
                }
            }
            val current = JSONObject()
            available.forEach { (revision, version) -> current.put(revision.toString(), version) }
            runCatching { writeAtomic(cache, JSONObject().put("checked", checkedAt).put("versions", current).toString()) }
            available
        }
    }

    private companion object {
        const val checkInterval = 24 * 60 * 60 * 1000L
    }
}
