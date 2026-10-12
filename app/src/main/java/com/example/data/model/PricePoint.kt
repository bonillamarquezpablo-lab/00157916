package com.example.data.model

data class PricePoint(
    val timestamp: Long,
    val dateLabel: String,
    val price: Double,
    val storeName: String,
    val eventLabel: String? = null
)
