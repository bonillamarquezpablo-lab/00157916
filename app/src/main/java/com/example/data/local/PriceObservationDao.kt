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
            "ORDER BY observedAt DESC"
    )
    fun observeVerifiedHistory(productId: String): Flow<List<PriceObservationEntity>>

    @Query(
        "SELECT * FROM price_observations " +
            "WHERE productId = :productId AND isVerified = 1 " +
            "ORDER BY observedAt DESC LIMIT 1"
    )
    suspend fun getLatestVerified(productId: String): PriceObservationEntity?

    @Query("SELECT * FROM price_observations WHERE isVerified = 1 ORDER BY observedAt ASC")
    fun observeAllVerified(): Flow<List<PriceObservationEntity>>

    @Query("SELECT * FROM price_observations WHERE productId = :productId AND isVerified = 1 AND observedAt >= :since ORDER BY price ASC")
    suspend fun getVerifiedSince(productId: String, since: Long): List<PriceObservationEntity>

    @Query("SELECT COUNT(*) FROM price_observations WHERE isVerified = 1")
    suspend fun getVerifiedObservationCount(): Int

    @Query(
        "SELECT * FROM price_observations " +
            "WHERE productId = :productId AND storeId = :storeId " +
            "AND observedAt >= :dayStart AND observedAt < :dayEnd " +
            "AND lower(evidenceType) != 'archive' " +
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
