package com.example

import com.example.model.ImageScoreSource
import com.example.model.MusicalProvenance
import com.example.model.MusicalPitch
import com.example.model.NormalizedMusicalTimeline
import com.example.model.PdfPage
import com.example.model.PdfRasterizationResult
import com.example.model.PdfScoreSource
import com.example.model.RecognitionResult
import com.example.model.RecognitionScoreValidator
import com.example.model.RecognitionStatus
import com.example.model.ScoreSourceIdentity
import com.example.model.ScoreValidationResult
import com.example.model.SourceMetadata
import com.example.model.SymbolicMusicalEvent
import com.example.model.SymbolicScore
import com.example.model.SymbolicSourceFormat
import com.example.model.SymbolicTrack
import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreIngestionFoundationTest {
    @Test
    fun imageIdentityIsDeterministicAndPageAware() {
        val first = ImageScoreSource.fromBytes(byteArrayOf(1, 2, 3), "scan", 0)
        val second = ImageScoreSource.fromBytes(byteArrayOf(1, 2, 3), "scan", 0)
        val nextPage = ImageScoreSource.fromBytes(byteArrayOf(1, 2, 3), "scan", 1)

        assertEquals(first.identity.sourceHash, second.identity.sourceHash)
        assertEquals(first.identity, second.identity)
        assertNotEquals(first.identity, nextPage.identity)
    }

    @Test
    fun pdfPagesHaveDeterministicOrderAndRenderingFailureIsExplicit() {
        val source = FakePdfScoreSource("pdf", listOf(false, true))

        assertEquals(listOf(0, 1), source.pages().map { it.pageNumber })
        val result = source.rasterize(1)
        assertTrue(result is PdfRasterizationResult.Failure)
        assertEquals(1, (result as PdfRasterizationResult.Failure).page.pageNumber)
    }

    @Test
    fun unresolvedRecognitionCannotEnterNormalizedTimeline() {
        val identity = ScoreSourceIdentity("scan", "hash", MusicalProvenance("scan"))
        val result = RecognitionResult(identity, RecognitionStatus.UNCERTAIN, confidence = 0.42, reason = "staff is unclear")

        val validation = RecognitionScoreValidator.validate(result)

        assertTrue(validation is ScoreValidationResult.Invalid)
    }

    @Test
    fun recognizedScoreIsNormalizedButMalformedRecognitionIsRejected() {
        val identity = ScoreSourceIdentity("scan", "hash", MusicalProvenance("scan"))
        val event = SymbolicMusicalEvent(
            eventId = "event-1",
            beatPosition = 0.0,
            durationBeats = 1.0,
            pitch = MusicalPitch(60),
            provenance = MusicalProvenance("scan"),
            tie = "invalid"
        )
        val score = SymbolicScore(
            metadata = SourceMetadata("scan", format = SymbolicSourceFormat.IMAGE),
            tempoMap = emptyList(),
            timeSignatureMap = emptyList(),
            keySignatureMap = emptyList(),
            tracks = listOf(SymbolicTrack("track-1", events = listOf(event)))
        )

        val recognized = RecognitionScoreValidator.validate(
            RecognitionResult(identity, RecognitionStatus.RECOGNIZED, score, confidence = 0.95)
        )
        val rejected = RecognitionScoreValidator.validate(
            RecognitionResult(identity, RecognitionStatus.REJECTED, confidence = 0.0, reason = "no staff")
        )

        assertTrue(recognized is ScoreValidationResult.Invalid)
        assertTrue(rejected is ScoreValidationResult.Invalid)
        assertTrue(NormalizedMusicalTimeline.from(score).events.single().pitch != null)
    }

    private class FakePdfScoreSource(
        sourceId: String,
        private val failures: List<Boolean>
    ) : PdfScoreSource, Closeable {
        override val identity = ScoreSourceIdentity(sourceId, "pdf-hash", MusicalProvenance(sourceId, sourceLocation = "pdf"))
        override val pageCount: Int get() = failures.size

        override fun pages(): List<PdfPage> = failures.indices.map { index ->
            PdfPage(identity.copy(pageIndex = index), index)
        }

        override fun rasterize(pageNumber: Int): PdfRasterizationResult {
            val page = pages()[pageNumber]
            return if (failures[pageNumber]) {
                PdfRasterizationResult.Failure(page, "test rendering failure")
            } else {
                PdfRasterizationResult.Failure(page, "test source has no renderer")
            }
        }

        override fun close() = Unit
    }
}