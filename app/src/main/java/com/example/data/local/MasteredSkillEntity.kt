package com.example.data.local

import androidx.room.Entity
import com.example.model.LearningSkill
import com.example.model.MasteredSkillState

@Entity(tableName = "mastered_skills")
data class MasteredSkillEntity(
    @androidx.room.PrimaryKey
    val skill: String,
    val masteryScore: Float,
    val confidence: Float,
    val recentPerformance: Float,
    val longTermPerformance: Float,
    val attemptCount: Int,
    val lastPracticedEpochMs: Long?,
    val trend: Float
) {
    fun toDomain() = MasteredSkillState(
        skill = LearningSkill.valueOf(skill),
        masteryScore = masteryScore,
        confidence = confidence,
        recentPerformance = recentPerformance,
        longTermPerformance = longTermPerformance,
        attemptCount = attemptCount,
        lastPracticedEpochMs = lastPracticedEpochMs,
        trend = trend
    )

    companion object {
        fun fromDomain(state: MasteredSkillState) = MasteredSkillEntity(
            skill = state.skill.name,
            masteryScore = state.masteryScore,
            confidence = state.confidence,
            recentPerformance = state.recentPerformance,
            longTermPerformance = state.longTermPerformance,
            attemptCount = state.attemptCount,
            lastPracticedEpochMs = state.lastPracticedEpochMs,
            trend = state.trend
        )
    }
}
