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
    val isPriceVerified: Boolean = false
) {
    val verdict: DealVerdict
        get() = if (!isPriceVerified) {
            DealVerdict.unverified()
        } else {
            DealVerdict.analyze(
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
        get() = isPriceVerified && currentPrice <= athMinPrice + 0.05
}
