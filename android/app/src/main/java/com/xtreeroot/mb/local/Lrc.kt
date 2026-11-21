package com.xtreeroot.mb.local

import com.xtreeroot.mb.music.CaptionLine
import kotlin.math.abs

private val timestamp = Regex("\\[(\\d+):([0-5]\\d)(?:[.:](\\d{1,3}))?]")
private val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)

internal fun parseLrc(text: String, translated: String, durationMs: Long = 0): List<CaptionLine> {
    val translations = parseLrc(translated, durationMs)
    return parseLrc(text, durationMs).map { line ->
        val index = translations.binarySearchBy(line.startMs, selector = CaptionLine::startMs)
        val insertion = if (index < 0) -(index + 1) else index
        val nearest = listOfNotNull(translations.getOrNull(insertion - 1), translations.getOrNull(insertion))
            .minByOrNull { abs(it.startMs - line.startMs) }
        line.copy(translation = nearest?.takeIf { abs(it.startMs - line.startMs) <= 1_500 && it.text != line.text }?.text)
    }
}

internal fun parseLrc(text: String, durationMs: Long = 0): List<CaptionLine> {
    val shift = offset.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
    val points = text.lineSequence().flatMap { line ->
        val stamps = timestamp.findAll(line).toList()
        val content = stamps.lastOrNull()?.let { line.substring(it.range.last + 1).trim() }.orEmpty()
        stamps.asSequence().mapNotNull { match ->
            val minutes = match.groupValues[1].toLongOrNull()?.takeIf { it < Long.MAX_VALUE / 120_000 }
                ?: return@mapNotNull null
            val seconds = match.groupValues[2].toInt()
            val millis = match.groupValues[3].padEnd(3, '0').toInt()
            val time = minutes * 60_000 + seconds * 1_000 + millis
            if (shift > Long.MAX_VALUE - time) return@mapNotNull null
            val start = (time + shift).coerceAtLeast(0)
            if (durationMs > 0 && start >= durationMs) null else start to content
        }
    }.groupBy({ it.first }, { it.second }).toSortedMap().entries.toList()
    return points.mapIndexedNotNull { index, point ->
        val content = point.value.filter(String::isNotEmpty).distinct().joinToString("\n")
        if (content.isEmpty()) return@mapIndexedNotNull null
        val end = points.getOrNull(index + 1)?.key ?: if (durationMs > 0) durationMs else point.key.coerceAtMost(Long.MAX_VALUE - 6_000) + 6_000
        CaptionLine(point.key, end, content)
    }
}
