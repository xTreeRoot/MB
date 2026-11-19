package com.xtreeroot.mb.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.local.LocalPlaylist
import com.xtreeroot.mb.local.LocalMusicIndex
import com.xtreeroot.mb.local.OfflineMediaStore
import com.xtreeroot.mb.local.PortableLibrary
import com.xtreeroot.mb.local.matchesLocalMusic
import com.xtreeroot.mb.music.Track
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DataLocationPage(library: PortableLibrary, back: () -> Unit) {
    val status by library.status.collectAsStateWithLifecycle()
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), library::bind)
    LazyColumn(Modifier.fillMaxSize()) {
        item { LibraryPageHeader(mb("数据位置", "Data Location"), back) }
        item {
            ListItem(headlineContent = {
                Text(if (status.linked) mb("已连接独立资料库目录", "Backup Folder Connected") else mb("未设置独立资料库目录", "No Backup Folder Selected"))
            },
                supportingContent = {
                    if (status.savedAt > 0) {
                        val locale = if (LocalMBEnglish.current) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
                        val date = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, locale).format(Date(status.savedAt))
                        Text(mb("最近备份：$date", "Last backup: $date"))
                    } else Text(mb("选择‘文档’下的文件夹，MB 会创建 MB Data 子目录。", "Choose a folder in Documents. MB will create an MB Data subfolder."))
                })
            Text(mb("歌曲、歌单、歌词、本地喜欢、最近播放、队列、插件安装包、背景图片和非敏感设置会备份。重装后重新选择同一目录，恢复的插件默认停用。登录凭据、插件私有存储和播放缓存不备份。",
                "The backup keeps songs, playlists, lyrics, local likes, recent plays, your queue, plugin packages, the background image, and non-sensitive settings. After reinstalling, select the same folder to restore them. Restored plugins stay disabled until enabled. Sign-in credentials, private plugin data, and playback cache aren’t backed up."),
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
            FlowRow(Modifier.padding(horizontal = 8.dp)) {
                TextButton(enabled = !status.busy, onClick = { choose.launch(null) }) {
                    Text(if (status.linked) mb("更换或恢复目录", "Change or Restore Folder") else mb("选择目录或恢复", "Choose Folder or Restore"))
                }
                if (status.linked) TextButton(enabled = !status.busy, onClick = library::saveNow) { Text(mb("立即备份", "Back Up Now")) }
            }
            if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            status.error?.let { error ->
                val message = mb(error.problem.chinese, error.problem.english)
                Text(listOfNotNull(message, error.asset).joinToString("\n"), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
internal fun DownloadsPage(saved: SavedLibraryModel, connected: Boolean, play: (List<Track>, Track) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val offline by saved.offline.collectAsStateWithLifecycle()
    val transfers by saved.transfers.collectAsStateWithLifecycle()
    val error by saved.transferError.collectAsStateWithLifecycle()
    var cacheMenu by remember { mutableStateOf(false) }
    var clear by rememberSaveable { mutableStateOf(false) }
    var remove by rememberSaveable { mutableStateOf<String?>(null) }
    val actions = LocalTrackActions.current
    val tracks = offline.downloads.map { it.track }
    val cacheSize = android.text.format.Formatter.formatShortFileSize(context, offline.cacheBytes)
    LazyColumn(Modifier.fillMaxSize()) {
        item { LibraryPageHeader(mb("下载与缓存", "Downloads & Cache"), back) }
        item {
            ListItem(headlineContent = { Text(mb("播放缓存", "Playback Cache")) }, supportingContent = { Text(mb("已用 $cacheSize", "$cacheSize used")) }, trailingContent = {
                Box {
                    TextButton(onClick = { cacheMenu = true }) { Text(if (offline.limitMb == 0) mb("已关闭", "Off") else "${offline.limitMb} MB") }
                    DropdownMenu(cacheMenu, { cacheMenu = false }) {
                        OfflineMediaStore.limitsMb.forEach { limit ->
                            DropdownMenuItem(text = { Text(if (limit == 0) mb("关闭自动缓存", "No Cache") else "$limit MB") }, onClick = {
                                cacheMenu = false; saved.setLimit(limit)
                            })
                        }
                    }
                }
            })
            TextButton(enabled = offline.cacheBytes > 0, onClick = { clear = true }) { Text(mb("清空播放缓存", "Clear Playback Cache")) }
        }
        error?.let { message -> item { EmptyMessage(message) } }
        items(transfers.values.toList(), key = { "transfer:${it.track.id}" }) { transfer ->
            ListItem(headlineContent = { Text(transfer.track.title, maxLines = 1) }, supportingContent = {
                if (transfer.expected > 0) LinearProgressIndicator(progress = {
                    (transfer.received.toFloat() / transfer.expected).coerceIn(0f, 1f)
                }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            }, trailingContent = { TextButton(onClick = { saved.cancel(transfer.track.id) }) { Text(mb("取消下载", "Cancel Download")) } })
        }
        item { Text(mb("已下载", "Downloaded"), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
        if (tracks.isEmpty()) item { EmptyMessage(mb("暂无下载", "No Downloads")) }
        items(tracks, key = Track::id) { track ->
            TrackRow(track, connected, actions = {
                SavedTrackMenu(track, more = { actions(track) }) {
                    DropdownMenuItem(text = { Text(mb("删除下载", "Delete Download")) }, onClick = { it(); remove = track.id })
                }
            }) { play(tracks, track) }
        }
    }
    if (clear) LibraryConfirmation(mb("清空播放缓存？", "Clear playback cache?"),
        mb("已下载和导入的音频会保留。", "Downloaded and imported audio will be kept."), mb("清空", "Clear"), { clear = false }) {
        clear = false; saved.clearCache()
    }
    remove?.let { id -> LibraryConfirmation(mb("删除下载？", "Delete download?"),
        mb("仅删除此音频的离线副本。", "Only the offline copy of this track will be deleted."), mb("删除", "Delete"), { remove = null }) {
        remove = null; saved.removeDownload(id)
    } }
}

@Composable
internal fun PlaylistsPage(saved: SavedLibraryModel, library: LocalMusicIndex, connected: Boolean,
                           play: (List<Track>, Track) -> Unit, playGroup: (List<Track>, Boolean) -> Unit, back: () -> Unit) {
    val playlists by saved.playlists.collectAsStateWithLifecycle()
    val available = remember(library) { library.songs.associateBy(Track::id) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var create by rememberSaveable { mutableStateOf(false) }
    var rename by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    var chooseTracks by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var trackQuery by rememberSaveable(selected) { mutableStateOf("") }
    val current = playlists.firstOrNull { it.id == selected }
    val actions = LocalTrackActions.current
    BackHandler(current != null) { selected = null }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            LibraryPageHeader(current?.name ?: mb("本地歌单", "Local Playlists"), back = { if (current == null) back() else selected = null }) {
                if (current == null) TextButton(onClick = { create = true }) { Text(mb("新建歌单", "New Playlist")) }
                else PlaylistMenu(current, { rename = true }, { delete = true })
            }
        }
        if (current == null) {
            item {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(mb("搜索歌单", "Search Playlists")) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            val matching = playlists.filter { matchesLocalMusic(it.name, query) }
            if (matching.isEmpty()) item { EmptyMessage(mb("没有匹配的歌单", "No Matching Playlists")) }
            items(matching, key = LocalPlaylist::id) { playlist ->
                ListItem(headlineContent = { Text(playlist.name) },
                    supportingContent = {
                        val count = playlist.trackIds.count(available::containsKey)
                        Text(mb("$count 首", if (count == 1) "1 song" else "$count songs"))
                    },
                    modifier = Modifier.clickable { selected = playlist.id })
            }
        } else {
            val tracks = current.trackIds.mapNotNull(available::get)
            val matching = library.filter(tracks, trackQuery)
            item { LocalMusicSearch(trackQuery, { trackQuery = it }, mb("在此页面搜索", "Search This Page")) }
            if (tracks.isNotEmpty()) item { LocalPlaybackButtons(matching, connected, playGroup) }
            item { TextButton(onClick = { chooseTracks = true }) { Text(mb("添加歌曲", "Add Songs")) } }
            if (tracks.isEmpty()) item { EmptyMessage(mb("歌单暂无歌曲", "This Playlist Is Empty")) }
            else if (matching.isEmpty()) item { EmptyMessage(mb("没有匹配的歌曲", "No Matching Songs")) }
            items(matching, key = Track::id) { track ->
                val index = tracks.indexOf(track)
                TrackRow(track, connected, actions = {
                    SavedTrackMenu(track, more = { actions(track) }) { close ->
                        DropdownMenuItem(text = { Text(mb("上移", "Move Up")) }, enabled = trackQuery.isBlank() && index > 0, onClick = {
                            close(); saved.move(current.id, track.id, -1, available.keys)
                        })
                        DropdownMenuItem(text = { Text(mb("下移", "Move Down")) }, enabled = trackQuery.isBlank() && index < tracks.lastIndex, onClick = {
                            close(); saved.move(current.id, track.id, 1, available.keys)
                        })
                        DropdownMenuItem(text = { Text(mb("移出歌单", "Remove from Playlist")) }, onClick = { close(); saved.remove(current.id, track.id) })
                    }
                }) { play(matching, track) }
            }
        }
    }
    if (create) PlaylistNameDialog(mb("新建歌单", "New Playlist"), close = { create = false }) { name -> create = false; saved.create(name) }
    if (current != null) {
        if (rename) PlaylistNameDialog(mb("重命名歌单", "Rename Playlist"), current.name, { rename = false }) { name ->
            rename = false; saved.rename(current.id, name)
        }
        if (delete) LibraryConfirmation(mb("删除歌单“${current.name}”？", "Delete playlist “${current.name}”?"),
            mb("音频文件会保留在资料库里。", "Audio files will stay in your library."), mb("删除", "Delete"), { delete = false }) {
            delete = false; selected = null; saved.delete(current)
        }
        if (chooseTracks) TrackPicker(available.values.filterNot { it.id in current.trackIds }, close = { chooseTracks = false }) {
            chooseTracks = false; saved.add(current.id, it)
        }
    }
}

@Composable
internal fun LibraryPageHeader(title: String, back: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, mb("返回资料库", "Back to Library")) }
        Text(title, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.titleLarge)
        actions()
    }
}

@Composable
private fun PlaylistMenu(playlist: LocalPlaylist, rename: () -> Unit, delete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, mb("操作歌单 ${playlist.name}", "Playlist Actions ${playlist.name}")) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(mb("重命名歌单", "Rename Playlist")) }, onClick = { open = false; rename() })
            DropdownMenuItem(text = { Text(mb("删除歌单", "Delete Playlist")) }, onClick = { open = false; delete() })
        }
    }
}

@Composable
private fun SavedTrackMenu(track: Track, more: () -> Unit, entries: @Composable (() -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, mb("操作 ${track.title}", "Actions for ${track.title}")) }
        DropdownMenu(open, { open = false }) {
            entries { open = false }
            DropdownMenuItem(text = { Text(mb("播放、歌单与导出", "Playback, Playlists & Export")) }, onClick = { open = false; more() })
        }
    }
}

@Composable
private fun TrackPicker(tracks: List<Track>, close: () -> Unit, add: (List<String>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var selection by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val matching = tracks.filter { it.title.contains(query.trim(), true) || it.artist.contains(query.trim(), true) }
    AlertDialog(onDismissRequest = close, title = { Text(mb("添加歌曲", "Add Songs")) }, text = {
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            item {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(mb("搜索已保存的歌曲", "Search Saved Songs")) })
            }
            if (matching.isEmpty()) item { Text(mb("没有可添加的歌曲", "No Songs to Add"), Modifier.padding(vertical = 12.dp)) }
            items(matching, key = Track::id) { track ->
                val checked = track.id in selection
                ListItem(headlineContent = { Text(track.title, maxLines = 1) }, supportingContent = { Text(track.artist, maxLines = 1) },
                    trailingContent = { Checkbox(checked, onCheckedChange = null) }, modifier = Modifier.clickable {
                        selection = ArrayList(if (checked) selection - track.id else selection + track.id)
                    })
            }
        }
    }, confirmButton = { TextButton(enabled = selection.isNotEmpty(), onClick = { add(selection) }) { Text(mb("添加", "Add")) } },
        dismissButton = { TextButton(onClick = close) { Text(mb("取消", "Cancel")) } })
}

@Composable
private fun LibraryConfirmation(title: String, detail: String, button: String, close: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Text(detail) },
        confirmButton = { TextButton(onClick = confirm) { Text(button) } },
        dismissButton = { TextButton(onClick = close) { Text(mb("取消", "Cancel")) } })
}
