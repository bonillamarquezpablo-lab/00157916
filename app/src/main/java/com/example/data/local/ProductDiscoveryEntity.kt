package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A verified-looking product found in an archived structured offer, but not yet part of the
 * user's catalogue. Pending rows are intentionally excluded from ProductRepository's main stream.
 */
@Entity(
    tableName = "product_discoveries",
    indices = [
        Index(value = ["identityKey"], unique = true),
        Index(value = ["status", "lastObservedAt"])
    ]
)
data class ProductDiscoveryEntity(
    @PrimaryKey val id: String,
    val identityKey: String,
    val title: String,
    val brand: String,
    /** ProductCategory.name, including OTHER when automatic classification is inconclusive. */
    val category: String,
    val productUrl: String,
    val latestStoreId: String,
    val latestStoreName: String,
    val latestStoreDomain: String,
    val latestPrice: Double,
    val currency: String = "EUR",
    val firstObservedAt: Long,
    val lastObservedAt: Long,
    val captureCount: Int,
    /** pending, accepted or dismissed. Only pending rows appear in Radar. */
    val status: String = STATUS_PENDING,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_DISMISSED = "dismissed"
    }
}
