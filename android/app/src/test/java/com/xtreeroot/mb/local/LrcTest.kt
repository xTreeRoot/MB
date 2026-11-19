package com.xtreeroot.mb.local

import com.xtreeroot.mb.music.CaptionLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcTest {
    @Test fun pairsOnlyTheNearestTranslationInsideTolerance() {
        val lines = parseLrc("[00:04]归来\n[00:08]夜晚\n[00:14]归来",
            "[00:03]Earlier\n[00:04.4]Nearest\n[00:09.5]Night\n[00:15.6]Too late", 20_000)
        assertEquals(listOf("Nearest", "Night", null), lines.map { it.translation })
    }
    @Test
    fun blankTimestampEndsPriorLineAndMultipleTagsReuseText() {
        val text = """[offset:-100]
            |[00:00.05][00:05.2]叠句
            |[00:02]
            |[00:05.200]翻译
            |[00:05.20]叠句
            |[00:11.333]过长
        """.trimMargin()
        assertEquals(listOf(CaptionLine(0, 1900, "叠句"), CaptionLine(5100, 10_000, "叠句\n翻译")),
            parseLrc(text, 10_000))
    }

    @Test
    fun invalidTimestampsAreSkippedAndUnknownDurationDoesNotHoldForever() {
        assertTrue(parseLrc("[00:60.0]错误\n[ar:Artist]").isEmpty())
        assertEquals(listOf(CaptionLine(1234, 7234, "一句")), parseLrc("[00:01.234]一句"))
    }
}
