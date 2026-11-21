package com.xtreeroot.mb.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.preferences.AudioNormalization
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class LoudnessDeviceTest {
    @Test fun androidDecoderMeasuresTheEntirePcmTrackIncludingLatePeaks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = File.createTempFile("loudness-fixture-", ".wav", context.cacheDir)
        try {
            audio.writeBytes(wave(latePeak = false))
            assertEquals(2.0, AndroidLoudness.measure(DefaultDataSource.Factory(context), Uri.fromFile(audio)), 0.025)
            audio.writeBytes(wave(latePeak = true))
            assertEquals(0.98 / 0.9, AndroidLoudness.measure(DefaultDataSource.Factory(context), Uri.fromFile(audio)), 0.025)
        } finally { audio.delete() }
    }

    @Test fun randomAccessReadPreservesBytesAcrossWindowsAndNearEnd() {
        val data = ByteArray(390_000) { index -> (index xor (index shr 7)).toByte() }
        val factory = DataSource.Factory { ByteArrayDataSource(data) }
        LoudnessMediaInput(factory, Uri.parse("mb-test://fixture")) {}.use { input ->
            assertEquals(data.size.toLong(), input.size)
            val output = ByteArray(180_000)
            assertEquals(output.size, input.readAt(96_000, output, 0, output.size))
            assertArrayEquals(data.copyOfRange(96_000, 276_000), output)
            assertEquals(13, input.readAt(389_987, output, 21, 24))
            assertArrayEquals(data.copyOfRange(389_987, 390_000), output.copyOfRange(21, 34))
            assertEquals(output.size, input.readAt(3, output, 0, output.size))
            assertArrayEquals(data.copyOfRange(3, 180_003), output)
            assertEquals(-1, input.readAt(data.size.toLong(), output, 0, 1))
        }
    }

    @Test fun pcmSinkAppliesMeasuredGainAndSwitchesModeWithoutReplacingTheTrack() {
        var mode = AudioNormalization.Standard
        val processor = AudioSignalProcessor({ mode }, { 1.6 }, { false }, {})
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val samples = shortArrayOf(5_000, -5_000, 2_000, -2_000)
        fun feed(samples: ShortArray): ByteBuffer {
            val pcm = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
            samples.forEach(pcm::putShort)
            pcm.flip()
            processor.queueInput(pcm)
            return processor.output.order(ByteOrder.nativeOrder())
        }
        assertArrayEquals(shortArrayOf(8_000, -8_000, 3_200, -3_200), feed(samples).shorts(samples.size))
        mode = AudioNormalization.Off
        assertArrayEquals(samples, feed(samples).shorts(samples.size))
        mode = AudioNormalization.Dynamic
        val quiet = ShortArray(2 * 48_000) { 900 }
        repeat(5) { feed(quiet) }
        val after = feed(shortArrayOf(30_000, -30_000)).shorts(2)
        assertTrue(after[0] in 32_105..32_114)
        assertEquals(after[0].toInt(), -after[1].toInt())
    }

    private fun ByteBuffer.shorts(size: Int) = ShortArray(size) { short }

    private fun wave(latePeak: Boolean): ByteArray {
        val rate = 48_000
        val frames = rate * 3
        val buffer = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(frames * 2 + 36).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(frames * 2)
        repeat(frames) { index ->
            val sample = when {
                latePeak && index == frames - 300 -> 29_491
                index < rate * 4 / 5 -> 0
                index % 120 < 60 -> 2_064
                else -> -2_064
            }
            buffer.putShort(sample.toShort())
        }
        return buffer.array()
    }
}
