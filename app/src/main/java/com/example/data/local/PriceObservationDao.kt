package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Durable price observations for source-backed history.
 * Callers must not mark an entry verified until its source response has been validated.
 */
@Dao
interface PriceObservationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(observation: PriceObservationEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(observations: List<PriceObservationEntity>)

    @Update
    suspend fun update(observation: PriceObservationEntity)

    @Query(
        "SELECT * FROM price_observations " +
            "WHERE productId = :productId AND isVerified = 1 " +
            "AND (NOT (lower(evidenceType) = 'archive' OR lower(sourceUrl) LIKE 'https://web.archive.org/web/%' OR lower(sourceUrl) LIKE 'http://web.archive.org/web/%') OR (upper(trim(currency)) = 'EUR' AND price >= 0.5 AND price <= 10000.0 AND trim(productTitle) <> '')) " +
            "ORDER BY observedAt DESC"
    )
    fun observeVerifiedHistory(productId: String): Flow<List<PriceObservationEntity>>

    /** Latest validated live quote; archived snapshots never represent the current price. */
    @Query(
        "SELECT * FROM price_observations " +
            "WHERE productId = :productId AND isVerified = 1 " +
            "AND lower(evidenceType) = 'live' " +
            "AND lower(sourceUrl) NOT LIKE 'https://web.archive.org/web/%' " +
            "AND lower(sourceUrl) NOT LIKE 'http://web.archive.org/web/%' " +
            "ORDER BY observedAt DESC LIMIT 1"
    )
    suspend fun getLatestVerified(productId: String): PriceObservationEntity?

    @Query("SELECT * FROM price_observations WHERE isVerified = 1 AND (NOT (lower(evidenceType) = 'archive' OR lower(sourceUrl) LIKE 'https://web.archive.org/web/%' OR lower(sourceUrl) LIKE 'http://web.archive.org/web/%') OR (upper(trim(currency)) = 'EUR' AND price >= 0.5 AND price <= 10000.0 AND trim(productTitle) <> '')) ORDER BY observedAt ASC")
    fun observeAllVerified(): Flow<List<PriceObservationEntity>>

    /** Verified live sources recorded for one product; used to safely associate other merchant URLs. */
    @Query(
        "SELECT * FROM price_observations " +
            "WHERE productId = :productId AND isVerified = 1 " +
            "AND lower(evidenceType) = 'live' " +
            "AND lower(sourceUrl) NOT LIKE 'https://web.archive.org/web/%' " +
            "AND lower(sourceUrl) NOT LIKE 'http://web.archive.org/web/%' " +
            "ORDER BY observedAt DESC"
    )
    suspend fun getVerifiedLiveObservationsForProduct(productId: String): List<PriceObservationEntity>

    /** Recent price-alert candidates must be live quotes, never past archive imports. */
    @Query(
        "SELECT * FROM price_observations " +
            "WHERE productId = :productId AND isVerified = 1 AND observedAt >= :since " +
            "AND lower(evidenceType) = 'live' " +
            "AND lower(sourceUrl) NOT LIKE 'https://web.archive.org/web/%' " +
            "AND lower(sourceUrl) NOT LIKE 'http://web.archive.org/web/%' " +
            "ORDER BY price ASC"
    )
    suspend fun getVerifiedSince(productId: String, since: Long): List<PriceObservationEntity>

    @Query("SELECT COUNT(*) FROM price_observations WHERE isVerified = 1 AND (NOT (lower(evidenceType) = 'archive' OR lower(sourceUrl) LIKE 'https://web.archive.org/web/%' OR lower(sourceUrl) LIKE 'http://web.archive.org/web/%') OR (upper(trim(currency)) = 'EUR' AND price >= 0.5 AND price <= 10000.0 AND trim(productTitle) <> ''))")
    suspend fun getVerifiedObservationCount(): Int

    @Query(
        "SELECT * FROM price_observations " +
            "WHERE productId = :productId AND storeId = :storeId " +
            "AND observedAt >= :dayStart AND observedAt < :dayEnd " +
            "AND lower(evidenceType) = 'live' " +
            "AND lower(sourceUrl) NOT LIKE 'https://web.archive.org/web/%' " +
            "AND lower(sourceUrl) NOT LIKE 'http://web.archive.org/web/%' " +
            "ORDER BY observedAt DESC LIMIT 1"
    )
    suspend fun getObservationForStoreProductDay(
        productId: String,
        storeId: String,
        dayStart: Long,
        dayEnd: Long
    ): PriceObservationEntity?

    /**
     * Find an archived observation for this product/store/day without considering live quotes.
     * Archived snapshots must deduplicate against other snapshots, but must never block a live
     * observation for the same merchant and day.
     */
    @Query(
        "SELECT * FROM price_observations " +
            "WHERE productId = :productId AND storeId = :storeId " +
            "AND observedAt >= :dayStart AND observedAt < :dayEnd " +
            "AND isVerified = 1 " +
            "AND (lower(evidenceType) = 'archive' " +
            "OR lower(sourceUrl) LIKE 'https://web.archive.org/web/%' " +
            "OR lower(sourceUrl) LIKE 'http://web.archive.org/web/%') " +
            "AND upper(trim(currency)) = 'EUR' " +
            "AND price >= 0.5 AND price <= 10000.0 " +
            "AND trim(productTitle) <> '' " +
            "ORDER BY price ASC, observedAt DESC LIMIT 1"
    )
    suspend fun getArchivedObservationForStoreProductDay(
        productId: String,
        storeId: String,
        dayStart: Long,
        dayEnd: Long
    ): PriceObservationEntity?

    /**
     * Historical Google Product API insights have a separate identity per merchant/day.
     * They must not overwrite a live quote (or be overwritten by one).
     */
    @Query(
        "SELECT * FROM price_observations " +
            "WHERE productId = :productId AND storeId = :storeId " +
            "AND observedAt >= :dayStart AND observedAt < :dayEnd " +
            "AND isVerified = 1 AND lower(evidenceType) = 'google_insights' " +
            "ORDER BY price ASC, observedAt DESC LIMIT 1"
    )
    suspend fun getGoogleInsightObservationForStoreProductDay(
        productId: String,
        storeId: String,
        dayStart: Long,
        dayEnd: Long
    ): PriceObservationEntity?

    /**
     * Retain one verified historical insight per product/store/local day without colliding with
     * live quotations or Wayback captures. A repeated import keeps the lower sourced insight.
     */
    @Transaction
    suspend fun saveGoogleInsightObservationIfLower(
        observation: PriceObservationEntity,
        dayStart: Long,
        dayEnd: Long
    ): Boolean {
        val existing = getGoogleInsightObservationForStoreProductDay(
            observation.productId,
            observation.storeId,
            dayStart,
            dayEnd
        )
        if (existing == null) {
            insert(observation)
            return true
        }
        val isLowerPrice = observation.price < existing.price
        val samePriceWithNewerCapture =
            observation.price == existing.price && observation.observedAt > existing.observedAt
        if (!isLowerPrice && !samePriceWithNewerCapture) return false
        update(observation.copy(id = existing.id))
        return true
    }

    /**
     * Keep the lowest verified archived price per product/store/day. A repeated import does not
     * create duplicate rows; live observations use their separate save path below.
     */
    @Transaction
    suspend fun saveArchivedObservationIfLower(
        observation: PriceObservationEntity,
        dayStart: Long,
        dayEnd: Long
    ): Boolean {
        val existing = getArchivedObservationForStoreProductDay(
            observation.productId,
            observation.storeId,
            dayStart,
            dayEnd
        )
        if (existing == null) {
            insert(observation)
            return true
        }
        val isLowerPrice = observation.price < existing.price
        val samePriceWithNewerCapture =
            observation.price == existing.price && observation.observedAt > existing.observedAt
        if (!isLowerPrice && !samePriceWithNewerCapture) return false
        update(observation.copy(id = existing.id))
        return true
    }

    /**
     * Atomically save at most one live observation per product/store/local day.
     * A suspicious observation cannot replace a verified quote. Archived snapshots
     * are excluded by getObservationForStoreProductDay and never block live prices.
     */
    @Transaction
    suspend fun saveDailyObservationIfNewer(
        observation: PriceObservationEntity,
        dayStart: Long,
        dayEnd: Long
    ): Boolean {
        val existing = getObservationForStoreProductDay(
            observation.productId,
            observation.storeId,
            dayStart,
            dayEnd
        )
        if (existing == null) {
            insert(observation)
            return true
        }
        if (!observation.isVerified && existing.isVerified) return false
        if (observation.isVerified && !existing.isVerified) {
            update(observation.copy(id = existing.id))
            return true
        }
        val isNewer = observation.observedAt > existing.observedAt
        val sameTimeAndBetterPrice =
            observation.observedAt == existing.observedAt && observation.price < existing.price
        if (!isNewer && !sameTimeAndBetterPrice) return false
        update(observation.copy(id = existing.id))
        return true
    }

    @Query("SELECT COUNT(*) FROM price_observations WHERE productId = :productId")
    suspend fun getObservationCount(productId: String): Int

}
