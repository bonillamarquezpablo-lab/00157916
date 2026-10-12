package com.example.data.remote

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

class SerpApiException(
    message: String,
    val httpCode: Int? = null
) : Exception(message)

/**
 * Queries Google Shopping results through SerpApi for Spain.
 *
 * Each product query consumes one search credit; this client caps a batch at 40 queries so a
 * single manual refresh stays below the free-plan throughput advertised by SerpApi. Nothing runs
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
    suspend fun searchProducts(
        token: String,
        products: List<SerpApiRequestProduct>,
        onStage: suspend (String) -> Unit = {}
    ): List<SerpApiQuote> = withContext(Dispatchers.IO) {
        val apiKey = token.trim()
        require(apiKey.isNotEmpty()) { "Guarda primero tu clave de SerpApi en Ajustes." }

        val requested = products
            .filter { it.id.isNotBlank() && it.queryTerm.isNotBlank() }
            .distinctBy { it.id }
            .take(MAX_PRODUCTS_PER_BATCH)

        if (requested.isEmpty()) return@withContext emptyList()

        val quotes = mutableListOf<SerpApiQuote>()
        requested.forEachIndexed { index, product ->
            onStage("Consultando Google Shopping España: ${index + 1}/${requested.size}…")
            val json = executeSearch(apiKey, product.queryTerm)
            quotes += parseQuotes(json, product)
            // Spread requests to avoid unnecessarily bursting the provider.
            if (index < requested.lastIndex) delay(INTER_REQUEST_DELAY_MS)
        }
        quotes
            .distinctBy { "${it.productId}|${it.storeId}|${it.price}|${it.sourceUrl}" }
            .sortedWith(compareBy<SerpApiQuote> { it.productId }.thenBy { it.price })
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

    private suspend fun executeSearch(apiKey: String, query: String): JSONObject {
        val url = buildUrl(apiKey, query)
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
