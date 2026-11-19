package com.xtreeroot.mb.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.xtreeroot.mb.music.StationPlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.json.JSONObject

internal data class PlaybackStation(val sourceId: String, val id: String, val title: String) {
    fun encode() = JSONObject().put("sourceId", sourceId).put("id", id).put("title", title)

    companion object {
        fun decode(value: JSONObject): PlaybackStation? {
            val source = value.optString("sourceId").takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
            val id = value.optString("id").takeIf { it.isNotBlank() && it.length <= 1024 } ?: return null
            return PlaybackStation(source, id, value.optString("title").take(1024))
        }
    }
}

internal enum class StationFailure { Empty, Load }
internal data class StationStatus(val station: PlaybackStation? = null, val failure: StationFailure? = null)

@OptIn(UnstableApi::class)
internal class StationPlayback(private val player: Player, private val scope: CoroutineScope,
                               private val sourceFor: (String) -> StationPlaybackSource?,
                               private val publish: (StationStatus) -> Unit) : Player.Listener {
    var station: PlaybackStation? = null
        private set
    private var job: Job? = null
    private var advanceFrom: MediaItem? = null
    private var shouldPlay = false
    private var failure: StationFailure? = null

    init { player.addListener(this) }

    fun set(station: PlaybackStation?, prefetch: Boolean = false) {
        job?.cancel()
        job = null
        advanceFrom = null
        shouldPlay = false
        failure = null
        this.station = station
        publish(StationStatus(station))
        if (prefetch) refill()
    }

    fun next(): Boolean {
        if (station == null || !atTail()) return false
        seekAfterAppend(play = true)
        return true
    }

    fun updateIntent(play: Boolean) { if (advanceFrom != null) shouldPlay = play }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) updateIntent(false)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        val selected = station ?: return
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            if (player.currentMediaItem?.track()?.sourceId != selected.sourceId) { set(null); return }
            if (advanceFrom !== player.currentMediaItem) advanceFrom = null
        }
        if (!player.playWhenReady || failure != null) return
        if (player.playbackState == Player.STATE_ENDED && events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && atTail()) {
            seekAfterAppend(play = true)
        } else if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
            (player.playbackState == Player.STATE_READY && events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED))) refill()
    }

    private fun atTail() = player.mediaItemCount > 0 && player.currentMediaItemIndex == player.mediaItemCount - 1

    private fun seekAfterAppend(play: Boolean) {
        advanceFrom = player.currentMediaItem
        shouldPlay = play
        refill(force = true)
    }

    private fun refill(force: Boolean = false) {
        val selected = station ?: return
        if (job != null || (!force && (failure != null || player.mediaItemCount - player.currentMediaItemIndex > 2))) return
        val source = sourceFor(selected.sourceId)
        if (source == null) {
            if (force) finish(StationFailure.Load)
            return
        }
        if (failure != null) { failure = null; publish(StationStatus(selected)) }
        job = scope.launch {
            yield()
            try {
                repeat(3) {
                    val tracks = source.stationNext(selected.id).filter { !it.isUnavailable && it.sourceId == selected.sourceId }
                    ensureActive()
                    if (sourceFor(selected.sourceId) !== source) { finish(StationFailure.Load); return@launch }
                    if (tracks.isNotEmpty()) {
                        val advance = advanceFrom != null && advanceFrom === player.currentMediaItem && atTail()
                        val resume = shouldPlay
                        val first = player.mediaItemCount
                        player.addMediaItems(tracks.map { it.mediaItem() })
                        if (advance) {
                            player.seekToDefaultPosition(first)
                            if (player.playbackState == Player.STATE_IDLE) player.prepare()
                            player.playWhenReady = resume
                        }
                        finish(null)
                        return@launch
                    }
                }
                finish(StationFailure.Empty)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureActive()
                finish(StationFailure.Load)
            }
        }
    }

    private fun finish(failure: StationFailure?) {
        advanceFrom = null
        job = null
        this.failure = failure
        publish(StationStatus(station, failure))
    }
}
