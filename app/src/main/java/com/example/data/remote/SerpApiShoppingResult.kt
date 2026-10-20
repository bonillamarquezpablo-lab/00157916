package com.example.data.remote

import android.text.Html
import com.example.data.model.Store
import java.net.URI
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale
import android.util.Base64

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

private fun explicitOnlineModelCodes(value: String): Set<String> {
    val normalized = normalizeOnlineSearchText(value)
    val codes = ONLINE_MODEL_CODE.findAll(normalized)
        .map { it.value }
        .filterNot { it in NON_MODEL_CODES }
        .toMutableSet()
    val tokens = normalized.split(' ').filter { it.isNotBlank() }
    // Consumers often type model families with a space, e.g. "Redmi 17" or "WH 1000XM5".
    val modelFamilies = setOf("redmi", "poco", "iphone", "pixel", "galaxy", "wh", "dyson", "roborock")
    for (index in tokens.indices) {
        val family = tokens[index]
        val next = tokens.getOrNull(index + 1) ?: continue
        if (family in modelFamilies && Regex("""^\d+[a-z0-9]*$""").matches(next)) {
            codes += family + next
        }
    }
    return codes
}

private fun onlineProductTypes(value: String): Set<String> {
    val normalized = normalizeOnlineSearchText(value)
    return ONLINE_PRODUCT_TYPES
        .filter { (_, pattern) -> pattern.containsMatchIn(normalized) }
        .map { it.first }
        .toSet()
}

/**
 * Rejects bundles that mention the requested model alongside a different product.
 * An explicit pack/combo query remains eligible; a model-only query does not.
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

    // Accessory kits such as vacuum mop kits are separate products, not unrelated bundles.
    val isKnownAccessory = ACCESSORY_LISTING_PATTERN.containsMatchIn(title)
    if (hasBundleTerm && !isKnownAccessory && (queryModels.isNotEmpty() || queryTypes.isNotEmpty())) return true

    val extraModelCode = queryModels.isNotEmpty() && titleModels.any { it !in queryModels }
    val extraProductType = titleTypes.size >= 2 &&
        (queryTypes.isEmpty() || titleTypes.any { it !in queryTypes })
    // Inspect the original title: normalization strips '+', so using the normalized title
    // silently missed mixed-product listings joined by plus signs.
    val hasMultiItemSeparator = Regex(
        """(?:\s+\+\s+|\b(with|and|incluye|incluido|acompañado de)\b)""",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(resultTitle)

    return (extraModelCode && extraProductType) ||
        (queryModels.isNotEmpty() && hasMultiItemSeparator && extraProductType)
}

/** Rejected/hidden offers are transient diagnostics and are never persisted to Room. */
data class RejectedOnlineOffer(
    val title: String,
    val priceLabel: String,
    val reason: String,
    val provider: String
)

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
    val primeEligible: Boolean = false,
    /** Provider condition, retained so the UI can reveal used/refurbished offers on request. */
    val condition: String = "new",
    /** True for offers far below the result-set median; visible but excluded from best-price/history calculations. */
    val isSuspiciousPrice: Boolean = false,
    /** Google Shopping catalog identity used to fetch the full list of retailer offers. */
    val googleProductId: String? = null,
    /** A wheel result whose title omits one or more requested fitment dimensions. */
    val isFitmentUnverified: Boolean = false,
    /** The title may match the requested model, but does not clearly identify a wheel. */
    val isCategoryUnverified: Boolean = false,
    /** Original offer provenance when sourceUrl has been unwrapped to the actual retailer page. */
    val sourceProvider: String? = null
) {
    /**
     * True when the offer originated from Google Shopping, even if its embedded seller URL has
     * been safely unwrapped for direct price-history lookup.
     */
    val isGoogleShoppingProductLink: Boolean
        get() = sourceProvider.equals("Google Shopping", ignoreCase = true) || isGoogleShoppingUrl(sourceUrl)

    val isSecondHand: Boolean
        get() {
            val listingText = "$title $storeName $sourceUrl $condition"
            return isSecondHandCondition(condition) ||
                SECOND_HAND_LISTING_PATTERN.containsMatchIn(listingText) ||
                SECOND_HAND_CONDITION_PATTERN.containsMatchIn(title)
        }

    /** True for accessory listings that belong at the end of a main-product search. */
    val isAccessoryOffer: Boolean
        get() = !isBicycleTireListing(title) && ACCESSORY_LISTING_PATTERN.containsMatchIn(title)

    val isRentalOrMonthly: Boolean
        get() = Regex(
            """(?i)(?:\b(alquiler|rental|renting|rent|leasing|lease|hire|financiacion|financiar|financing|subscription|suscripcion|monthly|mensual|cuota|pago\s+a\s+plazos|por\s+mes|per\s+month|mes\s+a\s+mes)\b|/\s*mes\b)"""
        ).containsMatchIn(title) || Regex(
            """(?i)(?:\b(alquiler|rental|renting|rent|leasing|lease|hire|financiacion|financiar|financing|subscription|suscripcion|monthly|mensual|cuota|pago\s+a\s+plazos|por\s+mes|per\s+month|mes\s+a\s+mes)\b|/\s*mes\b)"""
        ).containsMatchIn(condition)

    /** Stored source label, separate from the unwrapped destination used by the history motor. */
    val linkType: String
        get() = sourceProvider?.takeIf { it.isNotBlank() } ?: linkTypeForUrl(sourceUrl)

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

    /** Number of individual wheels represented by this offer, if it is explicitly a set. */
    val wheelSetCount: Int?
        get() = wheelSetCountForTitle(title)
}

/**
 * Extracts Google's catalog product ID from SerpApi's product_id or product_link conventions.
 * A numeric catalog ID is required by SerpApi's Google Product API.
 */
fun extractGoogleShoppingProductId(value: String): String? {
    if (value.isBlank()) return null
    val decoded = value
        .replace("%3A", ":", ignoreCase = true)
        .replace("%2C", ",", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
    return Regex("""(?:/shopping/product/|catalogid\s*[:=]\s*)([0-9]{5,})""", RegexOption.IGNORE_CASE)
        .find(decoded)
        ?.groupValues
        ?.getOrNull(1)
}

/**
 * Normalizes size notation typed on Android keyboards (straight/typographic quotes and escaped
 * inch marks) so fitment-aware parsing and result sorting agree on whether this is a wheel search.
 */
fun normalizeWheelFitmentQuery(query: String): String = query
    .replace(Regex("""\\(?=["”″])"""), "")
    .replace('″', '"')
    .replace('”', '"')
    .replace('“', '"')

/**
 * An explicit diameter plus PCD is a wheel-fitment query even when the user searches by model
 * ("RIAL Milano 14\" 5x100") rather than starting with the word "llanta".
 */
fun isWheelFitmentQuery(query: String): Boolean {
    val normalized = normalizeWheelFitmentQuery(query)
    val hasPcd = Regex(
        """(?i)\b[3456]\s*[x×]\s*(100|108|110|112|114(?:[.,]3)?|115|120|127|130|139(?:[.,]7)?)(?=\b|(?:et)?[0-9]{2,3}\b)"""
    ).containsMatchIn(normalized)
    if (!hasPcd) return false
    val hasDiameterWithUnit = Regex(
        """(?i)\b(?:R\s*(1[0-9]|2[0-4])|(1[0-9]|2[0-4])\s*(?:["”″]|pulg(?:adas?)?|inch(?:es)?))"""
    ).containsMatchIn(normalized)
    // Many Spanish searches omit the inch symbol when the PCD is explicit, e.g. "RIAL Milano 14 5x100".
    val hasBareDiameter = Regex("""\b(1[0-9]|2[0-4])\b""").containsMatchIn(normalized)
    return hasDiameterWithUnit || hasBareDiameter
}

/** Identifies the link destination without requiring a direct retailer URL or HTTPS. */
fun isGoogleShoppingUrl(sourceUrl: String): Boolean {
    val uri = runCatching { URI(sourceUrl) }.getOrNull() ?: return false
    val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.").orEmpty()
    // Match Google's actual domains and subdomains, not lookalike hosts such as
    // google.com.attacker.example, which otherwise pass a startsWith("google.") check.
    return Regex("(?i)^(?:[a-z0-9-]+\\.)*google\\.(?:com|es|[a-z]{2,3}(?:\\.[a-z]{2,3})?)$")
        .matches(host)
}

/** True only when the provider identifies a GAME Spain offer by seller name or retailer host. */
internal fun isGameSpainOffer(offer: SerpApiShoppingResult): Boolean =
    Store.findKnownByName(offer.storeName, offer.sourceUrl)?.id == Store.GAME.id

/**
 * Supplement the common search with GAME only for gaming-related queries that did not already
 * return an identified GAME offer. Unrelated searches do not consume an extra query.
 */
internal fun shouldSearchGameSupplement(
    query: String,
    existingOffers: List<SerpApiShoppingResult>
): Boolean {
    if (existingOffers.any(::isGameSpainOffer)) return false
    val normalized = Normalizer.normalize(query.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .split(" ")
        .filter { it.isNotBlank() }
        .toSet()
    val gamingIntent = setOf(
        "ps5", "playstation", "xbox", "nintendo", "switch", "consola", "consolas",
        "console", "consoles", "videojuego", "videojuegos", "juego", "juegos", "gaming",
        "gamepad", "mando", "mandos", "controller", "controllers", "steam", "deck", "rog", "ally"
    )
    return normalized.any { it in gamingIntent }
}

/** Merge only positively identified GAME offers from the supplemental Shopping query. */
internal fun mergeGoogleShoppingWithGameSupplement(
    primaryOffers: List<SerpApiShoppingResult>,
    supplementalOffers: List<SerpApiShoppingResult>
): List<SerpApiShoppingResult> {
    val combined = primaryOffers + supplementalOffers.filter(::isGameSpainOffer)
    return combined.distinctBy { offer ->
        val normalizedTitle = Normalizer.normalize(offer.title.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex(" +"), " ")
        val storeKey = Store.findKnownByName(offer.storeName, offer.sourceUrl)?.id
            ?: offer.storeName.trim().lowercase(Locale.ROOT)
        normalizedTitle + "|" + storeKey + "|" + String.format(Locale.ROOT, "%.2f", offer.price)
    }
}

/**
 * Return a retailer destination if a provider URL is already direct or if a Google redirect
 * embeds the actual destination. Never treats a generic Google Shopping/search page as a seller URL.
 */
private val REDIRECT_QUERY_PRIORITY = listOf(
    "url", "adurl", "url1", "url2", "dest", "destination", "target",
    "redirect", "redirect_url", "redirect_uri", "redirected", "final_url", "merchant_url",
    "merchant", "merchanturl", "url_source", "dest_url", "destination_url", "clickurl",
    "affiliate_url", "encoded_url", "uddg", "q", "u", "r", "link", "href", "to", "out",
    "next", "continue", "rut"
)
private val REDIRECT_QUERY_KEYS = REDIRECT_QUERY_PRIORITY.toSet()

private val REDIRECT_HOST_SUFFIXES = setOf(
    "google.com", "google.es", "googleadservices.com", "doubleclick.net", "bing.com",
    "duckduckgo.com", "search.yahoo.com", "t.co", "bit.ly", "tinyurl.com", "lnkd.in",
    "l.facebook.com", "l.instagram.com", "safelinks.protection.outlook.com"
)

internal fun isIntermediaryRedirectHost(rawHost: String): Boolean {
    val host = rawHost.lowercase(Locale.ROOT).trimEnd('.').removePrefix("www.")
    return REDIRECT_HOST_SUFFIXES.any { host == it || host.endsWith("." + it) } ||
        isGoogleShoppingUrl("https://" + host + "/")
}

internal fun isNonRetailerArchiveOrSearchHost(rawHost: String): Boolean {
    val host = rawHost.lowercase(Locale.ROOT).trimEnd('.').removePrefix("www.")
    return host == "web.archive.org" || host == "archive.org" ||
        host == "google.com" || host.endsWith(".google.com") ||
        host == "google.es" || host.endsWith(".google.es") ||
        host == "bing.com" || host.endsWith(".bing.com")
}

private fun cleanRedirectUrlText(raw: String): String =
    Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        .trim()
        .trim('"', '\'', '<', '>')

private fun decodeRedirectParameter(raw: String): String {
    var value = cleanRedirectUrlText(raw)
    repeat(4) {
        val candidate = when {
            value.startsWith("//") -> "https:" + value
            value.startsWith("www.", ignoreCase = true) -> "https://" + value
            else -> value
        }
        if (candidate.startsWith("https://", ignoreCase = true) ||
            candidate.startsWith("http://", ignoreCase = true)
        ) return candidate

        // Bing click IDs can store the destination as "u=a1<base64url>". Decode this
        // envelope, then let the same bounded loop unwrap any nested URL encoding or redirect.
        if (value.startsWith("a1") && value.length > 12) {
            val payload = value.substring(2)
            val paddedPayload = payload + "=".repeat((4 - payload.length % 4) % 4)
            val base64Decoded = runCatching {
                String(
                    Base64.decode(paddedPayload, Base64.URL_SAFE or Base64.NO_WRAP),
                    Charsets.UTF_8
                )
            }.getOrNull()?.let(::cleanRedirectUrlText)
            if (base64Decoded?.startsWith("https://", ignoreCase = true) == true ||
                base64Decoded?.startsWith("http://", ignoreCase = true) == true ||
                base64Decoded?.startsWith("//") == true
            ) {
                value = base64Decoded
                return@repeat
            }
        }
        val decoded = runCatching {
            URLDecoder.decode(value, Charsets.UTF_8.name())
        }.getOrDefault(value)
        val cleaned = cleanRedirectUrlText(decoded)
        if (cleaned == value) return value
        value = cleaned
    }
    return value
}

private fun isSafeResolvedRetailerUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.').orEmpty()
    scheme == "https" &&
        host.isNotBlank() &&
        uri.rawUserInfo == null &&
        !isNonRetailerArchiveOrSearchHost(host) &&
        !isIntermediaryRedirectHost(host)
}.getOrDefault(false)

/**
 * Extract a real HTTPS retailer page, following nested Google Shopping/Ads links and common
 * click-tracking wrappers. URL parameters are decoded repeatedly but never logged. Unknown short
 * links without an embedded destination are rejected rather than mistaken for the retailer.
 */
fun extractDirectRetailerUrl(sourceUrl: String): String? =
    extractDirectRetailerUrlWithDepth(sourceUrl)?.first

internal fun extractDirectRetailerUrlWithDepth(sourceUrl: String): Pair<String, Int>? {
    var current = cleanRedirectUrlText(sourceUrl)
    if (current.isBlank()) return null
    val visited = linkedSetOf<String>()
    val maxRedirectHops = 6

    repeat(maxRedirectHops) { hop ->
        current = decodeRedirectParameter(current)
        if (current.startsWith("//")) current = "https:" + current
        val uri = runCatching { URI(current) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.').orEmpty()
        if (scheme !in setOf("http", "https") || host.isBlank() || uri.rawUserInfo != null) return null

        // A direct destination is done. Never archive or return a search, Wayback, or URL-shortener
        // host as if it were the merchant's product page.
        val redirectEndpoint = Regex(
            """(?i)(?:^|/)(?:url|aclk|redirect|redir|slredirect|picassoredirect(?:\.html)?|out|outbound|away|click|link|track|go)(?:/|$)"""
        ).containsMatchIn(uri.rawPath.orEmpty())
        val intermediate = isIntermediaryRedirectHost(host) || redirectEndpoint
        if (!intermediate) {
            // Do not upgrade an HTTP link by assumption. Only an actual HTTPS destination is
            // safe to use for historical lookup; otherwise preserve the provider fallback.
            return current.takeIf { scheme == "https" && isSafeResolvedRetailerUrl(it) }
                ?.let { it to hop }
        }

        if (!visited.add(current)) return null

        val parameters = uri.rawQuery.orEmpty().split('&').mapNotNull { parameter ->
            if (parameter.isBlank()) return@mapNotNull null
            val keyValue = parameter.split('=', limit = 2)
            if (keyValue.size < 2) return@mapNotNull null
            val key = runCatching {
                URLDecoder.decode(keyValue[0].replace("+", "%2B"), Charsets.UTF_8.name())
            }.getOrDefault(keyValue[0]).lowercase(Locale.ROOT)
            // Ignore ordinary product parameters such as sku/color/variant: only known
            // redirect keys can advance the chain, so data is not mistaken for another URL.
            if (key !in REDIRECT_QUERY_KEYS) return@mapNotNull null
            key to decodeRedirectParameter(keyValue[1])
        }

        // Prefer explicitly named destination parameters before trying other URL-like values.
        val orderedValues = parameters.sortedBy { pair ->
            val index = REDIRECT_QUERY_PRIORITY.indexOf(pair.first)
            if (index < 0) Int.MAX_VALUE else index
        }.map { it.second }
        val fragmentParameters = uri.rawFragment.orEmpty()
            .substringAfter('?', uri.rawFragment.orEmpty())
            .split('&')
            .mapNotNull { parameter ->
                if (parameter.isBlank()) return@mapNotNull null
                val keyValue = parameter.split('=', limit = 2)
                if (keyValue.size < 2) return@mapNotNull null
                val key = runCatching {
                    URLDecoder.decode(keyValue[0].replace("+", "%2B"), Charsets.UTF_8.name())
                }.getOrDefault(keyValue[0]).lowercase(Locale.ROOT)
                if (key !in REDIRECT_QUERY_KEYS) return@mapNotNull null
                decodeRedirectParameter(keyValue[1])
            }
        val candidates = (orderedValues + fragmentParameters).distinct()
        val next = candidates.firstOrNull { candidate ->
            val target = runCatching { URI(candidate) }.getOrNull() ?: return@firstOrNull false
            target.scheme?.equals("https", ignoreCase = true) == true &&
                !target.host.isNullOrBlank() &&
                target.rawUserInfo == null &&
                candidate != current
        } ?: return null

        current = next
    }
    return current.takeIf(::isSafeResolvedRetailerUrl)?.let { it to maxRedirectHops }
}

fun linkTypeForUrl(sourceUrl: String): String {
    if (isGoogleShoppingUrl(sourceUrl)) return "Google Shopping"
    val uri = runCatching { URI(sourceUrl) }.getOrNull() ?: return "directo"
    val path = uri.path.orEmpty()
    val queryKeys = uri.rawQuery.orEmpty()
        .split('&')
        .map { it.substringBefore('=').lowercase(Locale.ROOT) }
    val searchPath = Regex("""(?i)(?:^|/)(?:search|searchresult|s|w)(?:/|$)""")
        .containsMatchIn(path)
    val searchQuery = queryKeys.any {
        it in setOf("q", "query", "search", "searchvalue", "text", "keyword", "ntt", "k")
    }
    return if (searchPath || searchQuery) "Búsqueda en tienda" else "directo"
}

/**
 * Resolves offer destinations consistently for interactive search and saved-price refreshes.
 * Exact seller URLs win; generic Google Shopping links may fall back to a known retailer's
 * own search page. Keep Google catalog product pages because they can contain specific offers.
 */
private fun isSecureRetailerDestination(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null
}.getOrDefault(false)

/**
 * Resolves a safe retailer destination. An insecure HTTP offer is rerouted to the known seller's
 * HTTPS search page when available; otherwise the caller can keep a secure Google Shopping link
 * instead of rendering a dead button that the app refuses to open.
 */
fun retailerDestinationForOffer(sourceUrl: String, storeName: String, productTitle: String): String? {
    extractDirectRetailerUrl(sourceUrl)?.let { direct ->
        if (!isGoogleShoppingUrl(direct) && isSecureRetailerDestination(direct)) return direct
    }
    val uri = runCatching { URI(sourceUrl) }.getOrNull()
    val isGoogle = isGoogleShoppingUrl(sourceUrl)
    val isGoogleCatalogProduct = uri?.let {
        isGoogle && it.path.orEmpty().contains("/shopping/product/")
    } == true
    if (isGoogleCatalogProduct && isSecureRetailerDestination(sourceUrl)) return sourceUrl

    val genericGoogleSearch = uri?.let {
        isGoogle && (it.path.orEmpty() == "/search" || it.path.orEmpty().startsWith("/search/"))
    } == true
    val insecureSource = uri?.scheme.equals("http", ignoreCase = true)
    val store = Store.findKnownByName(storeName, sourceUrl)
    if ((genericGoogleSearch || insecureSource) && store?.supportsProductSearch == true) {
        return store.searchUrlFor(productTitle).takeIf(::isSecureRetailerDestination)
    }
    return sourceUrl.takeIf(::isSecureRetailerDestination)
}

fun isSecondHandCondition(condition: String): Boolean = Regex(
    """(?i)\b(used|second[\s_-]*hand|pre[\s_-]*owned|usad[oa]s?|seminuev[oa]s?|refurbished|reacondicionad[oa]s?|renewed|open[\s_-]*box)\b"""
).containsMatchIn(condition)

private val SECOND_HAND_CONDITION_PATTERN = Regex(
    """(?i)\b(segunda[\s_-]+mano|second[\s_-]*hand|pre[\s_-]*owned|used|usad[oa]s?|seminuev[oa]s?|reacondicionad[oa]s?|refurbished|renewed|open[\s_-]*box)\b"""
)

private val SECOND_HAND_LISTING_PATTERN = Regex(
    """(?i)\b(wallapop|milanuncios|ebay|vinted|particular(?:es)?|amazon[\s_-]*warehouse)\b"""
)

private val BICYCLE_TIRE_LISTING_PATTERN = Regex(
    """(?i)\b(cubiertas?|neum[aá]tic[oa]s?|tyres?|tires?)\b"""
)

private val BICYCLE_TIRE_DIAMETER_PATTERN = Regex("""(?<!\d)(26|27[.,]?5|29)(?!\d)""")

/** True when a title explicitly identifies a bicycle tyre/cover, even if its text also mentions cables. */
internal fun isBicycleTireListing(title: String): Boolean =
    BICYCLE_TIRE_LISTING_PATTERN.containsMatchIn(title)

/** Requested wheel diameter, only for searches that clearly concern bicycle tyres. */
internal fun requestedBicycleTireDiameter(query: String): String? {
    val normalized = normalizedWheelCategoryText(query)
    val tireIntent = Regex("""\b(maxxis|cubiertas?|neumaticos?|tyres?|tires?)\b""")
        .containsMatchIn(normalized)
    if (!tireIntent) return null
    return BICYCLE_TIRE_DIAMETER_PATTERN.find(normalized)?.value
        ?.replace(',', '.')
        ?.let { if (it == "275") "27.5" else it }
}

private fun bicycleTireDiameters(title: String): Set<String> =
    BICYCLE_TIRE_DIAMETER_PATTERN.findAll(normalizedWheelCategoryText(title))
        .map { it.value.replace(',', '.').let { diameter -> if (diameter == "275") "27.5" else diameter } }
        .toSet()

/** Reject a listing if it does not advertise the exact tyre diameter requested. */
internal fun isWrongBicycleTireDiameter(query: String, title: String): Boolean {
    val requested = requestedBicycleTireDiameter(query) ?: return false
    if (!isBicycleTireListing(title)) return false
    return requested !in bicycleTireDiameters(title)
}

/** A multi-size title is visible for inspection, but its listed price cannot be assigned to one size. */
internal fun hasAmbiguousBicycleTireDiameter(query: String, title: String): Boolean {
    val requested = requestedBicycleTireDiameter(query) ?: return false
    if (!isBicycleTireListing(title)) return false
    val sizes = bicycleTireDiameters(title)
    return requested in sizes && sizes.any { it != requested }
}

/** A 7–12 speed mountain-bike groupset query should not return wheels and unrelated parts. */
internal fun isBicycleDrivetrainSearch(query: String): Boolean {
    val normalized = normalizedWheelCategoryText(query)
    val bicycle = Regex("""\b(mtb|bicicleta|bicicletas|bici|bike|bicycle)\b""")
        .containsMatchIn(normalized)
    val drivetrainBrand = Regex("""\b(shimano|sram|microshift|sunrace)\b""")
        .containsMatchIn(normalized)
    val speed = Regex("""\b(?:7|8|9|10|11|12)\s*(?:v|velocidades|speed)\b""")
        .containsMatchIn(normalized)
    val explicitTireSearch = requestedBicycleTireDiameter(query) != null
    return bicycle && drivetrainBrand && speed && !explicitTireSearch
}

internal fun isBicycleDrivetrainComponentTitle(title: String): Boolean {
    val normalized = normalizedWheelCategoryText(title)
    if (isBicycleTireListing(title) ||
        Regex("""\b(ruedas?|llantas?|wheel(?:s)?|rims?|cubiertas?|neumaticos?|tyres?|tires?)\b""")
            .containsMatchIn(normalized)
    ) return false
    return Regex(
        """\b(cassette|casete|pi[nñ]ones?|sprockets?|cambio|desviador|derailleur|shifter|manetas?|pulsadores?|cadena|chain|platos?|bielas?|pedalier|transmisi[oó]n|groupset|grupo)\b"""
    ).containsMatchIn(normalized)
}

private val ACCESSORY_LISTING_PATTERN = Regex(
    """(?i)\b(p[oó]ster(?:es)?|poster(?:s)?|funda(?:s)?|carcasa(?:s)?|cases?|covers?|cargador(?:es)?|chargers?|charging|mop\s+kit|protectores?|protector(?:es)?|screen\s+protector|protector\s+de\s+pantalla|cristal(?:es)?\s+templado|cables?|adaptadores?|hubs?|filtros?|recambios?|repuestos?|soportes?)\b"""
)

/** Accessory queries are intentional accessory searches and should not be re-labelled as accessories. */
fun isAccessorySearchQuery(query: String): Boolean =
    ACCESSORY_LISTING_PATTERN.containsMatchIn(query)

internal fun normalizedWheelCategoryText(value: String): String =
    Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

private val WHEEL_CATEGORY_PATTERN = Regex(
    """(?<![\p{L}\p{N}])(?:llantas?|ruedas?|aros?|wheel(?:s)?|rims?|(?:stahl|alu)?felg(?:e|en|a|i)|alufelgen?|alurader|alu|jantes?|cerchi|alloy)(?![\p{L}\p{N}])"""
)

/** Recognizes common Spanish, German, French, Italian and English wheel/rim vocabulary. */
fun containsWheelCategoryTerm(value: String): Boolean =
    WHEEL_CATEGORY_PATTERN.containsMatchIn(normalizedWheelCategoryText(value))

/** Detects explicit wheel-set counts, including leading titles such as "4 Autec ... Felgen". */
fun wheelSetCountForTitle(title: String): Int? {
    val normalized = normalizedWheelCategoryText(title)
    if (!WHEEL_CATEGORY_PATTERN.containsMatchIn(normalized)) return null
    // Retail titles commonly write four rims as both "4 Autec ..." and "4x Autec ...".
    // Require a space after the x so PCD text such as "4x100" is never mistaken for a set count.
    val leadingCount = Regex("""^\s*(\d{1,2})(?:\s*x\s+|\s+)(?=[\p{L}])""")
        .find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?.takeIf { it in 2..20 }
    if (leadingCount != null) return leadingCount
    return Regex("""\b(?:juego|set|kit|pack)\s+(?:(?:de|of)\s+)?(\d{1,2})\b""")
        .find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?.takeIf { it in 2..20 }
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
        get() = offers.filterNot { it.isSuspiciousPrice }.minByOrNull { it.price }
            ?: offers.minBy { it.price }

    /** Prefer a trusted direct retailer destination for the default purchase action. */
    val bestPurchaseOffer: SerpApiShoppingResult
        get() = offers.filterNot { it.isSuspiciousPrice || it.isGoogleShoppingProductLink }
            .minByOrNull { it.price }
            ?: offers.filterNot { it.isSuspiciousPrice }.minByOrNull { it.price }
            ?: cheapestOffer

    /** Keep the representative image aligned with the card title; fall back only within this group. */
    val representativeImageUrl: String
        get() = offers.firstOrNull {
            it.title.equals(title, ignoreCase = true) && it.thumbnailUrl.isNotBlank()
        }?.thumbnailUrl
            ?: offers.firstOrNull { it.thumbnailUrl.isNotBlank() }?.thumbnailUrl.orEmpty()

    val wheelSetCount: Int?
        get() = cheapestOffer.wheelSetCount

    /** For a set, the comparable displayed price is the per-wheel price, not the whole set. */
    val minPrice: Double
        get() {
            val offer = cheapestOffer
            val count = offer.wheelSetCount
            return if (count != null) offer.price / count else offer.price
        }

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
    }.replace(Regex("""(?i)\b(\d{1,3})[.,](\d{3})\b"""), "$1$2")

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
        .replace(Regex("""\bplaystation\s*5\b"""), "ps5")
    val productNameNormalized = rawNormalized
        .substringBefore(" compatible con ")
        .substringBefore(" compatible para ")
    val memorySpecs = Regex("""\b\d+\s?(?:gb|tb)\b""")
        .findAll(productNameNormalized)
        .map { it.value.replace(" ", "") }
        .distinct()
        .joinToString("+")
    val generalVariants = setOf("pro", "max", "plus", "ultra", "fe", "lite", "mini", "fold", "flip", "classic", "edge")
    // Console identity must retain chassis/media variants as well as storage.
    val consoleVariants = if (
        Regex("""\b(ps5|xbox|nintendo switch|switch)\b""").containsMatchIn(productNameNormalized)
    ) setOf("slim", "digital", "disc", "disk", "oled", "series", "s", "x")
      else emptySet()
    val variantTokens = (generalVariants + consoleVariants)
        .filter { Regex("""\b""" + Regex.escape(it) + """\b""").containsMatchIn(productNameNormalized) }
        .toMutableSet()
    // Fat/original/standard are aliases for the base PS5 chassis. Give that model a
    // stable identity even when a retailer omits those words from its title.
    if (Regex("""\bps5\b""").containsMatchIn(productNameNormalized) &&
        "pro" !in variantTokens && "slim" !in variantTokens
    ) variantTokens += "standard"
    val variants = variantTokens.sorted().joinToString("+")
    val accessoryType = listOf(
        "cristal templado", "screen protector", "mop kit", "poster", "funda",
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
        Regex("""\bps5\b""").containsMatchIn(normalized) -> "sony"
        Regex("""\bxbox\b""").containsMatchIn(normalized) -> "microsoft"
        Regex("""\b(nintendo switch|switch)\b""").containsMatchIn(normalized) -> "nintendo"
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

    val sonyHeadphoneModel = if (
        Regex("""\b(sony|headphones|auriculares|wh)\b""").containsMatchIn(productNameNormalized)
    ) {
        Regex("""(?i)(?:wh[\s-]*)?(\d{3,5}xm\d[a-z0-9]*)""")
            .find(title)
            ?.groupValues
            ?.get(1)
            ?.let { "wh" + it.lowercase(Locale.ROOT) }
    } else null

    val model = Regex(
        """\b(redmi|poco|iphone|pixel|galaxy|wh|dyson|roborock)\s*([a-z]{0,3}\d+[a-z0-9]*)\b"""
    ).find(normalized)?.let { match ->
        (match.groupValues[1] + match.groupValues[2]).replace(" ", "")
    } ?: sonyHeadphoneModel
      ?: Regex("""\b[a-z]{1,8}\d[a-z0-9]{1,12}\b""")
        .findAll(normalized)
        .map { it.value }
        .firstOrNull { it !in setOf("usb20", "usb30", "usb32", "wifi6", "wifi7", "typec", "4gb", "8gb", "16gb", "32gb", "64gb", "128gb", "256gb", "512gb", "1tb") }

    // Capacity matters for power banks; wattage matters for chargers and power stations.
    // Keep these specifications in the identity key so 20,000 mAh / 65 W and 100 W
    // variants never collapse into one card just because the numeric token was normalized away.
    val technicalSource = title.lowercase(Locale.ROOT)
        .replace(Regex("""(?<=\d)[.,](?=\d{3}\b)"""), "")
    val isPowerAccessory = Regex(
        """\b(cargador|charger|charging|power\s*bank|bateria\s+externa|battery\s+pack|power\s+station|estacion\s+de\s+energia|fuente\s+de\s+alimentacion|adaptador\s+de\s+corriente)\b"""
    ).containsMatchIn(productNameNormalized)
    val technicalSpecs = Regex("""(?i)\b(\d+(?:[.,]\d+)?)\s*(mah|w)\b""")
        .findAll(technicalSource)
        .filter { match -> match.groupValues[2].equals("mah", ignoreCase = true) || isPowerAccessory }
        .map { match ->
            val number = match.groupValues[1].replace(",", ".")
                .removeSuffix(".0")
            number + match.groupValues[2].lowercase(Locale.ROOT)
        }
        .distinct()
        .sorted()
        .joinToString("+")
    
    val suffix = listOf(
        accessoryType.takeIf { it.isNotBlank() }?.let { "accessory:$it" },
        variants.takeIf { it.isNotBlank() }?.let { "variant:$it" },
        memorySpecs.takeIf { it.isNotBlank() }?.let { "memory:$it" },
        technicalSpecs.takeIf { it.isNotBlank() }?.let { "specs:$it" }
    ).filterNotNull().joinToString("|")

    val wheelSetSuffix = wheelSetCountForTitle(title)?.let { "wheel-set:$it" }
    if (model != null) {
        val identity = (listOf(brand, model).filter { it.isNotBlank() } + suffix).joinToString("|")
        return wheelSetSuffix?.let { "$identity|$it" } ?: identity
    }

    val ignored = setOf("the", "and", "for", "with", "de", "del", "la", "las", "el", "los", "un", "una", "con", "para", "by", "from")
    val coreTokens = normalized.split(' ')
        .filter { it.isNotBlank() && it !in ignored }
        .distinct()
        .sorted()
    val identity = ((if (brand.isNotBlank()) listOf(brand) + coreTokens.filterNot { it == brand } else coreTokens) + suffix)
        .joinToString(" ")
        .ifBlank { normalized }
    return wheelSetSuffix?.let { "$identity|$it" } ?: identity
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

