package com.example.data.model

/**
 * Conservative summary of a chart series. A single dated quote is a first record,
 * not enough evidence to claim a comparable historical minimum.
 */
data class PriceHistorySummary(
    val observationCount: Int,
    val firstObservedAt: Long?,
    val minimumObservedPoint: PricePoint?
)

/**
 * Keeps only usable prices and one point per timestamp. If duplicate timestamps exist,
 * the lowest valid quote wins deterministically; callers should normally supply daily
 * snapshots that have already been reconciled across sellers.
 */
fun sanitizePriceHistory(points: List<PricePoint>): List<PricePoint> =
    points.asSequence()
        .filter { it.timestamp > 0L && it.price.isFinite() && it.price > 0.0 }
        .sortedWith(compareBy<PricePoint> { it.timestamp }.thenBy { it.price })
        .distinctBy { it.timestamp }
        .toList()

/**
 * A minimum is displayed only when at least two distinct dated observations are available.
 * The result is always computed from the supplied series, never from cached/default product
 * fields that may not correspond to the points currently shown on screen.
 */
fun summarizePriceHistory(points: List<PricePoint>): PriceHistorySummary {
    val validPoints = sanitizePriceHistory(points)
    return PriceHistorySummary(
        observationCount = validPoints.size,
        firstObservedAt = validPoints.firstOrNull()?.timestamp,
        minimumObservedPoint = validPoints.minByOrNull { it.price }
            .takeIf { validPoints.size >= 2 }
    )
}
