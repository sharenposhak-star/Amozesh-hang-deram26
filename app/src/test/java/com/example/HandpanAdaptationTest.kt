package com.example

import com.example.model.AdaptationStatus
import com.example.model.HandpanAdaptationSolver
import com.example.model.HandpanArrangement
import com.example.model.MusicalPitch
import com.example.model.MusicalProvenance
import com.example.model.NormalizedMusicalTimeline
import com.example.model.SourceMetadata
import com.example.model.SymbolicMusicalEvent
import com.example.model.SymbolicScore
import com.example.model.SymbolicSourceFormat
import com.example.model.SymbolicTrack
import com.example.model.TempoChange
import com.example.model.TimeSignature
import com.example.model.TimeSignatureChange
import com.example.model.StructuredScoreImporter
import com.example.model.SymbolicImportError
import com.example.model.SymbolicImportResult
import com.example.model.AdaptationQualityCalculator
import com.example.model.SymbolicPlaybackTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandpanAdaptationTest {
    private fun timeline(vararg events: SymbolicMusicalEvent): NormalizedMusicalTimeline {
        return NormalizedMusicalTimeline.from(SymbolicScore(
            SourceMetadata("fixture", format = SymbolicSourceFormat.STRUCTURED_SCORE),
            listOf(TempoChange(0.0, 100.0)),
            listOf(TimeSignatureChange(0.0, TimeSignature.Common44)),
            emptyList(),
            listOf(SymbolicTrack("track", events = events.toList()))
        ))
    }

    private fun event(id: String, midi: Int, beat: Double = 0.0, voice: String? = null, hand: com.example.model.PlayingHand? = null) =
        SymbolicMusicalEvent(id, beat, 0.5, pitch = MusicalPitch(midi), velocity = 0.4f,
            voiceId = voice, sourceHand = hand, provenance = MusicalProvenance("fixture", "track", id))

    @Test
    fun importerBoundaryRejectsInvalidSourceAndDefersRealParserSelection() {
        val emptyMidi = com.example.model.MidiScoreImporter().import("", "source")
        assertTrue(emptyMidi is SymbolicImportResult.Failure)
        assertEquals(SymbolicImportError.INVALID_SOURCE, (emptyMidi as SymbolicImportResult.Failure).error)

        val invalidXml = com.example.model.MusicXmlScoreImporter().import("plain text", "source")
        assertTrue(invalidXml is SymbolicImportResult.Failure)
        assertEquals(SymbolicImportError.INVALID_SOURCE, (invalidXml as SymbolicImportResult.Failure).error)

        val structured = com.example.model.StructuredScoreImporter().importScore(
            SymbolicScore(
                SourceMetadata("score", format = SymbolicSourceFormat.STRUCTURED_SCORE),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList()
            )
        )
        assertTrue(structured is SymbolicImportResult.Success)
    }

    @Test
    fun solverIsDeterministicAndProjectionPreservesTimingAndVelocity() {
        val first = HandpanAdaptationSolver.adapt(timeline(event("e1", 62)), com.example.model.AdaptationRequest(
            com.example.model.InstrumentProfile.DEFAULT_D_KURD_9
        ))
        val second = HandpanAdaptationSolver.adapt(timeline(event("e1", 62)), com.example.model.AdaptationRequest(
            com.example.model.InstrumentProfile.DEFAULT_D_KURD_9
        ))
        assertEquals(first.decisions, second.decisions)
        assertEquals(AdaptationStatus.EXACT, first.decisions.single().status)
        val pattern = first.toHandpanPattern("adapted")
        assertEquals(0.0, pattern.events.single().beatPosition, 0.0)
        assertEquals(0.5, pattern.events.single().duration, 0.0)
        assertEquals(0.4f, pattern.events.single().velocity, 0.0f)
    }

    @Test
    fun playbackScalingDoesNotMutateCanonicalTimelineAndMetricsAreZeroSafe() {
        val canonical = timeline(event("e1", 62))
        val halfSpeed = SymbolicPlaybackTiming.scale(canonical, 0.5)
        assertEquals(0.0, canonical.events.single().beatPosition, 0.0)
        assertEquals(1.0, halfSpeed.single().durationBeats, 0.0)
        val quality = AdaptationQualityCalculator.calculate(
            HandpanAdaptationSolver.adapt(canonical, com.example.model.AdaptationRequest(
                com.example.model.InstrumentProfile.DEFAULT_D_KURD_9
            ))
        )
        assertEquals(1.0, quality.playableNoteRatio.ratio!!, 0.0)
        assertEquals(0.0, quality.omittedNoteRatio.ratio!!, 0.0)
    }

    @Test
    fun unavailablePitchIsExplicitAndTransposeAndOctaveAreIndependent() {
        val unavailable = HandpanAdaptationSolver.adapt(timeline(event("missing", 61)), com.example.model.AdaptationRequest(
            com.example.model.InstrumentProfile.DEFAULT_D_KURD_9
        )).decisions.single()
        assertEquals(AdaptationStatus.IMPOSSIBLE, unavailable.status)
        assertTrue(unavailable.constraintViolations.contains("exact-pitch-unavailable"))

        val transposed = HandpanAdaptationSolver.adapt(timeline(event("transpose", 62)), com.example.model.AdaptationRequest(
            com.example.model.InstrumentProfile.DEFAULT_D_KURD_9, transposeSemitones = 2
        )).decisions.single()
        assertEquals(AdaptationStatus.TRANSPOSED, transposed.status)
        assertEquals(2, transposed.transposeSemitones)
        assertEquals(64, transposed.targetPitch?.midiNumber)

        val octave = HandpanAdaptationSolver.adapt(timeline(event("octave", 50)), com.example.model.AdaptationRequest(
            com.example.model.InstrumentProfile.DEFAULT_D_KURD_9, octaveShift = 1
        )).decisions.single()
        assertEquals(AdaptationStatus.OCTAVE_SHIFTED, octave.status)
        assertEquals(1, octave.octaveShift)
        assertEquals(62, octave.targetPitch?.midiNumber)
    }

    @Test
    fun chordReductionPreservesMelodyAndReportsSustainAndHandConstraints() {
        val chord = HandpanAdaptationSolver.adapt(
            timeline(event("melody", 64, voice = "melody"), event("harmony", 65, voice = "harmony")),
            com.example.model.AdaptationRequest(com.example.model.InstrumentProfile.DEFAULT_D_KURD_9, maxSimultaneousNotes = 1)
        )
        assertEquals(AdaptationStatus.EXACT, chord.decisions.first { it.sourceEventId == "melody" }.status)
        assertEquals(AdaptationStatus.REDUCED, chord.decisions.first { it.sourceEventId == "harmony" }.status)

        val sustained = HandpanAdaptationSolver.adapt(timeline(event("long", 62)), com.example.model.AdaptationRequest(
            com.example.model.InstrumentProfile.DEFAULT_D_KURD_9, maxSustainBeats = 0.25
        )).decisions.single()
        assertEquals(AdaptationStatus.SIMPLIFIED, sustained.status)
        assertTrue(sustained.constraintViolations.contains("sustain-limit"))

        val explicit = HandpanAdaptationSolver.adapt(timeline(event("right", 64, hand = com.example.model.PlayingHand.RIGHT)), com.example.model.AdaptationRequest(
            com.example.model.InstrumentProfile.DEFAULT_D_KURD_9
        )).decisions.single()
        assertEquals(com.example.model.HandAssignmentStatus.EXPLICIT, explicit.handStatus)
    }

    @Test
    fun repeatedNoteConstraintIsExplicit() {
        val arrangement = HandpanAdaptationSolver.adapt(
            timeline(event("first", 62, 0.0), event("second", 62, 0.25)),
            com.example.model.AdaptationRequest(com.example.model.InstrumentProfile.DEFAULT_D_KURD_9, minimumRepeatedNoteIntervalBeats = 0.5)
        )
        assertEquals(AdaptationStatus.IMPOSSIBLE, arrangement.decisions.single { it.sourceEventId == "second" }.status)
        assertTrue(arrangement.decisions.single { it.sourceEventId == "second" }.constraintViolations.contains("repeated-note-limit"))
    }
}