package com.xtreeroot.mb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.R
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.playback.PlaybackModel
import com.xtreeroot.mb.playback.PlaybackState
import kotlin.math.abs

private val handleSize = 48.dp
private val rowShape = RoundedCornerShape(12.dp)

private data class QueueDrag(val index: Int, val size: Int, val finger: Float, val grabOffset: Float) {
    val center get() = finger + grabOffset
}

private val LazyListItemInfo.center get() = offset + size / 2f

private fun LazyListState.destination(drag: QueueDrag): Int =
    layoutInfo.visibleItemsInfo.minByOrNull { abs(it.center - drag.center) }?.index ?: drag.index

@Composable
internal fun QueueTracks(model: PlaybackModel, state: PlaybackState, modifier: Modifier = Modifier, onSelect: () -> Unit = {}) {
    val tracks = state.tracks
    if (tracks.isEmpty()) return EmptyMessage(mb("队列为空", "Your queue is empty"))
    val list = rememberLazyListState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val edge = with(LocalDensity.current) { handleSize.toPx() }
    var drag by remember { mutableStateOf<QueueDrag?>(null) }
    val dragging = drag != null
    LaunchedEffect(dragging, list, edge) {
        if (!dragging) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val finger = drag?.finger ?: break
            val height = list.layoutInfo.viewportSize.height
            val zone = minOf(edge, height / 3f)
            if (zone > 0) {
                val direction = when {
                    finger < zone -> (finger - zone) / zone
                    finger > height - zone -> (finger - height + zone) / zone
                    else -> 0f
                }.coerceIn(-1f, 1f)
                if (direction != 0f) {
                    val elapsed = ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
                    list.scrollBy(direction * edge * 12 * elapsed)
                }
            }
            previous = now
        }
    }
    Box(modifier.fillMaxWidth().padding(horizontal = 8.dp).clipToBounds()) {
        LazyColumn(Modifier.fillMaxWidth().pointerInput(tracks, state.connected, rtl) {
            if (!state.connected || tracks.size < 2) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val distanceFromEnd = if (rtl) down.position.x else size.width - down.position.x
                if (distanceFromEnd !in 0f..handleSize.toPx()) return@awaitEachGesture
                val item = list.layoutInfo.visibleItemsInfo.firstOrNull { abs(it.center - down.position.y) <= handleSize.toPx() / 2 }
                    ?: return@awaitEachGesture
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (pointer.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                        if (!pointer.pressed) {
                            drag?.let { active ->
                                pointer.consume()
                                model.move(active.index, list.destination(active), tracks)
                            }
                            break
                        }
                        val delta = pointer.position - down.position
                        if (drag == null && abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) >= abs(delta.y)) break
                        if (drag != null || abs(delta.y) > viewConfiguration.touchSlop) {
                            pointer.consume()
                            drag = QueueDrag(item.index, item.size, pointer.position.y, item.center - down.position.y)
                        }
                    }
                } finally { drag = null }
            }
        }, state = list, contentPadding = PaddingValues(bottom = 14.dp)) {
            itemsIndexed(tracks, key = { index, track -> "$index:${track.id}" }) { index, track ->
                val current = index == state.index
                var menu by remember { mutableStateOf(false) }
                val active = drag
                val hidden = active?.index == index
                val row = Modifier.graphicsLayer {
                    if (hidden) alpha = 0f
                    else if (active != null) {
                        val target = list.destination(active)
                        translationY = when {
                            index > active.index && index <= target -> -active.size.toFloat()
                            index < active.index && index >= target -> active.size.toFloat()
                            else -> 0f
                        }
                    }
                }
                QueueTrackRow(track, current, lifted = false, modifier = if (hidden) row.clearAndSetSemantics {} else row,
                    contentModifier = Modifier.selectable(current, enabled = state.connected && !dragging, role = Role.Button,
                        onClick = { model.select(index); onSelect() })) {
                    Box {
                        IconButton(onClick = { menu = true }, enabled = state.connected && !dragging) {
                            QueueHandle(mb("编辑 ${track.title}", "Edit ${track.title}"))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(mb("上移", "Move Up")) }, enabled = index > 0,
                                onClick = { menu = false; model.move(index, index - 1, tracks) })
                            DropdownMenuItem(text = { Text(mb("下移", "Move Down")) }, enabled = index < tracks.lastIndex,
                                onClick = { menu = false; model.move(index, index + 1, tracks) })
                            DropdownMenuItem(text = { Text(mb("从队列移除", "Remove from Queue")) },
                                onClick = { menu = false; model.remove(index, tracks) })
                        }
                    }
                }
            }
        }
        drag?.let { active ->
            val track = tracks.getOrNull(active.index) ?: return@let
            QueueTrackRow(track, active.index == state.index, lifted = true,
                modifier = Modifier.graphicsLayer { translationY = active.center - active.size / 2f }
                    .clearAndSetSemantics {},
                handle = { Box(Modifier.size(handleSize), contentAlignment = Alignment.Center) { QueueHandle(null) } })
        }
    }
}

@Composable
private fun QueueTrackRow(track: Track, current: Boolean, lifted: Boolean, modifier: Modifier,
                          contentModifier: Modifier = Modifier, handle: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        lifted -> colors.surfaceContainerHighest
        current -> colors.primary.copy(alpha = 0.11f)
        else -> Color.Transparent
    }
    Row(modifier.fillMaxWidth().padding(vertical = 1.dp)
        .clip(rowShape).background(background), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).then(contentModifier).padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track, Modifier.size(40.dp))
            Column {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (current) colors.primary else colors.onSurface)
                ProvideTextStyle(MaterialTheme.typography.bodySmall.copy(color = colors.onSurfaceVariant)) {
                    TrackSubtitle(track)
                }
            }
        }
        handle()
    }
}

@Composable
private fun QueueHandle(description: String?) {
    Icon(painterResource(R.drawable.ic_queue_reorder), description, Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant)
}
