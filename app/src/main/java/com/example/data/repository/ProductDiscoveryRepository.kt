package com.example.data.repository

import androidx.room.withTransaction
import androidx.compose.ui.graphics.toArgb
import com.example.data.local.AppDatabase
import com.example.data.local.DiscoveryPriceObservationEntity
import com.example.data.local.PriceObservationEntity
import com.example.data.local.ProductDiscoveryEntity
import com.example.data.local.ProductEntity
import com.example.data.local.StoreEntity
import com.example.data.model.ProductCategory
import com.example.data.model.PENDING_VERIFICATION_DATE_LABEL
import com.example.data.model.Store
import com.example.data.remote.ArchivedPriceQuote
import com.example.data.remote.onlineProductIdentityKey
import kotlinx.coroutines.flow.Flow
import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class DiscoveryImportResult(
    val productId: String,
    val createdProduct: Boolean,
    val savedObservations: Int
)

/**
 * Holds incidental archived Product/Offer records outside the visible catalogue.
 * Nothing enters products or price_observations until the user accepts it in Radar.
 */
class ProductDiscoveryRepository(private val database: AppDatabase) {
    val pendingDiscoveries: Flow<List<ProductDiscoveryEntity>> =
        database.productDiscoveryDao().observePending()

    suspend fun stageArchivedQuote(
        quote: ArchivedPriceQuote,
        fallbackStoreName: String
    ): Boolean = database.withTransaction {
        if (!quote.isDiscoveryCandidate || !quote.targetProductId.isNullOrBlank()) return@withTransaction false
        val title = quote.productTitle.trim().replace(Regex("""\s+"""), " ")
        val productUrl = validatedHttpsUrl(quote.productUrl) ?: return@withTransaction false
        val sourcePageUrl = validatedHttpsUrl(quote.originalUrl) ?: return@withTransaction false
        val snapshotUrl = validatedArchiveUrl(quote.snapshotUrl) ?: return@withTransaction false
        val productUri = runCatching { URI(productUrl) }.getOrNull() ?: return@withTransaction false
        val sourceUri = runCatching { URI(sourcePageUrl) }.getOrNull() ?: return@withTransaction false
        val productHost = normalizeHost(productUri.host.orEmpty())
        val sourceHost = normalizeHost(sourceUri.host.orEmpty())

        // A recommendation pointing at a different merchant is not enough proof of that seller's
        // offer. Stage only a variant URL published on the same merchant page as the snapshot.
        if (productHost.isBlank() || productHost != sourceHost ||
            normalizeUrl(productUrl) == normalizeUrl(sourcePageUrl)
        ) return@withTransaction false

        val now = System.currentTimeMillis()
        val earliestAllowed = now - 2920L * 24L * 60L * 60L * 1000L
        if (quote.observedAt <= 0L || quote.observedAt >= now || quote.observedAt < earliestAllowed ||
            !quote.currency.equals("EUR", ignoreCase = true) ||
            !quote.price.isFinite() || quote.price !in MIN_DISCOVERY_PRICE..MAX_DISCOVERY_PRICE ||
            !isDiscoveryTitleEligible(title) || hasVariantUrlConflict(title, productUrl)
        ) return@withTransaction false
        val captureTime = validatedSnapshotTimestamp(snapshotUrl)
        if (captureTime != null && kotlin.math.abs(captureTime - quote.observedAt) > 1_000L) {
            return@withTransaction false
        }

        val merchant = resolveMerchant(productHost, fallbackStoreName)
        if (!isEligibleMerchant(merchant)) return@withTransaction false
        val identityKey = discoveryIdentityKey(title, productUrl)
        if (identityKey.isBlank()) return@withTransaction false

        val savedProducts = database.productDao().getAllProductsSnapshot()
        val trackedProductIds = database.trackedProductDao().getAllTrackedProductIds().toSet()
        val productIdsWithObservations = database.priceObservationDao().getAllProductIdsWithObservations().toSet()
        val existingTrustedMatch = savedProducts.any { saved ->
            isSameProductIdentity(title, productUrl, saved.title, saved.storeUrl) &&
                (!isUnverifiedLiveSearchPlaceholder(saved) ||
                    saved.id in trackedProductIds ||
                    saved.id in productIdsWithObservations)
        }
        if (existingTrustedMatch) {
            // Historical data for a trusted, tracked, or observed row is handled by its normal
            // product-history path. A live-search placeholder is different: it is not verified
            // history and must not suppress a discovery from an archived page.
            return@withTransaction false
        }

        val dao = database.productDiscoveryDao()
        var discovery = dao.getByIdentityKey(identityKey)
        if (discovery != null && discovery.status != ProductDiscoveryEntity.STATUS_PENDING) {
            // Do not resurrect dismissed candidates or re-stage already accepted products.
            return@withTransaction false
        }

        val storeId = canonicalStoreId(productHost)
        if (discovery == null) {
            val newDiscovery = ProductDiscoveryEntity(
                id = "discovery_${sha256(identityKey).take(24)}",
                identityKey = identityKey,
                title = title,
                brand = inferBrand(title),
                category = inferCategory(title).name,
                productUrl = productUrl,
                latestStoreId = storeId,
                latestStoreName = merchant,
                latestStoreDomain = productHost,
                latestPrice = quote.price,
                currency = "EUR",
                firstObservedAt = quote.observedAt,
                lastObservedAt = quote.observedAt,
                captureCount = 0,
                status = ProductDiscoveryEntity.STATUS_PENDING,
                createdAt = now,
                updatedAt = now
            )
            dao.insertDiscovery(newDiscovery)
            discovery = dao.getByIdentityKey(identityKey) ?: return@withTransaction false
        }

        val observation = DiscoveryPriceObservationEntity(
            discoveryId = discovery.id,
            price = quote.price,
            currency = "EUR",
            observedAt = quote.observedAt,
            snapshotUrl = snapshotUrl,
            sourcePageUrl = sourcePageUrl,
            productUrl = productUrl,
            storeId = storeId,
            storeName = merchant,
            storeDomain = productHost,
            sourceName = archiveProviderName(snapshotUrl),
            collectedAt = now,
            verificationNote = if (
                quote.sourceCurrency.equals("USD", ignoreCase = true) &&
                quote.exchangeRate != null &&
                !quote.exchangeRateDate.isNullOrBlank()
            ) {
                "Precio archivado originalmente en " +
                    String.format(Locale.ROOT, "%.2f", quote.sourcePrice) + " USD; convertido a " +
                    String.format(Locale.ROOT, "%.2f", quote.price) +
                    " EUR con 1 USD = " + String.format(Locale.ROOT, "%.4f", quote.exchangeRate) +
                    " EUR, fecha efectiva " + quote.exchangeRateDate +
                    " (fuente " + (quote.conversionSource ?: "no indicada") + "). " +
                    "Producto identificado en Product/Offer JSON-LD; es histórico, no una oferta actual."
            } else {
                "Oferta estructurada Product/Offer verificada en una captura pública archivada. " +
                    "La página capturada y el destino de la variante se conservan por separado; no representa un precio actual."
            }
        )
        if (dao.insertObservation(observation) < 0L) return@withTransaction false

        val isNewest = quote.observedAt >= discovery.lastObservedAt
        dao.updateDiscovery(
            discovery.copy(
                title = title,
                brand = inferBrand(title),
                category = inferCategory(title).name,
                productUrl = if (isNewest) productUrl else discovery.productUrl,
                latestStoreId = if (isNewest) storeId else discovery.latestStoreId,
                latestStoreName = if (isNewest) merchant else discovery.latestStoreName,
                latestStoreDomain = if (isNewest) productHost else discovery.latestStoreDomain,
                latestPrice = if (isNewest) quote.price else discovery.latestPrice,
                firstObservedAt = minOf(discovery.firstObservedAt, quote.observedAt),
                lastObservedAt = maxOf(discovery.lastObservedAt, quote.observedAt),
                captureCount = discovery.captureCount + 1,
                updatedAt = now
            )
        )
        true
    }

    suspend fun accept(discoveryId: String): DiscoveryImportResult? = database.withTransaction {
        val dao = database.productDiscoveryDao()
        val discovery = dao.getById(discoveryId) ?: return@withTransaction null
        if (discovery.status != ProductDiscoveryEntity.STATUS_PENDING) return@withTransaction null

        val stagedObservations = dao.getObservations(discovery.id)
            .filter {
                it.currency.equals("EUR", ignoreCase = true) &&
                    it.price.isFinite() && it.price in MIN_DISCOVERY_PRICE..MAX_DISCOVERY_PRICE &&
                    it.observedAt in 1L until System.currentTimeMillis() &&
                    validatedArchiveUrl(it.snapshotUrl) != null &&
                    validatedHttpsUrl(it.sourcePageUrl) != null &&
                    validatedHttpsUrl(it.productUrl) != null &&
                    normalizeHost(URI(it.productUrl).host.orEmpty()) == normalizeHost(URI(it.sourcePageUrl).host.orEmpty()) &&
                    !hasVariantUrlConflict(discovery.title, it.productUrl)
            }
            .distinctBy {
                listOf(it.storeId, localDayStart(it.observedAt).toString(), it.snapshotUrl, it.price.toString())
            }
            .sortedBy { it.observedAt }
        if (stagedObservations.isEmpty()) return@withTransaction null

        val catalogue = database.productDao().getAllProductsSnapshot()
        val matches = catalogue.filter {
            isSameProductIdentity(discovery.title, discovery.productUrl, it.title, it.storeUrl)
        }
        if (matches.size > 1) return@withTransaction null
        val existingProduct = matches.singleOrNull()
        val now = System.currentTimeMillis()
        val latestObservation = stagedObservations.maxByOrNull { it.observedAt }
            ?: return@withTransaction null
        val latestStoreId = ensureStore(
            latestObservation.storeId, latestObservation.storeName, latestObservation.storeDomain
        )
        val productId = existingProduct?.id ?: "discovered_product_${sha256(discovery.identityKey).take(24)}"

        if (existingProduct == null) {
            if (database.productDao().getProductById(productId) != null) return@withTransaction null
            val product = ProductEntity(
                id = productId,
                title = discovery.title,
                brand = discovery.brand,
                category = discovery.category,
                // Archive-only price: kept unverified as a current quote.
                currentPrice = latestObservation.price,
                athMinPrice = stagedObservations.minOf { it.price },
                athMinDate = "Historial en construcción",
                athMinStore = "",
                athMaxPrice = stagedObservations.maxOf { it.price },
                avg90dPrice = 0.0,
                officialPvp = 0.0,
                currentStoreId = latestStoreId,
                storeUrl = validatedHttpsUrl(latestObservation.productUrl) ?: discovery.productUrl,
                ean = "",
                imageResName = null,
                imageUrl = null,
                rating = 0.0,
                reviewCount = 0,
                wasRecentlyInflated = false,
                fakePriorPrice = null,
                isPriceVerified = false,
                googleProductId = null,
                isOfficialPvpVerified = false,
                isRatingVerified = false,
                isEanVerified = false
            )
            database.productDao().insert(product)
        }

        var savedObservations = 0
        val canonicalTitle = existingProduct?.title ?: discovery.title
        for (staged in stagedObservations) {
            val storeId = ensureStore(staged.storeId, staged.storeName, staged.storeDomain)
            val observation = PriceObservationEntity(
                productId = productId,
                storeId = storeId,
                price = staged.price,
                currency = "EUR",
                observedAt = staged.observedAt,
                sourceUrl = staged.snapshotUrl,
                sourceName = staged.sourceName,
                isVerified = true,
                verificationNote = staged.verificationNote,
                productTitle = canonicalTitle,
                storeName = staged.storeName,
                sourceType = "Internet Archive · Radar de descubrimientos",
                linkType = "Captura histórica",
                collectedAt = staged.collectedAt,
                evidenceType = "archive",
                sourcePageUrl = staged.sourcePageUrl
            )
            val (dayStart, dayEnd) = localDayBounds(staged.observedAt)
            if (database.priceObservationDao().saveArchivedObservationIfLower(
                    observation = observation,
                    dayStart = dayStart,
                    dayEnd = dayEnd
                )
            ) savedObservations++
        }

        dao.updateStatus(discovery.id, ProductDiscoveryEntity.STATUS_ACCEPTED, now)
        DiscoveryImportResult(productId, existingProduct == null, savedObservations)
    }

    suspend fun dismiss(discoveryId: String): Boolean = database.withTransaction {
        val dao = database.productDiscoveryDao()
        val current = dao.getById(discoveryId) ?: return@withTransaction false
        if (current.status != ProductDiscoveryEntity.STATUS_PENDING) return@withTransaction false
        dao.updateStatus(
            id = discoveryId,
            status = ProductDiscoveryEntity.STATUS_DISMISSED,
            updatedAt = System.currentTimeMillis()
        ) > 0
    }

    private suspend fun ensureStore(requestedId: String, requestedName: String, rawDomain: String): String {
        val domain = normalizeHost(rawDomain)
        val configured = Store.ALL_STORES.firstOrNull {
            val known = normalizeHost(it.domain.substringBefore('/'))
            known.isNotBlank() && (domain == known || domain.endsWith(".$known"))
        }
        val existing = database.storeDao().getAllStoresSnapshot().firstOrNull {
            val stored = normalizeHost(it.domain.substringBefore('/'))
            it.id == requestedId || (stored.isNotBlank() &&
                (domain == stored || domain.endsWith(".$stored") || stored.endsWith(".$domain")))
        }
        if (existing != null) return existing.id

        val name = configured?.name ?: requestedName.ifBlank { titleCaseHost(domain) }
        val id = configured?.id ?: requestedId.ifBlank { stableStoreId(domain) }
        val colorHex = configured?.let { String.format(Locale.ROOT, "#%08X", it.brandColor.toArgb()) }
            ?: "#FF047857"
        database.storeDao().insertAll(
            listOf(
                StoreEntity(
                    id = id,
                    name = name,
                    domain = configured?.domain ?: domain,
                    brandColorHex = colorHex,
                    category = configured?.category ?: "Tienda con precios",
                    physicalStoresInSpain = configured?.physicalStores ?: false,
                    storeCountApprox = 0,
                    headquartersInSpain = "",
                    description = "Comercio identificado desde el Radar de descubrimientos."
                )
            )
        )
        return id
    }

    private fun resolveMerchant(domain: String, suggestedName: String): String {
        val known = Store.ALL_STORES.firstOrNull {
            val knownDomain = normalizeHost(it.domain.substringBefore('/'))
            knownDomain.isNotBlank() && (domain == knownDomain || domain.endsWith(".$knownDomain"))
        }
        if (known != null) return known.name
        val candidate = suggestedName.trim()
        return if (candidate.isNotBlank() &&
            !candidate.contains("Google Shopping", ignoreCase = true) &&
            !candidate.contains("Amazon results", ignoreCase = true)
        ) candidate else titleCaseHost(domain)
    }

    private fun isEligibleMerchant(name: String): Boolean =
        name.isNotBlank() && !Regex("""(?i)\b(wallapop|cash\s+converters|vinted|cex|rentik)\b""")
            .containsMatchIn(name)

    private fun isDiscoveryTitleEligible(title: String): Boolean {
        val words = normalizeText(title).split(' ').filter { it.isNotBlank() }
        if (title.length !in 12..180 || words.size < 3) return false
        val generic = listOf(
            "productos recomendados", "productos relacionados", "completa tu compra",
            "mas vendidos", "ver mas", "related products", "recommended products", "you may also like"
        )
        if (generic.any { normalizeText(title).contains(it) }) return false
        val accessory = Regex(
            """(?i)\b(fundas?|carcasas?|cristal\s+templado|vidrio\s+templado|protectores?|cables?|cargadores?|adaptadores?|soportes?|recambios?|repuestos?|hubs?|cases?|covers?|screen\s+protector|tempered\s+glass|chargers?|accessories|accesorios)\b"""
        )
        if (accessory.containsMatchIn(title)) return false
        return !Regex("""(?i)\b(pack\s+promocional|lote\s+promocional|por\s+mayor|con\s+logotipo|personalizado\s+con\s+logo)\b""")
            .containsMatchIn(title)
    }

    private fun inferBrand(title: String): String {
        val brands = listOf(
            "Samsung", "Apple", "Xiaomi", "Redmi", "POCO", "Google", "Sony", "LG", "Nintendo",
            "PlayStation", "Microsoft", "Xbox", "Lenovo", "ASUS", "Acer", "HP", "Dell", "MSI",
            "Huawei", "Motorola", "OnePlus", "OPPO", "Anker", "Logitech", "JBL", "Bose", "Philips",
            "Hisense", "TCL", "Dyson", "Bosch", "Canon", "Nikon", "Garmin", "Intel", "AMD", "NVIDIA"
        )
        val normalized = normalizeText(title)
        return brands.firstOrNull { normalizeText(it) in normalized.split(' ') }
            ?: title.split(' ').firstOrNull().orEmpty().take(40)
    }

    private fun inferCategory(title: String): ProductCategory {
        val value = normalizeText(title)
        return when {
            isToyListingTitle(title) -> ProductCategory.TOYS
            isReplacementPartListingTitle(title) -> ProductCategory.REPLACEMENT_PARTS
            isAccessoryListingTitle(title) -> ProductCategory.ACCESSORIES
            Regex("""\b(airwrap|corrale|supersonic|planchas? de pelo|alisador(?:es)?|secador(?:es)?|styler|afeitadora|depiladora)\b""").containsMatchIn(value) -> ProductCategory.PERSONAL_CARE
            Regex("""\b(iphone|samsung galaxy|smartphone|telefono movil|redmi|pixel|ipad|tablet|oneplus|motorola edge)\b""").containsMatchIn(value) -> ProductCategory.SMARTPHONES
            Regex("""\b(laptop|portatil|notebook|thinkpad|macbook|chromebook|monitor|gpu|geforce|radeon|rtx|processor|procesador|ssd|ram|pc gaming|desktop)\b""").containsMatchIn(value) -> ProductCategory.COMPUTING
            Regex("""\b(playstation|ps5|ps4|xbox|nintendo switch|steam deck|rog ally|console|consola|joy con)\b""").containsMatchIn(value) -> ProductCategory.GAMING
            Regex("""\b(television|tv|oled|qled|bravia|soundbar|barra de sonido|headphones|auriculares|speaker|altavoz)\b""").containsMatchIn(value) -> ProductCategory.TV_AUDIO
            Regex("""\b(lavadora|frigorifico|lavavajillas|cafetera|aspiradora|air fryer|freidora|horno|microondas|batidora|secadora)\b""").containsMatchIn(value) -> ProductCategory.HOME_APPLIANCES
            Regex("""\b(afeitadora|depiladora|secador de pelo|cepillo electrico|oral b|oneblade)\b""").containsMatchIn(value) -> ProductCategory.PERSONAL_CARE
            Regex("""\b(bicicleta|shimano|zapatillas|running|futbol|ciclismo|pesas|fitness|raqueta|camping)\b""").containsMatchIn(value) -> ProductCategory.SPORTS
            else -> ProductCategory.OTHER
        }
    }

    private fun isUnverifiedLiveSearchPlaceholder(product: ProductEntity): Boolean =
        !product.isPriceVerified &&
            (product.id.startsWith("online_candidate_") ||
                product.athMinDate.equals(PENDING_VERIFICATION_DATE_LABEL, ignoreCase = true))

    private fun discoveryIdentityKey(title: String, productUrl: String): String {
        val normalizedTitle = normalizeText(title)
        val capacity = capacitySignal(title) ?: capacitySignal(productUrl).orEmpty()
        val color = colorSignal(title) ?: colorSignal(productUrl).orEmpty()
        val model = modelSignal(title) ?: modelSignal(productUrl).orEmpty()
        return "title=$normalizedTitle|model=$model|capacity=$capacity|color=$color"
    }

    private fun isSameProductIdentity(
        candidateTitle: String,
        candidateUrl: String,
        savedTitle: String,
        savedUrl: String
    ): Boolean {
        if (discoveryIdentityKey(candidateTitle, candidateUrl) ==
            discoveryIdentityKey(savedTitle, savedUrl)
        ) return true
        if (onlineProductIdentityKey(candidateTitle) != onlineProductIdentityKey(savedTitle)) return false
        val candidateCapacity = capacitySignal(candidateTitle) ?: capacitySignal(candidateUrl)
        val savedCapacity = capacitySignal(savedTitle) ?: capacitySignal(savedUrl)
        if (candidateCapacity != null && savedCapacity != null && candidateCapacity != savedCapacity) return false
        val candidateColor = colorSignal(candidateTitle) ?: colorSignal(candidateUrl)
        val savedColor = colorSignal(savedTitle) ?: colorSignal(savedUrl)
        if (candidateColor != null && savedColor != null && candidateColor != savedColor) return false
        val candidateModel = modelSignal(candidateTitle) ?: modelSignal(candidateUrl)
        val savedModel = modelSignal(savedTitle) ?: modelSignal(savedUrl)
        if (candidateModel != null && savedModel != null && candidateModel != savedModel) return false
        return true
    }

    private fun hasVariantUrlConflict(title: String, url: String): Boolean {
        val titleCapacity = capacitySignal(title)
        val urlCapacity = capacitySignal(url)
        if (titleCapacity != null && urlCapacity != null && titleCapacity != urlCapacity) return true
        val titleColor = colorSignal(title)
        val urlColor = colorSignal(url)
        if (titleColor != null && urlColor != null && titleColor != urlColor) return true
        val titleModel = modelSignal(title)
        val urlModel = modelSignal(url)
        return titleModel != null && urlModel != null && titleModel != urlModel
    }

    private fun capacitySignal(value: String): String? =
        Regex("""(?i)\b(\d+)\s*(gb|tb)\b""").find(decodeForIdentity(value))
            ?.let { it.groupValues[1] + it.groupValues[2].lowercase(Locale.ROOT) }

    private fun colorSignal(value: String): String? {
        val normalized = normalizeText(decodeForIdentity(value))
        val colors = listOf(
            "titanium gray" to "titanium-gray", "titanium grey" to "titanium-gray",
            "titanium black" to "titanium-black", "titanium blue" to "titanium-blue",
            "space black" to "space-black", "midnight" to "midnight",
            "negro" to "black", "black" to "black", "blanco" to "white", "white" to "white",
            "gris" to "gray", "grey" to "gray", "gray" to "gray", "azul" to "blue", "blue" to "blue",
            "amarillo" to "yellow", "yellow" to "yellow", "verde" to "green", "green" to "green",
            "rojo" to "red", "red" to "red", "rosa" to "pink", "pink" to "pink",
            "morado" to "purple", "purple" to "purple", "dorado" to "gold", "gold" to "gold",
            "plateado" to "silver", "silver" to "silver"
        )
        return colors.firstOrNull { (term, _) ->
            val normalizedTerm = normalizeText(term)
            Regex("""\b${Regex.escape(normalizedTerm)}\b""").containsMatchIn(normalized)
        }?.second
    }

    private fun modelSignal(value: String): String? =
        Regex("""(?i)\b[a-z]{1,8}[- ]?\d[a-z0-9-]{2,}\b""").find(normalizeText(decodeForIdentity(value)))
            ?.value?.replace(Regex("""[^a-z0-9]"""), "")

    private fun validatedHttpsUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        val host = normalizeHost(uri.host)
        if (host == "web.archive.org" || host.endsWith(".archive.org") || host == "arquivo.pt" ||
            host == "google.com" || host.endsWith(".google.com") || host.endsWith(".google.es")
        ) return null
        return uri.toString()
    }

    private fun validatedArchiveUrl(raw: String): String? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val host = normalizeHost(uri.host.orEmpty())
        if (!uri.scheme.equals("https", ignoreCase = true) || host !in setOf("web.archive.org", "arquivo.pt")) return null
        return raw.trim()
    }

    private fun validatedSnapshotTimestamp(raw: String): Long? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (normalizeHost(uri.host.orEmpty()) != "web.archive.org") return null
        val stamp = Regex("""/web/(\d{14})""").find(uri.path.orEmpty())?.groupValues?.getOrNull(1) ?: return null
        val parser = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        return runCatching { parser.parse(stamp)?.time }.getOrNull()
    }

    private fun canonicalStoreId(domain: String): String {
        val configured = Store.ALL_STORES.firstOrNull {
            val known = normalizeHost(it.domain.substringBefore('/'))
            known.isNotBlank() && (domain == known || domain.endsWith(".$known"))
        }
        return configured?.id ?: stableStoreId(domain)
    }

    private fun stableStoreId(domain: String): String = "discovery_store_${sha256(normalizeHost(domain)).take(16)}"

    private fun archiveProviderName(snapshotUrl: String): String =
        if (normalizeHost(URI(snapshotUrl).host.orEmpty()) == "arquivo.pt") "Arquivo.pt (Memento)"
        else "Internet Archive (Wayback Machine)"

    private fun normalizeUrl(raw: String): String = raw.trim().trimEnd('/').lowercase(Locale.ROOT)
    private fun normalizeHost(raw: String): String = raw.trim().lowercase(Locale.ROOT).removePrefix("www.")
    private fun titleCaseHost(domain: String): String =
        domain.substringBefore('.').replace('-', ' ').replaceFirstChar { it.uppercase(Locale.ROOT) }

    private fun normalizeText(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim().replace(Regex("""\s+"""), " ")

    private fun decodeForIdentity(raw: String): String =
        runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun localDayStart(timestamp: Long): Long {
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun localDayBounds(timestamp: Long): Pair<Long, Long> =
        localDayStart(timestamp) to (localDayStart(timestamp) + 24L * 60L * 60L * 1000L)

    companion object {
        private const val MIN_DISCOVERY_PRICE = 0.50
        private const val MAX_DISCOVERY_PRICE = 10_000.0
    }
}
