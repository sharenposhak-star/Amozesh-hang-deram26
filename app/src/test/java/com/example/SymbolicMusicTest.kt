package com.example

import com.example.model.DataAvailability
import com.example.model.KeySignatureChange
import com.example.model.MusicalPitch
import com.example.model.MusicalProvenance
import com.example.model.NormalizedMusicalTimeline
import com.example.model.SourceMetadata
import com.example.model.Subdivision
import com.example.model.SymbolicEventIds
import com.example.model.SymbolicMusicalEvent
import com.example.model.SymbolicScore
import com.example.model.SymbolicSourceFormat
import com.example.model.SymbolicTrack
import com.example.model.TempoChange
import com.example.model.TimeSignature
import com.example.model.TimeSignatureChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolicMusicTest {
    private val provenance = MusicalProvenance("fixture", "track-1", "source-event")

    @Test
    fun normalizesNotesRestsBarsBeatsAndSimultaneousEvents() {
        val score = SymbolicScore(
            metadata = SourceMetadata("fixture", format = SymbolicSourceFormat.STRUCTURED_SCORE),
            tempoMap = listOf(TempoChange(0.0, 120.0)),
            timeSignatureMap = listOf(TimeSignatureChange(0.0, TimeSignature.Common44)),
            keySignatureMap = listOf(KeySignatureChange(0.0, null, null)),
            tracks = listOf(
                SymbolicTrack("track-1", events = listOf(
                    SymbolicMusicalEvent("note-a", 0.0, 1.0, subdivision = Subdivision.QUARTER,
                        pitch = MusicalPitch(60), chordGroupId = "chord-1", provenance = provenance),
                    SymbolicMusicalEvent("note-b", 0.0, 1.0, pitch = MusicalPitch(64),
                        chordGroupId = "chord-1", provenance = provenance),
                    SymbolicMusicalEvent("rest-a", 1.0, 0.5, isRest = true, provenance = provenance)
                ))
            )
        )

        val timeline = NormalizedMusicalTimeline.from(score)

        assertEquals(listOf("note-a", "note-b", "rest-a"), timeline.events.map { it.sourceEventId })
        assertEquals(1, timeline.events[0].measureNumber)
        assertEquals(0.0, timeline.events[1].beatInMeasure, 0.0)
        assertTrue(timeline.events[2].isRest)
        assertEquals("chord-1", timeline.events[0].chordGroupId)
        assertEquals(DataAvailability.UNKNOWN, score.keySignatureMap[0].availability)
    }

    @Test
    fun absentPitchIsExplicitlyUnknownAndIdsAreDeterministic() {
        val unknown = SymbolicMusicalEvent(
            eventId = "unknown",
            beatPosition = 2.0,
            durationBeats = 1.0,
            pitch = null,
            provenance = provenance
        )

        assertNull(unknown.pitch)
        assertEquals(DataAvailability.UNKNOWN, unknown.availability)
        val first = SymbolicEventIds.deterministic("fixture", "track-1", 0, "60@0")
        val second = SymbolicEventIds.deterministic("fixture", "track-1", 0, "60@0")
        val different = SymbolicEventIds.deterministic("fixture", "track-1", 1, "60@0")
        assertEquals(first, second)
        assertNotEquals(first, different)
    }
}