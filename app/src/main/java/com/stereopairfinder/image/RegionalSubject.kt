package com.stereopairfinder.image

import kotlin.math.max
import kotlin.math.ceil

/** One image-grid cell used by the disparity-independent composition analysis. */
data class SubjectCell(
    val column: Int,
    val row: Int,
    val centerX: Double,
    val centerY: Double,
    val validPixels: Int,
    val score: Double
)

/**
 * Converts regional detail measurements into one conservative subject target.
 *
 * High-scoring neighbouring cells are joined before a target is chosen. This is
 * deliberately different from picking isolated edge pixels: a guitar neck or
 * another elongated object survives as one coherent region, while weak texture
 * spread over an entire wall or sky does not dominate. The returned footprint
 * later helps reject a visually strong but stereo-stationary aircraft wing.
 */
object RegionalSubject {
    private const val EPSILON = 1e-9

    fun select(cells: List<SubjectCell>): SubjectGuidance? {
        val usable = cells.filter {
            it.column >= 0 && it.row >= 0 &&
                it.centerX.isFinite() && it.centerY.isFinite() &&
                it.validPixels > 0 && it.score.isFinite() && it.score >= 0.0
        }
        if (usable.size < 12) return null

        val ordered = usable.map { it.score }.sorted()
        val median = percentile(ordered, 0.50)
        val upperQuartile = percentile(ordered, 0.75)
        val upperTail = percentile(ordered, 0.92)
        val spread = upperTail - median

        // A nearly uniform scene has no defensible composition target.
        if (upperTail < 3.0 || spread < max(1.2, median * 0.10)) return null

        // Keep a broad enough band to join the separate edges of a long object.
        val threshold = max(upperQuartile, median + spread * 0.34)
        val hot = usable.filter { it.score >= threshold }
        if (hot.size < 2) return null

        val byPosition = hot.associateBy { it.column to it.row }
        val unvisited = hot.toMutableSet()
        val groups = mutableListOf<List<SubjectCell>>()
        while (unvisited.isNotEmpty()) {
            val first = unvisited.first()
            unvisited.remove(first)
            val queue = ArrayDeque<SubjectCell>()
            val group = mutableListOf<SubjectCell>()
            queue.add(first)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                group += current
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val neighbour = byPosition[current.column + dx to current.row + dy]
                        if (neighbour != null && unvisited.remove(neighbour)) queue.add(neighbour)
                    }
                }
            }
            groups += group
        }

        fun weight(cell: SubjectCell): Double =
            (cell.score - median).coerceAtLeast(spread * 0.08) * cell.validPixels

        val weightedGroups = groups.map { group -> group to group.sumOf(::weight) }
        val best = weightedGroups.maxByOrNull { it.second } ?: return null
        if (best.first.size < 2 || best.second <= EPSILON) return null

        val totalWeight = weightedGroups.sumOf { it.second }.coerceAtLeast(EPSILON)
        val dominance = (best.second / totalWeight).coerceIn(0.0, 1.0)
        val separation = (spread / (median + 12.0)).coerceIn(0.0, 1.0)
        val occupiedShare = best.first.size.toDouble() / usable.size
        val occupancyQuality = when {
            occupiedShare < 0.015 -> occupiedShare / 0.015
            occupiedShare <= 0.32 -> 1.0
            else -> ((0.55 - occupiedShare) / 0.23).coerceIn(0.0, 1.0)
        }
        val confidence = (
            0.18 +
                0.38 * dominance +
                0.29 * separation +
                0.15 * occupancyQuality
            ).coerceIn(0.0, 1.0)

        var weightedX = 0.0
        var weightedY = 0.0
        var selectedWeight = 0.0
        best.first.forEach { cell ->
            val cellWeight = weight(cell)
            weightedX += cell.centerX * cellWeight
            weightedY += cell.centerY * cellWeight
            selectedWeight += cellWeight
        }
        if (selectedWeight <= EPSILON) return null

        val stepX = typicalStep(usable.map { it.centerX })
        val stepY = typicalStep(usable.map { it.centerY })
        val minX = best.first.minOf { it.centerX }
        val maxX = best.first.maxOf { it.centerX }
        val minY = best.first.minOf { it.centerY }
        val maxY = best.first.maxOf { it.centerY }

        return SubjectGuidance(
            x = weightedX / selectedWeight,
            y = weightedY / selectedWeight,
            confidence = confidence,
            evidenceCount = best.first.sumOf { it.validPixels },
            radiusX = ((maxX - minX) / 2.0 + stepX / 2.0).coerceAtLeast(1.0),
            radiusY = ((maxY - minY) / 2.0 + stepY / 2.0).coerceAtLeast(1.0)
        )
    }

    private fun typicalStep(values: List<Double>): Double {
        val differences = values.distinct().sorted()
            .zipWithNext { first, second -> second - first }
            .filter { it.isFinite() && it > EPSILON }
            .sorted()
        return if (differences.isEmpty()) 1.0 else differences[differences.size / 2]
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = ceil(fraction.coerceIn(0.0, 1.0) * (sorted.size - 1)).toInt()
        return sorted[index]
    }
}
