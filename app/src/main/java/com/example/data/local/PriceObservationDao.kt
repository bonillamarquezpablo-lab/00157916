package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Query("SELECT * FROM price_observations ORDER BY observedAt ASC")
    suspend fun getAllObservationsSnapshot(): List<PriceObservationEntity>

    @Query("DELETE FROM price_observations WHERE id IN (:observationIds)")
    suspend fun deleteByIds(observationIds: List<Long>): Int

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

    @Query("SELECT COUNT(*) FROM price_observations WHERE isVerified = 1")
    suspend fun getVerifiedObservationCount(): Int

    @Query(
        "SELECT * FROM price_observations " +
            "WHERE productId = :productId AND storeId = :storeId " +
            "AND observedAt >= :dayStart AND observedAt < :dayEnd " +
            "ORDER BY observedAt DESC"
    )
    suspend fun getObservationsForStoreProductDay(
        productId: String,
        storeId: String,
        dayStart: Long,
        dayEnd: Long
    ): List<PriceObservationEntity>

    /**
     * Insert at most one valid source-backed observation for a product/store/local-day.
     * Invalid legacy rows do not block a fresh valid observation.
     */
    @Transaction
    suspend fun insertIfNoVerifiedStoreOfferForDay(
        observation: PriceObservationEntity,
        dayStart: Long,
        dayEnd: Long
    ): Boolean {
        val existing = getObservationsForStoreProductDay(
            productId = observation.productId,
            storeId = observation.storeId,
            dayStart = dayStart,
            dayEnd = dayEnd
        )
        val hasValidExisting = existing.any { old ->
            val title = old.productTitle.lowercase()
            val seller = old.storeName.lowercase()
            val source = old.sourceUrl.lowercase()
            old.isVerified &&
                old.currency == "EUR" &&
                old.price.isFinite() &&
                old.price in 5.0..10000.0 &&
                source.startsWith("https://") &&
                listOf("wallapop", "cash converters", "vinted", "cex", "rentik").none { it in seller || it in source } &&
                listOf(
                    "segunda mano", "usado", "reacondicionad", "refurbished", "renewed",
                    "open box", "caja abierta", "pre owned", "seminuevo", "renting",
                    "alquiler", "cuota mensual", "subscription", "por mes",
                    "impreso con su logotipo", "impreso con el logotipo",
                    "regalo promocional", "articulo publicitario", "merchandising personalizado"
                ).none { it in title } &&
                !((listOf("macbook", "laptop", "chromebook", "portatil").any { it in title }) && old.price < 100.0) &&
                !((listOf("iphone", "smartphone", "redmi", "pixel", "galaxy s").any { it in title }) && old.price < 45.0) &&
                !((listOf("playstation 5", "ps5", "xbox series", "nintendo switch").any { it in title }) && old.price < 70.0)
        }
        if (hasValidExisting) return false
        insert(observation)
        return true
    }

    @Query("SELECT COUNT(*) FROM price_observations WHERE productId = :productId")
    suspend fun getObservationCount(productId: String): Int

    /** Demo/unverified points are never valid history in this app. */
    @Query("DELETE FROM price_observations WHERE isVerified = 0")
    suspend fun deleteUnverifiedObservations(): Int

    /** Remove points attached to legacy mock products before deleting those products. */
    @Query("DELETE FROM price_observations WHERE productId IN (SELECT id FROM products WHERE id LIKE 'demo_%' OR lower(athMinDate) LIKE '%demostración%' OR lower(athMinStore) LIKE '%demostración%')")
    suspend fun deleteExplicitDemoObservations(): Int
}
