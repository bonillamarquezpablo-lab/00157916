package com.example.data.repository

import com.example.data.local.ProductDao
import com.example.data.local.ProductEntity
import com.example.data.local.StoreDao
import com.example.data.local.StoreEntity
import com.example.data.local.TrackedProductDao
import com.example.data.local.TrackedProductEntity
import com.example.data.model.PricePoint
import com.example.data.model.Product
import com.example.data.model.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.net.URI

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
    externalScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val _customProducts = MutableStateFlow<List<Product>>(emptyList())
    private val _catalogProducts = MutableStateFlow<List<Product>>(SpanishMarketData.getInitialProducts())

    init {
        // Pre-populate Room database with all major Spanish stores and catalog
        externalScope.launch {
            val configuredStores = SpanishMarketData.getInitialStoreEntities()
            val existingStoreIds = storeDao.getAllStoresSnapshot().map { it.id }.toSet()
            val missingStores = configuredStores.filter { it.id !in existingStoreIds }
            if (missingStores.isNotEmpty()) storeDao.insertAll(missingStores)
            if (productDao.getProductCount() == 0) {
                val entities = SpanishMarketData.getInitialProducts().map { prod ->
                    ProductEntity(
                        id = prod.id,
                        title = prod.title,
                        brand = prod.brand,
                        category = prod.category.name,
                        currentPrice = prod.currentPrice,
                        athMinPrice = prod.athMinPrice,
                        athMinDate = prod.athMinDate,
                        athMinStore = prod.athMinStore,
                        athMaxPrice = prod.athMaxPrice,
                        avg90dPrice = prod.avg90dPrice,
                        officialPvp = prod.officialPvp,
                        currentStoreId = prod.currentStore.id,
                        storeUrl = prod.storeUrl,
                        ean = prod.ean,
                        imageResName = prod.imageResName,
                        imageUrl = prod.imageUrl,
                        rating = prod.rating,
                        reviewCount = prod.reviewCount,
                        wasRecentlyInflated = prod.wasRecentlyInflated,
                        fakePriorPrice = prod.fakePriorPrice
                    )
                }
                productDao.insertAll(entities)
            }
        }
    }

    val allStoresStream: Flow<List<Store>> = storeDao.getAllStores().map { list ->
        if (list.isEmpty()) Store.ALL_STORES
        else list.map { it.toDomainStore() }
    }

    // Stream combining catalog + custom items with Room database tracking states
    val allProductsStream: Flow<List<Product>> = combine(
        _catalogProducts,
        _customProducts,
        trackedDao.getAllTracked()
    ) { catalog, custom, trackedEntities ->
        val trackedMap = trackedEntities.associateBy { it.productId }
        (catalog + custom).map { product ->
            val trackingInfo = trackedMap[product.id]
            product.copy(
                isTracked = trackingInfo != null,
                alertTargetPrice = trackingInfo?.targetAlertPrice
            )
        }
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
        _catalogProducts.value = _catalogProducts.value.map { product ->
            if (product.id == productId) {
                val droppedPrice = newLowPrice ?: (product.athMinPrice * 0.95)
                val newPoint = PricePoint(
                    timestamp = System.currentTimeMillis(),
                    dateLabel = "Simulación",
                    price = droppedPrice,
                    storeName = product.currentStore.name,
                    eventLabel = "SIMULACIÓN DE DEMOSTRACIÓN — NO ES UN PRECIO REAL"
                )
                product.copy(
                    currentPrice = droppedPrice,
                    athMinPrice = minOf(product.athMinPrice, droppedPrice),
                    athMinDate = "Simulación de demostración",
                    athMinStore = product.currentStore.name,
                    priceHistory = product.priceHistory + newPoint
                )
            } else {
                product
            }
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
        val catalog = _catalogProducts.value + _customProducts.value

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
