package com.stereopairfinder.image

import kotlin.math.abs
import kotlin.math.min

data class CropSquare(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class ParallaxSample(
    val x: Double,
    val y: Double,
    /** Signed horizontal disparity after rectification. */
    val disparity: Double
)

data class BandValues(
    val top: Double,
    val middle: Double,
    val bottom: Double
) {
    fun value(band: VerticalBand): Double = when (band) {
        VerticalBand.TOP -> top
        VerticalBand.MIDDLE -> middle
        VerticalBand.BOTTOM -> bottom
    }
}

enum class VerticalBand { TOP, MIDDLE, BOTTOM }

data class FramingDecision(
    val square: CropSquare,
    val mode: String,
    val targetX: Double,
    val targetY: Double,
    val parallax: BandValues,
    /** Lower values mean a flatter, more nearly single-colour band. */
    val uniformity: BandValues
)

/**
 * Deliberately small vertical-framing policy.
 *
 * The image is treated as three broad horizontal bands. A clearly stronger
 * parallax band is preserved. If parallax is indecisive, the flatter of the top
 * and bottom bands is removed. No object detection and no horizontal targeting
 * are performed.
 */
object Geometry {
    private const val EPSILON = 1e-9
    private const val MIN_SAMPLES_IN_WINNING_BAND = 4
    private const val PARALLAX_RATIO = 1.20
    private const val PARALLAX_MARGIN_PX = 0.35
    private const val UNIFORMITY_RATIO = 0.80
    private const val UNIFORMITY_MARGIN = 3.0

    fun largestValidSquare(
        mask: ByteArray,
        width: Int,
        height: Int,
        parallaxSamples: List<ParallaxSample> = emptyList(),
        uniformity: BandValues? = null
    ): CropSquare? = verticalCrop(
        mask = mask,
        width = width,
        height = height,
        parallaxSamples = parallaxSamples,
        uniformity = uniformity
    )?.square

    fun verticalCrop(
        mask: ByteArray,
        width: Int,
        height: Int,
        parallaxSamples: List<ParallaxSample> = emptyList(),
        uniformity: BandValues? = null
    ): FramingDecision? {
        require(width >= 0 && height >= 0)
        require(mask.size == width * height)
        if (width == 0 || height == 0 || mask.none { (it.toInt() and 0xff) != 0 }) return null

        val usableSamples = parallaxSamples.filter {
            it.x.isFinite() && it.y.isFinite() && it.disparity.isFinite() &&
                it.x >= 0.0 && it.x < width && it.y >= 0.0 && it.y < height
        }
        val grouped = VerticalBand.entries.associateWith { band ->
            usableSamples.filter { bandAt(it.y, height) == band }
        }
        val parallax = BandValues(
            top = robustMeanAbsolute(grouped.getValue(VerticalBand.TOP).map { it.disparity }),
            middle = robustMeanAbsolute(grouped.getValue(VerticalBand.MIDDLE).map { it.disparity }),
            bottom = robustMeanAbsolute(grouped.getValue(VerticalBand.BOTTOM).map { it.disparity })
        )
        val texture = uniformity?.sanitized() ?: BandValues(0.0, 0.0, 0.0)

        val ranked = VerticalBand.entries.sortedByDescending(parallax::value)
        val winner = ranked.first()
        val winnerScore = parallax.value(winner)
        val runnerUpScore = parallax.value(ranked[1])
        val winnerCount = grouped.getValue(winner).size
        val clearParallax = winnerCount >= MIN_SAMPLES_IN_WINNING_BAND &&
            winnerScore >= runnerUpScore * PARALLAX_RATIO + PARALLAX_MARGIN_PX

        val choice = if (clearParallax) {
            when (winner) {
                VerticalBand.TOP -> CropChoice.BOTTOM_CUT to "alttan kırp · üst paralaks güçlü"
                VerticalBand.MIDDLE -> CropChoice.CENTER to "merkezden kırp · orta paralaks güçlü"
                VerticalBand.BOTTOM -> CropChoice.TOP_CUT to "üstten kırp · alt paralaks güçlü"
            }
        } else {
            uniformityChoice(texture)
        }

        val square = cropAt(width, height, choice.first)
        return FramingDecision(
            square = square,
            mode = choice.second,
            targetX = width / 2.0,
            targetY = (square.top + square.bottom) / 2.0,
            parallax = parallax,
            uniformity = texture
        )
    }

    private enum class CropChoice { TOP_CUT, CENTER, BOTTOM_CUT }

    private fun uniformityChoice(values: BandValues): Pair<CropChoice, String> {
        val topIsFlatter = values.top + UNIFORMITY_MARGIN <= values.bottom &&
            values.top <= values.bottom * UNIFORMITY_RATIO
        val bottomIsFlatter = values.bottom + UNIFORMITY_MARGIN <= values.top &&
            values.bottom <= values.top * UNIFORMITY_RATIO
        return when {
            topIsFlatter -> CropChoice.TOP_CUT to "üstten kırp · üst bölge daha tekdüze"
            bottomIsFlatter -> CropChoice.BOTTOM_CUT to "alttan kırp · alt bölge daha tekdüze"
            else -> CropChoice.CENTER to "merkezden kırp · fark belirgin değil"
        }
    }

    private fun cropAt(width: Int, height: Int, choice: CropChoice): CropSquare {
        val side = min(width, height)
        val left = (width - side) / 2
        val top = if (height <= side) {
            0
        } else {
            when (choice) {
                CropChoice.TOP_CUT -> height - side
                CropChoice.CENTER -> (height - side) / 2
                CropChoice.BOTTOM_CUT -> 0
            }
        }
        return CropSquare(left, top, left + side, top + side)
    }

    private fun bandAt(y: Double, height: Int): VerticalBand = when {
        y < height / 3.0 -> VerticalBand.TOP
        y < height * 2.0 / 3.0 -> VerticalBand.MIDDLE
        else -> VerticalBand.BOTTOM
    }

    /** A 10% trimmed mean prevents one bad optical-flow vector owning a band. */
    private fun robustMeanAbsolute(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.map(::abs).filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return 0.0
        val trim = if (sorted.size >= 10) sorted.size / 10 else 0
        val kept = sorted.subList(trim, sorted.size - trim)
        return kept.average()
    }

    private fun BandValues.sanitized() = BandValues(
        top = top.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
        middle = middle.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
        bottom = bottom.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    )

    fun outputSide(sourceSide: Int) = min(sourceSide, 2048)

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.POSITIVE_INFINITY
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        }
    }

    fun saneHomography(values: DoubleArray): Boolean =
        values.size == 9 &&
            values.all { it.isFinite() } &&
            abs(values[8]) > 1e-8 &&
            values.maxOf { abs(it) } < 1e5
}
