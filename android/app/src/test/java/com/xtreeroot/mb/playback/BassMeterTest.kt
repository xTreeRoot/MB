package com.xtreeroot.mb.playback

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.xtreeroot.mb.preferences.AudioNormalization
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BassMeterTest {
    @OptIn(UnstableApi::class)
    @Test fun emptyPipelineSentinelIsIgnoredAndPassThroughDoesNotChangeAudioBytes() {
        val processor = AudioSignalProcessor({ AudioNormalization.Off }, { 4.0 }, { false }, {})
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        val bytes = byteArrayOf(7, 3, 19, 31)
        processor.queueInput(ByteBuffer.wrap(bytes))
        assertArrayEquals(bytes, ByteArray(bytes.size).also { processor.output.get(it) })
    }

    @Test fun respondsToBassRatherThanEqualAmplitudeTrebleAndReturnsToSilence() {
        val rate = 48_000
        fun meterFor(frequency: Int) = BassMeter(rate).apply {
            repeat(rate) { frame -> sample(0.1 * sin(2 * PI * frequency * frame / rate)) }
        }
        val bass = meterFor(70)
        assertTrue(bass.level() > 0.45f)
        assertTrue(meterFor(2_000).level() < 0.07f)
        repeat(rate * 2) { bass.sample(0.0) }
        assertEquals(0f, bass.level(), 0.001f)
    }
}
