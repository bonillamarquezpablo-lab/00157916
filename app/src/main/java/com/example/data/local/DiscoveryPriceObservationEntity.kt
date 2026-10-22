package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Historical quotes retained while a product is quarantined.
 * These rows do not participate in product charts until the user accepts the discovery.
 */
@Entity(
    tableName = "discovery_price_observations",
    indices = [
        Index(value = ["discoveryId", "observedAt"]),
        Index(value = ["discoveryId", "observedAt", "snapshotUrl", "price"], unique = true)
    ]
)
data class DiscoveryPriceObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val discoveryId: String,
    val price: Double,
    val currency: String = "EUR",
    val observedAt: Long,
    /** Snapshot that actually contained the structured offer. */
    val snapshotUrl: String,
    /** Original page that was archived; can differ from the product's destination URL. */
    val sourcePageUrl: String,
    /** Direct product/variant destination extracted from that page's structured offer. */
    val productUrl: String,
    val storeId: String,
    val storeName: String,
    val storeDomain: String,
    val sourceName: String,
    val collectedAt: Long,
    val verificationNote: String
)
