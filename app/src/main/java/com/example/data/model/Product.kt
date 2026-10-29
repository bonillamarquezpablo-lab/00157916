package com.example.data.model

enum class PriceDisplayStatus {
    /** A live, source-backed observation captured within the freshness window. */
    VERIFIED_CURRENT,
    /** A real saved observation exists, but must not be represented as today's price. */
    SAVED_PENDING_REVALIDATION,
    /** A provider listed an amount, but seller/link is not confirmed; never treat it as a verified quote. */
    UNVERIFIED_LIVE_LISTING,
    /** No source-backed saved price can be safely shown; bundled/demo values stay hidden. */
    DEMONSTRATION
}

/** Shared label for live-search listings whose seller price has not been verified. */
const val PENDING_VERIFICATION_DATE_LABEL = "Pendiente de verificación"

data class Product(
    val id: String,
    val title: String,
    val brand: String,
    val category: ProductCategory,
    val currentPrice: Double,
    val athMinPrice: Double,
    val athMinDate: String,
    val athMinStore: String,
    val athMaxPrice: Double,
    val avg90dPrice: Double,
    val officialPvp: Double,
    val currentStore: Store,
    val storeUrl: String,
    val ean: String,
    val priceHistory: List<PricePoint>,
    val offers: List<StoreOffer>,
    val wasRecentlyInflated: Boolean = false,
    val fakePriorPrice: Double? = null,
    val isTracked: Boolean = false,
    val alertTargetPrice: Double? = null,
    val rating: Double = 4.7,
    val reviewCount: Int = 1240,
    val imageResName: String? = null,
    val imageUrl: String = "",
    /**
     * True only after a current price and its history have been fetched and validated.
     * The bundled catalogue remains unverified by default.
     */
    val isPriceVerified: Boolean = false,
    /** True only when the official reference price has independent source evidence. */
    val isOfficialPvpVerified: Boolean = false,
    val priceObservedAt: Long? = null,
    /** A product price lookup does not verify the demo rating or review count. */
    val isRatingVerified: Boolean = false,
    /** A product price lookup does not verify a bundled EAN. */
    val isEanVerified: Boolean = false,
    /** Provider-backed Google Shopping catalog ID, when available. */
    val googleProductId: String? = null,
    /** Provider which supplied the latest saved observation, e.g. Google Shopping or Amazon.es. */
    val priceSource: String = "",
    /** Latest verified historical observation for display only when no live quote is available. */
    val savedHistoricalPrice: Double? = null,
    val savedHistoricalPriceObservedAt: Long? = null,
    val savedHistoricalPriceSource: String = ""
) {
    /**
     * Last line of defence for legacy/provider rows that reached the catalogue before
     * repository validation. A full smartphone model cannot be shown at accessory-level
     * prices; use the same conservative €45 floor as historical quote validation.
     */
    private fun isPlausiblePriceForThisProduct(price: Double): Boolean {
        if (!price.isFinite() || price <= 0.0) return false
        val looksLikePhone = SmartphoneSubcategory.PHONES.matchesTitle(title)
        val isAccessory = Regex(
            """\b(funda|fundas|carcasa|carcasas|protector|protectores|cristal templado|cable|cargador|adaptador|repuesto|replacement|cover|bumper|pantalla|bateria externa|power bank)\b"""
        ).containsMatchIn(title.lowercase(java.util.Locale.ROOT))
        return !looksLikePhone || isAccessory || price >= MIN_PLAUSIBLE_SMARTPHONE_PRICE_EUR
    }

    /**
     * Historical conclusions require at least three distinct snapshots spanning 90 days,
     * including three observations within the latest 90-day window and a recent last query.
     * One live price can be shown immediately, but is not enough to establish a historical low.
     */
    val hasSufficientHistory: Boolean
        get() {
            if (!isPriceVerified || !isPlausiblePriceForThisProduct(currentPrice)) return false
            // External archive/Google Insight data is useful context, but cannot prove a live-price history.
            // Bad legacy prices are filtered again here so they cannot support a minimum or average.
            val liveTimeline = priceHistory
                .filterNot { it.isExternalHistoryEvidence }
                .filter { isPlausiblePriceForThisProduct(it.price) }
            val timestamps = liveTimeline.map { it.timestamp }.distinct().sorted()
            if (timestamps.size < MIN_HISTORY_POINTS) return false
            val now = System.currentTimeMillis()
            val ninetyDays = 90L * 24L * 60L * 60L * 1000L
            val recentPoints = timestamps.count { it >= now - ninetyDays && it <= now }
            return timestamps.last() - timestamps.first() >= ninetyDays &&
                recentPoints >= MIN_HISTORY_POINTS &&
                now - timestamps.last() <= MAX_PRICE_AGE_FOR_HISTORICAL_CLAIMS
        }

    /**
     * Price badge based on real saved daily observations. The newest point represents the
     * current quote; only earlier points can establish a previous observed minimum.
     */
    val priceBadgeState: PriceBadgeState
        get() {
            val orderedPoints = priceHistory
                .filterNot { it.isExternalHistoryEvidence }
                .filter { isPlausiblePriceForThisProduct(it.price) }
                .sortedBy { it.timestamp }
            val priorPrices = if (orderedPoints.size > 1) {
                orderedPoints.dropLast(1).map { it.price }
            } else {
                emptyList()
            }
            return PriceBadgeClassifier.classify(
                isPriceVerified = isPriceFresh,
                currentPrice = currentPrice,
                priorObservedPrices = priorPrices,
                averagePrice = avg90dPrice,
                hasValidAverage = isPriceFresh && hasSufficientHistory,
                isSavedPendingRevalidation =
                    priceDisplayStatus == PriceDisplayStatus.SAVED_PENDING_REVALIDATION
            )
        }

    val verdict: DealVerdict
        get() = when {
            !isPriceVerified -> DealVerdict.unverified()
            !isPriceFresh -> DealVerdict.insufficientHistory()
            !hasSufficientHistory -> DealVerdict.insufficientHistory()
            else -> DealVerdict.analyze(
                currentPrice = currentPrice,
                athMinPrice = athMinPrice,
                avg90dPrice = avg90dPrice,
                athMaxPrice = athMaxPrice,
                wasRecentlyInflated = wasRecentlyInflated,
                fakePriorPrice = fakePriorPrice
            )
        }

    /** Age of the latest live price observation, in milliseconds; null means unknown/invalid. */
    val priceAgeMillis: Long?
        get() {
            val observedAt = priceObservedAt ?: return null
            val now = System.currentTimeMillis()
            if (observedAt > now + FUTURE_TIMESTAMP_TOLERANCE_MS) return null
            return (now - observedAt).coerceAtLeast(0L)
        }

    /** A live observation older than 24 hours may remain visible, but is not a current deal. */
    val isPriceFresh: Boolean
        get() = isPriceVerified &&
            isPlausiblePriceForThisProduct(currentPrice) &&
            priceAgeMillis?.let { it <= PRICE_FRESHNESS_WINDOW_MS } == true

    /**
     * Separates a current quote from a stale-but-real saved quote and from bundled/demo values.
     * Never infer a real saved price from currentPrice alone: old catalogue rows also contain
     * placeholder numbers, so a verified source flag and valid price are required.
     */
    /** A search-only catalogue row can show its listed amount without asserting seller verification. */
    val isUnverifiedLiveListing: Boolean
        get() = !isPriceVerified &&
            (id.startsWith("online_candidate_") ||
                athMinDate.equals(PENDING_VERIFICATION_DATE_LABEL, ignoreCase = true)) &&
            isPlausiblePriceForThisProduct(currentPrice) &&
            savedHistoricalPrice == null

    /**
     * Builds a transient detail-sheet view of the latest public search listing.
     *
     * This copy is intentionally unverified and must not be written back to Room: it lets the
     * user see the price that was just returned by search without replacing a trusted saved quote
     * or deleting its historical evidence.
     */
    fun withUnverifiedLiveSearchListing(
        listedPrice: Double,
        observedAt: Long,
        sourceUrl: String,
        sourceName: String,
        sourceStore: Store
    ): Product {
        if (!listedPrice.isFinite() || listedPrice <= 0.0 || sourceUrl.isBlank()) return this
        return copy(
            currentPrice = listedPrice,
            athMinDate = PENDING_VERIFICATION_DATE_LABEL,
            athMinStore = "",
            currentStore = sourceStore,
            storeUrl = sourceUrl,
            isPriceVerified = false,
            priceObservedAt = observedAt.takeIf {
                it > 0L && it <= System.currentTimeMillis()
            },
            priceSource = sourceName.trim().ifBlank { sourceStore.name },
            savedHistoricalPrice = null,
            savedHistoricalPriceObservedAt = null,
            savedHistoricalPriceSource = ""
        )
    }

    val priceDisplayStatus: PriceDisplayStatus
        get() = when {
            isPriceFresh -> PriceDisplayStatus.VERIFIED_CURRENT
            isPriceVerified && isPlausiblePriceForThisProduct(currentPrice) ->
                PriceDisplayStatus.SAVED_PENDING_REVALIDATION
            savedHistoricalPrice?.let { isPlausiblePriceForThisProduct(it) } == true &&
                savedHistoricalPriceObservedAt?.let { it > 0L && it <= System.currentTimeMillis() } == true ->
                PriceDisplayStatus.SAVED_PENDING_REVALIDATION
            isUnverifiedLiveListing -> PriceDisplayStatus.UNVERIFIED_LIVE_LISTING
            else -> PriceDisplayStatus.DEMONSTRATION
        }

    val priceFreshnessLabel: String
        get() {
            if (isUnverifiedLiveListing) return "Precio listado en búsqueda pública; vendedor y precio final sin confirmar"
            if (!isPriceVerified) return "Sin observación online válida"
            if (priceObservedAt == null) return "Fecha de observación desconocida"
            val age = priceAgeMillis ?: return "Fecha de observación inválida"
            return when {
                age < 60_000L -> "Consultado hace menos de 1 min"
                age < HOUR_MS -> "Consultado hace menos de 1 h"
                age < DAY_MS -> "Consultado hace ${age / HOUR_MS} h"
                age < 2L * DAY_MS -> "Consultado hace 1 día · conviene confirmar"
                else -> "Consultado hace ${age / DAY_MS} días · conviene volver a consultar"
            }
        }

    val realSavingsAmount: Double
        get() = if (isPriceFresh && isOfficialPvpVerified && officialPvp.isFinite() && officialPvp > 0.0) (officialPvp - currentPrice).coerceAtLeast(0.0) else 0.0

    val realSavingsPercent: Int
        get() = if (isPriceFresh && isOfficialPvpVerified && officialPvp.isFinite() && officialPvp > 0.0) {
            (((officialPvp - currentPrice) / officialPvp) * 100).toInt()
        } else 0

    val diffFromAthPercent: Double
        get() = if (isPriceFresh && athMinPrice > 0) {
            ((currentPrice - athMinPrice) / athMinPrice) * 100
        } else 0.0

    val isAtAth: Boolean
        get() = isPriceFresh && hasSufficientHistory && currentPrice <= athMinPrice + 0.05

    private companion object {
        const val MIN_HISTORY_POINTS = 3
        const val HOUR_MS = 60L * 60L * 1000L
        const val DAY_MS = 24L * HOUR_MS
        const val PRICE_FRESHNESS_WINDOW_MS = DAY_MS
        const val MIN_PLAUSIBLE_SMARTPHONE_PRICE_EUR = 45.0
        const val FUTURE_TIMESTAMP_TOLERANCE_MS = 5L * 60L * 1000L
        const val MAX_PRICE_AGE_FOR_HISTORICAL_CLAIMS = 7L * DAY_MS
    }
}
