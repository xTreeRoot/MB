package com.xtreeroot.mb.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
internal class CaptionSessionPlayer(private val audio: ExoPlayer, private val video: VideoPlayback, private val stations: StationPlayback? = null,
                                    private val retryPlayback: () -> Unit) :
    ForwardingSimpleBasePlayer(audio) {
    private var caption: Pair<String, String>? = null

    init { video.onGateChanged = { invalidateState() } }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        stations?.updateIntent(playWhenReady)
        return if (video.changeIntent(playWhenReady)) Futures.immediateVoidFuture()
        else super.handleSetPlayWhenReady(playWhenReady)
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        if (stations?.station != null) stations.set(null)
        val selected = mediaItems.getOrNull(startIndex)?.station()
        val clean = if (selected == null) mediaItems else mediaItems.mapIndexed { index, item ->
            if (index == startIndex) item.buildUpon().setRequestMetadata(MediaItem.RequestMetadata.EMPTY).build() else item
        }
        val result = super.handleSetMediaItems(clean, startIndex, startPositionMs)
        if (audio.shuffleModeEnabled) shuffleFromSelection()
        if (selected != null) stations?.set(selected, prefetch = true)
        return result
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffleModeEnabled && !audio.shuffleModeEnabled) shuffleFromSelection()
        return super.handleSetShuffleModeEnabled(shuffleModeEnabled)
    }

    private fun shuffleFromSelection() {
        val indices = 0 until audio.mediaItemCount
        val selected = audio.currentMediaItemIndex
        if (selected !in indices) return
        val order = listOf(selected) + indices.filterNot { it == selected }.shuffled()
        audio.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if ((seekCommand == Player.COMMAND_SEEK_TO_NEXT || seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) &&
            stations?.next() == true) return Futures.immediateVoidFuture()
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (player.playerError != null) retryPlayback()
        return super.handlePrepare()
    }

    fun showCaption(trackId: String?, text: String?) {
        val next = if (trackId != null && !text.isNullOrBlank()) trackId to text else null
        if (caption != next) {
            caption = next
            invalidateState()
        }
    }

    override fun getState(): State {
        val original = super.getState()
        val builder = original.buildUpon()
        video.heldIntent?.let { intent ->
            builder.setPlayWhenReady(intent, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            if (player.playbackState == Player.STATE_READY) builder.setPlaybackState(Player.STATE_BUFFERING)
        }
        caption?.takeIf { it.first == player.currentMediaItem?.mediaId }?.second?.let { line ->
            builder.setPlaylist(player.currentTimeline, player.currentTracks, player.mediaMetadata.buildUpon().setArtist(line).build())
        }
        return builder.build()
    }
}
