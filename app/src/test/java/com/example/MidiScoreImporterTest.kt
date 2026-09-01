package com.example

import com.example.model.BinaryMidiScoreImporter
import com.example.model.SymbolicImportError
import com.example.model.SymbolicImportResult
import com.example.model.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiScoreImporterTest {
    @Test
    fun parsesFormatOneNotesMetadataRunningStatusAndProvenance() {
        val result = BinaryMidiScoreImporter().import(formatOneFixture(), "midi-fixture")
        assertTrue(result is SymbolicImportResult.Success)
        val score = (result as SymbolicImportResult.Success).score

        assertEquals(2, score.tracks.size)
        assertEquals(listOf(120.0), score.tempoMap.map { it.bpm })
        assertEquals(0.0, score.tempoMap.single().beatPosition, 0.0)
        assertEquals(TimeSignature(4, 4), score.timeSignatureMap.single().timeSignature)
        assertEquals(listOf(60, 64), score.events.mapNotNull { it.pitch?.midiNumber })
        assertEquals(listOf(0.0, 0.5), score.events.map { it.beatPosition })
        assertEquals(listOf(0.5, 0.5), score.events.map { it.durationBeats })
        assertEquals(listOf(100 / 127.0f, 127 / 127.0f), score.events.map { it.velocity })
        assertEquals(listOf(1, 1), score.events.map { it.trackId?.removePrefix("track-")?.toInt() })
        assertEquals(listOf(0, 0), score.events.map { it.channel })
        assertTrue(score.events.all { it.provenance.sourceId == "midi-fixture" })
        assertTrue(score.events.all { it.provenance.sourceLocation?.contains("startTick=") == true })
        assertEquals(score.events.map { it.eventId }, score.events.map { it.eventId }.distinct())
    }

    @Test
    fun parsesFormatZeroExplicitNoteOffAndVelocityZeroAsNoteOff() {
        val result = BinaryMidiScoreImporter().import(formatZeroFixture(), "format-0")
        assertTrue(result is SymbolicImportResult.Success)
        val events = (result as SymbolicImportResult.Success).score.events
        assertEquals(2, events.size)
        assertEquals(listOf(0.0, 0.5), events.map { it.beatPosition })
        assertEquals(listOf(0.5, 0.5), events.map { it.durationBeats })
        assertEquals(listOf(0, 0), events.map { it.channel })
    }

    @Test
    fun rejectsMalformedHeaderLengthVlqTrackAndRunningStatus() {
        val importer = BinaryMidiScoreImporter()
        assertEquals(SymbolicImportError.INVALID_SOURCE, failure(importer.import(byteArrayOf(0, 1, 2), "bad")).error)
        assertEquals(SymbolicImportError.PARSE_FAILED, failure(importer.import(formatZeroFixture().copyOfRange(0, 14), "bad")).error)
        assertEquals(SymbolicImportError.PARSE_FAILED, failure(importer.import(wrongTrackLengthFixture(), "bad")).error)
        assertEquals(SymbolicImportError.PARSE_FAILED, failure(importer.import(truncatedVlqFixture(), "bad")).error)
        assertEquals(SymbolicImportError.PARSE_FAILED, failure(importer.import(truncatedTrackEventFixture(), "bad")).error)
        assertEquals(SymbolicImportError.PARSE_FAILED, failure(importer.import(invalidRunningStatusFixture(), "bad")).error)
    }

    @Test
    fun missingTempoRemainsAbsent() {
        val result = BinaryMidiScoreImporter().import(formatZeroFixture(), "no-tempo")
        assertTrue(result is SymbolicImportResult.Success)
        assertTrue((result as SymbolicImportResult.Success).score.tempoMap.isEmpty())
    }

    @Test
    fun reimportIsDeterministicAndInputBytesRemainUnchanged() {
        val fixture = formatOneFixture()
        val original = fixture.copyOf()
        val first = BinaryMidiScoreImporter().import(fixture, "stable")
        val second = BinaryMidiScoreImporter().import(fixture, "stable")
        assertEquals(first, second)
        assertTrue(fixture.contentEquals(original))
    }

    @Test
    fun pairsOverlappingSamePitchNotesFifoAndKeepsSimultaneousNotes() {
        val events = (BinaryMidiScoreImporter().import(overlappingNotesFixture(), "overlap") as SymbolicImportResult.Success).score.events

        assertEquals(3, events.size)
        assertEquals(listOf(0.0, 0.0, 0.25), events.map { it.beatPosition })
        assertEquals(listOf(0.5, 1.0, 0.75), events.map { it.durationBeats })
    }

    @Test
    fun rejectsUnmatchedNoteOffUnclosedNoteMalformedVlqAndTrackBoundary() {
        val importer = BinaryMidiScoreImporter()

        assertEquals(SymbolicImportError.PARSE_FAILED, failure(importer.import(unmatchedNoteOffFixture(), "off")).error)
        assertEquals(SymbolicImportError.PARSE_FAILED, failure(importer.import(unclosedNoteFixture(), "on")).error)
        assertEquals(SymbolicImportError.PARSE_FAILED, failure(importer.import(malformedVlqFixture(), "vlq")).error)
        assertEquals(SymbolicImportError.PARSE_FAILED, failure(importer.import(invalidChunkLengthFixture(), "chunk")).error)
    }

    @Test
    fun deduplicatesIdenticalTempoAndTimeSignatureChangesAndPreservesSourceHash() {
        val fixture = duplicateMetaFixture()
        val first = BinaryMidiScoreImporter().import(fixture, "meta") as SymbolicImportResult.Success
        val second = BinaryMidiScoreImporter().import(fixture, "meta") as SymbolicImportResult.Success

        assertEquals(1, first.score.tempoMap.size)
        assertEquals(1, first.score.timeSignatureMap.size)
        assertEquals(first, second)
        assertTrue(first.score.metadata.sourceHash?.isNotBlank() == true)
    }

    private fun failure(result: SymbolicImportResult): SymbolicImportResult.Failure {
        assertTrue(result is SymbolicImportResult.Failure)
        return result as SymbolicImportResult.Failure
    }

    private fun formatOneFixture(): ByteArray = midi(
        1,
        track(0x00, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20, 0x00, 0xFF, 0x58, 0x04, 0x04, 0x02, 0x18, 0x08, 0x00, 0xFF, 0x2F, 0x00),
        track(0x00, 0x90, 0x3C, 0x64, 0x3C, 0x3C, 0x00, 0x00, 0x90, 0x40, 0x7F, 0x3C, 0x80, 0x40, 0x40, 0x00, 0xFF, 0x2F, 0x00)
    )

    private fun formatZeroFixture(): ByteArray = midi(
        0,
        track(0x00, 0x90, 0x3C, 0x40, 0x3C, 0x80, 0x3C, 0x20, 0x00, 0x90, 0x3E, 0x50, 0x3C, 0x3E, 0x00, 0x00, 0xFF, 0x2F, 0x00)
    )

    private fun truncatedVlqFixture(): ByteArray = midi(0, track(0x80))

    private fun wrongTrackLengthFixture(): ByteArray = formatZeroFixture().also {
        it[18] = 0x00
        it[19] = 0x00
        it[20] = 0x01
        it[21] = 0x00
    }

    private fun truncatedTrackEventFixture(): ByteArray = midi(0, track(0x00, 0x90, 0x3C))

    private fun invalidRunningStatusFixture(): ByteArray = midi(0, track(0x00, 0x3C, 0x40, 0x00, 0xFF, 0x2F, 0x00))

    private fun overlappingNotesFixture(): ByteArray = midi(
        0,
        track(0x00, 0x90, 0x3C, 0x40, 0x00, 0x90, 0x3C, 0x50, 0x1E, 0x90, 0x3C, 0x60, 0x1E, 0x80, 0x3C, 0x20, 0x3C, 0x80, 0x3C, 0x20, 0x00, 0x80, 0x3C, 0x20, 0x00, 0xFF, 0x2F, 0x00)
    )

    private fun unmatchedNoteOffFixture(): ByteArray = midi(0, track(0x00, 0x80, 0x3C, 0x20, 0x00, 0xFF, 0x2F, 0x00))

    private fun unclosedNoteFixture(): ByteArray = midi(0, track(0x00, 0x90, 0x3C, 0x40, 0x00, 0xFF, 0x2F, 0x00))

    private fun malformedVlqFixture(): ByteArray = midi(0, track(0x80, 0x80, 0x80, 0x80, 0x00, 0xFF, 0x2F, 0x00))

    private fun invalidChunkLengthFixture(): ByteArray = formatZeroFixture().also {
        it[17] = 0x7F
    }

    private fun duplicateMetaFixture(): ByteArray = midi(
        1,
        track(0x00, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20, 0x00, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20,
            0x00, 0xFF, 0x58, 0x04, 0x04, 0x02, 0x18, 0x08, 0x00, 0xFF, 0x58, 0x04, 0x04, 0x02, 0x18, 0x08, 0x00, 0xFF, 0x2F, 0x00),
        track(0x00, 0xFF, 0x2F, 0x00)
    )

    private fun midi(format: Int, vararg tracks: ByteArray): ByteArray = byteArrayOf(
        0x4D, 0x54, 0x68, 0x64, 0x00, 0x00, 0x00, 0x06,
        0x00, format.toByte(), 0x00, tracks.size.toByte(), 0x00, 0x78
    ) + tracks.fold(byteArrayOf()) { all, track -> all + track }

    private fun track(vararg bytes: Int): ByteArray = byteArrayOf(
        0x4D, 0x54, 0x72, 0x6B,
        (bytes.size shr 24).toByte(), (bytes.size shr 16).toByte(), (bytes.size shr 8).toByte(), bytes.size.toByte()
    ) + bytes.map { it.toByte() }.toByteArray()
}