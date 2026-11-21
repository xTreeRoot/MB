package com.xtreeroot.mb.plugin

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.zip.ZipFile
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

internal const val pluginZipLimit = 64L * 1024 * 1024
private const val extractedLimit = 128L * 1024 * 1024
private const val entryLimit = 10_000
private const val manifestLimit = 1024L * 1024

internal object PluginPackage {
    fun archive(folder: File, archive: File, checkActive: () -> Unit) {
        var entries = 0
        var bytes = 0L
        ZipOutputStream(archive.outputStream().buffered()).use { output ->
            for (file in folder.walkTopDown().drop(1)) {
                checkActive()
                if (Files.isSymbolicLink(file.toPath())) throw PluginException("Invalid plugin file")
                if (file.isDirectory) continue
                val path = file.relativeTo(folder).invariantSeparatorsPath
                if (!file.isFile || ++entries > entryLimit) throw PluginException("Invalid plugin file")
                zipPath(path)
                output.putNextEntry(ZipEntry(path).apply { time = 0 })
                bytes += file.inputStream().use { copyPluginBytes(it, output, extractedLimit - bytes, checkActive = checkActive) }
                output.closeEntry()
                if (archive.length() > pluginZipLimit) throw PluginException("Plugin ZIP exceeds 64 MB")
            }
        }
        if (archive.length() > pluginZipLimit) throw PluginException("Plugin ZIP exceeds 64 MB")
    }

    suspend fun unzip(archive: File, folder: File) {
        if (archive.length() > pluginZipLimit) throw PluginException("Plugin ZIP exceeds 64 MB")
        val context = coroutineContext
        val paths = mutableSetOf<String>()
        var declared = 0L
        var written = 0L
        ZipFile.builder().setFile(archive).get().use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                context.ensureActive()
                val entry = entries.nextElement()
                if (entry.rawName?.contains('\\'.code.toByte()) == true) throw PluginException("Invalid plugin archive path")
                val path = entry.name.removeSuffix(if (entry.isDirectory) "/" else "")
                val parts = zipPath(path)
                if (entry.isUnixSymlink || entry.unixMode and 0xF000 !in setOf(0, 0x8000, 0x4000)) {
                    throw PluginException("Plugin archives cannot contain special files or links")
                }
                if (!paths.add(path) || paths.size > entryLimit || entry.size !in 0..(extractedLimit - declared)) {
                    throw PluginException("Plugin ZIP exceeds limits or contains repeated paths")
                }
                declared += entry.size
                if (parts.any(::ignored)) continue
                val file = File(folder, path)
                if (entry.isDirectory) {
                    mkdir(file)
                } else {
                    if (!zip.canReadEntryData(entry)) throw PluginException("Unreadable ZIP entry")
                    mkdir(checkNotNull(file.parentFile))
                    val crc = CRC32()
                    val size = zip.getInputStream(entry).use { input -> Files.newOutputStream(file.toPath(),
                        CREATE_NEW, WRITE).use { output ->
                        copyPluginBytes(input, output, minOf(entry.size, extractedLimit - written), crc, checkActive = context::ensureActive)
                    } }
                    if (size != entry.size || crc.value != entry.crc) throw PluginException("Plugin ZIP entry failed verification")
                    written += size
                }
            }
        }
    }

    suspend fun copyTree(resolver: ContentResolver, tree: Uri, folder: File) {
        val context = coroutineContext
        var count = 0
        var bytes = 0L
        val seen = mutableSetOf<String>()
        fun walk(id: String, output: File, depth: Int) {
            if (depth > 32 || !seen.add(id)) throw PluginException("Invalid plugin directory hierarchy")
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            val names = mutableSetOf<String>()
            val cursor = resolver.query(children, columns, null, null, null) ?: throw PluginException("Cannot read plugin folder")
            val entries = cursor.use { buildList {
                while (it.moveToNext()) {
                    context.ensureActive()
                    val name = it.getString(1).orEmpty()
                    if (++count > entryLimit || !names.add(name)) throw PluginException("Plugin directory exceeds limits or contains repeated paths")
                    if (zipPath(name).size != 1) throw PluginException("Invalid plugin file name")
                    if (!ignored(name)) add(Triple(it.getString(0), name, it.getString(2)))
                }
            } }
            for ((child, name, type) in entries) {
                val file = File(output, name)
                if (type == DocumentsContract.Document.MIME_TYPE_DIR) {
                    mkdir(file)
                    walk(child, file, depth + 1)
                } else {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, child)
                    val input = resolver.openInputStream(uri) ?: throw PluginException("Cannot read plugin file")
                    bytes += input.use { file.outputStream().use { output ->
                        copyPluginBytes(it, output, extractedLimit - bytes, checkActive = context::ensureActive)
                    } }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), folder, 0)
    }

    fun findRoot(staging: File): File {
        if (File(staging, "plugin.json").isFile) return staging
        val child = staging.listFiles()?.filterNot { ignored(it.name) }?.singleOrNull()
        return child?.takeIf { it.isDirectory && File(it, "plugin.json").isFile }
            ?: throw PluginException("Expected one plugin folder with plugin.json")
    }

    fun readManifest(folder: File): PluginManifest {
        val file = File(folder, "plugin.json")
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.length() > manifestLimit) throw PluginException("Invalid plugin manifest")
        val manifest = PluginManifest.parse(JSONObject(file.readBytes().decodeToString(throwOnInvalidSequence = true)))
        val entry = File(folder, manifest.entry)
        if (!entry.canonicalFile.toPath().startsWith(folder.canonicalFile.toPath()) ||
            !Files.isRegularFile(entry.toPath(), NOFOLLOW_LINKS) || entry.length() > 4L * 1024 * 1024) throw PluginException("Missing or invalid plugin entry")
        return manifest
    }

    private fun mkdir(folder: File) {
        if (!folder.isDirectory && !folder.mkdirs()) throw PluginException("Cannot create plugin directory")
    }

    private fun zipPath(path: String): List<String> {
        val parts = path.split('/')
        if (parts.size > 32 || '\\' in path || '\u0000' in path || ':' in path || parts.any { it.isEmpty() || it == "." || it == ".." }) {
            throw PluginException("Invalid plugin archive path")
        }
        return parts
    }

    private fun ignored(name: String) = name.startsWith('.') || name == "__MACOSX"

    private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE)
}

internal fun copyPluginBytes(input: InputStream, output: OutputStream, limit: Long, crc: CRC32? = null,
                             checkActive: () -> Unit = {}): Long {
    var total = 0L
    val buffer = ByteArray(32 * 1024)
    while (true) {
        checkActive()
        val count = input.read(buffer)
        if (count < 0) return total
        if (count > limit - total) throw PluginException("Plugin package exceeds size limit")
        output.write(buffer, 0, count)
        crc?.update(buffer, 0, count)
        total += count
    }
}
