package com.example

import com.example.model.DataAvailability
import com.example.model.MusicXmlScoreImporter
import com.example.model.SymbolicImportError
import com.example.model.SymbolicImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicXmlScoreImporterTest {
    @Test
    fun parsesPartwiseMeasuresDivisionsRestChordBackupForwardAndMetadata() {
        val result = MusicXmlScoreImporter().import(fixture(), "xml-fixture")
        assertTrue(result is SymbolicImportResult.Success)
        val score = (result as SymbolicImportResult.Success).score

        assertEquals("XML Fixture", score.metadata.title)
        assertEquals("Composer", score.metadata.composer)
        assertEquals(2, score.tracks.size)
        assertEquals(listOf("P1", "P2"), score.tracks.map { it.trackId })
        assertEquals(setOf("C4", "E4", "F#4", "G4"), score.events.mapNotNull { it.pitch?.spelling }.toSet())
        assertEquals(5, score.events.size)
        assertEquals(setOf(0.0, 2.0), score.events.map { it.beatPosition }.toSet())
        assertEquals(2.0, score.events.single { it.pitch?.spelling == "C4" }.durationBeats, 0.0)
        assertEquals(1, score.events.count { it.isRest })
        assertEquals(setOf(1, 2), score.events.map { it.measureNumber }.toSet())
        assertEquals(setOf("1"), score.events.mapNotNull { it.voiceId }.toSet())
        assertEquals(setOf("P1", "P2"), score.events.mapNotNull { it.trackId }.toSet())
        assertEquals(setOf(64 / 127.0f, 96 / 127.0f, null, 102 / 127.0f, 76 / 127.0f), score.events.map { it.velocity }.toSet())
        assertEquals(120.0, score.tempoMap.single().bpm, 0.0)
        assertEquals(1.0, score.tempoMap.single().beatPosition, 0.0)
        assertEquals("fifths=1", score.keySignatureMap.single().key)
        assertEquals("major", score.keySignatureMap.single().mode)
        assertEquals(DataAvailability.PRESENT, score.keySignatureMap.single().availability)
        assertEquals(3, score.timeSignatureMap.single().timeSignature.numerator)
        assertEquals(4, score.timeSignatureMap.single().timeSignature.denominator)
        assertEquals(2, score.events.count { it.chordGroupId != null })
        assertTrue(score.events.any { it.articulation?.contains("tie") == true })
        assertTrue(score.events.all { it.provenance.sourceId == "xml-fixture" })
        assertTrue(score.events.all { it.provenance.sourceLocation?.contains("part=") == true })
    }

    @Test
    fun preservesDeterministicOrderIdsAndDoesNotFabricateMissingTempo() {
        val first = MusicXmlScoreImporter().import(minimalWithoutTempo(), "stable")
        val second = MusicXmlScoreImporter().import(minimalWithoutTempo(), "stable")
        assertEquals(first, second)
        val score = (first as SymbolicImportResult.Success).score
        assertTrue(score.tempoMap.isEmpty())
        assertEquals(score.events.map { it.eventId }, score.events.map { it.eventId }.distinct())
        assertNotEquals(
            score.events.map { it.eventId },
            (MusicXmlScoreImporter().import(minimalWithoutTempo(), "other") as SymbolicImportResult.Success)
                .score.events.map { it.eventId }
        )
    }

    @Test
    fun rejectsMalformedMissingStructureAndInvalidDurations() {
        val importer = MusicXmlScoreImporter()
        assertEquals(SymbolicImportError.INVALID_SOURCE, failure(importer.import("<score-partwise>", "bad")).error)
        assertEquals(SymbolicImportError.INVALID_SOURCE, failure(importer.import("<score-partwise version=\"4.0\"/>", "bad")).error)
        assertEquals(SymbolicImportError.PARSE_FAILED, failure(importer.import(invalidDuration(), "bad")).error)
        assertEquals(SymbolicImportError.PARSE_FAILED, failure(importer.import(missingDivisions(), "bad")).error)
    }

    private fun failure(result: SymbolicImportResult): SymbolicImportResult.Failure {
        assertTrue(result is SymbolicImportResult.Failure)
        return result as SymbolicImportResult.Failure
    }

    private fun fixture() = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 3.1 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">
        <score-partwise version="3.1">
          <work><work-title>XML Fixture</work-title></work>
          <identification><creator type="composer">Composer</creator></identification>
          <part-list>
            <score-part id="P1"><part-name>Melody</part-name></score-part>
            <score-part id="P2"><part-name>Bass</part-name></score-part>
          </part-list>
          <part id="P1">
            <measure number="1">
              <attributes><divisions>2</divisions><key><fifths>1</fifths><mode>major</mode></key><time><beats>3</beats><beat-type>4</beat-type></time></attributes>
              <note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice><staff>1</staff><velocity>64</velocity><tie type="start"/></note>
              <note><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice><staff>1</staff><velocity>96</velocity></note>
            </measure>
            <measure number="2">
              <direction><sound tempo="120"/></direction>
              <note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice><staff>1</staff><tie type="stop"/></note>
              <note><pitch><step>F</step><alter>1</alter><octave>4</octave></pitch><duration>2</duration><voice>1</voice><staff>1</staff><velocity>102</velocity></note>
            </measure>
          </part>
          <part id="P2">
            <measure number="1">
              <attributes><divisions>2</divisions></attributes>
              <note><pitch><step>G</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice><staff>2</staff><velocity>76</velocity></note>
              <backup><duration>2</duration></backup>
              <note><rest/><duration>2</duration><voice>1</voice><staff>2</staff></note>
            </measure>
          </part>
        </score-partwise>
    """.trimIndent()

    private fun minimalWithoutTempo() = fixture().replace("<direction><sound tempo=\"120\"/></direction>", "")

    private fun invalidDuration() = fixture().replace("<duration>2</duration>", "<duration>0</duration>")

    private fun missingDivisions() = fixture().replace("<divisions>2</divisions>", "")
}