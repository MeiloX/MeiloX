package com.ljyh.mei.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumEnvelopeTest {
    @Test
    fun silenceAndInvalidSamplesStayNeutral() {
        val envelope = SpectrumEnvelope()
        repeat(10) {
            assertEquals(PlaybackSpectrum.Zero, envelope.update(floatArrayOf(0f, Float.NaN, Float.POSITIVE_INFINITY), .033f))
        }
    }

    @Test
    fun attackIsSmoothedAndEveryBandDecaysInsteadOfFlashingOff() {
        val envelope = SpectrumEnvelope()
        val power = floatArrayOf(.1f, .1f, .1f)
        val first = envelope.update(power, .033f)
        assertTrue(first.low in .1f.. .5f)
        var peak = first
        repeat(20) { peak = envelope.update(power, .033f) }
        assertTrue(peak.low > .9f && peak.high > .9f)
        var tail = peak
        power.fill(0f)
        repeat(15) { tail = envelope.update(power, .033f) }
        assertTrue(tail.low > .1f && tail.low < peak.low)
        assertTrue(tail.high > tail.mid && tail.mid > tail.low)
        repeat(3_000) { tail = envelope.update(power, .033f) }
        assertEquals(PlaybackSpectrum.Zero, tail)
    }

    @Test
    fun amplitudesRemainBoundedAndIndependent() {
        val envelope = SpectrumEnvelope()
        var result = PlaybackSpectrum.Zero
        repeat(20) { result = envelope.update(floatArrayOf(100f, 0f, .0001f), .033f) }
        assertTrue(result.low in .99f..1f)
        assertEquals(0f, result.mid, 0f)
        assertTrue(result.high in .05f.. .4f)
    }

    @Test
    fun zoomShapesTheWeightedLowAndMidHistoryAfterTheAttack() {
        val seconds = 1f / 60f
        val bass = SpectrumEnvelope().update(floatArrayOf(1f, 0f, 0f), seconds)
        val middle = SpectrumEnvelope().update(floatArrayOf(0f, 1f, 0f), seconds)
        val treble = SpectrumEnvelope().update(floatArrayOf(0f, 0f, 1f), seconds)
        // The newest report weighs .4 and the first step moves halfway toward it.
        assertEquals(.05792f * .9f, bass.zoomEnergy, .00001f)
        assertEquals(.05792f * .1f, middle.zoomEnergy, .00001f)
        assertEquals(0f, treble.zoomEnergy, 0f)
    }

    @Test
    fun normalModeAttenuatesZoomBeforeShapingWithoutChangingColorBands() {
        val normal = SpectrumEnvelope()
        val lyrics = SpectrumEnvelope()
        val power = floatArrayOf(1f, 1f, 1f)
        val seconds = 1f / 30f
        repeat(40) {
            normal.update(power, seconds, zoomIntensity = .2f)
            lyrics.update(power, seconds)
        }
        val normalFrame = normal.update(power, seconds, zoomIntensity = .2f)
        val lyricsFrame = lyrics.update(power, seconds)
        assertEquals(.05792f, normalFrame.zoomEnergy, .00001f)
        assertEquals(1f, lyricsFrame.zoomEnergy, .00001f)
        assertEquals(normalFrame.copy(zoomEnergy = lyricsFrame.zoomEnergy), lyricsFrame)
    }

    @Test
    fun leavingLyricsRetainsTheRecentZoomEnvelopeAndThenSettles() {
        val envelope = SpectrumEnvelope()
        val power = floatArrayOf(1f, 1f, 0f)
        val seconds = 1f / 30f
        repeat(40) { envelope.update(power, seconds) }
        val first = envelope.update(power, seconds, zoomIntensity = .2f)
        assertTrue(first.zoomEnergy > .9f)
        repeat(300) { envelope.update(power, seconds, zoomIntensity = .2f) }
        assertEquals(.05792f, envelope.update(power, seconds, zoomIntensity = .2f).zoomEnergy, .00001f)
    }
}
