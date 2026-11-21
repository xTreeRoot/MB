package com.xtreeroot.mb.local

import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.toMetadataJson
import com.xtreeroot.mb.music.toTrackMetadata
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal data class SavedAudio(
    val track: Track,
    val fileName: String,
    val bytes: Long,
    val downloaded: Boolean,
    internal val used: Long,
)

internal data class OfflineSnapshot(
    val downloads: List<SavedAudio> = emptyList(),
    val cacheBytes: Long = 0,
    val limitMb: Int = 2048,
)

internal class OfflineMediaStore(private val directory: File) {
    private data class Index(val entries: List<SavedAudio> = emptyList(), val limitMb: Int = 2048)
    class CacheTicket internal constructor(internal val epoch: Long, val maximumBytes: Long)

    private val indexFile = File(directory, "index.json")
    private var index: Index? = null
    private val leased = mutableMapOf<String, Int>()
    private var cacheEpoch = 0L
    private val state = MutableStateFlow(OfflineSnapshot())
    val snapshot = state.asStateFlow()

    @Synchronized
    fun refresh() { load() }

    @Synchronized
    fun cacheTicket(trackId: String): CacheTicket? {
        val current = load()
        return if (current.limitMb == 0 || current.entries.any { it.track.id == trackId }) null
        else CacheTicket(cacheEpoch, current.limitMb * bytesPerMb)
    }

    @Synchronized
    fun promote(track: Track): Boolean {
        val current = load()
        val existing = current.entries.firstOrNull { it.track.id == track.id && file(it.fileName).isFile } ?: return false
        if (!existing.downloaded) {
            commit(current.copy(entries = current.entries.map {
                if (it.fileName == existing.fileName) it.copy(track = track, downloaded = true, used = stamp(current)) else it
            }))
        }
        return true
    }

    fun staging(): File {
        directory.mkdirs()
        return File.createTempFile("incoming-", ".part", directory)
    }

    @Synchronized
    fun save(staged: File, track: Track, extension: String, cache: CacheTicket? = null, restoreName: String? = null,
             restoredAddedAt: Long = 0) {
        val current = load()
        val size = staged.length()
        if (size <= 0) throw IOException("没有收到完整的音频")
        if (cache != null && (cache.epoch != cacheEpoch || size > current.limitMb * bytesPerMb ||
                current.entries.any { it.track.id == track.id })) return
        val suffix = extension.takeIf { it in audioExtensions } ?: "m4a"
        val name = restoreName?.takeIf { validName.matches(it) && !file(it).exists() } ?: "${UUID.randomUUID()}.$suffix"
        val now = stamp(current)
        val entry = SavedAudio(track, name, size, cache == null, restoredAddedAt.takeIf { it in 1..now } ?: now)
        val destination = file(entry.fileName)
        directory.mkdirs()
        Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        try { commit(current.copy(entries = prune(current.entries.filterNot { it.track.id == track.id } + entry, current.limitMb))) }
        catch (failure: Exception) { destination.delete(); throw failure }
    }

    @Synchronized
    fun hasIntactDownload(trackId: String): Boolean = load().entries.any {
        it.downloaded && it.track.id == trackId && it.bytes > 0 && file(it.fileName).length() == it.bytes
    }

    @Synchronized
    fun restoreDownload(staged: File, track: Track, fileName: String, addedAt: Long = 0) {
        if (!hasIntactDownload(track.id)) {
            save(staged, track, fileName.substringAfterLast('.'), restoreName = fileName, restoredAddedAt = addedAt)
        }
    }

    @Synchronized
    fun lease(trackId: String, downloadsOnly: Boolean = false): Lease? {
        val current = load()
        val item = current.entries.firstOrNull { it.track.id == trackId && (!downloadsOnly || it.downloaded) } ?: return null
        val path = file(item.fileName)
        if (!path.isFile) {
            commit(current.copy(entries = current.entries - item))
            return null
        }
        if (!item.downloaded) commit(current.copy(entries = current.entries.map {
            if (it == item) item.copy(used = stamp(current)) else it
        }))
        return retain(path)
    }

    private fun retain(path: File): Lease {
        leased[path.name] = leased.getOrDefault(path.name, 0) + 1
        return Lease(path)
    }

    @Synchronized
    private fun release(fileName: String) {
        val count = leased.getValue(fileName) - 1
        if (count == 0) {
            leased.remove(fileName)
            if (index?.entries?.none { it.fileName == fileName } == true) file(fileName).delete()
        } else leased[fileName] = count
    }

    @Synchronized
    fun removeDownload(trackId: String) {
        val current = load()
        commit(current.copy(entries = current.entries.filterNot { it.downloaded && it.track.id == trackId }))
    }

    @Synchronized
    fun clearCache() {
        val current = load()
        commit(current.copy(entries = current.entries.filter(SavedAudio::downloaded)))
        cacheEpoch++
    }

    @Synchronized
    fun setLimit(mb: Int) {
        require(mb in limitsMb)
        val current = load()
        if (mb == current.limitMb) return
        commit(current.copy(entries = prune(current.entries, mb), limitMb = mb))
        if (mb == 0) cacheEpoch++
    }

    private fun prune(entries: List<SavedAudio>, limit: Int): List<SavedAudio> {
        val cached = entries.filterNot(SavedAudio::downloaded).sortedByDescending(SavedAudio::used)
        var total = 0L
        val keep = cached.takeWhile { total += it.bytes; total <= limit * bytesPerMb }.map(SavedAudio::fileName).toSet()
        return entries.filter { it.downloaded || it.fileName in keep }
    }

    private fun load(): Index {
        index?.let { return it }
        val loaded = if (!indexFile.isFile) Index() else {
            val json = JSONObject(indexFile.readText())
            val entries = json.getJSONArray("entries")
            Index((0 until entries.length()).mapNotNull { index ->
                val item = entries.getJSONObject(index)
                val name = item.getString("file")
                if (!validName.matches(name) || !file(name).isFile) return@mapNotNull null
                SavedAudio(item.getJSONObject("track").toTrackMetadata(), name, item.getLong("bytes"),
                    item.getBoolean("download"), item.getLong("used"))
            }, json.getInt("limit"))
        }
        val names = loaded.entries.mapTo(mutableSetOf(), SavedAudio::fileName)
        directory.listFiles()?.filter { validName.matches(it.name) && it.name !in names }?.forEach(File::delete)
        publish(loaded)
        return loaded
    }

    private fun commit(next: Index) {
        if (next == index) return
        val json = JSONObject().put("limit", next.limitMb).put("entries", JSONArray(next.entries.map { item ->
            JSONObject().put("track", item.track.toMetadataJson()).put("file", item.fileName)
                .put("bytes", item.bytes).put("download", item.downloaded).put("used", item.used)
        }))
        writeAtomic(indexFile, json.toString())
        val previous = index?.entries.orEmpty()
        publish(next)
        val kept = next.entries.mapTo(mutableSetOf(), SavedAudio::fileName)
        previous.filterNot { it.fileName in kept || it.fileName in leased }.forEach { file(it.fileName).delete() }
    }

    private fun publish(loaded: Index) {
        index = loaded
        state.value = OfflineSnapshot(loaded.entries.filter(SavedAudio::downloaded).asReversed(),
            loaded.entries.filterNot(SavedAudio::downloaded).sumOf(SavedAudio::bytes), loaded.limitMb)
    }

    private fun file(name: String) = File(directory, name)
    private fun stamp(current: Index) = maxOf(System.currentTimeMillis(), (current.entries.maxOfOrNull(SavedAudio::used) ?: 0) + 1)

    inner class Lease internal constructor(val path: File) : Closeable {
        private var closed = false

        fun duplicate(): Lease = synchronized(this@OfflineMediaStore) {
            check(!closed)
            retain(path)
        }

        override fun close() = synchronized(this@OfflineMediaStore) {
            if (!closed) {
                closed = true
                release(path.name)
            }
        }
    }

    companion object {
        val limitsMb = listOf(0, 256, 512, 1024, 2048, 4096, 8192)
        private const val bytesPerMb = 1024L * 1024
        private val audioExtensions = setOf("m4a", "aac", "mp3", "mp4", "flac", "wav", "ogg", "opus", "webm", "aiff", "caf")
        private val validName = Regex("[a-f0-9-]{36}\\.[a-z0-9]{2,5}")
    }
}
