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
    val failedQueries: Int
)

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
        withContext(Dispatchers.IO) {
            val apiKey = token.trim()
            require(apiKey.isNotEmpty()) { "Guarda primero tu clave de SerpApi en Ajustes." }
            val normalizedQuery = query.trim().replace(Regex("\\s+"), " ").take(120)
            require(normalizedQuery.length >= 2) { "Escribe al menos 2 caracteres para buscar." }
            parseShoppingResults(executeSearch(apiKey, normalizedQuery))
        }

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
            parseAmazonResults(executeAmazonSearch(apiKey, normalizedQuery))
        }

    internal fun parseAmazonResults(
        root: JSONObject,
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
            if (extractedPrice == null &&
                !priceText.contains("€") && !priceText.contains("EUR", ignoreCase = true)
            ) return@mapNotNull null

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
            .take(30)
    }

    internal fun parseShoppingResults(
        root: JSONObject,
        observedAt: Long = System.currentTimeMillis()
    ): List<SerpApiShoppingResult> {
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

        return items.mapNotNull { item ->
            val title = item.optString("title").trim()
                .takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val priceText = item.optString("price")
            if (!priceText.contains("€") && !priceText.contains("EUR", ignoreCase = true)) {
                return@mapNotNull null
            }
            val price = parsePrice(
                item.opt("extracted_price")?.takeIf { it !is JSONObject && it !is JSONArray }
                    ?: priceText
            ) ?: return@mapNotNull null
            val url = item.optString("link").takeIf { isHttpsUrl(it) }
                ?: return@mapNotNull null
            val host = runCatching { URI(url).host.lowercase(Locale.ROOT).removePrefix("www.") }
                .getOrNull().orEmpty()
            if (host.isBlank()) return@mapNotNull null
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
            .take(30)
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
        var firstFailure: Exception? = null
        requested.forEachIndexed { index, product ->
            onStage("Consultando Google Shopping España: ${index + 1}/${requested.size}…")
            try {
                val json = executeSearch(apiKey, product.queryTerm)
                quotes += parseQuotes(json, product)
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
            val providerFailure = firstFailure as? SerpApiException
            if (providerFailure != null) throw providerFailure
            throw SerpApiException(
                "No se pudo consultar ningún producto (${failedQueries} de ${requested.size} consultas fallidas)."
            )
        }

        SerpApiBatchResult(
            quotes = quotes
                .distinctBy { "${it.productId}|${it.storeId}|${it.price}|${it.sourceUrl}" }
                .sortedWith(compareBy<SerpApiQuote> { it.productId }.thenBy { it.price }),
            requestedQueries = requested.size,
            failedQueries = failedQueries
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
            if (!isPlausibleProductMatch(requested.queryTerm, title)) return@mapNotNull null

            val priceText = item.optString("price")
            // Google Shopping can show other currencies depending on the result. Since this app is
            // targeting Spain, require an explicit euro marker before accepting a quote.
            if (!priceText.contains("€") && !priceText.contains("EUR", ignoreCase = true)) {
                return@mapNotNull null
            }
            val price = parsePrice(
                item.opt("extracted_price")?.takeIf { it !is JSONObject && it !is JSONArray }
                    ?: priceText
            ) ?: return@mapNotNull null

            val url = item.optString("link").takeIf { isHttpsUrl(it) }
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
                storeId = storeIdForHost(host),
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
        val wanted = tokens(requested)
        val found = tokens(returned)
        if (wanted.isEmpty() || found.isEmpty()) return false
        val overlap = wanted.intersect(found).size
        val required = if (wanted.size <= 2) wanted.size else maxOf(2, ceil(wanted.size * 0.45).toInt())
        return overlap >= required
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

    private fun storeIdForHost(host: String): String =
        host.split('.').joinToString("_").replace(Regex("[^a-zA-Z0-9_]"), "_")
            .let { "retailer_$it" }

    private fun humanizeHost(host: String): String =
        host.substringBefore('.')
            .replace('-', ' ')
            .replace('_', ' ')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("es", "ES")) else it.toString() }

    private companion object {
        const val MAX_PRODUCTS_PER_BATCH = 40
        const val INTER_REQUEST_DELAY_MS = 1_200L
        val STOP_WORDS = setOf(
            "the", "and", "for", "with", "in", "on", "de", "del", "la", "el", "los", "las",
            "para", "con", "por", "nuevo", "nueva", "original", "oficial", "gb", "tb", "mb"
        )
    }
}
