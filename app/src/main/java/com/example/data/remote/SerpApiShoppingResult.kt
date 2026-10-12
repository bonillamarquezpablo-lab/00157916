package com.example.data.remote

import java.net.URI
import java.util.Locale

/**
 * One result from a manual Google Shopping query.
 * Prices are point-in-time observations and must be rechecked at the seller.
 */
data class SerpApiShoppingResult(
    val title: String,
    val price: Double,
    val storeName: String,
    val sourceUrl: String,
    val thumbnailUrl: String = "",
    val delivery: String = "",
    val rating: Double? = null,
    val reviewCount: Int? = null,
    val observedAt: Long,
    /** Whether Amazon reports Prime delivery eligibility; not a personalized account price. */
    val primeEligible: Boolean = false
) {
    val isAmazonOffer: Boolean
        get() {
            val host = runCatching {
                URI(sourceUrl).host?.lowercase(Locale.ROOT)?.removePrefix("www.").orEmpty()
            }.getOrDefault("")
            return storeName.contains("amazon", ignoreCase = true) ||
                host == "amazon.es" ||
                host.startsWith("amazon.") ||
                host.contains(".amazon.")
        }
}
