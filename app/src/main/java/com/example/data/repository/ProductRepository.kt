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
import com.example.data.remote.SerpApiQuote
import com.example.data.remote.SerpApiShoppingResult
import com.example.data.remote.RejectedOnlineOffer
import com.example.data.remote.OnlineProductGroup
import com.example.data.remote.groupOnlineShoppingResults
import com.example.data.remote.onlineProductIdentityKey
import com.example.data.remote.linkTypeForUrl
import com.example.data.remote.isWheelFitmentQuery
import com.example.data.model.Store
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

    init {
        // Clean legacy demo rows and unverified history at startup. Preserve followed real products
        // even if their first valid observation has not been recorded yet.
        externalScope.launch {
            priceObservationDao.deleteExplicitDemoObservations()
            trackedDao.deleteTrackingForDemoProducts()
            productDao.deleteExplicitDemoProducts()
            priceObservationDao.deleteUnverifiedObservations()
            productDao.deleteProductsWithoutVerifiedObservations()
            productDao.markProductsWithVerifiedObservations()
            trackedDao.deleteTrackingForMissingProducts()
            storeDao.deleteAll()
        }
    }

    val allStoresStream: Flow<List<Store>> = storeDao.getAllStores().map { list ->
        if (list.isEmpty()) Store.ALL_STORES
        else list.map { it.toDomainStore() }
    }

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
                isEligibleSeller(quote.storeName) &&
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
                result.price.isFinite() &&
                result.price > 0.0 &&
                result.price <= MAX_HISTORY_PRICE_EUR &&
                result.observedAt >= HISTORY_START_TIMESTAMP &&
                !result.isRentalOrMonthly &&
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
        val wheelTitlePattern = Regex("""(?i)\b(llantas?|ruedas?|wheels?|rims?|felgen)\b""")
        val queryHasWheelSize = Regex("""(?i)\b(1[0-9]|2[0-4])\b""").containsMatchIn(searchQuery)
        val wheelTitleCount = eligible.count { wheelTitlePattern.containsMatchIn(it.title) }
        val isWheelSearch = isWheelFitmentQuery(searchQuery) ||
            Regex("""(?i)\b(llantas?|ruedas?|wheels?|rims?)\b""").containsMatchIn(searchQuery) ||
            (queryHasWheelSize && wheelTitleCount >= 2)

        fun wheelBundleCount(title: String): String {
            if (!wheelTitlePattern.containsMatchIn(title)) return "unspecified"
            val count = Regex(
                """(?i)\b(?:kit|juego|set)\s*(?:de\s*)?(\d+)\s*(?:[x×]\s*)?(?:llantas?|ruedas?|wheels?|rims?|felgen)\b"""
            ).find(title)?.groupValues?.getOrNull(1)
                ?: Regex(
                    """(?i)\b(\d+)\s*(?:[x×]\s*)?(?:[a-z0-9]+\s+){0,2}(?:llantas?|ruedas?|wheels?|rims?|felgen)\b"""
                ).find(title)?.groupValues?.getOrNull(1)
            return count?.toIntOrNull()?.takeIf { it in 1..20 }?.toString() ?: "unspecified"
        }

        fun comparisonKey(result: SerpApiShoppingResult): String {
            val identity = onlineProductIdentityKey(result.title)
            return if (isWheelSearch) identity + "|wheel-set:" + wheelBundleCount(result.title) else identity
        }

        val suspiciousByGroup = eligible.groupBy(::comparisonKey).mapValues { (_, comparableOffers) ->
            val trustedPrices = comparableOffers.filterNot {
                it.isSecondHand || it.isAccessoryOffer || it.isFitmentUnverified
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
                isSuspiciousPrice = !result.isFitmentUnverified &&
                    hasComparableReference && median > 0.0 && result.price < median * 0.30
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
                    (isDeviceSearchQuery(searchQuery) && !isAccessorySearchQuery(searchQuery) &&
                        isAccessoryListing(result.title))
            )
            if (visibleButNotHistory) return@mapNotNull null
            val reason = when {
                result.title.isBlank() || result.storeName.isBlank() ->
                    "Datos incompletos"
                !result.price.isFinite() || result.price <= 0.0 || result.price > MAX_HISTORY_PRICE_EUR ->
                    "Sin precio válido"
                result.isRentalOrMonthly ->
                    "Alquiler o cuota mensual"
                result.isSecondHand ->
                    "Segunda mano o reacondicionado"
                hasStrictModelMismatch(searchQuery, queryModelMarkers, result.title) ->
                    "Otro modelo o variante"
                !isEligibleSeller(result.storeName) ->
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

    private fun isPlausibleHistoryPrice(title: String, price: Double): Boolean =
        price.isFinite() && price > 0.0 && price <= MAX_HISTORY_PRICE_EUR

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
            .filterNot { it.isSecondHand || it.isFitmentUnverified }
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
            val displayTitle = existingProduct?.title?.takeIf { it.isNotBlank() } ?: group.title
            val hasTrustedOffer = group.offers.any { !it.isSuspiciousPrice }
            val currentMinimum = if (hasTrustedOffer) group.minPrice else existingProduct?.currentPrice ?: 0.0
            val highestToday = group.offers.maxOfOrNull { it.price } ?: currentMinimum
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
                    imageUrl = bestOffer.thumbnailUrl.takeIf { it.isNotBlank() } ?: existingProduct.imageUrl,
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
                    imageUrl = bestOffer.thumbnailUrl.takeIf { it.isNotBlank() },
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
                    price = result.price,
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
     * Save a single Google Product API response against the already-identified product row.
     * No new catalogue product is created and no observation is saved if the product was not
     * safely identified by the API parser.
     */
    suspend fun recordProductComparisonOffers(
        productId: String,
        expectedTitle: String,
        offers: List<SerpApiShoppingResult>
    ): Int {
        val product = productDao.getProductById(productId) ?: return 0
        if (expectedTitle.isBlank() || onlineProductIdentityKey(product.title) != onlineProductIdentityKey(expectedTitle)) {
            return 0
        }
        val valid = filterOnlineSearchResults(offers, expectedTitle)
            .filterNot { it.isSecondHand || it.isRentalOrMonthly }
            .filter { isEligibleSeller(it.storeName) && isEligibleHistoryTitle(it.title) }
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
                price = offer.price,
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
        val existing = priceObservationDao.getObservationForStoreProductDay(
            observation.productId, observation.storeId, dayStart, dayEnd
        )
        if (existing == null) {
            priceObservationDao.insert(observation)
            return true
        }
        // Never let a suspicious, unverified observation replace a verified same-day quote.
        if (!observation.isVerified && existing.isVerified) return false
        // A newly validated offer supersedes a previously unverified suspicious row.
        if (observation.isVerified && !existing.isVerified) {
            priceObservationDao.update(observation.copy(id = existing.id))
            return true
        }

        val isNewer = observation.observedAt > existing.observedAt
        val sameTimeAndBetterPrice =
            observation.observedAt == existing.observedAt && observation.price < existing.price
        if (!isNewer && !sameTimeAndBetterPrice) return false

        priceObservationDao.update(observation.copy(id = existing.id))
        return true
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
            """\b(segunda\s+mano|usad[oa]s?|used|reacondicionad[oa]s?|refurbished|renewed|pre owned|seminuev[oa]s?|alquiler|rental|renting|rent|leasing|lease|hire|financiacion|financiar|cuota|mensual|suscripcion|subscription|pago\s+a\s+plazos|monthly|por\s+mes|per\s+month|mes\s+a\s+mes)\b"""
        )
        return !excludedListing.containsMatchIn(normalized) &&
            !Regex("""(?i)/\s*mes\b""").containsMatchIn(title)
    }

    private fun isEligibleSeller(storeName: String): Boolean {
        if (storeName.isBlank() || !isEligibleHistoryTitle(storeName)) return false
        val normalized = " " + normalizedProductTitle(storeName) + " "
        val excludedSellers = listOf("rentik", "wallapop", "cash converters", "vinted", "cex")
        return excludedSellers.none { seller -> (" " + seller + " ") in normalized }
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
        // Fixed policy boundary: no stored row before this local Spain date contributes to history.
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
        // Ignore legacy/imported timestamps before the policy start. Observations from either a
        // direct retailer URL or a Google Shopping page can support visible history.
        val sortedHistory = verifiedObservations.filter { observation ->
            observation.observedAt >= HISTORY_START_TIMESTAMP &&
                observation.currency == "EUR" &&
                observation.price.isFinite() &&
                observation.price > 0.0 &&
                observation.price <= MAX_HISTORY_PRICE_EUR
        }.sortedBy { it.observedAt }
        // Room keeps each store observation, but the chart must be a daily time series, not a
        // sequence of sellers from the same search. Plot the cheapest valid offer for each day.
        val latestDayStart = sortedHistory.lastOrNull()?.let { localDayBounds(it.observedAt).first }
        val latestBatch = sortedHistory.filter {
            latestDayStart != null && localDayBounds(it.observedAt).first == latestDayStart
        }
        val snapshotHistory = sortedHistory
            .groupBy { localDayBounds(it.observedAt).first }
            .values
            .mapNotNull { dayOffers -> dayOffers.minByOrNull { it.price } }
            .sortedBy { localDayBounds(it.observedAt).first }
        val latestObservation = latestBatch.minByOrNull { it.price }
        val minObservation = snapshotHistory.minByOrNull { it.price }
        val maxObservation = snapshotHistory.maxByOrNull { it.price }
        // Require both the explicit product flag and a provider-backed observation.
        val priceIsVerified = isPriceVerified && latestObservation != null
        val effectiveHistory = if (priceIsVerified) snapshotHistory else emptyList()
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
            now - (sortedHistory.maxOfOrNull { it.observedAt } ?: 0L) <= 7L * 24L * 60L * 60L * 1000L
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
            googleProductId = googleProductId
        )
    }

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
