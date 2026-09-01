package com.example

import com.example.model.MusicXmlScoreImporter
import com.example.model.SymbolicImportError
import com.example.model.SymbolicImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimewiseMusicXmlImporterTest {
    @Test
    fun parsesTimewiseMultiplePartsAndPreservesCanonicalSemantics() {
        val result = MusicXmlScoreImporter().import(timewiseFixture(), "timewise")
        assertTrue(result is SymbolicImportResult.Success)
        val score = (result as SymbolicImportResult.Success).score
        assertEquals(listOf("P1", "P2"), score.tracks.map { it.trackId })
        assertEquals(listOf("C4", "E4", "G4"), score.events.mapNotNull { it.pitch?.spelling })
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), score.events.map { it.beatPosition })
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0), score.events.map { it.durationBeats })
        assertEquals(1, score.events.count { it.isRest })
        assertEquals(2, score.events.count { it.chordGroupId != null })
        assertTrue(score.events.all { it.provenance.sourceLocation?.contains("measure=1") == true })
    }

    @Test
    fun equivalentPartwiseAndTimewiseScoresProduceEquivalentEvents() {
        val partwise = MusicXmlScoreImporter().import(partwiseEquivalent(), "equivalent") as SymbolicImportResult.Success
        val timewise = MusicXmlScoreImporter().import(timewiseEquivalent(), "equivalent") as SymbolicImportResult.Success
        assertEquals(
            partwise.score.events.map { event ->
                listOf(event.trackId, event.beatPosition, event.durationBeats, event.pitch?.midiNumber, event.isRest, event.voiceId, event.provenance.sourceTrackId)
            },
            timewise.score.events.map { event ->
                listOf(event.trackId, event.beatPosition, event.durationBeats, event.pitch?.midiNumber, event.isRest, event.voiceId, event.provenance.sourceTrackId)
            }
        )
    }

    @Test
    fun rejectsTimewiseStructuralAndCursorErrorsAndKeepsMissingTempoAbsent() {
        val importer = MusicXmlScoreImporter()
        assertEquals(SymbolicImportError.INVALID_SOURCE, failure(importer.import(timewiseFixture().replace("<part-list>", "<no-list>"), "bad")).error)
        assertEquals(SymbolicImportError.INVALID_SOURCE, failure(importer.import(timewiseFixture().replace("<part id=\"P2\">", "<part id=\"UNKNOWN\">"), "bad")).error)
        assertEquals(SymbolicImportError.INVALID_SOURCE, failure(importer.import(timewiseFixture().replace("<measure number=\"1\">", ""), "bad")).error)
        assertEquals(SymbolicImportError.PARSE_FAILED, failure(importer.import(timewiseFixture().replace("</part>", "<forward><duration>99</duration></forward></part>"), "bad")).error)
        val noTempo = importer.import(timewiseFixture(), "no-tempo") as SymbolicImportResult.Success
        assertTrue(noTempo.score.tempoMap.isEmpty())
    }

    @Test
    fun deterministicTimewiseImportRetainsMissingVoiceAndStaffAsUnknown() {
        val first = MusicXmlScoreImporter().import(timewiseFixture(), "stable")
        val second = MusicXmlScoreImporter().import(timewiseFixture(), "stable")
        assertEquals(first, second)
        val score = (first as SymbolicImportResult.Success).score
        assertTrue(score.events.all { it.provenance.sourceId == "stable" })
        assertTrue(score.events.any { it.voiceId == null })
    }

    private fun failure(result: SymbolicImportResult): SymbolicImportResult.Failure {
        assertTrue(result is SymbolicImportResult.Failure)
        return result as SymbolicImportResult.Failure
    }

    private fun timewiseFixture() = """
        <score-timewise version="3.1">
          <work><work-title>Timewise</work-title></work>
          <part-list><score-part id="P1"><part-name>Lead</part-name></score-part><score-part id="P2"><part-name>Bass</part-name></score-part></part-list>
          <measure number="1">
            <part id="P1"><attributes><divisions>2</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes><note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice><staff>1</staff></note><note><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice><staff>1</staff></note></part>
            <part id="P2"><attributes><divisions>2</divisions></attributes><note><pitch><step>G</step><octave>4</octave></pitch><duration>2</duration></note><backup><duration>2</duration></backup><note><rest/><duration>2</duration></note></part>
          </measure>
        </score-timewise>
    """.trimIndent()

    private fun partwiseEquivalent() = """
        <score-partwise version="3.1"><part-list><score-part id="P1"/><score-part id="P2"/></part-list>
        <part id="P1"><measure number="1"><attributes><divisions>2</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes><note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice><staff>1</staff></note></measure></part>
        <part id="P2"><measure number="1"><attributes><divisions>2</divisions></attributes><note><pitch><step>G</step><octave>4</octave></pitch><duration>2</duration></note></measure></part></score-partwise>
    """.trimIndent()

    private fun timewiseEquivalent() = """
        <score-timewise version="3.1"><part-list><score-part id="P1"/><score-part id="P2"/></part-list>
        <measure number="1"><part id="P1"><attributes><divisions>2</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes><note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice><staff>1</staff></note></part><part id="P2"><attributes><divisions>2</divisions></attributes><note><pitch><step>G</step><octave>4</octave></pitch><duration>2</duration></note></part></measure></score-timewise>
    """.trimIndent()
}