package com.example.data.model

data class PricePoint(
    val timestamp: Long,
    val dateLabel: String,
    val price: Double,
    val storeName: String,
    val eventLabel: String? = null,
    val linkType: String = "directo",
    /** Original URL used to validate the observation; archive captures are not live quotes. */
    val sourceUrl: String? = null,
    /** Evidence provenance persisted in Room (live, archive, google_insights, etc.). */
    val evidenceType: String = "live"
) {
    val isArchiveEvidence: Boolean
        get() = evidenceType.equals("archive", ignoreCase = true) ||
            sourceUrl?.startsWith("https://web.archive.org/web/", ignoreCase = true) == true ||
            sourceUrl?.startsWith("http://web.archive.org/web/", ignoreCase = true) == true

    val isGoogleInsightEvidence: Boolean
        get() = evidenceType.equals("google_insights", ignoreCase = true) ||
            eventLabel?.contains("price_insights", ignoreCase = true) == true

    val isExternalHistoryEvidence: Boolean
        get() = isArchiveEvidence || isGoogleInsightEvidence
}
