package com.example.data.model

data class StoreOffer(
    val store: Store,
    val price: Double,
    val inStock: Boolean = true,
    val shippingInfo: String = "Envío gratis",
    val url: String = "",
    val linkType: String = "directo"
)
