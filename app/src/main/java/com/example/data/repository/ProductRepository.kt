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
import com.example.data.model.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
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
    // Room is the catalogue source of truth. This cache is only for URL analysis and explicitly
    // labelled local demo simulations; it does not replace the persisted product catalogue.
    private val _latestProducts = MutableStateFlow<List<Product>>(emptyList())
    private val _simulationOverrides = MutableStateFlow<Map<String, Product>>(emptyMap())

    init {
        // Seed new store/product IDs incrementally. Existing product rows and tracking settings are
        // preserved rather than replaced, and upgrades receive newly added catalogue entries.
        externalScope.launch {
            val configuredStores = SpanishMarketData.getInitialStoreEntities()
            val existingStoreIds = storeDao.getAllStoresSnapshot().map { it.id }.toSet()
            val missingStores = configuredStores.filter { it.id !in existingStoreIds }
            if (missingStores.isNotEmpty()) storeDao.insertAll(missingStores)

            val configuredProducts = SpanishMarketData.getInitialProducts()
            val existingProductIds = productDao.getAllProductsSnapshot().map { it.id }.toSet()
            val missingProducts = configuredProducts.filter { it.id !in existingProductIds }
            if (missingProducts.isNotEmpty()) {
                productDao.insertAll(missingProducts.map { it.toEntity() })
            }
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

        productEntities.map { entity ->
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
     * Store only provider-backed, EUR offers with HTTPS destination URLs. A product is marked
     * verified only if at least one validated offer was stored for that product.
     */
    suspend fun recordSerpApiQuotes(quotes: List<SerpApiQuote>): Int {
        val validQuotes = quotes.filter {
            it.productId.isNotBlank() &&
                it.price.isFinite() && it.price > 0.0 &&
                it.currency == "EUR" &&
                parseHttpUri(it.sourceUrl)?.scheme.equals("https", ignoreCase = true)
        }
        if (validQuotes.isEmpty()) return 0

        val observations = validQuotes.map { quote ->
            PriceObservationEntity(
                productId = quote.productId,
                storeId = quote.storeId,
                price = quote.price,
                currency = quote.currency,
                observedAt = quote.observedAt,
                sourceUrl = quote.sourceUrl,
                sourceName = "${quote.storeName} · ${quote.sourceName}",
                isVerified = true,
                verificationNote = "Consulta manual de Google Shopping España mediante SerpApi; validar precio y disponibilidad en la tienda antes de comprar."
            )
        }
        priceObservationDao.insertAll(observations)
        observations.map { it.productId }.distinct().forEach { productId ->
            productDao.updatePriceVerification(productId, true)
        }
        return observations.map { it.productId }.distinct().size
    }

    /**
     * Save a point-in-time history snapshot for an explicit model search from the online finder.
     * Broad brand searches are intentionally excluded so unrelated products are never merged into
     * one price history. Marketplaces, used/refurbished listings, and rental/financing offers are
     * shown in search but not mixed into the new-product price history.
     */
    suspend fun recordOnlineSearchSnapshot(
        query: String,
        results: List<SerpApiShoppingResult>
    ): Int {
        val normalizedQuery = normalizeHistoryKey(query)
        if (!hasSpecificModelIdentifier(normalizedQuery)) return 0

        val eligibleResults = results
            .filter(::isEligibleForNewProductHistory)
            .filter { it.price.isFinite() && it.price > 0.0 && parseHttpUri(it.sourceUrl)?.scheme.equals("https", ignoreCase = true) }
            .distinctBy { "${it.storeName.lowercase(Locale.ROOT)}|${it.sourceUrl.lowercase(Locale.ROOT)}|${it.price}" }
        if (eligibleResults.isEmpty()) return 0

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedQuery.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        val productId = "online_${digest.take(24)}"
        val currentProduct = productDao.getProductById(productId)
        val bestResult = eligibleResults.minBy { it.price }

        if (currentProduct == null) {
            productDao.insert(
                ProductEntity(
                    id = productId,
                    title = query.trim().replace(Regex("\\s+"), " "),
                    brand = query.trim().split(Regex("\\s+")).firstOrNull().orEmpty(),
                    category = ProductCategory.ALL.name,
                    currentPrice = bestResult.price,
                    athMinPrice = bestResult.price,
                    athMinDate = "Historial en construcción",
                    athMinStore = bestResult.storeName,
                    athMaxPrice = bestResult.price,
                    avg90dPrice = 0.0,
                    officialPvp = 0.0,
                    currentStoreId = onlineStoreId(bestResult.storeName),
                    storeUrl = bestResult.sourceUrl,
                    ean = "",
                    imageResName = null,
                    imageUrl = bestResult.thumbnailUrl.takeIf { it.isNotBlank() },
                    rating = 0.0,
                    reviewCount = 0,
                    wasRecentlyInflated = false,
                    fakePriorPrice = null,
                    isPriceVerified = false
                )
            )
        }

        val observations = eligibleResults.map { result ->
            PriceObservationEntity(
                productId = productId,
                storeId = onlineStoreId(result.storeName),
                price = result.price,
                currency = "EUR",
                observedAt = result.observedAt,
                sourceUrl = result.sourceUrl,
                sourceName = "${result.storeName} · SerpApi · Google Shopping España",
                isVerified = true,
                verificationNote = "Observación de búsqueda manual para el modelo «${query.trim()}». No garantiza precio final, stock, envío ni estado; confirma esos datos con el vendedor."
            )
        }
        priceObservationDao.insertAll(observations)
        productDao.updatePriceVerification(productId, true)
        return observations.size
    }

    private fun hasSpecificModelIdentifier(normalizedQuery: String): Boolean =
        normalizedQuery.split(' ').any { token ->
            token.length >= 3 &&
                token.any(Char::isLetter) &&
                token.any(Char::isDigit)
        }

    private fun isEligibleForNewProductHistory(result: SerpApiShoppingResult): Boolean {
        val merchant = normalizeHistoryKey(result.storeName)
        val nonRetailMarketplaces = listOf(
            "wallapop", "ebay", "vinted", "milanuncios", "facebookmarketplace",
            "cashconverters", "realcash"
        )
        if (nonRetailMarketplaces.any { it in merchant }) return false

        val condition = normalizeHistoryKey(result.condition)
        if (condition.isNotBlank() && condition !in setOf("new", "nuevo", "brandnew", "nuevos")) return false

        val title = normalizeHistoryKey(result.title)
        val excludedTerms = listOf(
            "segundamano", "usado", "reacondicionado", "refurbished",
            "alquiler", "alquilar", "renting", "rental", "leasing",
            "financiacion", "cuotamensual"
        )
        return excludedTerms.none { it in title }
    }

    private fun normalizeHistoryKey(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun onlineStoreId(storeName: String): String =
        "online_store_" + normalizeHistoryKey(storeName)
            .replace(" ", "_")
            .take(48)
            .ifBlank { "unknown" }

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
        val catalog = _latestProducts.value.ifEmpty { SpanishMarketData.getInitialProducts() }

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
                adviceMessage = "Coincidencia con una ficha del catálogo de demostración. Sus precios e historial son datos de ejemplo; no se ha consultado la tienda ni verificado el precio actual."
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
                "No hay coincidencia con el catálogo de demostración. Escribe el modelo exacto o pega un enlace de producto. Esta versión aún no dispone de una fuente de precios en directo."
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
        val sortedHistory = verifiedObservations.sortedBy { it.observedAt }
        // Each explicit refresh can produce several retailer offers at the same observation time.
        // Use the cheapest offer in the latest batch as the visible price, but retain all offers.
        val latestObservedAt = sortedHistory.lastOrNull()?.observedAt
        val latestBatch = sortedHistory.filter { it.observedAt == latestObservedAt }
        val snapshotHistory = sortedHistory
            .groupBy { it.observedAt }
            .values
            .mapNotNull { batch -> batch.minByOrNull { it.price } }
            .sortedBy { it.observedAt }
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
        val uniqueTimestamps = snapshotHistory.map { it.observedAt }.distinct().sorted()
        val hasSufficientHistory = priceIsVerified &&
            uniqueTimestamps.size >= 3 &&
            uniqueTimestamps.last() - uniqueTimestamps.first() >= ninetyDaysMs &&
            lastNinetyDays.map { it.observedAt }.distinct().size >= 3 &&
            now - (uniqueTimestamps.lastOrNull() ?: 0L) <= 7L * 24L * 60L * 60L * 1000L
        val current = if (priceIsVerified) latestObservation!!.price else currentPrice
        // Never present a single snapshot as an historical minimum or a 90-day average.
        val lowest = if (hasSufficientHistory) minObservation!!.price else current
        val highest = if (hasSufficientHistory) maxObservation!!.price else current
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
        val historyPoints = effectiveHistory.map { observation ->
            PricePoint(
                timestamp = observation.observedAt,
                dateLabel = dateFormatter.format(Date(observation.observedAt)),
                price = observation.price,
                storeName = observationStore(observation, stores).name,
                eventLabel = observation.sourceName
            )
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
        val merchantName = observation.sourceName.substringBefore(" · SerpApi")
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
