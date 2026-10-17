package com.example.data.model

/**
 * Visual price classification backed only by provider-validated observations.
 * A single quote is a first observation, never proof of a historical bargain.
 */
enum class PriceBadgeType {
    UNVERIFIED,
    SAVED_PENDING_REVALIDATION,
    FIRST_OBSERVATION,
    HISTORICAL_MINIMUM,
    GOOD_PRICE,
    HIGH_PRICE,
    AT_AVERAGE,
    HISTORY_IN_PROGRESS
}

data class PriceBadgeState(
    val type: PriceBadgeType,
    val label: String,
    val description: String
)

object PriceBadgeClassifier {
    fun classify(
        isPriceVerified: Boolean,
        currentPrice: Double,
        priorObservedPrices: List<Double>,
        averagePrice: Double,
        hasValidAverage: Boolean,
        isSavedPendingRevalidation: Boolean = false
    ): PriceBadgeState {
        if (isSavedPendingRevalidation) {
            return PriceBadgeState(
                PriceBadgeType.SAVED_PENDING_REVALIDATION,
                "PRECIO GUARDADO · PENDIENTE",
                "Mínimo conserva un precio observado, pero necesita revalidación y no se presenta como oferta actual."
            )
        }

        if (!isPriceVerified || !currentPrice.isFinite() || currentPrice <= 0.0) {
            return PriceBadgeState(
                PriceBadgeType.UNVERIFIED,
                "PRECIO NO VERIFICADO",
                "Todavía no hay una observación online validada."
            )
        }

        val validPriorPrices = priorObservedPrices.filter { it.isFinite() && it > 0.0 }
        if (validPriorPrices.isEmpty()) {
            return PriceBadgeState(
                PriceBadgeType.FIRST_OBSERVATION,
                "PRIMER PRECIO REGISTRADO",
                "Hay una cotización real, pero aún no un mínimo histórico comparable."
            )
        }

        val historicalMinimum = validPriorPrices.minOrNull()!!
        if (currentPrice <= historicalMinimum) {
            return PriceBadgeState(
                PriceBadgeType.HISTORICAL_MINIMUM,
                "🔥 MÍNIMO OBSERVADO",
                "El precio iguala o mejora el mínimo de las observaciones anteriores."
            )
        }

        if (!hasValidAverage || !averagePrice.isFinite() || averagePrice <= 0.0) {
            return PriceBadgeState(
                PriceBadgeType.HISTORY_IN_PROGRESS,
                "HISTORIAL EN CONSTRUCCIÓN",
                "Aún no hay una media fiable para clasificar esta oferta."
            )
        }

        return when {
            currentPrice < averagePrice -> PriceBadgeState(
                PriceBadgeType.GOOD_PRICE,
                "🟢 BUEN PRECIO",
                "El precio está por debajo de la media del historial válido."
            )
            currentPrice > averagePrice -> PriceBadgeState(
                PriceBadgeType.HIGH_PRICE,
                "🔴 PRECIO ALTO",
                "El precio está por encima de la media del historial válido."
            )
            else -> PriceBadgeState(
                PriceBadgeType.AT_AVERAGE,
                "PRECIO EN LA MEDIA",
                "El precio coincide con la media del historial válido."
            )
        }
    }
}
