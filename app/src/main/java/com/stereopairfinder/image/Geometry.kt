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
    val disparity: Double
)

object Geometry {
    /**
     * Finds the largest axis-aligned square whose every pixel is valid.
     *
     * Perspective warps can leave triangular invalid corners. The square size is
     * therefore determined only from fully valid pixels. When several equally
     * large squares are possible, rectified feature matches are used to prefer
     * the region with the strongest average horizontal parallax. If no usable
     * parallax sample falls inside any candidate, the candidate closest to the
     * image center is selected.
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

    fun saneHomography(values: DoubleArray): Boolean =
        values.size == 9 &&
            values.all { it.isFinite() } &&
            kotlin.math.abs(values[8]) > 1e-8 &&
            values.maxOf { kotlin.math.abs(it) } < 1e5
}
