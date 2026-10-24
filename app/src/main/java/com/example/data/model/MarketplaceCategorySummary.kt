package com.example.data.model

/**
 * Lightweight home-screen counts. These values are computed by Room without materializing
 * every full Product (including its price history and retailer offers) on the main screen.
 */
data class MarketplaceCategorySummary(
    val category: ProductCategory,
    val productCount: Int,
    val storeCount: Int
)
