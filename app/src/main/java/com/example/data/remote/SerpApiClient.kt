package com.example.data.remote

import com.example.data.model.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.Normalizer
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

data class SerpApiRequestProduct(
    val id: String,
    val title: String,
    val brand: String,
    val ean: String = ""
) {
    val queryTerm: String
        get() = if (brand.isNotBlank() && !title.contains(brand, ignoreCase = true)) {
            "${brand.trim()} ${title.trim()}".replace(Regex("\\s+"), " ")
        } else {
            title.trim().replace(Regex("\\s+"), " ")
        }
}

data class SerpApiQuote(
    val productId: String,
    val price: Double,
    val currency: String,
    val observedAt: Long,
    val sourceUrl: String,
    val storeId: String,
    val storeName: String,
    val sourceName: String = "SerpApi · Google Shopping España",
    val googleProductId: String? = null
)

/** A historical point explicitly returned by Google's product-price insights. */
data class SerpApiPriceInsight(
    val productTitle: String,
    val storeName: String,
    val price: Double,
    val currency: String,
    val observedAt: Long
)

data class SerpApiGoogleProductDetails(
    val offers: List<SerpApiShoppingResult>,
    val priceInsights: List<SerpApiPriceInsight>,
    val priceHistoryAvailable: Boolean
)

data class SerpApiBatchResult(
    val quotes: List<SerpApiQuote>,
    val requestedQueries: Int,
    val failedQueries: Int,
    val queriesWithoutMatches: Int = 0
)

data class SerpApiShoppingSearchReport(
    val results: List<SerpApiShoppingResult>,
    val rawResultCount: Int,
    val rejectedAsIrrelevant: Int,
    val rejectedForPriceOrCurrency: Int,
    val rejectedForLink: Int,
    val rejectedOther: Int,
    val firstResultFields: String = "",
    val discardedOffers: List<RejectedOnlineOffer> = emptyList(),
    val providerName: String = "Google Shopping",
    /** Unfiltered provider response; stored locally without credentials for later reclassification. */
    val rawResponse: String? = null
) {
    fun emptyResultMessage(query: String): String {
        if (rawResultCount == 0) {
            return "$providerName no devolvió productos para «$query». Prueba con el modelo exacto o inténtalo más tarde; no se ha guardado ningún precio."
        }
        val detailedReasons = discardedOffers.groupingBy { it.reason }.eachCount()
            .entries.joinToString("; ") { "${it.value} por ${it.key.lowercase(Locale.ROOT)}" }
        val reasons = mutableListOf<String>()
        if (rejectedAsIrrelevant > 0) reasons += "$rejectedAsIrrelevant no coincidían suficientemente con la búsqueda"
        if (rejectedForPriceOrCurrency > 0) reasons += "$rejectedForPriceOrCurrency no tenían un precio en euros verificable"
        if (rejectedForLink > 0) reasons += "$rejectedForLink no tenían un enlace utilizable"
        if (rejectedOther > 0) reasons += "$rejectedOther tenían condición no válida o datos incompletos"
        val detail = detailedReasons.ifBlank {
            reasons.joinToString("; ").ifBlank { "ninguno pasó los filtros de seguridad" }
        }
        val fieldDiagnostic = if (
            results.isEmpty() &&
            rejectedForLink > 0 &&
            rejectedForLink == rawResultCount &&
            firstResultFields.isNotBlank()
        ) " Campos recibidos: $firstResultFields." else ""
        return "$providerName devolvió $rawResultCount resultados para «$query», pero ninguno era utilizable: $detail.$fieldDiagnostic No se ha guardado ningún precio."
    }
}

data class SerpApiAccountInfo(
    val planName: String,
    val accountStatus: String,
    val monthlyLimit: Int,
    val monthlySearchesUsed: Int,
    val searchesRemaining: Int,
    val hourlyLimit: Int,
    val searchesThisHour: Int,
    val renewalDate: String?
) {
    val remainingThisHour: Int?
        get() = hourlyLimit.takeIf { it > 0 }?.let { (it - searchesThisHour).coerceAtLeast(0) }

    fun quotaSummary(): String {
        val plan = planName.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return if (monthlyLimit > 0) {
            "SerpApi$plan: quedan $searchesRemaining de $monthlyLimit búsquedas este mes."
        } else {
            "SerpApi$plan: quedan $searchesRemaining búsquedas disponibles."
        }
    }
}

class SerpApiException(
    message: String,
    val httpCode: Int? = null
) : Exception(message)

/**
 * Queries Google Shopping results through SerpApi for Spain.
 *
 * Each successful non-cached query consumes one search credit; the app reuses recent searches and
 * checks the actual account balance/rate window before catalog batches. Price alerts can run
 * a capped daily background check for up to three products with a target price. Results represent
 * a Google Shopping snapshot, not a direct
 * retailer API guarantee; prices and availability must be rechecked at the seller before purchase.
 *
 * The user's API key is supplied at runtime and stored encrypted with Android Keystore. This is
 * suitable only for a private single-device prototype: a public/multi-user release needs a
 * backend proxy and must not expose one user's API key in the APK.
 */
class SerpApiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .build()
) {
    /**
     * Account usage lookup is free at SerpApi and is used to avoid hard-coded local quota guesses.
     * It never exposes the key or email in app state.
     */
    suspend fun getAccountInfo(token: String): SerpApiAccountInfo = withContext(Dispatchers.IO) {
        val apiKey = token.trim()
        require(apiKey.isNotEmpty()) { "Guarda primero tu clave de SerpApi en Ajustes." }
        val request = Request.Builder()
            .url("https://serpapi.com/account.json".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", apiKey)
                .build())
            .get()
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = when (response.code) {
                    401, 403 -> "La clave de SerpApi no es válida o no tiene acceso."
                    429 -> "SerpApi está limitando temporalmente las consultas. Espera un poco y vuelve a intentarlo."
                    else -> "No se pudo comprobar la cuenta SerpApi (HTTP ${response.code})."
                }
                throw SerpApiException(message, response.code)
            }
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw SerpApiException("SerpApi devolvió información de cuenta no válida.")
            }
            parseAccountInfo(json)
        }
    }

    internal fun parseAccountInfo(root: JSONObject): SerpApiAccountInfo {
        val apiError = root.optString("error").takeIf { it.isNotBlank() && it != "null" }
        if (apiError != null) throw SerpApiException("SerpApi: $apiError")
        val planLeft = root.optInt("plan_searches_left", -1)
        val extras = root.optInt("extra_credits", 0).coerceAtLeast(0)
        val remaining = root.optInt(
            "total_searches_left",
            if (planLeft >= 0) planLeft + extras else -1
        )
        if (remaining < 0) throw SerpApiException("La cuenta SerpApi no ha informado del saldo de búsquedas.")
        return SerpApiAccountInfo(
            planName = root.optString("plan_name").takeUnless { it == "null" }.orEmpty(),
            accountStatus = root.optString("account_status").takeUnless { it == "null" }.orEmpty(),
            monthlyLimit = root.optInt("searches_per_month", 0).coerceAtLeast(0),
            monthlySearchesUsed = root.optInt("this_month_usage", 0).coerceAtLeast(0),
            searchesRemaining = remaining,
            hourlyLimit = root.optInt("account_rate_limit_per_hour", 0).coerceAtLeast(0),
            searchesThisHour = root.optInt("this_hour_searches", root.optInt("last_hour_searches", 0)).coerceAtLeast(0),
            renewalDate = root.optString("plan_renewal_date").takeIf { it.isNotBlank() && it != "null" && it != "0" }
        )
    }

    /**
     * Search any user-entered product phrase. Results are Google Shopping snapshots for Spain,
     * not prices obtained from the user's Amazon Prime session or a direct Amazon API.
     */
    suspend fun searchOnline(token: String, query: String): List<SerpApiShoppingResult> =
        searchOnlineWithDiagnostics(token, query).results

    /** Same search with counts of results rejected by the safety/relevance filters. */
    suspend fun searchOnlineWithDiagnostics(
        token: String,
        query: String
    ): SerpApiShoppingSearchReport = withContext(Dispatchers.IO) {
        val apiKey = token.trim()
        require(apiKey.isNotEmpty()) { "Guarda primero tu clave de SerpApi en Ajustes." }
        val normalizedQuery = query.trim().replace(Regex("\\s+"), " ").take(120)
        require(normalizedQuery.length >= 2) { "Escribe al menos 2 caracteres para buscar." }
        val response = executeSearch(apiKey, normalizedQuery)
        parseShoppingResultsDetailed(response, normalizedQuery).copy(rawResponse = response.toString())
    }

    /**
     * Internal supplement for the unified search, never a separate retailer button.
     * Called only if the general search contains no identified GAME offer and there is
     * enough remaining quota for an additional Google Shopping request.
     */
    suspend fun searchGameSupplementWithDiagnostics(
        token: String,
        query: String
    ): SerpApiShoppingSearchReport = withContext(Dispatchers.IO) {
        val apiKey = token.trim()
        require(apiKey.isNotEmpty()) { "Guarda primero tu clave de SerpApi en Ajustes." }
        val normalizedQuery = query.trim().replace(Regex("\\s+"), " ").take(100)
        require(normalizedQuery.length >= 2) { "Escribe al menos 2 caracteres para buscar." }
        val targetedQuery = normalizedQuery + " GAME España"
        val response = executeSearch(apiKey, targetedQuery)
        parseShoppingResultsDetailed(response, normalizedQuery).copy(rawResponse = response.toString())
    }

    /**
     * Search Amazon.es itself, rather than Google Shopping. This still returns a public search
     * snapshot through SerpApi; it cannot see the user's signed-in account or personalized coupons.
     */
    suspend fun searchAmazonOnline(token: String, query: String): List<SerpApiShoppingResult> =
        searchAmazonOnlineWithDiagnostics(token, query).results

    suspend fun searchAmazonOnlineWithDiagnostics(
        token: String,
        query: String
    ): SerpApiShoppingSearchReport = withContext(Dispatchers.IO) {
        val apiKey = token.trim()
        require(apiKey.isNotEmpty()) { "Guarda primero tu clave de SerpApi en Ajustes." }
        val normalizedQuery = query.trim().replace(Regex("\\s+"), " ").take(120)
        require(normalizedQuery.length >= 2) { "Escribe al menos 2 caracteres para buscar." }
        val response = executeAmazonSearch(apiKey, normalizedQuery)
        parseAmazonResultsDetailed(response, normalizedQuery).copy(rawResponse = response.toString())
    }

    internal fun parseAmazonResults(
        root: JSONObject,
        observedAt: Long = System.currentTimeMillis()
    ): List<SerpApiShoppingResult> = parseAmazonResultsDetailed(root, null, observedAt).results

    internal fun parseAmazonResultsDetailed(
        root: JSONObject,
        query: String?,
        observedAt: Long = System.currentTimeMillis()
    ): SerpApiShoppingSearchReport {
        val error = root.optString("error").takeIf { it.isNotBlank() && it != "null" }
        if (error != null) throw SerpApiException(error)
        val status = root.optJSONObject("search_metadata")?.optString("status")
        if (status.equals("Error", ignoreCase = true)) {
            throw SerpApiException("SerpApi no pudo completar la búsqueda en Amazon.es.")
        }

        val items = root.optJSONArray("organic_results")?.toObjects().orEmpty()
        var rejectedAsIrrelevant = 0
        var rejectedForPriceOrCurrency = 0
        var rejectedForLink = 0
        var rejectedOther = 0
        val discardedOffers = mutableListOf<RejectedOnlineOffer>()
        fun recordDiscard(item: JSONObject, title: String, reason: String) {
            discardedOffers += RejectedOnlineOffer(
                title = title.ifBlank { "Título no disponible" },
                priceLabel = sequenceOf(item.optString("price"), item.optString("primary_price"),
                    item.optString("extracted_price"), item.optString("extracted_primary_price"))
                    .firstOrNull { it.isNotBlank() && it != "null" }.orEmpty(),
                reason = reason,
                provider = "Amazon.es"
            )
        }
        val parsed = items.mapNotNull { item ->
            val title = item.optString("title").trim()
                .takeIf { it.isNotBlank() && it != "null" } ?: run {
                    rejectedOther++
                    recordDiscard(item, "", "Datos incompletos")
                    return@mapNotNull null
                }
            if (!query.isNullOrBlank() && isIrrelevantMultiProductBundle(query, title)) {
                rejectedAsIrrelevant++
                recordDiscard(item, title, "Pack con varios productos no solicitado")
                return@mapNotNull null
            }
            if (isClearlyNonNewListing(item, title)) {
                rejectedOther++
                recordDiscard(item, title, nonNewRejectionReason(item, title))
                return@mapNotNull null
            }
            if (!query.isNullOrBlank() && hasVariantMismatch(query, title)) {
                rejectedAsIrrelevant++
                recordDiscard(item, title, "Otro modelo o variante (capacidad, potencia o pack distinto)")
                return@mapNotNull null
            }
            if (!query.isNullOrBlank() && !isPlausibleProductMatch(query, title)) {
                rejectedAsIrrelevant++
                recordDiscard(item, title, "Otro modelo o título no coincidente")
                return@mapNotNull null
            }
            // SerpApi's Amazon engine can expose the active offer as price/extracted_price,
            // or as primary_price/extracted_primary_price depending on the product layout.
            val priceText = sequenceOf(
                item.optString("price"),
                item.optString("primary_price"),
                item.optString("extracted_price"),
                item.optString("extracted_primary_price")
            ).firstOrNull { it.isNotBlank() && it != "null" && it != "0" } ?: ""
            val extractedPrice = sequenceOf("extracted_price", "extracted_primary_price")
                .mapNotNull { key ->
                    item.opt(key)?.takeIf { it !is JSONObject && it !is JSONArray }
                        ?.toString()?.toDoubleOrNull()
                }
                .firstOrNull { it.isFinite() && it > 0.0 }
            // Amazon.es is a Spanish storefront; when its structured numeric price is present,
            // it is a EUR amount even if the formatted label is omitted by a result layout.
            val price = extractedPrice ?: parsePrice(priceText)
            if (price == null || !price.isFinite() || price <= 0.0) {
                rejectedForPriceOrCurrency++
                recordDiscard(item, title, "Sin precio válido en euros")
                return@mapNotNull null
            }
            val isEuroPrice = priceText.contains("€") ||
                priceText.contains("EUR", ignoreCase = true)
            // A structured numeric price is useful even when SerpApi omits the formatted label,
            // but never reinterpret an explicitly labelled foreign currency as euros.
            val explicitlyForeignCurrency =
                listOf('$', '£', '¥', '₹').any { priceText.contains(it) } ||
                    listOf("USD", "GBP", "CAD", "AUD", "NZD", "JPY", "INR", "CHF")
                        .any { currency -> priceText.contains(currency, ignoreCase = true) }
            if (explicitlyForeignCurrency || hasExplicitNonEurCurrency(item)) {
                rejectedForPriceOrCurrency++
                recordDiscard(item, title, "Sin precio válido: moneda extranjera")
                return@mapNotNull null
            }
            if (extractedPrice == null && !isEuroPrice) {
                rejectedForPriceOrCurrency++
                recordDiscard(item, title, "Sin precio válido en euros")
                return@mapNotNull null
            }

            val url = sequenceOf(item.optString("link_clean"), item.optString("link"))
                .firstOrNull { isAmazonSpainUrl(it) } ?: run {
                    rejectedForLink++
                    recordDiscard(item, title, "Datos incompletos: sin enlace válido de Amazon.es")
                    return@mapNotNull null
                }
            val thumbnail = item.optString("thumbnail").takeIf { isHttpsUrl(it) }.orEmpty()
            val delivery = when (val raw = item.opt("delivery")) {
                is JSONArray -> (0 until raw.length())
                    .mapNotNull { index -> raw.optString(index).takeIf { it.isNotBlank() && it != "null" } }
                    .joinToString(" · ")
                is JSONObject -> raw.optString("text").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                else -> raw?.toString()?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()
            }
            val rating = item.opt("rating")?.toString()?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in 0.0..5.0 }
            val reviewCount = item.opt("reviews")?.toString()
                ?.replace(Regex("[^0-9]"), "")
                ?.toIntOrNull()
                ?.takeIf { it >= 0 }

            SerpApiShoppingResult(
                title = title,
                price = price,
                storeName = "Amazon.es",
                sourceUrl = url,
                thumbnailUrl = thumbnail,
                delivery = delivery,
                rating = rating,
                reviewCount = reviewCount,
                observedAt = observedAt,
                primeEligible = item.optBoolean("prime", false)
            )
        }.distinctBy { "${it.sourceUrl.lowercase(Locale.ROOT)}|${it.price}" }
            .sortedBy { it.price }
            .take(MAX_ONLINE_RESULTS)

        return SerpApiShoppingSearchReport(
            results = parsed,
            rawResultCount = items.size,
            rejectedAsIrrelevant = rejectedAsIrrelevant,
            rejectedForPriceOrCurrency = rejectedForPriceOrCurrency,
            rejectedForLink = rejectedForLink,
            rejectedOther = rejectedOther,
            discardedOffers = discardedOffers,
            providerName = "Amazon.es"
        )
    }

    internal fun parseShoppingResults(
        root: JSONObject,
        observedAt: Long = System.currentTimeMillis()
    ): List<SerpApiShoppingResult> =
        parseShoppingResultsDetailed(root, query = null, observedAt = observedAt).results

    internal fun parseShoppingResultsDetailed(
        root: JSONObject,
        query: String?,
        observedAt: Long = System.currentTimeMillis()
    ): SerpApiShoppingSearchReport {
        val error = root.optString("error").takeIf { it.isNotBlank() && it != "null" }
        if (error != null) throw SerpApiException(error)
        val status = root.optJSONObject("search_metadata")?.optString("status")
        if (status.equals("Error", ignoreCase = true)) {
            throw SerpApiException("SerpApi no pudo completar la búsqueda.")
        }

        val items = mutableListOf<JSONObject>()
        root.optJSONArray("shopping_results")?.let { items += it.toObjects() }
        root.optJSONArray("categorized_shopping_results")?.let { categories ->
            for (index in 0 until categories.length()) {
                categories.optJSONObject(index)?.optJSONArray("shopping_results")?.let {
                    items += it.toObjects()
                }
            }
        }

        var rejectedAsIrrelevant = 0
        var rejectedForPriceOrCurrency = 0
        var rejectedForLink = 0
        var rejectedOther = 0
        val discardedOffers = mutableListOf<RejectedOnlineOffer>()
        fun recordDiscard(item: JSONObject, title: String, reason: String) {
            discardedOffers += RejectedOnlineOffer(
                title = title.ifBlank { "Título no disponible" },
                priceLabel = item.optString("price").takeIf { it.isNotBlank() && it != "null" }
                    ?: item.optString("extracted_price").takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
                reason = reason,
                provider = "Google Shopping"
            )
        }
        val parsed = items.mapNotNull { item ->
            val title = item.optString("title").trim()
                .takeIf { it.isNotBlank() && it != "null" }
                ?: run {
                    rejectedOther++
                    recordDiscard(item, "", "Datos incompletos")
                    return@mapNotNull null
                }
            val condition = sequenceOf(
                "second_hand_condition", "condition", "item_condition",
                "product_condition", "condition_description"
            ).mapNotNull { key ->
                item.optString(key).takeIf { it.isNotBlank() && it != "null" }
            }.firstOrNull().orEmpty()
            if (isRentalOrMonthlyListing(item, title)) {
                rejectedOther++
                recordDiscard(item, title, "Alquiler o cuota mensual")
                return@mapNotNull null
            }
            var fitmentUnverified = false
            var categoryUnverified = false
            val wheelFitmentQuery = query?.let(::parseWheelFitmentQuery)
            if (wheelFitmentQuery != null) {
                // Exclude explicit hubcap and decorative-cover listings from rim searches.
                val lowerTitle = Normalizer.normalize(title.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                    .replace(Regex("\\p{M}+"), "")
                if (listOf(
                    "tapacubo", "hubcap", "wheel cover", "wheel trim", "copricerchi",
                    "coprimozzo", "enjoliveur", "radkappe", "radkappen", "kolpak", "kolpaki", "kołpak", "kołpaki",
                    "cubrellanta", "cubrerueda"
                ).any(lowerTitle::contains)) {
                    rejectedAsIrrelevant++
                    recordDiscard(item, title, "Accesorio que no es una llanta")
                    return@mapNotNull null
                }
                val modelQuery = query?.let(::wheelModelQuery).orEmpty()
                val modelMatches = modelQuery.isBlank() || isPlausibleProductMatch(modelQuery, title)
                if (!modelMatches) {
                    rejectedAsIrrelevant++
                    recordDiscard(item, title, "Otro modelo o título no coincidente")
                    return@mapNotNull null
                }
                // When the query only specifies generic fitment (no wheel model), do not
                // accept obvious non-wheel accessories such as hubcaps as uncertain wheel results.
                // A named/model-specific match may still lack category words and remains explicitly
                // unverified, rather than being silently discarded.
                if (modelQuery.isBlank() && !isWheelListing(title) && !hasGenericWheelEvidence(title)) {
                    rejectedAsIrrelevant++
                    recordDiscard(item, title, "No parece una llanta o rueda")
                    return@mapNotNull null
                }
                categoryUnverified = !isWheelListing(title)
                val resultDiameter = wheelDiameterInches(title)
                val resultPcd = wheelBoltPattern(title)
                if (resultDiameter != null && resultDiameter != wheelFitmentQuery.diameterInches) {
                    rejectedAsIrrelevant++
                    recordDiscard(item, title, "Diámetro distinto: " + resultDiameter +
                        " pulgadas (buscaste " + wheelFitmentQuery.diameterInches + " pulgadas)")
                    return@mapNotNull null
                }
                if (resultPcd != null && resultPcd != wheelFitmentQuery.boltPattern) {
                    rejectedAsIrrelevant++
                    recordDiscard(item, title, "Anclaje distinto: " + resultPcd +
                        " (buscaste " + wheelFitmentQuery.boltPattern + ")")
                    return@mapNotNull null
                }
                val resultWidth = wheelWidthInches(title)
                if (wheelFitmentQuery.widthInches != null && resultWidth != null &&
                    kotlin.math.abs(resultWidth - wheelFitmentQuery.widthInches) > 0.01
                ) {
                    rejectedAsIrrelevant++
                    recordDiscard(
                        item,
                        title,
                        "Ancho distinto: " + formatWheelNumber(resultWidth) +
                            " pulgadas (buscaste " + formatWheelNumber(wheelFitmentQuery.widthInches) + " pulgadas)"
                    )
                    return@mapNotNull null
                }
                val resultOffset = wheelOffsetMm(title)
                if (wheelFitmentQuery.offsetMm != null && resultOffset != null &&
                    resultOffset != wheelFitmentQuery.offsetMm
                ) {
                    rejectedAsIrrelevant++
                    recordDiscard(
                        item,
                        title,
                        "ET distinto: " + resultOffset + " (buscaste ET" + wheelFitmentQuery.offsetMm + ")"
                    )
                    return@mapNotNull null
                }
                fitmentUnverified =
                    resultDiameter != wheelFitmentQuery.diameterInches ||
                    resultPcd != wheelFitmentQuery.boltPattern ||
                    (wheelFitmentQuery.widthInches != null && resultWidth != wheelFitmentQuery.widthInches) ||
                    (wheelFitmentQuery.offsetMm != null && resultOffset != wheelFitmentQuery.offsetMm)            } else {
                if (!query.isNullOrBlank() && isIrrelevantMultiProductBundle(query, title)) {
                    rejectedAsIrrelevant++
                    recordDiscard(item, title, "Pack con varios productos no solicitado")
                    return@mapNotNull null
                }
                if (!query.isNullOrBlank() && hasVariantMismatch(query, title)) {
                    rejectedAsIrrelevant++
                    recordDiscard(item, title, "Otro modelo o variante (capacidad, potencia o pack distinto)")
                    return@mapNotNull null
                }
                if (!query.isNullOrBlank() && !isPlausibleProductMatch(query, title)) {
                    rejectedAsIrrelevant++
                    recordDiscard(item, title, "Otro modelo o título no coincidente")
                    return@mapNotNull null
                }
            }

            val priceText = item.optString("price")
            val extractedPrice = item.opt("extracted_price")
                ?.takeIf { it !is JSONObject && it !is JSONArray }
                ?.toString()
                ?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
            if (hasExplicitForeignCurrency(priceText) || hasExplicitNonEurCurrency(item)) {
                rejectedForPriceOrCurrency++
                recordDiscard(item, title, "Sin precio válido: moneda extranjera")
                return@mapNotNull null
            }
            val price = extractedPrice ?: parsePrice(priceText)
            val isEuroPrice = priceText.contains("€") ||
                priceText.contains("EUR", ignoreCase = true)
            if (price == null || !price.isFinite() || price <= 0.0 ||
                (extractedPrice == null && !isEuroPrice)
            ) {
                rejectedForPriceOrCurrency++
                recordDiscard(item, title, "Sin precio válido en euros")
                return@mapNotNull null
            }

            // Prefer a real seller product URL extracted from Google redirects. If the provider
            // only gives a Google Shopping URL, use the known seller's own search page when
            // possible so tapping an Anker/Fnac/Amazon offer does not send the user back to Google.
            // Unknown sellers retain the explicit Google Shopping fallback instead of inventing
            // an unverified merchant destination.
            val candidateUrls = sequenceOf(
                item.optString("link_clean"),
                item.optString("link"),
                item.optString("product_link")
            ).filter(::isNavigableHttpUrl).toList()
            val providerSource = item.optString("source").trim()
                .takeIf { it.isNotBlank() && it != "null" }
            val directRetailerUrl = candidateUrls.firstNotNullOfOrNull(::extractDirectRetailerUrl)
            val fallbackProviderUrl = candidateUrls.firstOrNull()
            val isGoogleCatalogProductPage = fallbackProviderUrl?.let { candidate ->
                isGoogleShoppingUrl(candidate) &&
                    runCatching { URI(candidate).path.orEmpty().contains("/shopping/product/") }
                        .getOrDefault(false)
            } == true
            val knownStore = providerSource?.let { storeName ->
                Store.findKnownByName(storeName, directRetailerUrl ?: fallbackProviderUrl.orEmpty())
            }
            val url = directRetailerUrl
                ?: knownStore?.takeIf {
                    it.supportsProductSearch && !isGoogleCatalogProductPage
                }?.searchUrlFor(title)
                ?: fallbackProviderUrl
                ?: googleShoppingSearchUrl(title)
            val host = runCatching { URI(url).host.lowercase(Locale.ROOT).removePrefix("www.") }
                .getOrNull().orEmpty()
            val source = providerSource ?: humanizeHost(host)
            val thumbnail = item.optString("thumbnail").takeIf { isHttpsUrl(it) }.orEmpty()
            val delivery = sequenceOf(item.optString("delivery"), item.optString("shipping"))
                .firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()
            val rating = item.opt("rating")?.toString()?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in 0.0..5.0 }
            val reviewCount = item.opt("reviews")?.toString()
                ?.replace(Regex("[^0-9]"), "")
                ?.toIntOrNull()
                ?.takeIf { it >= 0 }

            val googleProductId = item.optString("product_id")
                .takeIf { it.matches(Regex("[0-9]{5,}")) }
                ?: extractGoogleShoppingProductId(item.optString("product_link"))
            SerpApiShoppingResult(
                title = title,
                price = price,
                storeName = source,
                sourceUrl = url,
                thumbnailUrl = thumbnail,
                delivery = delivery,
                rating = rating,
                reviewCount = reviewCount,
                observedAt = observedAt,
                condition = condition,
                googleProductId = googleProductId,
                isFitmentUnverified = fitmentUnverified,
                isCategoryUnverified = categoryUnverified
            )
        }.distinctBy { "${it.sourceUrl.lowercase(Locale.ROOT)}|${it.price}" }
            .sortedBy { it.price }
            .take(MAX_ONLINE_RESULTS)

        return SerpApiShoppingSearchReport(
            results = parsed,
            rawResultCount = items.size,
            rejectedAsIrrelevant = rejectedAsIrrelevant,
            rejectedForPriceOrCurrency = rejectedForPriceOrCurrency,
            rejectedForLink = rejectedForLink,
            rejectedOther = rejectedOther,
            discardedOffers = discardedOffers,
            firstResultFields = items.firstOrNull()
                ?.keys()
                ?.asSequence()
                ?.joinToString(", ")
                .orEmpty()
        )
    }

    /**
     * Fetches all retailer offers for a known Google Shopping catalog item. It costs one SerpApi
     * search and uses the product_id from a previous Google Shopping result, so this must not
     * silently fall back to a second keyword search.
     */
    suspend fun searchGoogleProductOffers(
        token: String,
        productId: String,
        expectedTitle: String,
        observedAt: Long = System.currentTimeMillis()
    ): List<SerpApiShoppingResult> =
        searchGoogleProductDetails(token, productId, expectedTitle, observedAt).offers

    /**
     * Fetches current store offers and any historical series Google exposes in the same request.
     * No extra SerpApi search is spent to read price_insights.
     */
    suspend fun searchGoogleProductDetails(
        token: String,
        productId: String,
        expectedTitle: String,
        observedAt: Long = System.currentTimeMillis()
    ): SerpApiGoogleProductDetails = withContext(Dispatchers.IO) {
        val apiKey = token.trim()
        require(apiKey.isNotEmpty()) { "Guarda primero tu clave de SerpApi en Ajustes." }
        val safeProductId = productId.trim()
            .takeIf { it.matches(Regex("[0-9]{5,}")) }
            ?: throw SerpApiException("Este producto no tiene un ID de catálogo de Google Shopping válido; no se ha lanzado ninguna búsqueda.")
        parseGoogleProductDetails(
            root = executeJson(buildGoogleProductUrl(apiKey, safeProductId)),
            expectedTitle = expectedTitle,
            productId = safeProductId,
            observedAt = observedAt
        )
    }

    /** Parser isolated from network access so current offers and historical insights can be tested offline. */
    internal fun parseGoogleProductDetails(
        root: JSONObject,
        expectedTitle: String,
        productId: String,
        observedAt: Long = System.currentTimeMillis()
    ): SerpApiGoogleProductDetails {
        val product = root.optJSONObject("product_results")
        val catalogTitle = product?.optString("title").orEmpty().trim()
        val catalogMatches = catalogTitle.isNotBlank() &&
            !hasVariantMismatch(expectedTitle, catalogTitle) &&
            !hasConflictingModelVariant(expectedTitle, catalogTitle) &&
            isPlausibleProductMatch(expectedTitle, catalogTitle)
        if (!catalogMatches) {
            return SerpApiGoogleProductDetails(
                offers = parseGoogleProductOffers(root, expectedTitle, productId, observedAt),
                priceInsights = emptyList(),
                priceHistoryAvailable = false
            )
        }

        val insightBlock = product?.optJSONObject("price_insights")
        val historyAvailable = insightBlock?.optBoolean("price_history", false) == true
        val currency = insightBlock?.optString("currency").orEmpty().uppercase(Locale.ROOT)
        if (!historyAvailable || currency != "EUR") {
            return SerpApiGoogleProductDetails(
                offers = parseGoogleProductOffers(root, expectedTitle, productId, observedAt),
                priceInsights = emptyList(),
                priceHistoryAvailable = historyAvailable
            )
        }

        val earliestAllowed = observedAt - MAX_PRICE_INSIGHT_LOOKBACK_DAYS * DAY_MS
        val history = mutableListOf<SerpApiPriceInsight>()
        val stores = insightBlock.optJSONArray("stores")
        if (stores != null) {
            for (storeIndex in 0 until stores.length()) {
                val store = stores.optJSONObject(storeIndex) ?: continue
                val storeName = store.optString("name").trim()
                    .takeIf { it.isNotBlank() && it != "null" } ?: continue
                val prices = store.optJSONArray("prices") ?: continue
                for (priceIndex in 0 until prices.length()) {
                    val item = prices.optJSONObject(priceIndex) ?: continue
                    val timestamp = parsePriceInsightDate(item.optString("date")) ?: continue
                    val price = item.opt("price")?.toString()?.toDoubleOrNull() ?: continue
                    if (timestamp < earliestAllowed || timestamp >= observedAt ||
                        !price.isFinite() || price !in MIN_PRICE_INSIGHT_EUR..MAX_PRICE_INSIGHT_EUR
                    ) continue
                    history += SerpApiPriceInsight(
                        productTitle = catalogTitle,
                        storeName = storeName,
                        price = price,
                        currency = currency,
                        observedAt = timestamp
                    )
                }
            }
        }

        return SerpApiGoogleProductDetails(
            offers = parseGoogleProductOffers(root, expectedTitle, productId, observedAt),
            priceInsights = history.distinctBy { Triple(it.storeName.lowercase(Locale.ROOT), it.observedAt, it.price) }
                .sortedBy { it.observedAt },
            priceHistoryAvailable = historyAvailable
        )
    }

    private fun parsePriceInsightDate(value: String): Long? {
        val input = value.trim().replace(Regex("\\s+"), " ")
        if (input.isBlank()) return null
        val locales = listOf(Locale.US, Locale.UK, Locale("es", "ES"))
        val patterns = listOf("MMM d, yyyy", "MMMM d, yyyy", "d MMM yyyy", "d MMMM yyyy", "yyyy-MM-dd", "dd/MM/yyyy")
        for (locale in locales) {
            for (pattern in patterns) {
                val formatter = SimpleDateFormat(pattern, locale).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val position = ParsePosition(0)
                val date: Date = formatter.parse(input, position) ?: continue
                if (position.index == input.length) return date.time
            }
        }
        return null
    }

    /** Parser isolated from network access so the full Google Product API shape can be tested offline. */
    internal fun parseGoogleProductOffers(
        root: JSONObject,
        expectedTitle: String,
        productId: String,
        observedAt: Long = System.currentTimeMillis()
    ): List<SerpApiShoppingResult> {
        val error = root.optString("error").takeIf { it.isNotBlank() && it != "null" }
        if (error != null) throw SerpApiException("SerpApi: $error")
        val state = root.optJSONObject("search_metadata")?.optString("status")
        if (state.equals("Error", ignoreCase = true)) {
            throw SerpApiException("SerpApi no pudo obtener las tiendas de este producto.")
        }
        val product = root.optJSONObject("product_results") ?: return emptyList()
        val catalogTitle = product.optString("title").trim()
        // Refuse a stale or mismatched catalog ID before accepting any merchant row.
        if (catalogTitle.isBlank() ||
            hasVariantMismatch(expectedTitle, catalogTitle) ||
            hasConflictingModelVariant(expectedTitle, catalogTitle) ||
            !isPlausibleProductMatch(expectedTitle, catalogTitle)
        ) return emptyList()

        val stores = product.optJSONArray("stores")?.toObjects().orEmpty()
        return stores.mapNotNull { store ->
            val name = store.optString("name").trim().takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            val offerTitle = store.optString("title").trim().takeIf { it.isNotBlank() && it != "null" }.orEmpty()
            val combinedTitle = listOf(catalogTitle, offerTitle).filter { it.isNotBlank() }.joinToString(" ")
            if (isClearlyNonNewListing(store, combinedTitle)) return@mapNotNull null
            val marketplaceSeller = Regex(
                """(?i)\b(wallapop|milanuncios|ebay|vinted|amazon[\s_-]*warehouse|vendedor\s+particular|particular(?:es)?)\b"""
            ).containsMatchIn("$name $offerTitle")
            if (marketplaceSeller || (offerTitle.isNotBlank() &&
                    (hasVariantMismatch(expectedTitle, offerTitle) ||
                        hasConflictingModelVariant(expectedTitle, offerTitle)))) {
                return@mapNotNull null
            }
            val paymentTerms = listOf(
                store.optString("installments_description"),
                store.optString("monthly_payment_duration"),
                store.optString("down_payment"),
                store.optString("alt_currency")
            ).filter { it.isNotBlank() && it != "null" }.joinToString(" ")
            if (Regex(
                    """(?i)(?:\b(cuota|cuotas|mensual|monthly|installment|installments|por\s+mes|per\s+month|alquiler|rental|renting)\b|/\s*mes\b)"""
                ).containsMatchIn(paymentTerms) ||
                store.optInt("monthly_payment_duration", 0) > 0 ||
                isRentalOrMonthlyListing(store, combinedTitle)
            ) return@mapNotNull null

            val priceText = store.optString("price")
            if (hasExplicitForeignCurrency(priceText) || hasExplicitNonEurCurrency(store)) {
                return@mapNotNull null
            }
            val extractedPrice = store.opt("extracted_price")
                ?.takeIf { it !is JSONObject && it !is JSONArray }
                ?.toString()
                ?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
            val price = extractedPrice ?: parsePrice(priceText) ?: return@mapNotNull null
            if (!price.isFinite() || price <= 0.0 ||
                (extractedPrice == null &&
                    !priceText.contains("€") &&
                    !priceText.contains("EUR", ignoreCase = true))
            ) return@mapNotNull null
            val rawUrl = store.optString("link").takeIf(::isNavigableHttpUrl) ?: return@mapNotNull null
            val url = extractDirectRetailerUrl(rawUrl) ?: rawUrl
            SerpApiShoppingResult(
                title = catalogTitle,
                price = price,
                storeName = name,
                sourceUrl = url,
                thumbnailUrl = store.optString("logo").takeIf(::isHttpsUrl).orEmpty(),
                delivery = store.optString("shipping").takeUnless { it == "null" }.orEmpty(),
                rating = store.optDouble("rating", Double.NaN).takeIf { it.isFinite() && it in 0.0..5.0 },
                reviewCount = store.optInt("reviews", -1).takeIf { it >= 0 },
                observedAt = observedAt,
                condition = store.optString("condition").takeUnless { it == "null" }.orEmpty(),
                googleProductId = productId
            )
        }.distinctBy {
            "${it.storeName.trim().lowercase(Locale.ROOT)}|${it.sourceUrl.trim().lowercase(Locale.ROOT)}|${it.price}"
        }.sortedBy { it.price }
    }

    private fun hasConflictingModelVariant(requested: String, returned: String): Boolean {
        val queryWords = normalize(requested).split(' ').filter { it.isNotBlank() }.toSet()
        val returnedWords = normalize(returned).split(' ').filter { it.isNotBlank() }.toSet()
        val variantFamilies = listOf(
            setOf("pro", "max", "plus", "ultra", "fe", "lite", "mini", "standard"),
            setOf("fold", "flip"),
            setOf("classic", "edge")
        )
        return variantFamilies.any { family ->
            val requestedVariant = queryWords.intersect(family)
            requestedVariant.isNotEmpty() && returnedWords.intersect(family) != requestedVariant
        }
    }

    suspend fun searchProducts(
        token: String,
        products: List<SerpApiRequestProduct>,
        onStage: suspend (String) -> Unit = {},
        onQueryStarted: suspend (SerpApiRequestProduct) -> Unit = {}
    ): SerpApiBatchResult = withContext(Dispatchers.IO) {
        val apiKey = token.trim()
        require(apiKey.isNotEmpty()) { "Guarda primero tu clave de SerpApi en Ajustes." }

        val requested = products
            .filter { it.id.isNotBlank() && it.queryTerm.isNotBlank() }
            .distinctBy { it.id }
            .take(MAX_PRODUCTS_PER_BATCH)

        if (requested.isEmpty()) {
            return@withContext SerpApiBatchResult(
                quotes = emptyList(),
                requestedQueries = 0,
                failedQueries = 0
            )
        }

        val quotes = mutableListOf<SerpApiQuote>()
        var failedQueries = 0
        var queriesWithoutMatches = 0
        var firstFailure: Exception? = null
        requested.forEachIndexed { index, product ->
            onStage("Consultando Google Shopping España: ${index + 1}/${requested.size}…")
            try {
                onQueryStarted(product)
                val json = executeSearch(apiKey, product.queryTerm)
                val productQuotes = parseQuotes(json, product)
                if (productQuotes.isEmpty()) {
                    queriesWithoutMatches++
                } else {
                    quotes += productQuotes
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A single bad product must not discard offers already collected for this batch.
                failedQueries++
                if (firstFailure == null) firstFailure = error
                onStage("Falló una consulta (${failedQueries}/${requested.size}); conservando los resultados válidos…")
            }
            // Spread requests to avoid unnecessarily bursting the provider.
            if (index < requested.lastIndex) delay(INTER_REQUEST_DELAY_MS)
        }

        if (quotes.isEmpty() && failedQueries > 0) {
            val failureDetail = when (val failure = firstFailure) {
                is SerpApiException -> failure.message ?: "Error devuelto por SerpApi."
                is java.net.SocketTimeoutException -> "Se agotó el tiempo de espera al conectar con SerpApi."
                is java.net.UnknownHostException -> "No se pudo localizar el servidor de SerpApi; comprueba la conexión."
                is java.io.IOException -> "Fallo de red (${failure.javaClass.simpleName})."
                else -> "Error técnico (${firstFailure?.javaClass?.simpleName ?: "desconocido"})."
            }
            throw SerpApiException(
                "No se pudo consultar ningún producto (${failedQueries} de ${requested.size} consultas fallidas). Motivo del primer fallo: $failureDetail",
                (firstFailure as? SerpApiException)?.httpCode
            )
        }

        SerpApiBatchResult(
            quotes = quotes
                .distinctBy { "${it.productId}|${it.storeId}|${it.price}|${it.sourceUrl}" }
                .sortedWith(compareBy<SerpApiQuote> { it.productId }.thenBy { it.price }),
            requestedQueries = requested.size,
            failedQueries = failedQueries,
            queriesWithoutMatches = queriesWithoutMatches
        )
    }

    internal fun parseQuotes(
        root: JSONObject,
        requested: SerpApiRequestProduct,
        observedAt: Long = System.currentTimeMillis()
    ): List<SerpApiQuote> {
        val error = root.optString("error").takeIf { it.isNotBlank() && it != "null" }
        if (error != null) throw SerpApiException(error)

        val status = root.optJSONObject("search_metadata")?.optString("status")
        if (status.equals("Error", ignoreCase = true)) {
            throw SerpApiException("SerpApi no pudo completar la búsqueda del producto.")
        }

        val results = mutableListOf<JSONObject>()
        root.optJSONArray("shopping_results")?.let { results += it.toObjects() }
        root.optJSONArray("categorized_shopping_results")?.let { categories ->
            for (i in 0 until categories.length()) {
                val nested = categories.optJSONObject(i)?.optJSONArray("shopping_results")
                if (nested != null) results += nested.toObjects()
            }
        }

        return results.mapNotNull { item ->
            val title = item.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (isClearlyNonNewListing(item, title)) return@mapNotNull null
            if (hasVariantMismatch(requested.queryTerm, title) ||
                hasConflictingModelVariant(requested.queryTerm, title)) return@mapNotNull null
            val requestedEan = requested.ean.filter(Char::isDigit)
            val responseEans = listOf("gtin", "gtins", "ean", "upc", "barcode", "product_ean", "product_gtin")
                .flatMap { key ->
                    when (val raw = item.opt(key)) {
                        null, JSONObject.NULL -> emptyList()
                        is JSONArray -> raw.toObjects().map { it.toString().filter(Char::isDigit) }
                        else -> raw.toString().split(Regex("[,;\\s]+")).map { it.filter(Char::isDigit) }
                    }
                }.filter { it.length in 8..14 }
            val eanMatch = requestedEan.length in 8..14 && responseEans.any { it == requestedEan }
            if (!isPlausibleProductMatch(requested.queryTerm, title) && !eanMatch) return@mapNotNull null

            val priceText = item.optString("price")
            val extractedPrice = item.opt("extracted_price")
                ?.takeIf { it !is JSONObject && it !is JSONArray }
                ?.toString()
                ?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
            // Google Shopping Spain may omit the € label even when extracted_price is present.
            // Accept the structured Spanish-market value, but reject explicit foreign currencies.
            if (hasExplicitForeignCurrency(priceText)) return@mapNotNull null
            val price = extractedPrice ?: parsePrice(priceText) ?: return@mapNotNull null
            val isEuroPrice = priceText.contains("€") ||
                priceText.contains("EUR", ignoreCase = true)
            if (extractedPrice == null && !isEuroPrice) return@mapNotNull null

            // Keep Google Shopping URLs or synthesize a Google Shopping search URL when the
            // provider omits a navigable destination. Link type is not a rejection criterion.
            val candidateUrls = sequenceOf(
                item.optString("link_clean"),
                item.optString("link"),
                item.optString("product_link")
            ).filter(::isNavigableHttpUrl).toList()
            val url = candidateUrls.firstOrNull { !isGoogleDomainUrl(it) }
                ?: candidateUrls.firstOrNull()
                ?: googleShoppingSearchUrl(title)
            val host = runCatching { URI(url).host.lowercase(Locale.ROOT).removePrefix("www.") }
                .getOrNull().orEmpty()

            val merchant = item.optString("source")
                .takeIf { it.isNotBlank() && it != "null" }
                ?: humanizeHost(host)

            SerpApiQuote(
                productId = requested.id,
                price = price,
                currency = "EUR",
                observedAt = observedAt,
                sourceUrl = url,
                storeId = storeIdForHost(host, merchant),
                storeName = merchant,
                sourceName = "SerpApi · Google Shopping España",
                googleProductId = item.optString("product_id")
                    .takeIf { it.matches(Regex("[0-9]{5,}")) }
                    ?: extractGoogleShoppingProductId(item.optString("product_link"))
            )
        }.sortedBy { it.price }
    }

    private suspend fun executeSearch(apiKey: String, query: String): JSONObject =
        executeJson(buildUrl(apiKey, query))

    private suspend fun executeAmazonSearch(apiKey: String, query: String): JSONObject =
        executeJson(buildAmazonUrl(apiKey, query))

    private suspend fun executeJson(url: HttpUrl): JSONObject {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "MinimoAndroid/1.5")
            .build()

        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = when (response.code) {
                        401, 403 -> "La clave de SerpApi no es válida o no tiene acceso."
                        429 -> "Has alcanzado el límite de consultas de SerpApi. Espera antes de volver a intentarlo."
                        else -> "SerpApi respondió con el código HTTP ${response.code}."
                    }
                    throw SerpApiException(message, response.code)
                }
                val json = runCatching { JSONObject(body) }.getOrElse {
                    throw SerpApiException("SerpApi devolvió una respuesta que no es JSON válido.")
                }
                val apiError = json.optString("error").takeIf { it.isNotBlank() && it != "null" }
                if (apiError != null) throw SerpApiException("SerpApi: $apiError")
                json
            }
        }
    }

    private fun buildUrl(apiKey: String, query: String): HttpUrl =
        "https://serpapi.com/search.json"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("engine", "google_shopping")
            .addQueryParameter("q", query)
            .addQueryParameter("num", MAX_ONLINE_RESULTS.toString())
            .addQueryParameter("gl", "es")
            .addQueryParameter("hl", "es")
            .addQueryParameter("api_key", apiKey)
            .build()

    private fun buildGoogleProductUrl(apiKey: String, productId: String): HttpUrl =
        "https://serpapi.com/search.json"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("engine", "google_product")
            .addQueryParameter("product_id", productId)
            .addQueryParameter("offers", "true")
            .addQueryParameter("gl", "es")
            .addQueryParameter("hl", "es")
            .addQueryParameter("api_key", apiKey)
            .build()

    private fun buildAmazonUrl(apiKey: String, query: String): HttpUrl =
        "https://serpapi.com/search.json"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("engine", "amazon")
            .addQueryParameter("k", query)
            .addQueryParameter("amazon_domain", "amazon.es")
            .addQueryParameter("language", "es_ES")
            .addQueryParameter("shipping_location", "ES")
            .addQueryParameter("s", "price-asc-rank")
            .addQueryParameter("api_key", apiKey)
            .build()

    private fun JSONArray.toObjects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }

    private fun hasExplicitForeignCurrency(value: String): Boolean =
        listOf(36.toChar(), '£', '¥', '₹').any { value.contains(it) } ||
            listOf("USD", "GBP", "CAD", "AUD", "NZD", "JPY", "INR", "CHF")
                .any { currency -> value.contains(currency, ignoreCase = true) }

    /** Reject a structured currency field that contradicts the Spanish/EUR search context. */
    private fun hasExplicitNonEurCurrency(item: JSONObject): Boolean {
        val currency = listOf(
            "currency", "currency_code", "currencyCode", "price_currency", "priceCurrency"
        ).firstNotNullOfOrNull { key ->
            item.optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
        } ?: return false

        val normalized = currency.uppercase(Locale.ROOT)
        val codes = Regex("""\b[A-Z]{3}\b""").findAll(normalized)
            .map { it.value }
            .toSet()
        if (codes.isNotEmpty()) return codes != setOf("EUR")
        return !(normalized in setOf("EURO", "EUROS") || currency.contains('€'))
    }

    private fun parsePrice(raw: Any?): Double? {
        if (raw == null || raw == JSONObject.NULL) return null
        if (raw is Number) return raw.toDouble().takeIf { it.isFinite() && it > 0.0 }
        var numeric = raw.toString().replace(Regex("[^0-9,.-]"), "")
        if (numeric.isBlank()) return null
        val comma = numeric.lastIndexOf(',')
        val dot = numeric.lastIndexOf('.')
        numeric = when {
            comma >= 0 && dot >= 0 && comma > dot ->
                numeric.replace(".", "").replace(',', '.')
            comma >= 0 && dot >= 0 && dot > comma ->
                numeric.replace(",", "")
            comma >= 0 && dot < 0 -> {
                val decimalDigits = numeric.length - comma - 1
                if (decimalDigits in 1..2) numeric.replace(',', '.') else numeric.replace(",", "")
            }
            else -> numeric
        }
        return numeric.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun isGoogleDomainUrl(value: String): Boolean = isGoogleShoppingUrl(value)

    private fun googleShoppingSearchUrl(title: String): String =
        "https://www.google.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("tbm", "shop")
            .addQueryParameter("hl", "es")
            .addQueryParameter("gl", "es")
            .addQueryParameter("q", title)
            .build()
            .toString()

    private fun isNavigableHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun isRentalOrMonthlyListing(item: JSONObject, title: String): Boolean {
        val excludedTerms = Regex(
            """(?i)(?:\b(alquiler|rental|renting|rent|leasing|lease|hire|financiacion|financiar|financing|subscription|suscripcion|monthly|mensual|cuota|pago\s+a\s+plazos|por\s+mes|per\s+month|mes\s+a\s+mes)\b|/\s*mes\b)"""
        )
        if (excludedTerms.containsMatchIn(title)) return true
        val conditionText = listOf(
            "second_hand_condition", "condition", "item_condition",
            "product_condition", "condition_description"
        ).mapNotNull { key ->
            item.opt(key)?.toString()?.takeUnless { it.isBlank() || it == "null" }
        }.joinToString(" ")
        return excludedTerms.containsMatchIn(conditionText)
    }

    private fun nonNewRejectionReason(item: JSONObject, title: String): String {
        val condition = listOf("second_hand_condition", "condition", "item_condition", "product_condition", "condition_description")
            .mapNotNull { item.optString(it).takeIf { value -> value.isNotBlank() && value != "null" } }
            .joinToString(" ")
        val text = "$title $condition"
        return if (Regex("""(?i)(?:\b(alquiler|rental|renting|rent|leasing|lease|hire|financiacion|financiar|financing|subscription|suscripcion|monthly|mensual|cuota|pago\s+a\s+plazos|por\s+mes|per\s+month|mes\s+a\s+mes)\b|/\s*mes\b)""").containsMatchIn(text)) {
            "Alquiler o cuota mensual"
        } else {
            "Segunda mano o reacondicionado"
        }
    }

    private data class WheelFitmentQuery(
        val diameterInches: Int,
        val boltPattern: String,
        val widthInches: Double?,
        val offsetMm: Int?
    )

    private fun parseWheelFitmentQuery(query: String): WheelFitmentQuery? {
        // An explicit diameter plus PCD identifies a wheel search even if the query begins with
        // a model name ("RIAL Milano 14\" 5x100") instead of the word "llanta".
        val normalizedQuery = normalizeWheelFitmentQuery(query)
        // A diameter plus PCD is sufficient even when the user starts with a wheel model name
        // rather than a generic word such as "llanta".
        val diameter = wheelDiameterInches(normalizedQuery)
            ?: Regex("""\b(1[0-9]|2[0-4])\b""").find(normalizedQuery)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        val boltPattern = wheelBoltPattern(normalizedQuery) ?: return null
        return WheelFitmentQuery(
            diameterInches = diameter,
            boltPattern = boltPattern,
            widthInches = wheelWidthInches(normalizedQuery),
            offsetMm = wheelOffsetMm(normalizedQuery)
        )
    }

    private fun wheelModelQuery(query: String): String =
        Normalizer.normalize(normalizeWheelFitmentQuery(query).lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            // Remove full width/diameter expressions first so "5.5x14" does not leave a
            // stray width token that can interfere with matching the actual wheel model.
            .replace(Regex("""(?i)\b(?:[3-9](?:[.,]\d)?|1[0-2](?:[.,]\d)?)\s*J?\s*[x×]\s*(?:1[0-9]|2[0-4])\b"""), " ")
            .replace(Regex("""(?i)\b(?:1[0-9]|2[0-4])\s*[x×]\s*(?:[3-9](?:[.,]\d)?|1[0-2](?:[.,]\d)?)\s*J?\b"""), " ")
            .replace(Regex("""(?i)\b[3456]\s*[x×]\s*(?:100|108|110|112|114(?:[.,]3)?|115|120|127|130|139(?:[.,]7)?)(?=\b|(?:et)?[0-9]{2,3}\b)"""), " ")
            .replace(Regex("""(?i)\b(?:R\s*)?(?:1[0-9]|2[0-4])\s*(?:["”″]|pulg(?:adas?)?|inch(?:es)?)?\b"""), " ")
            .replace(Regex("""(?i)\bET\s*[+-]?\s*\d{1,3}\b"""), " ")
            .replace(Regex("""(?i)\b(llantas?|ruedas?|aros?|wheels?|rims?|felgen?|stahlfelge|stahlfelgen|felgi|felga|alufelgen?|alurader|alu|jantes?|cerchi|alloy|PCD)\b"""), " ")
            .replace(Regex("""[^a-zA-Z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

    private fun isWheelListing(value: String): Boolean =
        containsWheelCategoryTerm(value)

    /**
     * Generic fitment searches can return real alloy-wheel models without a category word.
     * Retain those only as unverified candidates when their title contains wheel specifications
     * or a recognized wheel maker; obvious hubcaps are rejected earlier.
     */
    private fun hasGenericWheelEvidence(value: String): Boolean {
        val hasTechnicalSpec = wheelDiameterInches(value) != null ||
            wheelBoltPattern(value) != null ||
            Regex("""(?i)\bET\s*-?\s*\d{2,3}\b""").containsMatchIn(value) ||
            Regex("""(?i)\b(?:CB|DIA|center\s*bore|nabenbohrung)\s*\d{2,3}(?:[.,]\d)?\b""")
                .containsMatchIn(value)
        val knownWheelMaker = Regex(
            """(?i)\b(oxxo|mak|dezent|alutec|autec|rial|borbet|ronal|aez|oz|bbs|enkei|ats|dotz|keskin|alcar|mwd?|magnetto|platin|rc[\s-]?design|cms|msw|tomason|fondmetal|momo|sparco)\b"""
        ).containsMatchIn(value)
        return hasTechnicalSpec || knownWheelMaker
    }

    private fun wheelDiameterInches(value: String): Int? {
        val patterns = listOf(
            Regex("""(?i)\bR\s*(1[0-9]|2[0-4])\b"""),
            Regex("""(?i)\b(1[0-9]|2[0-4])\s*(?:["”″]|pulg(?:adas?)?|inch(?:es)?)"""),
            // Standard width x diameter notation: 5.5x14, 5.5Jx14 or 6x15.
            Regex("""(?i)\b(?:[3-9](?:[.,]\d)?|1[0-2](?:[.,]\d)?)\s*J?\s*[x×]\s*(1[0-9]|2[0-4])\b"""),
            // Some international listings reverse the order: diameter x width, e.g. 14x6.
            Regex("""(?i)\b(1[0-9]|2[0-4])\s*[x×]\s*(?:[3-9](?:[.,]\d)?|1[0-2](?:[.,]\d)?)\s*J?\b"""),
            Regex("""(?i)\b\d{3}/\d{2}/(1[0-9]|2[0-4])\b""")
        )
        for (pattern in patterns) {
            val match = pattern.find(value) ?: continue
            val group = match.groups.drop(1).firstOrNull { it != null }?.value ?: continue
            group.toIntOrNull()?.let { return it }
        }
        return null
    }

    /** Extracts rim width in inches from both 5.5Jx14 and 14x5.5 notation. */
    private fun wheelWidthInches(value: String): Double? {
        val standard = Regex(
            """(?i)\b([3-9](?:[.,]\d)?|1[0-2](?:[.,]\d)?)\s*J?\s*[x×]\s*(?:1[0-9]|2[0-4])\b"""
        ).find(value)?.groupValues?.getOrNull(1)
        val reversed = if (standard == null) {
            Regex(
                """(?i)\b(?:1[0-9]|2[0-4])\s*[x×]\s*([3-9](?:[.,]\d)?|1[0-2](?:[.,]\d)?)\s*J?\b"""
            ).find(value)?.groupValues?.getOrNull(1)
        } else null
        return (standard ?: reversed)?.replace(',', '.')?.toDoubleOrNull()
    }

    /** Extracts ET/offset values only when explicitly labelled, never from arbitrary numbers. */
    private fun wheelOffsetMm(value: String): Int? =
        Regex("""(?i)\bET\s*([+-]?\s*\d{1,3})\b""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(Regex("""\s+"""), "")
            ?.toIntOrNull()
            ?.takeIf { it in -100..100 }

    private fun formatWheelNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    private fun wheelBoltPattern(value: String): String? {
        // A PCD is the complete bolt pattern (bolt count x pitch-circle diameter).
        // Comparing only "100" would incorrectly treat 4x100 wheels as compatible with 5x100.
        val pattern = Regex(
            """(?i)\b([3456])\s*[x×]\s*(100|108|110|112|114(?:[.,]3)?|115|120|127|130|139(?:[.,]7)?)(?=\b|(?:et)?[0-9]{2,3}\b)"""
        )
        val match = pattern.find(value) ?: return null
        val boltCount = match.groupValues[1].toIntOrNull() ?: return null
        val rawPcd = match.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        val normalizedPcd = if (rawPcd % 1.0 == 0.0) rawPcd.toInt().toString() else rawPcd.toString()
        return "${boltCount}x${normalizedPcd}"
    }

    /**
     * Shoe-size constraints apply even when people omit the word "talla". This prevents a
     * query like "zapatillas nike 41" from accepting offers explicitly listed as size 44/45.
     * A size is compared only when both query and listing look like footwear and the listing
     * actually exposes a size; generic model numbers on listings are not enough.
     */
    private fun isGeneralFootwearSizeSearch(query: String): Boolean {
        val normalized = normalize(query)
        if (!Regex("""\b(zapatillas?|zapatos?|calzado|sneakers?|shoes?)\b""").containsMatchIn(normalized)) {
            return false
        }
        val labelled = Regex(
            """\b(?:talla|size|eu|eur|numero|n[º°o])\s*[:#-]?\s*((?:3[5-9]|4[0-9]|5[0-2])(?:[.,]5)?)\b"""
        )
        val numeric = Regex("""(?<!\d)(?:3[5-9]|4[0-9]|5[0-2])(?:[.,]5)?(?!\d)""")
        val hasSize = labelled.containsMatchIn(normalized) || numeric.containsMatchIn(normalized)
        if (!hasSize) return false

        val genericTerms = setOf(
            "zapatilla", "zapatillas", "zapato", "zapatos", "calzado", "sneaker", "sneakers", "shoe", "shoes",
            "nike", "adidas", "puma", "asics", "reebok", "converse", "vans", "new", "balance",
            "hombre", "mujer", "men", "women", "woman", "unisex", "running", "deportivo", "deportivas",
            "talla", "size", "eu", "eur", "numero", "n", "y", "para", "de", "del", "la", "el"
        )
        val remainingTerms = normalized.split(' ').filter { token ->
            token.isNotBlank() &&
                token !in genericTerms &&
                !numeric.matches(token) &&
                !Regex("""(?:talla|size|eu|eur|numero|n[º°o])""").matches(token)
        }
        return remainingTerms.isEmpty()
    }

    private fun requestedFootwearBrandMatches(query: String, title: String): Boolean {
        val brands = listOf("new balance", "nike", "adidas", "puma", "asics", "reebok", "converse", "vans")
        val normalizedQuery = normalize(query)
        val normalizedTitle = normalize(title)
        val requiredBrand = brands.firstOrNull { normalizedQuery.contains(it) }
        return requiredBrand == null || normalizedTitle.contains(requiredBrand)
    }

    private fun isLikelyFootwearTitle(title: String): Boolean {
        val normalized = normalize(title)
        val explicitCategory = Regex(
            """\b(zapatillas?|zapatos?|calzado|sneakers?|shoes?|running|trail|basketball|football|futbol|tenis)\b"""
        ).containsMatchIn(normalized)
        if (explicitCategory) return true
        // Many feeds omit "zapatillas" from the title but retain the unmistakable model.
        val knownShoeModels = Regex(
            """\b(air\s*max|air\s*force|air\s*jordan|p\s*6000|v5\s*rnr|run\s*defy|downshifter|revolution|pegasus|vomero|court\s*vision|blazer|killshot|dunk|metcon|invincible|sabrina|samba|gazelle|ultraboost|campus|superstar|stan\s*smith|forum)\b"""
        )
        return knownShoeModels.containsMatchIn(normalized)
    }

    private fun hasFootwearSizeMismatch(query: String, title: String): Boolean {
        val footwearPattern = Regex(
            """(?i)\b(zapatillas?|sneakers?|shoes|calzado|nike|adidas|puma|asics|new\s+balance|reebok|converse|vans|air\s+force|air\s+max|jordan)\b"""
        )
        if (!footwearPattern.containsMatchIn(query) || !footwearPattern.containsMatchIn(title)) return false

        fun normalizeSize(value: String?): String? = value
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf { it in 35.0..52.5 }
            ?.let { number -> if (number % 1.0 == 0.0) number.toInt().toString() else number.toString() }

        val labelledSize = Regex(
            """(?i)\b(?:talla|size|eu|eur|numero|n[º°o])\s*[:#-]?\s*((?:3[5-9]|4[0-9]|5[0-2])(?:[.,]5)?)\b"""
        )
        val querySize = normalizeSize(
            labelledSize.findAll(query).lastOrNull()?.groupValues?.getOrNull(1)
                ?: Regex("""(?<!\d)(?:3[5-9]|4[0-9]|5[0-2])(?:[.,]5)?(?!\d)""")
                    .findAll(query).lastOrNull()?.value
        ) ?: return false

        val titleSizeText = labelledSize.findAll(title).lastOrNull()?.groupValues?.getOrNull(1)
            ?: Regex(
                """(?i)(?:[-/|]\s*|\s)((?:3[5-9]|4[0-9]|5[0-2])(?:[.,]5)?)(?:\s+(?:hombre|mujer|men|women|woman|unisex))?\s*$"""
            ).find(title)?.groupValues?.getOrNull(1)
        val titleSize = normalizeSize(titleSizeText) ?: return false
        return querySize != titleSize
    }

    private fun hasVariantMismatch(query: String, title: String): Boolean {
        if (hasFootwearSizeMismatch(query, title)) return true
        val q = query.lowercase(Locale.ROOT).replace(',', '.')
        val t = title.lowercase(Locale.ROOT).replace(',', '.')
        val units = listOf("mah", "wh", "w", "gb", "tb", "ml", "kg", "g", "l", "inch", "pulgadas")
        for (unit in units) {
            val pattern = Regex("""(\d+(?:\.\d+)?)\s*""" + Regex.escape(unit) + """\b""")
            val queryValues = pattern.findAll(q)
                .mapNotNull { it.groupValues[1].toDoubleOrNull() }
                .toList()
            val titleValues = pattern.findAll(t)
                .mapNotNull { it.groupValues[1].toDoubleOrNull() }
                .toList()
            if (queryValues.isEmpty() || titleValues.isEmpty()) continue

            // Products may specify several values in the same unit (for example RAM and storage
            // in GB). Compare all of them, not only the first occurrence. Sorting makes this
            // independent of whether the retailer lists RAM or storage first.
            if (queryValues.size == titleValues.size &&
                queryValues.sorted() != titleValues.sorted()
            ) return true
        }
        val pack = Regex("""\b(\d+)\s*[x×]\s*(\d+(?:\.\d+)?)\s*(ml|g|kg|l)\b""").find(t)
        if (pack != null) {
            val queryHasSamePack = Regex("""\b${pack.groupValues[1]}\s*[x×]\s*${Regex.escape(pack.groupValues[2])}\s*${Regex.escape(pack.groupValues[3])}\b""").containsMatchIn(q)
            val queryHasSingleUnit = Regex("""\b${Regex.escape(pack.groupValues[2])}\s*${Regex.escape(pack.groupValues[3])}\b""").containsMatchIn(q)
            if (!queryHasSamePack && queryHasSingleUnit) return true
        }
        return false
    }

    private fun isClearlyNonNewListing(item: JSONObject, title: String): Boolean {
        val excludedTerms = Regex(
            """(?i)(?:\b(segunda[\s_-]+mano|second[\s_-]*hand|pre[\s_-]*owned|used|usad[oa]s?|seminuev[oa]s?|reacondicionad[oa]s?|refurbished|renewed|open[\s_-]*box|alquiler|rental|renting|rent|leasing|lease|hire|financiacion|financiar|financing|subscription|suscripcion|monthly|mensual|cuota|pago\s+a\s+plazos|por\s+mes|per\s+month|mes\s+a\s+mes)\b|/\s*mes\b)"""
        )
        if (excludedTerms.containsMatchIn(title)) return true

        // SerpApi may expose the condition outside the title. Check the commonly returned fields
        // without treating an explicitly "new" condition as used.
        val conditionText = listOf(
            "second_hand_condition",
            "condition",
            "item_condition",
            "product_condition",
            "condition_description"
        ).mapNotNull { key ->
            val value = item.opt(key)
            when (value) {
                null, JSONObject.NULL -> null
                is JSONObject, is JSONArray -> value.toString()
                else -> value.toString().takeUnless { it.isBlank() || it == "null" }
            }
        }.joinToString(" ")
        return excludedTerms.containsMatchIn(conditionText)
    }

    private fun isHttpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun isAmazonSpainUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.").orEmpty()
        uri.scheme.equals("https", ignoreCase = true) &&
            (host == "amazon.es" || host.endsWith(".amazon.es"))
    }.getOrDefault(false)

    private fun isPlausibleProductMatch(requested: String, returned: String): Boolean {
        // "Teclado Hall"/"teclado efecto Hall" is a technology search, not a literal
        // two-word product name. Match Hall/HE/TMR keyboards without accepting "Hall of Fame".
        if (isHallEffectKeyboardSearch(requested)) return isHallEffectKeyboardResult(returned)

        // Broad footwear searches with a size should not throw away every listing that omits
        // the size in its title: many retailers offer a size selector on the product page.
        // Explicitly wrong sizes have already been rejected by hasVariantMismatch() upstream.
        if (isGeneralFootwearSizeSearch(requested) &&
            isLikelyFootwearTitle(returned) &&
            requestedFootwearBrandMatches(requested, returned)
        ) return true

        val orderedWanted = normalize(requested)
            .replace(Regex("(\\d+)\\s?(gb|tb|mb)\\b"), "$1 $2")
            .split(' ')
            .filter { it.length > 1 && it !in STOP_WORDS }
            .distinct()
        val wanted = orderedWanted.toSet()
        val found = tokens(returned)
        if (wanted.isEmpty() || found.isEmpty()) return false

        // If the query specifies a year and the listing specifies a different year, don't mix
        // seasonal editions (e.g. an Advent calendar for 2024 with a 2026 edition).
        val requestedYears = Regex("""\b(?:19|20)\d{2}\b""").findAll(normalize(requested))
            .map { it.value }.toSet()
        val returnedYears = Regex("""\b(?:19|20)\d{2}\b""").findAll(normalize(returned))
            .map { it.value }.toSet()
        if (requestedYears.isNotEmpty() && returnedYears.isNotEmpty() &&
            requestedYears.intersect(returnedYears).isEmpty()
        ) return false

        val containsModelCode = wanted.any { token -> token.length >= 4 && token.any(Char::isDigit) && token.any(Char::isLetter) }

        // Permit a small number of spelling mistakes in longer brand/product tokens, without
        // relaxing model-code checks or allowing short generic words to match unrelated items.
        fun editDistance(left: String, right: String): Int {
            var previous = IntArray(right.length + 1) { it }
            for (i in left.indices) {
                val current = IntArray(right.length + 1)
                current[0] = i + 1
                for (j in right.indices) {
                    val substitution = if (left[i] == right[j]) 0 else 1
                    current[j + 1] = minOf(
                        current[j] + 1,
                        previous[j + 1] + 1,
                        previous[j] + substitution
                    )
                }
                previous = current
            }
            return previous[right.length]
        }

        fun tokenMatches(token: String): Boolean = found.any { candidate ->
            when {
                token == candidate -> true
                // Never use typo-tolerance for model codes, storage numbers or edition years.
                // WH-1000XM4 and WH-1000XM5 differ by one character but are different products.
                token.any(Char::isDigit) -> candidate.endsWith(token)
                token.length >= 7 && candidate.length >= 7 &&
                    editDistance(token, candidate) <= minOf(2, token.length / 4) -> true
                containsModelCode && token.length in 2..3 &&
                    candidate.length >= token.length + 3 && candidate.startsWith(token) -> true
                token.length >= 4 && candidate.length >= 4 &&
                    (candidate.contains(token) || token.contains(candidate)) &&
                    minOf(token.length, candidate.length).toDouble() /
                        maxOf(token.length, candidate.length).toDouble() >= 0.65 -> true
                else -> false
            }
        }

        // For longer product-specific queries, at least one of the first two meaningful
        // terms must match. This protects brand/model searches from generic category matches
        // such as another brand's advent calendar, even if several generic words overlap.
        if (orderedWanted.size >= 4) {
            // Ignore category words when choosing identity anchors. A user may start with
            // "calendario de adviento" before naming the brand; those generic words must not
            // let a different brand (or even a chocolate calendar) pass as the requested item.
            val genericAnchorTerms = setOf(
                "calendario", "calendarios", "calendar", "calendars", "advent",
                "adviento", "adventskalender", "adventskalenders", "beauty",
                "auriculares", "headphones", "headphone", "wireless", "inalambricos",
                "inalambrico", "portatil", "laptop", "smartphone", "movil", "telefono",
                "teclado", "keyboard", "raton", "mouse", "cargador", "charger",
                "bateria", "battery", "power", "bank", "perfume", "perfumes",
                "crema", "cream", "skin", "hombre", "hombres", "mujer", "mujeres",
                "men", "women", "frauen", "damen", "mann", "kosmetik", "pflege",
                "galaxy", "pixel", "iphone", "redmi", "poco"
            )
            // Keep explicit model/edition numbers even when short (S26, 17, 2024), and
            // combine them with up to two distinctive text anchors. Otherwise "Samsung Galaxy
            // S26" could accidentally match "Samsung Galaxy S25" because the first two tokens
            // identify only the brand/family.
            val modelAnchors = orderedWanted.filter { token ->
                token.length >= 2 &&
                    token !in genericAnchorTerms &&
                    (token.any(Char::isLetter) && token.any(Char::isDigit) ||
                        Regex("""(?:19|20)\d{2}""").matches(token))
            }
            val textAnchors = orderedWanted.filter { token ->
                token.length >= 4 && token !in genericAnchorTerms && !token.any(Char::isDigit)
            }.take(2)
            val identityAnchors = (textAnchors + modelAnchors).distinct()
            // Every selected identity anchor must match. Matching only the brand is not
            // enough when the query also names an exact model or edition.
            if (identityAnchors.any { anchor -> !tokenMatches(anchor) }) return false
        }

        val overlap = wanted.count(::tokenMatches)
        val required = if (wanted.size <= 2) wanted.size else maxOf(2, ceil(wanted.size * 0.45).toInt())
        if (overlap >= required) return true

        // Product codes such as WH-1000XM5 can be split differently by retailer titles.
        val compactQuery = normalize(requested).replace(" ", "")
        val compactTitle = normalize(returned).replace(" ", "")
        return compactQuery.length >= 8 && compactTitle.length >= 8 &&
            (compactTitle.contains(compactQuery) || compactQuery.contains(compactTitle))
    }

    private fun isHallEffectKeyboardSearch(value: String): Boolean {
        val normalized = normalize(value)
        val namesKeyboard = Regex("""\b(teclado|teclados|keyboard|keyboards)\b""")
            .containsMatchIn(normalized)
        val namesHallTechnology = Regex("""\b(hall|hal|efecto|effect|he|tmr|magnetic|magnetico|magnetica)\b""")
            .containsMatchIn(normalized)
        return namesKeyboard && namesHallTechnology
    }

    private fun isHallEffectKeyboardResult(value: String): Boolean {
        val normalized = normalize(value)
        val namesKeyboard = Regex("""\b(teclado|teclados|keyboard|keyboards)\b""")
            .containsMatchIn(normalized)
        if (!namesKeyboard) return false

        val explicitTechnology = Regex(
            """\b(hall\s*effect|effect\s*hall|efecto\s*hall|hall\s*sensor|sensor\s*hall|magnetic|magnetico|magnetica|magneticos|magneticas|tmr|rapid\s+trigger)\b"""
        ).containsMatchIn(normalized)
        val explicitHeLabel = Regex("""\bhe\b""").containsMatchIn(normalized)
        return explicitTechnology || explicitHeLabel
    }

    private fun tokens(value: String): Set<String> =
        normalize(value)
            .replace(Regex("(\\d+)\\s?(gb|tb|mb)\\b"), "$1 $2")
            .split(' ')
            .filter { it.length > 1 && it !in STOP_WORDS }
            .toSet()

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("""\bplaystation\s*5\b"""), "ps5")
            .replace(Regex("\\s+"), " ")

    private fun storeIdForHost(host: String, merchantName: String = ""): String {
        if (isGoogleShoppingUrl("https://$host")) {
            val merchant = normalize(merchantName)
            val knownStoreId = when {
                "amazon" in merchant -> "amazon_es"
                merchant == "game" || merchant == "game es" || "game espana" in merchant -> "game"
                "pccomponentes" in merchant -> "pccomponentes"
                "mediamarkt" in merchant -> "mediamarkt"
                "el corte ingles" in merchant -> "elcorteingles"
                "carrefour" in merchant -> "carrefour"
                "fnac" in merchant -> "fnac"
                "alcampo" in merchant -> "alcampo"
                "leroy merlin" in merchant -> "leroymerlin"
                "decathlon" in merchant -> "decathlon"
                "worten" in merchant -> "worten"
                "miravia" in merchant -> "miravia"
                "conforama" in merchant -> "conforama"
                "bauhaus" in merchant -> "bauhaus"
                "ikea" in merchant -> "ikea"
                "lidl" in merchant -> "lidl"
                "aliexpress" in merchant -> "aliexpress_plaza"
                "sprinter" in merchant -> "sprinter"
                "powerplanet" in merchant -> "powerplanet"
                "apple" in merchant -> "apple_store"
                "samsung" in merchant -> "samsung_store"
                "xiaomi" in merchant -> "xiaomi_store"
                "lenovo" in merchant -> "lenovo_store"
                "dell" in merchant -> "dell_store"
                "hp store" in merchant -> "hp_store"
                "anker" in merchant -> "anker_store"
                "ugreen" in merchant -> "ugreen_store"
                else -> null
            }
            if (knownStoreId != null) return knownStoreId
            val slug = merchant.replace(Regex("[^a-z0-9]+"), "_").trim('_')
            return "retailer_${slug.ifBlank { "google_shopping" }}"
        }
        return host.split('.').joinToString("_").replace(Regex("[^a-zA-Z0-9_]"), "_")
            .let { "retailer_$it" }
    }

    private fun humanizeHost(host: String): String =
        host.substringBefore('.')
            .replace('-', ' ')
            .replace('_', ' ')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("es", "ES")) else it.toString() }

    private companion object {
        const val MAX_PRODUCTS_PER_BATCH = 40
        const val MAX_PRICE_INSIGHT_LOOKBACK_DAYS = 1605L
        const val MIN_PRICE_INSIGHT_EUR = 0.50
        const val MAX_PRICE_INSIGHT_EUR = 10_000.0
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val MAX_ONLINE_RESULTS = 100
        const val INTER_REQUEST_DELAY_MS = 1_200L
        val STOP_WORDS = setOf(
            "the", "and", "for", "with", "in", "on", "de", "del", "la", "el", "los", "las",
            "para", "con", "por", "nuevo", "nueva", "original", "oficial", "gb", "tb", "mb"
        )
    }
}
