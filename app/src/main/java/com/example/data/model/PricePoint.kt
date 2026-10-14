package com.example.data.model

data class PricePoint(
    val timestamp: Long,
    val dateLabel: String,
    val price: Double,
    val storeName: String,
    val eventLabel: String? = null,
    val linkType: String = "directo",
    /** Direct archived snapshot URL for provenance; never a current offer URL. */
    val sourceUrl: String? = null
)
