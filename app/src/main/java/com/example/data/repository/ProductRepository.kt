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
        val catalog = _latestProducts.value.ifEmpty { SpanishMarketData.getInitialProducts() }
        // The currently refreshed item can exist in Room before the catalogue flow has emitted it.
        // Resolve its real stored title as well as the bundled seed data.
        val storedProducts = productDao.getAllProductsSnapshot()
        val titleById = buildMap {
            catalog.forEach { put(it.id, it.title) }
            storedProducts.forEach { put(it.id, it.title) }
        }
        val validQuotes = quotes.filter { quote ->
            val title = titleById[quote.productId].orEmpty()
            quote.productId.isNotBlank() &&
                quote.price.isFinite() && quote.price in MIN_HISTORY_PRICE_EUR..MAX_HISTORY_PRICE_EUR &&
                quote.currency == "EUR" &&
                parseHttpUri(quote.sourceUrl)?.scheme.equals("https", ignoreCase = true) &&
                isEligibleHistoryTitle(title)
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
     * Persist each valid "new in store" result returned by a manual online search.
     * Results are observations, not synthetic historical points. A known catalogue product
     * reuses its product ID; an unmatched result still gets a stable Room identity and title.
     */
    suspend fun recordOnlineSearchResults(
        results: List<SerpApiShoppingResult>,
        sourceType: String
    ): Int {
        val normalizedSource = when (sourceType) {
            "AMAZON" -> "Amazon.es"
            "GOOGLE_SHOPPING" -> "Google Shopping"
            "Amazon.es", "Google Shopping" -> sourceType
            else -> return 0
        }
        val catalog = _latestProducts.value.ifEmpty { SpanishMarketData.getInitialProducts() }
        val eligible = results.filter { result ->
            result.title.isNotBlank() &&
                result.storeName.isNotBlank() &&
                result.price.isFinite() && result.price in MIN_HISTORY_PRICE_EUR..MAX_HISTORY_PRICE_EUR &&
                parseHttpUri(result.sourceUrl)?.scheme.equals("https", ignoreCase = true) &&
                isEligibleHistoryTitle(result.title)
        }
        if (eligible.isEmpty()) return 0

        val eligibleModels = eligible.map { result -> result to modelCode(result.title) }
        var inserted = 0
        for ((result, code) in eligibleModels) {
            // Very low price outliers are rejected only against at least three listings carrying
            // the same identifiable model code. Broad searches such as "Samsung" remain usable.
            if (code != null) {
                val comparablePrices = eligibleModels
                    .filter { (other, otherCode) ->
                        otherCode == code && isEligibleHistoryTitle(other.title)
                    }
                    .map { (other, _) -> other.price }
                    .sorted()
                if (comparablePrices.size >= 3) {
                    val median = comparablePrices[comparablePrices.size / 2]
                    if (result.price < median * MIN_OUTLIER_PRICE_RATIO) continue
                }
            }

            val matchedProduct = catalog.firstOrNull { product ->
                isSafeCatalogTitleMatch(result.title, product.title)
            }
            val productId = matchedProduct?.id
                ?: stableOnlineId("online_product_", normalizedProductTitle(result.title))
            val storeId = canonicalStoreId(result.storeName, result.sourceUrl)
            val (dayStart, dayEnd) = localDayBounds(result.observedAt)
            if (priceObservationDao.getObservationForStoreProductDay(
                    productId, storeId, dayStart, dayEnd
                ) != null
            ) continue

            priceObservationDao.insert(
                PriceObservationEntity(
                    productId = productId,
                    storeId = storeId,
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
            if (matchedProduct != null) {
                productDao.updatePriceVerification(matchedProduct.id, true)
            }
            inserted++
        }
        return inserted
    }

    private fun isEligibleHistoryTitle(title: String): Boolean {
        if (title.isBlank()) return false
        val excludedListing = Regex(
            """(?i)\b(segunda\s+mano|usad[oa]s?|used|reacondicionad[oa]s?|refurbished|renewed|pre-owned|seminuev[oa]s?|alquiler|renting|rent|leasing|lease|hire|financiaci[oó]n|financiar|cuota\s+mensual|pago\s+mensual|pago\s+a\s+plazos|monthly|por\s+mes|\d+\s*€/mes)\b"""
        )
        return !excludedListing.containsMatchIn(title)
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
        Regex("""(?i)\b[a-z0-9-]{4,}\b""")
            .findAll(title)
            .map { it.value.lowercase(Locale.ROOT).replace("-", "") }
            .filter { value -> value.any(Char::isLetter) && value.any(Char::isDigit) }
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
        const val MIN_OUTLIER_PRICE_RATIO = 0.20
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
        // The chart shows every source-backed store observation, including separate stores
        // seen at the same search time. Summary calculations continue to use de-duplicated batches.
        val historyPoints = if (priceIsVerified) sortedHistory.map { observation ->
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
