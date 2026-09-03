package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportedScoreDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ImportedScoreEntity)

    @Query("SELECT * FROM imported_scores WHERE sourceId = :sourceId LIMIT 1")
    suspend fun getBySourceId(sourceId: String): ImportedScoreEntity?

    @Query("SELECT * FROM imported_scores ORDER BY importedAtEpochMs DESC")
    fun observeAll(): Flow<List<ImportedScoreEntity>>
}