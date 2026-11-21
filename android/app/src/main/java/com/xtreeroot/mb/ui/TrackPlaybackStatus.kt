package com.xtreeroot.mb.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.style.TextOverflow
import com.xtreeroot.mb.music.PlaybackIssue
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.playback.TrackPlaybackFailure
import com.xtreeroot.mb.playback.PlaybackTrackKey
import com.xtreeroot.mb.playback.playbackKey

internal val LocalTrackPlaybackIssues = compositionLocalOf<Map<PlaybackTrackKey, TrackPlaybackFailure>> { emptyMap() }

@Composable
internal fun TrackSubtitle(track: Track) {
    Column {
        Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TrackPlaybackStatus(track)
    }
}

@Composable
internal fun TrackPlaybackStatus(track: Track) {
    val issue = track.unavailableReason?.issue ?: LocalTrackPlaybackIssues.current[track.playbackKey]?.issue
        ?: PlaybackIssue.Unavailable.takeIf { track.unavailable }
    val text = when (issue) {
        PlaybackIssue.Vip -> mb("需要 VIP", "VIP required")
        PlaybackIssue.SourceUnavailable -> mb("音源不可用", "Audio source unavailable")
        PlaybackIssue.Unavailable -> mb("暂不可播放", "Temporarily unavailable")
        PlaybackIssue.SignIn -> mb("需要登录", "Sign-in required")
        PlaybackIssue.Network -> mb("网络异常，重试", "Network error, try again")
        PlaybackIssue.Unknown -> mb("播放失败，原因未知", "Playback failed for an unknown reason")
        null -> return
    }
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
