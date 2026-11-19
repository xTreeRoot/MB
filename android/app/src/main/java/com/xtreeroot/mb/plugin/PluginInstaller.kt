package com.xtreeroot.mb.plugin

import android.content.ContentResolver
import android.net.Uri
import com.xtreeroot.mb.local.localSourceId
import com.xtreeroot.mb.local.writeAtomic
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

internal data class InstalledPlugin(
    val manifest: PluginManifest,
    val revision: UUID,
    val directory: File,
    val dataDirectory: File,
    val repository: String?,
    val enabled: Boolean,
) {
    val id: String get() = manifest.id
}

internal data class PendingPlugin(
    val token: UUID,
    val manifest: PluginManifest,
    val directory: File,
    val repository: String?,
    val replaces: InstalledPlugin?,
)

internal class PluginInstaller(private val root: File) {
    private val gate = Mutex()
    private val pending = File(root, "pending")
    private val code = File(root, "code")
    private val data = File(root, "data")
    private val indexFile = File(root, "installed.json")
    private var initialized = false
    private val mutations = MutableStateFlow(0L)
    val changes = mutations.asStateFlow()

    suspend fun installed(): List<InstalledPlugin> = locked {
        val index = index()
        index.keys().asSequence().map { plugin(index, it) }.sortedBy { it.manifest.name.lowercase() }.toList()
    }

    suspend fun prepareZip(open: () -> InputStream): PendingPlugin = prepareArchive(download = { archive ->
        val context = coroutineContext
        open().use { input -> archive.outputStream().use { output ->
            copyPluginBytes(input, output, pluginZipLimit, checkActive = context::ensureActive)
        } }
    })

    suspend fun prepareArchive(repository: String? = null, expected: InstalledPlugin? = null,
                               download: suspend (File) -> Unit): PendingPlugin = prepare(repository, expected) { staging, folder ->
        val archive = File(staging, "source.zip")
        download(archive)
        PluginPackage.unzip(archive, folder)
        archive.delete()
    }

    suspend fun prepareTree(resolver: ContentResolver, tree: Uri): PendingPlugin = prepare(null, null) { _, folder ->
        PluginPackage.copyTree(resolver, tree, folder)
    }

    private suspend fun prepare(repository: String?, expected: InstalledPlugin?,
                                extract: suspend (File, File) -> Unit): PendingPlugin = withContext(Dispatchers.IO) {
        locked { Unit }
        val token = UUID.randomUUID()
        val staging = File(pending, token.toString())
        val content = File(staging, "content")
        if (!content.mkdirs()) throw PluginException("Cannot stage plugin")
        try {
            extract(staging, content)
            val directory = PluginPackage.findRoot(content)
            val manifest = PluginPackage.readManifest(directory)
            if (manifest.id == localSourceId) throw PluginException("Plugin ID is reserved")
            if (expected != null && manifest.id != expected.id) throw PluginException("Update belongs to a different plugin")
            val replaces = locked {
                val index = index()
                val current = if (index.has(manifest.id)) plugin(index, manifest.id) else null
                if (expected != null && current?.revision != expected.revision) throw PluginException("Plugin changed while checking for updates")
                current
            }
            PendingPlugin(token, manifest, directory, repository, replaces)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    suspend fun install(candidate: PendingPlugin): InstalledPlugin = install(candidate, null)

    private suspend fun install(candidate: PendingPlugin, restoredSettings: Map<String, String>?): InstalledPlugin = locked {
        val staging = File(pending, candidate.token.toString()).canonicalFile
        val folder = candidate.directory.canonicalFile
        if (!folder.toPath().startsWith(staging.toPath())) throw PluginException("Invalid staged plugin")
        val manifest = PluginPackage.readManifest(folder)
        if (manifest != candidate.manifest) throw PluginException("Plugin changed after review")
        val index = index()
        val previous = if (index.has(manifest.id)) plugin(index, manifest.id) else null
        if (previous?.revision != candidate.replaces?.revision) throw PluginException("Plugin changed after review")
        val target = File(code, candidate.token.toString())
        if (!code.isDirectory && !code.mkdirs()) throw PluginException("Cannot create plugin store")
        val enabled = previous?.enabled ?: (restoredSettings == null)
        Files.move(folder.toPath(), target.toPath(), ATOMIC_MOVE)
        try {
            val settings = File(File(data, manifest.id), "settings.json")
            if (restoredSettings != null && !settings.exists()) {
                val verified = manifest.backupSettings(restoredSettings)
                if (verified.isNotEmpty()) writeAtomic(settings, JSONObject(verified).toString())
            }
            index.put(manifest.id, JSONObject().put("revision", candidate.token.toString())
                .put("repository", candidate.repository ?: JSONObject.NULL).put("enabled", enabled))
            writeAtomic(indexFile, index.toString())
        } catch (error: Throwable) {
            Files.move(target.toPath(), folder.toPath(), ATOMIC_MOVE)
            throw error
        }
        staging.deleteRecursively()
        previous?.directory?.deleteRecursively()
        changed()
        plugin(index, manifest.id)
    }

    suspend fun discard(candidate: PendingPlugin) = locked { File(pending, candidate.token.toString()).deleteRecursively() }

    suspend fun setEnabled(current: InstalledPlugin, enabled: Boolean) = locked {
        val index = index()
        checkRevision(index, current)
        index.getJSONObject(current.id).put("enabled", enabled)
        writeAtomic(indexFile, index.toString())
        changed()
    }

    suspend fun remove(current: InstalledPlugin) = locked {
        val index = index()
        checkRevision(index, current)
        index.remove(current.id)
        writeAtomic(indexFile, index.toString())
        current.directory.deleteRecursively()
        changed()
    }

    suspend fun settings(current: InstalledPlugin): Map<String, String> = locked {
        checkRevision(index(), current)
        val file = File(current.dataDirectory, "settings.json")
        val values = if (file.isFile) JSONObject(file.readText()) else JSONObject()
        current.manifest.settings.mapNotNull { setting -> (values.opt(setting.key) as? String)?.let { setting.key to it } }.toMap()
    }

    suspend fun saveSettings(current: InstalledPlugin, settings: Map<String, String>) = locked {
        checkRevision(index(), current)
        val values = JSONObject(settings.filterKeys { key -> current.manifest.settings.any { it.key == key } }).toString()
        if (values.encodeToByteArray().size > 1024 * 1024) throw PluginException("Plugin settings too large")
        writeAtomic(File(current.dataDirectory, "settings.json"), values)
        changed()
    }

    suspend fun archiveForBackup(plugin: InstalledPlugin, archive: File) {
        val context = coroutineContext
        locked {
            checkRevision(index(), plugin)
            PluginPackage.archive(plugin.directory, archive, context::ensureActive)
        }
    }

    suspend fun restoreArchive(id: String, repository: String?, archive: File, settings: Map<String, String>) {
        val candidate = prepareArchive(repository) { destination -> archive.copyTo(destination) }
        try {
            if (candidate.manifest.id != id) throw PluginException("Backup plugin ID does not match")
            if (candidate.replaces == null) install(candidate, settings)
        } finally { withContext(NonCancellable) { discard(candidate) } }
    }

    private fun changed() { mutations.update { it + 1 } }

    private fun index() = if (indexFile.isFile) JSONObject(indexFile.readText()) else JSONObject()

    private fun plugin(index: JSONObject, id: String): InstalledPlugin {
        val record = index.getJSONObject(id)
        val revision = UUID.fromString(record.getString("revision"))
        val directory = File(code, revision.toString())
        val manifest = PluginPackage.readManifest(directory)
        if (id != manifest.id) throw PluginException("Installed plugin ID does not match")
        val source = record.opt("repository") as? String
        return InstalledPlugin(manifest, revision, directory, File(data, id), source, record.getBoolean("enabled"))
    }

    private fun checkRevision(index: JSONObject, current: InstalledPlugin) {
        if (index.optJSONObject(current.id)?.optString("revision") != current.revision.toString()) {
            throw PluginException("Plugin is no longer installed at this revision")
        }
    }

    private suspend fun <Value> locked(action: () -> Value): Value = withContext(Dispatchers.IO) {
        gate.withLock {
            if (!initialized) {
                pending.deleteRecursively()
                val index = index()
                val active = index.keys().asSequence().map { index.getJSONObject(it).getString("revision") }.toSet()
                code.listFiles()?.filter { it.name !in active }?.forEach(File::deleteRecursively)
                initialized = true
            }
            action()
        }
    }
}

internal fun PluginManifest.backupSettings(values: Map<String, String>): Map<String, String> = settings.mapNotNull { setting ->
    val value = values[setting.key] ?: return@mapNotNull null
    when {
        setting.type == "boolean" && value in setOf("true", "false") -> setting.key to value
        setting.type == "select" && setting.options.any { it.value == value } -> setting.key to value
        else -> null
    }
}.toMap()
