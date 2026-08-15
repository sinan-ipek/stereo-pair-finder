package com.stereopairfinder.image

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

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
    const val MAX_ROTATION_DEG = 1.0
    private const val MIN_ALIGNMENT_SAMPLES = 8
    private const val MAX_ROTATION_SAMPLES = 80
    private const val MIN_SLOPE_BASELINE_FRACTION = 0.08
    private const val MIN_USEFUL_ROTATION_DEG = 0.05
    private const val MIN_ROTATION_IMPROVEMENT_PX = 0.25
    private const val MIN_ROTATION_IMPROVEMENT_FRACTION = 0.20

    /** Largest axis-aligned rectangle containing only valid pixels. */
    fun largestValidRectangle(mask: ByteArray, width: Int, height: Int): CropRect? {
        require(width >= 0 && height >= 0)
        require(mask.size == width * height)
        if (width == 0 || height == 0) return null

        val heights = IntArray(width)
        var bestArea = 0
        var best: CropRect? = null

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                heights[x] = if ((mask[row + x].toInt() and 0xff) != 0) heights[x] + 1 else 0
            }

            val stack = IntArray(width + 1)
            var stackSize = 0
            var x = 0
            while (x <= width) {
                val currentHeight = if (x == width) 0 else heights[x]
                if (stackSize == 0 || currentHeight >= heights[stack[stackSize - 1]]) {
                    stack[stackSize++] = x
                    x++
                } else {
                    val topIndex = stack[--stackSize]
                    val h = heights[topIndex]
                    val left = if (stackSize == 0) 0 else stack[stackSize - 1] + 1
                    val right = x
                    val area = h * (right - left)
                    if (area > bestArea && h > 0) {
                        bestArea = area
                        best = CropRect(
                            left = left,
                            top = y - h + 1,
                            right = right,
                            bottom = y + 1
                        )
                    }
                }
            }
        }

        return best
    }

    /**
     * Square crop inside an already valid rectangle. verticalBias=-1 shows the
     * top, 0 centers it, +1 shows the bottom. Horizontal framing stays centered.
     */
    fun squareInside(rect: CropRect, verticalBias: Float): CropSquare {
        val side = min(rect.width, rect.height).coerceAtLeast(1)
        val left = rect.left + (rect.width - side) / 2
        val freeY = (rect.height - side).coerceAtLeast(0)
        val normalized = ((verticalBias.coerceIn(-1f, 1f) + 1f) / 2f)
        val top = rect.top + (freeY * normalized).roundToInt().coerceIn(0, freeY)
        return CropSquare(left, top, left + side, top + side)
    }

    /** Existing square finder kept for tests and fallback behavior. */
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
                            candidates += CropSquare(x - side, y - side, x, y)
                        }
                        side == bestSide && side > 0 -> candidates += CropSquare(x - side, y - side, x, y)
                    }
                }
            }
            previous = current
        }

        if (bestSide == 0) return null

        val usableSamples = parallaxSamples.filter {
            it.x.isFinite() && it.y.isFinite() && it.disparity.isFinite() && it.disparity >= 0.0
        }
        val imageCenterX = width / 2.0
        val imageCenterY = height / 2.0

        fun samplesInside(square: CropSquare) = usableSamples.filter {
            it.x >= square.left && it.x < square.right && it.y >= square.top && it.y < square.bottom
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
            val strongerParallax = candidateHasParallax && (!selectedHasParallax || candidateMean > selectedMean + 1e-9)
            val equallyStrongParallax = candidateHasParallax && selectedHasParallax && abs(candidateMean - selectedMean) <= 1e-9
            val betterCoverage = equallyStrongParallax && candidateSamples.size > selectedSamples.size
            val equallyCovered = (!candidateHasParallax && !selectedHasParallax) ||
                (equallyStrongParallax && candidateSamples.size == selectedSamples.size)
            val closerToCenter = equallyCovered && candidateDistance < selectedDistance - 1e-9

            if (strongerParallax || betterCoverage || closerToCenter) {
                selected = candidate
                selectedSamples = candidateSamples
                selectedMean = candidateMean
                selectedDistance = candidateDistance
            }
        }

        return selected
    }

    fun estimateRigidAlignment(
        samples: List<MatchSample>,
        width: Int,
        height: Int
    ): RigidAlignment? {
        if (width <= 0 || height <= 0) return null
        val finite = samples.filter {
            it.leftX.isFinite() && it.leftY.isFinite() && it.rightX.isFinite() && it.rightY.isFinite()
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
                if (best == null || candidate.medianVerticalError < best.medianVerticalError) best = candidate
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
        val verticalError = median(rotated.map { (sample, _, ry) -> abs((ry + ty) - sample.leftY) })

        return RigidAlignment(
            angleDegrees = angleDegrees,
            translateX = tx,
            translateY = ty,
            medianVerticalError = verticalError,
            translationOnlyVerticalError = verticalError,
            rotationApplied = false
        )
    }

    fun outputSide(sourceSide: Int) = min(sourceSide, 2048)
    fun fullOutputSide(sourceSide: Int) = min(sourceSide, 3072)

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.POSITIVE_INFINITY
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
}
