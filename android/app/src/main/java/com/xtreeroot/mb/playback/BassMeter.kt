package com.xtreeroot.mb.playback

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

internal class BassMeter(sampleRate: Int) {
    private val filter = 1 - exp(-2 * PI * 180 / sampleRate)
    private val smoothing = 1 - exp(-1 / (sampleRate * 0.08))
    private var low = 0.0
    private var power = 0.0

    fun sample(mono: Double) {
        low += (mono - low) * filter
        power += (low * low - power) * smoothing
    }

    fun level(): Float = (sqrt(power) * 8).coerceIn(0.0, 1.0).toFloat()
}
