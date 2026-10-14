package com.example.data.model

data class PricePoint(
    val timestamp: Long,
    val dateLabel: String,
    val price: Double,
    val storeName: String,
    val eventLabel: String? = null,
    val linkType: String = "directo",
    /** Direct archived snapshot URL for provenance; never a current offer URL. */
    val sourceUrl: String? = null,
    /** Room evidence class; legacy rows are recognized by their explicit archive source label too. */
    val evidenceType: String = "live"
) {
    val isArchiveEvidence: Boolean
        get() = evidenceType.equals("archive", ignoreCase = true) ||
            eventLabel?.contains("Internet Archive", ignoreCase = true) == true ||
            eventLabel?.contains("Wayback", ignoreCase = true) == true
}
