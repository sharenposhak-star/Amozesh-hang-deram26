package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.DetectedScoreFormat
import com.example.model.ImportedScoreRecord
import com.example.model.AdaptationApproval
import com.example.model.MusicalProvenance
import org.json.JSONArray
import org.json.JSONObject

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
    val transformedRatio: Double?,
    val adaptationApproval: String
) {
    companion object {
        fun fromRecord(record: ImportedScoreRecord) = ImportedScoreEntity(
            sourceId = record.sourceId,
            sourceHash = record.sourceHash,
            title = record.title,
            composer = record.composer,
            provenanceJson = JSONArray().apply {
                record.provenance.forEach { provenance ->
                    put(JSONObject().apply {
                        put("sourceId", provenance.sourceId)
                        put("sourceTrackId", provenance.sourceTrackId ?: JSONObject.NULL)
                        put("sourceEventId", provenance.sourceEventId ?: JSONObject.NULL)
                        put("sourceLocation", provenance.sourceLocation ?: JSONObject.NULL)
                    })
                }
            }.toString(),
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
            transformedRatio = record.transformedRatio,
            adaptationApproval = record.adaptationApproval.name
        )
    }

    fun toRecord() = ImportedScoreRecord(
        sourceId, sourceHash, title, composer,
        parseProvenance(provenanceJson, sourceId),
        runCatching { DetectedScoreFormat.valueOf(format) }.getOrDefault(DetectedScoreFormat.UNKNOWN),
        importedAtEpochMs,
        runCatching { com.example.model.RecognitionStatus.valueOf(recognitionStatus) }
            .getOrDefault(com.example.model.RecognitionStatus.REJECTED),
        confidence, pageCount, validationStatus, timelineJson, exerciseId,
        adaptationStatus, adaptationConfidence, omittedRatio, transformedRatio,
        runCatching { AdaptationApproval.valueOf(adaptationApproval) }
            .getOrDefault(AdaptationApproval.NOT_REQUIRED)
    )

    private fun parseProvenance(json: String, fallbackSourceId: String): List<MusicalProvenance> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            MusicalProvenance(
                sourceId = item.getString("sourceId"),
                sourceTrackId = item.optString("sourceTrackId").takeUnless { item.isNull("sourceTrackId") || it.isEmpty() },
                sourceEventId = item.optString("sourceEventId").takeUnless { item.isNull("sourceEventId") || it.isEmpty() },
                sourceLocation = item.optString("sourceLocation").takeUnless { item.isNull("sourceLocation") || it.isEmpty() }
            )
        }
    }.getOrElse {
        json.split('|').filter { it.isNotBlank() }.map { MusicalProvenance(fallbackSourceId, sourceLocation = it) }
    }
}