package com.example

import com.example.audio.HandpanSynthesizer
import com.example.audio.OnsetAndPitchMatcher
import com.example.audio.YinPitchDetector
import com.example.model.NotePitchConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DspAndAcousticVerificationTest {

    @Test
    fun steadyBackgroundNoiseDoesNotBecomeAnOnsetAfterNoiseFloorIsLearned() {
        val matcher = OnsetAndPitchMatcher(22050)
        val config = NotePitchConfig.D_KURD_9

        repeat(12) {
            matcher.processFrame(ShortArray(2048), 2048, rms = 0.06f, lastRms = 0.06f, config)
        }

        val noiseRise = matcher.processFrame(
            buffer = ShortArray(2048),
            readSamples = 2048,
            rms = 0.061f,
            lastRms = 0.05f,
            scaleConfig = config
        )

        assertTrue("A learned noise floor must reject a small noise fluctuation", !noiseRise.isStrike)
    }

    @Test
    fun testYinPitchDetectionOnPureSine_440Hz() {
        val sampleRate = 22050
        val targetFreq = 440.0 // A4
        val durationSamples = 2048
        val buffer = ShortArray(durationSamples)

        for (i in 0 until durationSamples) {
            val t = i.toDouble() / sampleRate
            val sample = sin(2.0 * PI * targetFreq * t) * 0.8
            buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }

        val yin = YinPitchDetector(sampleRate = sampleRate)
        val result = yin.detectPitch(buffer)

        assertTrue("Should detect pitch as true", result.isPitched)
        assertEquals("A4", result.noteName)
        assertEquals(440.0f, result.frequencyHz, 3.5f) // High accuracy within ~3.5 Hz
        assertTrue("High confidence", result.confidence > 0.8f)
    }

    @Test
    fun testYinPitchDetectionOnDing_146Hz() {
        val sampleRate = 22050
        val targetFreq = 146.83 // D3 Ding
        val durationSamples = 2048
        val buffer = ShortArray(durationSamples)

        for (i in 0 until durationSamples) {
            val t = i.toDouble() / sampleRate
            val sample = sin(2.0 * PI * targetFreq * t) * 0.85
            buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }

        val yin = YinPitchDetector(sampleRate = sampleRate)
        val result = yin.detectPitch(buffer)

        assertTrue(result.isPitched)
        assertEquals("D3", result.noteName)
        assertEquals(146.83f, result.frequencyHz, 2.5f)
    }

    @Test
    fun testCentsCalculationAccuracy() {
        // Equal frequencies -> 0 cents
        val zeroCents = YinPitchDetector.calculateCentsDifference(440f, 440f)
        assertEquals(0f, zeroCents, 0.01f)

        // One octave up (880Hz / 440Hz) -> exactly 1200 cents
        val octaveCents = YinPitchDetector.calculateCentsDifference(880f, 440f)
        assertEquals(1200f, octaveCents, 0.5f)

        // One semitone up (440 * 2^(1/12) ≈ 466.16) -> 100 cents
        val semitoneCents = YinPitchDetector.calculateCentsDifference(466.164f, 440f)
        assertEquals(100f, semitoneCents, 0.5f)
    }

    @Test
    fun testOnsetMatcherScaleMatchingByCents() {
        val matcher = OnsetAndPitchMatcher(22050)
        val config = NotePitchConfig() // Default D Kurd 9

        // Test Ding (146.83 Hz) with tiny 5-cent detune
        val detunedDing = 146.83f * Math.pow(2.0, 5.0 / 1200.0).toFloat()
        val (matchedNote, centsDev) = matcher.matchToScaleByCents(detunedDing, config, centsTolerance = 50f)

        assertEquals(NotePitchConfig.NOTE_DING, matchedNote)
        assertEquals(5.0f, centsDev, 0.5f)

        // Test Note 8 (A4 = 440 Hz)
        val (matchedNote8, _) = matcher.matchToScaleByCents(441.5f, config, centsTolerance = 50f)
        assertEquals(8, matchedNote8)
    }

    @Test
    fun testOnsetMatcherKeepsUnpitchedEnergyOnsetAsStrike() {
        val matcher = OnsetAndPitchMatcher(22050)
        val impulse = ShortArray(2048)
        impulse[0] = (Short.MAX_VALUE * 0.9f).toInt().toShort()

        val result = matcher.processFrame(
            buffer = impulse,
            readSamples = impulse.size,
            rms = 0.02f,
            lastRms = 0f,
            scaleConfig = NotePitchConfig()
        )

        assertTrue(result.isStrike)
        assertEquals(0f, result.detectedFreqHz, 0.01f)
        assertEquals(0f, result.confidence, 0.01f)
        assertEquals(null, result.matchedScaleNote)
    }

    @Test
    fun strongOnsetDuringWarmupIsNotDiscarded() {
        val matcher = OnsetAndPitchMatcher(22050)
        val config = NotePitchConfig()

        matcher.processFrame(ShortArray(2048), 2048, rms = 0.005f, lastRms = 0f, config)
        val result = matcher.processFrame(
            buffer = ShortArray(2048),
            readSamples = 2048,
            rms = 0.02f,
            lastRms = 0.005f,
            scaleConfig = config
        )

        assertTrue(result.isStrike)
    }

    @Test
    fun testHandpanSynthesizerClippingSafety() {
        val dingPcm = HandpanSynthesizer.generateHandpanSample(
            frequency = 146.83f,
            durationSeconds = 0.5f,
            isDing = true,
            velocity = 1.0f
        )
        assertTrue(dingPcm.isNotEmpty())

        val slapPcm = HandpanSynthesizer.generateSlapSample(velocity = 1.0f)
        assertTrue(slapPcm.isNotEmpty())

        val clickPcm = HandpanSynthesizer.generateClickSample(isAccent = true)
        assertTrue(clickPcm.isNotEmpty())

        val wav = HandpanSynthesizer.pcmToWav(dingPcm)
        assertTrue(wav.size > 44)
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])
    }
}
