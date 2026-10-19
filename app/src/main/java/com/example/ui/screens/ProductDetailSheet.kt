package com.example.ui.screens

import android.content.Intent
import com.example.security.safeExternalHttpsUrl
import com.example.security.openExternalHttpsUrl
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Product
import com.example.data.model.ProductCategory
import com.example.data.model.Store
import com.example.data.remote.WaybackTraceStatus
import com.example.data.remote.extractDirectRetailerUrl
import com.example.ui.viewmodel.WaybackDiagnosticEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.ui.components.DealVerdictBadge
import com.example.ui.components.HeroPriceBadge
import com.example.ui.components.PriceHistoryChart
import com.example.ui.components.ProductImageView
import com.example.ui.components.StoreComparisonView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailSheet(
    product: Product,
    onDismiss: () -> Unit,
    onOpenSetAlert: () -> Unit,
    onCompareStores: () -> Unit = {},
    onSearchFreeHistory: () -> Unit = {},
    freeHistoryStatus: String? = null,
    isSearchingFreeHistory: Boolean = false,
    waybackDiagnosticSteps: List<WaybackDiagnosticEntry> = emptyList(),
    serpApiActionStatus: String? = null,
    isComparingStores: Boolean = false
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showWaybackDiagnostics by remember(product.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val spanishLocale = remember { Locale("es", "ES") }
    val observedAtFormatter = remember { SimpleDateFormat("dd/MM HH:mm", spanishLocale) }
    val savedDateFormatter = remember { SimpleDateFormat("dd/MM/yyyy", spanishLocale) }
    val liveHistoryPoints = remember(product.priceHistory) {
        product.priceHistory.filterNot { it.isExternalHistoryEvidence }
    }
    val archivedHistoryPoints = remember(product.priceHistory) {
        product.priceHistory.filter { it.isArchiveEvidence }
    }
    val googleInsightPoints = remember(product.priceHistory) {
        product.priceHistory.filter { it.isGoogleInsightEvidence }
    }
    val oldestArchivedDate = remember(archivedHistoryPoints, savedDateFormatter) {
        archivedHistoryPoints.minByOrNull { it.timestamp }?.let {
            savedDateFormatter.format(Date(it.timestamp))
        }
    }
    val oldestGoogleInsightDate = remember(googleInsightPoints, savedDateFormatter) {
        googleInsightPoints.minByOrNull { it.timestamp }?.let {
            savedDateFormatter.format(Date(it.timestamp))
        }
    }
    val historicalSourceSummary = remember(
        googleInsightPoints, archivedHistoryPoints, oldestArchivedDate, oldestGoogleInsightDate
    ) {
        buildString {
            if (googleInsightPoints.isNotEmpty()) {
                append("Google Shopping: ${googleInsightPoints.size} puntos")
                oldestGoogleInsightDate?.let { append(" desde $it") }
                append(".")
            }
            if (archivedHistoryPoints.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append("Internet Archive: ${archivedHistoryPoints.size} capturas")
                oldestArchivedDate?.let { append(" desde $it") }
                append(".")
            }
            if (isEmpty()) {
                append("Aún no hay historial verificable. «Buscar histórico gratis» consulta capturas públicas sin gastar cuota de SerpApi, pero solo podrá reconstruir el precio si hay una captura con título y precio en euros verificables.")
            } else {
                append(" Puede haber huecos; stock, envío y precio final deben confirmarse en la tienda.")
            }
        }
    }
    // Historical archive/insight points must never be presented as the source for today's price.
    val latestLivePricePoint = remember(liveHistoryPoints) { liveHistoryPoints.maxByOrNull { it.timestamp } }
    val isDisplayingSavedArchivePrice =
        product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION &&
            !product.isPriceVerified && product.savedHistoricalPrice != null
    val displayedPriceObservedAt = if (isDisplayingSavedArchivePrice) {
        product.savedHistoricalPriceObservedAt
    } else {
        product.priceObservedAt
    }
    val sharePrice = if (isDisplayingSavedArchivePrice) {
        product.savedHistoricalPrice ?: product.currentPrice
    } else {
        product.currentPrice
    }
    val historyDayKey = System.currentTimeMillis() / 86_400_000L
    val hasCurrentVerifiedHistory = remember(
        product,
        product.priceDisplayStatus,
        historyDayKey
    ) {
        product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT &&
            product.hasSufficientHistory
    }
    val currentSourceLabel = if (!product.isPriceVerified && product.savedHistoricalPrice != null) {
        product.savedHistoricalPriceSource.takeIf { it.isNotBlank() } ?: "Historial archivado"
    } else if (!product.isPriceVerified) {
        "Sin precio actual verificado"
    } else {
        product.priceSource.takeIf { it.isNotBlank() }
            ?: latestLivePricePoint?.eventLabel?.takeIf { it.isNotBlank() }
            ?: "Fuente no disponible"
    }
    val currentLinkType = if (!product.isPriceVerified) {
        "Sin oferta actual verificada"
    } else {
        product.offers.firstOrNull { it.url == product.storeUrl }?.linkType
            ?: latestLivePricePoint?.linkType
            ?: "directo"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("product_detail_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(product.currentStore.brandColor.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = product.currentStore.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = product.currentStore.brandColor
                        )
                    }

                    if (product.category != ProductCategory.ALL) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = product.category.displayName,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row {
                    IconButton(
                        onClick = {
                            val shareText = when {
                                hasCurrentVerifiedHistory ->
                                    "Precio observado para ${product.title}:\\n" +
                                        "• Precio actual verificado: ${String.format(spanishLocale, "%.2f", product.currentPrice)} € en ${product.currentStore.name}\\n" +
                                        "• Mínimo observado: ${String.format(spanishLocale, "%.2f", product.athMinPrice)} € (${product.athMinDate} en ${product.athMinStore})\\n" +
                                        "• Diagnóstico: ${product.verdict.badgeText} - ${product.verdict.explanation}"
                                product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT ->
                                    "Precio observado para ${product.title}: ${String.format(spanishLocale, "%.2f", product.currentPrice)} € en ${product.currentStore.name}. " +
                                        "Aún no hay cobertura histórica suficiente para afirmar un mínimo de largo plazo o un descuento real."
                                product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION ->
                                    "Último precio guardado para ${product.title}: ${String.format(spanishLocale, "%.2f", sharePrice)} €. " +
                                        (displayedPriceObservedAt?.let { "Fecha: ${savedDateFormatter.format(Date(it))}. " }.orEmpty()) +
                                        "Fuente: $currentSourceLabel. No confirma una oferta actual, stock ni envío."
                                else ->
                                    "Ficha sin precio verificado de Mínimo: ${product.title}.\\nNo hay una oferta actual validada ni un precio histórico confirmado que se pueda compartir."
                            }
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Auditoría de precio: ${product.title}")
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(Intent.createChooser(intent, "Compartir auditoría de precio"))
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Compartir informe",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar"
                        )
                    }
                }
            }

            // Hero Product Photo
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            ) {
                ProductImageView(
                    product = product,
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentScale = ContentScale.Fit,
                    showAthRibbon = product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT && product.isAtAth
                )
            }

            // Title
            Text(
                text = product.title,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Prominent price classification backed by previous saved observations.
            HeroPriceBadge(
                state = product.priceBadgeState,
                modifier = Modifier.fillMaxWidth()
            )

            // Existing analysis certificate.
            DealVerdictBadge(verdict = product.verdict, compact = false)

            if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.DEMONSTRATION) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Datos de demostración · sin precio real guardado",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (product.priceHistory.isNotEmpty()) {
                                "El precio actual todavía no está confirmado. Los puntos del gráfico son precios antiguos de fuentes verificables; no demuestran el precio actual, el stock ni el envío."
                            } else {
                                "Mínimo todavía no ha confirmado el precio actual de este producto con una fuente y una fecha verificables. No uses los valores de referencia como una oferta real; el histórico solo aparecerá si encontramos capturas con precios verificables."
                            },
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (product.priceDisplayStatus != com.example.data.model.PriceDisplayStatus.DEMONSTRATION) {
            // Key Price Metrics Grid
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(14.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Current price
                        Column {
                            Text(
                                text = if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT) "Precio actual verificado" else "Precio guardado · pendiente de revalidación",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${String.format(spanishLocale, "%.2f", if (!product.isPriceVerified && product.savedHistoricalPrice != null) product.savedHistoricalPrice else product.currentPrice)} €",
                                fontWeight = FontWeight.Black,
                                fontSize = 20.sp,
                                color = if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT && product.isAtAth) Color(0xFF059669) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            displayedPriceObservedAt?.let { observedAt ->
                                Text(
                                    text = if (isDisplayingSavedArchivePrice) {
                                        "Precio guardado el " + savedDateFormatter.format(Date(observedAt))
                                    } else if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION) {
                                        "Guardado " + observedAtFormatter.format(Date(observedAt))
                                    } else {
                                        "Consultado " + observedAtFormatter.format(Date(observedAt))
                                    },
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION &&
                                !isDisplayingSavedArchivePrice
                            ) {
                                Text(
                                    text = product.priceFreshnessLabel,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            Text(
                                text = if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION) {
                                    "Última fuente guardada: $currentSourceLabel · no confirma precio actual ni stock."
                                } else {
                                    "$currentSourceLabel · confirma precio final y stock en la tienda."
                                },
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (product.isPriceVerified) {
                                Text(
                                    text = "Enlace: $currentLinkType",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Do not infer a historical low from a single query.
                        Column(horizontalAlignment = Alignment.End) {
                            if (hasCurrentVerifiedHistory) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF10B981))
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "MÍNIMO OBSERVADO",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF059669)
                                    )
                                }
                                Text(
                                    text = "${String.format(spanishLocale, "%.2f", product.athMinPrice)} €",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 20.sp,
                                    color = Color(0xFF059669)
                                )
                                Text(
                                    text = "${product.athMinDate} en ${product.athMinStore}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = if (product.priceHistory.isNotEmpty()) "Primer registro guardado" else "Historial en curso",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = when {
                                        liveHistoryPoints.size == 1 -> "1 observación real · la gráfica ya está disponible"
                                        liveHistoryPoints.size > 1 -> "${liveHistoryPoints.size} fechas registradas · evolución en curso"
                                        else -> "Se necesitan observaciones reales para mostrar la evolución"
                                    },
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (hasCurrentVerifiedHistory || (product.isOfficialPvpVerified && product.officialPvp > 0.0)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (hasCurrentVerifiedHistory) {
                                Column {
                                    Text(
                                        text = "Media de 90 días",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${String.format(spanishLocale, "%.2f", product.avg90dPrice)} €",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                            if (product.isOfficialPvpVerified && product.officialPvp > 0.0) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "PVP de referencia",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${String.format(spanishLocale, "%.2f", product.officialPvp)} €",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        textDecoration = TextDecoration.LineThrough
                                    )
                                }
                            }
                            if (product.isPriceFresh && product.isOfficialPvpVerified && product.officialPvp > product.currentPrice) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "Diferencia frente al PVP",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${String.format(spanishLocale, "%.2f", product.realSavingsAmount)} € (${product.realSavingsPercent}%)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color(0xFF059669)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (product.isPriceVerified || product.priceHistory.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Historial anterior a Mínimo",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        historicalSourceSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Only validated observations reach priceHistory. Show genuine archive captures
            // even when there is no verified current quote; never extrapolate missing dates.
            PriceHistoryChart(
                priceHistory = liveHistoryPoints,
                avg90dPrice = product.avg90dPrice,
                archiveHistory = archivedHistoryPoints,
                googleInsightHistory = googleInsightPoints
            )

            // Only show offers that came back from the latest provider query.
            if (product.offers.isNotEmpty()) {
                StoreComparisonView(
                    offers = product.offers,
                    athPrice = product.athMinPrice,
                    hasSufficientHistory = hasCurrentVerifiedHistory,
                    productTitle = product.title
                )
            }

            // The price provider does not independently verify the bundled EAN.
            if (product.isEanVerified && product.ean.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.QrCode,
                    contentDescription = "Código de barras",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "EAN verificado: ${product.ean}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            }
            }

            // Free archive lookup needs a product destination, not a verified live quote.
            if (product.storeUrl.isNotBlank() ||
                product.currentStore.officialUrl.isNotBlank() ||
                product.offers.isNotEmpty()
            ) {
                OutlinedButton(
                    onClick = {
                        showWaybackDiagnostics = true
                        onSearchFreeHistory()
                    },
                    enabled = !isSearchingFreeHistory && !isComparingStores,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("free_history_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (isSearchingFreeHistory) "Buscando histórico gratuito…" 
                        else "Buscar histórico gratis · Internet Archive"
                    )
                }
                freeHistoryStatus?.let { status ->
                    Text(
                        text = status,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = { showWaybackDiagnostics = !showWaybackDiagnostics },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .testTag("wayback_diagnostic_toggle"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        if (showWaybackDiagnostics) "Ocultar diagnóstico Wayback"
                        else "Ver diagnóstico Wayback · 15 pasos"
                    )
                }
                if (showWaybackDiagnostics || isSearchingFreeHistory) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("wayback_diagnostic_panel"),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Telemetría local de Wayback",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                "Observa la búsqueda solicitada: no lanza peticiones adicionales. Se ocultan URLs completas, parámetros y contenido HTML.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            waybackDiagnosticSteps.forEach { entry ->
                                val statusLabel = when (entry.status) {
                                    WaybackTraceStatus.PENDING -> "PENDIENTE"
                                    WaybackTraceStatus.RUNNING -> "EN CURSO"
                                    WaybackTraceStatus.PASSED -> "SUPERADO"
                                    WaybackTraceStatus.FAILED -> "FALLÓ"
                                    WaybackTraceStatus.SKIPPED -> "NO EJECUTADO"
                                }
                                val statusColor = when (entry.status) {
                                    WaybackTraceStatus.PASSED -> Color(0xFF047857)
                                    WaybackTraceStatus.FAILED -> Color(0xFFB91C1C)
                                    WaybackTraceStatus.RUNNING -> Color(0xFF1D4ED8)
                                    WaybackTraceStatus.PENDING,
                                    WaybackTraceStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            "${entry.step}. ${entry.title}",
                                            modifier = Modifier.weight(1f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            statusLabel,
                                            color = statusColor,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    if (entry.detail.isNotBlank()) {
                                        Text(
                                            entry.detail,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 10.sp
                                        )
                                    }
                                    entry.durationMs?.let { duration ->
                                        Text(
                                            "Duración del último intento: ${duration} ms",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 9.sp
                                        )
                                    }
                                }
                                if (entry.step != 15) {
                                    Spacer(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Manual, quota-confirmed store comparison.
            OutlinedButton(
                onClick = onCompareStores,
                enabled = !isComparingStores,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("compare_stores_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (isComparingStores) "Consultando tiendas…" else "Comparar tiendas · 1 búsqueda")
            }
            serpApiActionStatus?.let { status ->
                Text(
                    text = status,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Primary Actions: Alert setting and Open store
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpenSetAlert,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("set_alert_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF047857)
                    )
                ) {
                    Icon(imageVector = Icons.Default.NotificationsActive, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (product.isTracked) "Modificar seguimiento" else "Guardar seguimiento",
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val exactRetailerUrl = product.storeUrl
                        .takeIf { product.isPriceVerified }
                        ?.let(::extractDirectRetailerUrl)
                    val knownRetailer = Store.findKnownByName(
                        name = product.currentStore.name,
                        sourceUrl = product.storeUrl
                    )
                    val destination = exactRetailerUrl
                        ?: knownRetailer?.searchUrlFor(product.title)
                        ?: product.currentStore.officialUrl.takeIf { extractDirectRetailerUrl(it) != null }
                        ?: product.storeUrl
                    OutlinedButton(
                        onClick = {
                            openExternalHttpsUrl(context, destination)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        enabled = safeExternalHttpsUrl(destination) != null,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            when {
                                exactRetailerUrl != null -> "Ver en ${product.currentStore.name}"
                                knownRetailer?.supportsProductSearch == true -> "Buscar en ${knownRetailer.name}"
                                knownRetailer != null -> "Abrir web de ${knownRetailer.name}"
                                extractDirectRetailerUrl(product.currentStore.officialUrl) != null -> "Abrir web de ${product.currentStore.name}"
                                else -> "Abrir en Google Shopping"
                            },
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                }
            }
        }
    }
}
