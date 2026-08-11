package com.stereopairfinder.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
enum class VerticalCropPlacement { CUT_TOP, CENTER, CUT_BOTTOM }

data class FramingDecision(
    val square: CropSquare,
    val placement: VerticalCropPlacement,
    val mode: String,
    val targetX: Double,
    val targetY: Double,
    val parallax: BandValues,
    /** Lower values mean a flatter, more nearly single-colour band. */
    val uniformity: BandValues
)

data class StereoSourceCrops(
    val left: CropSquare,
    val right: CropSquare
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
    private const val MIN_VERTICAL_RESIDUAL_TOLERANCE_PX = 2.5
    private const val VERTICAL_RESIDUAL_TOLERANCE_RATIO = 0.003

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
                VerticalBand.TOP -> VerticalCropPlacement.CUT_BOTTOM to "alttan kırp · üst paralaks güçlü"
                VerticalBand.MIDDLE -> VerticalCropPlacement.CENTER to "merkezden kırp · orta paralaks güçlü"
                VerticalBand.BOTTOM -> VerticalCropPlacement.CUT_TOP to "üstten kırp · alt paralaks güçlü"
            }
        } else {
            uniformityChoice(texture)
        }

        val square = originalCrop(width, height, choice.first) ?: return null
        return FramingDecision(
            square = square,
            placement = choice.first,
            mode = choice.second,
            targetX = width / 2.0,
            targetY = (square.top + square.bottom) / 2.0,
            parallax = parallax,
            uniformity = texture
        )
    }

    private fun uniformityChoice(values: BandValues): Pair<VerticalCropPlacement, String> {
        val topIsFlatter = values.top + UNIFORMITY_MARGIN <= values.bottom &&
            values.top <= values.bottom * UNIFORMITY_RATIO
        val bottomIsFlatter = values.bottom + UNIFORMITY_MARGIN <= values.top &&
            values.bottom <= values.top * UNIFORMITY_RATIO
        return when {
            topIsFlatter -> VerticalCropPlacement.CUT_TOP to "üstten kırp · üst bölge daha tekdüze"
            bottomIsFlatter -> VerticalCropPlacement.CUT_BOTTOM to "alttan kırp · alt bölge daha tekdüze"
            else -> VerticalCropPlacement.CENTER to "merkezden kırp · fark belirgin değil"
        }
    }

    /**
     * Returns an unwarped, full-width square crop. Only rows are removed; the
     * source pixels are never rotated, sheared, perspective-warped or stretched.
     */
    fun originalCrop(
        width: Int,
        height: Int,
        placement: VerticalCropPlacement
    ): CropSquare? {
        if (width <= 0 || height < width) return null
        val top = when (placement) {
            VerticalCropPlacement.CUT_TOP -> height - width
            VerticalCropPlacement.CENTER -> (height - width) / 2
            VerticalCropPlacement.CUT_BOTTOM -> 0
        }
        return CropSquare(0, top, width, top + width)
    }

    /**
     * Produces full-width square source crops whose different top rows apply a
     * pure vertical translation. No source pixel is warped or resampled here.
     *
     * [verticalOffsetInAnalysis] is median(rightY - leftY), measured in the
     * shared analysis image. A positive value makes the right crop start lower.
     */
    fun translatedSourceCrops(
        leftWidth: Int,
        leftHeight: Int,
        rightWidth: Int,
        rightHeight: Int,
        analysisHeight: Int,
        verticalOffsetInAnalysis: Double,
        placement: VerticalCropPlacement
    ): StereoSourceCrops? {
        if (
            leftWidth <= 0 || rightWidth <= 0 ||
            leftHeight < leftWidth || rightHeight < rightWidth ||
            analysisHeight <= 0 || !verticalOffsetInAnalysis.isFinite()
        ) return null

        val leftAspect = leftHeight.toDouble() / leftWidth
        val rightAspect = rightHeight.toDouble() / rightWidth
        // Refuse a mismatched pair instead of silently stretching either image.
        if (abs(leftAspect - rightAspect) > 0.01) return null

        val aspect = (leftAspect + rightAspect) / 2.0
        val offsetInCropWidths = verticalOffsetInAnalysis * aspect / analysisHeight
        val leftMaxTop = (leftHeight - leftWidth).toDouble() / leftWidth
        val rightMaxTop = (rightHeight - rightWidth).toDouble() / rightWidth

        // rightTop/rightWidth = leftTop/leftWidth + offsetInCropWidths
        val minLeftTop = maxOf(0.0, -offsetInCropWidths)
        val maxLeftTop = minOf(leftMaxTop, rightMaxTop - offsetInCropWidths)
        if (maxLeftTop + EPSILON < minLeftTop) return null

        val leftTopInWidths = when (placement) {
            VerticalCropPlacement.CUT_BOTTOM -> minLeftTop
            VerticalCropPlacement.CENTER -> (minLeftTop + maxLeftTop) / 2.0
            VerticalCropPlacement.CUT_TOP -> maxLeftTop
        }
        val rightTopInWidths = leftTopInWidths + offsetInCropWidths
        val leftTop = (leftTopInWidths * leftWidth).roundToInt()
            .coerceIn(0, leftHeight - leftWidth)
        val rightTop = (rightTopInWidths * rightWidth).roundToInt()
            .coerceIn(0, rightHeight - rightWidth)

        return StereoSourceCrops(
            left = CropSquare(0, leftTop, leftWidth, leftTop + leftWidth),
            right = CropSquare(0, rightTop, rightWidth, rightTop + rightWidth)
        )
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

    /**
     * The saved SBS is assembled without resizing either eye. If the two
     * source crops do not already have identical pixel dimensions, the pair is
     * rejected instead of scaling, stretching or padding one side.
     */
    fun outputCropsAreCompatible(crops: StereoSourceCrops): Boolean =
        crops.left.width == crops.right.width &&
            crops.left.height == crops.right.height &&
            crops.left.width == crops.left.height &&
            crops.right.width == crops.right.height

    /**
     * Translation residuals are measured in analysis-image pixels. A fixed
     * pixel threshold is too strict for full-resolution phone photographs, so
     * the tolerance follows image height while retaining the old 2.5 px floor
     * for small analysis images.
     */
    fun verticalResidualTolerance(imageHeight: Int): Double {
        if (imageHeight <= 0) return 0.0
        return max(
            MIN_VERTICAL_RESIDUAL_TOLERANCE_PX,
            imageHeight * VERTICAL_RESIDUAL_TOLERANCE_RATIO
        )
    }

    fun verticalAlignmentIsAcceptable(medianResidual: Double, imageHeight: Int): Boolean =
        medianResidual.isFinite() &&
            medianResidual <= verticalResidualTolerance(imageHeight)

    fun verticalAlignmentConfidence(
        medianResidual: Double,
        imageHeight: Int,
        reliableMatches: Int
    ): Double {
        val tolerance = verticalResidualTolerance(imageHeight)
        if (!medianResidual.isFinite() || tolerance <= 0.0 || reliableMatches <= 0) return 0.0

        val residualScore = (100.0 - 35.0 * medianResidual / tolerance)
            .coerceIn(0.0, 100.0)
        val evidenceScore = reliableMatches / (reliableMatches + 15.0)
        return residualScore * evidenceScore
    }

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
