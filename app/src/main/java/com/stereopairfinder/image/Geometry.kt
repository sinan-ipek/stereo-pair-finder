package com.stereopairfinder.image

import kotlin.math.min

data class CropSquare(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object Geometry {
    /**
     * Finds the largest axis-aligned square whose every pixel is valid.
     *
     * The old implementation centered a square inside the bounding rectangle
     * of the mask. Perspective warps leave triangular invalid corners, so that
     * square could contain holes even when the usable overlap was ample.
     */
    fun largestValidSquare(mask: ByteArray, width: Int, height: Int): CropSquare? {
        require(width >= 0 && height >= 0)
        require(mask.size == width * height)
        if (width == 0 || height == 0) return null

        var previous = IntArray(width + 1)
        var bestSide = 0
        var bestRight = 0
        var bestBottom = 0

        for (y in 1..height) {
            val current = IntArray(width + 1)
            val rowOffset = (y - 1) * width
            for (x in 1..width) {
                if ((mask[rowOffset + x - 1].toInt() and 0xff) != 0) {
                    current[x] = 1 + minOf(current[x - 1], previous[x], previous[x - 1])
                    if (current[x] > bestSide) {
                        bestSide = current[x]
                        bestRight = x
                        bestBottom = y
                    }
                }
            }
            previous = current
        }

        if (bestSide == 0) return null
        return CropSquare(
            left = bestRight - bestSide,
            top = bestBottom - bestSide,
            right = bestRight,
            bottom = bestBottom
        )
    }

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
            kotlin.math.abs(values[8]) > 1e-8 &&
            values.maxOf { kotlin.math.abs(it) } < 1e5
}
