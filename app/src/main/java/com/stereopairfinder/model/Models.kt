package com.stereopairfinder.model

import android.graphics.Bitmap
import android.net.Uri

const val DEFAULT_MAX_SECONDS = 15
const val DEFAULT_SIMILARITY = 72

enum class CropMode(val label: String) {
    FIT("Fit"),
    FILL("Fill")
}

data class RenderSettings(
    val cropMode: CropMode = CropMode.FIT,
    val verticalBias: Float = 0f
) {
    fun normalized() = copy(verticalBias = verticalBias.coerceIn(-1f, 1f))
}

data class ScanSettings(
    val render: RenderSettings = RenderSettings(),
    val maxSeconds: Int = DEFAULT_MAX_SECONDS,
    val similarity: Int = DEFAULT_SIMILARITY
)

data class Photo(
    val uri: Uri,
    val takenAtMillis: Long?,
    val label: String,
    val tie: String = uri.toString()
)

data class PairCandidate(
    val index: Int,
    val left: Photo,
    val right: Photo
) {
    val seconds: Double? = left.takenAtMillis?.let { a ->
        right.takenAtMillis?.let { b -> kotlin.math.abs(b - a) / 1000.0 }
    }
}

enum class PairStatus(val text: String) {
    MATCHED("Eşleşti"),
    TIME_MISSING("Çekim zamanı bulunamadı"),
    TIME_EXCEEDED("Zaman sınırı aşıldı"),
    SIMILARITY_LOW("Benzerlik eşiğinin altında"),
    EVIDENCE_LOW("Benzerlik güveni yetersiz"),
    ALIGNMENT_FAILED("Hizalama başarısız"),
    ALIGNMENT_LOW("Düşük hizalama güveni"),
    AREA_LOW("Ortak görüntü alanı yetersiz")
}

data class AnalysisResult(
    val pair: PairCandidate,
    val similarity: Double,
    val reliableMatches: Int,
    val alignmentConfidence: Double,
    val medianVerticalError: Double,
    val commonAreaRatio: Double,
    val status: PairStatus,
    val leftPreview: Bitmap?,
    val rightPreview: Bitmap?,
    val sbsPreview: Bitmap?,
    val sbsJpeg: ByteArray? = null
) {
    val saveable get() = status == PairStatus.MATCHED
}

object PairPolicy {
    fun sorted(photos: List<Photo>) = photos.sortedWith(
        compareBy<Photo> { it.takenAtMillis == null }
            .thenBy { it.takenAtMillis ?: Long.MAX_VALUE }
            .thenBy { it.tie }
    )

    fun adjacent(photos: List<Photo>) = sorted(photos)
        .zipWithNext()
        .mapIndexed { i, (a, b) -> PairCandidate(i + 1, a, b) }

    fun status(
        pair: PairCandidate,
        similarity: Double,
        evidence: Boolean,
        aligned: Boolean,
        confidence: Double,
        area: Double,
        maxSeconds: Int = DEFAULT_MAX_SECONDS,
        threshold: Int = DEFAULT_SIMILARITY,
        enforceTime: Boolean = true,
        enforceSimilarity: Boolean = true
    ): PairStatus = when {
        enforceTime && pair.seconds == null -> PairStatus.TIME_MISSING
        enforceTime && pair.seconds != null && pair.seconds!! > maxSeconds -> PairStatus.TIME_EXCEEDED
        !evidence -> PairStatus.EVIDENCE_LOW
        enforceSimilarity && similarity < threshold -> PairStatus.SIMILARITY_LOW
        !aligned -> PairStatus.ALIGNMENT_FAILED
        area < .35 -> PairStatus.AREA_LOW
        confidence < 60 -> PairStatus.ALIGNMENT_LOW
        else -> PairStatus.MATCHED
    }
}
