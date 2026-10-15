package com.example.data.repository

import androidx.compose.ui.graphics.Color
import com.example.data.local.PriceObservationDao
import com.example.data.local.PriceObservationEntity
import com.example.data.local.ProductDao
import com.example.data.local.ProductEntity
import com.example.data.local.StoreDao
import com.example.data.local.TrackedProductDao
import com.example.data.local.TrackedProductEntity
import com.example.data.model.PricePoint
import com.example.data.model.Product
import com.example.data.model.ProductCategory
import com.example.data.model.StoreOffer
import com.example.data.remote.ArchivedPriceQuote
import com.example.data.remote.SerpApiQuote
import com.example.data.remote.SerpApiPriceInsight
import com.example.data.remote.SerpApiShoppingResult
import com.example.data.remote.RejectedOnlineOffer
import com.example.data.remote.OnlineProductGroup
import com.example.data.remote.groupOnlineShoppingResults
import com.example.data.remote.isIrrelevantMultiProductBundle
import com.example.data.remote.onlineProductIdentityKey
import com.example.data.remote.linkTypeForUrl
import com.example.data.remote.hasAmbiguousBicycleTireDiameter
import com.example.data.remote.isBicycleDrivetrainComponentTitle
import com.example.data.remote.isBicycleDrivetrainSearch
import com.example.data.remote.isBicycleTireListing
import com.example.data.remote.isWrongBicycleTireDiameter
import com.example.data.remote.isWheelFitmentQuery
import com.example.data.remote.containsWheelCategoryTerm
import com.example.data.remote.wheelSetCountForTitle
import com.example.data.model.Store
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class UrlAnalysisResult(
    val queryOrUrl: String,
    val identifiedStore: Store?,
    val detectedTitle: String,
    val matchedProduct: Product?,
    val isKnownRetailerDomain: Boolean,
    val adviceMessage: String
)

class ProductRepository(
    private val storeDao: StoreDao,
    private val productDao: ProductDao,
    private val trackedDao: TrackedProductDao,
    private val priceObservationDao: PriceObservationDao,
    externalScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    // Room is the catalogue source of truth; only persisted products and verified observations
    // may affect the product UI or its price history.
    private val _latestProducts = MutableStateFlow<List<Product>>(emptyList())

    // Prevent a just-inserted online result from being mistaken for an orphan by startup cleanup.
    private val startupCleanupCompleted = CompletableDeferred<Unit>()

    init {
        // Startup reconciliation is non-destructive: never delete catalogue rows automatically.
        // A product without a verified quote or watchlist entry may still be user-owned data.
        // Preserve all rows and only reconcile the cached verification flag from source-backed
        // observations. Any failure is logged and the crash-report handler remains available.
        externalScope.launch {
            try {
                productDao.markProductsWithVerifiedObservations()
                startupCleanupCompleted.complete(Unit)
            } catch (cancelled: CancellationException) {
                startupCleanupCompleted.completeExceptionally(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                Log.e("ProductRepository", "Room startup reconciliation failed; preserving stored products.", failure)
                startupCleanupCompleted.complete(Unit)
            }
        }
    }

    val allStoresStream: Flow<List<Store>> = storeDao.getAllStores().map { list ->
        if (list.isEmpty()) Store.ALL_STORES
        else list.map { it.toDomainStore() }
    }

    /**
     * Live directory of sellers represented by at least one valid saved EUR price.
     * The static 33-store directory remains available for resolving existing products, but it is
     * deliberately not used as the list shown under the main "Tiendas" tab.
     */
    val storesWithSavedPricesStream: Flow<List<Store>> = combine(
        storeDao.getAllStores(),
        priceObservationDao.observeAllVerified()
    ) { storeEntities, observations ->
        val configuredStores = (storeEntities.map { it.toDomainStore() } + Store.ALL_STORES)
            .distinctBy { it.id }

        observations.asSequence()
            .filter { observation ->
                observation.isVerified &&
                    observation.currency.equals("EUR", ignoreCase = true) &&
                    observation.price.isFinite() && observation.price in MIN_HISTORY_PRICE_EUR..MAX_HISTORY_PRICE_EUR &&
                    observation.observedAt in 1L..System.currentTimeMillis() &&
                    observation.storeId.isNotBlank()
            }
            .mapNotNull { observation ->
                val merchantHost = merchantHostForObservation(observation)
                val configuredStore = configuredStoreForObservation(
                    observation = observation,
                    stores = configuredStores,
                    merchantHost = merchantHost
                )
                val identity = configuredStore?.id ?: normalizeStoreIdentity(observation.storeName)
                    .takeIf { it.isNotBlank() }
                    ?: merchantHost
                    ?: observation.storeId
                StorePriceCandidate(
                    key = configuredStore?.id?.let { "configured:$it" } ?: "merchant:$identity",
                    observation = observation,
                    configuredStore = configuredStore,
                    merchantHost = merchantHost
                )
            }
            .groupBy { it.key }
            .map { (key, rows) ->
                val configuredStore = rows.firstNotNullOfOrNull { it.configuredStore }
                val merchantHost = rows.firstNotNullOfOrNull { it.merchantHost }
                val savedPriceCount = rows.size
                if (configuredStore != null) {
                    configuredStore.copy(
                        savedPriceCount = savedPriceCount,
                        hasOfficialSite = true
                    )
                } else {
                    val merchantName = rows.map { it.observation.storeName.trim() }
                        .filter { it.isNotBlank() }
                        .groupingBy { it }
                        .eachCount()
                        .entries
                        .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }
                            .thenByDescending { it.key.length })
                        ?.key
                        ?: merchantHost
                        ?: "Tienda online"
                    Store(
                        id = stableOnlineId("price_store_", key),
                        name = merchantName,
                        domain = merchantHost ?: "Google Shopping",
                        brandColor = Color(0xFF047857),
                        category = "Tienda con precios",
                        physicalStores = false,
                        hasLivePriceIntegration = false,
                        savedPriceCount = savedPriceCount,
                        hasOfficialSite = merchantHost != null
                    )
                }
            }
            .distinctBy { normalizeStoreIdentity(it.name) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private data class StorePriceCandidate(
        val key: String,
        val observation: PriceObservationEntity,
        val configuredStore: Store?,
        val merchantHost: String?
    )

    // Persisted Room rows + verified observations + local tracking are the canonical stream.
    // No demo or simulated price points are composed into this flow.
    val allProductsStream: Flow<List<Product>> = combine(
        productDao.getAllProducts(),
        storeDao.getAllStores(),
        trackedDao.getAllTracked(),
        priceObservationDao.observeAllVerified()
    ) { productEntities, storeEntities, trackedEntities, observations ->
        val stores = storeEntities.map { it.toDomainStore() }.ifEmpty { Store.ALL_STORES }
        val trackingMap = trackedEntities.associateBy { it.productId }
        val observationsByProduct = observations.groupBy { it.productId }

        productEntities.map { entity ->
            val product = entity.toDomainProduct(
                stores = stores,
                verifiedObservations = observationsByProduct[entity.id].orEmpty()
            )
            val trackingInfo = trackingMap[product.id]
            product.copy(
                isTracked = trackingInfo != null,
                alertTargetPrice = trackingInfo?.targetAlertPrice
            )
        }
    }.onEach { currentProducts ->
        _latestProducts.value = currentProducts
    }

    val trackedProductsStream: Flow<List<Product>> = allProductsStream.map { list ->
        list.filter { it.isTracked }
    }

    val todayAthDealsStream: Flow<List<Product>> = allProductsStream.map { list ->
        list.filter { it.isAtAth }
    }

    /**
     * Store only provider-backed, valid EUR offers. Direct merchant URLs and Google Shopping
     * destinations are both allowed; link type is recorded separately for the UI.
     */
    suspend fun recordSerpApiQuotes(quotes: List<SerpApiQuote>): Int {
        startupCleanupCompleted.await()
        // Resolve titles from persisted products only. The bundled demonstration catalogue is not
        // a valid source for a real market observation.
        val storedProducts = productDao.getAllProductsSnapshot()
        val titleById = storedProducts.associate { it.id to it.title }.toMutableMap().apply {
            _latestProducts.value.forEach { product -> put(product.id, product.title) }
        }
        val validQuotes = quotes.filter { quote ->
            val title = titleById[quote.productId].orEmpty()
            quote.productId.isNotBlank() &&
                quote.price.isFinite() && quote.price > 0.0 && quote.price <= MAX_HISTORY_PRICE_EUR &&
                quote.currency == "EUR" &&
                quote.observedAt >= HISTORY_START_TIMESTAMP &&
                isEligibleHistoryTitle(title) &&
                !isPromotionalBulkListing(title, "") &&
                isEligibleSeller(quote.storeName, quote.sourceUrl) &&
                isPlausibleHistoryPrice(title, quote.price)
        }
        if (validQuotes.isEmpty()) return 0

        var inserted = 0
        for (quote in validQuotes) {
            val productTitle = titleById[quote.productId] ?: continue
            val storeId = canonicalStoreId(quote.storeName, quote.sourceUrl)
            val observation = PriceObservationEntity(
                productId = quote.productId,
                storeId = storeId,
                price = quote.price,
                currency = "EUR",
                observedAt = quote.observedAt,
                sourceUrl = quote.sourceUrl,
                sourceName = "Google Shopping",
                isVerified = true,
                verificationNote = "Precio observado por SerpApi; confirmar precio final y disponibilidad en la tienda.",
                productTitle = productTitle,
                storeName = quote.storeName,
                sourceType = "Google Shopping",
                linkType = linkTypeForUrl(quote.sourceUrl)
            )
            if (saveDailyObservation(observation)) inserted++
            productDao.updatePriceVerification(quote.productId, true)
            quote.googleProductId?.takeIf { it.isNotBlank() }?.let { googleId ->
                val savedProduct = productDao.getProductById(quote.productId)
                if (savedProduct != null && savedProduct.googleProductId != googleId) {
                    productDao.insert(savedProduct.copy(googleProductId = googleId))
                }
            }
        }
        // Preserve the previous test contract: a validated provider quote is a successful
        // configuration test even when today's copy was already recorded and deduplicated.
        return validQuotes.map { it.productId }.distinct().size
    }

    /**
     * Applies the same conservative quality rules to visible results and Room history.
     * This keeps a suspicious price out of both the UI and the permanent price log.
     */
    fun filterOnlineSearchResults(
        results: List<SerpApiShoppingResult>,
        searchQuery: String = ""
    ): List<SerpApiShoppingResult> {
        val queryModelMarkers = explicitModelMarkers(searchQuery)
        val eligible = results.filter { result ->
            val accessoryForMainSearch =
                isDeviceSearchQuery(searchQuery) &&
                    !isAccessorySearchQuery(searchQuery) &&
                    isAccessoryListing(result.title)
            result.title.isNotBlank() &&
                result.storeName.isNotBlank() &&
                isMerchantDestinationConsistent(result.storeName, result.sourceUrl) &&
                result.price.isFinite() &&
                result.price > 0.0 &&
                result.price <= MAX_HISTORY_PRICE_EUR &&
                result.observedAt >= HISTORY_START_TIMESTAMP &&
                !result.isRentalOrMonthly &&
                !isPromotionalBulkListing(result.title, searchQuery) &&
                !isIrrelevantMultiProductBundle(searchQuery, result.title) &&
                (!isHallEffectKeyboardSearch(searchQuery) || isHallEffectKeyboardResult(result.title)) &&
                (!isBicycleDrivetrainSearch(searchQuery) || isBicycleDrivetrainComponentTitle(result.title)) &&
                !isWrongBicycleTireDiameter(searchQuery, result.title) &&
                !hasStrictModelMismatch(
                    searchQuery,
                    queryModelMarkers,
                    result.title,
                    allowAccessoryMemoryMismatch = accessoryForMainSearch
                )
        }
        if (eligible.isEmpty()) return emptyList()

        // Compare outliers only with the same product identity, not every result returned by
        // a broad search. This is especially important for wheels: one 14-inch rim at 111 EUR
        // must not be treated as suspicious merely because a different listing is a four-rim set.
        val queryHasWheelSize = Regex("""(?i)\b(1[0-9]|2[0-4])\b""").containsMatchIn(searchQuery)
        val wheelTitleCount = eligible.count { containsWheelCategoryTerm(it.title) }
        val isWheelSearch = isWheelFitmentQuery(searchQuery) ||
            containsWheelCategoryTerm(searchQuery) ||
            (queryHasWheelSize && wheelTitleCount >= 2)

        // Compare set prices only against other sets of the same count, never against a single wheel.
        fun wheelBundleCount(title: String): String =
            wheelSetCountForTitle(title)?.toString() ?: "single"

        fun comparisonKey(result: SerpApiShoppingResult): String {
            val identity = onlineProductIdentityKey(result.title)
            return if (isWheelSearch) identity + "|wheel-set:" + wheelBundleCount(result.title) else identity
        }

        val suspiciousByGroup = eligible.groupBy(::comparisonKey).mapValues { (_, comparableOffers) ->
            val trustedPrices = comparableOffers.filterNot {
                it.isSecondHand || it.isAccessoryOffer || it.isFitmentUnverified || it.isCategoryUnverified
            }.map { it.price }.sorted()
            val fallbackPrices = comparableOffers.filterNot { it.isFitmentUnverified }
                .map { it.price }.sorted()
            val prices = trustedPrices.ifEmpty { fallbackPrices }
            if (prices.size < 2) {
                // One listing alone provides no same-product reference for a statistical outlier.
                false to (prices.singleOrNull() ?: 0.0)
            } else {
                val median = if (prices.size % 2 == 1) prices[prices.size / 2]
                    else (prices[prices.size / 2 - 1] + prices[prices.size / 2]) / 2.0
                true to median
            }
        }
        return eligible.map { result ->
            val (hasComparableReference, median) = suspiciousByGroup[comparisonKey(result)] ?: (false to 0.0)
            result.copy(
                // Multi-size tyre listings stay visible for review, but their price cannot be
                // assigned to one exact diameter and therefore must not enter Mercado/history.
                isCategoryUnverified = result.isCategoryUnverified ||
                    hasAmbiguousBicycleTireDiameter(searchQuery, result.title),
                // Keep suspicious offers visible for diagnosis, but never count them as trusted prices.
                isSuspiciousPrice = result.isSuspiciousPrice ||
                    (!result.isFitmentUnverified && !isPlausibleHistoryPrice(result.title, result.price)) ||
                    (!result.isFitmentUnverified &&
                        hasComparableReference && median > 0.0 && result.price < median * 0.30)
            )
        }
    }

    /**
     * Explains online offers rejected by trust/history rules without saving them.
     * This is a pure in-memory diagnosis: it does not write Room or affect best-price selection.
     */
    fun diagnoseOnlineSearchRejections(
        results: List<SerpApiShoppingResult>,
        searchQuery: String = ""
    ): List<RejectedOnlineOffer> {
        val accepted = filterOnlineSearchResults(results, searchQuery)
        fun key(result: SerpApiShoppingResult): String =
            listOf(
                result.title.trim().lowercase(Locale.ROOT),
                result.storeName.trim().lowercase(Locale.ROOT),
                result.price.toString(),
                result.sourceUrl.trim().lowercase(Locale.ROOT)
            ).joinToString("|")
        val acceptedByKey = accepted.associateBy(::key)
        val queryModelMarkers = explicitModelMarkers(searchQuery)

        return results.mapNotNull { result ->
            val filtered = acceptedByKey[key(result)]
            val visibleButNotHistory = filtered != null && (
                result.isSecondHand ||
                    result.isFitmentUnverified ||
                    result.isCategoryUnverified ||
                    (isDeviceSearchQuery(searchQuery) && !isAccessorySearchQuery(searchQuery) &&
                        isAccessoryListing(result.title))
            )
            if (visibleButNotHistory) return@mapNotNull null
            val reason = when {
                result.title.isBlank() || result.storeName.isBlank() ->
                    "Datos incompletos"
                !result.price.isFinite() || result.price <= 0.0 || result.price > MAX_HISTORY_PRICE_EUR ->
                    "Sin precio válido"
                isWrongBicycleTireDiameter(searchQuery, result.title) ->
                    "Diámetro de cubierta distinto del solicitado"
                isBicycleDrivetrainSearch(searchQuery) && !isBicycleDrivetrainComponentTitle(result.title) ->
                    "No es un componente de la transmisión de bicicleta solicitado"
                !isPlausibleHistoryPrice(result.title, result.price) ->
                    "Precio sospechoso: fuera del rango plausible para esta categoría"
                result.isRentalOrMonthly ->
                    "Alquiler o cuota mensual"
                isPromotionalBulkListing(result.title, searchQuery) ->
                    "Merchandising promocional o lote no solicitado"
                isIrrelevantMultiProductBundle(searchQuery, result.title) ->
                    "Pack con varios productos no solicitado"
                !isMerchantDestinationConsistent(result.storeName, result.sourceUrl) ->
                    "El dominio del enlace no coincide con el vendedor"
                result.isSecondHand ->
                    "Segunda mano o reacondicionado"
                hasStrictModelMismatch(searchQuery, queryModelMarkers, result.title) ->
                    "Otro modelo o variante"
                !isEligibleSeller(result.storeName, result.sourceUrl) ->
                    "Otro modelo o vendedor no elegible"
                !isEligibleHistoryTitle(result.title) ->
                    "Título no elegible para historial"
                filtered == null ->
                    "Otro modelo o vendedor no elegible"
                filtered.isSuspiciousPrice ->
                    "Precio sospechoso: demasiado bajo frente a ofertas comparables"
                !isPlausibleHistoryPrice(result.title, result.price) ->
                    "Precio sospechoso"
                else -> null
            } ?: return@mapNotNull null

            RejectedOnlineOffer(
                title = result.title.ifBlank { "Título no disponible" },
                priceLabel = if (result.price.isFinite() && result.price > 0.0)
                    String.format(Locale.ROOT, "%.2f", result.price)
                else "Precio no disponible",
                reason = reason,
                provider = if (result.isAmazonOffer) "Amazon.es" else "Google Shopping"
            )
        }.distinctBy { "${it.provider}|${it.title}|${it.priceLabel}|${it.reason}" }
    }

    /**
     * Revalidates rows already stored in Room before they can affect visible history.
     * This is intentionally non-destructive: invalid legacy rows stay in the database for audit,
     * but cannot set current prices, minima, maxima, averages or deal badges.
     */
    private fun isEligibleObservationForHistory(observation: PriceObservationEntity): Boolean =
        observation.isVerified &&
            observation.productId.isNotBlank() &&
            observation.currency == "EUR" &&
            observation.price.isFinite() &&
            observation.price in MIN_HISTORY_PRICE_EUR..MAX_HISTORY_PRICE_EUR &&
            parseHttpUri(observation.sourceUrl)?.scheme.equals("https", ignoreCase = true) &&
            isEligibleHistoryTitle(observation.productTitle) &&
            !isPromotionalBulkListing(observation.productTitle, "") &&
            isEligibleSeller(observation.storeName, observation.sourceUrl) &&
            isPlausibleHistoryPrice(observation.productTitle, observation.price)

    /**
     * Conservative absolute floors catch a lone impossible-looking device price even when there
     * are no comparable offers for median-based outlier detection. Explicit accessories are exempt:
     * a cheap case or cable must not be rejected because its title mentions a laptop or console.
     */
    private fun isPlausibleHistoryPrice(title: String, price: Double): Boolean {
        if (!price.isFinite() || price !in MIN_HISTORY_PRICE_EUR..MAX_HISTORY_PRICE_EUR) return false
        val normalized = normalizedProductTitle(title)
        val accessory = !isBicycleTireListing(title) && Regex(
            """\b(funda|case|carcasa|protector|cristal|cable|cargador|adaptador|repuesto|replacement|cover|bumper|pantalla|bateria|solo caja|dummy|mando|controller|gamepad|videojuego|juego)\b"""
        ).containsMatchIn(normalized)
        if (accessory) return true

        val drivetrainPart = Regex(
            """\b(cassette|casete|pi[nñ]ones?|sprockets?|cambio trasero|rear derailleur|derailleur|desviador|shifter|manetas?|pulsadores?|cadena|chain)\b"""
        ).containsMatchIn(normalized)
        val completeDrivetrain = Regex("""\b(grupo|groupset|kit completo|complete group|pack|juego|set)\b""")
            .containsMatchIn(normalized)
        if (drivetrainPart && !completeDrivetrain && price > 600.0) return false
        if (isBicycleTireListing(title) && price > 350.0) return false

        val smartphone = Regex(
            """\b(iphone|redmi|poco|pixel|oneplus|realme|oppo|vivo|honor|smartphone|galaxy\s+(s|a|z|m)\s?\d{1,2})\b"""
        ).containsMatchIn(normalized)
        if (smartphone && price < 45.0) return false

        val tablet = Regex("""\b(ipad|tablet|galaxy\s+tab|lenovo\s+tab)\b""")
            .containsMatchIn(normalized)
        if (tablet && price < 50.0) return false

        val laptop = Regex("""\b(laptop|portatil|macbook|chromebook|notebook)\b""")
            .containsMatchIn(normalized)
        if (laptop && price < 100.0) return false

        val television = Regex("""\b(oled|qled|televisor|television|smart\s+tv|tv\s+(oled|qled|samsung|lg|sony))\b""")
            .containsMatchIn(normalized)
        if (television && price < 80.0) return false

        val console = Regex("""\b(playstation\s*5|ps5|xbox\s+(series|one)|nintendo\s+switch)\b""")
            .containsMatchIn(normalized)
        if (console && price < 70.0) return false

        return true
    }

    /**
     * Persist each valid "new in store" result returned by a manual online search.
     * Results are observations, not synthetic historical points. A known catalogue product
     * reuses its product ID; an unmatched result still gets a stable Room identity and title.
     */
    suspend fun recordOnlineSearchResults(
        results: List<SerpApiShoppingResult>,
        sourceType: String,
        searchQuery: String = ""
    ): Int {
        // Startup cleanup must finish before inserting a product row, otherwise cleanup can
        // race between the product insert and its first Room observation.
        startupCleanupCompleted.await()
        val normalizedSource = when (sourceType) {
            "AMAZON" -> "Amazon.es"
            "GOOGLE_SHOPPING" -> "Google Shopping"
            "Amazon.es", "Google Shopping" -> sourceType
            else -> return 0
        }
        // Suspicious prices remain visible as unverified Room observations. Second-hand offers
        // and wheel listings missing requested fitment dimensions stay visible in the search UI,
        // but are not added to Mercado or its price history.
        val eligible = filterOnlineSearchResults(results, searchQuery)
            .filterNot { it.isSecondHand || it.isFitmentUnverified || it.isCategoryUnverified }
            // Accessories remain visible in online search, but are not saved as the main device.
            .filterNot {
                isDeviceSearchQuery(searchQuery) && !isAccessorySearchQuery(searchQuery) &&
                    isAccessoryListing(it.title)
            }
        if (eligible.isEmpty()) return 0

        var inserted = 0
        val currentEntities = productDao.getAllProductsSnapshot().toMutableList()
        val groups = groupOnlineShoppingResults(eligible)
        for (group in groups) {
            val bestOffer = group.cheapestOffer
            val storeId = canonicalStoreId(bestOffer.storeName, bestOffer.sourceUrl)
            val existingProduct = currentEntities.firstOrNull {
                onlineProductIdentityKey(it.title) == group.identityKey
            }
            val productId = existingProduct?.id
                ?: stableOnlineId("online_product_", group.identityKey)
            // Keep the persisted title and representative image from the same provider listing.
            val displayTitle = group.title
            val hasTrustedOffer = group.offers.any { !it.isSuspiciousPrice }
            val currentMinimum = if (hasTrustedOffer) group.minPrice else existingProduct?.currentPrice ?: 0.0
            val highestToday = group.offers.maxOfOrNull { offer ->
                offer.price / (offer.wheelSetCount ?: 1)
            } ?: currentMinimum
            val productEntity = if (existingProduct != null) {
                existingProduct.copy(
                    title = displayTitle,
                    brand = existingProduct.brand.takeIf { it.isNotBlank() }
                        ?: inferProductBrand(displayTitle),
                    category = if (existingProduct.category == ProductCategory.ALL.name) {
                        inferProductCategory(displayTitle, searchQuery).name
                    } else existingProduct.category,
                    currentPrice = currentMinimum,
                    athMinPrice = if (existingProduct.athMinPrice > 0.0) existingProduct.athMinPrice else currentMinimum,
                    athMinDate = existingProduct.athMinDate.ifBlank { "Historial en construcción" },
                    athMinStore = bestOffer.storeName,
                    athMaxPrice = maxOf(existingProduct.athMaxPrice, highestToday),
                    currentStoreId = storeId,
                    storeUrl = bestOffer.sourceUrl,
                    imageUrl = group.representativeImageUrl.takeIf { it.isNotBlank() }
                        ?: existingProduct.imageUrl.takeIf { existingProduct.title.equals(displayTitle, ignoreCase = true) },
                    isPriceVerified = hasTrustedOffer || existingProduct.isPriceVerified,
                    googleProductId = bestOffer.googleProductId
                        ?: group.offers.firstNotNullOfOrNull { it.googleProductId }
                        ?: existingProduct.googleProductId
                )
            } else {
                ProductEntity(
                    id = productId,
                    title = displayTitle,
                    brand = inferProductBrand(displayTitle),
                    category = inferProductCategory(displayTitle, searchQuery).name,
                    currentPrice = currentMinimum,
                    athMinPrice = currentMinimum,
                    athMinDate = "Historial en construcción",
                    athMinStore = bestOffer.storeName,
                    athMaxPrice = highestToday,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = storeId,
                    storeUrl = bestOffer.sourceUrl,
                    ean = "",
                    imageUrl = group.representativeImageUrl.takeIf { it.isNotBlank() },
                    rating = 0.0,
                    reviewCount = 0,
                    isPriceVerified = group.offers.any { !it.isSuspiciousPrice },
                    googleProductId = bestOffer.googleProductId
                        ?: group.offers.firstNotNullOfOrNull { it.googleProductId }
                )
            }
            productDao.insert(productEntity)
            if (existingProduct == null) currentEntities += productEntity

            // Keep one best offer per merchant and day. A later search updates that day's row
            // rather than losing the refreshed price or creating a duplicate.
            val bestOfferPerStore = group.offers
                .groupBy { offer -> canonicalStoreId(offer.storeName, offer.sourceUrl) }
                .values
                .mapNotNull { offers ->
                    offers.filterNot { it.isSuspiciousPrice }.minByOrNull { it.price }
                        ?: offers.minByOrNull { it.price }
                }
            for (result in bestOfferPerStore) {
                val offerStoreId = canonicalStoreId(result.storeName, result.sourceUrl)
                val observation = PriceObservationEntity(
                    productId = productId,
                    storeId = offerStoreId,
                    price = result.price / (result.wheelSetCount ?: 1),
                    currency = "EUR",
                    observedAt = result.observedAt,
                    sourceUrl = result.sourceUrl,
                    sourceName = normalizedSource,
                    isVerified = !result.isSuspiciousPrice,
                    verificationNote = if (result.isSuspiciousPrice) {
                        "Precio sospechoso: inferior al 30 % de la mediana de la búsqueda. Conservado como observación sin verificar y excluido del mejor precio."
                    } else {
                        "Resultado online válido en euros; precio y disponibilidad pendientes de confirmación en la tienda."
                    },
                    productTitle = result.title,
                    storeName = result.storeName,
                    sourceType = normalizedSource,
                    linkType = result.linkType
                )
                if (saveDailyObservation(observation)) inserted++
            }
        }
        return inserted
    }

    /**
     * Persists prices parsed from public Internet Archive captures for a product that already has
     * a current provider-backed row. Archive points use capture time as observedAt and separately
     * record when Mínimo imported them. They never update the product's current price.
     */
    suspend fun recordArchivedPriceHistory(
        expectedTitle: String,
        originalUrl: String,
        storeName: String,
        quotes: List<ArchivedPriceQuote>
    ): Int {
        startupCleanupCompleted.await()
        if (expectedTitle.isBlank() || originalUrl.isBlank() || quotes.isEmpty()) return 0
        val originalUri = parseHttpUri(originalUrl) ?: return 0
        if (!originalUri.scheme.equals("https", ignoreCase = true)) return 0

        val products = productDao.getAllProductsSnapshot()
        val identity = onlineProductIdentityKey(expectedTitle)
        val normalizedOriginalUrl = originalUrl.trimEnd('/')
        val product = products.firstOrNull {
            onlineProductIdentityKey(it.title) == identity &&
                (it.isPriceVerified ||
                    it.storeUrl.trimEnd('/').equals(normalizedOriginalUrl, ignoreCase = true))
        } ?: return 0
        val now = System.currentTimeMillis()
        val earliestAllowed = now - (1605L * 24L * 60L * 60L * 1000L)
        val merchantName = storeName.ifBlank { originalUri.host.orEmpty() }
        val storeId = canonicalStoreId(merchantName, originalUrl)
        var inserted = 0

        for (quote in quotes.distinctBy { it.observedAt to it.price }) {
            val snapshotUri = parseHttpUri(quote.snapshotUrl) ?: continue
            if (!snapshotUri.host.equals("web.archive.org", ignoreCase = true)) continue
            val snapshotTimestamp = Regex("""^/web/(\d{14})id_/""")
                .find(snapshotUri.path.orEmpty())?.groupValues?.getOrNull(1)?.let { rawTimestamp ->
                    runCatching {
                        SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
                            isLenient = false
                            timeZone = TimeZone.getTimeZone("UTC")
                        }.parse(rawTimestamp)?.time
                    }.getOrNull()
                } ?: continue
            if (kotlin.math.abs(snapshotTimestamp - quote.observedAt) > 1_000L) continue
            if (!quote.originalUrl.trimEnd('/').equals(originalUrl.trimEnd('/'), ignoreCase = true)) continue
            if (quote.currency != "EUR" ||
                !quote.price.isFinite() ||
                quote.price !in MIN_HISTORY_PRICE_EUR..MAX_HISTORY_PRICE_EUR ||
                quote.observedAt < earliestAllowed ||
                quote.observedAt >= now ||
                quote.productTitle.isBlank()
            ) continue

            val observation = PriceObservationEntity(
                productId = product.id,
                storeId = storeId,
                price = quote.price,
                currency = "EUR",
                observedAt = quote.observedAt,
                sourceUrl = quote.snapshotUrl,
                sourceName = "Internet Archive (Wayback Machine)",
                isVerified = true,
                verificationNote = "Precio EUR extraído de metadatos de producto en una captura pública archivada. Es un precio histórico anunciado; no confirma impuestos, envío, disponibilidad ni precio final.",
                productTitle = quote.productTitle,
                storeName = merchantName,
                sourceType = "Internet Archive",
                linkType = "Archivo histórico",
                collectedAt = now,
                evidenceType = "archive",
                sourcePageUrl = originalUrl
            )
            if (saveDailyObservation(observation)) inserted++
        }
        return inserted
    }

    /**
     * Persists dated price_insights returned by Google Product API. These are historical
     * observations from Google, not prices observed by Mínimo on those dates. A known,
     * verified product and the exact Google catalog item must match before anything is saved.
     */
    suspend fun recordGooglePriceInsightsHistory(
        productId: String,
        expectedTitle: String,
        googleCatalogId: String,
        insights: List<SerpApiPriceInsight>
    ): Int {
        startupCleanupCompleted.await()
        if (productId.isBlank() || expectedTitle.isBlank() ||
            !googleCatalogId.matches(Regex("[0-9]{5,}")) || insights.isEmpty()
        ) return 0

        val product = productDao.getProductById(productId) ?: return 0
        if (!product.isPriceVerified ||
            onlineProductIdentityKey(product.title) != onlineProductIdentityKey(expectedTitle)
        ) return 0

        val now = System.currentTimeMillis()
        val earliestAllowed = now - (1605L * 24L * 60L * 60L * 1000L)
        val sourceUrl = "https://www.google.com/shopping/product/$googleCatalogId"
        var inserted = 0
        for (quote in insights.distinctBy { Triple(it.storeName.lowercase(Locale.ROOT), it.observedAt, it.price) }) {
            if (quote.currency != "EUR" ||
                !isSafeCatalogTitleMatch(quote.productTitle, product.title) ||
                quote.observedAt < earliestAllowed ||
                quote.observedAt >= now ||
                !quote.price.isFinite() ||
                quote.price !in MIN_HISTORY_PRICE_EUR..MAX_HISTORY_PRICE_EUR ||
                !isEligibleHistoryTitle(quote.productTitle) ||
                !isEligibleSeller(quote.storeName, sourceUrl) ||
                !isPlausibleHistoryPrice(quote.productTitle, quote.price)
            ) continue

            val storeId = canonicalStoreId(quote.storeName, sourceUrl)
            val observation = PriceObservationEntity(
                productId = product.id,
                storeId = storeId,
                price = quote.price,
                currency = "EUR",
                observedAt = quote.observedAt,
                sourceUrl = sourceUrl,
                sourceName = "Google Product API · price_insights",
                isVerified = true,
                verificationNote = "Precio histórico fechado devuelto por Google Product API. Es una observación del proveedor, no una consulta de Mínimo realizada ese día; puede tener huecos y no garantiza precio final, envío ni disponibilidad.",
                productTitle = quote.productTitle,
                storeName = quote.storeName,
                sourceType = "Google Product API · price_insights",
                linkType = "Historial Google Shopping",
                collectedAt = now,
                evidenceType = "google_insights",
                sourcePageUrl = sourceUrl
            )
            if (saveDailyObservation(observation)) inserted++
        }
        return inserted
    }

    /**
     * Save a single Google Product API response against the already-identified product row.
     * No new catalogue product is created and no observation is saved if the product was not
     * safely identified by the API parser.
     */
    suspend fun recordProductComparisonOffers(
        productId: String,
        expectedTitle: String,
        offers: List<SerpApiShoppingResult>
    ): Int {
        startupCleanupCompleted.await()
        val product = productDao.getProductById(productId) ?: return 0
        if (expectedTitle.isBlank() || onlineProductIdentityKey(product.title) != onlineProductIdentityKey(expectedTitle)) {
            return 0
        }
        val valid = filterOnlineSearchResults(offers, expectedTitle)
            .filterNot { it.isSecondHand || it.isRentalOrMonthly }
            .filter { isEligibleSeller(it.storeName, it.sourceUrl) && isEligibleHistoryTitle(it.title) }
            .groupBy { canonicalStoreId(it.storeName, it.sourceUrl) }
            .values
            .mapNotNull { rows ->
                rows.filterNot { it.isSuspiciousPrice }.minByOrNull { it.price }
                    ?: rows.minByOrNull { it.price }
            }
        if (valid.isEmpty()) return 0

        var savedCount = 0
        for (offer in valid) {
            val storeId = canonicalStoreId(offer.storeName, offer.sourceUrl)
            val observation = PriceObservationEntity(
                productId = productId,
                storeId = storeId,
                price = offer.price / (offer.wheelSetCount ?: 1),
                currency = "EUR",
                observedAt = offer.observedAt,
                sourceUrl = offer.sourceUrl,
                sourceName = "SerpApi · Google Product API",
                isVerified = !offer.isSuspiciousPrice,
                verificationNote = if (offer.isSuspiciousPrice) {
                    "Precio sospechoso según la mediana de ofertas; conservado sin verificar."
                } else {
                    "Oferta de tienda recuperada con Google Product API; confirma precio final y disponibilidad."
                },
                productTitle = product.title,
                storeName = offer.storeName,
                sourceType = "Google Shopping",
                linkType = offer.linkType
            )
            if (saveDailyObservation(observation)) savedCount++
        }
        if (savedCount > 0) {
            productDao.updatePriceVerification(productId, true)
            val googleId = valid.firstNotNullOfOrNull { it.googleProductId }
            if (!googleId.isNullOrBlank() && product.googleProductId != googleId) {
                productDao.insert(product.copy(googleProductId = googleId))
            }
        }
        return savedCount
    }

    /** Resolve a persisted online product when a grouped result card is tapped. */
    suspend fun awaitProductForOnlineIdentity(identityKey: String): Product {
        // Wait until Room has emitted the saved observation as well as the product row. That way
        // opening a result immediately after a search shows the first point instead of a transient
        // empty-history state caused by Room flows emitting on separate table invalidations.
        return allProductsStream.first { products ->
            products.any { product ->
                onlineProductIdentityKey(product.title) == identityKey &&
                    product.isPriceVerified &&
                    product.priceHistory.isNotEmpty()
            }
        }.first { product ->
            onlineProductIdentityKey(product.title) == identityKey &&
                product.isPriceVerified &&
                product.priceHistory.isNotEmpty()
        }
    }

    /**
     * Save one verified row per product/merchant/local day. Only replace an existing row when
     * the new search is newer, or has a lower price at the exact same timestamp.
     */
    private suspend fun saveDailyObservation(observation: PriceObservationEntity): Boolean {
        val (dayStart, dayEnd) = localDayBounds(observation.observedAt)
        // Room wraps lookup + insert/update in one transaction so simultaneous refreshes
        // cannot both see an empty day and create duplicate observations.
        return priceObservationDao.saveDailyObservationIfNewer(
            observation = observation,
            dayStart = dayStart,
            dayEnd = dayEnd
        )
    }

    private fun inferProductBrand(title: String): String {
        val brands = listOf(
            "Apple", "Samsung", "Xiaomi", "Redmi", "POCO", "Google", "Sony", "UGREEN",
            "Anker", "Baseus", "Huawei", "Honor", "Motorola", "OPPO", "OnePlus", "Dyson",
            "Roborock", "JBL", "Logitech", "Nintendo", "Microsoft", "Lenovo", "HP", "ASUS",
            "Acer", "Dell", "Philips", "Rowenta", "Cecotec", "Bosch", "LG", "TCL"
        )
        return brands.firstOrNull { title.contains(it, ignoreCase = true) } ?: "Sin marca"
    }

    private fun inferProductCategory(title: String, query: String): ProductCategory {
        val text = "${title.lowercase(Locale.ROOT)} ${query.lowercase(Locale.ROOT)}"
        return when {
            Regex("""\b(iphone|redmi|poco|smartphone|movil|telefono|galaxy s|pixel)\b""").containsMatchIn(text) ->
                ProductCategory.SMARTPHONES
            Regex("""\b(laptop|portatil|macbook|chromebook|pc|ram|ssd|procesador|gpu|usb|hub|ethernet|router|cable|cargador)\b""").containsMatchIn(text) ->
                ProductCategory.COMPUTING
            Regex("""\b(playstation|ps5|xbox|nintendo|switch|consola|mando)\b""").containsMatchIn(text) ->
                ProductCategory.GAMING
            Regex("""\b(oled|qled|televisor|television|tv|auriculares|headphones|altavoz|speaker)\b""").containsMatchIn(text) ->
                ProductCategory.TV_AUDIO
            Regex("""\b(aspiradora|vacuum|robot aspirador|cafetera|frigorifico|microondas|lavadora|mop kit)\b""").containsMatchIn(text) ->
                ProductCategory.HOME_APPLIANCES
            Regex("""\b(afeitadora|depiladora|secador|cepillo electrico)\b""").containsMatchIn(text) ->
                ProductCategory.PERSONAL_CARE
            Regex("""\b(bicicleta|bicicletas|casco|running|deporte)\b""").containsMatchIn(text) ->
                ProductCategory.SPORTS
            else -> ProductCategory.ALL
        }
    }

    private fun isEligibleHistoryTitle(title: String): Boolean {
        if (title.isBlank()) return false
        val normalized = normalizedProductTitle(title)
        val excludedListing = Regex(
            """\b(segunda\s+mano|usad[oa]s?|used|reacondicionad[oa]s?|refurbished|renewed|pre owned|seminuev[oa]s?|open\s+box|caja\s+abierta|alquiler|rental|renting|rent|leasing|lease|hire|financiacion|financiar|cuota|mensual|suscripcion|subscription|pago\s+a\s+plazos|monthly|por\s+mes|per\s+month|mes\s+a\s+mes)\b"""
        )
        return !excludedListing.containsMatchIn(normalized) &&
            !Regex("""(?i)/\s*mes\b""").containsMatchIn(title)
    }

    private fun isPromotionalBulkListing(title: String, searchQuery: String): Boolean {
        val titleNormalized = normalizedProductTitle(title)
        val queryNormalized = normalizedProductTitle(searchQuery)
        val explicitlyRequested = Regex(
            """\b(personalizado|personalizada|logotipo|logo|publicitario|promocional|por mayor|lote)\b"""
        ).containsMatchIn(queryNormalized)
        if (explicitlyRequested) return false
        val logoOrPromo = Regex(
            """\b(impreso con (su|el|tu) logotipo|con (su|tu) logo|regalo promocional|articulo publicitario|merchandising personalizado|personalizado con logo|para publicidad)\b"""
        )
        val bulk = Regex(
            """\b(\d+\s*x\s*(?:bateria|baterias|powerbank|power bank|cargador|auriculares)|pack\s+promocional|lote\s+promocional|por\s+mayor)\b"""
        )
        return logoOrPromo.containsMatchIn(titleNormalized) || bulk.containsMatchIn(titleNormalized)
    }

    private fun isEligibleSeller(storeName: String, sourceUrl: String): Boolean {
        if (storeName.isBlank() || !isEligibleHistoryTitle(storeName)) return false
        val normalized = " " + normalizedProductTitle(storeName) + " "
        val excludedSellers = listOf("rentik", "wallapop", "cash converters", "vinted", "cex")
        return excludedSellers.none { seller -> (" " + seller + " ") in normalized } &&
            isMerchantDestinationConsistent(storeName, sourceUrl)
    }

    /**
     * If the URL belongs to a known listed retailer, reject a contradictory provider label.
     * Google Shopping redirect links are allowed as indirect destinations. Unknown hosts are
     * retained because valid sellers may not yet be listed in the local store directory.
     */
    private fun isMerchantDestinationConsistent(storeName: String, sourceUrl: String): Boolean {
        val host = parseHttpUri(sourceUrl)?.host?.lowercase(Locale.ROOT)?.removePrefix("www.").orEmpty()
        if (host.isBlank()) return false
        if (host.startsWith("google.") || host.endsWith(".google.com") || host.endsWith(".google.es")) {
            return true
        }

        val knownStore = Store.ALL_STORES.firstOrNull { store ->
            val domain = store.domain.substringBefore('/').removePrefix("www.").lowercase(Locale.ROOT)
            domain.isNotBlank() && (host == domain || host.endsWith(".$domain"))
        } ?: return true

        val stop = setOf("espana", "españa", "online", "tienda", "store", "shop", "seller", "es", "de", "el", "la")
        val expectedTokens = normalizedProductTitle(knownStore.name).split(' ')
            .filter { it.length > 2 && it !in stop }
        val actualTokens = normalizedProductTitle(storeName).split(' ')
            .filter { it.length > 2 && it !in stop }
        val expectedCompact = normalizedProductTitle(knownStore.name).replace(" ", "")
        val actualCompact = normalizedProductTitle(storeName).replace(" ", "")
        return expectedTokens.any { it in actualTokens } ||
            (actualCompact.length >= 4 && expectedCompact.contains(actualCompact)) ||
            (expectedCompact.length >= 4 && actualCompact.contains(expectedCompact))
    }

    private fun isAccessoryListing(title: String): Boolean =
        Regex(
            """(?i)\b(p[oó]ster(?:es|s)?|poster(?:es|s)?|fundas?|carcasas?|cristal\s+templado|mop\s+kit|recambios?|filtros?|protector(?:es)?|cargador(?:es)?|chargers?|charging|cables?|adaptador(?:es)?|hubs?|soportes?|repuestos?|replacement|screen\s+protector|tempered\s+glass|covers?|cases?)\b"""
        ).containsMatchIn(normalizedProductTitle(title))

    private fun explicitModelMarkers(text: String): Set<String> {
        val normalized = normalizedProductTitle(text)
        val tokens = normalized.split(' ').filter { it.isNotBlank() }
        val markers = linkedSetOf<String>()
        val modelFamilies = setOf("redmi", "poco", "iphone", "pixel", "galaxy", "wh", "dyson", "roborock")
        for (index in tokens.indices) {
            val token = tokens[index]
            if (token in modelFamilies && index + 1 < tokens.size) {
                val next = tokens[index + 1]
                if (Regex("""^[a-z]{0,3}\d+[a-z0-9]*$""").matches(next) &&
                    !Regex("""^\d+(gb|tb|mah|mp|hz|w|mm|cm|g)$""").matches(next)
                ) markers += token + next
            }
            if (Regex("""^[a-z]{1,8}\d[a-z0-9]{1,12}$""").matches(token) &&
                !Regex("""^(usb|wifi|typec|[0-9]+gb|[0-9]+tb)""").containsMatchIn(token)
            ) {
                markers += token
            }
        }
        // Sony model codes are often formatted as "WH-1000XM5", "WH1000XM5" or
        // "1000XM5". Add a canonical marker so harmless retailer formatting differences match.
        val sonyModelCode = Regex("""^(?:wh)?\d{3,5}xm\d[a-z0-9]*$""")
        tokens.filter(sonyModelCode::matches).forEach { token ->
            val canonicalCode = token.removePrefix("wh")
            markers += canonicalCode
            markers += "wh$canonicalCode"
        }
        if ("wh" in tokens) {
            tokens.getOrNull(tokens.indexOf("wh") + 1)?.let { next ->
                if (Regex("""^\d+[a-z0-9]+$""").matches(next)) {
                    markers += "wh" + next
                    markers += next
                }
            }
        }
        return markers
    }

    private fun hasStrictModelMismatch(
        query: String,
        queryMarkers: Set<String>,
        resultTitle: String,
        allowAccessoryMemoryMismatch: Boolean = false
    ): Boolean {
        if (queryMarkers.isEmpty()) return false
        val resultMarkers = explicitModelMarkers(resultTitle)
        if (resultMarkers.isEmpty() || queryMarkers.intersect(resultMarkers).isEmpty()) return true

        val variantWords = setOf("pro", "max", "plus", "ultra", "fe", "lite", "mini", "fold", "flip", "classic", "edge")
        val requestedVariants = normalizedProductTitle(query).split(' ').filter { it in variantWords }.toSet()
        val resultVariants = normalizedProductTitle(resultTitle).split(' ').filter { it in variantWords }.toSet()
        if (!resultVariants.containsAll(requestedVariants)) return true

        // Do not accidentally accept an Ultra/Plus/FE result for a different requested variant,
        // even when both titles contain the same base model number.
        val exclusiveVariantFamilies = listOf(
            setOf("pro", "max", "plus", "ultra", "fe", "lite", "mini"),
            setOf("fold", "flip"),
            setOf("classic", "edge")
        )
        for (family in exclusiveVariantFamilies) {
            val requestedFamily = requestedVariants.intersect(family)
            if (requestedFamily.isNotEmpty() && resultVariants.intersect(family) != requestedFamily) return true
        }

        val memoryPattern = Regex("""\b\d+\s?(?:gb|tb)\b""")
        val requestedMemory = memoryPattern.findAll(normalizedProductTitle(query))
            .map { it.value.replace(" ", "") }.toSet()
        val availableMemory = memoryPattern.findAll(normalizedProductTitle(resultTitle))
            .map { it.value.replace(" ", "") }.toSet()
        return !allowAccessoryMemoryMismatch &&
            requestedMemory.isNotEmpty() && !availableMemory.containsAll(requestedMemory)
    }

    /**
     * Hall/HE keyboard searches describe a switch technology, not the literal word "Hall".
     * Keep unrelated titles such as "Hall of Fame" out of visible results and price history.
     */
    private fun isHallEffectKeyboardSearch(query: String): Boolean {
        val normalized = normalizedProductTitle(query)
        val namesKeyboard = Regex("""\b(teclado|teclados|keyboard|keyboards)\b""")
            .containsMatchIn(normalized)
        val namesTechnology = Regex("""\b(hall|hal|efecto|effect|he|tmr|magnetic|magnetico|magnetica)\b""")
            .containsMatchIn(normalized)
        return namesKeyboard && namesTechnology
    }

    private fun isHallEffectKeyboardResult(title: String): Boolean {
        val normalized = normalizedProductTitle(title)
        val namesKeyboard = Regex("""\b(teclado|teclados|keyboard|keyboards)\b""")
            .containsMatchIn(normalized)
        if (!namesKeyboard) return false
        return Regex(
            """\b(hall\s*effect|effect\s*hall|efecto\s*hall|hall\s*sensor|sensor\s*hall|magnetic[oa]s?|tmr|rapid\s+trigger)\b"""
        ).containsMatchIn(normalized) ||
            Regex("""\bhe\b""").containsMatchIn(normalized)
    }

    private fun isAccessorySearchQuery(query: String): Boolean =
        Regex(
            """\b(poster(?:es|s)?|funda|fundas|carcasa|carcasas|cristal templado|protector|protectores|protector de pantalla|screen protector|tempered glass|cable|cables|cargador|cargadores|charger|chargers|charging|adaptador|adaptadores|hub|hubs|mop kit|recambio|recambios|filtro|filtros|repuesto|repuestos|replacement|case|cases|cover|covers)\b"""
        ).containsMatchIn(normalizedProductTitle(query))

    private fun isDeviceSearchQuery(query: String): Boolean {
        val normalized = normalizedProductTitle(query)
        return explicitModelMarkers(query).isNotEmpty() || Regex(
            """\b(movil|telefono|smartphone|iphone|redmi|poco|pixel|galaxy\s+(s|a|z|m)\s?\d{1,2}|aspiradora|vacuum|dyson|roomba|robot\s+aspirador|consola|playstation|ps5|xbox|nintendo\s+switch|oled|qled|televisor|television|smart\s+tv|laptop|portatil|macbook|chromebook|tablet|ipad|auriculares|headphones|headset)\b"""
        ).containsMatchIn(normalized)
    }

    private fun isSafeCatalogTitleMatch(resultTitle: String, catalogTitle: String): Boolean {
        val resultCodes = modelCodes(resultTitle)
        val catalogCodes = modelCodes(catalogTitle)
        // If both titles identify a model, require the exact model code before attaching history.
        if (resultCodes.isNotEmpty() && catalogCodes.isNotEmpty()) {
            return resultCodes.any { it in catalogCodes }
        }
        val resultTokens = normalizedProductTitle(resultTitle)
            .split(' ').filter { it.length > 2 && it !in TITLE_STOP_WORDS }.toSet()
        val catalogTokens = normalizedProductTitle(catalogTitle)
            .split(' ').filter { it.length > 2 && it !in TITLE_STOP_WORDS }.toSet()
        if (resultTokens.isEmpty() || catalogTokens.isEmpty()) return false
        val overlap = resultTokens.intersect(catalogTokens).size
        val smallerSize = minOf(resultTokens.size, catalogTokens.size)
        return overlap >= 2 && overlap.toDouble() / smallerSize >= 0.6
    }

    private fun modelCode(title: String): String? = modelCodes(title).firstOrNull()

    private fun modelCodes(title: String): Set<String> =
        Regex("""(?i)\b[a-z0-9][a-z0-9-]{2,15}\b""")
            .findAll(title)
            .map { it.value.lowercase(Locale.ROOT).replace("-", "") }
            .filter { value ->
                value.any(Char::isLetter) &&
                    value.any(Char::isDigit) &&
                    !Regex("""(?i)^(?:\d+(?:gb|tb|mb|mah|w|hz|mp|mm|cm|v|g|kg|fps|mbps)|wifi\d*|usb\d*|[458]k|[45]g)$""")
                        .matches(value)
            }
            .toSet()

    private fun normalizedProductTitle(title: String): String =
        java.text.Normalizer.normalize(title.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun canonicalStoreId(storeName: String, sourceUrl: String): String {
        val host = parseHttpUri(sourceUrl)?.host?.let(::normalizeHost).orEmpty()
        val googleHost = host.startsWith("google.") || host.endsWith(".google.com") || host.endsWith(".google.es")
        if (host.isNotBlank() && !googleHost) {
            Store.ALL_STORES.firstOrNull { store ->
                val knownHost = normalizeHost(store.domain.substringBefore('/'))
                knownHost.isNotBlank() && (host == knownHost || host.endsWith(".$knownHost"))
            }?.let { return it.id }
            // For retailers not in the bundled store list, a stable URL host is a better
            // deduplication key than the provider's inconsistent display name.
            return stableOnlineId("online_store_", host)
        }

        val normalizedName = normalizedProductTitle(storeName)
        Store.ALL_STORES.firstOrNull { store ->
            val candidate = normalizedProductTitle(store.name)
            candidate.isNotBlank() &&
                (candidate == normalizedName || normalizedName.contains(candidate) || candidate.contains(normalizedName))
        }?.let { return it.id }

        return when {
            "amazon" in normalizedName -> "amazon_es"
            "pccomponentes" in normalizedName -> "pccomponentes"
            "mediamarkt" in normalizedName -> "mediamarkt"
            "carrefour" in normalizedName -> "carrefour"
            "fnac" in normalizedName -> "fnac"
            "worten" in normalizedName -> "worten"
            "el corte ingles" in normalizedName -> "elcorteingles"
            else -> stableOnlineId("online_store_", normalizedName)
        }
    }

    private fun stableOnlineId(prefix: String, value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        val hash = digest.take(10).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return prefix + hash
    }

    private fun localDayBounds(timestamp: Long): Pair<Long, Long> {
        val start = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dayStart = start.timeInMillis
        val dayEnd = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        return dayStart to dayEnd
    }

    private companion object {
        // Live provider rows before this local Spain date remain excluded; verified archive rows bypass this boundary only through evidenceType=archive.
        val HISTORY_START_TIMESTAMP: Long = Calendar.getInstance(TimeZone.getTimeZone("Europe/Madrid")).apply {
            clear()
            set(2026, Calendar.OCTOBER, 13, 0, 0, 0)
        }.timeInMillis
        const val MIN_HISTORY_PRICE_EUR = 0.50
        const val MAX_HISTORY_PRICE_EUR = 10000.0
        const val MIN_OUTLIER_PRICE_RATIO = 0.20
        const val ACCESSORY_PRICE_RATIO = 0.25
        val TITLE_STOP_WORDS = setOf(
            "the", "and", "for", "with", "de", "del", "la", "las", "el", "los", "un", "una",
            "nuevo", "new", "of", "en", "para", "by", "from", "original", "oficial"
        )
    }

    suspend fun toggleTracking(productId: String, targetAlertPrice: Double? = null) {
        val existing = trackedDao.getById(productId)
        if (existing != null) {
            trackedDao.delete(productId)
        } else {
            trackedDao.insertOrUpdate(
                TrackedProductEntity(
                    productId = productId,
                    targetAlertPrice = targetAlertPrice
                )
            )
        }
    }

    suspend fun updateAlertTarget(productId: String, targetPrice: Double?) {
        val existing = trackedDao.getById(productId)
        if (existing != null) {
            trackedDao.updateTargetPrice(productId, targetPrice)
        } else {
            trackedDao.insertOrUpdate(
                TrackedProductEntity(
                    productId = productId,
                    targetAlertPrice = targetPrice
                )
            )
        }
    }

    fun analyzeSpanishUrlOrQuery(input: String): UrlAnalysisResult {
        val rawInput = input.trim()
        if (rawInput.isBlank()) {
            return UrlAnalysisResult(
                queryOrUrl = input,
                identifiedStore = null,
                detectedTitle = "",
                matchedProduct = null,
                isKnownRetailerDomain = false,
                adviceMessage = "Escribe el nombre de un producto o pega el enlace de una ficha de producto."
            )
        }

        val submittedUri = parseHttpUri(rawInput)
        val detectedStore = findStoreForUrl(submittedUri)
        val cleaned = rawInput.lowercase()
        val catalog = _latestProducts.value

        val directMatch = if (submittedUri != null) {
            catalog.firstOrNull { product ->
                val productUri = parseHttpUri(product.storeUrl)
                productUri != null &&
                    normalizeHost(productUri.host) == normalizeHost(submittedUri.host) &&
                    normalizePath(productUri) == normalizePath(submittedUri)
            }
        } else {
            catalog.firstOrNull { product ->
                product.title.lowercase().contains(cleaned) ||
                    product.brand.equals(rawInput, ignoreCase = true) ||
                    product.ean == rawInput ||
                    product.id.equals(rawInput, ignoreCase = true)
            }
        }

        if (directMatch != null) {
            return UrlAnalysisResult(
                queryOrUrl = input,
                identifiedStore = detectedStore,
                detectedTitle = directMatch.title,
                matchedProduct = directMatch,
                isKnownRetailerDomain = detectedStore != null,
                adviceMessage = "Coincidencia con un producto guardado en tu Mercado y sus observaciones reales de precio."
            )
        }

        val displayTitle = submittedUri?.let { uri ->
            val slug = uri.path.orEmpty()
                .substringAfterLast('/')
                .replace(Regex("[-_]+"), " ")
                .trim()
            if (slug.isBlank()) "Página de ${uri.host}" else slug
        } ?: rawInput

        val advice = when {
            submittedUri != null && detectedStore != null ->
                "Dominio reconocido: ${detectedStore.name}. Esta versión todavía no extrae el precio de la página ni consulta su historial. No se ha inventado ningún precio."
            submittedUri != null ->
                "No se reconoce este dominio como una de las tiendas configuradas y la app todavía no puede extraer el precio de la página. No se ha inventado ningún precio ni historial."
            else ->
                "No hay coincidencia con los productos que has guardado. Busca el modelo desde la lupa para consultar precios actuales y añadirlo a Mercado."
        }

        // Never manufacture a product with placeholder prices, a fake EAN or synthetic history.
        return UrlAnalysisResult(
            queryOrUrl = input,
            identifiedStore = detectedStore,
            detectedTitle = displayTitle,
            matchedProduct = null,
            isKnownRetailerDomain = detectedStore != null,
            adviceMessage = advice
        )
    }

    private fun Product.toEntity(): ProductEntity = ProductEntity(
        id = id,
        title = title,
        brand = brand,
        category = category.name,
        currentPrice = currentPrice,
        athMinPrice = athMinPrice,
        athMinDate = athMinDate,
        athMinStore = athMinStore,
        athMaxPrice = athMaxPrice,
        avg90dPrice = avg90dPrice,
        officialPvp = officialPvp,
        currentStoreId = currentStore.id,
        storeUrl = storeUrl,
        ean = ean,
        imageResName = imageResName,
        imageUrl = imageUrl.takeIf { it.isNotBlank() },
        rating = rating,
        reviewCount = reviewCount,
        wasRecentlyInflated = wasRecentlyInflated,
        fakePriorPrice = fakePriorPrice,
        isPriceVerified = isPriceVerified
    )

    private fun ProductEntity.toDomainProduct(
        stores: List<Store>,
        verifiedObservations: List<PriceObservationEntity>
    ): Product {
        // Live rows retain the policy boundary so legacy/demo prices cannot enter the chart.
        // Independently sourced historical archive points may predate that boundary, but only
        // when the row explicitly carries archive provenance from the v8 schema.
        val sortedHistory = verifiedObservations.filter { observation ->
            val eligibleDate = observation.evidenceType in setOf("archive", "google_insights") ||
                observation.observedAt >= HISTORY_START_TIMESTAMP
            eligibleDate && isEligibleObservationForHistory(observation)
        }.sortedBy { it.observedAt }
        // Only current search observations establish the live price. Historical-provider points
        // remain visible on the chart but can never replace the current quote.
        val liveHistory = sortedHistory.filter { it.evidenceType == "live" }
        // Current price/store must always come from a live provider observation, never a Wayback
        // snapshot, even when an archived snapshot was captured later in the same calendar day.
        val latestLiveDayStart = liveHistory.lastOrNull()?.let { localDayBounds(it.observedAt).first }
        val latestBatch = liveHistory.filter {
            latestLiveDayStart != null && localDayBounds(it.observedAt).first == latestLiveDayStart
        }
        // One point per local day. Prefer a live observation for a day when available; otherwise
        // use its lowest verified archive quote. Gaps remain gaps, with no interpolated values.
        val snapshotHistory = sortedHistory
            .groupBy { localDayBounds(it.observedAt).first }
            .values
            .mapNotNull { dayOffers ->
                val liveOffers = dayOffers.filter { it.evidenceType == "live" }
                (liveOffers.ifEmpty { dayOffers }).minByOrNull { it.price }
            }
            .sortedBy { localDayBounds(it.observedAt).first }
        val latestObservation = latestBatch.minByOrNull { it.price }
        val minObservation = snapshotHistory.minByOrNull { it.price }
        val maxObservation = snapshotHistory.maxByOrNull { it.price }
        // Archive-backed points are independently source-verified and can be displayed before a
        // live quote exists. They never make the current price verified or establish deal badges.
        val priceIsVerified = isPriceVerified && latestObservation != null
        val effectiveHistory = snapshotHistory
        val effectiveStore = if (priceIsVerified) {
            observationStore(latestObservation!!, stores)
        } else {
            stores.firstOrNull { it.id == currentStoreId } ?: Store.findByName(currentStoreId)
        }
        val now = System.currentTimeMillis()
        val ninetyDaysMs = 90L * 24L * 60L * 60L * 1000L
        val ninetyDaysAgo = now - ninetyDaysMs
        val lastNinetyDays = effectiveHistory.filter { it.observedAt >= ninetyDaysAgo }
        val uniqueTimestamps = snapshotHistory.map { localDayBounds(it.observedAt).first }.distinct().sorted()
        val hasSufficientHistory = priceIsVerified &&
            uniqueTimestamps.size >= 3 &&
            uniqueTimestamps.last() - uniqueTimestamps.first() >= ninetyDaysMs &&
            lastNinetyDays.map { it.observedAt }.distinct().size >= 3 &&
            now - (liveHistory.maxOfOrNull { it.observedAt } ?: 0L) <= 7L * 24L * 60L * 60L * 1000L
        val current = if (priceIsVerified) latestObservation!!.price else currentPrice
        // These bounds describe the actual saved daily series; the 90-day average remains
        // unavailable until enough dates have accumulated.
        val lowest = minObservation?.price ?: current
        val highest = maxObservation?.price ?: current
        val average = if (hasSufficientHistory) lastNinetyDays.map { it.price }.average() else 0.0
        val latestOffers = if (priceIsVerified) {
            latestBatch.distinctBy { "${it.storeId}|${it.sourceUrl}" }.map { observation ->
                StoreOffer(
                    store = observationStore(observation, stores),
                    price = observation.price,
                    inStock = false,
                    shippingInfo = "Consultar envío en la tienda",
                    url = observation.sourceUrl,
                    linkType = observation.linkType
                )
            }.sortedBy { it.price }
        } else {
            emptyList()
        }
        val dateFormatter = SimpleDateFormat("dd MMM yyyy", Locale("es", "ES"))
        // Plot just one genuine point per local date: the cheapest valid offer observed that day.
        val historyPoints = if (priceIsVerified) snapshotHistory.map { observation ->
            PricePoint(
                timestamp = observation.observedAt,
                dateLabel = dateFormatter.format(Date(observation.observedAt)),
                price = observation.price,
                storeName = observation.storeName.ifBlank { observationStore(observation, stores).name },
                eventLabel = observation.sourceType.ifBlank { observation.sourceName },
                linkType = observation.linkType
            )
        } else {
            emptyList()
        }
        return Product(
            id = id,
            title = title,
            brand = brand,
            category = ProductCategory.values().firstOrNull { it.name == category } ?: ProductCategory.ALL,
            currentPrice = current,
            athMinPrice = lowest,
            athMinDate = if (hasSufficientHistory) dateFormatter.format(Date(minObservation!!.observedAt)) else "Historial en construcción",
            athMinStore = if (hasSufficientHistory) observationStore(minObservation!!, stores).name else "",
            athMaxPrice = highest,
            avg90dPrice = average,
            officialPvp = if (priceIsVerified) 0.0 else officialPvp,
            currentStore = effectiveStore,
            storeUrl = if (priceIsVerified) latestObservation!!.sourceUrl else storeUrl,
            // Price lookup doesn't prove the bundled EAN, rating or review count.
            ean = if (priceIsVerified) "" else ean,
            priceHistory = historyPoints,
            // The latest provider snapshot retains individual sellers; availability and shipping
            // are not inferred from a search result and must be checked at the merchant.
            offers = latestOffers,
            wasRecentlyInflated = if (priceIsVerified) false else wasRecentlyInflated,
            fakePriorPrice = if (priceIsVerified) null else fakePriorPrice,
            rating = if (priceIsVerified) 0.0 else rating,
            reviewCount = if (priceIsVerified) 0 else reviewCount,
            imageResName = imageResName,
            imageUrl = imageUrl.orEmpty(),
            isPriceVerified = priceIsVerified,
            priceObservedAt = latestObservation?.observedAt.takeIf { priceIsVerified },
            googleProductId = googleProductId,
            priceSource = latestObservation
                ?.let { it.sourceType.ifBlank { it.sourceName } }
                .orEmpty()
                .takeIf { priceIsVerified }
                .orEmpty()
        )
    }

    private fun configuredStoreForObservation(
        observation: PriceObservationEntity,
        stores: List<Store>,
        merchantHost: String?
    ): Store? {
        stores.firstOrNull { it.id == observation.storeId }?.let { return it }
        merchantHost?.let { host ->
            stores.firstOrNull { store ->
                val storeHost = normalizeHost(store.domain.substringBefore('/'))
                storeHost.isNotBlank() && (host == storeHost || host.endsWith(".$storeHost"))
            }?.let { return it }
        }
        val normalizedName = normalizeStoreIdentity(observation.storeName)
        if (normalizedName.isBlank()) return null
        return stores.firstOrNull { store ->
            val candidate = normalizeStoreIdentity(store.name)
            candidate.isNotBlank() && (
                candidate == normalizedName ||
                    (normalizedName.length >= 5 && candidate.contains(normalizedName)) ||
                    (candidate.length >= 5 && normalizedName.contains(candidate))
                )
        }
    }

    private fun merchantHostForObservation(observation: PriceObservationEntity): String? {
        return sequenceOf(observation.sourcePageUrl, observation.sourceUrl)
            .filter { it.isNotBlank() }
            .mapNotNull(::parseHttpUri)
            .map { normalizeHost(it.host.orEmpty()) }
            .firstOrNull { host ->
                host.isNotBlank() &&
                    host != "google.com" &&
                    !host.endsWith(".google.com") &&
                    !host.endsWith(".google.es") &&
                    host != "web.archive.org" &&
                    !host.endsWith(".web.archive.org")
            }
    }

    private fun normalizeStoreIdentity(value: String): String =
        java.text.Normalizer.normalize(value.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun observationStore(observation: PriceObservationEntity, stores: List<Store>): Store {
        stores.firstOrNull { it.id == observation.storeId }?.let { return it }
        val host = parseHttpUri(observation.sourceUrl)?.host
            ?.lowercase(Locale.ROOT)
            ?.removePrefix("www.")
            .orEmpty()
        if (host.isNotBlank()) {
            stores.firstOrNull { store ->
                val storeHost = store.domain.substringBefore('/').removePrefix("www.").lowercase(Locale.ROOT)
                storeHost.isNotBlank() && (host == storeHost || host.endsWith(".$storeHost"))
            }?.let { return it }
        }
        val merchantName = observation.storeName
            .takeIf { it.isNotBlank() }
            ?: observation.sourceName.substringBefore(" · SerpApi")
                .takeIf { it.isNotBlank() }
            ?: host.ifBlank { "Comercio consultado" }
        return Store(
            id = observation.storeId,
            name = merchantName,
            domain = host.ifBlank { "shopping.google.com" },
            brandColor = Color(0xFF4285F4),
            category = "Oferta online",
            physicalStores = false,
            hasLivePriceIntegration = false
        )
    }

    private fun parseHttpUri(value: String): URI? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        return uri.takeIf {
            (scheme == "http" || scheme == "https") && !it.host.isNullOrBlank()
        }
    }

    private fun findStoreForUrl(uri: URI?): Store? {
        val host = uri?.host?.let(::normalizeHost) ?: return null
        return Store.ALL_STORES.firstOrNull { store ->
            val storeHost = normalizeHost(store.domain.substringBefore('/'))
            storeHost.isNotBlank() && (host == storeHost || host.endsWith(".$storeHost"))
        }
    }

    private fun normalizeHost(host: String): String = host.lowercase().removePrefix("www.")

    private fun normalizePath(uri: URI): String = uri.path.orEmpty().trimEnd('/').ifEmpty { "/" }

}
