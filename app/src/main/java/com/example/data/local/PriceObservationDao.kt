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

    @Query(
        "SELECT * FROM price_observations WHERE isVerified = 1 " +
            "AND lower(storeName) NOT LIKE '%wallapop%' " +
            "AND lower(storeName) NOT LIKE '%cash converters%' " +
            "AND lower(storeName) NOT LIKE '%vinted%' " +
            "AND lower(storeName) NOT LIKE '%cex%' " +
            "AND lower(storeName) NOT LIKE '%rentik%' " +
            "AND lower(sourceUrl) NOT LIKE '%wallapop.%' " +
            "AND lower(sourceUrl) NOT LIKE '%cashconverters.%' " +
            "AND lower(sourceUrl) NOT LIKE '%vinted.%' " +
            "AND lower(sourceUrl) NOT LIKE '%webuy.com%' " +
            "AND lower(sourceUrl) NOT LIKE '%cex.%' " +
            "AND lower(productTitle) NOT LIKE '%segunda mano%' " +
            "AND lower(productTitle) NOT LIKE '%usado%' " +
            "AND lower(productTitle) NOT LIKE '%reacondicionad%' " +
            "AND lower(productTitle) NOT LIKE '%refurbished%' " +
            "AND lower(productTitle) NOT LIKE '%renewed%' " +
            "AND lower(productTitle) NOT LIKE '%alquiler%' " +
            "AND lower(productTitle) NOT LIKE '%renting%' " +
            "AND lower(productTitle) NOT LIKE '%cuota mensual%' " +
            "AND lower(productTitle) NOT LIKE '%suscripcion%' " +
            "AND lower(productTitle) NOT LIKE '%subscription%' " +
            "AND lower(productTitle) NOT LIKE '%por mes%' " +
            "AND lower(productTitle) NOT LIKE '%impreso con su logotipo%' " +
            "AND lower(productTitle) NOT LIKE '%impreso con el logotipo%' " +
            "AND lower(productTitle) NOT LIKE '%regalo promocional%' " +
            "AND lower(productTitle) NOT LIKE '%articulo publicitario%' " +
            "AND lower(productTitle) NOT LIKE '%merchandising personalizado%' " +
            "AND NOT ((lower(productTitle) LIKE '%macbook%' OR lower(productTitle) LIKE '%laptop%' OR " +
                "lower(productTitle) LIKE '%chromebook%' OR lower(productTitle) LIKE '%portatil%') AND price < 100) " +
            "AND NOT ((lower(productTitle) LIKE '%iphone%' OR lower(productTitle) LIKE '%smartphone%' OR " +
                "lower(productTitle) LIKE '%redmi%' OR lower(productTitle) LIKE '%pixel%' OR lower(productTitle) LIKE '%galaxy s%') AND price < 45) " +
            "AND NOT ((lower(productTitle) LIKE '%playstation 5%' OR lower(productTitle) LIKE '%ps5%' OR " +
                "lower(productTitle) LIKE '%xbox series%' OR lower(productTitle) LIKE '%nintendo switch%') AND price < 70) " +
            "ORDER BY observedAt ASC"
    )
    fun observeAllVerified(): Flow<List<PriceObservationEntity>>

    @Query("SELECT COUNT(*) FROM price_observations WHERE isVerified = 1")
    suspend fun getVerifiedObservationCount(): Int

    @Query(
        "SELECT * FROM price_observations " +
            "WHERE productId = :productId AND storeId = :storeId " +
            "AND observedAt >= :dayStart AND observedAt < :dayEnd " +
            "AND isVerified = 1 AND currency = 'EUR' " +
            "AND lower(storeName) NOT LIKE '%wallapop%' " +
            "AND lower(storeName) NOT LIKE '%cash converters%' " +
            "AND lower(storeName) NOT LIKE '%vinted%' " +
            "AND lower(storeName) NOT LIKE '%cex%' " +
            "AND lower(storeName) NOT LIKE '%rentik%' " +
            "AND lower(productTitle) NOT LIKE '%segunda mano%' " +
            "AND lower(productTitle) NOT LIKE '%usado%' " +
            "AND lower(productTitle) NOT LIKE '%reacondicionad%' " +
            "AND lower(productTitle) NOT LIKE '%refurbished%' " +
            "AND lower(productTitle) NOT LIKE '%renewed%' " +
            "AND lower(productTitle) NOT LIKE '%open box%' " +
            "AND lower(productTitle) NOT LIKE '%caja abierta%' " +
            "AND lower(productTitle) NOT LIKE '%pre owned%' " +
            "AND lower(productTitle) NOT LIKE '%seminuevo%' " +
            "AND lower(productTitle) NOT LIKE '%alquiler%' " +
            "AND lower(productTitle) NOT LIKE '%renting%' " +
            "AND lower(productTitle) NOT LIKE '%cuota mensual%' " +
            "AND lower(productTitle) NOT LIKE '%por mes%' " +
            "AND lower(productTitle) NOT LIKE '%subscription%' " +
            "AND lower(productTitle) NOT LIKE '%impreso con su logotipo%' " +
            "AND lower(productTitle) NOT LIKE '%impreso con el logotipo%' " +
            "AND lower(productTitle) NOT LIKE '%articulo publicitario%' " +
            "AND NOT ((lower(productTitle) LIKE '%macbook%' OR lower(productTitle) LIKE '%laptop%' OR lower(productTitle) LIKE '%chromebook%') AND price < 100) " +
            "AND NOT ((lower(productTitle) LIKE '%iphone%' OR lower(productTitle) LIKE '%smartphone%' OR lower(productTitle) LIKE '%redmi%' OR lower(productTitle) LIKE '%galaxy s%') AND price < 45) " +
            "LIMIT 1"
    )
    suspend fun getObservationForStoreProductDay(
        productId: String,
        storeId: String,
        dayStart: Long,
        dayEnd: Long
    ): PriceObservationEntity?

    /**
     * Atomically deduplicate and insert a daily offer. The transaction prevents two overlapping
     * search callbacks from both observing "no row" and writing duplicate history points.
     */
    @Transaction
    suspend fun insertIfNoVerifiedStoreOfferForDay(
        observation: PriceObservationEntity,
        dayStart: Long,
        dayEnd: Long
    ): Boolean {
        val existing = getObservationForStoreProductDay(
            productId = observation.productId,
            storeId = observation.storeId,
            dayStart = dayStart,
            dayEnd = dayEnd
        )
        if (existing != null) return false
        insert(observation)
        return true
    }

    @Query("SELECT COUNT(*) FROM price_observations WHERE productId = :productId")
    suspend fun getObservationCount(productId: String): Int

    /**
     * Purge legacy rows known to be incompatible with the app's "new in store" price history.
     * This is intentionally conservative: these sellers/conditions were never eligible for
     * the consumer-price history, and some old builds persisted them before today's filters.
     */
    @Query(
        "DELETE FROM price_observations WHERE " +
            "lower(storeName) LIKE '%wallapop%' OR " +
            "lower(storeName) LIKE '%cash converters%' OR " +
            "lower(storeName) LIKE '%vinted%' OR " +
            "lower(storeName) LIKE '%cex%' OR " +
            "lower(storeName) LIKE '%rentik%' OR " +
            "lower(sourceUrl) LIKE '%wallapop.%' OR " +
            "lower(sourceUrl) LIKE '%cashconverters.%' OR " +
            "lower(sourceUrl) LIKE '%vinted.%' OR " +
            "lower(sourceUrl) LIKE '%webuy.com%' OR " +
            "lower(sourceUrl) LIKE '%cex.%' OR " +
            "lower(productTitle) LIKE '%segunda mano%' OR " +
            "lower(productTitle) LIKE '%reacondicionad%' OR " +
            "lower(productTitle) LIKE '%refurbished%' OR " +
            "lower(productTitle) LIKE '%renewed%' OR " +
            "lower(productTitle) LIKE '%open box%' OR " +
            "lower(productTitle) LIKE '%caja abierta%' OR " +
            "lower(productTitle) LIKE '%pre owned%' OR " +
            "lower(productTitle) LIKE '%seminuevo%' OR " +
            "lower(productTitle) LIKE '%usado%' OR " +
            "lower(productTitle) LIKE '%renting%' OR " +
            "lower(productTitle) LIKE '%alquiler%' OR " +
            "lower(productTitle) LIKE '%cuota mensual%' OR " +
            "lower(productTitle) LIKE '%suscripción%' OR " +
            "lower(productTitle) LIKE '%subscription%' OR " +
            "lower(productTitle) LIKE '%impreso con su logotipo%' OR " +
            "lower(productTitle) LIKE '%impreso con el logotipo%' OR " +
            "lower(productTitle) LIKE '%regalo promocional%' OR " +
            "lower(productTitle) LIKE '%artículo publicitario%' OR " +
            "lower(productTitle) LIKE '%articulo publicitario%' OR " +
            "lower(productTitle) LIKE '%merchandising personalizado%' OR " +
            "((lower(productTitle) LIKE '%macbook%' OR lower(productTitle) LIKE '%laptop%' OR " +
            "lower(productTitle) LIKE '%chromebook%' OR lower(productTitle) LIKE '%portátil%' OR lower(productTitle) LIKE '%portatil%') AND price < 100) OR " +
            "((lower(productTitle) LIKE '%iphone%' OR lower(productTitle) LIKE '%smartphone%' OR lower(productTitle) LIKE '%redmi%' OR lower(productTitle) LIKE '%pixel%' OR lower(productTitle) LIKE '%galaxy s%') AND price < 45) OR " +
            "((lower(productTitle) LIKE '%playstation 5%' OR lower(productTitle) LIKE '%ps5%' OR lower(productTitle) LIKE '%xbox series%' OR lower(productTitle) LIKE '%nintendo switch%') AND price < 70)"
    )
    suspend fun deleteIneligibleLegacyObservations(): Int

    /** Demo/unverified points are never valid history in this app. */
    @Query("DELETE FROM price_observations WHERE isVerified = 0")
    suspend fun deleteUnverifiedObservations(): Int

    /** Remove points attached to legacy mock products before deleting those products. */
    @Query("DELETE FROM price_observations WHERE productId IN (SELECT id FROM products WHERE id LIKE 'demo_%' OR lower(athMinDate) LIKE '%demostración%' OR lower(athMinStore) LIKE '%demostración%')")
    suspend fun deleteExplicitDemoObservations(): Int
}
