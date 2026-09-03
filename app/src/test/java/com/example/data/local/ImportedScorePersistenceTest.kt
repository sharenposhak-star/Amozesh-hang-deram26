package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.model.DetectedScoreFormat
import com.example.model.ImportedScoreRecord
import com.example.model.MusicalProvenance
import com.example.model.RecognitionStatus
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
            timelineJson = "{\"sourceId\":\"source-1\"}",
            exerciseId = "exercise-1",
            adaptationStatus = "EXACT",
            adaptationConfidence = 1.0,
            omittedRatio = 0.0,
            transformedRatio = 0.0
        )

        db.importedScoreDao().insert(ImportedScoreEntity.fromRecord(record))
        val restored = db.importedScoreDao().getBySourceId("source-1")

        assertNotNull(restored)
        assertEquals("hash-1", restored?.sourceHash)
        assertEquals("exercise-1", restored?.exerciseId)
        assertEquals("{\"sourceId\":\"source-1\"}", restored?.timelineJson)
        assertEquals("track=0", restored?.toRecord()?.provenance?.single()?.sourceLocation)
    }
}