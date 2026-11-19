package com.xtreeroot.mb.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionTimingTest {
    @Test fun suppliedWordIntervalsWinAndCaptionStopsBeforeGap() {
        val explicit = listOf(CaptionWord(2_500, 2_700, "晚"))
        val lines = listOf(CaptionLine(2_000, 3_000, "晚", words = explicit), CaptionLine(5_000, 9_000, "归"))
        assertEquals(explicit, lines.first().timedWords())
        assertEquals(0.5f, explicit.first().progress(2_600), 0.01f)
        assertEquals(0, lines.activeCaption(2_000))
        assertEquals(-1, lines.activeCaption(3_000))
        assertEquals(1, lines.activeCaption(5_000))
        assertEquals(-1, lines.activeCaption(9_000))
    }

    @Test fun generatedWordsUseCjkScalarsLatinLengthsAndEightyFivePercentOfLine() {
        val words = CaptionLine(1_000, 5_000, "𠀀晚 blue sky").timedWords()
        assertEquals(listOf("𠀀", "晚", "blue ", "sky"), words.map { it.text })
        assertEquals(1_000L, words.first().startMs)
        assertEquals(4_400L, words.last().endMs)
        assertEquals(words[1].endMs, words[2].startMs)
        assertEquals(4f, (words[2].endMs - words[2].startMs).toFloat() / (words[0].endMs - words[0].startMs), 0.02f)
    }

    @Test fun instrumentalCommentsRespectLyricsCreditsRankAndPadding() {
        val lyrics = listOf(CaptionLine(0, 4_000, "作曲：某人"), CaptionLine(24_000, 27_000, "归"), CaptionLine(31_000, 34_000, "来"))
        val best = TrackComment("best", "A", null, "第一条", 19)
        val next = TrackComment("next", "B", null, "第二条", 11)
        val candidates = listOf(next, best, next.copy(text = "x".repeat(141), likes = 999))
        assertNull(quietCommentAt(1_199, lyrics, 42_000, candidates))
        assertEquals(best, quietCommentAt(1_200, lyrics, 42_000, candidates))
        assertEquals(next, quietCommentAt(15_000, lyrics, 42_000, candidates))
        assertNull(quietCommentAt(23_000, lyrics, 42_000, candidates))
        assertNull(quietCommentAt(29_000, lyrics, 42_000, candidates))
        assertEquals(best, quietCommentAt(36_000, lyrics, 42_000, candidates))
    }
}
