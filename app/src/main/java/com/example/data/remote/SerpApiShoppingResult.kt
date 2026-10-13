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
    /**
     * True when the accepted URL stays on a Google domain rather than opening the seller directly.
     * SerpApi can return /search?prds=... links as well as /shopping/product/... links.
     */
    val isGoogleShoppingProductLink: Boolean
        get() {
            val uri = runCatching { URI(sourceUrl) }.getOrNull() ?: return false
            val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.").orEmpty()
            return host.startsWith("google.") ||
                host.endsWith(".google.com") ||
                host.endsWith(".google.es")
        }

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
