package com.xtreeroot.mb.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ShuffledInsertionTest {
    @Test fun playsInsertedTrackNextWithoutReshufflingTheOthers() {
        assertArrayEquals(intArrayOf(3, 0, 1, 4, 2), shuffledInsertion(listOf(2, 0, 3, 1), 1, 0))
    }

    @Test fun appendsToTheEndOfThePlayOrderAndHandlesAnEmptyQueue() {
        assertArrayEquals(intArrayOf(2, 0, 1, 3), shuffledInsertion(listOf(2, 0, 1), 3, null))
        assertArrayEquals(intArrayOf(0), shuffledInsertion(emptyList(), 0, null))
    }
}
