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
     * True only after a current price and its source have been fetched and validated.
     * The bundled catalogue remains unverified by default.
     */
    val isPriceVerified: Boolean = false,
    /** True only when the official reference price came from a trusted source. */
    val isOfficialPvpVerified: Boolean = false,
    /** True only when the rating/review count has independent source provenance. */
    val isRatingVerified: Boolean = false,
    /** True only when the barcode was validated against an independent product source. */
    val isEanVerified: Boolean = false
) {
    /**
     * An all-time low or "good deal" cannot be inferred from a single snapshot. Require at least
     * three verified observations spanning 30 days before claiming historical context.
     */
    val hasSufficientHistoryForDealVerdict: Boolean
        get() = isPriceVerified &&
            priceHistory.size >= 3 &&
            (priceHistory.maxOfOrNull { it.timestamp } ?: 0L) -
            (priceHistory.minOfOrNull { it.timestamp } ?: 0L) >= MIN_HISTORY_COVERAGE_MS

    val verdict: DealVerdict
        get() = when {
            !isPriceVerified -> DealVerdict.unverified()
            !hasSufficientHistoryForDealVerdict -> DealVerdict.insufficientHistory(priceHistory.size)
            else -> DealVerdict.analyze(
                currentPrice = currentPrice,
                athMinPrice = athMinPrice,
                avg90dPrice = avg90dPrice,
                athMaxPrice = athMaxPrice,
                // These fields came from bundled sample data until a provider separately proves them.
                wasRecentlyInflated = wasRecentlyInflated && hasSufficientHistoryForDealVerdict,
                fakePriorPrice = fakePriorPrice.takeIf { hasSufficientHistoryForDealVerdict }
            )
        }

    val realSavingsAmount: Double
        get() = if (isOfficialPvpVerified && isPriceVerified) {
            (officialPvp - currentPrice).coerceAtLeast(0.0)
        } else 0.0

    val realSavingsPercent: Int
        get() = if (isOfficialPvpVerified && isPriceVerified && officialPvp > 0) {
            (((officialPvp - currentPrice) / officialPvp) * 100).toInt().coerceAtLeast(0)
        } else 0

    val diffFromAthPercent: Double
        get() = if (hasSufficientHistoryForDealVerdict && athMinPrice > 0) {
            ((currentPrice - athMinPrice) / athMinPrice) * 100
        } else 0.0

    val isAtAth: Boolean
        get() = hasSufficientHistoryForDealVerdict && currentPrice <= athMinPrice + 0.05

    companion object {
        private const val MIN_HISTORY_COVERAGE_MS = 30L * 24L * 60L * 60L * 1000L
    }
}
