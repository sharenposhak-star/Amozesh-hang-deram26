package com.example.model

import java.nio.charset.StandardCharsets

enum class DetectedScoreFormat {
    MIDI_BINARY,
    MUSIC_XML,
    PDF,
    IMAGE,
    UNKNOWN
}

sealed class FormatDetectionResult {
    data class Detected(val format: DetectedScoreFormat) : FormatDetectionResult()
    data class Unsupported(val reason: String) : FormatDetectionResult()
}

object ScoreFormatDetector {
    fun detect(bytes: ByteArray): FormatDetectionResult {
        if (bytes.isEmpty()) return FormatDetectionResult.Unsupported("Source is empty")
        if (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x4D, 0x54, 0x68, 0x64))) {
            return FormatDetectionResult.Detected(DetectedScoreFormat.MIDI_BINARY)
        }
        if (bytes.size >= 5 && bytes.copyOfRange(0, 5).toString(StandardCharsets.US_ASCII) == "%PDF-") {
            return FormatDetectionResult.Detected(DetectedScoreFormat.PDF)
        }
        if (bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) ||
            bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) ||
            bytes.startsWith("GIF8".encodeToByteArray()) ||
            bytes.startsWith("BM".encodeToByteArray()) ||
            bytes.startsWith("RIFF".encodeToByteArray()) && bytes.size >= 12 && bytes.copyOfRange(8, 12).contentEquals("WEBP".encodeToByteArray())
        ) {
            return FormatDetectionResult.Detected(DetectedScoreFormat.IMAGE)
        }
        val text = bytes.toString(StandardCharsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return when {
            text.contains("<score-partwise", ignoreCase = true) || text.contains("<score-timewise", ignoreCase = true) ->
                FormatDetectionResult.Detected(DetectedScoreFormat.MUSIC_XML)
            text.startsWith("<") -> FormatDetectionResult.Unsupported("XML source is not MusicXML")
            else -> FormatDetectionResult.Unsupported("Unsupported or ambiguous source format")
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size &&
        copyOfRange(0, prefix.size).contentEquals(prefix)
}

sealed class ScoreSource {
    abstract val sourceId: String

    data class BinaryMidi(override val sourceId: String, val bytes: ByteArray) : ScoreSource()
    data class MusicXml(override val sourceId: String, val text: String) : ScoreSource()
    data class Pdf(override val sourceId: String, val source: PdfScoreSource) : ScoreSource()
    data class Image(override val sourceId: String, val source: ImageScoreSource) : ScoreSource()
}

fun scoreSourceFromImportableBytes(sourceId: String, bytes: ByteArray): ScoreSource? {
    return when (val detection = ScoreFormatDetector.detect(bytes)) {
        is FormatDetectionResult.Detected -> when (detection.format) {
            DetectedScoreFormat.MIDI_BINARY -> ScoreSource.BinaryMidi(sourceId, bytes)
            DetectedScoreFormat.MUSIC_XML -> ScoreSource.MusicXml(sourceId, bytes.toString(StandardCharsets.UTF_8))
            else -> null
        }
        is FormatDetectionResult.Unsupported -> null
    }
}

enum class ScoreIngestionStatus { SUCCESS, PARTIAL, UNCERTAIN, REJECTED, UNSUPPORTED, FAILED }

sealed class ScoreIngestionError(open val message: String) {
    data class UnsupportedFormat(override val message: String) : ScoreIngestionError(message)
    data class InvalidScore(override val message: String) : ScoreIngestionError(message)
    data class RecognitionUncertain(override val message: String) : ScoreIngestionError(message)
    data class RecognitionRejected(override val message: String) : ScoreIngestionError(message)
    data class RenderingFailed(override val message: String) : ScoreIngestionError(message)
    data class ImportFailed(override val message: String) : ScoreIngestionError(message)
    data class AdaptationImpossible(override val message: String) : ScoreIngestionError(message)
}

data class ImportedScoreRecord(
    val sourceId: String,
    val sourceHash: String,
    val title: String?,
    val composer: String?,
    val provenance: List<MusicalProvenance>,
    val format: DetectedScoreFormat,
    val importedAtEpochMs: Long,
    val recognitionStatus: RecognitionStatus,
    val confidence: Double,
    val pageCount: Int?,
    val validationStatus: String,
    val timelineJson: String,
    val exerciseId: String? = null,
    val adaptationStatus: String? = null,
    val adaptationConfidence: Double? = null,
    val omittedRatio: Double? = null,
    val transformedRatio: Double? = null
)

interface ScoreIngestionStore {
    suspend fun saveImportedScore(record: ImportedScoreRecord)
    suspend fun saveImportedExercise(record: ImportedScoreRecord, pattern: HandpanPattern)
}

sealed class ScoreIngestionResult(val status: ScoreIngestionStatus) {
    data class Success(val timeline: NormalizedMusicalTimeline, val record: ImportedScoreRecord) : ScoreIngestionResult(ScoreIngestionStatus.SUCCESS)
    data class Partial(
        val timeline: NormalizedMusicalTimeline,
        val record: ImportedScoreRecord,
        val reasons: List<String>,
        val arrangement: HandpanArrangement? = null,
        val pattern: HandpanPattern? = null
    ) : ScoreIngestionResult(ScoreIngestionStatus.PARTIAL)
    data class Adapted(val timeline: NormalizedMusicalTimeline, val arrangement: HandpanArrangement, val pattern: HandpanPattern, val record: ImportedScoreRecord) : ScoreIngestionResult(ScoreIngestionStatus.SUCCESS)
    data class Uncertain(val error: ScoreIngestionError.RecognitionUncertain) : ScoreIngestionResult(ScoreIngestionStatus.UNCERTAIN)
    data class Rejected(val error: ScoreIngestionError) : ScoreIngestionResult(ScoreIngestionStatus.REJECTED)
    data class Unsupported(val error: ScoreIngestionError.UnsupportedFormat) : ScoreIngestionResult(ScoreIngestionStatus.UNSUPPORTED)
    data class Failed(val error: ScoreIngestionError) : ScoreIngestionResult(ScoreIngestionStatus.FAILED)
}

class ScoreIngestionUseCase(
    private val store: ScoreIngestionStore? = null,
    private val omrEngine: OmrEngine? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun ingest(source: ScoreSource): ScoreIngestionResult {
        val imported = when (source) {
            is ScoreSource.BinaryMidi -> importMidi(source)
            is ScoreSource.MusicXml -> importMusicXml(source)
            is ScoreSource.Pdf -> ingestPdf(source)
            is ScoreSource.Image -> ingestImage(source)
        }
        if (imported !is ScoreIngestionResult.Success) return imported
        store?.saveImportedScore(imported.record)
        return imported
    }

    suspend fun ingestAndAdapt(
        source: ScoreSource,
        request: AdaptationRequest,
        exerciseId: String,
        title: String? = null
    ): ScoreIngestionResult {
        val imported = ingest(source)
        if (imported !is ScoreIngestionResult.Success) return imported
        val arrangement = HandpanAdaptationSolver.adapt(imported.timeline, request)
        val blocked = arrangement.decisions.filter {
            it.status == AdaptationStatus.IMPOSSIBLE || it.status == AdaptationStatus.UNKNOWN_UNAVAILABLE
        }
        if (blocked.isNotEmpty()) {
            return ScoreIngestionResult.Rejected(
                ScoreIngestionError.AdaptationImpossible(
                    "${blocked.size} source event(s) cannot be adapted without silent omission"
                )
            )
        }
        val pattern = arrangement.toHandpanPattern(exerciseId, title ?: imported.timeline.title ?: "Adapted ${source.sourceId}")
        if (pattern.activeNotes.isEmpty()) {
            return ScoreIngestionResult.Rejected(ScoreIngestionError.AdaptationImpossible("Adaptation produced no playable notes"))
        }
        val quality = AdaptationQualityCalculator.calculate(arrangement)
        val adaptedRecord = imported.record.copy(
            exerciseId = pattern.id,
            adaptationStatus = arrangement.decisions.joinToString(",") { it.status.name },
            adaptationConfidence = quality.confidence.ratio,
            omittedRatio = quality.omittedNoteRatio.ratio,
            transformedRatio = quality.transformedNoteRatio.ratio
        )
        store?.saveImportedExercise(adaptedRecord, pattern)
        val hasTransformations = arrangement.decisions.any { it.status !in setOf(AdaptationStatus.EXACT, AdaptationStatus.PRESERVED) }
        return if (hasTransformations) {
            ScoreIngestionResult.Partial(
                imported.timeline,
                adaptedRecord,
                arrangement.decisions.filter { it.status !in setOf(AdaptationStatus.EXACT, AdaptationStatus.PRESERVED) }
                    .map { "${it.sourceEventId}: ${it.status.name}" },
                arrangement,
                pattern
            )
        } else {
            ScoreIngestionResult.Adapted(imported.timeline, arrangement, pattern, adaptedRecord)
        }
    }

    private fun importMidi(source: ScoreSource.BinaryMidi): ScoreIngestionResult {
        return when (val detection = ScoreFormatDetector.detect(source.bytes)) {
            is FormatDetectionResult.Detected -> if (detection.format == DetectedScoreFormat.MIDI_BINARY) {
                fromImport(BinaryMidiScoreImporter().import(source.bytes, source.sourceId), DetectedScoreFormat.MIDI_BINARY, source.sourceId)
            } else ScoreIngestionResult.Unsupported(ScoreIngestionError.UnsupportedFormat("Source is not binary MIDI"))
            is FormatDetectionResult.Unsupported -> ScoreIngestionResult.Unsupported(ScoreIngestionError.UnsupportedFormat(detection.reason))
        }
    }

    private fun importMusicXml(source: ScoreSource.MusicXml): ScoreIngestionResult =
        fromImport(MusicXmlScoreImporter().import(source.text, source.sourceId), DetectedScoreFormat.MUSIC_XML, source.sourceId)

    private fun ingestPdf(source: ScoreSource.Pdf): ScoreIngestionResult {
        if (omrEngine == null) return ScoreIngestionResult.Unsupported(ScoreIngestionError.UnsupportedFormat("PDF music recognition is deferred: no OMR engine is configured"))
        for (page in source.source.pages()) {
            when (val rasterized = source.source.rasterize(page.pageNumber)) {
                is PdfRasterizationResult.Failure -> return ScoreIngestionResult.Failed(ScoreIngestionError.RenderingFailed(rasterized.reason))
                is PdfRasterizationResult.Success -> {
                    val recognition = omrEngine.recognize(rasterized.source)
                    if (recognition.status == RecognitionStatus.UNCERTAIN) return ScoreIngestionResult.Uncertain(ScoreIngestionError.RecognitionUncertain("PDF page ${page.pageNumber}: ${recognition.reason ?: "uncertain recognition"}"))
                    if (recognition.status == RecognitionStatus.REJECTED) return ScoreIngestionResult.Rejected(ScoreIngestionError.RecognitionRejected("PDF page ${page.pageNumber}: ${recognition.reason ?: "recognition rejected"}"))
                    val validation = RecognitionScoreValidator.validate(recognition)
                    if (validation is ScoreValidationResult.Invalid) return ScoreIngestionResult.Rejected(ScoreIngestionError.InvalidScore(validation.errors.joinToString()))
                    return ScoreIngestionResult.Failed(ScoreIngestionError.ImportFailed("Multi-page OMR merge is not available without a real OMR engine adapter"))
                }
            }
        }
        return ScoreIngestionResult.Failed(ScoreIngestionError.ImportFailed("PDF contains no pages"))
    }

    private fun ingestImage(source: ScoreSource.Image): ScoreIngestionResult {
        val engine = omrEngine ?: return ScoreIngestionResult.Unsupported(ScoreIngestionError.UnsupportedFormat("Image music recognition is deferred: no OMR engine is configured"))
        val recognition = engine.recognize(source.source)
        return when (recognition.status) {
            RecognitionStatus.UNCERTAIN -> ScoreIngestionResult.Uncertain(ScoreIngestionError.RecognitionUncertain(recognition.reason ?: "Image recognition is uncertain"))
            RecognitionStatus.REJECTED -> ScoreIngestionResult.Rejected(ScoreIngestionError.RecognitionRejected(recognition.reason ?: "Image recognition was rejected"))
            RecognitionStatus.RECOGNIZED -> when (val validation = RecognitionScoreValidator.validate(recognition)) {
                is ScoreValidationResult.Invalid -> ScoreIngestionResult.Rejected(ScoreIngestionError.InvalidScore(validation.errors.joinToString()))
                is ScoreValidationResult.Valid -> fromTimeline(validation.timeline, DetectedScoreFormat.IMAGE, source.source.identity, null, recognition.confidence)
            }
        }
    }

    private fun fromImport(result: SymbolicImportResult, format: DetectedScoreFormat, sourceId: String): ScoreIngestionResult = when (result) {
        is SymbolicImportResult.Failure -> ScoreIngestionResult.Rejected(ScoreIngestionError.ImportFailed(result.message))
        is SymbolicImportResult.Success -> fromTimeline(NormalizedMusicalTimeline.from(result.score), format, ScoreSourceIdentity(sourceId, result.score.metadata.sourceHash ?: "", MusicalProvenance(sourceId)), null, 1.0)
    }

    private fun fromTimeline(timeline: NormalizedMusicalTimeline, format: DetectedScoreFormat, identity: ScoreSourceIdentity, pageCount: Int?, confidence: Double): ScoreIngestionResult {
        if (timeline.sourceHash.isNullOrBlank()) return ScoreIngestionResult.Rejected(ScoreIngestionError.InvalidScore("Source hash is required"))
        val record = ImportedScoreRecord(timeline.sourceId, timeline.sourceHash, timeline.title, timeline.composer, timeline.provenance, format, clock(), RecognitionStatus.RECOGNIZED, confidence, pageCount, "VALID", timeline.toCanonicalJson())
        return ScoreIngestionResult.Success(timeline, record)
    }
}

fun NormalizedMusicalTimeline.toCanonicalJson(): String {
    fun String?.json() = if (this == null) "null" else "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
    fun Number?.json() = this?.toString() ?: "null"
    fun Boolean.json() = toString()
    val tempos = tempoMap.joinToString(",") { "{\"beat\":${it.beatPosition},\"bpm\":${it.bpm}}" }
    val signatures = timeSignatureMap.joinToString(",") { "{\"beat\":${it.beatPosition},\"numerator\":${it.timeSignature.numerator},\"denominator\":${it.timeSignature.denominator}}" }
    val keys = keySignatureMap.joinToString(",") { "{\"beat\":${it.beatPosition},\"key\":${it.key.json()},\"mode\":${it.mode.json()}}" }
    val serializedEvents = events.joinToString(",") {
        "{\"id\":${it.sourceEventId.json()},\"beat\":${it.beatPosition},\"duration\":${it.durationBeats}," +
            "\"absoluteQuarterNotes\":${it.absoluteQuarterNotes},\"measure\":${it.measureNumber},\"beatInMeasure\":${it.beatInMeasure}," +
            "\"pitch\":${it.pitch?.midiNumber.json()},\"staff\":${it.staffId.json()},\"accidental\":${it.accidental.json()}," +
            "\"tie\":${it.tie.json()},\"velocity\":${it.velocity.json()},\"rest\":${it.isRest.json()}," +
            "\"voice\":${it.voiceId.json()},\"chord\":${it.chordGroupId.json()}}"
    }
    return "{\"sourceId\":${sourceId.json()},\"sourceHash\":${sourceHash.json()},\"title\":${title.json()},\"composer\":${composer.json()}," +
        "\"tempo\":[$tempos],\"timeSignatures\":[$signatures],\"keySignatures\":[$keys],\"events\":[$serializedEvents]}"
}