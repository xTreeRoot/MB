package com.xtreeroot.mb.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.xtreeroot.mb.preferences.AudioNormalization
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
internal class AudioSignalProcessor(private val mode: () -> AudioNormalization, private val standardGain: () -> Double,
                                    private val bassEnabled: () -> Boolean, private val reportBass: (Float) -> Unit) : BaseAudioProcessor() {
    private var bass: BassMeter? = null
    private var dynamic: DynamicLoudness? = null
    private var frames = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        inputAudioFormat.takeIf { it.encoding == C.ENCODING_PCM_16BIT } ?: AudioFormat.NOT_SET

    override fun onFlush() {
        clearBass()
        dynamic = null
    }

    private fun clearBass() {
        if (bass != null) reportBass(0f)
        bass = null
        frames = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining())
        val sampleRate = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount
        val measuringBass = bassEnabled()
        if (!measuringBass) clearBass()
        val normalization = mode()
        val isDynamic = normalization == AudioNormalization.Dynamic
        if (!isDynamic) dynamic = null
        val fixed = if (normalization == AudioNormalization.Standard) standardGain() else 1.0
        if (!measuringBass && !isDynamic && fixed == 1.0) {
            output.put(inputBuffer).flip()
            return
        }
        val meter = if (measuringBass) bass ?: BassMeter(sampleRate).also { bass = it } else null
        val dynamicGain = if (isDynamic) {
            dynamic ?: DynamicLoudness(sampleRate).also { dynamic = it }
        } else null
        val block = (sampleRate / 12).coerceAtLeast(1)
        while (inputBuffer.remaining() >= channels * 2) {
            var mono = 0.0
            var energy = 0.0
            var peak = 0.0
            val start = inputBuffer.position()
            if (meter != null || dynamicGain != null) repeat(channels) { channel ->
                val sample = inputBuffer.getShort(start + channel * 2) / 32768.0
                mono += sample
                energy += sample * sample
                peak = maxOf(peak, abs(sample))
            }
            if (meter != null) {
                meter.sample(mono / channels)
                if (++frames >= block) { frames = 0; reportBass(meter.level()) }
            }
            val gain = dynamicGain?.gain(energy / channels, peak) ?: fixed
            repeat(channels) {
                val sample = inputBuffer.getShort()
                output.putShort((sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        output.put(inputBuffer).flip()
    }
}
