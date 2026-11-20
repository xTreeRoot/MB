package com.xtreeroot.mb.playback

import androidx.media3.common.C
import androidx.media3.common.Player

internal fun Player.shuffledQueueOrder(): List<Int>? {
    if (!shuffleModeEnabled) return null
    val timeline = currentTimeline
    return generateSequence(timeline.getFirstWindowIndex(true)) { index ->
        timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
    }.takeWhile { it != C.INDEX_UNSET }.toList()
}

internal fun shuffledInsertion(order: List<Int>, insertion: Int, after: Int?): IntArray = buildList {
    for (index in order) {
        add(if (index >= insertion) index + 1 else index)
        if (index == after) add(insertion)
    }
    if (after !in order) add(insertion)
}.toIntArray()
