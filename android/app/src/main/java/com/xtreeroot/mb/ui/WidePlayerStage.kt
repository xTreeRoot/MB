package com.xtreeroot.mb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.music.InteractionSource
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.playback.PlaybackModel
import com.xtreeroot.mb.playback.PlaybackState

internal enum class WidePlayerPage {
    Lyrics, Rhythm, Queue, Songs, Comments;

    @Composable fun label(): String = when (this) {
        Lyrics -> mb("歌词", "Lyrics")
        Rhythm -> mb("律动", "Rhythm")
        Queue -> mb("待播清单", "Up Next")
        Songs -> mb("选歌", "Songs")
        Comments -> mb("评论", "Comments")
    }
}

@Composable
internal fun WidePlayerStage(track: Track, state: PlaybackState, model: PlaybackModel, page: WidePlayerPage,
                             selectPage: (WidePlayerPage) -> Unit, interaction: InteractionSource?, signedIn: Boolean,
                             captions: @Composable (rhythm: Boolean) -> Unit, modifier: Modifier) {
    Column(modifier) {
        val tabs = WidePlayerPage.entries.associate { it.name to it.label() }
        MusicTabs(tabs, page.name) { selectPage(WidePlayerPage.valueOf(it)) }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (page) {
                WidePlayerPage.Lyrics, WidePlayerPage.Rhythm -> Box(Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center) { captions(page == WidePlayerPage.Rhythm) }
                WidePlayerPage.Queue -> QueueTracks(model, state, Modifier.fillMaxSize())
                WidePlayerPage.Songs -> WideSongGrid(state, model::select)
                WidePlayerPage.Comments -> if (interaction == null) {
                    EmptyMessage(mb("此音源不支持评论", "Comments are not available for this source"))
                } else TrackComments(interaction, track, signedIn, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun WideSongGrid(state: PlaybackState, select: (Int) -> Unit) {
    LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(state.tracks, key = { index, track -> "$index:${track.id}" }) { index, track ->
            val active = index == state.index
            val colors = MaterialTheme.colorScheme
            Column(Modifier.selectable(active, enabled = state.connected && !track.isUnavailable,
                role = Role.Button, onClick = { select(index) }), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    Artwork(track, Modifier.fillMaxSize())
                    if (active) Icon(Icons.Default.Check, contentDescription = null, Modifier.align(Alignment.BottomEnd)
                        .padding(5.dp).size(23.dp).background(colors.primary, CircleShape).padding(4.dp), tint = colors.onPrimary)
                }
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium)
                TrackSubtitle(track)
            }
        }
    }
}
