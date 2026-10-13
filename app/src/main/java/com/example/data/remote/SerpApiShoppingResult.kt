package com.example.data.remote

import java.net.URI
import java.util.Locale

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

    val minPrice: Double
        get() = cheapestOffer.price

    val storeCount: Int
        get() = offers.map { it.storeName.trim().lowercase(Locale.ROOT) }.distinct().size
}

/**
 * Stable grouping key shared by the result cards, Room product rows and the product detail sheet.
 * Model IDs such as Redmi 17, Redmi A5, WH-1000XM5 and Dyson G10 take precedence over marketing
 * descriptions; capacity, color and copy differences do not create duplicate cards.
 */
fun onlineProductIdentityKey(title: String): String {
    val normalized = java.text.Normalizer.normalize(
        title.lowercase(Locale.ROOT),
        java.text.Normalizer.Form.NFD
    ).replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("""compatible (con|para) .*"""), " ")
        .replace(Regex("""\\b\\d+(?:[.,]\\d+)?\\s*(?:gb|tb|mah|mp|hz|w|mm|cm|inch|pulgadas|pulgada)\\b"""), " ")
        .replace(Regex("""\\b(?:4g|5g|wifi|wi-fi|bluetooth|nfc)\\b"""), " ")
        .replace(
            Regex(
                """\\b(smartphone|telefono|telefonos|movil|moviles|mobile|phone|nuevo|new|original|oficial|libre|negro|negra|negros|black|azul|blue|blanco|blanca|white|verde|green|rojo|roja|red|gris|gray|grey|rosa|pink|morado|purple|plateado|silver)\\b"""
            ),
            " "
        )
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
        .replace(Regex("""\\s+"""), " ")

    val brand = listOf(
        "xiaomi", "samsung", "apple", "sony", "ugreen", "anker", "baseus", "google",
        "huawei", "honor", "motorola", "oppo", "oneplus", "dyson", "roborock", "jbl",
        "logitech", "nintendo", "microsoft", "lenovo", "hp", "asus", "acer", "dell",
        "philips", "rowenta", "cecotec", "ecovacs", "bosch", "lg", "tcl"
    ).firstOrNull { Regex("""\\b${Regex.escape(it)}\\b""").containsMatchIn(normalized) }.orEmpty()

    val model = Regex(
        """\\b(redmi|poco|iphone|pixel|galaxy|wh|dyson|roborock)\\s*([a-z]{0,3}\\d+[a-z0-9]*)\\b"""
    ).find(normalized)?.let { match ->
        (match.groupValues[1] + match.groupValues[2]).replace(" ", "")
    } ?: Regex("""\\b[a-z]{1,8}\\d[a-z0-9]{1,12}\\b""")
        .findAll(normalized)
        .map { it.value }
        .firstOrNull { it !in setOf("usb20", "usb30", "usb32", "wifi6", "wifi7", "typec", "4gb", "8gb", "16gb", "32gb", "64gb", "128gb", "256gb", "512gb", "1tb") }

    if (model != null) return listOf(brand, model).filter { it.isNotBlank() }.joinToString("|")

    val ignored = setOf("the", "and", "for", "with", "de", "del", "la", "las", "el", "los", "un", "una", "con", "para", "by", "from")
    val coreTokens = normalized.split(' ')
        .filter { it.isNotBlank() && it !in ignored }
        .distinct()
        .sorted()
    return (if (brand.isNotBlank()) listOf(brand) + coreTokens.filterNot { it == brand } else coreTokens)
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

