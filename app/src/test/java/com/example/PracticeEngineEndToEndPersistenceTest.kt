package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioAnalysisSession
import com.example.audio.AudioEngine
import com.example.audio.DetectedPitchResult
import com.example.audio.PracticeEngine
import com.example.audio.PracticePhase
import com.example.data.local.AppDatabase
import com.example.data.local.EvidenceEntity
import com.example.data.repository.HandpanRepository
import com.example.model.AssessmentSessionValidity
import com.example.model.DetectedStrikeEvent
import com.example.model.HandpanPattern
import com.example.model.NoteEvent
import com.example.model.NotePitchConfig
import com.example.model.PracticeInputMode
import com.example.model.TimingPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class EndToEndAudioAnalysisSession : AudioAnalysisSession() {
    var sessionId: String? = null
        private set
    private var onStrike: ((DetectedStrikeEvent) -> Unit)? = null

    override fun acquire(
        scaleConfig: NotePitchConfig,
        onStrike: (DetectedStrikeEvent) -> Unit,
        onPitch: (DetectedPitchResult) -> Unit,
        sessionId: String
    ): Subscription {
        this.sessionId = sessionId
        this.onStrike = onStrike
        return Subscription({ this@EndToEndAudioAnalysisSession.onStrike = null })
    }

    fun emitStrike(noteNumber: Int) {
        val id = sessionId ?: error("Audio analysis session was not acquired")
        onStrike?.invoke(
            DetectedStrikeEvent(
                id = "$id-${System.nanoTime()}",
                sessionId = id,
                monotonicTimestampNanos = System.nanoTime(),
                detectedFrequencyHz = NotePitchConfig.D_KURD_9.getFrequency(noteNumber),
                detectedNoteName = NotePitchConfig.D_KURD_9.getPitchName(noteNumber),
                detectedCentsOffset = 0,
                detectedNote = noteNumber,
                matchedPitchDiffHz = 0f,
                pitchConfidence = 0.95f,
                onsetStrength = 0.9f,
                energy = 0.9f,
                pitchValid = true,
                onsetConfidence = 0.95f,
                signalQuality = 0.95f
            )
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PracticeEngineEndToEndPersistenceTest {
    private var database: AppDatabase? = null

    @After
    fun tearDown() {
        database?.close()
    }

    @Test
    fun naturalCompletionFinalizesThroughEngineCallbackAndPersistsAssessment() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database = room
        val repository = HandpanRepository(
            room.patternDao(),
            room.practiceProgressDao(),
            room.lessonProgressDao(),
            room.recordingTrackDao(),
            room
        )
        val analysisSession = EndToEndAudioAnalysisSession()
        val engine = PracticeEngine(
            audioEngine = AudioEngine(null),
            acousticEvaluator = com.example.audio.AcousticPracticeEvaluator(
                analysisSession = analysisSession,
                ownsAnalysisSession = false
            )
        )
        val pattern = HandpanPattern(
            id = "end-to-end-pattern",
            title = "End to end",
            description = "Persistence integration",
            bpm = 180,
            bars = 1,
            events = listOf(
                NoteEvent(noteNumber = 1, beatPosition = 1.0, duration = 0.25, velocity = 0.8f),
                NoteEvent(noteNumber = 1, beatPosition = 3.5, duration = 0.25, velocity = 0.8f)
            )
        )
        var callbackCount = 0
        var callbackSessionId: String? = null
        engine.onAssessmentFinalized = { assessment ->
            callbackCount++
            callbackSessionId = assessment.sessionId
            val evidence = EvidenceEntity.fromDomain(
                sessionId = assessment.sessionId,
                validEvidenceCount = assessment.quality.validEventCount,
                validity = assessment.quality.validity.name,
                metrics = assessment.metrics
            )
            runBlocking { repository.persistFinalizedAssessment(assessment, evidence) }
        }

        engine.loadPattern(pattern)
        engine.setInputMode(PracticeInputMode.REAL_HANDPAN)
        engine.setPreviewEnabled(false)
        engine.toggleCountIn()
        engine.toggleLoop()
        engine.acousticEvaluator.setTimingPolicy(
            TimingPolicy(
                earlyWindowNanos = 500_000_000L,
                lateWindowNanos = 500_000_000L,
                perfectWindowNanos = 500_000_000L,
                goodWindowNanos = 500_000_000L
            )
        )
        engine.play()

        while (analysisSession.sessionId == null) delay(10)
        repeat(32) {
            analysisSession.emitStrike(1)
            delay(50)
        }

        val completed = waitForCompletion(engine)
        assertTrue(completed)
        assertEquals(PracticePhase.COMPLETED, engine.uiState.value.phase)
        val finalizedAfterCompletion = engine.acousticEvaluator.finalizedAssessment(System.currentTimeMillis())
        assertNotNull(finalizedAfterCompletion)
        assertEquals(1, callbackCount)
        assertNotNull(callbackSessionId)
        assertEquals(analysisSession.sessionId, callbackSessionId)

        engine.stop()
        assertEquals(1, callbackCount)

        val assessment = repository.getAssessment(callbackSessionId!!)
        val evidence = repository.getEvidence(callbackSessionId!!)
        val processed = room.processedAssessmentDao().find(callbackSessionId!!)
        val progress = repository.allProgress.firstValue()[pattern.id]

        assertNotNull(assessment)
        assertNotNull(evidence)
        assertEquals(callbackSessionId, processed)
        assertEquals(AssessmentSessionValidity.VALID.name, assessment?.validity)
        assertEquals(1, progress?.practiceCount)
        assertEquals(1, progress?.completedRounds)
        assertTrue(room.masteredSkillDao().getAll().isNotEmpty())

        val duplicate = repository.persistFinalizedAssessment(
            assessment = callbackAssessment(engine),
            evidence = evidence!!
        )
        assertEquals(false, duplicate)
        assertEquals(1, room.assessmentDao().observeAll().firstValue().count { it.sessionId == callbackSessionId })
        assertEquals(1, progressCount(repository, pattern.id))

        engine.release()
    }

    private suspend fun waitForCompletion(engine: PracticeEngine): Boolean {
        repeat(100) {
            if (engine.uiState.value.phase == PracticePhase.COMPLETED) return true
            delay(50)
        }
        return false
    }

    private fun callbackAssessment(engine: PracticeEngine) =
        engine.acousticEvaluator.finalizedAssessment(System.currentTimeMillis())
            ?: error("Expected finalized assessment")

    private suspend fun progressCount(repository: HandpanRepository, patternId: String): Int =
        repository.allProgress.firstValue()[patternId]?.practiceCount ?: 0
}

private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = first()