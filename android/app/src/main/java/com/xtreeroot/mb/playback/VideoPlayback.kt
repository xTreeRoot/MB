package com.xtreeroot.mb.playback

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.xtreeroot.mb.music.AudioQuality
import com.xtreeroot.mb.music.MediaResource
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.VideoSource
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient

internal data class VideoFrameState(val trackId: String, val player: Player? = null, val loading: Boolean = true,
                                    val error: String? = null)

@OptIn(UnstableApi::class)
internal class VideoPlayback(private val context: Context, private val http: OkHttpClient, private val audio: ExoPlayer,
                             private val scope: CoroutineScope, private val sourceFor: (Track) -> VideoSource?,
                             private val quality: () -> AudioQuality) : Player.Listener {
    private class Stage(val track: Track, val source: VideoSource, val quality: AudioQuality) {
        var resource: MediaResource? = null
        var engine: ExoPlayer? = null
        var firstFrame = false
        var recovery = MediaRecoveryBudget()
        var job: Job? = null
        var stalledSince: Long? = null
    }

    private var active: Stage? = null
    private val mutable = MutableStateFlow<VideoFrameState?>(null)
    val frames = mutable.asStateFlow()
    var onGateChanged: () -> Unit = {}
    var heldIntent: Boolean? = null
        private set

    init { audio.addListener(this) }

    fun show(track: Track): AutoCloseable? {
        if (audio.currentMediaItem?.mediaId != track.id) return null
        val source = sourceFor(track) ?: return null
        val stage = Stage(track, source, quality())
        active?.let(::retire)
        active = stage
        hold()
        if (audio.playbackState == Player.STATE_IDLE && audio.playerError == null) audio.prepare()
        load(stage, audio.currentPosition)
        return AutoCloseable { if (active === stage) hide() }
    }

    fun retry() {
        val stage = active ?: return
        stage.recovery = MediaRecoveryBudget()
        stage.resource = null
        hold()
        load(stage, audio.currentPosition)
    }

    fun changeIntent(value: Boolean): Boolean {
        if (heldIntent == null) return false
        heldIntent = value
        onGateChanged()
        return true
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mediaItem?.mediaId != active?.track?.id) hide()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) changeIntent(false)
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        val stage = active ?: return
        if ((reason != Player.DISCONTINUITY_REASON_SEEK && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) ||
            newPosition.mediaItem?.mediaId != stage.track.id) return
        hold()
        load(stage, newPosition.positionMs)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        val stage = active ?: return
        stage.engine?.let { synchronize(stage, it) }
    }

    private fun hold() {
        if (heldIntent != null) return
        heldIntent = audio.playWhenReady
        audio.pause()
        onGateChanged()
    }

    private fun releaseHold() {
        val intent = heldIntent ?: return
        heldIntent = null
        if (intent && audio.mediaItemCount > 0) audio.play()
        onGateChanged()
    }

    private fun hide() {
        val stage = active ?: return
        active = null
        retire(stage)
        mutable.value = null
        releaseHold()
    }

    private fun retire(stage: Stage) {
        stage.job?.cancel()
        val engine = stage.engine
        stage.engine = null
        engine?.release()
    }

    fun close() {
        audio.removeListener(this)
        onGateChanged = {}
        heldIntent = null
        hide()
    }

    private fun load(stage: Stage, position: Long, wait: Long = 0) {
        retire(stage)
        stage.firstFrame = false
        stage.stalledSince = null
        mutable.value = VideoFrameState(stage.track.id)
        stage.job = scope.launch {
            yield()
            delay(wait)
            if (!valid(stage)) { hideIfActive(stage); return@launch }
            val media = try {
                stage.resource ?: stage.source.video(stage.track.sourceTrackId, stage.quality)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (valid(stage)) mutable.value = VideoFrameState(stage.track.id, loading = false, error = "视频地址加载失败")
                return@launch
            }
            ensureActive()
            if (!valid(stage)) { hideIfActive(stage); return@launch }
            stage.resource = media
            val engine = ExoPlayer.Builder(context).setMediaSourceFactory(ProgressiveMediaSource.Factory {
                guardedMediaHttp(http).apply { media.headers.forEach(::setRequestProperty) }
            }.recoverOutsideLoader()).build()
            stage.engine = engine
            engine.trackSelectionParameters = engine.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            engine.volume = 0f
            engine.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (stage.engine !== engine) return
                    stage.firstFrame = true
                    synchronize(stage, engine)
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (stage.engine !== engine || !valid(stage)) return
                    hold()
                    val action = mediaRecovery(error)
                    val retry = action?.let(stage.recovery::delayFor)
                    if (retry != null) {
                        if (action == MediaRecovery.RefreshAddress) stage.resource = null
                        load(stage, audio.currentPosition, retry)
                    } else {
                        retire(stage)
                        mutable.value = VideoFrameState(stage.track.id, loading = false, error = "视频加载失败（${error.errorCodeName}）")
                    }
                }
            })
            mutable.value = VideoFrameState(stage.track.id, engine)
            engine.setMediaItem(MediaItem.fromUri(media.url), position.coerceAtLeast(0))
            engine.prepare()
            while (valid(stage) && stage.engine === engine) {
                delay(150)
                synchronize(stage, engine)
            }
            if (!valid(stage)) hideIfActive(stage)
        }
    }

    private fun valid(stage: Stage): Boolean = active === stage && audio.currentMediaItem?.mediaId == stage.track.id &&
        sourceFor(stage.track) === stage.source

    private fun hideIfActive(stage: Stage) { if (active === stage) hide() }

    private fun synchronize(stage: Stage, video: ExoPlayer) {
        if (!valid(stage) || stage.engine !== video) return
        val pictureReady = (video.playbackState == Player.STATE_READY || video.playbackState == Player.STATE_ENDED) && stage.firstFrame
        val ready = pictureReady && audio.playbackState == Player.STATE_READY
        if (heldIntent != null && ready) releaseHold()
        if (heldIntent == null && audio.isPlaying && video.playbackState == Player.STATE_BUFFERING) {
            val now = SystemClock.elapsedRealtime()
            val since = stage.stalledSince ?: now.also { stage.stalledSince = it }
            if (now - since >= 450) {
                hold()
                load(stage, audio.currentPosition)
                return
            }
        } else stage.stalledSince = null
        if (heldIntent == null && video.playbackState == Player.STATE_READY && pictureReady && abs(video.currentPosition - audio.currentPosition) > 250) {
            video.seekTo(audio.currentPosition)
        }
        val playVideo = heldIntent == null && audio.isPlaying
        if (video.playWhenReady != playVideo) video.playWhenReady = playVideo
        val loading = !pictureReady || heldIntent != null
        if (mutable.value?.loading != loading) mutable.value = VideoFrameState(stage.track.id, video, loading)
    }
}
