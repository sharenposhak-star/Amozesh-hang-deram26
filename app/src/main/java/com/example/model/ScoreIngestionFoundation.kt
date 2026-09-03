package com.example.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
import android.content.Context
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

data class ScoreSourceIdentity(
    val sourceId: String,
    val sourceHash: String,
    val provenance: MusicalProvenance,
    val pageIndex: Int? = null
) {
    init {
        require(sourceId.isNotBlank())
        require(sourceHash.isNotBlank())
    }
}

enum class PdfContentKind { STRUCTURED, SCANNED, UNKNOWN }

data class PdfPage(
    val identity: ScoreSourceIdentity,
    val pageNumber: Int,
    val contentKind: PdfContentKind = PdfContentKind.UNKNOWN
) {
    init {
        require(pageNumber >= 0)
        require(identity.pageIndex == pageNumber)
    }
}

sealed interface PdfRasterizationResult {
    data class Success(val source: ImageScoreSource) : PdfRasterizationResult
    data class Failure(val page: PdfPage, val reason: String, val cause: Throwable? = null) : PdfRasterizationResult
}

interface PdfScoreSource : Closeable {
    val identity: ScoreSourceIdentity
    val pageCount: Int
    fun pages(): List<PdfPage>
    fun rasterize(pageNumber: Int): PdfRasterizationResult
}

/** Uses the platform renderer only; it does not infer musical notation. */
class AndroidPdfScoreSource private constructor(
    private val sourceFile: File,
    private val descriptor: ParcelFileDescriptor,
    override val identity: ScoreSourceIdentity,
    private val renderer: PdfRenderer
) : PdfScoreSource {
    override val pageCount: Int get() = renderer.pageCount

    override fun pages(): List<PdfPage> = (0 until pageCount).map { index ->
        PdfPage(identity.copy(pageIndex = index), index)
    }

    override fun rasterize(pageNumber: Int): PdfRasterizationResult {
        val page = pages().getOrNull(pageNumber)
            ?: return PdfRasterizationResult.Failure(
                PdfPage(identity.copy(pageIndex = pageNumber), pageNumber),
                "PDF page index is out of bounds"
            )
        return try {
            renderer.openPage(pageNumber).use { pdfPage ->
                val width = pdfPage.width.coerceAtLeast(1)
                val height = pdfPage.height.coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                PdfRasterizationResult.Success(ImageScoreSource.fromBitmap(bitmap, page.identity))
            }
        } catch (error: Exception) {
            PdfRasterizationResult.Failure(page, "PDF page rendering failed", error)
        }
    }

    override fun close() {
        renderer.close()
        descriptor.close()
        sourceFile.delete()
    }

    companion object {
        fun fromBytes(context: Context, sourceId: String, bytes: ByteArray): AndroidPdfScoreSource {
            require(bytes.isNotEmpty())
            val hash = sha256(bytes)
            val file = File.createTempFile("score-$hash-", ".pdf", context.cacheDir)
            file.writeBytes(bytes)
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                AndroidPdfScoreSource(
                    file,
                    descriptor,
                    ScoreSourceIdentity(sourceId, hash, MusicalProvenance(sourceId, sourceLocation = "pdf")),
                    PdfRenderer(descriptor)
                )
            } catch (error: Exception) {
                descriptor.close()
                file.delete()
                throw error
            }
        }
    }
}

data class ImageScoreSource(
    val bytes: ByteArray,
    val identity: ScoreSourceIdentity,
    val bitmap: Bitmap? = null
) {
    init {
        require(bytes.isNotEmpty() || bitmap != null)
    }

    fun decodedBitmap(): Bitmap? = bitmap ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    companion object {
        fun fromBytes(bytes: ByteArray, sourceId: String, pageIndex: Int? = null): ImageScoreSource {
            val hash = sha256(bytes)
            return ImageScoreSource(
                bytes.copyOf(),
                ScoreSourceIdentity(sourceId, hash, MusicalProvenance(sourceId, sourceLocation = "image"), pageIndex)
            )
        }

        fun fromBitmap(bitmap: Bitmap, identity: ScoreSourceIdentity): ImageScoreSource =
            ImageScoreSource(ByteArray(1) { 0 }, identity, bitmap)
    }
}

object DeterministicImagePreprocessor {
    fun normalize(bitmap: Bitmap): Bitmap {
        require(!bitmap.isRecycled)
        val normalized = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(normalized).drawBitmap(bitmap, null, Rect(0, 0, bitmap.width, bitmap.height), paint)
        return normalized
    }
}

enum class RecognitionStatus { RECOGNIZED, UNCERTAIN, REJECTED }

data class RecognitionUncertainty(
    val sourceLocation: String,
    val confidence: Double,
    val reason: String
) {
    init {
        require(sourceLocation.isNotBlank())
        require(confidence in 0.0..1.0)
        require(reason.isNotBlank())
    }
}

data class RecognitionResult(
    val source: ScoreSourceIdentity,
    val status: RecognitionStatus,
    val score: SymbolicScore? = null,
    val confidence: Double = 0.0,
    val uncertainties: List<RecognitionUncertainty> = emptyList(),
    val reason: String? = null
) {
    init {
        require(confidence in 0.0..1.0)
        when (status) {
            RecognitionStatus.RECOGNIZED -> require(score != null && uncertainties.isEmpty())
            RecognitionStatus.UNCERTAIN -> require(uncertainties.isNotEmpty() || !reason.isNullOrBlank())
            RecognitionStatus.REJECTED -> require(score == null)
        }
    }
}

interface OmrEngine {
    fun recognize(source: ImageScoreSource): RecognitionResult
}

sealed interface ScoreValidationResult {
    data class Valid(val timeline: NormalizedMusicalTimeline) : ScoreValidationResult
    data class Invalid(val errors: List<String>) : ScoreValidationResult
}

object RecognitionScoreValidator {
    fun validate(result: RecognitionResult): ScoreValidationResult {
        if (result.status != RecognitionStatus.RECOGNIZED || result.score == null) {
            return ScoreValidationResult.Invalid(listOf(result.reason ?: "Recognition is not resolved"))
        }
        val errors = mutableListOf<String>()
        if (result.source.sourceId != result.score.metadata.sourceId) errors += "Recognition source id does not match score metadata"
        if (result.source.sourceHash != result.score.metadata.sourceHash) errors += "Recognition source hash does not match score metadata"
        val events = result.score.events
        if (events.map { it.eventId }.distinct().size != events.size) errors += "Duplicate event identity"
        if (events.zipWithNext().any { it.first.beatPosition > it.second.beatPosition }) errors += "Impossible event ordering"
        events.forEach { event ->
            if (event.beatPosition < 0.0) errors += "Invalid beat"
            if (event.durationBeats <= 0.0) errors += "Invalid duration"
            if (!event.isRest && event.pitch == null) errors += "Unresolved required pitch"
            if (event.isRest && event.pitch != null) errors += "Rest cannot contain pitch"
            if (event.tie != null && event.tie !in setOf("start", "stop", "continue")) errors += "Malformed tie"
        }
        return if (errors.isEmpty()) ScoreValidationResult.Valid(NormalizedMusicalTimeline.from(result.score))
        else ScoreValidationResult.Invalid(errors.distinct())
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }