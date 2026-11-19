package com.xtreeroot.mb.music

internal fun List<CaptionLine>.activeCaption(positionMs: Long): Int = indexOfLast { positionMs >= it.startMs && positionMs < it.endMs }

internal fun CaptionLine.timedWords(): List<CaptionWord> {
    if (words.isNotEmpty()) return words
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    fun flush() {
        if (current.isNotEmpty()) {
            parts.add(current.toString())
            current.clear()
        }
    }
    text.codePoints().forEachOrdered { code ->
        when {
            Character.isWhitespace(code) || Character.isSpaceChar(code) -> {
                if (current.isNotEmpty()) { current.append(' '); flush() }
            }
            code in 0x3400..0x9FFF || code in 0x3040..0x30FF || code in 0xAC00..0xD7AF || code in 0x20000..0x323AF -> {
                flush(); parts.add(String(Character.toChars(code)))
            }
            else -> current.appendCodePoint(code)
        }
    }
    flush()
    val weights = parts.map { part -> part.trim().let { it.codePointCount(0, it.length).coerceAtLeast(1) } }
    val total = weights.sum().coerceAtLeast(1)
    val span = (endMs - startMs).coerceAtLeast(0) * 0.85
    var elapsed = 0
    return parts.mapIndexed { index, part ->
        val from = startMs + (span * elapsed / total).toLong()
        elapsed += weights[index]
        CaptionWord(from, startMs + (span * elapsed / total).toLong(), part)
    }
}

internal fun quietCommentAt(positionMs: Long, lines: List<CaptionLine>, durationMs: Long, comments: List<TrackComment>): TrackComment? {
    val candidates = comments.filter { it.text.isNotBlank() && it.text.codePointCount(0, it.text.length) <= 140 }
        .sortedByDescending(TrackComment::likes)
    if (candidates.isEmpty()) return null
    var end = 0L
    val intervals = buildList {
        for (line in lines) {
            if (isCaptionCredit(line.text)) continue
            if (line.startMs - end >= 8_000) add(end to line.startMs)
            end = maxOf(end, line.endMs)
        }
        if (durationMs - end >= 8_000) add(end to durationMs)
    }
    var shown = 0L
    for ((start, finish) in intervals) {
        val from = start + 1_200
        val to = finish - 1_000
        val slots = ((to - from + 5_000) / 10_000).coerceAtLeast(1)
        val each = ((to - from) / slots).coerceAtLeast(1)
        if (positionMs in from until to) {
            val slot = minOf(slots - 1, (positionMs - from) / each)
            return candidates[((shown + slot) % candidates.size).toInt()]
        }
        shown = (shown + slots) % candidates.size
    }
    return null
}

private val creditPrefixes = listOf("作词", "作曲", "编曲", "制作", "监制", "混音", "录音", "母带", "和声", "吉他", "贝斯", "鼓",
    "键盘", "弦乐", "出品", "发行", "OP", "SP", "词", "曲", "Lyrics", "Music", "Composer", "Arranger", "Producer")

private fun isCaptionCredit(text: String): Boolean {
    val separator = text.indexOfFirst { it == ':' || it == '：' }
    if (separator < 0) return false
    val head = text.substring(0, separator).trim()
    return head.length <= 12 && creditPrefixes.any { head == it || it.length > 1 && head.startsWith(it) }
}
