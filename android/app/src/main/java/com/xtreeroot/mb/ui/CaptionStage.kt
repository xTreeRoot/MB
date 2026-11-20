package com.xtreeroot.mb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.music.CaptionLine
import com.xtreeroot.mb.music.CaptionWord
import com.xtreeroot.mb.music.activeCaption
import com.xtreeroot.mb.music.timedWords
import com.xtreeroot.mb.preferences.LyricStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun CaptionStage(lines: List<CaptionLine>, position: Long, style: LyricStyle, sparks: Boolean,
                          animate: Boolean, seek: (Long) -> Unit) {
    val index = lines.activeCaption(position)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = index.coerceAtLeast(0))
    LaunchedEffect(index, animate) {
        if (index < 0) return@LaunchedEffect
        if (animate) list.animateScrollToItem(index) else list.scrollToItem(index)
    }
    BoxWithConstraints(Modifier.fillMaxSize().semantics { testTag = "lyric-style-${style.name.lowercase()}" }) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = (maxHeight / 2 - 26.dp).coerceAtLeast(12.dp))) {
            itemsIndexed(lines, key = { lineIndex, line -> "${line.startMs}:$lineIndex" }) { lineIndex, line ->
                val active = lineIndex == index
                val words = remember(line) { line.timedWords() }
                Column(Modifier.fillMaxWidth().clickable(onClickLabel = mb("跳转到 ${line.text}", "Seek to ${line.text}")) {
                    seek(line.startMs)
                }.semantics {
                    selected = active
                    contentDescription = listOfNotNull(line.text, line.translation).joinToString("\n")
                }.padding(vertical = 10.dp)) {
                    WordLayout(style) {
                        words.forEachIndexed { wordIndex, word ->
                            LyricWord(word, if (active) position else -1, style, wordIndex, animate, sparks && animate && active)
                        }
                    }
                    line.translation?.takeIf(String::isNotBlank)?.let { translation ->
                        Text(translation, Modifier.padding(top = 4.dp).clearAndSetSemantics { }, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun WordLayout(style: LyricStyle, words: @Composable () -> Unit) {
    Layout(content = words, modifier = Modifier.clearAndSetSemantics { }) { measurables, constraints ->
        val gap = (if (style == LyricStyle.Flow) 1.dp else 3.dp).roundToPx()
        val lineGap = (if (style == LyricStyle.Flow) 5.dp else 10.dp).roundToPx()
        val places = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        val offsets = mutableListOf<Pair<Int, Int>>()
        var left = 0
        var top = 0
        var height = 0
        var row = 0
        places.forEach { place ->
            if (left > 0 && left + place.width > constraints.maxWidth) {
                row++
                top += height + lineGap
                height = 0
                left = if (style == LyricStyle.Steps) (12.dp * (row % 3)).roundToPx()
                    .coerceAtMost(constraints.maxWidth - place.width) else 0
            }
            offsets.add(left to top)
            left += place.width + gap
            height = maxOf(height, place.height)
        }
        layout(constraints.maxWidth, (top + height).coerceIn(constraints.minHeight, constraints.maxHeight)) {
            places.forEachIndexed { index, place -> place.placeRelative(offsets[index].first, offsets[index].second) }
        }
    }
}

@Composable
private fun LyricWord(word: CaptionWord, position: Long, style: LyricStyle, index: Int, animate: Boolean, sparks: Boolean) {
    val typography = if (style == LyricStyle.Tilt) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall
    val weight = if (style == LyricStyle.Tilt && index % 2 == 0) FontWeight.Black else FontWeight.SemiBold
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (position >= 0) 0.92f else 0.62f)
    val progress = word.progress(position)
    val age = (position - word.startMs) / 600f
    TimedCaptionWord(word.text, progress, typography, weight, dim,
        Modifier.graphicsLayer { rotationZ = if (style == LyricStyle.Tilt && animate && index % 3 == 0) -4f else 0f }
        .drawWithContent {
            drawContent()
            if (sparks && age >= 0f && age < 1f) {
                repeat(6) { particle ->
                    val angle = particle * PI / 3
                    drawCircle(accent.copy(alpha = 1f - age), 1.25.dp.toPx(), Offset(
                        size.width * progress + cos(angle).toFloat() * age * 24.dp.toPx(),
                        size.height / 2 + (sin(angle).toFloat() * 22 - 15) * age * 1.dp.toPx()))
                }
            }
        })
}

@Composable
internal fun TimedCaptionWord(text: String, progress: Float, typography: TextStyle, weight: FontWeight,
                              dim: Color, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier) {
        Text(text, color = dim, style = typography, fontWeight = weight)
        if (progress > 0f) Text(text, Modifier.matchParentSize().clearAndSetSemantics { }.drawWithContent {
            if (layoutDirection == LayoutDirection.Ltr) {
                clipRect(right = size.width * progress) { this@drawWithContent.drawContent() }
            } else clipRect(left = size.width * (1f - progress)) { this@drawWithContent.drawContent() }
        }, color = accent, style = typography, fontWeight = weight)
    }
}
