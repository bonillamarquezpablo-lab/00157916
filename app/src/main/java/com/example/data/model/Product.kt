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
    /** Provider which supplied the latest saved observation, e.g. Google Shopping or Amazon.es. */
    val priceSource: String = ""
) {
    /**
     * Historical conclusions require at least three distinct snapshots spanning 90 days,
     * including three observations within the latest 90-day window and a recent last query.
     * One live price can be shown immediately, but is not enough to establish a historical low.
     */
    val hasSufficientHistory: Boolean
        get() {
            if (!isPriceVerified) return false
            val timestamps = priceHistory.map { it.timestamp }.distinct().sorted()
            if (timestamps.size < MIN_HISTORY_POINTS) return false
            val now = System.currentTimeMillis()
            val ninetyDays = 90L * 24L * 60L * 60L * 1000L
            val recentPoints = timestamps.count { it >= now - ninetyDays && it <= now }
            return timestamps.last() - timestamps.first() >= ninetyDays &&
                recentPoints >= MIN_HISTORY_POINTS &&
                now - timestamps.last() <= MAX_PRICE_AGE_FOR_HISTORICAL_CLAIMS
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

    val realSavingsAmount: Double
        get() = if (isPriceVerified) (officialPvp - currentPrice).coerceAtLeast(0.0) else 0.0

    val realSavingsPercent: Int
        get() = if (isPriceVerified && officialPvp > 0) {
            (((officialPvp - currentPrice) / officialPvp) * 100).toInt()
        } else 0

    val diffFromAthPercent: Double
        get() = if (isPriceVerified && athMinPrice > 0) {
            ((currentPrice - athMinPrice) / athMinPrice) * 100
        } else 0.0

    /** Provider observations are snapshots, not a promise that a retailer still honours the price. */
    val priceAgeMillis: Long?
        get() = priceObservedAt?.let {
            (System.currentTimeMillis() - it).coerceAtLeast(0L)
        }

    /** A price is considered fresh for 24 hours; older prices remain visible but must be flagged. */
    val isPriceFresh: Boolean
        get() = isPriceVerified && (priceAgeMillis?.let { it <= PRICE_FRESHNESS_WINDOW_MS } == true)

    val priceFreshnessLabel: String
        get() {
            if (!isPriceVerified) return "Sin observación online válida"
            val age = priceAgeMillis ?: return "Fecha de observación desconocida"
            return when {
                age < 60_000L -> "Consultado hace menos de 1 min"
                age < HOUR_MS -> "Consultado hace menos de 1 h"
                age < DAY_MS -> "Consultado hace ${age / HOUR_MS} h"
                age < 2L * DAY_MS -> "Consultado hace 1 día · conviene confirmar"
                else -> "Consultado hace ${age / DAY_MS} días · conviene volver a consultar"
            }
        }

    val isAtAth: Boolean
        get() = isPriceFresh && hasSufficientHistory && currentPrice <= athMinPrice + 0.05

    private companion object {
        const val MIN_HISTORY_POINTS = 3
        const val HOUR_MS = 60L * 60L * 1000L
        const val DAY_MS = 24L * HOUR_MS
        const val PRICE_FRESHNESS_WINDOW_MS = DAY_MS
        const val MAX_PRICE_AGE_FOR_HISTORICAL_CLAIMS = 7L * DAY_MS
    }
}
