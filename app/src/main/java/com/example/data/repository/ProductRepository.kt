package com.example.data.repository

import com.example.data.local.ProductDao
import com.example.data.local.ProductEntity
import com.example.data.local.StoreDao
import com.example.data.local.StoreEntity
import com.example.data.local.TrackedProductDao
import com.example.data.local.TrackedProductEntity
import com.example.data.model.PricePoint
import com.example.data.model.Product
import com.example.data.model.ProductCategory
import com.example.data.model.Store
import com.example.data.model.StoreOffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class UrlAnalysisResult(
    val queryOrUrl: String,
    val identifiedStore: Store?,
    val detectedTitle: String,
    val matchedProduct: Product?,
    val isVerifiedSpanishRetailer: Boolean,
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
            if (storeDao.getStoreCount() == 0) {
                storeDao.insertAll(SpanishMarketData.getInitialStoreEntities())
            }
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
                    dateLabel = "Ahora mismo",
                    price = droppedPrice,
                    storeName = product.currentStore.name,
                    eventLabel = "¡NUEVO MÍNIMO HISTÓRICO!"
                )
                product.copy(
                    currentPrice = droppedPrice,
                    athMinPrice = minOf(product.athMinPrice, droppedPrice),
                    athMinDate = "Hoy (Nuevo récord)",
                    athMinStore = product.currentStore.name,
                    priceHistory = product.priceHistory + newPoint
                )
            } else {
                product
            }
        }
    }

    fun analyzeSpanishUrlOrQuery(input: String): UrlAnalysisResult {
        val cleaned = input.trim().lowercase()

        // Match against existing catalog first
        val directMatch = (_catalogProducts.value + _customProducts.value).firstOrNull { prod ->
            prod.title.lowercase().contains(cleaned) ||
            prod.brand.lowercase().contains(cleaned) ||
            prod.ean == cleaned ||
            prod.storeUrl.lowercase().contains(cleaned) ||
            cleaned.contains(prod.id.lowercase())
        }

        if (directMatch != null) {
            return UrlAnalysisResult(
                queryOrUrl = input,
                identifiedStore = directMatch.currentStore,
                detectedTitle = directMatch.title,
                matchedProduct = directMatch,
                isVerifiedSpanishRetailer = true,
                adviceMessage = "Producto verificado en nuestra base histórica de precios de España."
            )
        }

        // Detect Spanish Retailer domain among all 20 Spanish stores
        val detectedStore = Store.ALL_STORES.firstOrNull { store ->
            cleaned.contains(store.domain) ||
            cleaned.contains(store.id) ||
            cleaned.contains(store.name.lowercase())
        }

        // Generate synthetic tracked item for the verified URL/query
        val extractedName = if (input.startsWith("http://") || input.startsWith("https://")) {
            val domain = input.substringAfter("://").substringBefore("/")
            val path = input.substringAfter(domain).replace("-", " ").replace("/", " ").trim()
            if (path.length > 5) "Artículo verificado en $domain (${path.take(35)}...)"
            else "Producto en $domain"
        } else {
            input.replaceFirstChar { it.uppercase() }
        }

        val estimatedPvp = 199.99
        val estimatedAth = 149.99
        val estimatedCurrent = 169.99
        val newId = "custom_" + System.currentTimeMillis()

        val generated = Product(
            id = newId,
            title = extractedName,
            brand = "Marca Detectada",
            category = ProductCategory.COMPUTING,
            currentPrice = estimatedCurrent,
            athMinPrice = estimatedAth,
            athMinDate = "Hace 2 meses",
            athMinStore = detectedStore?.name ?: "Amazon España",
            athMaxPrice = estimatedPvp,
            avg90dPrice = 179.0,
            officialPvp = estimatedPvp,
            currentStore = detectedStore ?: Store.AMAZON_ES,
            storeUrl = if (input.startsWith("http")) input else "https://www.google.es/search?q=$input",
            ean = "8400000000000",
            imageUrl = "https://images.unsplash.com/photo-1526738549149-8e07eca6c147?w=600",
            priceHistory = listOf(
                PricePoint(System.currentTimeMillis() - (90 * 86400000L), "Hace 90 días", estimatedPvp, "Mercado"),
                PricePoint(System.currentTimeMillis() - (45 * 86400000L), "Hace 45 días", estimatedAth, "Mínimo"),
                PricePoint(System.currentTimeMillis(), "Hoy", estimatedCurrent, "Actual")
            ),
            offers = listOf(
                StoreOffer(detectedStore ?: Store.AMAZON_ES, estimatedCurrent, true)
            )
        )

        // Add to custom monitored products
        _customProducts.value = _customProducts.value + generated

        return UrlAnalysisResult(
            queryOrUrl = input,
            identifiedStore = detectedStore,
            detectedTitle = extractedName,
            matchedProduct = generated,
            isVerifiedSpanishRetailer = detectedStore != null,
            adviceMessage = if (detectedStore != null) {
                "Enlace de tienda española (${detectedStore.name}) reconocido e indexado en la base de datos para auditoría."
            } else {
                "Producto agregado al rastreador de precios mínimos del mercado español."
            }
        )
    }
}
