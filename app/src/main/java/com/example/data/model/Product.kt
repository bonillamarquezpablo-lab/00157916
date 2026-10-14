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
    val googleProductId: String? = null
) {
    /**
     * Historical conclusions require at least three distinct snapshots spanning 90 days,
     * including three observations within the latest 90-day window and a recent last query.
     * One live price can be shown immediately, but is not enough to establish a historical low.
     */
    val hasSufficientHistory: Boolean
        get() {
            if (!isPriceVerified) return false
            val liveHistory = priceHistory.filterNot { it.isArchiveEvidence }
            val timestamps = liveHistory.map { it.timestamp }.distinct().sorted()
            if (timestamps.size < MIN_HISTORY_POINTS) return false
            val now = System.currentTimeMillis()
            val ninetyDays = 90L * 24L * 60L * 60L * 1000L
            val recentPoints = timestamps.count { it >= now - ninetyDays && it <= now }
            return timestamps.last() - timestamps.first() >= ninetyDays &&
                recentPoints >= MIN_HISTORY_POINTS &&
                now - timestamps.last() <= MAX_PRICE_AGE_FOR_HISTORICAL_CLAIMS
        }

    /**
     * Price badge based on real saved daily observations. The newest point represents the
     * current quote; only earlier points can establish a previous observed minimum.
     */
    val priceBadgeState: PriceBadgeState
        get() {
            val orderedPoints = priceHistory.filterNot { it.isArchiveEvidence }.sortedBy { it.timestamp }
            val priorPrices = if (orderedPoints.size > 1) {
                orderedPoints.dropLast(1).map { it.price }
            } else {
                emptyList()
            }
            return PriceBadgeClassifier.classify(
                isPriceVerified = isPriceVerified,
                currentPrice = currentPrice,
                priorObservedPrices = priorPrices,
                averagePrice = avg90dPrice,
                hasValidAverage = hasSufficientHistory
            )
        }

    val verdict: DealVerdict
        get() = when {
            !isPriceVerified -> DealVerdict.unverified()
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

    val isAtAth: Boolean
        get() = hasSufficientHistory && currentPrice <= athMinPrice + 0.05

    private companion object {
        const val MIN_HISTORY_POINTS = 3
        const val MAX_PRICE_AGE_FOR_HISTORICAL_CLAIMS = 7L * 24L * 60L * 60L * 1000L
    }
}
