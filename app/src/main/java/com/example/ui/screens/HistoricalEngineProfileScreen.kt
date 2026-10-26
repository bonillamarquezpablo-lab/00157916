package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig

private enum class EvidenceState(val label: String) {
    PRESENT_IN_CODE("En el código"),
    TESTED_BY_REPORT("Comprobado en informe"),
    PARTIAL("Parcial"),
    NEEDS_MEASUREMENT("Sin medir"),
    EXTERNAL_LIMIT("Depende de la fuente externa"),
    NEXT("Pendiente")
}

private data class EngineCapability(
    val title: String,
    val explanation: String,
    val state: EvidenceState
)

private data class EngineMetric(
    val title: String,
    val value: String,
    val whyItMatters: String
)

@Composable
fun HistoricalEngineProfileScreen(modifier: Modifier = Modifier) {
    val capabilities = remember {
        listOf(
            EngineCapability(
                "Búsqueda de capturas mediante Wayback/CDX",
                "El motor prepara consultas por URL y rutas alternativas para localizar páginas archivadas.",
                EvidenceState.PRESENT_IN_CODE
            ),
            EngineCapability(
                "Recuperación alternativa mediante Availability y Memento",
                "Hay rutas de respaldo para buscar capturas si la ruta principal no proporciona una respuesta útil. La cobertura depende de los archivos externos.",
                EvidenceState.PRESENT_IN_CODE
            ),
            EngineCapability(
                "Descarga y lectura de páginas históricas",
                "El código descarga HTML archivado y examina contenido de página y datos estructurados Product/Offer.",
                EvidenceState.PRESENT_IN_CODE
            ),
            EngineCapability(
                "Identificación del producto y de sus variantes",
                "Se comprueban señales de modelo, capacidad y otros datos para evitar asignar el precio de una variante distinta. Falta medir la tasa de aciertos y rechazos en una muestra amplia.",
                EvidenceState.PARTIAL
            ),
            EngineCapability(
                "Validación de precio, moneda y fecha",
                "El motor contiene controles de importe, moneda, fechas y conversión histórica cuando corresponde. La precisión global todavía no está cuantificada.",
                EvidenceState.PRESENT_IN_CODE
            ),
            EngineCapability(
                "Deduplicación y guardado local",
                "Los puntos históricos se contrastan con los registros locales de Room para no volver a insertar fechas ya guardadas.",
                EvidenceState.PRESENT_IN_CODE
            ),
            EngineCapability(
                "Pausa ante HTTP 429",
                "El informe aportado del 26/10/2026 confirma que Mínimo respetó la pausa global y no envió consultas al archivo durante ese bloqueo.",
                EvidenceState.TESTED_BY_REPORT
            ),
            EngineCapability(
                "Diagnóstico por 22 fases y detalle por captura",
                "La app puede describir las fases, marcar las omitidas y conservar detalles de capturas. Aún se puede mejorar la claridad de la causa raíz y el resumen final.",
                EvidenceState.PARTIAL
            ),
            EngineCapability(
                "Inspector de conexiones Chucker",
                "La dependencia está configurada para compilaciones debug y sideload; no se incluye en la variante release normal.",
                EvidenceState.PRESENT_IN_CODE
            ),
            EngineCapability(
                "Búsqueda acotada por subcategoría",
                "Existe carga diferida para subcategorías de móviles y accesorios y sus propios flujos filtrados. Hay que seguir comprobando la clasificación de títulos ambiguos.",
                EvidenceState.PARTIAL
            )
        )
    }
    val metrics = remember {
        listOf(
            EngineMetric(
                "Búsquedas que añaden al menos un precio histórico nuevo",
                "SIN MEDIR",
                "Mide cuántas búsquedas terminan añadiendo datos útiles, no solo cuántas capturas encuentran."
            ),
            EngineMetric(
                "Nuevos puntos históricos por búsqueda",
                "SIN MEDIR",
                "Cuenta solo fechas nuevas y verificadas guardadas en la base de datos."
            ),
            EngineMetric(
                "Capturas descargadas que producen un precio válido",
                "SIN MEDIR",
                "Permite saber si falla la disponibilidad de capturas, la descarga de páginas o la extracción del precio."
            ),
            EngineMetric(
                "Tiempo medio y percentil 95",
                "SIN MEDIR",
                "Mide duración normal y los casos lentos sin ocultarlos con una media."
            ),
            EngineMetric(
                "Errores por fase y proveedor",
                "SIN MEDIR",
                "Agrupa HTTP 429, límites de tiempo, errores de red, páginas sin precio y rechazos de identidad."
            ),
            EngineMetric(
                "Errores al identificar variantes",
                "SIN MEDIR",
                "Hace falta una colección de casos reales para contar modelos mal aceptados o productos válidos rechazados."
            )
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("historical_engine_profile"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Ficha técnica viva",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        "Motor de extracción del precio mínimo histórico",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Versión instalada", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(BuildConfig.VERSION_NAME, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Esta ficha distingue las funciones presentes en el código de las capacidades realmente medidas. No se calcula una puntuación de potencia sin datos que la respalden.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { SectionHeader("1. Qué sabe hacer el motor", "Estado basado en el código y en los informes disponibles") }

        items(capabilities, key = { it.title }) { capability ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            capability.title,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.padding(start = 4.dp))
                        StatePill(capability.state)
                    }
                    Text(
                        capability.explanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SectionHeader(
                "2. Potencia y eficacia medidas",
                "Estos huecos se rellenarán cuando tengamos mediciones comparables"
            )
        }

        items(metrics, key = { it.title }) { metric ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            metric.title,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold
                        )
                        StatePill(EvidenceState.NEEDS_MEASUREMENT)
                    }
                    Text(
                        metric.value,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        metric.whyItMatters,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { SectionHeader("3. Última evidencia documentada", "Informe aportado el 26/10/2026") }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Versión 1.7.838.1 · 01:27", fontWeight = FontWeight.Bold)
                    StatePill(EvidenceState.TESTED_BY_REPORT)
                    Text(
                        "HTTP 429 activó la pausa global antes de consultar CDX o Availability. Se superaron 2 de 22 fases; las otras 20 no se ejecutaron. No hubo capturas procesadas ni puntos nuevos en esta prueba.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Interpretación correcta: esta prueba valida el bloqueo de seguridad, pero no mide la eficacia de extracción porque el motor no llegó a ejecutarse.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalRule()
                    Text("Prueba anterior documentada · versión 1.7.835.1", fontWeight = FontWeight.Bold)
                    Text(
                        "Se descargaron y evaluaron cuatro capturas Wayback y se recuperaron dos precios de Memento; los datos coincidían con fechas ya existentes. Aquella prueba mostró extracción y deduplicación, pero no añadió fechas nuevas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { SectionHeader("4. Cómo evolucionará esta ficha", "La ficha debe cambiar con cada mejora real") }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Cada nueva versión debe registrar:", fontWeight = FontWeight.Bold)
                    Text("• Capacidad añadida o corregida y archivos modificados.")
                    Text("• Pruebas ejecutadas y resultado de cada una.")
                    Text("• Capturas vistas, páginas descargadas, precios válidos y puntos nuevos guardados.")
                    Text("• Tiempo empleado y errores separados por fase y proveedor.")
                    Text("• Limitaciones conocidas y el siguiente objetivo.")
                    HorizontalRule()
                    Text(
                        "Las cifras seguirán vacías mientras no exista una medición fiable. El siguiente paso técnico es recoger estas métricas de forma automática y redactada, sin guardar URLs completas, HTML ni claves privadas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Text(
                "Esta ficha es la línea base inicial. Las métricas no se actualizan solas todavía: hay que instrumentar el motor para agregarlas y actualizarlas al cerrar cada búsqueda.",
                modifier = Modifier.padding(vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatePill(state: EvidenceState) {
    val background = when (state) {
        EvidenceState.PRESENT_IN_CODE -> MaterialTheme.colorScheme.secondaryContainer
        EvidenceState.TESTED_BY_REPORT -> MaterialTheme.colorScheme.tertiaryContainer
        EvidenceState.PARTIAL -> MaterialTheme.colorScheme.primaryContainer
        EvidenceState.NEEDS_MEASUREMENT -> MaterialTheme.colorScheme.surfaceVariant
        EvidenceState.EXTERNAL_LIMIT -> MaterialTheme.colorScheme.errorContainer
        EvidenceState.NEXT -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when (state) {
        EvidenceState.EXTERNAL_LIMIT -> MaterialTheme.colorScheme.onErrorContainer
        EvidenceState.PRESENT_IN_CODE -> MaterialTheme.colorScheme.onSecondaryContainer
        EvidenceState.TESTED_BY_REPORT -> MaterialTheme.colorScheme.onTertiaryContainer
        EvidenceState.PARTIAL -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = background,
        contentColor = foreground,
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            state.label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun HorizontalRule() {
    androidx.compose.material3.HorizontalDivider()
}
