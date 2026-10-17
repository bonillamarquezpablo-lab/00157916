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
        val validPriorPrices = priorObservedPrices.filter { it.isFinite() && it > 0.0 }
        val hasUsableCurrentPrice = currentPrice.isFinite() && currentPrice > 0.0

        // A saved-but-stale quote may still be identified as the lowest recorded point,
        // but it must remain visibly marked as pending and must never look like a live deal.
        if (isSavedPendingRevalidation) {
            val savedMinimum = validPriorPrices.minOrNull()
            if (hasUsableCurrentPrice && savedMinimum != null && currentPrice <= savedMinimum) {
                return PriceBadgeState(
                    PriceBadgeType.HISTORICAL_MINIMUM,
                    "MÍNIMO OBSERVADO · PENDIENTE",
                    "La última cotización guardada iguala o mejora el mínimo de las observaciones válidas que conserva Mínimo. Necesita revalidación y no debe interpretarse como una oferta actual."
                )
            }
            return PriceBadgeState(
                PriceBadgeType.SAVED_PENDING_REVALIDATION,
                "PRECIO GUARDADO · PENDIENTE",
                "Mínimo conserva un precio observado, pero necesita revalidación y no se presenta como oferta actual."
            )
        }

        if (!isPriceVerified || !hasUsableCurrentPrice) {
            return PriceBadgeState(
                PriceBadgeType.UNVERIFIED,
                "PRECIO NO VERIFICADO",
                "Todavía no hay una observación online validada."
            )
        }

        if (validPriorPrices.isEmpty()) {
            return PriceBadgeState(
                PriceBadgeType.FIRST_OBSERVATION,
                "PRIMER PRECIO REGISTRADO",
                "Hay una cotización real, pero aún no un mínimo histórico comparable."
            )
        }

        val historicalMinimum = validPriorPrices.minOrNull()!!
        if (currentPrice <= historicalMinimum) {
            return if (hasValidAverage && averagePrice.isFinite() && averagePrice > 0.0) {
                PriceBadgeState(
                    PriceBadgeType.HISTORICAL_MINIMUM,
                    "🔥 MÍNIMO OBSERVADO",
                    "El precio iguala o mejora el mínimo de las observaciones anteriores dentro de un historial verificado de al menos 90 días."
                )
            } else {
                PriceBadgeState(
                    PriceBadgeType.HISTORICAL_MINIMUM,
                    "🔥 MÍNIMO OBSERVADO",
                    "El precio actual iguala o mejora el más bajo de los precios en vivo verificados que conserva Mínimo. El historial aún no cubre 90 días: este resultado describe solo el periodo observado, no un mínimo histórico de largo plazo."
                )
            }
        }

        if (!hasValidAverage || !averagePrice.isFinite() || averagePrice <= 0.0) {
            return PriceBadgeState(
                PriceBadgeType.HISTORY_IN_PROGRESS,
                "HISTORIAL EN CONSTRUCCIÓN",
                "Hay precios observados, pero aún no hay cobertura verificada suficiente para comparar la media."
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
