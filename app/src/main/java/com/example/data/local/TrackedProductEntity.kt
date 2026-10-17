package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracked_products")
data class TrackedProductEntity(
    @PrimaryKey
    val productId: String,
    val targetAlertPrice: Double? = null,
    val isNotificationEnabled: Boolean = true,
    val trackedAtTimestamp: Long = System.currentTimeMillis(),
    val notes: String = ""
)
