package com.ljyh.mei.playback

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10

/** Normalized, smoothed output energy; unrelated to microphone input. */
data class PlaybackSpectrum(
    val low: Float,
    val mid: Float,
    val high: Float,
    val zoomEnergy: Float = low * .9f + mid * .1f,
) {
    companion object {
        val Zero = PlaybackSpectrum(0f, 0f, 0f)
    }
}

/** Runs at UI sampling cadence, keeping logarithms and envelope work off the audio thread. */
internal class SpectrumEnvelope {
    private val history = FloatArray(12)
    private val peaks = FloatArray(3)
    private val current = FloatArray(3)
    private val decayRates = floatArrayOf(ln(.98f) * 120f, ln(.99f) * 120f, ln(.999f) * 120f)
    private val zoomHistory = FloatArray(8)
    private val zoomPeaks = FloatArray(2)
    private val zoomLevels = FloatArray(2)
    private val zoomDecayRates = floatArrayOf(ln(.98f) * 60f, ln(.99f) * 60f)
    private var index = 0

    fun update(power: FloatArray, seconds: Float, zoomIntensity: Float = 1f): PlaybackSpectrum {
        val dt = seconds.coerceIn(0f, .25f)
        val retention = exp(-83.17766f * dt) // Native backdrop's half-life of 1/120 second.
        val zoomRetention = exp(-41.58883f * dt) // Halfway toward the peak at 60 updates per second.
        for (band in 0..2) {
            val level = levelFor(power[band])
            val drivesZoom = band < zoomLevels.size
            history[index * 3 + band] = smootherstep(level)
            if (drivesZoom) zoomHistory[index * 2 + band] = level * zoomIntensity
            var weighted = 0f
            var weightedZoom = 0f
            for (age in 0..3) {
                val report = (index + 1 + age) % 4
                val weight = (age + 1) * .1f
                weighted += history[report * 3 + band] * weight
                if (drivesZoom) weightedZoom += zoomHistory[report * 2 + band] * weight
            }
            peaks[band] = maxOf(peaks[band] * exp(decayRates[band] * dt), weighted)
            current[band] = peaks[band] + (current[band] - peaks[band]) * retention
            if (current[band] < .0001f) current[band] = 0f
            if (drivesZoom) {
                zoomPeaks[band] = maxOf(zoomPeaks[band] * exp(zoomDecayRates[band] * dt), weightedZoom)
                zoomLevels[band] = zoomPeaks[band] + (zoomLevels[band] - zoomPeaks[band]) * zoomRetention
            }
        }
        index = (index + 1) % 4
        // Shape the zoom bands after applying the mode gain, history and peak response.
        val zoomEnergy = smootherstep(zoomLevels[0]) * .9f + smootherstep(zoomLevels[1]) * .1f
        return PlaybackSpectrum(current[0], current[1], current[2], zoomEnergy)
    }

    private fun levelFor(energy: Float): Float = if (energy.isFinite() && energy > 0f) {
        ((10f * log10(energy) + 55f) / 47f).coerceIn(0f, 1f)
    } else 0f

    private fun smootherstep(level: Float): Float =
        level * level * level * (level * (level * 6f - 15f) + 10f)
}
