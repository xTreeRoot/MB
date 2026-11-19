package com.xtreeroot.mb.playback

import kotlin.math.exp
import kotlin.math.sqrt

private const val targetRms = 0.126
private const val gatePower = 0.003 * 0.003
private const val peakLimit = 0.98

internal class LoudnessMeasurement(sampleRate: Int) {
    private val blockFrames = (sampleRate * 2 / 5).coerceAtLeast(1)
    private var frames = 0
    private var keptFrames = 0L
    private var blockEnergy = 0.0
    private var total = 0.0
    private var peak = 0.0

    fun append(energy: Double, peak: Double) {
        blockEnergy += energy
        this.peak = maxOf(this.peak, peak)
        if (++frames == blockFrames) finishBlock()
    }

    fun gain(): Double {
        finishBlock()
        return if (keptFrames == 0L || peak <= 0) 1.0
        else minOf((targetRms / sqrt(total / keptFrames)).coerceIn(0.25, 4.0), peakLimit / peak)
    }

    private fun finishBlock() {
        if (frames > 0 && blockEnergy / frames >= gatePower) {
            total += blockEnergy
            keptFrames += frames
        }
        frames = 0
        blockEnergy = 0.0
    }
}

internal class DynamicLoudness(sampleRate: Int) {
    private val meter = coefficient(sampleRate, 0.4)
    private val attack = coefficient(sampleRate, 0.03)
    private val release = coefficient(sampleRate, 1.0)
    private val recovery = coefficient(sampleRate, 0.08)
    private var power = 0.0
    private var gain = 1.0
    private var limit = 1.0

    fun gain(energy: Double, peak: Double): Double {
        power += (energy - power) * meter
        val target = if (power >= gatePower) (targetRms / sqrt(power)).coerceIn(0.25, 4.0) else 1.0
        gain += (target - gain) * if (target < gain) attack else release
        limit += (1 - limit) * recovery
        if (peak * gain > peakLimit) limit = minOf(limit, peakLimit / (peak * gain))
        return gain * limit
    }

    private fun coefficient(rate: Int, time: Double) = 1 - exp(-1 / (rate * time))
}
