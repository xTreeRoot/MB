package com.xtreeroot.mb.playback

internal fun shuffledInsertion(order: List<Int>, insertion: Int, after: Int?): IntArray = buildList {
    for (index in order) {
        add(if (index >= insertion) index + 1 else index)
        if (index == after) add(insertion)
    }
    if (after !in order) add(insertion)
}.toIntArray()
