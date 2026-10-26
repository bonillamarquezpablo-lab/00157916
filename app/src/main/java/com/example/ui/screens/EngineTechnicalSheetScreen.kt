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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig

private data class EngineSheetItem(
    val name: String,
    val detail: String,
    val status: String,
    val evidence: String
)

private val engineCapabilities = listOf(
    EngineSheetItem(
        "Internet Archive / Wayback",
        "Consulta el índice CDX y dispone de rutas alternativas de recuperación de capturas.",
        "EN CÓDIGO",
        "El informe de búsqueda distingue CDX, Availability y las fases que no llegaron a ejecutarse."
    ),
    EngineSheetItem(
        "Memento / Arquivo.pt",
        "Cliente independiente para buscar páginas archivadas cuando Wayback no aporta una captura utilizable.",
        "EN CÓDIGO",
        "Las páginas recuperadas pasan por la validación estricta de producto, variante, fecha e importe."
    ),
    EngineSheetItem(
        "Lectura de páginas archivadas",
        "Descarga HTML e intenta localizar información estructurada de producto y oferta.",
        "EN CÓDIGO",
        "La extracción debe identificar el producto correcto; encontrar un número en una página no basta."
    ),
    EngineSheetItem(
        "Validación del producto y la variante",
        "Comprueba señales de identidad, modelo y capacidad para evitar mezclar versiones diferentes.",
        "EN CÓDIGO",
        "Hay pruebas automatizadas para casos concretos; la precisión global real aún no se ha medido."
    ),
    EngineSheetItem(
        "Validación del precio histórico",
        "Valida importe, moneda, fecha y límites razonables; contempla conversión de moneda cuando hay datos suficientes.",
        "EN CÓDIGO",
        "Solo una oferta que supera las comprobaciones debe convertirse en observación histórica."
    ),
    EngineSheetItem(
        "Consolidación y almacenamiento",
        "Elimina duplicados y conserva observaciones históricas en la base de datos local Room.",
        "EN CÓDIGO",
        "Que una captura sea válida no implica que cree un punto nuevo: puede corresponder a una fecha ya guardada."
    ),
    EngineSheetItem(
        "Diagnóstico de 22 fases",
        "Registra el recorrido general y, cuando hay datos, detalles individuales de descarga, análisis y rechazo.",
        "EN CÓDIGO",
        "Los informes permiten separar una fase omitida, un tiempo de espera, un bloqueo HTTP o un precio rechazado."
    ),
    EngineSheetItem(
        "Control de límites y tiempos de espera",
        "Respeta una pausa global tras HTTP 429 y limita determinadas rutas alternativas para evitar peticiones repetidas.",
        "EN CÓDIGO",
        "Un bloqueo del proveedor puede impedir temporalmente una búsqueda correcta; el motor no puede eliminar ese límite."
    ),
    EngineSheetItem(
        "Chucker · inspector de red",
        "Incluido en la variante de diagnóstico sideload; conserva tráfico durante una hora y redacta cabeceras sensibles configuradas.",
        "EN APK DE DIAGNÓSTICO",
        "El flujo de compilación comprueba que Chucker está presente en la APK sideload. No debe confundirse con la APK normal."
    ),
    EngineSheetItem(
        "Caché y carga diferida",
        "Hay flujos de productos por categoría/subcategoría y trabajo de filtrado fuera del hilo principal.",
        "EN CÓDIGO",
        "La mejora debe confirmarse con mediciones de tiempos y uso de memoria en dispositivos reales."
    )
)

private val engineMeasurements = listOf(
    "Porcentaje de búsquedas históricas que guardan al menos un punto nuevo",
    "Porcentaje de productos con histórico verificable",
    "Mediana y percentil 95 de duración de una búsqueda",
    "Número medio de fechas candidatas y fechas que superan la validación",
    "Tasa de capturas rechazadas por identidad o variante incorrecta",
    "Tasa de respuestas HTTP 429, errores de red y tiempos de espera",
    "Cobertura histórica por tienda, categoría y antigüedad",
    "Memoria y consumo de batería durante una búsqueda"
)

private val engineRoadmap = listOf(
    Triple("1. Ficha técnica visible y estados honestos", "Hecha en esta primera versión", "HECHO"),
    Triple("2. Informes completos por captura y causa de rechazo", "Mejorar el diagnóstico para localizar el punto exacto del fallo", "EN CURSO"),
    Triple("3. Recuperar más fechas históricas útiles", "Mejorar el muestreo temporal, los fallbacks y la recuperación tras bloqueos", "PENDIENTE"),
    Triple("4. Extracción y variantes más fiables", "Ampliar casos reales y evitar mezclar modelos, capacidades y colores", "EN CURSO"),
    Triple("5. Pruebas de regresión del motor", "Cubrir éxitos, cero resultados, duplicados, fallos de red y datos ambiguos", "EN CURSO"),
    Triple("6. Métricas de eficacia y rendimiento", "Medir éxito, latencia, cobertura, falsos positivos y consumo", "PENDIENTE"),
    Triple("7. Verificación de APK y diagnóstico de red", "Comprobar compilación, firma e inclusión de Chucker en la versión de diagnóstico", "EN CÓDIGO")
)

@Composable
fun EngineTechnicalSheetScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("engine_technical_sheet"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().testTag("engine_sheet_back")
        ) {
            Text("Volver a Ajustes")
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("MÍNIMO · MOTOR HISTÓRICO", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Ficha técnica evolutiva",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    "Objetivo: encontrar precios antiguos reales, demostrar que pertenecen al producto correcto y guardar una serie histórica fiable sin duplicados.",
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider()
                SheetInfoLine("Versión de la app", BuildConfig.VERSION_NAME)
                SheetInfoLine("Estado de esta ficha", "Base inicial · 26/10/2026")
                SheetInfoLine("Métricas reales de eficacia", "Pendientes de instrumentar")
            }
        }

        SectionHeading(
            "1. Capacidades identificadas en el código",
            "«En código» no significa que funcione siempre ni que ya se haya medido su precisión."
        )
        engineCapabilities.forEach { item ->
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(15.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            item.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        StatePill(item.status)
                    }
                    Text(item.detail, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        item.evidence,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        SectionHeading(
            "2. Medidas que aún no tenemos",
            "Estos campos se quedan pendientes hasta que el motor registre suficientes pruebas reales. No se rellenan con estimaciones inventadas."
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                engineMeasurements.forEachIndexed { index, name ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "— Pendiente de medir",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    if (index != engineMeasurements.lastIndex) HorizontalDivider()
                }
            }
        }


        SectionHeading(
            "3. Evidencia real de referencia",
            "Son casos concretos, no estadísticas generales. Se conservan para comparar futuras versiones sin exagerar los resultados."
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("26/10/2026 · Mínimo 1.7.838.1", fontWeight = FontWeight.Bold)
                StatePill("PAUSA HTTP 429")
                Text(
                    "El control de pausa se activó antes de consultar CDX. Se completaron 2 de las 22 fases; las otras 20 quedaron sin ejecutar. Esto confirma la parada preventiva, pero no prueba la capacidad de extracción en ese intento.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                Text("26/10/2026 · informe completo anterior · Mínimo 1.7.835.1", fontWeight = FontWeight.Bold)
                StatePill("CAPTURAS VÁLIDAS · 0 PUNTOS NUEVOS")
                Text(
                    "Se descargaron y evaluaron 4 capturas Wayback con coincidencia de producto y oferta estructurada. Se comprobaron 5 observaciones, pero ya existían en la base local. Memento aportó 2 observaciones más, también duplicadas. El resultado demuestra recuperación y validación en ese caso, no una tasa general de éxito.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionHeading(
            "4. Hoja de ruta de evolución",
            "Cada fase cambiará de estado solo cuando exista código y evidencia suficiente."
        )
        engineRoadmap.forEach { (title, detail, status) ->
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatePill(status)
                }
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Regla de control", fontWeight = FontWeight.Bold)
                Text(
                    "Una mejora no se marcará como conseguida solo porque compile. Para subir de nivel necesita pruebas automáticas y, cuando corresponda, una prueba real con un resultado comprobable.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SheetInfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatePill(value: String) {
    val container = when (value) {
        "HECHO", "EN CÓDIGO" -> MaterialTheme.colorScheme.primaryContainer
        "EN CURSO" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (value) {
        "HECHO", "EN CÓDIGO" -> MaterialTheme.colorScheme.onPrimaryContainer
        "EN CURSO" -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(100.dp)) {
        Text(
            value,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
    }
}
