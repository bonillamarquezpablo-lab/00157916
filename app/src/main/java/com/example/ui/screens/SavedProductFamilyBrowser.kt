package com.example.ui.screens

import com.example.data.model.Product
import com.example.data.remote.onlineProductIdentityKey
import com.example.data.model.StoreOffer
import com.example.ui.viewmodel.SortOption
import java.net.URI
import java.text.Normalizer
import java.util.Locale

internal data class SavedStorageGroup(
    val label: String,
    val products: List<Product>
)

internal data class SavedVariantGroup(
    val key: String,
    val label: String,
    val storages: List<SavedStorageGroup>
) {
    val products: List<Product>
        get() = storages.flatMap { it.products }
}

internal data class SavedProductFamily(
    val key: String,
    val label: String,
    val variants: List<SavedVariantGroup>
) {
    val products: List<Product>
        get() = variants.flatMap { it.products }

    val productCount: Int
        get() = products.size

    val storeCount: Int
        get() = products.flatMap { product ->
            listOf(product.currentStore.name) + product.offers.map { it.store.name }
        }.filter { it.isNotBlank() }
            .distinctBy { it.trim().lowercase(Locale.ROOT) }
            .size
}

/**
 * Retailer feeds often append SEO/marketing text after '|'. Ignore that tail for identity only
 * when the title before it already carries a concrete model/spec and the tail adds no variant.
 */
private val SAVED_IDENTITY_TAIL_DIGIT_PATTERN = Regex("""\d""")
private val SAVED_IDENTITY_TAIL_SPEC_PATTERN = Regex(
    """(?i)\b\d+(?:[.,]\d+)?\s*(?:w|gb|tb|mah|mp|hz|mm|cm|a|mbps|gbps)\b"""
)
private val SAVED_IDENTITY_TAIL_VARIANT_PATTERN = Regex(
    """(?i)\b(pro|max|plus|ultra|fe|lite|mini|fold|flip|4g|5g)\b"""
)
private val SAVED_IDENTITY_TAIL_CONDITION_PACKAGE_CONNECTIVITY_PATTERN = Regex(
    """(?i)\b(open[\s-]?box|refurbished|renewed|reacondicionado|reacondicionada|usado|usada|used|segunda\s+mano|second\s+hand|como\s+nuevo|como\s+nueva|nuevos?|nuevas?|semi[\s-]?nuevo|semi[\s-]?nueva|packs?|bundle|bundles|duo|pair|set|kit|unidades?|uds|pcs|pieces|piezas|wifi|wi-fi|bluetooth|nfc|usb|type[\s-]?c|tipo[\s-]?c|pd|qc|lte|lightning|magsafe|thunderbolt)\b"""
)
private val SAVED_CONDITION_PATTERN = Regex(
    """(?i)\b(open[\s-]?box|refurbished|renewed|reacondicionado|reacondicionada|usado|usada|used|segunda\s+mano|second\s+hand|como\s+nuevo|como\s+nueva|semi[\s-]?nuevo|semi[\s-]?nueva|nuevos?|nuevas?)\b"""
)
private val SAVED_PACK_MARKER_PATTERN = Regex(
    """(?i)\b(packs?|bundle|bundles|duo|pair|set|kit|unidades?|uds|pcs|pieces|piezas)\b"""
)
private val SAVED_PACK_COUNT_PATTERN = Regex(
    """(?i)\b(?:(\d{1,2})[\s-]*(?:x[\s-]*)?(?:packs?|unidades?|uds|pcs|piezas|pieces)|(?:packs?|bundles?|sets?)[\s-]*(\d{1,2}))\b"""
)

/**
 * Retailer feeds append SEO/marketing text after '|'. Strip it only when it is generic advertising.
 * Digits, specifications, colours, editions, condition, package, and connectivity tokens are SKU data.
 */
private fun savedIdentityTitle(product: Product): String {
    val title = product.title.trim()
    val separator = title.indexOf('|')
    if (separator <= 0) return title
    val base = title.substring(0, separator).trim()
    val tail = title.substring(separator + 1)
    val baseHasConcreteSpec = SAVED_IDENTITY_TAIL_SPEC_PATTERN.containsMatchIn(base)
    val tailHasDistinctIdentity = SAVED_IDENTITY_TAIL_DIGIT_PATTERN.containsMatchIn(tail) ||
        SAVED_IDENTITY_TAIL_SPEC_PATTERN.containsMatchIn(tail) ||
        SAVED_COLOUR_PATTERN.containsMatchIn(tail) ||
        SAVED_IDENTITY_TAIL_VARIANT_PATTERN.containsMatchIn(tail) ||
        SAVED_IDENTITY_TAIL_CONDITION_PACKAGE_CONNECTIVITY_PATTERN.containsMatchIn(tail)
    return if (base.length >= 20 && baseHasConcreteSpec && !tailHasDistinctIdentity) base else title
}

/** Keep an unrecognised Xiaomi/Redmi/Poco "Pro Ultra" listing from masquerading as a real edition. */
internal fun savedProductDisplayTitle(product: Product): String {
    val title = savedIdentityTitle(product)
    val normalized = normalizeFamilyTitle(title)
    val isXiaomiFamily = Regex("""\b(xiaomi|redmi|poco)\b""").containsMatchIn(normalized)
    return if (isXiaomiFamily && Regex("""\bpro\s+ultra\b""").containsMatchIn(normalized)) {
        title.replace(Regex("""(?i)\bpro\s+ultra\b"""), "modelo por confirmar")
    } else title
}

private fun savedIdentityParts(product: Product): List<String> {
    val title = savedIdentityTitle(product)
    val parts = onlineProductIdentityKey(title)
        .split('|')
        .filter { it.isNotBlank() }
        .toMutableList()
    SAVED_CONDITION_PATTERN.find(title)?.value?.let { condition ->
        parts += "condition:" + normalizeFamilyTitle(condition).replace(' ', '_')
    }
    if (SAVED_PACK_MARKER_PATTERN.containsMatchIn(title)) {
        val count = SAVED_PACK_COUNT_PATTERN.find(title)?.let { match ->
            match.groups[1]?.value ?: match.groups[2]?.value
        }
        parts += "pack:" + (count ?: "unspecified")
    }
    return parts
}

private fun savedFamilyKey(product: Product): String {
    val parts = savedIdentityParts(product)
    // For recognized model keys, all edition/storage/colour variants share a family.
    // Accessory identity remains part of the key so a phone and its case never merge.
    val base = parts.filterNot {
        it.startsWith("variant:") ||
            it.startsWith("memory:") ||
            it.startsWith("color:") ||
            it.startsWith("specs:") ||
            it.startsWith("condition:") ||
            it.startsWith("pack:")
    }
    return base.joinToString("|").ifBlank { normalizeFamilyTitle(product.title) }
}

private fun normalizeFamilyTitle(value: String): String =
    Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")

private val SAVED_CAPACITY_PATTERN = Regex(
    """(?i)\b(\d+(?:[.,]\d+)?)\s*(TB|GB)\b(?!\s*(?:RAM|memoria))"""
)
private val SAVED_COLOUR_PATTERN = Regex(
    """(?i)\b(negro|negra|negros|negras|black|nero|blanco|blanca|white|bianco|azul|blue|bleu|navy|celeste|verde|green|mint|amarillo|amarilla|yellow|giallo|rojo|roja|red|rosso|rosa|pink|rose|morado|morada|purple|violet|violeta|gris|gray|grey|silver|plata|plateado|graphite|titanio|titanium|naranja|orange|dorado|dorada|gold|oro|marron|brown|cafe|beige|arena|sand|lavender|cream|starlight|ultramarine|teal)\b"""
)
private val SAVED_RAM_STORAGE_PATTERN = Regex(
    """(?i)\b(\d{1,2})\s*GB\s*(?:[/+]\s*|\s+)(\d{2,4})\s*GB\b"""
)
private val SAVED_VARIANT_PATTERN = Regex(
    """(?i)\b(pro\s*max|max|plus|pro|ultra|fe|lite|mini|fold|flip|classic|edge|slim|fat|standard|estandar|digital|disc|disk|oled|series|edition|original|4g|5g)\b"""
)

private fun isRealRetailerDestination(rawUrl: String): Boolean {
    val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
    val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return false
    if (!uri.scheme.equals("https", true)) return false
    val excluded = setOf("google.com", "google.es", "bing.com", "web.archive.org", "archive.org", "arquivo.pt")
    return host !in excluded &&
        !host.endsWith(".google.com") &&
        !host.endsWith(".google.es") &&
        !host.endsWith(".bing.com")
}

/**
 * Historical imports can leave duplicate Room rows for the same exact SKU because the product
 * was first seen at one shop and later at another. Merge those rows only for display. The DB
 * entries remain untouched; their validated price points and retailer offers are combined into
 * one product card, while distinct edition/capacity/colour keys remain separate.
 */
private fun mergeExactVariantProducts(rows: List<Product>): Product {
    if (rows.size == 1) return rows.single()

    val validPriceRows = rows.filter { it.isPriceVerified && it.currentPrice.isFinite() && it.currentPrice > 0.0 }
    val chosenCurrent = validPriceRows.filter { it.isPriceFresh }
        .minByOrNull { it.currentPrice }
        ?: validPriceRows.maxByOrNull { it.priceObservedAt ?: 0L }
    val representative = chosenCurrent ?: rows.maxByOrNull {
        it.savedHistoricalPriceObservedAt ?: it.priceObservedAt ?: 0L
    } ?: rows.first()
    val displayTitle = rows.maxWithOrNull(
        compareBy<Product> {
            val parts = savedIdentityParts(it)
            (if (parts.any { part -> part.startsWith("memory:") }) 4 else 0) +
                (if (parts.any { part -> part.startsWith("color:") }) 2 else 0) +
                (if (parts.any { part -> part.startsWith("variant:") }) 1 else 0)
        }.thenBy { savedIdentityTitle(it).length }
    )?.let(::savedProductDisplayTitle) ?: savedProductDisplayTitle(representative)

    val synthesizedCurrentOffers = validPriceRows.filter {
        it.isPriceFresh && isRealRetailerDestination(it.storeUrl)
    }.map { row ->
        StoreOffer(
            store = row.currentStore,
            price = row.currentPrice,
            inStock = false,
            shippingInfo = "Consultar envío",
            url = row.storeUrl,
            linkType = "directo"
        )
    }
    val offers = (rows.flatMap { it.offers } + synthesizedCurrentOffers)
        .filter { it.price.isFinite() && it.price > 0.0 }
        .distinctBy {
            listOf(
                it.store.id.trim().lowercase(Locale.ROOT),
                it.price.toString(),
                it.url.trim().lowercase(Locale.ROOT)
            ).joinToString("|")
        }
        .sortedBy { it.price }

    val history = rows.flatMap { it.priceHistory }
        .distinctBy {
            listOf(
                it.timestamp.toString(),
                it.price.toString(),
                it.storeName.trim().lowercase(Locale.ROOT),
                it.evidenceType.lowercase(Locale.ROOT),
                it.sourceUrl.orEmpty().trim().lowercase(Locale.ROOT)
            ).joinToString("|")
        }
        .sortedBy { it.timestamp }

    val lastSavedArchive = rows.filter {
        it.savedHistoricalPrice?.let { price -> price.isFinite() && price > 0.0 } == true &&
            it.savedHistoricalPriceObservedAt?.let { timestamp -> timestamp > 0L } == true
    }.maxByOrNull { it.savedHistoricalPriceObservedAt ?: 0L }

    return representative.copy(
        title = displayTitle,
        offers = offers,
        priceHistory = history,
        isTracked = rows.any { it.isTracked },
        alertTargetPrice = rows.firstOrNull { it.isTracked && it.alertTargetPrice != null }?.alertTargetPrice
            ?: representative.alertTargetPrice,
        savedHistoricalPrice = if (representative.isPriceVerified) {
            representative.savedHistoricalPrice
        } else {
            lastSavedArchive?.savedHistoricalPrice ?: representative.savedHistoricalPrice
        },
        savedHistoricalPriceObservedAt = if (representative.isPriceVerified) {
            representative.savedHistoricalPriceObservedAt
        } else {
            lastSavedArchive?.savedHistoricalPriceObservedAt ?: representative.savedHistoricalPriceObservedAt
        },
        savedHistoricalPriceSource = if (representative.isPriceVerified) {
            representative.savedHistoricalPriceSource
        } else {
            lastSavedArchive?.savedHistoricalPriceSource ?: representative.savedHistoricalPriceSource
        }
    )
}

private fun capacityLabel(title: String, identityParts: List<String>): String {
    val ramStorage = SAVED_RAM_STORAGE_PATTERN.find(title)
    if (ramStorage != null) {
        val ram = ramStorage.groupValues[1].toIntOrNull() ?: return "Capacidad no indicada"
        val storage = ramStorage.groupValues[2].toIntOrNull() ?: return "Capacidad no indicada"
        return "$ram GB RAM · $storage GB"
    }
    val matches = SAVED_CAPACITY_PATTERN.findAll(title).toList()
    val match = matches.lastOrNull()
    if (match != null) {
        val amount = match.groupValues[1].replace(',', '.').removeSuffix(".0")
        return amount + " " + match.groupValues[2].uppercase(Locale.ROOT)
    }
    val memoryToken = identityParts.firstOrNull { it.startsWith("memory:") }
        ?.substringAfter(':')
        ?.uppercase(Locale.ROOT)
    return memoryToken ?: "Capacidad no indicada"
}

private fun capacitySortValue(label: String): Double {
    if (label == "Capacidad no indicada") return Double.POSITIVE_INFINITY
    val found = Regex("""(?i)(\d+(?:[.,]\d+)?)\s*(TB|GB)""")
        .findAll(label).lastOrNull()
        ?: return Double.POSITIVE_INFINITY
    val amount = found.groupValues[1].replace(',', '.').toDoubleOrNull()
        ?: return Double.POSITIVE_INFINITY
    return if (found.groupValues[2].equals("TB", true)) amount * 1024.0 else amount
}

private fun variantKey(product: Product): String {
    val savedTokens = savedIdentityParts(product)
        .firstOrNull { it.startsWith("variant:") }
        ?.removePrefix("variant:")
        ?.split('+')
        .orEmpty()
        .filter { it.isNotBlank() && it != "base" }
    val title = normalizeFamilyTitle(savedIdentityTitle(product)).replace(
        Regex("""\b(redmi note\s*\d+)(pro\s*max|pro|plus|ultra|fe|lite|mini|4g|5g)\b"""),
        "$1 $2"
    )
    val isMobile = Regex(
        """\b(redmi|poco|iphone|pixel|galaxy|smartphone|movil|moviles|telefono|telefonos)\b"""
    ).containsMatchIn(title)
    val titleTokens = Regex(
        """\b(pro|max|plus|ultra|fe|lite|mini|fold|flip|classic|edge|slim|digital|disc|disk|oled|series|standard|estandar|original|4g|5g)\b"""
    ).findAll(title).map { it.value }.filter { isMobile || (it != "4g" && it != "5g") }.toList()
    val consoleTokens = if (Regex("""\b(ps5|xbox|nintendo switch|switch)\b""").containsMatchIn(title)) {
        Regex("""\b(s|x)\b""").findAll(title).map { it.value }.toList()
    } else emptyList()
    val isUnrecognisedXiaomiProUltra =
        Regex("""\b(xiaomi|redmi|poco)\b""").containsMatchIn(title) &&
            Regex("""\bpro\s+ultra\b""").containsMatchIn(title)
    if (isUnrecognisedXiaomiProUltra) {
        val network = (savedTokens + titleTokens).firstOrNull {
            it.equals("4g", ignoreCase = true) || it.equals("5g", ignoreCase = true)
        }?.lowercase(Locale.ROOT)
        return if (network == null) "variant:unverified-model"
        else "variant:unverified-model+$network"
    }
    val tokens = (savedTokens + titleTokens + consoleTokens).distinct().sorted()
    return if (tokens.isEmpty()) "variant:base" else "variant:" + tokens.joinToString("+")
}

private fun variantLabel(key: String): String {
    val raw = key.removePrefix("variant:")
    if (raw == "base" || raw.isBlank()) return "Estándar"
    val tokens = raw.split('+').distinct()
    // onlineProductIdentityKey sorts tokens for a stable identity. Render edition in natural
    // product-name order (for example, "Pro Max") and network generation at the end.
    val editionOrder = listOf(
        "standard", "original", "fat", "estandar", "plus", "pro", "max", "ultra", "fe",
        "lite", "mini", "fold", "flip", "classic", "edge", "slim", "digital", "disc",
        "disk", "oled", "series", "s", "x"
    )
    val labels = if ("unverified-model" in tokens) {
        listOf("Modelo por confirmar")
    } else tokens
        .filterNot { it.equals("4g", true) || it.equals("5g", true) }
        .sortedWith(compareBy<String> { token ->
            editionOrder.indexOf(token.lowercase(Locale.ROOT)).let { if (it < 0) Int.MAX_VALUE else it }
        }.thenBy { it })
        .map { token ->
        when (token.lowercase(Locale.ROOT)) {
            "standard", "original", "fat", "estandar" -> "Estándar"
            "plus" -> "Plus"
            "pro" -> "Pro"
            "max" -> "Max"
            "ultra" -> "Ultra"
            "fe" -> "FE"
            "lite" -> "Lite"
            "mini" -> "Mini"
            "fold" -> "Fold"
            "flip" -> "Flip"
            "edge" -> "Edge"
            "slim" -> "Slim"
            "digital" -> "Digital"
            "disc", "disk" -> "Disco"
            "oled" -> "OLED"
            "series" -> "Series"
            "s" -> "S"
            "x" -> "X"
            "classic" -> "Classic"
            else -> token.replaceFirstChar { it.uppercase(Locale.ROOT) }
        }
    }
    val network = tokens.filter { it.equals("4g", true) || it.equals("5g", true) }
        .map { it.uppercase(Locale.ROOT) }
    return (labels + network).joinToString(" ")
}

private fun familyLabel(products: List<Product>): String =
    products.map { product ->
        savedIdentityTitle(product).substringBefore('|').trim().replace(
            Regex("""(?i)(\bredmi\s+note\s*\d+)(pro\s*max|pro|plus|ultra|fe|lite|mini|4g|5g)\b"""),
            "$1 $2"
        )
            .replace(SAVED_CAPACITY_PATTERN, " ")
            .replace(SAVED_COLOUR_PATTERN, " ")
            .replace(SAVED_VARIANT_PATTERN, " ")
            .replace(SAVED_CONDITION_PATTERN, " ")
            .replace(SAVED_PACK_COUNT_PATTERN, " ")
            .replace(SAVED_PACK_MARKER_PATTERN, " ")
            .replace(Regex("""(?i)\b(ram|rom|memoria|storage|almacenamiento)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '/', '|', ',')
    }.filter { it.isNotBlank() }
        .minWithOrNull(compareBy<String> { it.length }.thenBy { it.lowercase(Locale.ROOT) })
        .orEmpty()
        .ifBlank { products.first().brand.ifBlank { products.first().title } }

/**
 * Only quotes observed in the current freshness window can drive deal/price ordering.
 * Archived values remain visible as saved history, but must not make a stale card jump ahead
 * of products with genuinely current prices.
 */
private fun hasValidSortablePrice(product: Product): Boolean =
    product.isPriceFresh && product.currentPrice.isFinite() && product.currentPrice > 0.0

private fun savedProductComparator(sortOption: SortOption): Comparator<Product> = when (sortOption) {
    SortOption.BEST_ATH_DISCOUNT -> compareByDescending<Product> { hasValidSortablePrice(it) }
        .thenByDescending { if (hasValidSortablePrice(it)) it.realSavingsPercent else Int.MIN_VALUE }
        .thenBy { it.title.lowercase(Locale.ROOT) }
        .thenBy { it.id }
    SortOption.PRICE_LOW_HIGH -> compareByDescending<Product> { hasValidSortablePrice(it) }
        .thenBy { if (hasValidSortablePrice(it)) it.currentPrice else Double.POSITIVE_INFINITY }
        .thenBy { it.title.lowercase(Locale.ROOT) }
        .thenBy { it.id }
    SortOption.PRICE_HIGH_LOW -> compareByDescending<Product> { hasValidSortablePrice(it) }
        .thenByDescending { if (hasValidSortablePrice(it)) it.currentPrice else Double.NEGATIVE_INFINITY }
        .thenBy { it.title.lowercase(Locale.ROOT) }
        .thenBy { it.id }
    SortOption.NAME_AZ -> compareBy<Product> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }
    SortOption.NAME_ZA -> compareByDescending<Product> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }
    SortOption.CATEGORY_AZ -> compareBy<Product> { it.category.displayName.lowercase(Locale.ROOT) }
        .thenBy { it.title.lowercase(Locale.ROOT) }
        .thenBy { it.id }
    SortOption.RATING -> compareByDescending<Product> { it.isRatingVerified }
        .thenByDescending { if (it.isRatingVerified) it.rating else Double.NEGATIVE_INFINITY }
        .thenBy { it.title.lowercase(Locale.ROOT) }
        .thenBy { it.id }
}

private fun savedStorageComparator(sortOption: SortOption): Comparator<SavedStorageGroup> {
    fun prices(group: SavedStorageGroup) =
        group.products.filter(::hasValidSortablePrice).map { it.currentPrice }

    return when (sortOption) {
        SortOption.PRICE_LOW_HIGH -> compareByDescending<SavedStorageGroup> {
            prices(it).isNotEmpty()
        }.thenBy {
            prices(it).minOrNull() ?: Double.POSITIVE_INFINITY
        }.thenBy { capacitySortValue(it.label) }
            .thenBy { it.label.lowercase(Locale.ROOT) }

        SortOption.PRICE_HIGH_LOW -> compareByDescending<SavedStorageGroup> {
            prices(it).isNotEmpty()
        }.thenByDescending {
            prices(it).maxOrNull() ?: Double.NEGATIVE_INFINITY
        }.thenBy { capacitySortValue(it.label) }
            .thenBy { it.label.lowercase(Locale.ROOT) }

        else -> compareBy<SavedStorageGroup> { capacitySortValue(it.label) }
            .thenBy { it.label.lowercase(Locale.ROOT) }
    }
}

private fun savedVariantComparator(sortOption: SortOption): Comparator<SavedVariantGroup> {
    fun validProducts(group: SavedVariantGroup) =
        group.products.filter(::hasValidSortablePrice)

    return when (sortOption) {
        SortOption.PRICE_LOW_HIGH -> compareByDescending<SavedVariantGroup> {
            validProducts(it).isNotEmpty()
        }.thenBy {
            validProducts(it).minOfOrNull { product -> product.currentPrice }
                ?: Double.POSITIVE_INFINITY
        }.thenBy { it.label.lowercase(Locale.ROOT) }

        SortOption.PRICE_HIGH_LOW -> compareByDescending<SavedVariantGroup> {
            validProducts(it).isNotEmpty()
        }.thenByDescending {
            validProducts(it).maxOfOrNull { product -> product.currentPrice }
                ?: Double.NEGATIVE_INFINITY
        }.thenBy { it.label.lowercase(Locale.ROOT) }

        else -> compareBy<SavedVariantGroup> {
            if (it.key == "variant:base" || it.label == "Estándar") 0 else 1
        }.thenBy { it.label.lowercase(Locale.ROOT) }
    }
}

private fun savedFamilyComparator(sortOption: SortOption): Comparator<SavedProductFamily> {
    fun verifiedPrices(family: SavedProductFamily) =
        family.products.filter(::hasValidSortablePrice).map { it.currentPrice }
    fun verifiedRows(family: SavedProductFamily) =
        family.products.filter(::hasValidSortablePrice)

    return when (sortOption) {
        SortOption.BEST_ATH_DISCOUNT -> compareByDescending<SavedProductFamily> {
            verifiedRows(it).isNotEmpty()
        }.thenByDescending {
            verifiedRows(it).maxOfOrNull { product -> product.realSavingsPercent } ?: Int.MIN_VALUE
        }.thenBy { it.label.lowercase(Locale.ROOT) }.thenBy { it.key }
        SortOption.PRICE_LOW_HIGH -> compareByDescending<SavedProductFamily> {
            verifiedPrices(it).isNotEmpty()
        }.thenBy {
            verifiedPrices(it).minOrNull() ?: Double.POSITIVE_INFINITY
        }.thenBy { it.label.lowercase(Locale.ROOT) }.thenBy { it.key }
        SortOption.PRICE_HIGH_LOW -> compareByDescending<SavedProductFamily> {
            verifiedPrices(it).isNotEmpty()
        }.thenByDescending {
            verifiedPrices(it).maxOrNull() ?: Double.NEGATIVE_INFINITY
        }.thenBy { it.label.lowercase(Locale.ROOT) }.thenBy { it.key }
        SortOption.NAME_AZ -> compareBy<SavedProductFamily> { it.label.lowercase(Locale.ROOT) }.thenBy { it.key }
        SortOption.NAME_ZA -> compareByDescending<SavedProductFamily> { it.label.lowercase(Locale.ROOT) }.thenBy { it.key }
        SortOption.CATEGORY_AZ -> compareBy<SavedProductFamily> {
            it.products.firstOrNull()?.category?.displayName.orEmpty().lowercase(Locale.ROOT)
        }.thenBy { it.label.lowercase(Locale.ROOT) }.thenBy { it.key }
        SortOption.RATING -> compareByDescending<SavedProductFamily> {
            it.products.any { product -> product.isRatingVerified && product.rating.isFinite() }
        }.thenByDescending {
            it.products.filter { product -> product.isRatingVerified && product.rating.isFinite() }
                .maxOfOrNull { product -> product.rating } ?: Double.NEGATIVE_INFINITY
        }.thenBy { it.label.lowercase(Locale.ROOT) }.thenBy { it.key }
    }
}

/** Families -> model edition -> storage -> separately identified colour/SKU product rows. */
internal fun buildSavedProductFamilyBuckets(
    products: List<Product>,
    sortOption: SortOption = SortOption.BEST_ATH_DISCOUNT
): List<SavedProductFamily> =
    products.groupBy(::savedFamilyKey)
        .map { (familyKey, familyProducts) ->
            val variants = familyProducts.groupBy(::variantKey)
                .map { (key, variantProducts) ->
                    val storageGroups = variantProducts.groupBy { product ->
                        capacityLabel(savedIdentityTitle(product), savedIdentityParts(product))
                    }.map { (label, rows) ->
                        val exactVariantRows = rows.groupBy { savedIdentityParts(it).joinToString("|") }
                            .values
                            .map(::mergeExactVariantProducts)
                            .sortedWith(savedProductComparator(sortOption))
                        SavedStorageGroup(
                            label = label,
                            products = exactVariantRows
                        )
                    }.sortedWith(savedStorageComparator(sortOption))
                    SavedVariantGroup(key, variantLabel(key), storageGroups)
                }.sortedWith(savedVariantComparator(sortOption))
            SavedProductFamily(
                key = familyKey,
                label = familyLabel(familyProducts),
                variants = variants
            )
        }
        .sortedWith(savedFamilyComparator(sortOption))
