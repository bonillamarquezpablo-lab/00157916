package com.example.data.remote

import android.content.Context
import java.security.MessageDigest
import java.util.Locale

/**
 * Prevents repeated paid lookups of an identical query after failures or explicit refreshes.
 * Query text is never persisted; SharedPreferences stores only a SHA-256-derived key.
 */
class OnlineSearchQuotaGuard(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "minimo_online_search_quota_guard",
        Context.MODE_PRIVATE
    )

    fun cooldownRemainingMs(
        provider: String,
        query: String,
        now: Long = System.currentTimeMillis()
    ): Long {
        if (provider.isBlank() || query.isBlank()) return 0L
        val lastAttempt = preferences.getLong(preferenceKey(provider, query), 0L)
        if (lastAttempt <= 0L) return 0L
        val elapsed = (now - lastAttempt).coerceAtLeast(0L)
        return (SAME_QUERY_COOLDOWN_MS - elapsed).coerceAtLeast(0L)
    }

    /**
     * Atomically check and reserve the next paid attempt for this provider/query.
     * Returns zero when reserved, or the remaining cooldown when a recent attempt exists.
     */
    fun tryStartAttempt(
        provider: String,
        query: String,
        now: Long = System.currentTimeMillis()
    ): Long {
        if (provider.isBlank() || query.isBlank()) return 0L
        synchronized(preferences) {
            val key = preferenceKey(provider, query)
            val lastAttempt = preferences.getLong(key, 0L)
            if (lastAttempt > 0L) {
                val elapsed = (now - lastAttempt).coerceAtLeast(0L)
                val remaining = (SAME_QUERY_COOLDOWN_MS - elapsed).coerceAtLeast(0L)
                if (remaining > 0L) return remaining
            }
            preferences.edit().putLong(key, now).apply()
            return 0L
        }
    }

    private fun preferenceKey(provider: String, query: String): String {
        val normalizedQuery = query.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${provider.trim().uppercase(Locale.ROOT)}|$normalizedQuery".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "paid_attempt_$digest"
    }

    companion object {
        /** Same query retries are cooled down for ten minutes; the regular result cache stays 24h. */
        const val SAME_QUERY_COOLDOWN_MS = 10L * 60L * 1000L
    }
}
