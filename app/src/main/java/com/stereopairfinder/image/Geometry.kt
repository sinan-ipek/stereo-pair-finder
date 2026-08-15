package com.stereopairfinder.image

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin


data class CropSquare(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class ParallaxSample(
    val x: Double,
    val y: Double,
    val disparity: Double
)

data class MatchSample(
    val leftX: Double,
    val leftY: Double,
    val rightX: Double,
    val rightY: Double
)

data class RigidAlignment(
    val angleDegrees: Double,
    val translateX: Double,
    val translateY: Double,
    val medianVerticalError: Double,
    val translationOnlyVerticalError: Double,
    val rotationApplied: Boolean
)

object Geometry {
    /**
     * Finds the largest axis-aligned square whose every pixel is valid.
     *
     * With rigid alignment the valid mask is rectangular for translation and
     * has only small corner losses when the optional tiny rotation is used.
     * When several equally large squares are possible, feature matches are
     * used to prefer the region with stronger horizontal stereo parallax.
     */
    fun largestValidSquare(
        mask: ByteArray,
        width: Int,
        height: Int,
        parallaxSamples: List<ParallaxSample> = emptyList()
    ): CropSquare? {
        require(width >= 0 && height >= 0)
        require(mask.size == width * height)
        if (width == 0 || height == 0) return null

        var previous = IntArray(width + 1)
        var bestSide = 0
        val candidates = mutableListOf<CropSquare>()

        for (y in 1..height) {
            val current = IntArray(width + 1)
            val rowOffset = (y - 1) * width
            for (x in 1..width) {
                if ((mask[rowOffset + x - 1].toInt() and 0xff) != 0) {
                    current[x] = 1 + minOf(current[x - 1], previous[x], previous[x - 1])
                    val side = current[x]
                    when {
                        side > bestSide -> {
                            bestSide = side
                            candidates.clear()
                            candidates += CropSquare(
                                left = x - side,
                                top = y - side,
                                right = x,
                                bottom = y
                            )
                        }

                        side == bestSide && side > 0 -> {
                            candidates += CropSquare(
                                left = x - side,
                                top = y - side,
                                right = x,
                                bottom = y
                            )
                        }
                    }
                }
            }
            previous = current
        }

        if (bestSide == 0) return null

        val usableSamples = parallaxSamples.filter {
            it.x.isFinite() &&
                it.y.isFinite() &&
                it.disparity.isFinite() &&
                it.disparity >= 0.0
        }
        val imageCenterX = width / 2.0
        val imageCenterY = height / 2.0

        fun samplesInside(square: CropSquare): List<ParallaxSample> =
            usableSamples.filter {
                it.x >= square.left &&
                    it.x < square.right &&
                    it.y >= square.top &&
                    it.y < square.bottom
            }

        fun centerDistanceSquared(square: CropSquare): Double {
            val centerX = (square.left + square.right) / 2.0
            val centerY = (square.top + square.bottom) / 2.0
            val dx = centerX - imageCenterX
            val dy = centerY - imageCenterY
            return dx * dx + dy * dy
        }

        var selected = candidates.first()
        var selectedSamples = samplesInside(selected)
        var selectedMean = selectedSamples.map { it.disparity }.average()
        var selectedDistance = centerDistanceSquared(selected)

        for (candidate in candidates.drop(1)) {
            val candidateSamples = samplesInside(candidate)
            val candidateMean = candidateSamples.map { it.disparity }.average()
            val candidateDistance = centerDistanceSquared(candidate)

            val candidateHasParallax = candidateSamples.isNotEmpty()
            val selectedHasParallax = selectedSamples.isNotEmpty()
            val strongerParallax =
                candidateHasParallax &&
                    (!selectedHasParallax || candidateMean > selectedMean + 1e-9)
            val equallyStrongParallax =
                candidateHasParallax &&
                    selectedHasParallax &&
                    abs(candidateMean - selectedMean) <= 1e-9
            val betterCoverage =
                equallyStrongParallax && candidateSamples.size > selectedSamples.size
            val equallyCovered =
                (!candidateHasParallax && !selectedHasParallax) ||
                    (equallyStrongParallax && candidateSamples.size == selectedSamples.size)
            val closerToCenter =
                equallyCovered && candidateDistance < selectedDistance - 1e-9

            if (strongerParallax || betterCoverage || closerToCenter) {
                selected = candidate
                selectedSamples = candidateSamples
                selectedMean = candidateMean
                selectedDistance = candidateDistance
            }
        }

        return selected
    }

    /**
     * Estimates the only geometry Stereo Pair Finder is allowed to apply.
     *
     * 1) Translation is always the baseline model.
     * 2) A tiny roll correction is considered only inside +/- 1 degree.
     * 3) Rotation is used only when it measurably improves vertical alignment.
     *
     * Scale, shear and perspective terms do not exist in this model.
     */
    fun estimateRigidAlignment(
        samples: List<MatchSample>,
        width: Int,
        height: Int
    ): RigidAlignment? {
        if (width <= 0 || height <= 0) return null
        val finite = samples.filter {
            it.leftX.isFinite() && it.leftY.isFinite() &&
                it.rightX.isFinite() && it.rightY.isFinite()
        }
        if (finite.size < MIN_ALIGNMENT_SAMPLES) return null

        val translation = evaluateAlignment(finite, width, height, 0.0)
        val rotationCandidate = estimateRotationCandidate(finite, width, height)

        if (rotationCandidate == null) return translation

        val improvement = translation.medianVerticalError - rotationCandidate.medianVerticalError
        val requiredImprovement = max(
            MIN_ROTATION_IMPROVEMENT_PX,
            translation.medianVerticalError * MIN_ROTATION_IMPROVEMENT_FRACTION
        )
        val usefulAngle = abs(rotationCandidate.angleDegrees) >= MIN_USEFUL_ROTATION_DEG
        val usefulImprovement = improvement >= requiredImprovement

        return if (usefulAngle && usefulImprovement) {
            rotationCandidate.copy(
                translationOnlyVerticalError = translation.medianVerticalError,
                rotationApplied = true
            )
        } else {
            translation
        }
    }

    private fun estimateRotationCandidate(
        samples: List<MatchSample>,
        width: Int,
        height: Int
    ): RigidAlignment? {
        val subset = if (samples.size <= MAX_ROTATION_SAMPLES) {
            samples
        } else {
            List(MAX_ROTATION_SAMPLES) { index ->
                val sourceIndex = index * (samples.size - 1) / (MAX_ROTATION_SAMPLES - 1)
                samples[sourceIndex]
            }
        }

        var best: RigidAlignment? = null
        val minHorizontalSeparation = width * MIN_SLOPE_BASELINE_FRACTION

        for (i in 0 until subset.lastIndex) {
            val first = subset[i]
            val firstDy = first.leftY - first.rightY
            for (j in i + 1 until subset.size) {
                val second = subset[j]
                val dx = second.rightX - first.rightX
                if (abs(dx) < minHorizontalSeparation) continue

                val secondDy = second.leftY - second.rightY
                val slope = (secondDy - firstDy) / dx
                val angleDegrees = Math.toDegrees(atan(slope))
                if (!angleDegrees.isFinite() || abs(angleDegrees) > MAX_ROTATION_DEG) continue

                val candidate = evaluateAlignment(samples, width, height, angleDegrees)
                if (best == null || candidate.medianVerticalError < best.medianVerticalError) {
                    best = candidate
                }
            }
        }

        return best
    }

    private fun evaluateAlignment(
        samples: List<MatchSample>,
        width: Int,
        height: Int,
        angleDegrees: Double
    ): RigidAlignment {
        val centerX = width / 2.0
        val centerY = height / 2.0
        val radians = Math.toRadians(angleDegrees)
        val c = cos(radians)
        val s = sin(radians)

        val rotated = samples.map { sample ->
            val x = sample.rightX - centerX
            val y = sample.rightY - centerY
            val rx = c * x - s * y + centerX
            val ry = s * x + c * y + centerY
            Triple(sample, rx, ry)
        }

        val tx = median(rotated.map { (sample, rx, _) -> sample.leftX - rx })
        val ty = median(rotated.map { (sample, _, ry) -> sample.leftY - ry })
        val verticalError = median(
            rotated.map { (sample, _, ry) -> abs((ry + ty) - sample.leftY) }
        )

        return RigidAlignment(
            angleDegrees = angleDegrees,
            translateX = tx,
            translateY = ty,
            medianVerticalError = verticalError,
            translationOnlyVerticalError = verticalError,
            rotationApplied = false
        )
    }

    /** UI preview stays memory-friendly. */
    fun outputSide(sourceSide: Int) = min(sourceSide, 2048)

    /** Full saved eye image. 3072 preserves a normal 4032x3024 camera frame without upscaling. */
    fun fullOutputSide(sourceSide: Int) = min(sourceSide, 3072)

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.POSITIVE_INFINITY
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        }
    }

    companion object {
        const val MAX_ROTATION_DEG = 1.0
        private const val MIN_ALIGNMENT_SAMPLES = 8
        private const val MAX_ROTATION_SAMPLES = 80
        private const val MIN_SLOPE_BASELINE_FRACTION = 0.08
        private const val MIN_USEFUL_ROTATION_DEG = 0.05
        private const val MIN_ROTATION_IMPROVEMENT_PX = 0.25
        private const val MIN_ROTATION_IMPROVEMENT_FRACTION = 0.20
    }
}
