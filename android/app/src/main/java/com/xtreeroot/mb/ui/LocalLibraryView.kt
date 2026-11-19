package com.xtreeroot.mb.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.local.LocalAudioDocument
import com.xtreeroot.mb.local.LocalMusicIndex
import com.xtreeroot.mb.local.LocalTrack
import com.xtreeroot.mb.local.matchesLocalMusic
import com.xtreeroot.mb.music.Track
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class LocalLibraryModel(application: Application) : AndroidViewModel(application) {
    private val source = (application as MBApplication).local
    private val state = MutableStateFlow(LoadState(emptyList<LocalTrack>()))
    val library = state.asStateFlow()

    fun refresh() = update { }

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        update { importFiles(uris.map { LocalAudioDocument(it) }) }
    }

    fun importFolder(uri: Uri?) {
        if (uri == null) return
        update {
            val files = source.audioDocuments(uri)
            if (files.isEmpty()) throw IllegalStateException("文件夹内没有可导入的音频")
            importFiles(files)
        }
    }

    private suspend fun importFiles(documents: List<LocalAudioDocument>) {
        var failed = 0
        for (document in documents.distinctBy(LocalAudioDocument::uri)) {
            try { source.importFile(document) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed++ }
        }
        if (failed > 0) throw IllegalStateException("$failed 个文件未能导入")
    }

    fun attach(trackId: String, uri: Uri) = update { source.attachLyrics(trackId, uri) }
    fun delete(trackId: String) = update { source.delete(trackId) }

    private fun update(action: suspend () -> Unit) {
        if (state.value.loading) return
        state.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            var error: String? = null
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "操作失败" }
            try { state.value = LoadState(source.catalog.list(), error = error) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { state.value = state.value.copy(loading = false, error = failure.message ?: "资料库读取失败") }
        }
    }
}

private enum class Category(val chinese: String, val english: String) {
    Songs("歌曲", "Songs"), Albums("专辑", "Albums"), Artists("歌手", "Artists"),
    Recent("最近播放", "Recently Played"), Favorites("喜欢", "Liked Songs"),
}

private enum class LibraryPage { Downloads, Playlists, DataLocation }

@Composable
internal fun LocalLibraryView(connected: Boolean, play: (List<Track>, Track) -> Unit, playGroup: (List<Track>, Boolean) -> Unit, saved: SavedLibraryModel,
                              model: LocalLibraryModel = viewModel()) {
    val state by model.library.collectAsStateWithLifecycle()
    val portable by saved.portable.status.collectAsStateWithLifecycle()
    val offline by saved.offline.collectAsStateWithLifecycle()
    val activity by saved.activity.collectAsStateWithLifecycle()
    var category by rememberSaveable { mutableStateOf(Category.Songs) }
    var query by rememberSaveable { mutableStateOf("") }
    var collectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var collectionQuery by rememberSaveable { mutableStateOf("") }
    var editingLyrics by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf<LibraryPage?>(null) }
    var importMenu by remember { mutableStateOf(false) }
    val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), model::import)
    val importFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), model::importFolder)
    val importLyrics = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = editingLyrics
        if (id != null && uri != null) model.attach(id, uri)
        editingLyrics = null
    }
    LaunchedEffect(portable.savedAt) { model.refresh() }
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val index = remember(state.value, offline.downloads, locale) { LocalMusicIndex(state.value, offline.downloads.map { it.track }, locale) }
    val imports = remember(state.value) { state.value.mapTo(mutableSetOf()) { it.track.id } }
    fun closeCollection() { collectionId = null; collectionQuery = "" }
    BackHandler(page != null || collectionId != null) { if (page != null) page = null else closeCollection() }
    when (page) {
        LibraryPage.Downloads -> { DownloadsPage(saved, connected, play) { page = null }; return }
        LibraryPage.Playlists -> { PlaylistsPage(saved, index, connected, play, playGroup) { page = null }; return }
        LibraryPage.DataLocation -> { DataLocationPage(saved.portable) { page = null }; return }
        null -> Unit
    }
    val collections = when (category) {
        Category.Albums -> index.albums
        Category.Artists -> index.artists
        else -> null
    }
    val collection = collections?.firstOrNull { it.id == collectionId }
    val entries = when (category) {
        Category.Albums, Category.Artists -> collection?.tracks.orEmpty()
        Category.Recent -> index.songs.filter { (activity[it.id]?.playedAt ?: 0) > 0 }.sortedByDescending { activity[it.id]?.playedAt }.take(100)
        Category.Favorites -> index.songs.filter { (activity[it.id]?.likedAt ?: 0) > 0 }.sortedByDescending { activity[it.id]?.likedAt }
        Category.Songs -> index.songs
    }
    val matching = index.filter(entries, if (collectionId == null) query else collectionQuery)
    val listState = key(category, collectionId) { rememberLazyListState() }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item(key = "local-controls") {
            Column {
                if (collectionId != null) {
                    LibraryPageHeader(collection?.title ?: mb(category.chinese, category.english), back = ::closeCollection)
                    LocalMusicSearch(collectionQuery, { collectionQuery = it }, mb("在此页面搜索", "Search This Page"))
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(mb("本地资料库", "Local Library"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        Box {
                            TextButton(enabled = !state.loading, onClick = { importMenu = true }) { Text(mb("添加到资料库", "Add to Library")) }
                            DropdownMenu(importMenu, { importMenu = false }) {
                                DropdownMenuItem(text = { Text(mb("导入音频", "Import Audio")) }, onClick = {
                                    importMenu = false; importAudio.launch(arrayOf("audio/*", "application/ogg"))
                                })
                                DropdownMenuItem(text = { Text(mb("导入文件夹", "Import Folder")) }, onClick = {
                                    importMenu = false; importFolder.launch(null)
                                })
                            }
                        }
                    }
                    FlowRow(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(onClick = { page = LibraryPage.Playlists }) { Text(mb("本地歌单", "Local Playlists")) }
                        TextButton(onClick = { page = LibraryPage.Downloads }) { Text(mb("下载与缓存", "Downloads & Cache")) }
                        TextButton(onClick = { page = LibraryPage.DataLocation }) { Text(mb("数据位置", "Data Location")) }
                    }
                    LocalMusicSearch(query, { query = it }, mb("搜索本地音乐", "Search Local Music"))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(Category.entries) { item -> FilterChip(item == category, { category = item },
                            label = { Text(mb(item.chinese, item.english)) }) }
                    }
                }
            }
        }
        if (state.loading) item { Loading() }
        state.error?.let { error -> item { Retry(error, model::refresh) } }
        if (collections != null && collectionId == null) {
            val visible = collections.filter { matchesLocalMusic(it.title, query) }
            if (!state.loading && visible.isEmpty()) item { EmptyMessage(mb("没有匹配的分类", "No Matching Collections")) }
            items(visible, key = { it.id }) { group ->
                ListItem(headlineContent = { Text(group.title) }, supportingContent = {
                    val count = localSongCount(group.tracks.size)
                    Text(listOf(group.artist, count).filter(String::isNotBlank).joinToString(" · "))
                }, leadingContent = { Artwork(group.artwork, Modifier.size(48.dp)) },
                    modifier = Modifier.clickable { collectionId = group.id })
            }
        } else {
            if (entries.isNotEmpty()) item { LocalPlaybackButtons(matching, connected, playGroup) }
            if (!state.loading && matching.isEmpty()) item { EmptyMessage(mb("暂无本地曲目", "No Local Tracks")) }
            items(matching, key = Track::id) { track ->
                var menu by remember { mutableStateOf(false) }
                val trackActions = LocalTrackActions.current
                val favorite = (activity[track.id]?.likedAt ?: 0) > 0
                TrackRow(track, connected, actions = {
                    Box {
                        IconButton(enabled = !state.loading, onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, mb("操作 ${track.title}", "Actions for ${track.title}"))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(if (favorite) mb("取消喜欢", "Unlike") else mb("喜欢", "Like")) },
                                onClick = { menu = false; saved.toggleFavorite(track) })
                            if (track.id in imports) {
                                DropdownMenuItem(text = { Text(mb("导入 LRC", "Import LRC")) }, onClick = {
                                    menu = false; editingLyrics = track.sourceTrackId; importLyrics.launch(arrayOf("*/*"))
                                })
                                DropdownMenuItem(text = { Text(mb("删除音频", "Delete Audio")) }, onClick = {
                                    menu = false; deleting = track.sourceTrackId
                                })
                            }
                            DropdownMenuItem(text = { Text(mb("播放、歌单与导出", "Playback, Playlists & Export")) }, onClick = { menu = false; trackActions(track) })
                        }
                    }
                }) { play(matching, track) }
            }
        }
    }
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(mb("删除本地音频？", "Delete Local Audio?")) },
            text = { Text(mb("只删除 MB 保存的副本，不影响导入前的文件。", "Only the copy stored by MB will be deleted. The original will be kept.")) },
            confirmButton = { TextButton(onClick = { deleting = null; model.delete(id) }) { Text(mb("删除", "Delete")) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(mb("取消", "Cancel")) } })
    }
}

@Composable
internal fun LocalMusicSearch(query: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(query, onValueChange = change, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = { Text(label) })
}

@Composable
internal fun LocalPlaybackButtons(tracks: List<Track>, connected: Boolean, play: (List<Track>, Boolean) -> Unit) {
    val enabled = connected && tracks.any { !it.isUnavailable }
    FlowRow(Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = { play(tracks, false) }, enabled = enabled) { Text(mb("播放全部", "Play All")) }
        TextButton(onClick = { play(tracks, true) }, enabled = enabled) { Text(mb("随机播放", "Shuffle Play")) }
    }
}

@Composable
private fun localSongCount(count: Int): String = mb("$count 首", if (count == 1) "1 song" else "$count songs")
