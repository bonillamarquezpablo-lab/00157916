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

/**
 * Minimum among prices actually captured by Mínimo. Third-party historical insights and
 * archived snapshots are excluded: they are useful context, but Mínimo did not observe them
 * on their original dates. Unlike a historical claim, this can return a single first observation.
 */
data class InAppObservedMinimum(
    val observationCount: Int,
    val lowestPoint: PricePoint?
)

/**
 * Summarizes only live observations made by the app's price collection flow.
 * A single result is displayed as a first recorded price, not as a historical record.
 */
fun summarizeInAppObservedPrices(points: List<PricePoint>): InAppObservedMinimum {
    val appObservedPoints = sanitizePriceHistory(
        points.filterNot { point ->
            val source = point.eventLabel.orEmpty()
            val linkType = point.linkType
            source.contains("internet archive", ignoreCase = true) ||
                source.contains("web.archive.org", ignoreCase = true) ||
                source.contains("price_insights", ignoreCase = true) ||
                linkType.contains("archivo", ignoreCase = true) ||
                linkType.contains("historial google shopping", ignoreCase = true)
        }
    )
    return InAppObservedMinimum(
        observationCount = appObservedPoints.size,
        lowestPoint = appObservedPoints.minByOrNull { it.price }
    )
}

