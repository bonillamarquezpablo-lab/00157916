package com.example.data.remote

import java.net.URI
import java.text.Normalizer
import java.util.Locale

private val BUNDLE_TERMS = Regex(
    """\b(pack|paquete|combo|bundle|kit|set|conjunto|combinacion|combinado)\b"""
)
private val ONLINE_PRODUCT_TYPES = listOf(
    "keyboard" to Regex("""\b(teclado|teclados|keyboard|keyboards)\b"""),
    "mouse" to Regex("""\b(raton|ratones|mouse|mice)\b"""),
    "headphones" to Regex("""\b(auricular|auriculares|headphones?|headsets?|cascos)\b"""),
    "monitor" to Regex("""\b(monitores?|pantallas?|monitors?|display)\b"""),
    "laptop" to Regex("""\b(portatil|portatiles|laptop|notebook|chromebook)\b"""),
    "phone" to Regex("""\b(smartphone|movil|moviles|telefono|telefonos|phone)\b"""),
    "controller" to Regex("""\b(mandos?|controllers?|gamepad)\b"""),
    "webcam" to Regex("""\b(webcams?|camara web)\b"""),
    "camera" to Regex("""\b(camaras?|cameras?)\b"""),
    "microphone" to Regex("""\b(microfonos?|microphones?)\b"""),
    "speaker" to Regex("""\b(altavoces?|speakers?|soundbar|barra de sonido)\b"""),
    "console" to Regex("""\b(consolas?|playstation|xbox|nintendo switch)\b""")
)
private val ONLINE_MODEL_CODE = Regex("""\b[a-z]{1,8}\d[a-z0-9]{1,12}\b""")
private val NON_MODEL_CODES = setOf(
    "usb20", "usb30", "usb32", "wifi6", "wifi7", "typec", "nvme",
    "4gb", "8gb", "16gb", "32gb", "64gb", "128gb", "256gb", "512gb",
    "1tb", "2tb"
)

private fun normalizeOnlineSearchText(value: String): String =
    Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")

private fun explicitOnlineModelCodes(value: String): Set<String> =
    ONLINE_MODEL_CODE.findAll(normalizeOnlineSearchText(value))
        .map { it.value }
        .filterNot { it in NON_MODEL_CODES }
        .toSet()

private fun onlineProductTypes(value: String): Set<String> {
    val normalized = normalizeOnlineSearchText(value)
    return ONLINE_PRODUCT_TYPES
        .filter { (_, pattern) -> pattern.containsMatchIn(normalized) }
        .map { it.first }
        .toSet()
}

/**
 * Rejects bundles that happen to mention the requested model alongside a different product.
 * For an exact-model search, a keyboard+mouse pack is not a standalone mouse offer.
 * Users can still search explicitly for a pack/combo when that is what they want.
 */
fun isIrrelevantMultiProductBundle(searchQuery: String, resultTitle: String): Boolean {
    if (searchQuery.isBlank() || resultTitle.isBlank()) return false
    val query = normalizeOnlineSearchText(searchQuery)
    val title = normalizeOnlineSearchText(resultTitle)
    if (BUNDLE_TERMS.containsMatchIn(query)) return false

    val queryModels = explicitOnlineModelCodes(query)
    val titleModels = explicitOnlineModelCodes(title)
    val queryTypes = onlineProductTypes(query)
    val titleTypes = onlineProductTypes(title)
    val hasBundleTerm = BUNDLE_TERMS.containsMatchIn(title)

    // Bundle terminology is enough when the user named a specific model/category.
    if (hasBundleTerm && (queryModels.isNotEmpty() || queryTypes.isNotEmpty())) return true

    // Some providers omit "pack" or "combo". Multiple distinct product classes plus
    // an extra model code are strong evidence that the result combines different items.
    val extraModelCode = queryModels.isNotEmpty() && titleModels.any { it !in queryModels }
    val extraProductType = titleTypes.size >= 2 &&
        (queryTypes.isEmpty() || titleTypes.any { it !in queryTypes })
    return extraModelCode && extraProductType
}

/**
 * One result from a manual Google Shopping query.
 * Prices are point-in-time observations and must be rechecked at the seller.
 */
data class SerpApiShoppingResult(
    val title: String,
    val price: Double,
    val storeName: String,
    val sourceUrl: String,
    val thumbnailUrl: String = "",
    val delivery: String = "",
    val rating: Double? = null,
    val reviewCount: Int? = null,
    val observedAt: Long,
    /** Whether Amazon reports Prime delivery eligibility; not a personalized account price. */
    val primeEligible: Boolean = false
) {
    /**
     * True when the accepted URL stays on a Google domain rather than opening the seller directly.
     * SerpApi can return /search?prds=... links as well as /shopping/product/... links.
     */
    val isGoogleShoppingProductLink: Boolean
        get() {
            val uri = runCatching { URI(sourceUrl) }.getOrNull() ?: return false
            val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.").orEmpty()
            return host.startsWith("google.") ||
                host.endsWith(".google.com") ||
                host.endsWith(".google.es")
        }

    val isAmazonOffer: Boolean
        get() {
            val host = runCatching {
                URI(sourceUrl).host?.lowercase(Locale.ROOT)?.removePrefix("www.").orEmpty()
            }.getOrDefault("")
            return storeName.contains("amazon", ignoreCase = true) ||
                host == "amazon.es" ||
                host.startsWith("amazon.") ||
                host.contains(".amazon.")
        }
}

/**
 * One exact product/model grouping in the online results. Individual retailer listings remain
 * separate offers so the detail sheet can show their prices and Room history.
 */
data class OnlineProductGroup(
    val identityKey: String,
    val title: String,
    val offers: List<SerpApiShoppingResult>
) {
    val cheapestOffer: SerpApiShoppingResult
        get() = offers.minBy { it.price }

    /**
     * Prefer a direct seller link for the main purchase action. Google Shopping fallback
     * pages are useful for discovery, but they are not the retailer's product page.
     */
    val bestPurchaseOffer: SerpApiShoppingResult
        get() = offers.filterNot { it.isGoogleShoppingProductLink }
            .minByOrNull { it.price } ?: cheapestOffer

    val minPrice: Double
        get() = cheapestOffer.price

    /**
     * Prefer the image belonging to the selected display title. The cheapest seller can have
     * a missing or unrelated thumbnail, so it must not automatically dictate the product image.
     */
    val representativeImageUrl: String
        get() = offers.firstOrNull {
            it.title.equals(title, ignoreCase = true) && it.thumbnailUrl.isNotBlank()
        }?.thumbnailUrl
            ?: offers.firstOrNull { it.thumbnailUrl.isNotBlank() }?.thumbnailUrl.orEmpty()

    val storeCount: Int
        get() = offers.map { it.storeName.trim().lowercase(Locale.ROOT) }.distinct().size
}

/**
 * Stable grouping key shared by the result cards, Room product rows and the product detail sheet.
 * Model IDs such as Redmi 17, Redmi A5, WH-1000XM5 and Dyson G10 take precedence over marketing
 * descriptions; capacity, color and copy differences do not create duplicate cards.
 */
fun onlineProductIdentityKey(title: String): String {
    // Convert both "4GB/256GB" and "4+256GB" to the same RAM/storage spelling first.
    val standardizedTitle = Regex(
        """(?i)\b(\d{1,2})\s*(?:gb)?\s*[+/]\s*(\d{2,4})\s*gb\b"""
    ).replace(title.lowercase(Locale.ROOT)) { match ->
        match.groupValues[1] + "gb " + match.groupValues[2] + "gb"
    }

    // Remove target-gender and generic apparel words before trimming a trailing size,
    // so titles such as "Nike Air Force 1 Gris - 40.5 Hombre" normalize correctly.
    val titleForSize = standardizedTitle.replace(
        Regex("""(?i)\b(zapatillas|zapatilla|sneakers|shoes|calzado|hombre|mujer|men|man|women|woman|unisex)\b"""),
        " "
    )
    val looksLikeFootwear = Regex(
        """\b(nike|adidas|puma|asics|new balance|reebok|converse|vans|air force|air max|jordan|dunk|samba|gazelle|pegasus|zapatillas|zapatilla|calzado|sneakers|shoes|botas)\b"""
    ).containsMatchIn(titleForSize)
    val looksLikeClothing = Regex(
        """\b(camiseta|camisetas|sudadera|sudaderas|pantalon|pantalones|vaqueros|vestido|vestidos|chaqueta|chaquetas|abrigo|abrigos|ropa|camisa|camisas)\b"""
    ).containsMatchIn(titleForSize)
    val sizeNeutralTitle = when {
        looksLikeFootwear -> Regex(
            """(?i)(?:\s*[-/|]\s*|\s+)(?:talla\s*|size\s*)?(?:3[5-9]|4[0-9]|5[0-2])(?:[.,]\d)?\s*$"""
        ).replace(titleForSize, " ")
        looksLikeClothing -> Regex(
            """(?i)(?:\s*[-/|]\s*|\s+)(?:talla\s*|size\s*)?(?:3[0-9]|[4-5][0-9]|60)(?:[.,]\d)?\s*$"""
        ).replace(titleForSize, " ")
        else -> titleForSize
    }
    val rawNormalized = java.text.Normalizer.normalize(
        sizeNeutralTitle,
        java.text.Normalizer.Form.NFD
    ).replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")
    val productNameNormalized = rawNormalized
        .substringBefore(" compatible con ")
        .substringBefore(" compatible para ")
    val memorySpecs = Regex("""\b\d+\s?(?:gb|tb)\b""")
        .findAll(productNameNormalized)
        .map { it.value.replace(" ", "") }
        .distinct()
        .joinToString("+")
    val variants = setOf("pro", "max", "plus", "ultra", "fe", "lite", "mini", "fold", "flip", "classic", "edge")
        .filter { Regex("""\b""" + it + """\b""").containsMatchIn(productNameNormalized) }
        .distinct()
        .sorted()
        .joinToString("+")
    val accessoryType = listOf(
        "cristal templado", "screen protector", "mop kit", "funda",
        "carcasa", "case", "cover", "protector", "cable", "cargador", "adaptador", "hub",
        "filtro", "recambio", "repuesto", "soporte"
    ).firstOrNull { type ->
        Regex("""\b""" + Regex.escape(type) + """\b""").containsMatchIn(productNameNormalized)
    }.orEmpty()

    val normalized = rawNormalized
        .replace(Regex("""compatible (con|para) .*"""), " ")
        .replace(Regex("""\b\d+(?:[.,]\d+)?\s*(?:gb|tb|mah|mp|hz|w|mm|cm|inch|pulgadas|pulgada)\b"""), " ")
        .replace(Regex("""\b(?:4g|5g|wifi|wi-fi|bluetooth|nfc)\b"""), " ")
        .replace(
            Regex(
                """\b(smartphone|telefono|telefonos|movil|moviles|mobile|phone|nuevo|new|original|oficial|libre|zapatilla|zapatillas|calzado|shoes|sneakers|hombre|mujer|men|man|woman|women|unisex|negro|negra|negros|black|azul|blue|blanco|blanca|white|verde|green|rojo|roja|red|gris|gray|grey|rosa|pink|morado|purple|plateado|silver)\b"""
            ),
            " "
        )
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")

    val brand = when {
        Regex("""\b(xiaomi|redmi|poco)\b""").containsMatchIn(normalized) -> "xiaomi"
        Regex("""\b(samsung|galaxy)\b""").containsMatchIn(normalized) -> "samsung"
        Regex("""\b(apple|iphone)\b""").containsMatchIn(normalized) -> "apple"
        Regex("""\b(google|pixel)\b""").containsMatchIn(normalized) -> "google"
        Regex("""\b(sony|wh)\b""").containsMatchIn(normalized) -> "sony"
        else -> listOf(
            "ugreen", "anker", "baseus", "huawei", "honor", "motorola", "oppo", "oneplus",
            "dyson", "roborock", "jbl", "logitech", "nintendo", "microsoft", "lenovo", "hp",
            "asus", "acer", "dell", "philips", "rowenta", "cecotec", "ecovacs", "bosch", "lg", "tcl"
        ).firstOrNull { Regex("""\b""" + Regex.escape(it) + """\b""").containsMatchIn(normalized) }.orEmpty()
    }

    val model = Regex(
        """\b(redmi|poco|iphone|pixel|galaxy|wh|dyson|roborock)\s*([a-z]{0,3}\d+[a-z0-9]*)\b"""
    ).find(normalized)?.let { match ->
        (match.groupValues[1] + match.groupValues[2]).replace(" ", "")
    } ?: Regex("""\b[a-z]{1,8}\d[a-z0-9]{1,12}\b""")
        .findAll(normalized)
        .map { it.value }
        .firstOrNull { it !in setOf("usb20", "usb30", "usb32", "wifi6", "wifi7", "typec", "4gb", "8gb", "16gb", "32gb", "64gb", "128gb", "256gb", "512gb", "1tb") }

    val suffix = listOf(
        accessoryType.takeIf { it.isNotBlank() }?.let { "accessory:$it" },
        variants.takeIf { it.isNotBlank() }?.let { "variant:$it" },
        memorySpecs.takeIf { it.isNotBlank() }?.let { "memory:$it" }
    ).filterNotNull().joinToString("|")

    if (model != null) return (listOf(brand, model).filter { it.isNotBlank() } + suffix)
        .joinToString("|")

    val ignored = setOf("the", "and", "for", "with", "de", "del", "la", "las", "el", "los", "un", "una", "con", "para", "by", "from")
    val coreTokens = normalized.split(' ')
        .filter { it.isNotBlank() && it !in ignored }
        .distinct()
        .sorted()
    return ((if (brand.isNotBlank()) listOf(brand) + coreTokens.filterNot { it == brand } else coreTokens) + suffix)
        .joinToString(" ")
        .ifBlank { normalized }
}

/** Groups repeated seller listings into one product card, retaining all separate offers. */
fun groupOnlineShoppingResults(
    results: List<SerpApiShoppingResult>
): List<OnlineProductGroup> =
    results.groupBy { onlineProductIdentityKey(it.title) }
        .mapNotNull { (identityKey, offers) ->
            val sortedOffers = offers.distinctBy {
                "${it.storeName.trim().lowercase(Locale.ROOT)}|${it.price}|${it.sourceUrl.lowercase(Locale.ROOT)}"
            }.sortedBy { it.price }
            if (sortedOffers.isEmpty()) null
            else {
                val displayTitle = sortedOffers
                    .minWithOrNull(compareBy<SerpApiShoppingResult> {
                        displayTitlePenalty(it.title)
                    }.thenBy { it.title.length })?.title ?: sortedOffers.first().title
                OnlineProductGroup(identityKey, displayTitle, sortedOffers)
            }
        }
        .sortedBy { it.minPrice }

private fun displayTitlePenalty(title: String): Int {
    val normalized = title.lowercase(Locale.ROOT)
    var penalty = 0
    if (normalized.contains("smartphone")) penalty += 20
    if (normalized.contains("compatible con")) penalty += 30
    if (normalized.contains(" compatible para ")) penalty += 30
    if (normalized.contains("libre")) penalty += 5
    return penalty
}

