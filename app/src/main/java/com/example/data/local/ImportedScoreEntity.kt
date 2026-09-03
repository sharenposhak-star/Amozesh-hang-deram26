package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.DetectedScoreFormat
import com.example.model.ImportedScoreRecord

@Entity(tableName = "imported_scores")
data class ImportedScoreEntity(
    @PrimaryKey val sourceId: String,
    val sourceHash: String,
    val title: String?,
    val composer: String?,
    val provenanceJson: String,
    val format: String,
    val importedAtEpochMs: Long,
    val recognitionStatus: String,
    val confidence: Double,
    val pageCount: Int?,
    val validationStatus: String,
    val timelineJson: String,
    val exerciseId: String?,
    val adaptationStatus: String?,
    val adaptationConfidence: Double?,
    val omittedRatio: Double?,
    val transformedRatio: Double?
) {
    companion object {
        fun fromRecord(record: ImportedScoreRecord) = ImportedScoreEntity(
            sourceId = record.sourceId,
            sourceHash = record.sourceHash,
            title = record.title,
            composer = record.composer,
            provenanceJson = record.provenance.joinToString("|") { it.sourceLocation ?: it.sourceId },
            format = record.format.name,
            importedAtEpochMs = record.importedAtEpochMs,
            recognitionStatus = record.recognitionStatus.name,
            confidence = record.confidence,
            pageCount = record.pageCount,
            validationStatus = record.validationStatus,
            timelineJson = record.timelineJson,
            exerciseId = record.exerciseId,
            adaptationStatus = record.adaptationStatus,
            adaptationConfidence = record.adaptationConfidence,
            omittedRatio = record.omittedRatio,
            transformedRatio = record.transformedRatio
        )
    }

    fun toRecord() = ImportedScoreRecord(
        sourceId, sourceHash, title, composer,
        provenanceJson.split('|').filter { it.isNotBlank() }.map {
            com.example.model.MusicalProvenance(sourceId = sourceId, sourceLocation = it)
        },
        runCatching { DetectedScoreFormat.valueOf(format) }.getOrDefault(DetectedScoreFormat.UNKNOWN),
        importedAtEpochMs,
        runCatching { com.example.model.RecognitionStatus.valueOf(recognitionStatus) }
            .getOrDefault(com.example.model.RecognitionStatus.REJECTED),
        confidence, pageCount, validationStatus, timelineJson, exerciseId,
        adaptationStatus, adaptationConfidence, omittedRatio, transformedRatio
    )
}