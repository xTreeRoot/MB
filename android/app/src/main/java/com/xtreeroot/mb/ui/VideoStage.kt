package com.xtreeroot.mb.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.playback.PlaybackState

@Composable
internal fun VideoStage(track: Track, playback: PlaybackState, toggle: () -> Unit, seek: (Long) -> Unit,
                        retry: () -> Unit, fullScreen: Boolean, setFullScreen: (Boolean) -> Unit) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (!lifecycle.isAtLeast(Lifecycle.State.STARTED)) return
    val application = LocalContext.current.applicationContext as MBApplication
    val coordinator by application.videoPlayback.collectAsStateWithLifecycle()
    val video = coordinator ?: return Loading()
    val current by video.frames.collectAsStateWithLifecycle()
    DisposableEffect(video, track.id) {
        val ticket = video.show(track)
        onDispose { ticket?.close() }
    }
    val state = current?.takeIf { it.trackId == track.id }
    val error = state?.error
    if (error != null) return Retry(error, retry)
    if (fullScreen) Dialog(onDismissRequest = { setFullScreen(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        VideoFrame(state?.player, state?.loading != false, playback, toggle, seek, mb("退出全屏", "Exit Fullscreen"), { setFullScreen(false) },
            Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing))
    } else VideoFrame(state?.player, state?.loading != false, playback, toggle, seek, mb("全屏", "Fullscreen"), { setFullScreen(true) }, Modifier.fillMaxSize())
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoFrame(engine: Player?, loading: Boolean, playback: PlaybackState, toggle: () -> Unit,
                       seek: (Long) -> Unit, action: String, fullscreen: () -> Unit, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(factory = { context -> PlayerView(context).apply { useController = false } },
            update = { view -> view.player = engine }, onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize())
        if (loading) Loading()
        MaterialTheme(colorScheme = darkColorScheme(primary = Color.White)) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
                    .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayButton(playback.playWhenReady, toggle)
                    Column(Modifier.weight(1f).padding(bottom = 6.dp)) { PlayerProgress(playback, smoke = false, seek) }
                    TextButton(onClick = fullscreen) { Text(action, color = Color.White) }
                }
            }
        }
    }
}
