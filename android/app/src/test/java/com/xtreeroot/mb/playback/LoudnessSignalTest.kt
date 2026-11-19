package com.xtreeroot.mb.playback

import com.xtreeroot.mb.preferences.AudioNormalization
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessSignalTest {
    @Test fun wholeTrackMeasurementExcludesSilenceButLimitsTheLoudestSample() {
        val meter = LoudnessMeasurement(1_000)
        repeat(800) { meter.append(0.0, 0.0) }
        repeat(440) { meter.append(0.063 * 0.063, 0.063) }
        assertEquals(2.0, meter.gain(), 0.00001)

        val spike = LoudnessMeasurement(1_000)
        repeat(499) { spike.append(0.1 * 0.1, 0.1) }
        spike.append(0.9 * 0.9, 0.9)
        assertEquals(0.98 / 0.9, spike.gain(), 0.00001)
        assertEquals(1.0, LoudnessMeasurement(1_000).gain(), 0.0)
    }

    @Test fun dynamicModeBoostsQuietSectionsAndImmediatelyLimitsALoudTransient() {
        val processor = DynamicLoudness(48_000)
        var gain = 1.0
        repeat(48_000 * 6) { gain = processor.gain(0.03 * 0.03, 0.03) }
        assertTrue(gain in 3.95..4.0)
        val transient = processor.gain(0.9 * 0.9, 0.9) * 0.9
        assertEquals(0.98, transient, 0.00001)
        repeat(48_000 * 5) { gain = processor.gain(0.5 * 0.5, 0.5) }
        assertEquals(0.252, gain, 0.003)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun lateMeasurementsNeverLeakToTheNextTrackOrFromDisabledMode() = runTest {
        val pending = mutableMapOf<String, Continuation<Double>>()
        val controller = LoudnessController<String>(this) { item -> suspendCoroutine { pending[item] = it } }
        controller.update("first", AudioNormalization.Standard)
        runCurrent()
        controller.update("second", AudioNormalization.Standard)
        runCurrent()
        pending.remove("first")?.resume(2.0)
        runCurrent()
        assertEquals(1.0, controller.gain, 0.0)

        controller.update("second", AudioNormalization.Dynamic)
        pending.remove("second")?.resume(3.0)
        runCurrent()
        assertEquals(1.0, controller.gain, 0.0)

        controller.update("second", AudioNormalization.Standard)
        runCurrent()
        checkNotNull(pending.remove("second")).resume(1.7)
        runCurrent()
        assertEquals(1.7, controller.gain, 0.0)
        controller.update(null, AudioNormalization.Standard)
        assertEquals(1.0, controller.gain, 0.0)
    }
}
