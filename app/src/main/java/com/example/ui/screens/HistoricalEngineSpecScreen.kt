package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig

private enum class EngineEvidenceState(val label: String, val color: Color) {
    CODE("En el código", Color(0xFF2563EB)),
    INVESTIGATING("En investigación", Color(0xFFD97706)),
    UNMEASURED("Sin medir", Color(0xFF64748B)),
    VERIFIED("Verificado por pruebas", Color(0xFF059669))
}

private data class EngineCapability(
    val title: String,
    val detail: String,
    val state: EngineEvidenceState
)

private data class EngineMetric(
    val title: String,
    val value: String = "— Sin medir todavía",
    val explanation: String
)

private val engineCapabilities = listOf(
    EngineCapability(
        "Wayback CDX",
        "Consulta el índice de capturas archivadas y prepara URLs candidatas.",
        EngineEvidenceState.CODE
    ),
    EngineCapability(
        "Availability y rutas alternativas",
        "Dispone de rutas de recuperación cuando la consulta principal no proporciona capturas utilizables.",
        EngineEvidenceState.CODE
    ),
    EngineCapability(
        "Memento como respaldo",
        "Existe un cliente alternativo para intentar recuperar capturas de otro servicio de archivo.",
        EngineEvidenceState.CODE
    ),
    EngineCapability(
        "Identificación de producto y variante",
        "Contrasta título, modelo y capacidad para reducir la mezcla de variantes distintas.",
        EngineEvidenceState.CODE
    ),
    EngineCapability(
        "Extracción y validación del precio",
        "Analiza datos estructurados y valida el importe, moneda y coherencia antes de aceptar un punto.",
        EngineEvidenceState.CODE
    ),
    EngineCapability(
        "Deduplicación y guardado local",
        "Evita añadir de nuevo una observación ya guardada y conserva el histórico en la base de datos local.",
        EngineEvidenceState.CODE
    ),
    EngineCapability(
        "Diagnóstico por fases",
        "El informe técnico registra 22 fases generales y puede añadir detalles individuales de capturas.",
        EngineEvidenceState.CODE
    ),
    EngineCapability(
        "Control de límites HTTP 429",
        "La pausa global impide insistir contra Internet Archive mientras el bloqueo temporal está activo.",
        EngineEvidenceState.CODE
    ),
    EngineCapability(
        "Inspector HTTP Chucker",
        "La dependencia está integrada para las compilaciones debug y sideload; no se incluye en release normal.",
        EngineEvidenceState.CODE
    ),
    EngineCapability(
        "Recuperación sostenida bajo carga real",
        "Hay que medir si las nuevas rutas consiguen añadir fechas nuevas con rapidez y sin alcanzar límites.",
        EngineEvidenceState.INVESTIGATING
    )
)

private val engineMetrics = listOf(
    EngineMetric(
        "Consultas históricas que guardan al menos una fecha nueva",
        explanation = "Medir por búsqueda, diferenciando capturas recuperadas de puntos nuevos realmente insertados."
    ),
    EngineMetric(
        "Porcentaje de consultas con algún precio histórico verificado",
        explanation = "Contar solo precios que superen la validación de producto, fecha, importe y moneda."
    ),
    EngineMetric(
        "Tiempo medio y percentil 95 de búsqueda",
        explanation = "Separar duración total, espera de red, descarga HTML y extracción."
    ),
    EngineMetric(
        "Fechas históricas nuevas por consulta",
        explanation = "No contar duplicados de Room como si fueran nuevas fechas."
    ),
    EngineMetric(
        "Porcentaje de respuestas HTTP 429 y tiempos de espera",
        explanation = "Separar bloqueos del proveedor, timeouts, errores de red y ausencia real de capturas."
    ),
    EngineMetric(
        "Precisión de identificación de modelo y variante",
        explanation = "Medir aciertos y rechazos correctos con casos reales anotados."
    ),
    EngineMetric(
        "Cobertura temporal conseguida",
        explanation = "Primera y última fecha verificadas, huecos entre fechas y antigüedad del último punto."
    ),
    EngineMetric(
        "Errores por 100 búsquedas y regresiones",
        explanation = "Comparar versiones usando el mismo conjunto de pruebas."
    )
)

@Composable
fun HistoricalEngineSpecScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("historical_engine_spec_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "Ficha técnica del motor histórico",
                        modifier = Modifier.padding(start = 9.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Text(
                    "Capacidades, límites conocidos y medidas pendientes. Esta ficha distingue lo que existe en el código de lo que ya se ha medido en pruebas reales.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                SpecValueRow("Versión instalada", BuildConfig.VERSION_NAME)
                SpecValueRow("Fases de diagnóstico", "22 fases generales")
                SpecValueRow("Métricas de eficacia", "${engineMetrics.size} pendientes de medir")
            }
        }

        SectionHeading(
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
            title = "Capacidades del motor",
            subtitle = "Que una función esté en el código no significa que ya conozcamos su tasa de éxito."
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                engineCapabilities.forEachIndexed { index, capability ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                capability.title,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            StatePill(capability.state)
                        }
                        Text(
                            capability.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (index < engineCapabilities.lastIndex) HorizontalDivider()
                }
            }
        }

        SectionHeading(
            icon = { Icon(Icons.Default.Speed, contentDescription = null) },
            title = "Potencia y eficacia: datos pendientes",
            subtitle = "Estos campos se rellenarán cuando instrumentemos mediciones fiables; no se inventan porcentajes."
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                engineMetrics.forEachIndexed { index, metric ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(metric.title, fontWeight = FontWeight.Bold)
                        Text(
                            metric.value,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            metric.explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (index < engineMetrics.lastIndex) HorizontalDivider()
                }
            }
        }

        SectionHeading(
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = "Problemas abiertos",
            subtitle = "Puntos que hay que resolver y comprobar con informes reales."
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                OpenIssue(
                    "HTTP 429 de Internet Archive",
                    "Respetar la pausa del proveedor es correcto, pero deja la búsqueda detenida hasta que vuelva a permitir consultas."
                )
                HorizontalDivider()
                OpenIssue(
                    "Timeouts en CDX",
                    "Las consultas por prefijo o por bloques temporales pueden agotar su tiempo; hay que medir cuándo conviene continuar, reintentar o cambiar de fuente."
                )
                HorizontalDivider()
                OpenIssue(
                    "Capturas repetidas sin fechas nuevas",
                    "Una captura válida puede contener un precio ya guardado. El informe debe separar capturas válidas, observaciones duplicadas y puntos nuevos."
                )
                HorizontalDivider()
                OpenIssue(
                    "Cobertura real por producto",
                    "Todavía falta una medida fiable que indique cuánto histórico recupera el motor según tienda, producto y período."
                )
            }
        }

        SectionHeading(
            icon = { Icon(Icons.Default.Timeline, contentDescription = null) },
            title = "Registro de evolución",
            subtitle = "Añadir aquí cada hito solo después de que su cambio y sus pruebas estén confirmados."
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Base actual del registro", fontWeight = FontWeight.Bold)
                Text(
                    "El motor dispone de rutas de archivo, validación de precios y diagnóstico por 22 fases. El cierre de una fase de código no equivale a demostrar más precios nuevos.",
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider()
                Text("Próximo hito", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Instrumentar resultados por búsqueda: capturas recuperadas, precios aceptados, duplicados, puntos nuevos, duración y motivo final.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            "Ficha inicial · Versión de la app: ${BuildConfig.VERSION_NAME} · Estado de las cifras: aún no instrumentadas",
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionHeading(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon()
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        }
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SpecValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatePill(state: EngineEvidenceState) {
    Surface(
        color = state.color.copy(alpha = 0.13f),
        shape = RoundedCornerShape(50)
    ) {
        Text(
            state.label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = state.color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun OpenIssue(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
