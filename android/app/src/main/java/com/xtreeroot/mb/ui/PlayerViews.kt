package com.xtreeroot.mb.ui

import android.animation.ValueAnimator
import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.R
import com.xtreeroot.mb.local.localSourceId
import com.xtreeroot.mb.music.InteractionSource
import com.xtreeroot.mb.music.LibrarySource
import com.xtreeroot.mb.music.TrackComment
import com.xtreeroot.mb.music.quietCommentAt
import com.xtreeroot.mb.playback.PlaybackModel
import com.xtreeroot.mb.playback.PlaybackState
import com.xtreeroot.mb.preferences.AppPreferences
import com.xtreeroot.mb.preferences.LyricStyle
import kotlinx.coroutines.CancellationException

@Composable
internal fun MiniPlayer(state: PlaybackState, open: () -> Unit, toggle: () -> Unit, queue: () -> Unit,
                        coverBounds: (Rect) -> Unit) {
    val track = state.current ?: return
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(8.dp), color = colors.surfaceContainer, shadowElevation = 2.dp,
        border = BorderStroke(0.5.dp, colors.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(onClick = open).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Artwork(track, Modifier.size(40.dp).onGloballyPositioned { coverBounds(it.boundsInWindow()) })
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        (state.error ?: track.artist).takeIf(String::isNotBlank)?.let { subtitle ->
                            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
                PlayButton(state.playWhenReady && state.error == null, toggle)
                IconButton(onClick = queue) { Icon(Icons.AutoMirrored.Filled.List, mb("播放队列", "Queue"), Modifier.size(20.dp)) }
            }
            if (state.durationMs > 0) {
                val progress = (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(2.dp).background(colors.outlineVariant)) {
                    Box(Modifier.fillMaxWidth(progress).height(2.dp).background(colors.primary))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlaying(model: PlaybackModel, state: PlaybackState, preferences: AppPreferences, saved: SavedLibraryModel, signedIn: Boolean,
                        close: () -> Unit, showQueue: () -> Unit, showArtist: (String) -> Unit, openingCover: Rect?,
                        rotate: ((PlaybackDirection) -> Unit)?) {
    val track = state.current ?: return
    val offline by saved.offline.collectAsStateWithLifecycle()
    val transfers by saved.transfers.collectAsStateWithLifecycle()
    val activity by saved.activity.collectAsStateWithLifecycle()
    val inLibrary = track.sourceId == localSourceId || offline.downloads.any { it.track.id == track.id }
    val favorite = (activity[track.id]?.likedAt ?: 0) > 0
    val app = LocalContext.current.applicationContext as MBApplication
    val source = app.playbackSource(track.sourceId)
    val supportsVideo = state.videoAvailable
    var showingCaptions by rememberSaveable { mutableStateOf(preferences.lyricsStage) }
    var widePage by rememberSaveable { mutableStateOf(WidePlayerPage.Lyrics) }
    val window = LocalWindowInfo.current.containerSize
    val windowWidth = with(LocalDensity.current) { window.width.toDp() }
    val wide = window.width > window.height && windowWidth >= 600.dp
    val enlargedText = LocalDensity.current.fontScale >= 1.5f
    val showToolbarTitle = windowWidth >= if (enlargedText) 400.dp else 360.dp
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val animate = ValueAnimator.areAnimatorsEnabled()
    val origin = if (animate && preferences.growFromCover) openingCover else null
    var frame by remember { mutableStateOf<Rect?>(null) }
    val entrance = remember { Animatable(if (origin == null) 1f else 0f) }
    LaunchedEffect(frame != null) { if (frame != null) entrance.animateTo(1f, tween(320)) }
    val captions by model.captions.collectAsStateWithLifecycle()
    val interaction = source as? InteractionSource
    val lyricsVisible = if (wide) widePage == WidePlayerPage.Lyrics || widePage == WidePlayerPage.Rhythm else showingCaptions
    val showHotComments = preferences.hotComments && interaction != null && !wide && showingCaptions
    var hotComments by remember(track.id, interaction, signedIn, showHotComments) { mutableStateOf(emptyList<TrackComment>()) }
    var openComments by remember(track.id, interaction, signedIn) { mutableStateOf(false) }
    LaunchedEffect(track.id, source, lyricsVisible) {
        if (lyricsVisible) model.loadCaptions(track)
    }
    LaunchedEffect(track.id, interaction, signedIn, showHotComments) {
        if (showHotComments) {
            try {
                if (interaction.interactionCapabilities().comments) hotComments = interaction.comments(track.sourceTrackId).comments
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
    }
    val comment = if (showHotComments && !captions.loading && captions.error == null) {
        quietCommentAt(state.positionMs, captions.value, state.durationMs, hotComments)
    } else null
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = if (origin == null) Modifier else Modifier.onGloballyPositioned { frame = it.boundsInWindow() }.graphicsLayer {
            val destination = frame
            val progress = entrance.value
            if (destination == null) alpha = 0f else {
                val initial = (origin.width / destination.width.coerceAtLeast(1f)).coerceIn(0.03f, 1f)
                transformOrigin = TransformOrigin.Center
                scaleX = initial + (1f - initial) * progress
                scaleY = scaleX
                translationX = (origin.center.x - destination.center.x) * (1f - progress)
                translationY = (origin.center.y - destination.center.y) * (1f - progress)
                alpha = progress.coerceAtLeast(0.02f)
                clip = true
            }
        },
        topBar = {
            TopAppBar(title = {
                if (showToolbarTitle) Text(mb("正在播放", "Now Playing"), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            },
                expandedHeight = 48.dp, colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surfaceContainerLow), navigationIcon = {
                IconButton(onClick = close) { Icon(Icons.Default.KeyboardArrowDown, mb("关闭播放器", "Close Player")) }
            }, actions = {
                if (supportsVideo) IconButton(onClick = model::watchCurrentVideo) {
                    Icon(painterResource(R.drawable.ic_watch_video), mb("观看当前视频", "Watch Current Video"))
                }
                PlayerStyleMenu(preferences, if (wide) lyricsVisible else showingCaptions, chooseArtwork = { style ->
                    app.settings.update { it.copy(playerStyle = style) }
                    showingCaptions = false
                }, chooseLyrics = { style ->
                    app.settings.update { it.copy(lyricStyle = style) }
                    showingCaptions = true
                    if (wide) widePage = WidePlayerPage.Lyrics
                })
                if (rotate != null) IconButton(onClick = { rotate(if (landscape) PlaybackDirection.Portrait else PlaybackDirection.Landscape) }) {
                    Icon(painterResource(R.drawable.ic_screen_rotate), if (landscape) mb("竖屏播放", "Portrait Player") else mb("横屏播放", "Landscape Player"))
                }
                AudioOutputButton(state.connected)
                IconButton(onClick = showQueue) { Icon(Icons.AutoMirrored.Filled.List, mb("播放队列", "Queue")) }
            })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.surfaceContainerLow, colors.background))),
            contentAlignment = Alignment.Center) {
            if (preferences.fog) {
                val bass by app.bassLevel.collectAsStateWithLifecycle()
                PlaybackFog(track, if (state.playing) bass else 0f, animate, Modifier.matchParentSize())
            }
            val seekCurrent: (Long) -> Unit = { model.seekCurrent(track.id, state.index, it) }
            val showLyrics: @Composable (Boolean) -> Unit = { rhythm ->
                when {
                    captions.loading -> Loading()
                    captions.error != null -> Retry(checkNotNull(captions.error)) { model.loadCaptions(track) }
                    captions.value.isEmpty() && rhythm -> PlayerArtwork(track, state, preferences.playerStyle, animate, Modifier)
                    captions.value.isEmpty() -> EmptyMessage(mb("暂无歌词", "No Lyrics"))
                    else -> CaptionStage(captions.value, state.positionMs, if (rhythm) LyricStyle.Tilt else preferences.lyricStyle,
                        preferences.stageSparks, animate, seekCurrent)
                }
            }
            val stage: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier.padding(horizontal = 24.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    when {
                        comment != null -> Column(Modifier.fillMaxWidth().clickable { openComments = true }.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(mb("热评", "Top Comment"), style = MaterialTheme.typography.labelLarge)
                            Text(comment.text, style = MaterialTheme.typography.titleLarge)
                            Text("${comment.author} · ${comment.likes} ${mb("赞", "likes")}", style = MaterialTheme.typography.labelMedium)
                        }
                        showingCaptions -> showLyrics(false)
                        else -> {
                            val watch = mb("切换到实时视频", "Switch to Video")
                            PlayerArtwork(track, state, preferences.playerStyle, animate,
                                if (supportsVideo) Modifier.clickable(onClickLabel = watch, role = Role.Button, onClick = model::watchCurrentVideo)
                                    .semantics { contentDescription = watch } else Modifier)
                        }
                    }
                }
            }
            val controls: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(track.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (inLibrary) {
                            IconButton(onClick = { saved.toggleFavorite(track) }) {
                                Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    if (favorite) mb("取消本地喜欢", "Unlike Locally") else mb("本地喜欢", "Like Locally"))
                            }
                        } else {
                            IconButton(onClick = { saved.download(track) }, enabled = track.id !in transfers && !track.isUnavailable) {
                                Icon(painterResource(R.drawable.ic_download), mb("下载音频", "Download Audio"))
                            }
                        }
                        TrackActionsButton(track)
                    }
                    val artistId = track.artistId
                    Text(track.artist, modifier = if (artistId == null || source !is LibrarySource) Modifier else Modifier.clickable(onClickLabel = mb("查看 UP 主投稿", "View Creator Uploads")) {
                        showArtist(artistId)
                    }, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.station?.let { station ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(station.title.ifBlank { mb("电台", "Radio") }, Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = model::endStation, enabled = state.connected) {
                                Text(mb("退出电台", "Leave Station"))
                            }
                        }
                    }
                    if (state.error != null) Retry(state.error, model::retry)
                    else if (state.loading) Text(mb("正在加载…", "Loading…"), style = MaterialTheme.typography.labelMedium)
                    PlayerProgress(state, preferences.smoke && animate, seekCurrent)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        PlayerModeControl(if (state.shuffle) mb("随机", "Shuffle") else mb("顺序", "In Order"),
                            R.drawable.ic_shuffle, state.shuffle, state.connected, model::shuffle, Modifier.weight(1f))
                        IconButton(onClick = model::previous) {
                            Icon(painterResource(R.drawable.ic_media_previous), mb("上一首", "Previous"))
                        }
                        PlayButton(state.playWhenReady && state.error == null, model::toggle, prominent = !enlargedText)
                        IconButton(onClick = model::next) {
                            Icon(painterResource(R.drawable.ic_media_next), mb("下一首", "Next"))
                        }
                        val repeat = when (state.repeatMode) {
                            Player.REPEAT_MODE_ONE -> mb("单曲", "Repeat One")
                            Player.REPEAT_MODE_ALL -> mb("循环", "Repeat All")
                            else -> mb("不循环", "Repeat Off")
                        }
                        PlayerModeControl(repeat, if (state.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                            state.repeatMode != Player.REPEAT_MODE_OFF, state.connected, model::repeat, Modifier.weight(1f))
                    }
                    PlayerInteractionBar(track, signedIn, showCommentsButton = !wide)
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (wide) {
                    Row(Modifier.widthIn(max = 1040.dp).fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        WidePlayerStage(track, state, model, widePage, { widePage = it }, interaction, signedIn, showLyrics,
                            Modifier.weight(1.24f).fillMaxHeight())
                        controls(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                    }
                } else if (maxWidth >= 720.dp) {
                    val stageHeight = minOf(540.dp, maxHeight * 0.62f)
                    Column(Modifier.widthIn(max = 580.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        stage(Modifier.fillMaxWidth().height(stageHeight))
                        controls(Modifier.fillMaxWidth())
                    }
                } else if (enlargedText || maxHeight < 520.dp) {
                    val stageHeight = minOf(maxWidth, 360.dp)
                    Column(Modifier.widthIn(max = 480.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
                        stage(Modifier.fillMaxWidth().height(stageHeight))
                        controls(Modifier.fillMaxWidth())
                    }
                } else {
                    Column(Modifier.widthIn(max = 480.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        stage(Modifier.weight(1f).fillMaxWidth())
                        controls(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    if (openComments && interaction != null) CommentsSheet(interaction, track, signedIn) { openComments = false }
}

@Composable
private fun PlayerModeControl(label: String, icon: Int, active: Boolean, enabled: Boolean, onClick: () -> Unit,
                              modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
        active -> colors.primary
        else -> colors.onSurfaceVariant
    }
    val status = if (active) mb("已开启", "On") else mb("已关闭", "Off")
    Column(modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { stateDescription = status }.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        Icon(painterResource(icon), null, Modifier.size(19.dp), tint = tint)
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun PlayButton(playing: Boolean, toggle: () -> Unit, prominent: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val size = if (prominent) 32.dp else 24.dp
    val modifier = if (prominent) Modifier.size(60.dp).background(colors.primary, CircleShape) else Modifier
    IconButton(onClick = toggle, modifier = modifier, colors = IconButtonDefaults.iconButtonColors(
        contentColor = if (prominent) colors.onPrimary else colors.onSurface)) {
        if (playing) Icon(painterResource(R.drawable.ic_media_pause), mb("暂停", "Pause"), Modifier.size(size))
        else Icon(Icons.Default.PlayArrow, mb("播放", "Play"), Modifier.size(size))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheet(model: PlaybackModel, state: PlaybackState, close: () -> Unit) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val expand = with(LocalDensity.current) { windowHeight.toDp() < 480.dp || fontScale >= 1.5f }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = expand)
    ModalBottomSheet(onDismissRequest = close, sheetState = sheet) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(mb("播放队列 · ${state.tracks.size}", "Queue · ${state.tracks.size}"), Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { confirmClear = true }, enabled = state.tracks.isNotEmpty()) { Text(mb("清空", "Clear")) }
            IconButton(onClick = close) { Icon(Icons.Default.Close, mb("关闭队列", "Close Queue")) }
        }
        QueueTracks(model, state)
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(mb("清空播放队列？", "Clear the play queue?")) },
        confirmButton = { TextButton(onClick = { confirmClear = false; model.clear(); close() }) { Text(mb("清空", "Clear")) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(mb("取消", "Cancel")) } })
}

@Composable
internal fun QueueTracks(model: PlaybackModel, state: PlaybackState, modifier: Modifier = Modifier) {
    if (state.tracks.isEmpty()) return EmptyMessage(mb("队列为空", "Your queue is empty"))
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 14.dp)) {
        itemsIndexed(state.tracks, key = { index, track -> "$index:${track.id}" }) { index, track ->
            val active = index == state.index
            var menu by remember { mutableStateOf(false) }
            val colors = MaterialTheme.colorScheme
            ListItem(modifier = Modifier.selectable(active, enabled = state.connected, role = Role.Button,
                onClick = { model.select(index) }), colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (active) colors.primary else colors.onSurface) },
                supportingContent = { TrackSubtitle(track) },
                leadingContent = { Artwork(track, Modifier.size(44.dp)) },
                trailingContent = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, mb("编辑 ${track.title}", "Edit ${track.title}")) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(mb("上移", "Move Up")) }, enabled = index > 0,
                                onClick = { menu = false; model.move(index, index - 1) })
                            DropdownMenuItem(text = { Text(mb("下移", "Move Down")) }, enabled = index < state.tracks.lastIndex,
                                onClick = { menu = false; model.move(index, index + 1) })
                            DropdownMenuItem(text = { Text(mb("从队列移除", "Remove from Queue")) }, onClick = { menu = false; model.remove(index) })
                        }
                    }
                })
        }
    }
}
