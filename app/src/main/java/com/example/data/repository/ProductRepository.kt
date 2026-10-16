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
import com.example.data.remote.OnlineProductGroup
import com.example.data.remote.groupOnlineShoppingResults
import com.example.data.remote.onlineProductIdentityKey
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
    // Room is the only product catalogue. Online searches are the sole source of persisted items.
    private val _latestProducts = MutableStateFlow<List<Product>>(emptyList())
    private val _simulationOverrides = MutableStateFlow<Map<String, Product>>(emptyMap())

    init {
        // The database must never seed a demonstration catalogue. On upgrade, discard old rows
        // that do not have real verified online observations and their orphaned watchlist/history.
        // The static Store.ALL_STORES directory is read-only metadata and does not need Room rows.
        externalScope.launch {
            // Purge demo-linked history and watchlist rows before removing the products themselves.
            priceObservationDao.deleteExplicitDemoObservations()
            trackedDao.deleteTrackingForDemoProducts()
            productDao.deleteExplicitDemoProducts()
            priceObservationDao.deleteUnverifiedObservations()
            sanitizeLegacyObservations()
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
    // A simulation is a temporary, clearly unverified overlay and never becomes a recorded price.
    val allProductsStream: Flow<List<Product>> = combine(
        productDao.getAllProducts(),
        storeDao.getAllStores(),
        trackedDao.getAllTracked(),
        priceObservationDao.observeAllVerified(),
        _simulationOverrides
    ) { productEntities, storeEntities, trackedEntities, observations, simulations ->
        val stores = storeEntities.map { it.toDomainStore() }.ifEmpty { Store.ALL_STORES }
        val trackingMap = trackedEntities.associateBy { it.productId }
        val observationsByProduct = observations.groupBy { it.productId }

        // Never render a Room product without at least one verified source-backed observation.
        // This also prevents legacy/mock rows from flashing on screen while startup cleanup runs.
        productEntities
            .filter { entity ->
                // Explicitly exclude legacy demo markers immediately, before async Room cleanup.
                !entity.id.startsWith("demo_", ignoreCase = true) &&
                    !entity.athMinDate.contains("demostración", ignoreCase = true) &&
                    !entity.athMinStore.contains("demostración", ignoreCase = true) &&
                    observationsByProduct[entity.id].orEmpty().isNotEmpty()
            }
            .map { entity ->
                val product = entity.toDomainProduct(
                    stores = stores,
                    verifiedObservations = observationsByProduct[entity.id].orEmpty()
                )
                val shownProduct = simulations[product.id] ?: product
                val trackingInfo = trackingMap[product.id]
                shownProduct.copy(
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
     * Remove persisted snapshots that older app versions accepted too loosely. This is a
     * one-time, conservative cleanup: it removes only observations that fail current price,
     * seller, currency, URL, or product-identity rules, never valid tracked history.
     */
    private suspend fun sanitizeLegacyObservations() {
        val productsById = productDao.getAllProductsSnapshot().associateBy { it.id }
        val invalidIds = priceObservationDao.getAllVerifiedSnapshot()
            .filter { observation ->
                val product = productsById[observation.productId]
                product == null || !isSafeStoredObservation(observation, product.title)
            }
            .map { it.id }
        if (invalidIds.isNotEmpty()) priceObservationDao.deleteByIds(invalidIds)
    }

    private fun isSafeStoredObservation(
        observation: PriceObservationEntity,
        catalogTitle: String
    ): Boolean {
        val observedTitle = observation.productTitle.ifBlank { catalogTitle }
        val seller = observation.storeName.ifBlank { observation.storeId }
        val source = observation.sourceType.ifBlank { observation.sourceName }
        val sourceIsRecognized = source.equals("Google Shopping", ignoreCase = true) ||
            source.equals("Amazon.es", ignoreCase = true) ||
            observation.sourceName.equals("Amazon.es", ignoreCase = true) ||
            observation.sourceName.equals("GOOGLE_SHOPPING", ignoreCase = true)
        val uri = parseHttpUri(observation.sourceUrl)
        val host = uri?.host?.lowercase(Locale.ROOT)?.removePrefix("www.").orEmpty()
        val pointsToSearchInsteadOfMerchant = host in setOf(
            "google.com", "google.es", "shopping.google.com"
        ) || host.contains("googleadservices.com") ||
            host.contains("googlesyndication.com") || host.contains("googleusercontent.com")
        return observation.productId.isNotBlank() &&
            observation.observedAt > 0L &&
            observation.observedAt <= System.currentTimeMillis() + MAX_FUTURE_OBSERVATION_SKEW_MS &&
            observation.price.isFinite() &&
            observation.price in MIN_HISTORY_PRICE_EUR..MAX_HISTORY_PRICE_EUR &&
            observation.currency.equals("EUR", ignoreCase = true) &&
            uri?.scheme.equals("https", ignoreCase = true) &&
            !pointsToSearchInsteadOfMerchant &&
            sourceIsRecognized &&
            observation.sourceType in setOf("Google Shopping", "Amazon.es") &&
            isEligibleHistoryTitle(observedTitle) &&
            isEligibleSeller(seller) &&
            isPlausibleHistoryPrice(observedTitle, observation.price) &&
            isSafeCatalogTitleMatch(observedTitle, catalogTitle) &&
            !hasStrictModelMismatch(catalogTitle, explicitModelMarkers(catalogTitle), observedTitle)
    }

    /**
     * Store only provider-backed, EUR offers with HTTPS destination URLs. A product is marked
     * verified only if at least one validated offer was stored for that product.
     */
    suspend fun recordSerpApiQuotes(quotes: List<SerpApiQuote>): Int {
        // Resolve titles from persisted, user-searched products only.
        val storedProducts = productDao.getAllProductsSnapshot()
        val titleById = storedProducts.associate { it.id to it.title }.toMutableMap().apply {
            _latestProducts.value.forEach { product -> put(product.id, product.title) }
        }
        val validQuotes = quotes.filter { quote ->
            val title = titleById[quote.productId].orEmpty()
            val uri = parseHttpUri(quote.sourceUrl)
            title.isNotBlank() &&
                quote.productId.isNotBlank() &&
                quote.observedAt > 0L &&
                quote.observedAt <= System.currentTimeMillis() + MAX_FUTURE_OBSERVATION_SKEW_MS &&
                quote.price.isFinite() && quote.price in MIN_HISTORY_PRICE_EUR..MAX_HISTORY_PRICE_EUR &&
                quote.currency.equals("EUR", ignoreCase = true) &&
                uri?.scheme.equals("https", ignoreCase = true) &&
                isEligibleHistoryTitle(title) &&
                isEligibleSeller(quote.storeName) &&
                isPlausibleHistoryPrice(title, quote.price) &&
                isSafeCatalogTitleMatch(title, title)
        }
        if (validQuotes.isEmpty()) return 0

        var inserted = 0
        for (quote in validQuotes) {
            val productTitle = titleById[quote.productId] ?: continue
            val storeId = canonicalStoreId(quote.storeName, quote.sourceUrl)
            val (dayStart, dayEnd) = localDayBounds(quote.observedAt)
            if (priceObservationDao.getObservationForStoreProductDay(
                    quote.productId, storeId, dayStart, dayEnd
                ) != null
            ) continue

            priceObservationDao.insert(
                PriceObservationEntity(
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
                    sourceType = "Google Shopping"
                )
            )
            productDao.updatePriceVerification(quote.productId, true)
            inserted++
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
        val deviceSearch = isDeviceSearchQuery(searchQuery)
        val queryModelMarkers = explicitModelMarkers(searchQuery)
        val eligible = results.filter { result ->
            val uri = parseHttpUri(result.sourceUrl)
            val host = uri?.host?.lowercase(Locale.ROOT)?.removePrefix("www.").orEmpty()
            val pointsToSearchInsteadOfMerchant = host in setOf(
                "google.com", "google.es", "shopping.google.com"
            ) || host.contains("googleadservices.com") ||
                host.contains("googlesyndication.com") || host.contains("googleusercontent.com")
            result.title.isNotBlank() &&
                result.storeName.isNotBlank() &&
                result.observedAt > 0L &&
                result.observedAt <= System.currentTimeMillis() + MAX_FUTURE_OBSERVATION_SKEW_MS &&
                result.price.isFinite() &&
                result.price in MIN_HISTORY_PRICE_EUR..MAX_HISTORY_PRICE_EUR &&
                // SerpApiShoppingResult is produced by the Spain-specific Shopping client and
                // does not carry an independent currency field; currency is validated in the
                // quote path where the provider actually supplies it.
                uri?.scheme.equals("https", ignoreCase = true) &&
                !pointsToSearchInsteadOfMerchant &&
                isEligibleHistoryTitle(result.title) &&
                isEligibleSeller(result.storeName) &&
                isPlausibleHistoryPrice(result.title, result.price) &&
                !hasStrictModelMismatch(searchQuery, queryModelMarkers, result.title) &&
                // A device-specific query should never show cases, filters, mop kits or other
                // accessories as if they were the requested device. Brand-only accessory searches
                // remain unrestricted, preserving UGREEN cable/hub deals.
                !(deviceSearch && !isAccessorySearchQuery(searchQuery) && isAccessoryListing(result.title))
        }

        // Retain the 25% accessory outlier guard for queries that ask about an entire device but
        // lack a fully identifiable model. Median is robust against an extreme provider quote.
        val fullProductPrices = if (deviceSearch) {
            eligible.filterNot { isAccessoryListing(it.title) }.map { it.price }.sorted()
        } else emptyList()
        val referenceProductPrice = fullProductPrices.takeIf { it.isNotEmpty() }?.let { prices ->
            if (prices.size % 2 == 1) prices[prices.size / 2]
            else (prices[prices.size / 2 - 1] + prices[prices.size / 2]) / 2.0
        }

        val coded = eligible.map { result -> result to modelCode(result.title) }
        return coded.filter { (candidate, code) ->
            if (deviceSearch && isAccessoryListing(candidate.title) &&
                referenceProductPrice != null &&
                candidate.price < referenceProductPrice * ACCESSORY_PRICE_RATIO
            ) {
                false
            } else if (code == null) {
                true
            } else {
                val comparablePrices = coded
                    .filter { (_, otherCode) -> otherCode == code }
                    .map { (other, _) -> other.price }
                    .sorted()
                if (comparablePrices.size < 3) {
                    true
                } else {
                    val median = comparablePrices[comparablePrices.size / 2]
                    candidate.price >= median * MIN_OUTLIER_PRICE_RATIO
                }
            }
        }.map { (result, _) -> result }
    }

    private fun isPlausibleHistoryPrice(title: String, price: Double): Boolean {
        if (!price.isFinite() || price !in MIN_HISTORY_PRICE_EUR..MAX_HISTORY_PRICE_EUR) return false
        val normalized = normalizedProductTitle(title)
        // Low prices are normal for accessories; do not apply device floors to them.
        val accessory = Regex(
            """\b(funda|case|carcasa|protector|cristal|cable|cargador|adaptador|repuesto|replacement|cover|bumper|pantalla|bateria|solo caja|dummy)\b"""
        ).containsMatchIn(normalized)
        if (accessory) return true

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
        val normalizedSource = when (sourceType) {
            "AMAZON" -> "Amazon.es"
            "GOOGLE_SHOPPING" -> "Google Shopping"
            "Amazon.es", "Google Shopping" -> sourceType
            else -> return 0
        }
        val eligible = filterOnlineSearchResults(results, searchQuery)
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
            val currentMinimum = group.minPrice
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
                    isPriceVerified = true
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
                    isPriceVerified = true
                )
            }
            productDao.insert(productEntity)
            if (existingProduct == null) currentEntities += productEntity

            // Keep every distinct merchant observation in Room. The product chart later condenses
            // these offers into one minimum-price point per local calendar day.
            for (result in group.offers) {
                val offerStoreId = canonicalStoreId(result.storeName, result.sourceUrl)
                val (dayStart, dayEnd) = localDayBounds(result.observedAt)
                if (priceObservationDao.getObservationForStoreProductDay(
                        productId, offerStoreId, dayStart, dayEnd
                    ) != null
                ) continue

                priceObservationDao.insert(
                    PriceObservationEntity(
                        productId = productId,
                        storeId = offerStoreId,
                        price = result.price,
                        currency = "EUR",
                        observedAt = result.observedAt,
                        sourceUrl = result.sourceUrl,
                        sourceName = normalizedSource,
                        isVerified = true,
                        verificationNote = "Resultado online válido en euros; precio y disponibilidad pendientes de confirmación en la tienda.",
                        productTitle = result.title,
                        storeName = result.storeName,
                        sourceType = normalizedSource
                    )
                )
                inserted++
            }
        }
        return inserted
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
            """\b(segunda\s+mano|usad[oa]s?|used|reacondicionad[oa]s?|refurbished|renewed|pre owned|seminuev[oa]s?|alquiler|rental|renting|rent|leasing|lease|hire|financiacion|financiar|cuota|mensual|suscripcion|subscription|pago\s+a\s+plazos|monthly|por\s+mes|per\s+month|mes\s+a\s+mes|personalizad[oa]s?|publicitari[oa]s?|merchandising|regalo\s+promocional|impreso\s+con\s+(?:su\s+)?logotipo|con\s+su\s+logotipo|logo\s+impreso)\b"""
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
            """(?i)\b(funda|fundas|carcasa|carcasas|cristal\s+templado|mop\s+kit|recambio|recambios|filtro|filtros|protector|adaptador|soporte|repuesto|repuestos|replacement|screen\s+protector|tempered\s+glass|cover|case)\b"""
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
        // Headphones such as WH-1000XM5 are tokenized as "wh 1000xm5".
        if ("wh" in tokens) {
            tokens.getOrNull(tokens.indexOf("wh") + 1)?.let { next ->
                if (Regex("""^\d+[a-z0-9]+$""").matches(next)) markers += "wh" + next
            }
        }
        return markers
    }

    private fun hasStrictModelMismatch(
        query: String,
        queryMarkers: Set<String>,
        resultTitle: String
    ): Boolean {
        if (queryMarkers.isEmpty()) return false
        val resultMarkers = explicitModelMarkers(resultTitle)
        if (resultMarkers.isEmpty() || queryMarkers.intersect(resultMarkers).isEmpty()) return true

        val variantWords = setOf("pro", "max", "plus", "ultra", "fe", "lite", "mini", "fold", "flip", "classic", "edge")
        val requestedVariants = normalizedProductTitle(query).split(' ').filter { it in variantWords }.toSet()
        val resultVariants = normalizedProductTitle(resultTitle).split(' ').filter { it in variantWords }.toSet()
        if (!resultVariants.containsAll(requestedVariants)) return true

        val memoryPattern = Regex("""\b\d+\s?(?:gb|tb)\b""")
        val requestedMemory = memoryPattern.findAll(normalizedProductTitle(query))
            .map { it.value.replace(" ", "") }.toSet()
        val availableMemory = memoryPattern.findAll(normalizedProductTitle(resultTitle))
            .map { it.value.replace(" ", "") }.toSet()
        return requestedMemory.isNotEmpty() && !availableMemory.containsAll(requestedMemory)
    }

    private fun isAccessorySearchQuery(query: String): Boolean =
        Regex(
            """\b(funda|fundas|carcasa|carcasas|cristal templado|protector|cable|cables|cargador|adaptador|hub|mop kit|recambio|recambios|filtro|filtros|repuesto|repuestos|replacement|case|cover)\b"""
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
        const val MIN_HISTORY_PRICE_EUR = 5.0
        const val MAX_HISTORY_PRICE_EUR = 10000.0
        const val MAX_FUTURE_OBSERVATION_SKEW_MS = 5L * 60L * 1000L
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

    fun simulatePriceDrop(productId: String, newLowPrice: Double? = null) {
        val source = _latestProducts.value.firstOrNull { it.id == productId } ?: return
        val droppedPrice = newLowPrice ?: (source.athMinPrice * 0.95)
        val newPoint = PricePoint(
            timestamp = System.currentTimeMillis(),
            dateLabel = "Simulación",
            price = droppedPrice,
            storeName = source.currentStore.name,
            eventLabel = "SIMULACIÓN DE DEMOSTRACIÓN — NO ES UN PRECIO REAL"
        )
        _simulationOverrides.value = _simulationOverrides.value + (
            productId to source.copy(
                currentPrice = droppedPrice,
                athMinPrice = minOf(source.athMinPrice, droppedPrice),
                athMinDate = "Simulación de demostración",
                athMinStore = source.currentStore.name,
                priceHistory = source.priceHistory + newPoint,
                isPriceVerified = false
            )
        )
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
        // Re-validate persisted observations at read time too. This protects users who already
        // have stale low-price, used-marketplace or wrong-variant rows from an older APK, even
        // before the startup cleanup has completed.
        val sortedHistory = verifiedObservations
            .filter { isSafeStoredObservation(it, title) }
            .sortedBy { it.observedAt }
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
        // The verified Room observation is authoritative; repair stale pre-v5 product flags.
        val priceIsVerified = latestObservation != null
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
                    url = observation.sourceUrl
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
                eventLabel = observation.sourceType.ifBlank { observation.sourceName }
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
            priceObservedAt = latestObservation?.observedAt.takeIf { priceIsVerified }
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
