package com.example.data.model

data class Product(
    val id: String,
    val title: String,
    val brand: String,
    val category: ProductCategory,
    val currentPrice: Double,
    val athMinPrice: Double,
    val athMinDate: String,
    val athMinStore: String,
    val athMaxPrice: Double,
    val avg90dPrice: Double,
    val officialPvp: Double,
    val currentStore: Store,
    val storeUrl: String,
    val ean: String,
    val priceHistory: List<PricePoint>,
    val offers: List<StoreOffer>,
    val wasRecentlyInflated: Boolean = false,
    val fakePriorPrice: Double? = null,
    val isTracked: Boolean = false,
    val alertTargetPrice: Double? = null,
    val rating: Double = 4.7,
    val reviewCount: Int = 1240,
    val imageResName: String? = null,
    val imageUrl: String = "",
    /**
     * True only after a current price and its history have been fetched and validated.
     * The bundled catalogue remains unverified by default.
     */
    val isPriceVerified: Boolean = false,
    val priceObservedAt: Long? = null,
    /** A product price lookup does not verify the demo rating or review count. */
    val isRatingVerified: Boolean = false,
    /** A product price lookup does not verify a bundled EAN. */
    val isEanVerified: Boolean = false,
    /** Provider-backed Google Shopping catalog ID, when available. */
    val googleProductId: String? = null,
    /** Provider which supplied the latest saved observation, e.g. Google Shopping or Amazon.es. */
    val priceSource: String = ""
) {
    /** Three or more dated observations span at least 90 days, regardless of today's quote. */
    val hasObservedHistoricalRange: Boolean
        get() {
            val now = System.currentTimeMillis()
            val timestamps = priceHistory.map { it.timestamp }
                .filter { it in 1L..now }
                .distinct()
                .sorted()
            return timestamps.size >= MIN_HISTORY_POINTS &&
                timestamps.last() - timestamps.first() >= NINETY_DAYS_MS
        }

    /** Enough dated observations are available to calculate an observed 90-day average. */
    val hasRecentHistoricalAverage: Boolean
        get() {
            val now = System.currentTimeMillis()
            val since = now - NINETY_DAYS_MS
            return priceHistory.map { it.timestamp }
                .filter { it in since..now }
                .distinct()
                .size >= MIN_HISTORY_POINTS
        }

    /**
     * A deal verdict needs current-price verification plus a long-enough, recent series.
     * Archive history by itself must never imply a current deal or a current minimum.
     */
    val hasSufficientHistory: Boolean
        get() {
            if (!isPriceVerified || !hasObservedHistoricalRange) return false
            val timestamps = priceHistory.map { it.timestamp }
                .filter { it > 0L }
                .distinct()
                .sorted()
            val now = System.currentTimeMillis()
            val recentPoints = timestamps.count { it >= now - NINETY_DAYS_MS && it <= now }
            return recentPoints >= MIN_HISTORY_POINTS &&
                now - timestamps.last() <= MAX_PRICE_AGE_FOR_HISTORICAL_CLAIMS
        }

    /**
     * Price badge based on real saved daily observations. The newest point represents the
     * current quote; only earlier points can establish a previous observed minimum.
     */
    val priceBadgeState: PriceBadgeState
        get() {
            val orderedPoints = priceHistory.sortedBy { it.timestamp }
            val priorPrices = if (orderedPoints.size > 1) {
                orderedPoints.dropLast(1).map { it.price }
            } else {
                emptyList()
            }
            return PriceBadgeClassifier.classify(
                isPriceVerified = isPriceFresh,
                currentPrice = currentPrice,
                priorObservedPrices = priorPrices,
                averagePrice = avg90dPrice,
                hasValidAverage = isPriceFresh && hasSufficientHistory
            )
        }

    val verdict: DealVerdict
        get() = when {
            !isPriceVerified -> DealVerdict.unverified()
            !isPriceFresh -> DealVerdict.insufficientHistory()
            !hasSufficientHistory -> DealVerdict.insufficientHistory()
            else -> DealVerdict.analyze(
                currentPrice = currentPrice,
                athMinPrice = athMinPrice,
                avg90dPrice = avg90dPrice,
                athMaxPrice = athMaxPrice,
                wasRecentlyInflated = wasRecentlyInflated,
                fakePriorPrice = fakePriorPrice
            )
        }

    /** Age of the latest live price observation, in milliseconds; null means unknown/invalid. */
    val priceAgeMillis: Long?
        get() {
            val observedAt = priceObservedAt ?: return null
            val now = System.currentTimeMillis()
            if (observedAt > now + FUTURE_TIMESTAMP_TOLERANCE_MS) return null
            return (now - observedAt).coerceAtLeast(0L)
        }

    /** A live observation older than 24 hours may remain visible, but is not a current deal. */
    val isPriceFresh: Boolean
        get() = isPriceVerified &&
            priceAgeMillis?.let { it <= PRICE_FRESHNESS_WINDOW_MS } == true

    val priceFreshnessLabel: String
        get() {
            if (!isPriceVerified) return "Sin observación online válida"
            if (priceObservedAt == null) return "Fecha de observación desconocida"
            val age = priceAgeMillis ?: return "Fecha de observación inválida"
            return when {
                age < 60_000L -> "Consultado hace menos de 1 min"
                age < HOUR_MS -> "Consultado hace menos de 1 h"
                age < DAY_MS -> "Consultado hace ${age / HOUR_MS} h"
                age < 2L * DAY_MS -> "Consultado hace 1 día · conviene confirmar"
                else -> "Consultado hace ${age / DAY_MS} días · conviene volver a consultar"
            }
        }

    val realSavingsAmount: Double
        get() = if (isPriceFresh) (officialPvp - currentPrice).coerceAtLeast(0.0) else 0.0

    val realSavingsPercent: Int
        get() = if (isPriceFresh && officialPvp > 0) {
            (((officialPvp - currentPrice) / officialPvp) * 100).toInt()
        } else 0

    val diffFromAthPercent: Double
        get() = if (isPriceFresh && athMinPrice > 0) {
            ((currentPrice - athMinPrice) / athMinPrice) * 100
        } else 0.0

    val isAtAth: Boolean
        get() = isPriceFresh && hasSufficientHistory && currentPrice <= athMinPrice + 0.05

    private companion object {
        const val MIN_HISTORY_POINTS = 3
        const val HOUR_MS = 60L * 60L * 1000L
        const val DAY_MS = 24L * HOUR_MS
        const val NINETY_DAYS_MS = 90L * DAY_MS
        const val PRICE_FRESHNESS_WINDOW_MS = DAY_MS
        const val FUTURE_TIMESTAMP_TOLERANCE_MS = 5L * 60L * 1000L
        const val MAX_PRICE_AGE_FOR_HISTORICAL_CLAIMS = 7L * DAY_MS
    }
}
