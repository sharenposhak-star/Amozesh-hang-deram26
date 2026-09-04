package com.example

import com.example.model.AdaptationRequest
import com.example.model.AdaptationApproval
import com.example.model.DetectedScoreFormat
import com.example.model.FormatDetectionResult
import com.example.model.ImageScoreSource
import com.example.model.ImportedScoreRecord
import com.example.model.PdfPage
import com.example.model.PdfRasterizationResult
import com.example.model.PdfScoreSource
import com.example.model.ScoreFormatDetector
import com.example.model.ScoreIngestionResult
import com.example.model.ScoreIngestionStore
import com.example.model.ScoreIngestionUseCase
import com.example.model.ScoreSource
import com.example.model.InstrumentProfile
import com.example.model.scoreSourceFromImportableBytes
import java.io.Closeable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScoreIngestionPipelineTest {
    @Test
    fun detectorUsesContentSignatures() {
        assertEquals(DetectedScoreFormat.MIDI_BINARY, detected(midiBytes()))
        assertEquals(DetectedScoreFormat.MUSIC_XML, detected("<score-partwise version=\"4.0\"/>".encodeToByteArray()))
        assertEquals(DetectedScoreFormat.PDF, detected("%PDF-1.7".encodeToByteArray()))
        assertEquals(DetectedScoreFormat.IMAGE, detected(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertTrue(ScoreFormatDetector.detect("not a score".encodeToByteArray()) is FormatDetectionResult.Unsupported)
    }

    @Test
    fun importableBytesBecomeCanonicalTypedSources() {
        val midi = scoreSourceFromImportableBytes("midi-source", midiBytes())
        val musicXml = scoreSourceFromImportableBytes("xml-source", minimalMusicXml().encodeToByteArray())

        assertTrue(midi is ScoreSource.BinaryMidi)
        assertTrue(musicXml is ScoreSource.MusicXml)
        assertEquals("xml-source", musicXml?.sourceId)
    }

    @Test
    fun binaryMidiHasOneCanonicalImportAndPersistsMetadata() = runBlocking {
        val store = MemoryStore()
        val result = ScoreIngestionUseCase(store = store, clock = { 123L }).ingest(
            ScoreSource.BinaryMidi("midi-source", midiBytes())
        )

        assertTrue(result is ScoreIngestionResult.Success)
        assertEquals(DetectedScoreFormat.MIDI_BINARY, store.record?.format)
        assertEquals(123L, store.record?.importedAtEpochMs)
        assertTrue(store.record?.timelineJson?.contains("midi-source") == true)
    }

    @Test
    fun musicXmlUsesTheCanonicalImporterAndTimeline() = runBlocking {
        val result = ScoreIngestionUseCase().ingest(ScoreSource.MusicXml("xml-source", minimalMusicXml()))

        assertTrue(result is ScoreIngestionResult.Success)
        val timeline = (result as ScoreIngestionResult.Success).timeline
        assertEquals("xml-source", timeline.sourceId)
        assertEquals(60, timeline.events.single().pitch?.midiNumber)
        assertEquals(1, timeline.events.single().measureNumber)
    }

    @Test
    fun malformedMidiAndImpossibleAdaptationAreRejected() = runBlocking {
        val useCase = ScoreIngestionUseCase()
        val malformed = useCase.ingest(ScoreSource.BinaryMidi("bad", "MThd".encodeToByteArray()))
        val impossible = useCase.ingestAndAdapt(
            ScoreSource.BinaryMidi("impossible", midiBytes(note = 61)),
            AdaptationRequest(InstrumentProfile.DEFAULT_D_KURD_9),
            "exercise-impossible"
        )

        assertTrue(malformed is ScoreIngestionResult.Rejected)
        assertTrue(impossible is ScoreIngestionResult.Rejected)
    }

    @Test
    fun adaptedMidiProducesPlayableExerciseAndPreservesProvenance() = runBlocking {
        val store = MemoryStore()
        val result = ScoreIngestionUseCase(store = store).ingestAndAdapt(
            ScoreSource.BinaryMidi("adaptable", midiBytes(note = 62)),
            AdaptationRequest(InstrumentProfile.DEFAULT_D_KURD_9),
            "exercise-adapted",
            "Imported exercise"
        )

        assertTrue(result is ScoreIngestionResult.Adapted)
        val adapted = result as ScoreIngestionResult.Adapted
        assertEquals("adaptable", adapted.arrangement.decisions.single().sourceProvenance?.sourceId)
        assertEquals("exercise-adapted", store.patternId)
        assertEquals(1, adapted.pattern.activeNotes.size)
        assertEquals(AdaptationApproval.APPROVED, adapted.record.adaptationApproval)
    }

    @Test
    fun partialAdaptationIsPendingAndDoesNotPersistPlayableExercise() = runBlocking {
        val store = MemoryStore()
        val result = ScoreIngestionUseCase(store = store).ingestAndAdapt(
            ScoreSource.BinaryMidi("partial", simultaneousMidiBytes()),
            AdaptationRequest(InstrumentProfile.DEFAULT_D_KURD_9, maxSimultaneousNotes = 1),
            "exercise-partial"
        )

        assertTrue(result is ScoreIngestionResult.Partial)
        val partial = result as ScoreIngestionResult.Partial
        val provenance = requireNotNull(store.record?.provenance)
        val sourceEventIds = partial.arrangement?.decisions?.map { it.sourceEventId } ?: emptyList()
        assertEquals(AdaptationApproval.PENDING, store.record?.adaptationApproval)
        assertEquals(null, partial.record.exerciseId)
        assertEquals(null, store.patternId)
        assertEquals(2, sourceEventIds.size)
        assertEquals(2, provenance.size)
        assertEquals(2, sourceEventIds.toSet().size)
        assertTrue(provenance.all { it.sourceId == "partial" })
        assertEquals(sourceEventIds.toSet(), provenance.mapNotNull { it.sourceEventId }.toSet())
    }

    @Test
    fun approvedPartialAdaptationPersistsPlayableExerciseAndApprovalSurvivesReload() = runBlocking {
        val store = MemoryStore()
        val pending = ScoreIngestionUseCase(store = store).ingestAndAdapt(
            ScoreSource.BinaryMidi("approve", simultaneousMidiBytes()),
            AdaptationRequest(InstrumentProfile.DEFAULT_D_KURD_9, maxSimultaneousNotes = 1),
            "exercise-approved"
        ) as ScoreIngestionResult.Partial

        val approved = ScoreIngestionUseCase(store = store).approvePartialAdaptation(pending)

        assertTrue(approved is ScoreIngestionResult.Adapted)
        assertEquals("exercise-approved", store.patternId)
        assertEquals(AdaptationApproval.APPROVED, store.record?.adaptationApproval)
        assertEquals(
            AdaptationApproval.APPROVED,
            com.example.data.local.ImportedScoreEntity.fromRecord(store.record!!).toRecord().adaptationApproval
        )
    }

    @Test
    fun rejectedPartialAndImpossibleAdaptationsNeverPersistPlayableExercise() = runBlocking {
        val store = MemoryStore()
        val pending = ScoreIngestionUseCase(store = store).ingestAndAdapt(
            ScoreSource.BinaryMidi("reject", simultaneousMidiBytes()),
            AdaptationRequest(InstrumentProfile.DEFAULT_D_KURD_9, maxSimultaneousNotes = 1),
            "exercise-rejected"
        ) as ScoreIngestionResult.Partial

        val rejected = ScoreIngestionUseCase(store = store).rejectPartialAdaptation(pending)
        val impossible = ScoreIngestionUseCase(store = store).ingestAndAdapt(
            ScoreSource.BinaryMidi("impossible", midiBytes(note = 61)),
            AdaptationRequest(InstrumentProfile.DEFAULT_D_KURD_9),
            "exercise-impossible"
        )

        assertTrue(rejected is ScoreIngestionResult.Rejected)
        assertTrue(impossible is ScoreIngestionResult.Rejected)
        assertEquals(null, store.patternId)
        assertEquals(AdaptationApproval.REJECTED, store.record?.adaptationApproval)
    }

    @Test
    fun unknownUnavailableAdaptationIsRejectedWithoutPlayableExercise() = runBlocking {
        val store = MemoryStore()
        val result = ScoreIngestionUseCase(store = store).ingestAndAdapt(
            ScoreSource.MusicXml("unknown", musicXmlWithoutPitch()),
            AdaptationRequest(InstrumentProfile.DEFAULT_D_KURD_9),
            "exercise-unknown"
        )

        assertTrue(result is ScoreIngestionResult.Rejected)
        assertEquals(null, store.patternId)
        assertEquals(AdaptationApproval.REJECTED, store.record?.adaptationApproval)
    }

    @Test
    fun pdfAndImageWithoutOmrReturnExplicitUnsupported() = runBlocking {
        val pdf = FakePdfScoreSource("pdf-source")
        val useCase = ScoreIngestionUseCase()
        val pdfResult = useCase.ingest(ScoreSource.Pdf("pdf-source", pdf))
        val imageResult = useCase.ingest(ScoreSource.Image("image-source", ImageScoreSource.fromBytes(byteArrayOf(1), "image-source")))

        assertTrue(pdfResult is ScoreIngestionResult.Unsupported)
        assertTrue(imageResult is ScoreIngestionResult.Unsupported)
        pdf.close()
    }

    private fun detected(bytes: ByteArray): DetectedScoreFormat =
        (ScoreFormatDetector.detect(bytes) as FormatDetectionResult.Detected).format

    private fun midiBytes(note: Int = 62): ByteArray {
        val track = byteArrayOf(0, 0x90.toByte(), note.toByte(), 100, 0x60, 0x80.toByte(), note.toByte(), 0, 0, 0xFF.toByte(), 0x2F, 0)
        return byteArrayOf(
            0x4D, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 0, 0, 1, 0, 96,
            0x4D, 0x54, 0x72, 0x6B, 0, 0, 0, track.size.toByte(), *track
        )
    }

    private fun simultaneousMidiBytes(): ByteArray {
        val track = byteArrayOf(
            0, 0x90.toByte(), 62, 100,
            0, 0x90.toByte(), 64, 100,
            0x60, 0x80.toByte(), 62, 0,
            0, 0x80.toByte(), 64, 0,
            0, 0xFF.toByte(), 0x2F, 0
        )
        return byteArrayOf(
            0x4D, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 0, 0, 1, 0, 96,
            0x4D, 0x54, 0x72, 0x6B, 0, 0, 0, track.size.toByte(), *track
        )
    }

    private fun musicXmlWithoutPitch() = """
        <?xml version="1.0" encoding="UTF-8"?>
        <score-partwise version="4.0">
            <part-list><score-part id="P1"><part-name>Unknown</part-name></score-part></part-list>
            <part id="P1"><measure number="1">
                <attributes><divisions>1</divisions></attributes>
                <note><duration>1</duration><voice>1</voice></note>
            </measure></part>
        </score-partwise>
    """.trimIndent()

        private fun minimalMusicXml() = """
                <?xml version="1.0" encoding="UTF-8"?>
                <score-partwise version="4.0">
                    <work><work-title>Pipeline</work-title></work>
                    <part-list><score-part id="P1"><part-name>Handpan</part-name></score-part></part-list>
                    <part id="P1"><measure number="1">
                        <attributes><divisions>1</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>
                        <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><voice>1</voice></note>
                    </measure></part>
                </score-partwise>
        """.trimIndent()

    private class MemoryStore : ScoreIngestionStore {
        var record: ImportedScoreRecord? = null
        var patternId: String? = null
        override suspend fun saveImportedScore(record: ImportedScoreRecord) { this.record = record }
        override suspend fun saveImportedExercise(record: ImportedScoreRecord, pattern: com.example.model.HandpanPattern) {
            this.record = record
            patternId = pattern.id
        }
    }

    private class FakePdfScoreSource(sourceId: String) : PdfScoreSource, Closeable {
        override val identity = com.example.model.ScoreSourceIdentity(sourceId, "pdf-hash", com.example.model.MusicalProvenance(sourceId))
        override val pageCount = 1
        override fun pages() = listOf(PdfPage(identity.copy(pageIndex = 0), 0))
        override fun rasterize(pageNumber: Int): PdfRasterizationResult = PdfRasterizationResult.Failure(pages().single(), "not reached")
        override fun close() = Unit
    }
}