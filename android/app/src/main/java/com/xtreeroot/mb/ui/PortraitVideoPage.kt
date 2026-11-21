package com.xtreeroot.mb.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import com.xtreeroot.mb.R
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.xtreeroot.mb.ui.components.MBBottomSheet as ModalBottomSheet
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.InteractionSource
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.playback.PlaybackModel
import com.xtreeroot.mb.playback.PlaybackState
import com.xtreeroot.mb.playback.VideoFrameState
import com.xtreeroot.mb.preferences.PlaybackMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 按视频真实尺寸呈现竖屏资源，手势和操作适用于所有支持视频的音源。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortraitVideoPage(track: Track, frame: VideoFrameState?, state: PlaybackState, model: PlaybackModel,
                               signedIn: Boolean, browse: () -> Unit, fullScreen: Boolean,
                               setFullScreen: (Boolean) -> Unit, chooseMode: (PlaybackMode) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MBApplication
    val scope = rememberCoroutineScope()
    val resources by app.resourceBookmarks.tracks.collectAsStateWithLifecycle()
    val saved = resources.any { it.id == track.id }
    val source = app.playbackSource(track.sourceId) as? InteractionSource
    var captions by remember(track.id) { mutableStateOf(false) }
    var comments by remember(track.id) { mutableStateOf(false) }
    var notice by remember(track.id) { mutableStateOf<String?>(null) }
    val actions = LocalTrackActions.current
    val shareLabel = mb("分享链接", "Share Link")
    val next by rememberUpdatedState(model::next)
    val previous by rememberUpdatedState(model::previous)
    if (fullScreen) VideoStage(frame, state, model::toggle, { model.seekCurrent(state, it) }, model::retryVideo, true, setFullScreen)
    Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = browse) { Text(mb("返回", "Back"), color = Color.White) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { chooseMode(PlaybackMode.Music) }) { Text(mb("听歌模式", "Music Mode"), color = Color.White) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val ratio = frame?.aspectRatio ?: if (track.videoWidth > 0 && track.videoHeight > 0) track.videoWidth.toFloat() / track.videoHeight else 9f / 16f
            val width = minOf(maxWidth, maxHeight * ratio)
            Box(Modifier.width(width).fillMaxHeight().pointerInput(track.id) {
                var distance = 0f
                detectVerticalDragGestures(onDragStart = { distance = 0f }, onDragCancel = { distance = 0f },
                    onVerticalDrag = { change, amount -> change.consume(); distance += amount },
                    onDragEnd = { if (distance < -72.dp.toPx()) next() else if (distance > 72.dp.toPx()) previous() })
            }) {
                if (!fullScreen) VideoStage(frame, state, model::toggle, { model.seekCurrent(state, it) }, model::retryVideo, false, setFullScreen)
            }
            Column(Modifier.align(Alignment.CenterEnd).padding(4.dp).width(68.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally) {
                PortraitAction(mb("上一个", "Previous"), model::previous) { Icon(Icons.Default.KeyboardArrowUp, null, tint = Color.White) }
                PortraitAction(if (state.playWhenReady) mb("暂停", "Pause") else mb("播放", "Play"), model::toggle) { if (state.playWhenReady) Icon(painterResource(R.drawable.ic_media_pause), null, tint = Color.White) else Icon(Icons.Default.PlayArrow, null, tint = Color.White) }
                if (source != null) PortraitAction(mb("评论", "Comments"), { comments = true }) { Icon(painterResource(R.drawable.ic_comment), null, tint = Color.White) }
                PortraitAction(if (saved) mb("已收藏", "Saved") else mb("收藏", "Save"), {
                    scope.launch {
                        try { withContext(Dispatchers.IO) { if (saved) app.resourceBookmarks.remove(track.id) else app.resourceBookmarks.save(track) } }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (failure: Exception) { notice = failure.message }
                    }
                }) { Icon(Icons.Default.Star, null, tint = if (saved) Color(0xFFFFD54F) else Color.White) }
                track.pageUrl?.let { url -> PortraitAction(mb("分享", "Share"), {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), shareLabel))
                }) { Icon(painterResource(R.drawable.ic_share), null, tint = Color.White) } }
                PortraitAction(mb("字幕", "Captions"), { captions = true }) { Text("CC", color = Color.White, style = MaterialTheme.typography.labelLarge) }
                PortraitAction(mb("下一个", "Next"), model::next) { Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White) }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(track.artist, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
            Text(track.title, color = Color.White, maxLines = 2)
            if (source != null) CompositionLocalProvider(LocalContentColor provides Color.White) { PlayerInteractionBar(track, signedIn, showCommentsButton = false) }
            TextButton(onClick = { actions(track) }) { Text(mb("加入歌单／稍后观看", "Playlist / Watch Later"), color = Color.White) }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (captions) ModalBottomSheet(onDismissRequest = { captions = false }) {
        VideoCaptions(track, state, model, app.settings.values.value, Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 440.dp))
    }
    if (comments && source != null) CommentsSheet(source, track, signedIn) { comments = false }
}

@Composable
private fun PortraitAction(label: String, action: () -> Unit, icon: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 3.dp)) {
        IconButton(onClick = action, modifier = Modifier.size(44.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape).semantics { contentDescription = label }) { icon() }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
