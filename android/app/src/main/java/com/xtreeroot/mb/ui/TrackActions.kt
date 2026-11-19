package com.xtreeroot.mb.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.local.localSourceId
import com.xtreeroot.mb.music.Track

internal val LocalTrackActions = staticCompositionLocalOf<(Track) -> Unit> { {} }

@Composable
internal fun TrackActionsButton(track: Track) {
    val open = LocalTrackActions.current
    IconButton(onClick = { open(track) }) { Icon(Icons.Default.MoreVert, mb("操作 ${track.title}", "Actions for ${track.title}")) }
}

@Composable
internal fun TrackActions(track: Track, saved: SavedLibraryModel, connected: Boolean, close: () -> Unit,
                          enqueue: (Boolean) -> Unit, export: () -> Unit, creator: ((String) -> Unit)?) {
    val context = LocalContext.current
    val offline by saved.offline.collectAsStateWithLifecycle()
    val transfers by saved.transfers.collectAsStateWithLifecycle()
    val playlists by saved.playlists.collectAsStateWithLifecycle()
    var choosePlaylist by remember(track.id) { mutableStateOf(false) }
    var createPlaylist by remember(track.id) { mutableStateOf(false) }
    val downloaded = offline.downloads.any { it.track.id == track.id }
    val offlineAvailable = downloaded || track.sourceId == localSourceId
    if (createPlaylist) {
        PlaylistNameDialog(mb("新建歌单", "New Playlist"), close = close) { name -> saved.create(name, listOf(track.id)); close() }
    } else AlertDialog(onDismissRequest = close, title = { Text(if (choosePlaylist) mb("添加到歌单", "Add to Playlist") else track.title, maxLines = 2) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (choosePlaylist) {
                    playlists.forEach { playlist ->
                        TrackAction(playlist.name) { saved.add(playlist.id, listOf(track.id)); close() }
                    }
                    TrackAction(mb("新建歌单", "New Playlist")) { createPlaylist = true }
                } else {
                    TrackAction(mb("加入播放队列", "Add to Queue"), connected) { enqueue(false); close() }
                    TrackAction(mb("下一首播放", "Play Next"), connected) { enqueue(true); close() }
                    if (offlineAvailable) {
                        TrackAction(mb("添加到歌单", "Add to Playlist")) { choosePlaylist = true }
                        TrackAction(mb("导出音频", "Export Audio")) { export(); close() }
                    } else if (track.id in transfers) TrackAction(mb("取消下载", "Cancel Download")) { saved.cancel(track.id); close() }
                    else TrackAction(mb("下载音频", "Download Audio")) { saved.download(track); close() }
                    if (creator != null) track.artistId?.let { id -> TrackAction(mb("查看 UP 主投稿", "View Creator Uploads")) { creator(id); close() } }
                    track.pageUrl?.let { url -> TrackAction(mb("分享链接", "Share Link")) {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, url), null))
                        close()
                    } }
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    val titleLabel = mb("标题", "Title")
                    val creditLabel = mb("标题和作者", "Title and Artist")
                    TrackAction(mb("复制标题", "Copy Title")) { clipboard.setPrimaryClip(ClipData.newPlainText(titleLabel, track.title)); close() }
                    TrackAction(mb("复制标题和作者", "Copy Title and Artist")) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(creditLabel, "${track.title} - ${track.artist}")); close()
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = close) { Text(mb("关闭", "Close")) } })
}

@Composable
private fun TrackAction(text: String, enabled: Boolean = true, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(text) }
}

@Composable
internal fun PlaylistNameDialog(title: String, initial: String = "", close: () -> Unit, save: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = close, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(mb("歌单名称", "Playlist Name")) }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { save(name) }) { Text(mb("保存", "Save")) } },
        dismissButton = { TextButton(onClick = close) { Text(mb("取消", "Cancel")) } })
}
