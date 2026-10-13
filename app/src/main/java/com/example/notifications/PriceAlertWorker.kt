package com.example.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.AppDatabase
import com.example.data.local.ProductEntity
import com.example.data.local.TrackedProductEntity
import com.example.data.remote.SerpApiClient
import com.example.data.remote.SerpApiException
import com.example.data.remote.SerpApiRequestProduct
import com.example.data.remote.SerpApiTokenStore
import com.example.data.repository.ProductRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Quota-capped daily monitor: checks at most three products with target prices per run.
 * Products are rotated by last-check time so the same watchlist items do not always win.
 */
class PriceAlertWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Do not spend paid searches if Android cannot display the notifications the user requested.
        if (!PriceAlertNotifications.hasPermission(applicationContext)) return Result.success()

        val token = SerpApiTokenStore(applicationContext).read()
        if (token.isNullOrBlank()) return Result.success()

        val database = AppDatabase.getInstance(applicationContext)
        val preferences = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val tracked = database.trackedProductDao().getAllTracked().first()
            .filter {
                it.isNotificationEnabled &&
                    it.targetAlertPrice?.let { price -> price.isFinite() && price > 0.0 } == true
            }
        if (tracked.isEmpty()) return Result.success()

        val productsById = database.productDao().getAllProductsSnapshot().associateBy { it.id }
        val now = System.currentTimeMillis()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(now))
        val usedToday = if (preferences.getString(QUOTA_DAY_KEY, null) == today) {
            preferences.getInt(QUERIES_USED_TODAY_KEY, 0).coerceAtLeast(0)
        } else {
            0
        }
        val remainingDailyQuota = (MAX_PRODUCTS_PER_DAY - usedToday).coerceAtLeast(0)
        if (remainingDailyQuota == 0) return Result.success()

        val due = tracked.mapNotNull { watch ->
            val product = productsById[watch.productId] ?: return@mapNotNull null
            if (now - preferences.getLong(checkedKey(watch.productId), 0L) < MIN_RECHECK_INTERVAL_MS) {
                return@mapNotNull null
            }
            Candidate(watch, product)
        }.sortedBy { preferences.getLong(checkedKey(it.watch.productId), 0L) }
            .take(remainingDailyQuota)
        if (due.isEmpty()) return Result.success()

        val client = SerpApiClient()
        val account = try {
            client.getAccountInfo(token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Result.retry()
        }
        if (account.accountStatus.isNotBlank() &&
            !account.accountStatus.equals("active", ignoreCase = true)
        ) return Result.success()

        val availableThisHour = account.remainingThisHour ?: MAX_PRODUCTS_PER_RUN
        val queryCount = minOf(remainingDailyQuota, due.size, account.searchesRemaining, availableThisHour)
        if (queryCount <= 0) return Result.success()
        val selected = due.take(queryCount)
        // Reserve quota and mark attempted items before the request: retries or repeated button
        // presses must not silently turn this into more than three paid searches per local day.
        preferences.edit().apply {
            putString(QUOTA_DAY_KEY, today)
            putInt(QUERIES_USED_TODAY_KEY, usedToday + selected.size)
            selected.forEach { putLong(checkedKey(it.watch.productId), now) }
        }.commit()
        val requests = selected.map { candidate ->
            SerpApiRequestProduct(
                id = candidate.product.id,
                title = candidate.product.title,
                brand = candidate.product.brand,
                ean = candidate.product.ean
            )
        }

        val startedAt = System.currentTimeMillis()
        val batch = try {
            client.searchProducts(token, requests)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SerpApiException) {
            return if (error.httpCode == 401 || error.httpCode == 403) Result.success() else Result.retry()
        } catch (_: Exception) {
            return Result.retry()
        }

        val repository = ProductRepository(
            storeDao = database.storeDao(),
            productDao = database.productDao(),
            trackedDao = database.trackedProductDao(),
            priceObservationDao = database.priceObservationDao()
        )
        repository.recordSerpApiQuotes(batch.quotes)

        for (candidate in selected) {
            val productId = candidate.watch.productId
            val target = candidate.watch.targetAlertPrice ?: continue
            val latestQuotes = database.priceObservationDao().getVerifiedSince(productId, startedAt)
            val best = latestQuotes.minByOrNull { it.price } ?: continue
            val previousPrice = preferences.getString(priceKey(productId), null)?.toDoubleOrNull()
            val previousTarget = preferences.getString(targetKey(productId), null)?.toDoubleOrNull()
            val shouldNotify = PriceAlertRules.shouldNotifyPriceDrop(
                previousPrice = previousPrice,
                currentPrice = best.price,
                targetPrice = target,
                previousTargetPrice = previousTarget
            )
            if (shouldNotify) {
                val posted = PriceAlertNotifications.showPriceDrop(
                    context = applicationContext,
                    productId = productId,
                    productTitle = candidate.product.title,
                    price = best.price,
                    targetPrice = target,
                    storeName = best.storeName.ifBlank { "Google Shopping" }
                )
                // If Android rejects the notification, preserve the previous state so a later run retries it.
                if (!posted) continue
            }

            preferences.edit()
                .putString(priceKey(productId), best.price.toString())
                .putString(targetKey(productId), target.toString())
                .apply()
        }
        return Result.success()
    }

    private fun checkedKey(productId: String) = "checked_at_$productId"
    private fun priceKey(productId: String) = "last_price_$productId"
    private fun targetKey(productId: String) = "last_target_$productId"

    private data class Candidate(
        val watch: TrackedProductEntity,
        val product: ProductEntity
    )

    private companion object {
        const val PREFS_NAME = "minimo_price_alert_monitor"
        const val MAX_PRODUCTS_PER_DAY = 3
        const val QUOTA_DAY_KEY = "quota_day"
        const val QUERIES_USED_TODAY_KEY = "queries_used_today"
        const val MIN_RECHECK_INTERVAL_MS = 20L * 60L * 60L * 1000L
    }
}
