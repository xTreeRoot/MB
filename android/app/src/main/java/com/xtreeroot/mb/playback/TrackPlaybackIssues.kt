package com.xtreeroot.mb.playback

import androidx.media3.common.PlaybackException
import com.xtreeroot.mb.music.PlaybackIssue
import com.xtreeroot.mb.music.PlaybackIssueException
import com.xtreeroot.mb.music.Track
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class PlaybackTrackKey(val sourceId: String, val trackId: String)

internal val Track.playbackKey: PlaybackTrackKey get() = PlaybackTrackKey(sourceId, sourceTrackId)

internal data class TrackPlaybackFailure(val issue: PlaybackIssue, val code: Int?, val diagnostic: String?)

internal class TrackPlaybackIssues {
    private val mutable = MutableStateFlow(emptyMap<PlaybackTrackKey, TrackPlaybackFailure>())
    val values = mutable.asStateFlow()

    fun failed(track: Track, issue: PlaybackIssue, error: PlaybackException? = null) = mutable.update { issues ->
        val failure = TrackPlaybackFailure(issue, error?.errorCode, error?.playbackDiagnostic(error.errorCode))
        val updated = (issues - track.playbackKey) + (track.playbackKey to failure)
        if (updated.size <= 256) updated else updated - updated.keys.first()
    }

    fun playing(track: Track) = mutable.update { it - track.playbackKey }
}

internal fun classifyPlaybackIssue(error: Throwable, code: Int): PlaybackIssue {
    var failure: Throwable? = error
    var network = code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
    repeat(16) {
        when (val current = failure ?: return if (network) PlaybackIssue.Network else PlaybackIssue.Unknown) {
            is PlaybackIssueException -> return current.issue
            is MediaFetchFailure -> return if (current.recovery == MediaRecovery.Reopen) PlaybackIssue.Network else PlaybackIssue.Unknown
            is UnknownHostException, is SocketTimeoutException, is NoRouteToHostException, is ConnectException -> network = true
        }
        failure = failure?.cause
    }
    return if (network) PlaybackIssue.Network else PlaybackIssue.Unknown
}
