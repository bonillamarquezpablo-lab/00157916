package com.example.data.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class CachedOnlineSearch(
    val results: List<SerpApiShoppingResult>,
    val savedAt: Long,
    /** The original provider response is kept for reclassification without another search. */
    val rawResponse: String? = null
)

/**
 * Stores public product search snapshots locally so repeating a query does not use another
 * provider search credit. Query text itself is never stored; preference keys use a SHA-256 digest.
 */
class OnlineSearchCache(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "minimo_online_search_cache",
        Context.MODE_PRIVATE
    )

    fun read(provider: String, query: String, now: Long = System.currentTimeMillis()): CachedOnlineSearch? {
        val key = cacheKey(provider, query)
        val payload = preferences.getString(key, null) ?: return null
        val savedAt = preferences.getLong(key + "_saved_at", 0L)
        val age = now - savedAt
        if (savedAt <= 0L || age < 0L || age >= CACHE_TTL_MS) {
            preferences.edit().remove(key).remove(key + "_saved_at").apply()
            return null
        }

        return runCatching {
            // New-format entries retain the complete response, not merely accepted offers.
            if (payload.trimStart().startsWith("{")) {
                val wrapper = JSONObject(payload)
                val rawResponse = wrapper.optString("raw_response")
                    .takeIf { it.isNotBlank() && it != "null" }
                if (rawResponse != null) {
                    return@runCatching CachedOnlineSearch(
                        results = emptyList(),
                        savedAt = savedAt,
                        rawResponse = rawResponse
                    )
                }
            }

            // Backward compatibility for filtered results written by older app versions.
            val array = JSONArray(payload)
            val results = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val title = item.optString("title").takeIf { it.isNotBlank() && it != "null" }
                    ?: return@mapNotNull null
                val price = item.optDouble("price", Double.NaN)
                    .takeIf { it.isFinite() && it > 0.0 } ?: return@mapNotNull null
                val url = item.optString("sourceUrl").takeIf { it.startsWith("https://") }
                    ?: return@mapNotNull null
                val rating = if (item.isNull("rating")) null else item.optDouble("rating", Double.NaN)
                    .takeIf { it.isFinite() && it in 0.0..5.0 }
                val reviewCount = if (item.isNull("reviewCount")) null else item.optInt("reviewCount", -1)
                    .takeIf { it >= 0 }
                SerpApiShoppingResult(
                    title = title,
                    price = price,
                    storeName = item.optString("storeName").takeIf { it.isNotBlank() && it != "null" }
                        ?: "Tienda online",
                    sourceUrl = url,
                    thumbnailUrl = item.optString("thumbnailUrl").takeIf { it.startsWith("https://") }.orEmpty(),
                    delivery = item.optString("delivery").takeUnless { it == "null" }.orEmpty(),
                    rating = rating,
                    reviewCount = reviewCount,
                    observedAt = item.optLong("observedAt", savedAt),
                    primeEligible = item.optBoolean("primeEligible", false),
                    isFitmentUnverified = item.optBoolean("isFitmentUnverified", false),
                    isCategoryUnverified = item.optBoolean("isCategoryUnverified", false)
                )
            }
            CachedOnlineSearch(results = results, savedAt = savedAt)
        }.getOrElse {
            preferences.edit().remove(key).remove(key + "_saved_at").apply()
            null
        }
    }

    /** Stores an unfiltered provider response for 24 hours without credentials. */
    fun writeRaw(
        provider: String,
        query: String,
        rawResponse: String,
        now: Long = System.currentTimeMillis()
    ) {
        val root = runCatching { JSONObject(rawResponse) }.getOrNull() ?: return
        stripSensitiveFields(root)
        pruneExpiredAndOldEntries(now)
        val key = cacheKey(provider, query)
        val payload = JSONObject()
            .put("cache_version", 2)
            .put("raw_response", root.toString())
        preferences.edit()
            .putString(key, payload.toString())
            .putLong(key + "_saved_at", now)
            .apply()
    }

    private fun stripSensitiveFields(value: Any?) {
        val sensitive = Regex("""(?i)^(?:api[_-]?key|apikey|access[_-]?token|token|client[_-]?secret|secret|authorization|credential)$""")
        when (value) {
            is JSONObject -> {
                val names = value.keys().asSequence().toList()
                names.forEach { name ->
                    if (sensitive.matches(name)) value.remove(name)
                    else stripSensitiveFields(value.opt(name))
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) stripSensitiveFields(value.opt(index))
            }
        }
    }

    fun write(provider: String, query: String, results: List<SerpApiShoppingResult>, now: Long = System.currentTimeMillis()) {
        pruneExpiredAndOldEntries(now)
        val key = cacheKey(provider, query)
        val array = JSONArray()
        results.forEach { result ->
            array.put(JSONObject().apply {
                put("title", result.title)
                put("price", result.price)
                put("storeName", result.storeName)
                put("sourceUrl", result.sourceUrl)
                put("thumbnailUrl", result.thumbnailUrl)
                put("delivery", result.delivery)
                put("rating", result.rating ?: JSONObject.NULL)
                put("reviewCount", result.reviewCount ?: JSONObject.NULL)
                put("observedAt", result.observedAt)
                put("primeEligible", result.primeEligible)
                put("isFitmentUnverified", result.isFitmentUnverified)
                put("isCategoryUnverified", result.isCategoryUnverified)
            })
        }
        preferences.edit()
            .putString(key, array.toString())
            .putLong("${key}_saved_at", now)
            .apply()
    }

    private fun pruneExpiredAndOldEntries(now: Long) {
        val keys = preferences.all.keys
            .filter { it.startsWith("search_") && !it.endsWith("_saved_at") }
        val entries = keys.map { key -> key to preferences.getLong("${key}_saved_at", 0L) }
        val stale = entries.filter { (_, savedAt) ->
            savedAt <= 0L || now < savedAt || now - savedAt >= CACHE_TTL_MS
        }.map { it.first }
        val validNewestFirst = entries
            .filterNot { it.first in stale }
            .sortedByDescending { it.second }
        val excess = validNewestFirst.drop(MAX_CACHED_SEARCHES).map { it.first }
        if (stale.isNotEmpty() || excess.isNotEmpty()) {
            preferences.edit().apply {
                (stale + excess).distinct().forEach { key ->
                    remove(key)
                    remove("${key}_saved_at")
                }
            }.apply()
        }
    }

    private fun cacheKey(provider: String, query: String): String {
        val normalized = query.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT).take(120)
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("${provider.trim().uppercase(Locale.ROOT)}|$normalized".toByteArray(Charsets.UTF_8))
        val digest = bytes.joinToString("") { byte -> "%02x".format(byte) }
        return "search_$digest"
    }

    companion object {
        const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
        private const val MAX_CACHED_SEARCHES = 40
    }
}
