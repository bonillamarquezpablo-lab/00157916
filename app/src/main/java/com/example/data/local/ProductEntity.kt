package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val brand: String,
    val category: String,
    val currentPrice: Double,
    val athMinPrice: Double,
    val athMinDate: String,
    val athMinStore: String,
    val athMaxPrice: Double,
    val avg90dPrice: Double,
    val officialPvp: Double,
    val currentStoreId: String,
    val storeUrl: String,
    val ean: String,
    val imageResName: String? = null,
    val imageUrl: String? = null,
    val rating: Double = 4.7,
    val reviewCount: Int = 1200,
    val wasRecentlyInflated: Boolean = false,
    val fakePriorPrice: Double? = null,
    @ColumnInfo(defaultValue = "0")
    val isPriceVerified: Boolean = false
)
