package com.xtreeroot.mb.playback

import com.xtreeroot.mb.preferences.AudioNormalization
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class LoudnessController<Input>(private val scope: CoroutineScope, private val measure: suspend (Input) -> Double) {
    @Volatile var gain = 1.0
        private set
    private var input: Input? = null
    private var analysis: Job? = null
    private var measured = false

    fun update(current: Input?, mode: AudioNormalization) {
        if (input != current) {
            analysis?.cancel()
            analysis = null
            input = current
            gain = 1.0
            measured = false
        }
        if (mode != AudioNormalization.Standard) {
            analysis?.cancel()
            analysis = null
        } else if (current != null && analysis == null && !measured) {
            analysis = scope.launch {
                val result = try { measure(current) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return@launch }
                ensureActive()
                gain = result.takeIf { it.isFinite() && it in 0.0..4.0 } ?: 1.0
                measured = true
            }
        }
    }
}
