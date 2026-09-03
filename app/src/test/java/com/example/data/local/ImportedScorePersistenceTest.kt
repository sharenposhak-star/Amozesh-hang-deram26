package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.model.DetectedScoreFormat
import com.example.model.AdaptationApproval
import com.example.model.ImportedScoreRecord
import com.example.model.MusicalProvenance
import com.example.model.RecognitionStatus
import com.example.model.MusicalProvenance
import com.example.model.NormalizedMusicalEvent
import com.example.model.NormalizedMusicalTimeline
import com.example.model.TimelineDecodeResult
import com.example.model.TempoChange
import com.example.model.TimeSignature
import com.example.model.TimeSignatureChange
import com.example.model.toCanonicalJson
import com.example.model.decodeTimeline
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImportedScorePersistenceTest {
    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() = db.close()

    @Test
    fun importedScoreMetadataAndCanonicalTimelinePersist() = runBlocking {
        val timeline = NormalizedMusicalTimeline(
            sourceId = "source-1",
            sourceHash = "hash-1",
            tempoMap = listOf(TempoChange(0.0, 100.0)),
            timeSignatureMap = listOf(TimeSignatureChange(0.0, TimeSignature.Common44)),
            keySignatureMap = emptyList(),
            events = listOf(
                NormalizedMusicalEvent("event-1", 0.0, 1.0, 0.0, 1, 0.0, null, null, null, null, null, true, "voice", null, null,
                    MusicalProvenance("source-1", "track-1", "event-1", "page=0"))
            ),
            provenance = listOf(MusicalProvenance("source-1", "track-1", "event-1", "page=0")),
            trackIds = listOf("track-1")
        )
        val record = ImportedScoreRecord(
            sourceId = "source-1",
            sourceHash = "hash-1",
            title = "Title",
            composer = "Composer",
            provenance = listOf(MusicalProvenance("source-1", sourceLocation = "track=0")),
            format = DetectedScoreFormat.MIDI_BINARY,
            importedAtEpochMs = 42L,
            recognitionStatus = RecognitionStatus.RECOGNIZED,
            confidence = 1.0,
            pageCount = null,
            validationStatus = "VALID",
            timelineJson = timeline.toCanonicalJson(),
            exerciseId = "exercise-1",
            adaptationStatus = "EXACT",
            adaptationConfidence = 1.0,
            omittedRatio = 0.0,
            transformedRatio = 0.0,
            adaptationApproval = AdaptationApproval.APPROVED
        )

        db.importedScoreDao().insert(ImportedScoreEntity.fromRecord(record))
        val restored = db.importedScoreDao().getBySourceId("source-1")

        assertNotNull(restored)
        assertEquals("hash-1", restored?.sourceHash)
        assertEquals("exercise-1", restored?.exerciseId)
        val restoredTimeline = (restored!!.toRecord().decodeTimeline() as TimelineDecodeResult.Success).timeline
        assertEquals(timeline, restoredTimeline)
        assertEquals(AdaptationApproval.APPROVED, restored?.toRecord()?.adaptationApproval)
        assertEquals(record.provenance, restored?.toRecord()?.provenance)
        assertEquals(record.sourceId, restored?.toRecord()?.sourceId)
        assertEquals(record.sourceHash, restored?.toRecord()?.sourceHash)
    }
}