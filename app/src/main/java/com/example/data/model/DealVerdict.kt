package com.example.data.model

import androidx.compose.ui.graphics.Color

enum class VerdictLevel {
    UNVERIFIED,         // Datos insuficientes para clasificar la oferta
    ATH_RECORD,         // Record mínimo histórico absoluto
    NEAR_ATH,           // Rozando el mínimo (<5% por encima)
    FAIR_DISCOUNT,      // Oferta honesta (por debajo de la media de 90 días)
    STANDARD_PRICE,     // Precio ordinario habitual
    INFLATED_FAKE_DEAL, // OJO: Oferta falsa / inflada antes del descuento
    EXPENSIVE           // Más caro de lo normal
}

data class DealVerdict(
    val level: VerdictLevel,
    val title: String,
    val badgeText: String,
    val explanation: String,
    val iconName: String,
    val color: Color,
    val confidenceScore: Int = 100 // Porcentaje de fiabilidad matemática
) {
    companion object {
        fun unverified(): DealVerdict = DealVerdict(
            level = VerdictLevel.UNVERIFIED,
            title = "Datos de demostración",
            badgeText = "PRECIO SIN VERIFICAR",
            explanation = "Los precios, ofertas e historial de esta ficha son datos de ejemplo. Mínimo todavía no consulta la tienda en tiempo real, así que no puede confirmar una oferta ni un mínimo histórico.",
            iconName = "Info",
            color = Color(0xFF64748B),
            confidenceScore = 0
        )

        fun insufficientHistory(observationCount: Int): DealVerdict = DealVerdict(
            level = VerdictLevel.UNVERIFIED,
            title = "Historial insuficiente",
            badgeText = "HISTORIAL EN CONSTRUCCIÓN",
            explanation = "El precio actual tiene una fuente verificada, pero solo hay $observationCount observaciones y/o no cubren al menos 90 días. Mínimo no afirmará que es un récord ni calculará descuentos históricos hasta reunir al menos 3 observaciones verificadas a lo largo de al menos 90 días.",
            iconName = "Info",
            color = Color(0xFF64748B),
            confidenceScore = 0
        )

        fun analyze(
            currentPrice: Double,
            athMinPrice: Double,
            avg90dPrice: Double,
            athMaxPrice: Double,
            wasRecentlyInflated: Boolean = false,
            fakePriorPrice: Double? = null
        ): DealVerdict {
            val diffWithAth = currentPrice - athMinPrice
            val percentAboveAth = if (athMinPrice > 0) ((diffWithAth / athMinPrice) * 100) else 0.0

            return when {
                wasRecentlyInflated -> {
                    val inflatedMsg = if (fakePriorPrice != null) {
                        "¡Alerta 'Sin Trampa ni Cartón'! La tienda infló el precio de referencia a ${String.format("%.2f", fakePriorPrice)} € poco antes de aplicar la supuesta rebaja. En realidad, su precio medio habitual es de ${String.format("%.2f", avg90dPrice)} €."
                    } else {
                        "¡Atención! Este producto subió de precio días antes para simular una falsa gran rebaja. El precio actual está inflado respecto a su media."
                    }
                    DealVerdict(
                        level = VerdictLevel.INFLATED_FAKE_DEAL,
                        title = "¡Alerta Oferta Inflada!",
                        badgeText = "OJO: OFERTA ENGAÑOSA",
                        explanation = inflatedMsg,
                        iconName = "Warning",
                        color = Color(0xFFDC2626)
                    )
                }

                currentPrice <= athMinPrice + 0.05 -> {
                    DealVerdict(
                        level = VerdictLevel.ATH_RECORD,
                        title = "Mínimo registrado en los datos disponibles",
                        badgeText = "MÍNIMO OBSERVADO",
                        explanation = "El precio actual iguala la observación verificada más baja que conserva Mínimo. Esto no garantiza que sea el precio más bajo de toda la vida del producto.",
                        iconName = "CheckCircle",
                        color = Color(0xFF059669)
                    )
                }

                percentAboveAth in 0.01..5.0 -> {
                    DealVerdict(
                        level = VerdictLevel.NEAR_ATH,
                        title = "Cerca del mínimo observado",
                        badgeText = "A ${String.format("%.1f", percentAboveAth)}% DEL MÍNIMO OBSERVADO",
                        explanation = "El precio está ${String.format("%.2f", diffWithAth)} € por encima del mínimo de las observaciones verificadas disponibles.",
                        iconName = "TrendingDown",
                        color = Color(0xFF10B981)
                    )
                }

                currentPrice < avg90dPrice * 0.93 -> {
                    val savingsVsAvg = avg90dPrice - currentPrice
                    DealVerdict(
                        level = VerdictLevel.FAIR_DISCOUNT,
                        title = "Por debajo de la media observada",
                        badgeText = "POR DEBAJO DE LA MEDIA",
                        explanation = "El precio está ${String.format("%.2f", savingsVsAvg)} € por debajo de la media de las observaciones verificadas de los últimos 90 días. La fuente no garantiza que sea el mínimo de toda la vida del producto.",
                        iconName = "ThumbUp",
                        color = Color(0xFF0284C7)
                    )
                }

                currentPrice in (avg90dPrice * 0.93)..(avg90dPrice * 1.05) -> {
                    DealVerdict(
                        level = VerdictLevel.STANDARD_PRICE,
                        title = "Precio Habitual de Mercado",
                        badgeText = "PRECIO REGULAR",
                        explanation = "El precio está en la media cotidiana (${String.format("%.2f", avg90dPrice)} €). No hay prisa ni urgencia real por comprar.",
                        iconName = "Info",
                        color = Color(0xFF64748B)
                    )
                }

                else -> {
                    DealVerdict(
                        level = VerdictLevel.EXPENSIVE,
                        title = "Precio Elevado Actualmente",
                        badgeText = "CARO AHORA",
                        explanation = "Está notablemente más caro que su media habitual (${String.format("%.2f", avg90dPrice)} €). Recomendamos activar alerta y esperar.",
                        iconName = "ArrowUpward",
                        color = Color(0xFFD97706)
                    )
                }
            }
        }
    }
}
