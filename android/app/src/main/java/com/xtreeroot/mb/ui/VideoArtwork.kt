package com.xtreeroot.mb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.music.Track

@Composable
internal fun VideoArtwork(track: Track, mark: TrackPlaybackMark?, modifier: Modifier) {
    Box(modifier.aspectRatio(16f / 9f)) {
        TrackRowArtwork(track, mark, Modifier.matchParentSize())
        if (track.durationMs > 0) Text(
            timeLabel(track.durationMs), Modifier.align(Alignment.BottomEnd).padding(4.dp)
                .background(Color.Black.copy(alpha = 0.76f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            color = Color.White, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun VideoCoverCard(track: Track, play: () -> Unit) {
    val mark = LocalMarkedTrack.current?.takeIf { it.id == track.id }?.playback
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp).clip(RoundedCornerShape(8.dp))
        .trackRowInput(track, enabled = true, mark, highlighted = mark != null, play = play),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            VideoArtwork(track, mark, Modifier.fillMaxWidth())
            TrackActionsButton(track, Modifier.align(Alignment.TopEnd), onArtwork = true)
        }
        Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium, color = if (mark == null) colors.onSurface else colors.primary)
        ProvideTextStyle(MaterialTheme.typography.bodySmall.copy(color = colors.onSurfaceVariant)) {
            TrackSubtitle(track)
        }
    }
}
