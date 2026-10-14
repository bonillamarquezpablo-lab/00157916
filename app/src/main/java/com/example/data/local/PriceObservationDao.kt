package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
            "WHERE productId = :productId AND storeId = :storeId AND isVerified = 1 " +
            "AND observedAt >= :dayStart AND observedAt < :dayEnd " +
            "LIMIT 1"
    )
    suspend fun getObservationForStoreProductDay(
        productId: String,
        storeId: String,
        dayStart: Long,
        dayEnd: Long
    ): PriceObservationEntity?

    @Query("SELECT COUNT(*) FROM price_observations WHERE productId = :productId")
    suspend fun getObservationCount(productId: String): Int
 
    /** Demo/unverified points are never valid price history. */
    @Query("DELETE FROM price_observations WHERE isVerified = 0")
    suspend fun deleteUnverifiedObservations(): Int

    /** Remove legacy demo-linked history before deleting demo products. */
    @Query("DELETE FROM price_observations WHERE productId IN (SELECT id FROM products WHERE id LIKE 'demo_%' OR lower(athMinDate) LIKE '%demostración%' OR lower(athMinStore) LIKE '%demostración%')")
    suspend fun deleteExplicitDemoObservations(): Int
}
