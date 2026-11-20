package com.xtreeroot.mb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.music.CaptionLine
import com.xtreeroot.mb.music.activeCaption
import com.xtreeroot.mb.music.timedWords
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun RhythmCaptionStage(lines: List<CaptionLine>, positionMs: Long, playing: Boolean,
                                animate: Boolean, seek: (Long) -> Unit) {
    if (lines.isEmpty()) return
    val index = lines.activeCaption(positionMs).takeIf { it >= 0 }
        ?: lines.indexOfFirst { it.startMs > positionMs }.takeIf { it >= 0 } ?: lines.lastIndex
    val line = lines[index]
    val words = remember(line) { line.timedWords() }
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, animate) {
        if (!animate) phase.floatValue = 0f
        if (!playing || !animate) return@LaunchedEffect
        val startPhase = phase.floatValue
        val start = withFrameNanos { it }
        var drawnAt = start
        while (true) withFrameNanos { now ->
            if (now - drawnAt >= 30_000_000L) {
                phase.floatValue = ((startPhase + (now - start) * 2.4e-9) % (2 * PI)).toFloat()
                drawnAt = now
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            key(index, line.startMs) {
                FlowRow(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp).clearAndSetSemantics { contentDescription = line.text },
                    horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    words.forEachIndexed { wordIndex, word ->
                        TimedCaptionWord(word.text, word.progress(positionMs), MaterialTheme.typography.headlineLarge,
                            FontWeight.Bold, colors.onSurfaceVariant, if (!animate) Modifier else Modifier.graphicsLayer {
                                val beat = phase.floatValue
                                translationY = sin(beat + wordIndex * 0.65f) * 4.dp.toPx()
                                rotationZ = sin(beat + wordIndex) * 2.5f
                            })
                    }
                }
            }
        }
        val next = lines.getOrNull(index + 1)
        if (next == null) Spacer(Modifier.height(48.dp))
        else {
            val label = mb("下一句：${next.text}", "Next line: ${next.text}")
            val shape = RoundedCornerShape(24.dp)
            Row(Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(min = 48.dp).clip(shape)
                .background(colors.surfaceContainerHigh).clickable(role = Role.Button) { seek(next.startMs) }
                .semantics { contentDescription = label }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp), tint = colors.primary)
                Text(next.text, Modifier.clearAndSetSemantics { }, color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
