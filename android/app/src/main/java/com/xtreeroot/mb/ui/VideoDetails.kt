package com.xtreeroot.mb.ui

import android.animation.ValueAnimator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.InteractionSource
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.playback.PlaybackModel
import com.xtreeroot.mb.playback.PlaybackState
import com.xtreeroot.mb.playback.VideoFrameState
import com.xtreeroot.mb.preferences.AppPreferences

@Composable
internal fun VideoDetailsScreen(model: PlaybackModel, state: PlaybackState, preferences: AppPreferences, signedIn: Boolean,
                                fullScreen: Boolean, setFullScreen: (Boolean) -> Unit, browse: () -> Unit, openMusic: () -> Unit) {
    val track = state.current ?: return
    var selected by rememberSaveable(track.id) { mutableStateOf(VideoDetailTab.Info) }
    val frame = rememberVideoFrame(track)
    if (fullScreen) VideoStage(frame, state, model::toggle, { model.seekCurrent(state, it) }, model::retryVideo,
        fullScreen = true, setFullScreen)
    val picture: @Composable (Modifier) -> Unit = { modifier ->
        WatchPicture(track, state, model, browse, openMusic, frame, fullScreen, setFullScreen, modifier)
    }
    val information: @Composable (Modifier) -> Unit = { modifier ->
        VideoInformation(track, state, model, preferences, signedIn, selected, { selected = it }, modifier)
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (maxWidth >= 600.dp && maxWidth > maxHeight) {
            val pictureWidth = minOf(maxWidth * 0.57f, maxHeight * (16f / 9f))
            Row(Modifier.fillMaxSize()) {
                picture(Modifier.width(pictureWidth))
                information(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            val pictureWidth = minOf(maxWidth, maxHeight * (0.62f * 16f / 9f))
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                picture(Modifier.width(pictureWidth))
                information(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WatchPicture(track: Track, state: PlaybackState, model: PlaybackModel, browse: () -> Unit,
                         openMusic: () -> Unit, frame: VideoFrameState?, fullScreen: Boolean, setFullScreen: (Boolean) -> Unit,
                         modifier: Modifier) {
    Box(modifier.aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
        if (!fullScreen) VideoStage(frame, state, model::toggle, { model.seekCurrent(state, it) }, model::retryVideo,
            fullScreen = false, setFullScreen) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent))),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = browse) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, mb("返回浏览", "Back to Browse"), tint = Color.White)
                }
                Box(Modifier.weight(1f))
                TextButton(onClick = openMusic) { Text(mb("听歌模式", "Music Player"), color = Color.White) }
            }
        }
    }
}

@Composable
private fun VideoInformation(track: Track, state: PlaybackState, model: PlaybackModel, preferences: AppPreferences,
                             signedIn: Boolean, selected: VideoDetailTab, select: (VideoDetailTab) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val source = (context.applicationContext as MBApplication).playbackSource(track.sourceId) as? InteractionSource
    Column(modifier.fillMaxWidth()) {
        val tabs: @Composable () -> Unit = {
            VideoDetailTab.entries.forEach { tab ->
                Tab(selected == tab, { select(tab) }, text = { Text(tab.label(), maxLines = 1, overflow = TextOverflow.Ellipsis) })
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.4f) {
                PrimaryScrollableTabRow(selected.ordinal, edgePadding = 8.dp, tabs = tabs)
            } else PrimaryTabRow(selected.ordinal, tabs = tabs)
        }
        when (selected) {
            VideoDetailTab.Info -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                item {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Text(track.artist, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(track.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        if (source != null) PlayerInteractionBar(track, signedIn, showCommentsButton = false)
                        state.error?.let { Retry(it, model::retry) }
                        FlowRow(Modifier.fillMaxWidth()) {
                            TextButton(onClick = model::previous) { Text(mb("上一首", "Previous")) }
                            TextButton(onClick = model::next) { Text(mb("下一首", "Next")) }
                            TextButton(onClick = model::shuffle) { Text(if (state.shuffle) mb("随机", "Shuffle") else mb("顺序", "In Order")) }
                            TextButton(onClick = model::repeat) {
                                Text(when (state.repeatMode) {
                                    Player.REPEAT_MODE_ONE -> mb("单曲", "Repeat One")
                                    Player.REPEAT_MODE_ALL -> mb("循环", "Repeat All")
                                    else -> mb("不循环", "No Repeat")
                                })
                            }
                        }
                    }
                }
            }
            VideoDetailTab.Comments -> if (source == null) EmptyMessage(mb("此音源不支持评论", "Comments are not available for this source"))
                else TrackComments(source, track, signedIn, Modifier.fillMaxSize())
            VideoDetailTab.Captions -> VideoCaptions(track, state, model, preferences)
            VideoDetailTab.Queue -> LazyColumn(Modifier.fillMaxSize()) {
                item { Text(mb("播放队列 · ${state.tracks.size}", "Queue · ${state.tracks.size}"),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium) }
                itemsIndexed(state.tracks) { index, item ->
                    VideoPreviewRow(item, state.connected, selectedTrack = index == state.index) { model.select(index) }
                }
            }
        }
    }
}

@Composable
private fun VideoCaptions(track: Track, state: PlaybackState, model: PlaybackModel, preferences: AppPreferences) {
    val captions by model.captions.collectAsStateWithLifecycle()
    LaunchedEffect(track.id) { model.loadCaptions(track) }
    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        when {
            captions.loading -> Loading()
            captions.error != null -> Retry(checkNotNull(captions.error)) { model.loadCaptions(track) }
            captions.value.isEmpty() -> EmptyMessage(mb("暂无字幕", "No Captions"))
            else -> CaptionStage(captions.value, state.positionMs, preferences.lyricStyle, preferences.stageSparks,
                ValueAnimator.areAnimatorsEnabled(), { model.seekCurrent(state, it) })
        }
    }
}

private enum class VideoDetailTab { Info, Comments, Captions, Queue }

@Composable
private fun VideoDetailTab.label(): String = when (this) {
    VideoDetailTab.Info -> mb("简介", "Info")
    VideoDetailTab.Comments -> mb("评论", "Comments")
    VideoDetailTab.Captions -> mb("字幕", "Captions")
    VideoDetailTab.Queue -> mb("队列", "Queue")
}
