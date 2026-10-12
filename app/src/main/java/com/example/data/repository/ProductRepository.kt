package com.example.data.repository

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
        val observationsByProduct = observations
            .filter { observation -> observation.isValidVerifiedObservation(stores) }
            .groupBy { it.productId }

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
        isPriceVerified = isPriceVerified,
        isOfficialPvpVerified = isOfficialPvpVerified,
        isRatingVerified = isRatingVerified,
        isEanVerified = isEanVerified
    )

    private fun ProductEntity.toDomainProduct(
        stores: List<Store>,
        verifiedObservations: List<PriceObservationEntity>
    ): Product {
        val sortedHistory = verifiedObservations.sortedBy { it.observedAt }
        val latestObservation = sortedHistory.lastOrNull()
        val minObservation = sortedHistory.minByOrNull { it.price }
        val maxObservation = sortedHistory.maxByOrNull { it.price }
        // Require both the explicit product flag and at least one provenance-bearing verified
        // observation before a stored row may be displayed as a real live price.
        val priceIsVerified = isPriceVerified && latestObservation != null
        val effectiveHistory = if (priceIsVerified) sortedHistory else emptyList()
        val effectiveStore = if (priceIsVerified) {
            stores.firstOrNull { it.id == latestObservation?.storeId }
                ?: Store.findByName(latestObservation?.storeId.orEmpty())
        } else {
            stores.firstOrNull { it.id == currentStoreId } ?: Store.findByName(currentStoreId)
        }
        val now = System.currentTimeMillis()
        val ninetyDaysAgo = now - 90L * 24L * 60L * 60L * 1000L
        val lastNinetyDays = effectiveHistory.filter { it.observedAt >= ninetyDaysAgo }
        val historyCoverage = (sortedHistory.lastOrNull()?.observedAt ?: 0L) -
            (sortedHistory.firstOrNull()?.observedAt ?: 0L)
        val hasFullNinetyDaySample = priceIsVerified &&
            sortedHistory.size >= 3 &&
            historyCoverage >= 90L * 24L * 60L * 60L * 1000L &&
            lastNinetyDays.size >= 3
        val current = if (priceIsVerified) latestObservation!!.price else currentPrice
        val lowest = if (priceIsVerified) minObservation!!.price else athMinPrice
        val highest = if (priceIsVerified) maxObservation!!.price else athMaxPrice
        val average = if (hasFullNinetyDaySample) lastNinetyDays.map { it.price }.average() else 0.0
        val dateFormatter = SimpleDateFormat("dd MMM yyyy", Locale("es", "ES"))
        val historyPoints = effectiveHistory.map { observation ->
            PricePoint(
                timestamp = observation.observedAt,
                dateLabel = dateFormatter.format(Date(observation.observedAt)),
                price = observation.price,
                storeName = stores.firstOrNull { it.id == observation.storeId }?.name ?: observation.storeId,
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
            athMinDate = if (priceIsVerified) dateFormatter.format(Date(minObservation!!.observedAt)) else athMinDate,
            athMinStore = if (priceIsVerified) (stores.firstOrNull { it.id == minObservation!!.storeId }?.name ?: minObservation!!.storeId) else athMinStore,
            athMaxPrice = highest,
            avg90dPrice = average,
            officialPvp = officialPvp,
            currentStore = effectiveStore,
            storeUrl = if (priceIsVerified) latestObservation!!.sourceUrl else storeUrl,
            ean = ean,
            priceHistory = historyPoints,
            // Historical offers in the original bundled catalogue were examples, not source-backed.
            offers = emptyList(),
            // The seed catalogue's fake-discount marker, PVP, ratings and barcode aren't
            // evidence from the price provider. Only expose those fields when independently verified.
            wasRecentlyInflated = false,
            fakePriorPrice = null,
            rating = rating,
            reviewCount = reviewCount,
            imageResName = imageResName,
            imageUrl = imageUrl.orEmpty(),
            isPriceVerified = priceIsVerified,
            isOfficialPvpVerified = isOfficialPvpVerified,
            isRatingVerified = isRatingVerified,
            isEanVerified = isEanVerified
        )
    }

    /**
     * The database verification flag alone is not enough to trust a price. Validate the fields
     * needed for provenance again at read time, including that the source URL belongs to the
     * configured retailer named by storeId.
     */
    private fun PriceObservationEntity.isValidVerifiedObservation(stores: List<Store>): Boolean {
        if (!isVerified || !price.isFinite() || price <= 0.0) return false
        if (currency != "EUR" || observedAt <= 0L) return false
        if (observedAt > System.currentTimeMillis() + 5L * 60L * 1000L) return false
        if (sourceName.isBlank()) return false

        val uri = parseHttpUri(sourceUrl) ?: return false
        if (uri.scheme?.equals("https", ignoreCase = true) != true) return false
        val host = uri.host?.let(::normalizeHost) ?: return false
        val sourceStore = stores.firstOrNull { store ->
            val storeHost = normalizeHost(store.domain.substringBefore('/'))
            storeHost.isNotBlank() && (host == storeHost || host.endsWith(".$storeHost"))
        } ?: return false

        return sourceStore.id == storeId
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
