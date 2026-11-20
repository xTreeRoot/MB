package com.xtreeroot.mb.ui

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.local.LocalPlaylist
import com.xtreeroot.mb.local.LocalTrackActivity
import com.xtreeroot.mb.local.localSourceId
import com.xtreeroot.mb.music.Track
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SavedLibraryModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MBApplication
    private val store = app.offline.store
    private val playlistStore = app.playlists
    private val activityState = MutableStateFlow(emptyMap<String, LocalTrackActivity>())
    private val refreshingState = MutableStateFlow(false)
    private val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = notices.asSharedFlow()
    val offline = store.snapshot
    val transfers = app.offline.transfers
    val transferError = app.offline.error
    val playlists = playlistStore.playlists
    val portable = app.portableLibrary
    val activity = activityState.asStateFlow()
    val refreshing = refreshingState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            app.local.catalog.changes.collect {
                try { activityState.value = app.local.catalog.activity().associateBy(LocalTrackActivity::trackId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { report(failure) }
            }
        }
    }

    fun refresh() {
        if (refreshingState.value) return
        refreshingState.value = true
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { store.refresh(); playlistStore.refresh() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
            finally { refreshingState.value = false }
        }
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            try { app.local.catalog.toggleFavorite(track.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
        }
    }
    fun download(track: Track) = app.offline.download(track)
    fun cancel(id: String) = app.offline.cancel(id)
    fun removeDownload(id: String) = runIO { store.removeDownload(id) }
    fun clearCache() = runIO { store.clearCache() }
    fun setLimit(mb: Int) = runIO { store.setLimit(mb) }
    fun create(name: String, tracks: List<String> = emptyList()) = runIO { playlistStore.create(name, tracks) }
    fun rename(id: String, name: String) = runIO { playlistStore.rename(id, name) }
    fun delete(playlist: LocalPlaylist) = runIO { playlistStore.delete(playlist.id) }
    fun add(id: String, tracks: List<String>) = runIO { playlistStore.add(id, tracks) }
    fun remove(id: String, track: String) = runIO { playlistStore.remove(id, track) }
    fun move(id: String, track: String, step: Int, available: Set<String>) = runIO { playlistStore.move(id, track, step, available) }
    fun reorder(id: String, from: Int, to: Int, visible: List<String>) = runIO { playlistStore.reorder(id, from, to, visible) }

    suspend fun exportName(track: Track): String = withContext(Dispatchers.IO) {
        val extension = if (track.sourceId == localSourceId) {
            val extractor = MediaMetadataRetriever()
            val mime = try {
                extractor.setDataSource(app.local.catalog.file(track.sourceTrackId).path)
                extractor.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            } finally { extractor.release() }
            if (mime == "audio/mp4" || mime == "video/mp4") "m4a"
            else MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "audio"
        } else store.snapshot.value.downloads.firstOrNull { it.track.id == track.id }?.fileName?.substringAfterLast('.')
            ?: throw IOException("下载文件不存在")
        val title = track.title.replace(Regex("[\\\\/\\p{Cntrl}]"), "_").trim().take(96).ifEmpty { "MB 音频" }
        "$title.$extension"
    }

    fun export(track: Track, uri: Uri) = runIO {
        val lease = if (track.sourceId == localSourceId) null else store.lease(track.id, downloadsOnly = true)
            ?: throw IOException("下载文件不存在")
        lease.use {
            val file = lease?.path ?: app.local.catalog.file(track.sourceTrackId)
            file.inputStream().use { input ->
                val output = app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法写入导出文件")
                output.use { input.copyTo(it) }
            }
        }
        notices.tryEmit("音频已导出")
    }

    fun report(failure: Exception) { notices.tryEmit(failure.localizedMessage ?: "操作失败") }

    private fun runIO(action: () -> Unit) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
        }
    }
}
