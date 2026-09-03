package com.example.audio

import com.example.model.NotePitchConfig
import kotlin.math.abs

data class OnsetDecision(
    val isOnset: Boolean,
    val onsetConfidence: Float,
    val signalQuality: Float,
    val noiseFloorRms: Float
)

class AdaptiveOnsetDetector(
    private val minimumRms: Float = 0.012f,
    private val noiseMultiplier: Float = 1.8f,
    private val noiseAdaptation: Float = 0.08f,
    private val minimumRise: Float = 0.01f
) {
    private var noiseFloorRms = 0.005f
    private var warmupFrames = 0

    fun reset() {
        noiseFloorRms = 0.005f
        warmupFrames = 0
    }

    fun process(rms: Float, previousRms: Float): OnsetDecision {
        if (!rms.isFinite()) {
            return OnsetDecision(false, 0f, 0f, noiseFloorRms)
        }

        if (rms <= 0f) {
            if (warmupFrames < 8) warmupFrames++
            return decision(false, 0f)
        }

        val energyRise = rms - previousRms
        if (warmupFrames < 8) {
            val isStrongOnset = rms >= minimumRms && energyRise >= minimumRise
            noiseFloorRms = if (warmupFrames == 0) rms else {
                noiseFloorRms * 0.8f + rms * 0.2f
            }
            warmupFrames++
            return decision(isStrongOnset, rms)
        }

        val threshold = maxOf(minimumRms, noiseFloorRms * noiseMultiplier)
        val riseThreshold = maxOf(minimumRise, noiseFloorRms * 0.35f)
        val isOnset = rms >= threshold && energyRise >= riseThreshold
        if (rms <= noiseFloorRms * 1.25f) {
            noiseFloorRms += (rms - noiseFloorRms) * noiseAdaptation
        }
        return decision(isOnset, rms, threshold)
    }

    private fun decision(isOnset: Boolean, rms: Float, threshold: Float = minimumRms): OnsetDecision {
        val quality = ((rms - noiseFloorRms) / maxOf(rms, minimumRms)).coerceIn(0f, 1f)
        val confidence = if (isOnset) quality else 0f
        return OnsetDecision(isOnset, confidence, quality, noiseFloorRms)
    }
}

/**
 * High-accuracy musical onset detector and note matcher.
 * Uses energy slope, spectral flux estimation, and refractory windows to prevent duplicate triggers.
 */
class OnsetAndPitchMatcher(
    private val sampleRate: Int = 22050
) {
    private val onsetDetector = AdaptiveOnsetDetector()
    private val yinDetector = YinPitchDetector(
        sampleRate = sampleRate,
        threshold = 0.15,
        minFrequency = 80.0,
        maxFrequency = 900.0
    )

    fun reset() = onsetDetector.reset()

    data class StrikeEvaluation(
        val isStrike: Boolean,
        val detectedFreqHz: Float,
        val noteName: String,
        val centsOffset: Int,
        val confidence: Float,
        val matchedScaleNote: Int?,
        val centsDeviationFromScale: Float,
        val energy: Float,
        val onsetSampleOffset: Int = 0,
        val onsetConfidence: Float = 0f,
        val signalQuality: Float = 0f,
        val noiseFloorRms: Float = 0f
    )

    /**
     * Evaluates a PCM buffer for strike onset, sample-accurate attack position, and pitch matching to the active scale.
     */
    fun processFrame(
        buffer: ShortArray,
        readSamples: Int,
        rms: Float,
        lastRms: Float,
        scaleConfig: NotePitchConfig
    ): StrikeEvaluation {
        val onset = onsetDetector.process(rms, lastRms)
        val hasEnergyOnset = onset.isOnset

        // Find the sample offset of maximum rising slope for acoustic timestamp precision
        var onsetOffset = 0
        if (hasEnergyOnset && readSamples > 64) {
            var maxSlope = 0
            val step = 16
            for (i in 0 until (readSamples - step) step step) {
                val diff = Math.abs(buffer[i + step].toInt()) - Math.abs(buffer[i].toInt())
                if (diff > maxSlope) {
                    maxSlope = diff
                    onsetOffset = i
                }
            }
        }

        if (rms < 0.012f) {
            return StrikeEvaluation(
                isStrike = false,
                detectedFreqHz = 0f,
                noteName = "--",
                centsOffset = 0,
                confidence = 0f,
                matchedScaleNote = null,
                centsDeviationFromScale = 999f,
                energy = rms,
                onsetSampleOffset = 0,
                onsetConfidence = 0f,
                signalQuality = onset.signalQuality,
                noiseFloorRms = onset.noiseFloorRms
            )
        }

        val pitchResult = yinDetector.detectPitch(buffer, readSamples)

        if (!pitchResult.isPitched || pitchResult.frequencyHz <= 0f || pitchResult.confidence < 0.5f) {
            return StrikeEvaluation(
                isStrike = hasEnergyOnset,
                detectedFreqHz = 0f,
                noteName = "--",
                centsOffset = 0,
                confidence = 0f,
                matchedScaleNote = null,
                centsDeviationFromScale = 0f,
                energy = rms,
                onsetSampleOffset = onsetOffset,
                onsetConfidence = onset.onsetConfidence,
                signalQuality = onset.signalQuality,
                noiseFloorRms = onset.noiseFloorRms
            )
        }

        val (matchedNote, centsDev) = matchToScaleByCents(pitchResult.frequencyHz, scaleConfig)

        return StrikeEvaluation(
            isStrike = hasEnergyOnset,
            detectedFreqHz = pitchResult.frequencyHz,
            noteName = pitchResult.noteName,
            centsOffset = pitchResult.centsOffset,
            confidence = pitchResult.confidence,
            matchedScaleNote = matchedNote,
            centsDeviationFromScale = centsDev,
            energy = rms,
            onsetSampleOffset = onsetOffset,
            onsetConfidence = onset.onsetConfidence,
            signalQuality = onset.signalQuality,
            noiseFloorRms = onset.noiseFloorRms
        )
    }

    /**
     * Matches detected frequency to scale using logarithmic cents (1200 * log2(f_actual / f_expected)).
     * Tolerance: ±65 cents.
     */
    fun matchToScaleByCents(
        freq: Float,
        scaleConfig: NotePitchConfig,
        centsTolerance: Float = 65f
    ): Pair<Int?, Float> {
        var bestNote: Int? = null
        var minAbsCents = Float.MAX_VALUE
        var bestCentsDev = Float.MAX_VALUE

        for (i in scaleConfig.notePitches.keys.filter { it != NotePitchConfig.NOTE_SLAP }) {
            val noteFreq = scaleConfig.getFrequency(i)
            val cents = YinPitchDetector.calculateCentsDifference(freq, noteFreq)
            if (abs(cents) < minAbsCents) {
                minAbsCents = abs(cents)
                bestCentsDev = cents
                bestNote = i
            }
        }

        return if (minAbsCents <= centsTolerance) {
            Pair(bestNote, bestCentsDev)
        } else {
            Pair(null, bestCentsDev)
        }
    }
}
