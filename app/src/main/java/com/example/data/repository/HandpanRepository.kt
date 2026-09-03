package com.example.data.repository

import com.example.data.builtin.BuiltinExercises
import com.example.data.local.PatternDao
import com.example.data.local.PatternEntity
import com.example.data.local.PracticeProgressDao
import com.example.data.local.PracticeProgressEntity
import com.example.data.local.toEntity
import com.example.data.local.toDomain
import com.example.data.local.toDomainOrNull
import com.example.model.HandpanPattern
import com.example.model.PatternCategory
import com.example.model.PracticeProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.local.AssessmentEntity
import com.example.data.local.EvidenceEntity
import com.example.data.local.ProcessedAssessmentEntity
import com.example.model.FinalizedAssessment
import com.example.data.local.MasteredSkillEntity
import com.example.model.LearningSkill
import com.example.model.MasteredSkillState
import com.example.model.MasteredSkillUpdater
import com.example.model.PersonalizationEngine
import com.example.model.LearningRecommendation
import com.example.model.ImportedScoreRecord
import com.example.model.ScoreIngestionStore
import com.example.data.local.ImportedScoreEntity

class HandpanRepository(
    private val patternDao: PatternDao,
    private val practiceProgressDao: PracticeProgressDao,
    private val lessonProgressDao: com.example.data.local.LessonProgressDao,
    private val recordingTrackDao: com.example.data.local.RecordingTrackDao,
    private val database: AppDatabase? = null
) : ScoreIngestionStore {
    /**
     * Flow of all available patterns: Built-in + User custom patterns.
     */
    val allPatterns: Flow<List<HandpanPattern>> = patternDao.getCustomPatterns().map { customEntities ->
        val customPatterns = customEntities.map { it.toDomain() }
        BuiltinExercises.ALL_BUILTIN_PATTERNS + customPatterns
    }

    /**
     * Get custom patterns created by user.
     */
    val customPatterns: Flow<List<HandpanPattern>> = patternDao.getCustomPatterns().map { list ->
        list.map { it.toDomain() }
    }

    /**
     * Get all practice progress records.
     */
    val allProgress: Flow<Map<String, PracticeProgress>> = practiceProgressDao.getAllProgress().map { list ->
        list.associate { it.patternId to it.toDomain() }
    }

    /**
     * Flow of all lesson progress mapped by lessonId.
     */
    val allLessonProgress: Flow<Map<String, com.example.data.local.LessonProgressEntity>> = 
        lessonProgressDao.getAllLessonProgress().map { list ->
            list.associateBy { it.lessonId }
        }

    /**
     * Flow of all recorded tracks sorted chronologically.
     */
    val allRecordedTracks: Flow<List<com.example.audio.RecordedTrack>> = 
        recordingTrackDao.getAllRecordingTracks().map { list ->
            list.mapNotNull { it.toDomainOrNull() }
        }

    val allMasteredSkills: Flow<List<MasteredSkillState>> =
        database?.masteredSkillDao()?.observeAll()?.map { states -> states.map { it.toDomain() } }
            ?: kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun getNextRecommendation(recentPatternIds: Set<String> = emptySet()): LearningRecommendation {
        val states = database?.masteredSkillDao()?.getAll()?.map { it.toDomain() } ?: emptyList()
        return PersonalizationEngine.recommend(states, recentPatternIds)
    }

    suspend fun getPatternById(id: String): HandpanPattern? {
        val builtin = BuiltinExercises.ALL_BUILTIN_PATTERNS.find { it.id == id }
        if (builtin != null) return builtin

        val custom = patternDao.getPatternById(id)
        return custom?.toDomain()
    }

    suspend fun saveCustomPattern(pattern: HandpanPattern) {
        val customPattern = pattern.copy(isCustom = true, category = PatternCategory.CUSTOM)
        patternDao.insertPattern(PatternEntity.fromDomain(customPattern))
    }

    suspend fun deleteCustomPattern(id: String) {
        patternDao.deletePatternById(id)
    }

    override suspend fun saveImportedScore(record: ImportedScoreRecord) {
        checkNotNull(database) { "Imported score persistence requires a database" }
            .importedScoreDao().insert(ImportedScoreEntity.fromRecord(record))
    }

    override suspend fun saveImportedExercise(record: ImportedScoreRecord, pattern: HandpanPattern) {
        val db = checkNotNull(database) { "Imported exercise persistence requires a database" }
        db.withTransaction {
            patternDao.insertPattern(PatternEntity.fromDomain(pattern.copy(isCustom = true, category = PatternCategory.CUSTOM)))
            db.importedScoreDao().insert(ImportedScoreEntity.fromRecord(record))
        }
    }

    suspend fun getImportedScore(sourceId: String): ImportedScoreRecord? =
        database?.importedScoreDao()?.getBySourceId(sourceId)?.toRecord()

    suspend fun recordPracticeSession(patternId: String, currentBpm: Int, elapsedSeconds: Int) {
        val existing = practiceProgressDao.getProgressForPattern(patternId)?.toDomain()
        val updated = if (existing != null) {
            existing.copy(
                practiceCount = existing.practiceCount + 1,
                lastPracticedTimestamp = System.currentTimeMillis(),
                highestBpmAchieved = maxOf(existing.highestBpmAchieved, currentBpm),
                lastUsedBpm = currentBpm,
                totalTimeSeconds = existing.totalTimeSeconds + elapsedSeconds,
                completedRounds = existing.completedRounds + 1
            )
        } else {
            PracticeProgress(
                patternId = patternId,
                practiceCount = 1,
                lastPracticedTimestamp = System.currentTimeMillis(),
                highestBpmAchieved = currentBpm,
                lastUsedBpm = currentBpm,
                totalTimeSeconds = elapsedSeconds,
                completedRounds = 1
            )
        }
        practiceProgressDao.saveProgress(PracticeProgressEntity.fromDomain(updated))
    }

    suspend fun getLessonProgress(lessonId: String): com.example.data.local.LessonProgressEntity? {
        return lessonProgressDao.getProgressForLesson(lessonId)
    }

    fun observeLessonProgress(lessonId: String): Flow<com.example.data.local.LessonProgressEntity?> {
        return lessonProgressDao.observeProgressForLesson(lessonId)
    }

    suspend fun saveLessonProgress(lessonId: String, score: Int, stars: Int, isCompleted: Boolean = true) {
        val existing = lessonProgressDao.getProgressForLesson(lessonId)
        val updated = if (existing != null) {
            existing.copy(
                isCompleted = isCompleted || existing.isCompleted,
                stars = maxOf(existing.stars, stars),
                bestScore = maxOf(existing.bestScore, score),
                attempts = existing.attempts + 1,
                lastPracticedAt = System.currentTimeMillis()
            )
        } else {
            com.example.data.local.LessonProgressEntity(
                lessonId = lessonId,
                isCompleted = isCompleted,
                stars = stars,
                bestScore = score,
                attempts = 1,
                lastPracticedAt = System.currentTimeMillis()
            )
        }
        lessonProgressDao.saveLessonProgress(updated)
    }

    suspend fun deleteLessonProgress(lessonId: String) {
        lessonProgressDao.deleteLessonProgress(lessonId)
    }

    suspend fun saveRecordingTrack(track: com.example.audio.RecordedTrack) {
        recordingTrackDao.insertRecordingTrack(track.toEntity())
    }

    suspend fun deleteRecordingTrack(id: String) {
        recordingTrackDao.deleteRecordingTrackById(id)
    }

    suspend fun getRecordingTrackById(id: String): com.example.audio.RecordedTrack? {
        return recordingTrackDao.getRecordingTrackById(id)?.toDomain()
    }

    suspend fun persistFinalizedAssessment(
        assessment: FinalizedAssessment,
        evidence: EvidenceEntity
    ): Boolean {
        require(assessment.sessionId == evidence.sessionId)
        require(assessment.quality.validity == com.example.model.AssessmentSessionValidity.VALID)
        val db = database ?: return false
        return db.withTransaction {
            val inserted = db.assessmentDao().insertIgnore(AssessmentEntity.fromDomain(assessment))
            if (inserted == -1L) {
                false
            } else {
                db.evidenceDao().insertIgnore(evidence)
                db.processedAssessmentDao().insertIgnore(ProcessedAssessmentEntity(assessment.sessionId))
                val masteredSkillDao = db.masteredSkillDao()
                val practicedAt = assessment.completedAtEpochMs
                MasteredSkillUpdater.evidenceFrom(assessment.metrics).forEach { (skill, evidence) ->
                    val existing = masteredSkillDao.get(skill.name)?.toDomain()
                        ?: MasteredSkillState(skill = skill)
                    masteredSkillDao.save(
                        MasteredSkillEntity.fromDomain(
                            MasteredSkillUpdater.update(existing, evidence, practicedEpochMs = practicedAt)
                        )
                    )
                }
                recordPracticeSession(
                    patternId = assessment.patternId,
                    currentBpm = assessment.bpm,
                    elapsedSeconds = (assessment.quality.activeDurationMs / 1_000L).toInt()
                )
                true
            }
        }
    }

    suspend fun getAssessment(sessionId: String): AssessmentEntity? =
        database?.assessmentDao()?.getBySessionId(sessionId)

    suspend fun getEvidence(sessionId: String): EvidenceEntity? =
        database?.evidenceDao()?.getBySessionId(sessionId)
}
