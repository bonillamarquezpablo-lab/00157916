package com.example.data.remote

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
import java.util.Locale
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
    val sourceName: String = "SerpApi · Google Shopping España"
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
    val queriesExecuted: Int = 1
) {
    fun emptyResultMessage(query: String): String {
        if (rawResultCount == 0) {
            return "Google Shopping no devolvió productos para «$query». Prueba con el modelo exacto o inténtalo más tarde; no se ha guardado ningún precio."
        }
        val reasons = mutableListOf<String>()
        if (rejectedAsIrrelevant > 0) reasons += "$rejectedAsIrrelevant no coincidían suficientemente con la búsqueda"
        if (rejectedForPriceOrCurrency > 0) reasons += "$rejectedForPriceOrCurrency no tenían un precio en euros verificable"
        if (rejectedForLink > 0) reasons += "$rejectedForLink no tenían un enlace HTTPS válido"
        if (rejectedOther > 0) reasons += "$rejectedOther estaban incompletos"
        val detail = reasons.joinToString("; ").ifBlank { "ninguno pasó los filtros de seguridad" }
        val fieldDiagnostic = if (
            results.isEmpty() &&
            rejectedForLink > 0 &&
            rejectedForLink == rawResultCount &&
            firstResultFields.isNotBlank()
        ) " Campos recibidos: $firstResultFields." else ""
        return "Google Shopping devolvió $rawResultCount resultados para «$query», pero ninguno era utilizable: $detail.$fieldDiagnostic No se ha guardado ningún precio."
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
 * checks the actual account balance/rate window before catalog batches. Nothing runs
 * automatically in the background. Results represent a Google Shopping snapshot, not a direct
 * retailer API guarantee; prices and availability must be rechecked at the seller before purchase.
 *
 * The user's API key is supplied at runtime and stored encrypted with Android Keystore. This is
 * suitable only for a private single-device prototype: a public/multi-user release needs a
 * backend proxy and must not expose one user's API key in the APK.
 */
class SerpApiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
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
        query: String,
        relevanceQuery: String = query
    ): SerpApiShoppingSearchReport = withContext(Dispatchers.IO) {
        val apiKey = token.trim()
        require(apiKey.isNotEmpty()) { "Guarda primero tu clave de SerpApi en Ajustes." }
        val normalizedQuery = query.trim().replace(Regex("\\s+"), " ").take(120)
        require(normalizedQuery.length >= 2) { "Escribe al menos 2 caracteres para buscar." }
        parseShoppingResultsDetailed(executeSearch(apiKey, normalizedQuery), relevanceQuery.trim().ifBlank { normalizedQuery })
    }

    /**
     * When the first Google Shopping query returns few acceptable offers, run up to two
     * controlled alternate phrasings and merge genuine merchant listings. The caller supplies
     * the available quota so the method never makes more requests than allowed.
     */
    suspend fun searchOnlineWithExpandedDiagnostics(
        token: String,
        query: String,
        maxAdditionalQueries: Int
    ): SerpApiShoppingSearchReport {
        val normalizedQuery = query.trim().replace(Regex("\\s+"), " ").take(120)
        val initial = searchOnlineWithDiagnostics(token, normalizedQuery)
        if (maxAdditionalQueries <= 0 || initial.results.size >= EXPAND_BELOW_RESULT_COUNT) {
            return initial
        }

        val followUps = additionalShoppingQueries(normalizedQuery).take(maxAdditionalQueries.coerceAtMost(MAX_ADDITIONAL_SEARCHES))
        if (followUps.isEmpty()) return initial

        val reports = mutableListOf(initial)
        for (alternateQuery in followUps) {
            // Keep the original query as the relevance anchor so alternate wording does not
            // cause otherwise valid product titles to be rejected.
            delay(EXPANDED_QUERY_DELAY_MS)
            reports += searchOnlineWithDiagnostics(token, alternateQuery, relevanceQuery = normalizedQuery)
            val uniqueCount = reports.flatMap { it.results }
                .distinctBy { offerIdentity(it) }.size
            if (uniqueCount >= TARGET_EXPANDED_RESULT_COUNT) break
        }

        val combined = reports.flatMap { it.results }
            .distinctBy { offerIdentity(it) }
            .sortedBy { it.price }
            .take(MAX_ONLINE_RESULTS)
        return SerpApiShoppingSearchReport(
            results = combined,
            rawResultCount = reports.sumOf { it.rawResultCount },
            rejectedAsIrrelevant = reports.sumOf { it.rejectedAsIrrelevant },
            rejectedForPriceOrCurrency = reports.sumOf { it.rejectedForPriceOrCurrency },
            rejectedForLink = reports.sumOf { it.rejectedForLink },
            rejectedOther = reports.sumOf { it.rejectedOther },
            firstResultFields = initial.firstResultFields,
            queriesExecuted = reports.size
        )
    }

    private fun additionalShoppingQueries(query: String): List<String> {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        val lower = normalized.lowercase(Locale.ROOT)
        val modelCode = Regex("""\b[a-z]{1,8}\s*\d[a-z0-9-]{1,12}\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
        val candidates = when {
            lower.contains("logitech") && Regex("""\bg\s*\d{2,4}\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) ->
                listOf("$normalized Lightsync", "$normalized gaming mouse")
            modelCode ->
                listOf("$normalized ofertas", "$normalized precio en tiendas")
            else ->
                listOf("$normalized ofertas", "$normalized en tiendas")
        }
        return candidates.map { it.trim().take(120) }
            .filter { it.length >= 2 && !it.equals(normalized, ignoreCase = true) }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }

    private fun offerIdentity(result: SerpApiShoppingResult): String =
        "\${result.storeName.trim().lowercase(Locale.ROOT)}|\${result.price}|\${result.sourceUrl.trim().lowercase(Locale.ROOT)}"

    /**
     * Search Amazon.es itself, rather than Google Shopping. This still returns a public search
     * snapshot through SerpApi; it cannot see the user's signed-in account or personalized coupons.
     */
    suspend fun searchAmazonOnline(token: String, query: String): List<SerpApiShoppingResult> =
        withContext(Dispatchers.IO) {
            val apiKey = token.trim()
            require(apiKey.isNotEmpty()) { "Guarda primero tu clave de SerpApi en Ajustes." }
            val normalizedQuery = query.trim().replace(Regex("\\s+"), " ").take(120)
            require(normalizedQuery.length >= 2) { "Escribe al menos 2 caracteres para buscar." }
            parseAmazonResults(executeAmazonSearch(apiKey, normalizedQuery), query = normalizedQuery)
        }

    internal fun parseAmazonResults(
        root: JSONObject,
        query: String? = null,
        observedAt: Long = System.currentTimeMillis()
    ): List<SerpApiShoppingResult> {
        val error = root.optString("error").takeIf { it.isNotBlank() && it != "null" }
        if (error != null) throw SerpApiException(error)
        val status = root.optJSONObject("search_metadata")?.optString("status")
        if (status.equals("Error", ignoreCase = true)) {
            throw SerpApiException("SerpApi no pudo completar la búsqueda en Amazon.es.")
        }

        val items = root.optJSONArray("organic_results")?.toObjects().orEmpty()
        return items.mapNotNull { item ->
            val title = item.optString("title").trim()
                .takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            // Amazon's engine must obey the same query relevance rules as Google Shopping.
            if ((!query.isNullOrBlank() && !isPlausibleProductMatch(query, title)) ||
                isNonRetailPromotionalListing(title) ||
                isDisallowedListingCondition(item, title)
            ) return@mapNotNull null
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
            if (price == null || !price.isFinite() || price <= 0.0) return@mapNotNull null
            val isEuroPrice = priceText.contains("€") ||
                priceText.contains("EUR", ignoreCase = true)
            // A structured numeric price is useful even when SerpApi omits the formatted label,
            // but never reinterpret an explicitly labelled foreign currency as euros.
            val explicitlyForeignCurrency =
                listOf('$', '£', '¥', '₹').any { priceText.contains(it) } ||
                    listOf("USD", "GBP", "CAD", "AUD", "NZD", "JPY", "INR", "CHF")
                        .any { currency -> priceText.contains(currency, ignoreCase = true) }
            if (explicitlyForeignCurrency) return@mapNotNull null
            if (extractedPrice == null && !isEuroPrice) return@mapNotNull null

            val url = sequenceOf(item.optString("link_clean"), item.optString("link"))
                .firstOrNull { isAmazonSpainUrl(it) } ?: return@mapNotNull null
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
        val parsed = items.mapNotNull { item ->
            val title = item.optString("title").trim()
                .takeIf { it.isNotBlank() && it != "null" }
                ?: run {
                    rejectedOther++
                    return@mapNotNull null
                }
            if (!query.isNullOrBlank() && !isPlausibleProductMatch(query, title)) {
                rejectedAsIrrelevant++
                return@mapNotNull null
            }
            if (isDisallowedListingCondition(item, title)) {
                rejectedOther++
                return@mapNotNull null
            }
            if (isNonRetailPromotionalListing(title)) {
                rejectedOther++
                return@mapNotNull null
            }

            val priceText = item.optString("price")
            val extractedPrice = item.opt("extracted_price")
                ?.takeIf { it !is JSONObject && it !is JSONArray }
                ?.toString()
                ?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
            if (hasExplicitForeignCurrency(priceText)) {
                rejectedForPriceOrCurrency++
                return@mapNotNull null
            }
            val price = extractedPrice ?: parsePrice(priceText)
            val isEuroPrice = priceText.contains("€") ||
                priceText.contains("EUR", ignoreCase = true)
            if (price == null || !price.isFinite() || price <= 0.0 ||
                (extractedPrice == null && !isEuroPrice)
            ) {
                rejectedForPriceOrCurrency++
                return@mapNotNull null
            }

            // Prefer a real retailer URL whenever SerpApi supplies one. Standard Google Shopping
            // product_link values often point to Google itself, so retain those only as a clearly
            // labelled fallback rather than pretending they're direct seller links.
            val candidateUrls = sequenceOf(
                item.optString("link"),
                item.optString("link_clean"),
                item.optString("product_link")
            ).filter(::isHttpsUrl).toList()
            val url = candidateUrls.firstOrNull { !isGoogleDomainUrl(it) }
                ?: candidateUrls.firstOrNull { isGoogleDomainUrl(it) }
                ?: "https://www.google.com/search".toHttpUrl().newBuilder()
                    .addQueryParameter("tbm", "shop")
                    .addQueryParameter("hl", "es")
                    .addQueryParameter("gl", "es")
                    .addQueryParameter("q", title)
                    .build()
                    .toString()
            val host = runCatching { URI(url).host.lowercase(Locale.ROOT).removePrefix("www.") }
                .getOrNull().orEmpty()
            if (host.isBlank()) {
                rejectedForLink++
                return@mapNotNull null
            }
            val source = item.optString("source").trim()
                .takeIf { it.isNotBlank() && it != "null" } ?: humanizeHost(host)
            val thumbnail = item.optString("thumbnail").takeIf { isHttpsUrl(it) }.orEmpty()
            val delivery = sequenceOf(item.optString("delivery"), item.optString("shipping"))
                .firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()
            val rating = item.opt("rating")?.toString()?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in 0.0..5.0 }
            val reviewCount = item.opt("reviews")?.toString()
                ?.replace(Regex("[^0-9]"), "")
                ?.toIntOrNull()
                ?.takeIf { it >= 0 }

            SerpApiShoppingResult(
                title = title,
                price = price,
                storeName = source,
                sourceUrl = url,
                thumbnailUrl = thumbnail,
                delivery = delivery,
                rating = rating,
                reviewCount = reviewCount,
                observedAt = observedAt
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
            firstResultFields = items.firstOrNull()
                ?.keys()
                ?.asSequence()
                ?.joinToString(", ")
                .orEmpty()
        )
    }

    suspend fun searchProducts(
        token: String,
        products: List<SerpApiRequestProduct>,
        onStage: suspend (String) -> Unit = {}
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
            if (!isPlausibleProductMatch(requested.queryTerm, title) ||
                isNonRetailPromotionalListing(title) ||
                isDisallowedListingCondition(item, title)
            ) return@mapNotNull null

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

            // SerpApi can expose a direct merchant URL as link/link_clean and a Google
            // product page as product_link. Prefer a non-Google HTTPS destination whenever one
            // exists; use the Google product page only as an explicitly labelled fallback.
            val candidateUrls = sequenceOf(
                item.optString("link_clean"),
                item.optString("link"),
                item.optString("product_link")
            ).filter(::isHttpsUrl).toList()
            val url = candidateUrls.firstOrNull { !isGoogleDomainUrl(it) }
                ?: candidateUrls.firstOrNull()
                ?: return@mapNotNull null
            val host = runCatching { URI(url).host.lowercase(Locale.ROOT).removePrefix("www.") }
                .getOrNull().orEmpty()
            if (host.isBlank()) return@mapNotNull null

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
                sourceName = "SerpApi · Google Shopping España"
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
            // Ask SerpApi for the widest supported Shopping response; its current layout may
            // still cap results itself, but the app no longer throws away results after 30.
            .addQueryParameter("num", MAX_ONLINE_RESULTS.toString())
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

    private fun isGoogleDomainUrl(value: String): Boolean = runCatching {
        val host = URI(value).host?.lowercase(Locale.ROOT)?.removePrefix("www.").orEmpty()
        host.startsWith("google.") || host.endsWith(".google.com") || host.endsWith(".google.es")
    }.getOrDefault(false)

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
        if (isIrrelevantMultiProductBundle(requested, returned)) return false
        val wanted = tokens(requested)
        val found = tokens(returned)
        if (wanted.isEmpty() || found.isEmpty()) return false

        // If the query identifies a model, require compatible model evidence before allowing
        // fuzzy token overlap. Otherwise a search for Redmi 17 could accidentally attach a Redmi A5.
        val wantedModels = modelMarkers(requested)
        val foundModels = modelMarkers(returned)
        if (wantedModels.isNotEmpty() &&
            (foundModels.isEmpty() || wantedModels.intersect(foundModels).isEmpty())
        ) return false

        val variantsWanted = variantMarkers(requested)
        val variantsFound = variantMarkers(returned)
        // Requested variant evidence must be present in the result; a generic title cannot
        // safely stand in for an explicitly requested Ultra/FE/Pro/Max configuration.
        if (variantsWanted.isNotEmpty() && !variantsFound.containsAll(variantsWanted)) return false

        // Explicit RAM/storage details must also match. Unknown memory is not proof of a match.
        val memoryWanted = memoryMarkers(requested)
        val memoryFound = memoryMarkers(returned)
        if (memoryWanted.isNotEmpty() && !memoryFound.containsAll(memoryWanted)) return false

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

        val overlap = wanted.count(::tokenMatches)
        val required = if (wanted.size <= 2) wanted.size else maxOf(2, ceil(wanted.size * 0.45).toInt())
        if (overlap >= required) return true

        // Product codes such as WH-1000XM5 can be split differently by retailer titles.
        val compactQuery = normalize(requested).replace(" ", "")
        val compactTitle = normalize(returned).replace(" ", "")
        return compactQuery.length >= 8 && compactTitle.length >= 8 &&
            (compactTitle.contains(compactQuery) || compactQuery.contains(compactTitle))
    }

    /** Reject condition metadata that identifies a non-new item even when the title omits it. */
    private fun isDisallowedListingCondition(item: JSONObject, title: String): Boolean {
        val conditionFields = listOf(
            "condition", "item_condition", "product_condition", "condition_label",
            "condition_description", "conditionDescription"
        ).mapNotNull { key ->
            item.opt(key)?.takeIf { it !is JSONObject && it !is JSONArray }
                ?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        }.joinToString(" ")
        val evidence = normalize("$title $conditionFields")
        return Regex(
            """\b(segunda mano|usado|usada|usados|usadas|used|reacondicionad|refurbished|renewed|pre owned|seminuevo|seminueva|open box|caja abierta|alquiler|renting|lease|leasing|cuota mensual|pago mensual|subscription)\b"""
        ).containsMatchIn(evidence)
    }

    /**
     * Mínimo tracks consumer retail prices, not branded giveaways/custom merchandise
     * whose price often reflects bulk printing or minimum order quantities.
     */
    private fun isNonRetailPromotionalListing(title: String): Boolean {
        val normalized = normalize(title)
        return Regex(
            """\b(impreso con (su|el|tu) logotipo|con (su|tu) logo|regalo promocional|articulo publicitario|merchandising personalizado|personalizado con logo|para publicidad|precio por mayor|lote promocional)\b"""
        ).containsMatchIn(normalized)
    }

    private fun modelMarkers(value: String): Set<String> {
        val words = normalize(value).split(' ').filter { it.isNotBlank() }
        val markers = linkedSetOf<String>()
        val families = setOf("redmi", "poco", "iphone", "pixel", "galaxy", "wh", "dyson", "roborock", "playstation", "xbox")
        for (index in words.indices) {
            val word = words[index]
            if (word in families) {
                words.getOrNull(index + 1)?.let { next ->
                    if (Regex("""^[a-z]{0,3}\d+[a-z0-9]*$""").matches(next) &&
                        !Regex("""^\d+(gb|tb|mah|mp|hz|w|mm|cm|g)$""").matches(next)
                    ) markers += word + next
                }
            }
            if (Regex("""^[a-z]{1,8}\d[a-z0-9]{1,12}$""").matches(word) &&
                word !in setOf("usb20", "usb30", "usb32", "wifi6", "wifi7", "typec") &&
                !Regex("""^\d+(gb|tb|mah|mp|hz|w|mm|cm|g)$""").matches(word)
            ) markers += word
        }
        return markers
    }

    private fun variantMarkers(value: String): Set<String> {
        val words = tokens(value)
        return words.intersect(setOf("pro", "max", "plus", "ultra", "fe", "lite", "mini", "fold", "flip", "classic", "edge"))
    }

    private fun memoryMarkers(value: String): Set<String> {
        val standardized = Regex(
            """(?i)\b(\d{1,2})\s*(?:gb)?\s*[+/]\s*(\d{2,4})\s*gb\b"""
        ).replace(value) { match ->
            "${match.groupValues[1]}GB ${match.groupValues[2]}GB"
        }
        return Regex("""\b(\d{1,4})\s?(gb|tb)\b""")
            .findAll(normalize(standardized))
            .map { it.groupValues[1] + it.groupValues[2] }
            .toSet()
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
            .replace(Regex("\\s+"), " ")

    private fun storeIdForHost(host: String, merchantName: String = ""): String {
        if (host == "google.com" || host.endsWith(".google.com")) {
            val merchant = normalize(merchantName)
            val knownStoreId = when {
                "amazon" in merchant -> "amazon_es"
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
        const val MAX_ONLINE_RESULTS = 100
        const val EXPAND_BELOW_RESULT_COUNT = 12
        const val TARGET_EXPANDED_RESULT_COUNT = 15
        const val MAX_ADDITIONAL_SEARCHES = 2
        const val EXPANDED_QUERY_DELAY_MS = 350L
        const val INTER_REQUEST_DELAY_MS = 1_200L
        val STOP_WORDS = setOf(
            "the", "and", "for", "with", "in", "on", "de", "del", "la", "el", "los", "las",
            "para", "con", "por", "nuevo", "nueva", "original", "oficial", "gb", "tb", "mb"
        )
    }
}
