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
    /** Signed rectified disparity: left.x - right.x. */
    val disparity: Double
)

object Geometry {
    private const val SCORE_EPSILON = 1e-9
    private const val NEAR_BEST_SCORE_RATIO = 0.98
    private const val MIN_PARALLAX_SAMPLES = 3

    /**
     * Finds the largest axis-aligned square whose every pixel is valid.
     *
     * Perspective warps can leave triangular invalid corners, so the square size
     * is determined only from fully valid pixels. Crop placement uses relative
     * parallax: a robust affine disparity plane is fitted to the dominant scene
     * surface and only deviations from that plane count as depth evidence. This
     * prevents a global rectification offset or a textured background from being
     * mistaken for the foreground. Among candidates that retain nearly all of
     * the strongest evidence, the crop whose center is closest to the evidence
     * centroid is selected. With no reliable evidence, image center is used.
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
                            candidates += CropSquare(x - side, y - side, x, y)
                        }

                        side == bestSide && side > 0 -> {
                            candidates += CropSquare(x - side, y - side, x, y)
                        }
                    }
                }
            }
            previous = current
        }

        if (bestSide == 0) return null

        val usableSamples = parallaxSamples.filter {
            it.x.isFinite() && it.y.isFinite() && it.disparity.isFinite() &&
                it.x >= 0.0 && it.x < width && it.y >= 0.0 && it.y < height
        }
        val weightedEvidence = relativeParallaxEvidence(usableSamples, width, height)
        val imageCenterX = width / 2.0
        val imageCenterY = height / 2.0

        fun centerDistanceSquared(square: CropSquare, x: Double, y: Double): Double {
            val dx = (square.left + square.right) / 2.0 - x
            val dy = (square.top + square.bottom) / 2.0 - y
            return dx * dx + dy * dy
        }

        if (weightedEvidence.size < MIN_PARALLAX_SAMPLES) {
            return candidates.minByOrNull {
                centerDistanceSquared(it, imageCenterX, imageCenterY)
            }
        }

        val totalWeight = weightedEvidence.sumOf { it.weight }
        if (totalWeight <= SCORE_EPSILON) {
            return candidates.minByOrNull {
                centerDistanceSquared(it, imageCenterX, imageCenterY)
            }
        }

        val evidenceCenterX = weightedEvidence.sumOf { it.sample.x * it.weight } / totalWeight
        val evidenceCenterY = weightedEvidence.sumOf { it.sample.y * it.weight } / totalWeight

        data class CandidateScore(
            val square: CropSquare,
            val capturedWeight: Double,
            val centerDistance: Double
        )

        val scored = candidates.map { square ->
            val captured = weightedEvidence.sumOf {
                if (it.sample.inside(square)) it.weight else 0.0
            }
            CandidateScore(
                square = square,
                capturedWeight = captured,
                centerDistance = centerDistanceSquared(square, evidenceCenterX, evidenceCenterY)
            )
        }
        val bestWeight = scored.maxOf { it.capturedWeight }
        if (bestWeight <= SCORE_EPSILON) {
            return candidates.minByOrNull {
                centerDistanceSquared(it, imageCenterX, imageCenterY)
            }
        }

        return scored
            .asSequence()
            .filter { it.capturedWeight + SCORE_EPSILON >= bestWeight * NEAR_BEST_SCORE_RATIO }
            .minWithOrNull(
                compareBy<CandidateScore> { it.centerDistance }
                    .thenByDescending { it.capturedWeight }
            )
            ?.square
    }

    private data class WeightedEvidence(
        val sample: ParallaxSample,
        val weight: Double
    )

    private data class DisparityPlane(
        val xSlope: Double,
        val ySlope: Double,
        val offset: Double
    ) {
        fun valueAt(x: Double, y: Double) = xSlope * x + ySlope * y + offset
    }

    private fun ParallaxSample.inside(square: CropSquare) =
        x >= square.left && x < square.right && y >= square.top && y < square.bottom

    /** Robustly removes the dominant background disparity plane. */
    private fun relativeParallaxEvidence(
        samples: List<ParallaxSample>,
        width: Int,
        height: Int
    ): List<WeightedEvidence> {
        if (samples.size < MIN_PARALLAX_SAMPLES) return emptyList()

        val plane = fitDominantPlane(samples, width, height)
        val residuals = samples.map { sample ->
            abs(
                sample.disparity - plane.valueAt(
                    sample.x / width.coerceAtLeast(1),
                    sample.y / height.coerceAtLeast(1)
                )
            )
        }
        val residualMedian = median(residuals)
        val mad = median(residuals.map { abs(it - residualMedian) })
        val noiseFloor = residualMedian + maxOf(0.75, 3.0 * 1.4826 * mad)
        val positive = residuals.map { (it - noiseFloor).coerceAtLeast(0.0) }
        val nonZero = positive.filter { it > 0.0 }.sorted()
        if (nonZero.size < MIN_PARALLAX_SAMPLES) return emptyList()

        // A single imperfect feature match must not drag the crop by itself.
        val cap = percentile(nonZero, 0.90).coerceAtLeast(SCORE_EPSILON)
        return samples.zip(positive)
            .filter { (_, value) -> value > 0.0 }
            .map { (sample, value) -> WeightedEvidence(sample, min(value, cap)) }
    }

    /**
     * Fits disparity = a*x + b*y + c with deterministic RANSAC. A least-squares
     * seed can be pulled toward the foreground and then keep reinforcing the
     * wrong plane; three-point hypotheses avoid that failure. The model with
     * the smallest median residual is refined using only its robust inliers.
     */
    private fun fitDominantPlane(
        samples: List<ParallaxSample>,
        width: Int,
        height: Int
    ): DisparityPlane {
        if (samples.size < 6) {
            return DisparityPlane(0.0, 0.0, median(samples.map { it.disparity }))
        }
        var bestPlane = DisparityPlane(0.0, 0.0, median(samples.map { it.disparity }))
        var bestMedian = planeResiduals(samples, bestPlane, width, height).let(::median)
        var state = samples.size.toLong() * 2_654_435_761L + 1_013_904_223L

        fun nextIndex(): Int {
            state = (state * 1_664_525L + 1_013_904_223L) and 0xffff_ffffL
            return (state % samples.size).toInt()
        }

        repeat(minOf(160, maxOf(64, samples.size * 2))) {
            val first = nextIndex()
            var second = nextIndex()
            var third = nextIndex()
            repeat(6) {
                if (second == first) second = nextIndex()
                if (third == first || third == second) third = nextIndex()
            }
            if (first == second || first == third || second == third) return@repeat

            val candidate = solvePlane(
                listOf(samples[first], samples[second], samples[third]),
                width,
                height
            ) ?: return@repeat
            val candidateMedian = median(planeResiduals(samples, candidate, width, height))
            if (candidateMedian < bestMedian - SCORE_EPSILON) {
                bestPlane = candidate
                bestMedian = candidateMedian
            }
        }

        repeat(2) {
            val residuals = planeResiduals(samples, bestPlane, width, height)
            val center = median(residuals)
            val mad = median(residuals.map { abs(it - center) })
            val inlierLimit = center + maxOf(0.75, 2.5 * 1.4826 * mad)
            val inliers = samples.zip(residuals)
                .filter { (_, residual) -> residual <= inlierLimit }
                .map { (sample, _) -> sample }
            solvePlane(inliers, width, height)?.let { bestPlane = it }
        }
        return bestPlane
    }

    private fun planeResiduals(
        samples: List<ParallaxSample>,
        plane: DisparityPlane,
        width: Int,
        height: Int
    ): List<Double> = samples.map { sample ->
        abs(
            sample.disparity - plane.valueAt(
                sample.x / width.coerceAtLeast(1),
                sample.y / height.coerceAtLeast(1)
            )
        )
    }

    private fun solvePlane(
        samples: List<ParallaxSample>,
        width: Int,
        height: Int
    ): DisparityPlane? {
        if (samples.size < 3) return null
        var xx = 0.0
        var xy = 0.0
        var x1 = 0.0
        var yy = 0.0
        var y1 = 0.0
        var xd = 0.0
        var yd = 0.0
        var d1 = 0.0

        for (sample in samples) {
            val x = sample.x / width.coerceAtLeast(1)
            val y = sample.y / height.coerceAtLeast(1)
            val d = sample.disparity
            xx += x * x
            xy += x * y
            x1 += x
            yy += y * y
            y1 += y
            xd += x * d
            yd += y * d
            d1 += d
        }

        val matrix = arrayOf(
            doubleArrayOf(xx, xy, x1, xd),
            doubleArrayOf(xy, yy, y1, yd),
            doubleArrayOf(x1, y1, samples.size.toDouble(), d1)
        )
        if (!gaussianEliminate(matrix)) return null
        return DisparityPlane(matrix[0][3], matrix[1][3], matrix[2][3])
    }

    private fun gaussianEliminate(matrix: Array<DoubleArray>): Boolean {
        for (column in 0..2) {
            var pivot = column
            for (row in column + 1..2) {
                if (abs(matrix[row][column]) > abs(matrix[pivot][column])) pivot = row
            }
            if (abs(matrix[pivot][column]) < 1e-10) return false
            val swap = matrix[column]
            matrix[column] = matrix[pivot]
            matrix[pivot] = swap

            val divisor = matrix[column][column]
            for (index in column..3) matrix[column][index] /= divisor
            for (row in 0..2) {
                if (row == column) continue
                val factor = matrix[row][column]
                for (index in column..3) {
                    matrix[row][index] -= factor * matrix[column][index]
                }
            }
        }
        return matrix.all { row -> row.all { it.isFinite() } }
    }

    private fun percentile(sortedValues: List<Double>, fraction: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        val index = ((sortedValues.size - 1) * fraction).toInt()
        return sortedValues[index]
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
            abs(values[8]) > 1e-8 &&
            values.maxOf { abs(it) } < 1e5
}
