package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

private const val AUTHENTIC_ARCHIVE_URL_SQL =
    "(" +
        "lower(trim(sourceUrl)) GLOB 'https://web.archive.org/web/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]id_/https://*' OR " +
        "lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]/https://*' OR " +
        "lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]mp_/https://*' OR " +
        "lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]id_/https://*' OR " +
        "lower(trim(sourceUrl)) GLOB 'https://web.archive.org/web/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]id_/http://*' OR " +
        "lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]/http://*' OR " +
        "lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]mp_/http://*' OR " +
        "lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]id_/http://*'" +
        ")"

/**
 * Durable price observations for source-backed history.
 * Callers must not mark an entry verified until its source response has been validated.
 */
@Dao
interface PriceObservationDao {
    /** All persisted observations, including legacy rows awaiting revalidation. Read-only recovery path. */
    @Query("SELECT * FROM price_observations ORDER BY observedAt DESC")
    fun observeAllStored(): Flow<List<PriceObservationEntity>>


    /** Read the latest committed rows for one product without waiting for a Flow emission. */
    @Query("SELECT * FROM price_observations WHERE productId = :productId ORDER BY observedAt ASC")
    suspend fun getObservationsForProduct(productId: String): List<PriceObservationEntity>

    /** Load history rows only for the product IDs currently visible in a category. */
    @Query("SELECT * FROM price_observations WHERE productId IN (:productIds) ORDER BY observedAt ASC")
    fun observeForProducts(productIds: List<String>): Flow<List<PriceObservationEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(observation: PriceObservationEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(observations: List<PriceObservationEntity>)

    @Update
    suspend fun update(observation: PriceObservationEntity)

    @Query(
        "SELECT * FROM price_observations " +
            "WHERE productId = :productId AND isVerified = 1 " +
            "AND upper(trim(currency)) = 'EUR' AND price >= 0.5 AND price <= 10000.0 " +
            "AND trim(productTitle) <> '' AND observedAt > 0 " +
            "AND (NOT (lower(trim(evidenceType)) = 'archive' OR lower(trim(sourceUrl)) LIKE 'https://web.archive.org/web/%' OR lower(trim(sourceUrl)) LIKE 'http://web.archive.org/web/%' OR lower(trim(sourceUrl)) LIKE 'https://arquivo.pt/wayback/%' OR lower(trim(sourceUrl)) LIKE 'http://arquivo.pt/wayback/%' OR lower(trim(sourceUrl)) LIKE 'https://archive.org/%' OR lower(trim(sourceUrl)) LIKE 'http://archive.org/%') OR " +
                "(lower(trim(sourceUrl)) GLOB 'https://web.archive.org/web/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]id_/https://*' OR lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]/https://*' OR lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]mp_/https://*' OR lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]id_/https://*' OR lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]mp_/https://*' OR lower(trim(sourceUrl)) GLOB 'https://web.archive.org/web/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]id_/http://*' OR lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]/http://*' OR lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]mp_/http://*' OR lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]id_/http://*' OR lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]mp_/http://*' OR lower(trim(sourceUrl)) GLOB 'https://web.archive.org/web/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]id_/http://*' OR lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]/http://*' OR lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]mp_/http://*' OR lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]id_/http://*' OR lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]mp_/http://*')) " +
            "ORDER BY observedAt DESC"
    )
    fun observeVerifiedHistory(productId: String): Flow<List<PriceObservationEntity>>

    /** Latest validated live quote; archived snapshots never represent the current price. */
    @Query(
        "SELECT * FROM price_observations " +
            "WHERE productId = :productId AND isVerified = 1 " +
            "AND lower(evidenceType) = 'live' " +
            "AND lower(sourceUrl) LIKE 'https://%' " +
            "AND lower(sourceUrl) NOT LIKE 'https://web.archive.org/web/%' " +
            "AND lower(sourceUrl) NOT LIKE 'http://web.archive.org/web/%' " +
            "AND lower(trim(sourceUrl)) NOT LIKE 'https://arquivo.pt/wayback/%' " +
            "AND lower(trim(sourceUrl)) NOT LIKE 'http://arquivo.pt/wayback/%' " +
            "AND lower(trim(sourceUrl)) NOT LIKE 'https://archive.org/%' " +
            "AND lower(trim(sourceUrl)) NOT LIKE 'http://archive.org/%' " +
            "AND upper(trim(currency)) = 'EUR' AND price >= 0.5 AND price <= 10000.0 " +
            "AND trim(productTitle) <> '' AND trim(storeName) <> '' " +
            "AND observedAt > 0 AND observedAt <= :nowMillis + 300000 " +
            "ORDER BY observedAt DESC LIMIT 1"
    )
    suspend fun getLatestVerified(
        productId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): PriceObservationEntity?

    @Query("SELECT * FROM price_observations WHERE isVerified = 1 AND (NOT (lower(trim(evidenceType)) = 'archive' OR lower(trim(sourceUrl)) LIKE 'https://web.archive.org/web/%' OR lower(trim(sourceUrl)) LIKE 'http://web.archive.org/web/%' OR lower(trim(sourceUrl)) LIKE 'https://arquivo.pt/wayback/%' OR lower(trim(sourceUrl)) LIKE 'http://arquivo.pt/wayback/%' OR lower(trim(sourceUrl)) LIKE 'https://archive.org/%' OR lower(trim(sourceUrl)) LIKE 'http://archive.org/%') OR (lower(trim(sourceUrl)) GLOB 'https://web.archive.org/web/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]id_/https://*' OR lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]/https://*' OR lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]mp_/https://*' OR lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]id_/https://*' OR lower(trim(sourceUrl)) GLOB 'https://arquivo.pt/wayback/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]mp_/https://*')) ORDER BY observedAt ASC")
    fun observeAllVerified(): Flow<List<PriceObservationEntity>>

    /** Verified live sources recorded for one product; used to safely associate other merchant URLs. */
    @Query(
        "SELECT * FROM price_observations " +
            "WHERE productId = :productId AND isVerified = 1 " +
            "AND lower(evidenceType) = 'live' " +
            "AND lower(sourceUrl) LIKE 'https://%' " +
            "AND lower(sourceUrl) NOT LIKE 'https://web.archive.org/web/%' " +
            "AND lower(sourceUrl) NOT LIKE 'http://web.archive.org/web/%' " +
            "AND lower(trim(sourceUrl)) NOT LIKE 'https://arquivo.pt/wayback/%' " +
            "AND lower(trim(sourceUrl)) NOT LIKE 'http://arquivo.pt/wayback/%' " +
            "AND lower(trim(sourceUrl)) NOT LIKE 'https://archive.org/%' " +
            "AND lower(trim(sourceUrl)) NOT LIKE 'http://archive.org/%' " +
            "AND upper(trim(currency)) = 'EUR' AND price >= 0.5 AND price <= 10000.0 " +
            "AND trim(productTitle) <> '' AND trim(storeName) <> '' " +
            "AND observedAt > 0 AND observedAt <= :nowMillis + 300000 " +
            "ORDER BY observedAt DESC"
    )
    suspend fun getVerifiedLiveObservationsForProduct(
        productId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): List<PriceObservationEntity>

    /** Recent price-alert candidates must be live quotes, never past archive imports. */
    @Query(
        "SELECT * FROM price_observations " +
            "WHERE productId = :productId AND isVerified = 1 AND observedAt >= :since " +
            "AND observedAt <= :nowMillis + 300000 " +
            "AND lower(evidenceType) = 'live' " +
            "AND lower(sourceUrl) LIKE 'https://%' " +
            "AND lower(sourceUrl) NOT LIKE 'https://web.archive.org/web/%' " +
            "AND lower(sourceUrl) NOT LIKE 'http://web.archive.org/web/%' " +
            "AND lower(trim(sourceUrl)) NOT LIKE 'https://arquivo.pt/wayback/%' " +
            "AND lower(trim(sourceUrl)) NOT LIKE 'http://arquivo.pt/wayback/%' " +
            "AND lower(trim(sourceUrl)) NOT LIKE 'https://archive.org/%' " +
            "AND lower(trim(sourceUrl)) NOT LIKE 'http://archive.org/%' " +
            "AND upper(trim(currency)) = 'EUR' AND price >= 0.5 AND price <= 10000.0 " +
            "AND trim(productTitle) <> '' AND trim(storeName) <> '' " +
            "ORDER BY price ASC"
    )
    suspend fun getVerifiedSince(
        productId: String,
        since: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): List<PriceObservationEntity>

    @Query(
        "SELECT COUNT(*) FROM price_observations " +
            "WHERE isVerified = 1 " +
            "AND lower(trim(sourceUrl)) LIKE 'https://%' " +
            "AND upper(trim(currency)) = 'EUR' " +
            "AND price >= 0.5 AND price <= 10000.0 " +
            "AND trim(productTitle) <> '' AND trim(storeName) <> '' " +
            "AND observedAt > 0 AND observedAt <= :nowMillis + 300000 " +
            "AND (NOT (lower(trim(evidenceType)) = 'archive' OR lower(trim(sourceUrl)) LIKE 'https://web.archive.org/web/%' OR lower(trim(sourceUrl)) LIKE 'http://web.archive.org/web/%' OR lower(trim(sourceUrl)) LIKE 'https://arquivo.pt/wayback/%' OR lower(trim(sourceUrl)) LIKE 'http://arquivo.pt/wayback/%' OR lower(trim(sourceUrl)) LIKE 'https://archive.org/%' OR lower(trim(sourceUrl)) LIKE 'http://archive.org/%') " +
            "OR " + AUTHENTIC_ARCHIVE_URL_SQL + ")"
    )
    suspend fun getVerifiedObservationCount(
        nowMillis: Long = System.currentTimeMillis()
    ): Int

    @Query(
        "SELECT * FROM price_observations " +
            "WHERE productId = :productId AND storeId = :storeId " +
            "AND observedAt >= :dayStart AND observedAt < :dayEnd " +
            "AND lower(evidenceType) = 'live' " +
            "AND lower(sourceUrl) NOT LIKE 'https://web.archive.org/web/%' " +
            "AND lower(sourceUrl) NOT LIKE 'http://web.archive.org/web/%' " +
            "AND lower(trim(sourceUrl)) NOT LIKE 'https://arquivo.pt/wayback/%' " +
            "AND lower(trim(sourceUrl)) NOT LIKE 'http://arquivo.pt/wayback/%' " +
            "AND lower(trim(sourceUrl)) NOT LIKE 'https://archive.org/%' " +
            "AND lower(trim(sourceUrl)) NOT LIKE 'http://archive.org/%' " +
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
            "OR lower(trim(sourceUrl)) LIKE 'https://web.archive.org/web/%' " +
            "OR lower(trim(sourceUrl)) LIKE 'http://web.archive.org/web/%' " +
            "OR lower(trim(sourceUrl)) LIKE 'https://arquivo.pt/wayback/%' " +
            "OR lower(trim(sourceUrl)) LIKE 'http://arquivo.pt/wayback/%') " +
            "AND " + AUTHENTIC_ARCHIVE_URL_SQL + " " +
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
     * All archived observations for a product/store/day. The repository applies its full
     * snapshot, seller, and product validation before treating a legacy row as a real duplicate.
     */
    @Query(
        "SELECT * FROM price_observations " +
            "WHERE productId = :productId AND storeId = :storeId " +
            "AND observedAt >= :dayStart AND observedAt < :dayEnd " +
            "AND isVerified = 1 " +
            "AND (lower(evidenceType) = 'archive' " +
            "OR lower(trim(sourceUrl)) LIKE 'https://web.archive.org/web/%' " +
            "OR lower(trim(sourceUrl)) LIKE 'http://web.archive.org/web/%' " +
            "OR lower(trim(sourceUrl)) LIKE 'https://arquivo.pt/wayback/%' " +
            "OR lower(trim(sourceUrl)) LIKE 'http://arquivo.pt/wayback/%') " +
            "AND upper(trim(currency)) = 'EUR' " +
            "AND price >= 0.5 AND price <= 10000.0 " +
            "AND trim(productTitle) <> '' " +
            "ORDER BY price ASC, observedAt DESC"
    )
    suspend fun getArchivedObservationsForStoreProductDay(
        productId: String,
        storeId: String,
        dayStart: Long,
        dayEnd: Long
    ): List<PriceObservationEntity>

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
     * Insert an archived history point only when this product/store/local day has no verified
     * archived observation yet. Historical backfill must not replace an already-recorded day:
     * repeated captures are useful evidence for deciding to continue discovery, not new points.
     * Unverified legacy rows and live observations do not block a validated archive import.
     */
    @Transaction
    suspend fun insertArchivedObservationIfAbsent(
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
        if (existing != null) return false
        insert(observation)
        return true
    }

    /**
     * Save the best verified archive price for a product/store/local day.
     *
     * Invalid legacy rows are identified by the repository's stricter snapshot/product checks.
     * Valid same-day rows block duplicates, but a newly verified lower price replaces the
     * existing daily point so a cheaper same-model offer (including another colour) is not lost.
     * Rechecking in this transaction prevents concurrent imports creating duplicate rows.
     */
    @Transaction
    suspend fun insertArchivedObservationIfNoEligibleDuplicate(
        observation: PriceObservationEntity,
        dayStart: Long,
        dayEnd: Long,
        invalidExistingIds: List<Long>
    ): Boolean {
        val existing = getArchivedObservationsForStoreProductDay(
            observation.productId,
            observation.storeId,
            dayStart,
            dayEnd
        )
        val bestEligibleExisting = existing.firstOrNull { it.id !in invalidExistingIds }
        if (bestEligibleExisting != null) {
            val lowerPrice = observation.price < bestEligibleExisting.price
            val samePriceWithNewerCapture =
                observation.price == bestEligibleExisting.price &&
                    observation.observedAt > bestEligibleExisting.observedAt
            if (!lowerPrice && !samePriceWithNewerCapture) return false
            update(observation.copy(id = bestEligibleExisting.id))
            return true
        }
        insert(observation)
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

    @Query("SELECT DISTINCT productId FROM price_observations")
    suspend fun getAllProductIdsWithObservations(): List<String>

}
