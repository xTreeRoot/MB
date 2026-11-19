package com.xtreeroot.mb.playback

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.CaptionLine
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.VideoSource
import com.xtreeroot.mb.music.toMetadataJson
import com.xtreeroot.mb.preferences.PlaybackMode
import com.xtreeroot.mb.ui.LoadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

internal data class PlaybackState(
    val tracks: List<Track> = emptyList(),
    val index: Int = -1,
    val playWhenReady: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val error: String? = null,
    val connected: Boolean = false,
    val playing: Boolean = false,
    val videoAvailable: Boolean = false,
    val presentation: PlaybackPresentation = PlaybackPresentation(),
    val station: PlaybackStation? = null,
) {
    val current: Track? get() = tracks.getOrNull(index)
}

internal class PlaybackModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MBApplication
    private val state = MutableStateFlow(PlaybackState())
    private val captionState = MutableStateFlow(LoadState(emptyList<CaptionLine>()))
    private var captionJob: Job? = null
    private var captionTrackId: String? = null
    private var contentSelection: Pair<String, PlaybackMode>? = null
    private var videoSource: VideoSource? = null
    private var videoCapability: Boolean? = false
    private var videoJob: Job? = null
    private var controller: MediaController? = null
    private val future = MediaController.Builder(application,
        SessionToken(application, ComponentName(application, PlaybackService::class.java)))
        .setListener(object : MediaController.Listener {
            override fun onDisconnected(controller: MediaController) {
                this@PlaybackModel.controller = null
                contentSelection = null
                state.value = state.value.copy(connected = false, error = "播放服务已断开")
            }
        }).buildAsync()
    val playback = state.asStateFlow()
    val captions = captionState.asStateFlow()

    init {
        future.addListener({
            runCatching { future.get() }.onSuccess { player ->
                controller = player
                player.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = publish()
                })
                publish()
            }.onFailure { state.value = PlaybackState(error = "无法连接播放服务") }
        }, ContextCompat.getMainExecutor(application))
        viewModelScope.launch {
            app.settings.values.map { it.builtInEnabled }.distinctUntilChanged().collect { publish() }
        }
        viewModelScope.launch { app.installedPlugins.collect { publish() } }
        viewModelScope.launch { app.stationStatus.collect { publish() } }
        viewModelScope.launch {
            while (true) {
                delay(if (captionTrackId == state.value.current?.id && captionTrackId != null) 70 else 250)
                val player = controller ?: continue
                if (player.isPlaying) state.value = state.value.copy(positionMs = player.currentPosition)
            }
        }
    }

    override fun onCleared() {
        controller = null
        MediaController.releaseFuture(future)
    }

    fun play(tracks: List<Track>, selected: Track, station: PlaybackStation? = null, enableShuffle: Boolean = false) {
        val player = controller ?: return
        val playable = tracks.filterNot(Track::isUnavailable)
        val index = playable.indexOfFirst { it.id == selected.id }
        if (index < 0) return
        contentSelection = selected.id to app.settings.values.value.playbackMode
        player.setMediaItems(playable.mapIndexed { position, track ->
            val item = track.mediaItem()
            if (position == index && station != null) item.withStation(station) else item
        }, index, 0)
        if (enableShuffle) player.shuffleModeEnabled = true
        player.prepare()
        player.play()
        publish()
    }

    fun watchCurrentVideo() {
        val previous = state.value
        if (previous.current == null || !previous.videoAvailable) return
        state.value = previous.copy(presentation = previous.presentation.update(true, PlaybackMode.Video))
    }

    fun closeVideo() {
        state.value = state.value.let { it.copy(presentation = it.presentation.copy(mode = PlaybackMode.Music)) }
    }

    private fun resolveVideoCapability(track: Track?): Boolean? {
        val source = track?.let { app.playbackSource(it.sourceId) } as? VideoSource
        if (videoSource === source) return videoCapability
        videoSource = source
        videoJob?.cancel()
        videoCapability = if (source == null) false else null
        if (source != null) videoJob = viewModelScope.launch {
            yield()
            videoCapability = try { source.supportsVideo() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            publish()
        }
        return videoCapability
    }

    fun enqueue(track: Track, next: Boolean) {
        if (track.isUnavailable) return
        val player = controller ?: return
        player.sendCustomCommand(enqueueTrack, Bundle().apply {
            putString("track", track.toMetadataJson().toString())
            putBoolean("next", next)
        })
    }

    fun toggle() {
        val player = controller ?: return
        if (player.mediaItemCount == 0) return
        if (player.playerError == null && !player.isPlaying && stationError(player) != null) { requestStationNext(player); return }
        if (player.playWhenReady && player.playerError == null) player.pause() else start(player)
    }

    fun retry() {
        val player = controller ?: return
        if (player.playerError != null) player.prepare()
        else if (stationError(player) != null) requestStationNext(player)
    }

    fun retryVideo() {
        val video = app.videoPlayback.value ?: return
        video.retry()
        controller?.takeIf { it.playerError != null }?.prepare()
    }

    private fun requestStationNext(player: MediaController) { player.sendCustomCommand(nextStationTrack, Bundle.EMPTY) }

    fun endStation() { controller?.sendCustomCommand(leaveStation, Bundle.EMPTY) }

    private fun atStationTail(player: Player): Boolean = app.stationStatus.value.station != null &&
        player.mediaItemCount > 0 && player.currentMediaItemIndex == player.mediaItemCount - 1

    private fun stationError(player: Player): String? = if (!atStationTail(player)) null else when (app.stationStatus.value.failure) {
        StationFailure.Empty -> "电台暂无可播放曲目"
        StationFailure.Load -> "电台加载失败"
        null -> null
    }

    private fun start(player: Player) {
        when (player.playbackState) {
            Player.STATE_IDLE -> player.prepare()
            Player.STATE_ENDED -> player.seekToDefaultPosition()
        }
        player.play()
    }

    fun select(index: Int) {
        controller?.let { player ->
            if (index in 0 until player.mediaItemCount) {
                contentSelection = null
                player.seekToDefaultPosition(index)
                start(player)
            }
        }
    }

    fun move(from: Int, to: Int) {
        controller?.let { player -> if (to in 0 until player.mediaItemCount) player.moveMediaItem(from, to) }
    }

    fun remove(index: Int) { controller?.removeMediaItem(index) }
    fun removeSource(sourceId: String) {
        val player = controller ?: return
        for (index in player.mediaItemCount - 1 downTo 0) {
            val track = player.getMediaItemAt(index).track() ?: continue
            if (track.sourceId != sourceId) continue
            if (contentSelection?.first == track.id) contentSelection = null
            player.removeMediaItem(index)
        }
    }
    fun clear() { contentSelection = null; controller?.clearMediaItems() }
    fun seek(positionMs: Long) { controller?.seekTo(positionMs) }
    fun previous() = step(forward = false)
    fun next() = step(forward = true)

    private fun step(forward: Boolean) {
        val player = controller ?: return
        if (forward && atStationTail(player)) { requestStationNext(player); return }
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return
        val index = player.currentMediaItemIndex
        val shuffle = player.shuffleModeEnabled
        val target = if (forward) timeline.getNextWindowIndex(index, Player.REPEAT_MODE_ALL, shuffle)
        else timeline.getPreviousWindowIndex(index, Player.REPEAT_MODE_ALL, shuffle)
        select(target)
    }

    fun shuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun repeat() {
        controller?.let { player ->
            player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun loadCaptions(track: Track) {
        if (captionTrackId == track.id && captionJob?.isActive == true) return
        captionJob?.cancel()
        captionTrackId = track.id
        captionState.value = LoadState(emptyList(), loading = true)
        captionJob = viewModelScope.launch {
            try {
                val source = getApplication<MBApplication>().playbackSource(track.sourceId)
                captionState.value = LoadState(source?.captions(track.sourceTrackId).orEmpty())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                captionState.value = LoadState(emptyList(), error = failure.message ?: "字幕加载失败")
            }
        }
    }

    private fun publish() {
        val player = controller ?: return
        val tracks = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).track() }
        val index = player.currentMediaItemIndex
        val current = tracks.getOrNull(index)
        val capability = resolveVideoCapability(current)
        val chosen = contentSelection?.takeIf { capability != null && it.first == current?.id }
        if (chosen != null || player.playerError != null) contentSelection = null
        val presentation = state.value.presentation.update(capability == true, chosen?.second)
        if (captionTrackId != current?.id) {
            captionJob?.cancel()
            captionTrackId = null
            captionState.value = LoadState(emptyList())
        }
        state.value = PlaybackState(tracks, index, player.playWhenReady,
            player.playbackState == Player.STATE_BUFFERING, player.currentPosition,
            player.duration.takeUnless { it == C.TIME_UNSET } ?: current?.durationMs ?: 0,
            player.repeatMode, player.shuffleModeEnabled,
            player.playerError?.let { "播放失败（${it.errorCodeName}）" } ?: stationError(player), connected = true, playing = player.isPlaying,
            videoAvailable = capability == true, presentation = presentation, station = app.stationStatus.value.station)
    }
}
