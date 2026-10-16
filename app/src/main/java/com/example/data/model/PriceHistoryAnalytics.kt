package com.example.data.model

import java.util.Calendar

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
 * on their original dates. The count represents distinct local calendar days, not raw quotes.
 * A single day can still be displayed as a first observation on an individual product card.
 */
data class InAppObservedMinimum(
    val observationCount: Int,
    val lowestPoint: PricePoint?
)

/**
 * Summarizes only live observations made by the app's price collection flow.
 * Multiple prices on the same local date count as one observation day; the day's lowest
 * valid quote represents that day for calculating Mínimo's observed minimum.
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
    val dailyPoints = appObservedPoints
        .groupBy { localDayStart(it.timestamp) }
        .values
        .mapNotNull { dayPoints -> dayPoints.minByOrNull { it.price } }
        .sortedBy { localDayStart(it.timestamp) }

    return InAppObservedMinimum(
        observationCount = dailyPoints.size,
        lowestPoint = dailyPoints.minByOrNull { it.price }
    )
}

/**
 * True only when Mínimo has captured usable prices on at least two distinct local dates.
 * Callers supply the product's verified observation history; imported archives and Google
 * price-insights history are excluded by summarizeInAppObservedPrices.
 */
fun hasComparableInAppMinimum(points: List<PricePoint>): Boolean {
    val summary = summarizeInAppObservedPrices(points)
    return summary.observationCount >= 2 && summary.lowestPoint != null
}

private fun localDayStart(timestamp: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/**
 * Sorts rows by their confirmed in-app minimum. Products without a minimum stay at the end
 * in either direction; a stable textual tie-break makes the order predictable.
 */
fun <T> sortByObservedMinimum(
    items: List<T>,
    ascending: Boolean,
    minimumPrice: (T) -> Double?,
    tieBreaker: (T) -> String
): List<T> = items.sortedWith { first, second ->
    val firstPrice = minimumPrice(first)
    val secondPrice = minimumPrice(second)
    when {
        firstPrice == null && secondPrice == null ->
            tieBreaker(first).compareTo(tieBreaker(second), ignoreCase = true)
        firstPrice == null -> 1
        secondPrice == null -> -1
        firstPrice != secondPrice ->
            if (ascending) firstPrice.compareTo(secondPrice) else secondPrice.compareTo(firstPrice)
        else -> tieBreaker(first).compareTo(tieBreaker(second), ignoreCase = true)
    }
}

