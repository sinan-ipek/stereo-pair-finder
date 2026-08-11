package com.stereopairfinder.model

import android.graphics.Bitmap
import android.net.Uri

const val DEFAULT_MAX_SECONDS = 15
const val DEFAULT_SIMILARITY = 72

data class Photo(val uri: Uri, val takenAtMillis: Long?, val label: String, val tie: String = uri.toString())

data class PairCandidate(val index: Int, val left: Photo, val right: Photo) {
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
    val algorithmVersion: String = "bilinmiyor",
    val cropCenterXPercent: Double? = null,
    val cropCenterYPercent: Double? = null,
    val cropSidePercent: Double? = null,
    val parallaxEvidenceCount: Int = 0,
    val framingMode: String = "merkez / en büyük kare",
    val subjectConfidence: Double = 0.0,
    val subjectEvidenceCount: Int = 0
) {
    val saveable get() = status == PairStatus.MATCHED
}

object PairPolicy {
    fun sorted(photos: List<Photo>) = photos.sortedWith(
        compareBy<Photo> { it.takenAtMillis == null }
            .thenBy { it.takenAtMillis ?: Long.MAX_VALUE }
            .thenBy { it.tie }
    )

    fun adjacent(photos: List<Photo>) = sorted(photos).zipWithNext().mapIndexed { index, (a, b) ->
        PairCandidate(index + 1, a, b)
    }

    fun status(
        pair: PairCandidate,
        similarity: Double,
        evidence: Boolean,
        aligned: Boolean,
        confidence: Double,
        area: Double,
        maxSeconds: Int = DEFAULT_MAX_SECONDS,
        threshold: Int = DEFAULT_SIMILARITY
    ): PairStatus = when {
        pair.seconds == null -> PairStatus.TIME_MISSING
        pair.seconds!! > maxSeconds -> PairStatus.TIME_EXCEEDED
        !evidence -> PairStatus.EVIDENCE_LOW
        similarity < threshold -> PairStatus.SIMILARITY_LOW
        !aligned -> PairStatus.ALIGNMENT_FAILED
        area < .35 -> PairStatus.AREA_LOW
        confidence < 60 -> PairStatus.ALIGNMENT_LOW
        else -> PairStatus.MATCHED
    }
}
