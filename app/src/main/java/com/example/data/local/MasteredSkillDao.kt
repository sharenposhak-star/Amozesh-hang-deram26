package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MasteredSkillDao {
    @Query("SELECT * FROM mastered_skills ORDER BY skill ASC")
    fun observeAll(): Flow<List<MasteredSkillEntity>>

    @Query("SELECT * FROM mastered_skills ORDER BY skill ASC")
    suspend fun getAll(): List<MasteredSkillEntity>

    @Query("SELECT * FROM mastered_skills WHERE skill = :skill LIMIT 1")
    suspend fun get(skill: String): MasteredSkillEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(state: MasteredSkillEntity)
}
