package com.xtreeroot.mb.playback

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
internal object AndroidLoudness {
    suspend fun measure(factory: DataSource.Factory, input: Uri): Double = withContext(Dispatchers.IO) {
        LoudnessMediaInput(factory, input) { ensureActive() }.use { source ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(source)
                val track = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw IOException("No audio track")
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val decoder = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
                try {
                    decoder.configure(format, null, null, 0)
                    decoder.start()
                    decode(extractor, decoder) { ensureActive() }.also { source.checkReadFailure() }
                } finally { decoder.release() }
            } finally { extractor.release() }
        }
    }

    private fun decode(extractor: MediaExtractor, decoder: MediaCodec, checkActive: () -> Unit): Double {
        val info = MediaCodec.BufferInfo()
        var meter: LoudnessMeasurement? = null
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var channels = 0
        var rate = 0
        var inputEnded = false
        var progressedAt = System.nanoTime()
        while (true) {
            checkActive()
            if (!inputEnded) {
                val index = decoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val input = checkNotNull(decoder.getInputBuffer(index))
                    val bytes = extractor.readSampleData(input, 0)
                    if (bytes < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        decoder.queueInputBuffer(index, 0, bytes, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    progressedAt = System.nanoTime()
                }
            }
            val index = decoder.dequeueOutputBuffer(info, 10_000)
            if (index >= 0) {
                try {
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val pcm = checkNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.nativeOrder())
                        pcm.position(info.offset).limit(info.offset + info.size)
                        val current = meter ?: LoudnessMeasurement(rate).also { meter = it }
                        append(pcm, channels, encoding, current)
                    }
                } finally { decoder.releaseOutputBuffer(index, false) }
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    return meter?.gain() ?: throw IOException("No decoded audio")
                }
                progressedAt = System.nanoTime()
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = decoder.outputFormat
                val nextRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val nextChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (nextRate <= 0 || nextChannels <= 0 || (meter != null && (nextRate != rate || nextChannels != channels))) {
                    throw IOException("Incompatible PCM format")
                }
                rate = nextRate
                channels = nextChannels
                encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                else AudioFormat.ENCODING_PCM_16BIT
                progressedAt = System.nanoTime()
            } else if (System.nanoTime() - progressedAt > 5_000_000_000L) throw IOException("Audio decoder stalled")
        }
    }

    private fun append(pcm: ByteBuffer, channels: Int, encoding: Int, meter: LoudnessMeasurement) {
        val bytes = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> throw IOException("Unsupported PCM encoding")
        }
        if (channels <= 0 || pcm.remaining() % (bytes * channels) != 0) throw IOException("Invalid PCM frame")
        while (pcm.hasRemaining()) {
            var energy = 0.0
            var peak = 0.0
            repeat(channels) {
                val sample = if (bytes == 2) pcm.short / 32768.0 else pcm.float.toDouble()
                if (!sample.isFinite()) throw IOException("Invalid PCM sample")
                energy += sample * sample
                peak = maxOf(peak, abs(sample))
            }
            meter.append(energy / channels, peak)
        }
    }
}
