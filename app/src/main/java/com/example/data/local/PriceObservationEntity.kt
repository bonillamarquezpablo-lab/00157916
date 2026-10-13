package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One recorded price observation. An entry is usable for deal calculations only when
 * isVerified is true and its source, product and capture timestamp have been validated.
 *
 * This table is the foundation for future authorized price providers; it does not itself
 * retrieve prices or turn demo catalogue values into real observations.
 */
@Entity(
    tableName = "price_observations",
    indices = [
        Index(value = ["productId", "observedAt"]),
        Index(value = ["storeId", "observedAt"])
    ]
)
data class PriceObservationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val productId: String,
    val storeId: String,
    val price: Double,
    val currency: String = "EUR",
    val observedAt: Long = System.currentTimeMillis(),
    val sourceUrl: String,
    val sourceName: String,
    val isVerified: Boolean = false,
    val verificationNote: String? = null,
    /** Full title captured from the live search result; blank only for legacy rows. */
    val productTitle: String = "",
    /** Merchant presented by the provider, kept separate from the provider name. */
    val storeName: String = "",
    /** Provider that yielded this observation: Google Shopping or Amazon.es. */
    val sourceType: String = "Google Shopping"
)
