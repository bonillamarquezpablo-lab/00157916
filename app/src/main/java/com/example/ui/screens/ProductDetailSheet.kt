package com.example.ui.screens

import android.content.Intent
import android.net.Uri
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
    serpApiActionStatus: String? = null,
    isComparingStores: Boolean = false
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val archivedHistoryPoints = product.priceHistory.filter {
        it.eventLabel.equals("Internet Archive", ignoreCase = true)
    }
    val oldestArchivedDate = archivedHistoryPoints.minByOrNull { it.timestamp }?.let {
        SimpleDateFormat("dd/MM/yyyy", Locale("es", "ES")).format(Date(it.timestamp))
    }
    val googleInsightPoints = product.priceHistory.filter {
        it.eventLabel?.contains("price_insights", ignoreCase = true) == true
    }
    val oldestGoogleInsightDate = googleInsightPoints.minByOrNull { it.timestamp }?.let {
        SimpleDateFormat("dd/MM/yyyy", Locale("es", "ES")).format(Date(it.timestamp))
    }
    val historicalSourceSummary = buildString {
        if (googleInsightPoints.isNotEmpty()) {
            append("Google Shopping: ${googleInsightPoints.size} puntos históricos")
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
            append("Todavía no hay un historial anterior verificado. Pulsa «Buscar histórico gratis» para revisar páginas públicas archivadas sin gastar cuota de SerpApi. Si el comercio no está archivado o no publicó un precio EUR verificable, no se puede reconstruir ese precio. «Comparar tiendas» es una consulta adicional de SerpApi y tampoco garantiza datos históricos.")
        } else {
            append(" Puede haber huecos; los precios históricos no garantizan stock, envío ni precio final.")
        }
    }
    val latestPricePoint = product.priceHistory.maxByOrNull { it.timestamp }
    val currentSourceLabel = latestPricePoint
        ?.eventLabel
        ?.takeIf { it.isNotBlank() }
        ?: "Fuente no disponible"
    val currentLinkType = latestPricePoint?.linkType ?: "directo"

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
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = product.category.displayName,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row {
                    IconButton(
                        onClick = {
                            val shareText = when {
                                product.hasSufficientHistory ->
                                    "Precio observado para ${product.title}:\\n" +
                                        "• Precio actual: ${String.format("%.2f", product.currentPrice)} € en ${product.currentStore.name}\\n" +
                                        "• Mínimo observado en los datos disponibles: ${String.format("%.2f", product.athMinPrice)} € (${product.athMinDate} en ${product.athMinStore})\\n" +
                                        "• Diagnóstico: ${product.verdict.badgeText} - ${product.verdict.explanation}"
                                product.isPriceVerified ->
                                    "Precio observado para ${product.title}: ${String.format("%.2f", product.currentPrice)} € en ${product.currentStore.name}. " +
                                        "Aún no hay observaciones durante 90 días para afirmar un mínimo histórico o un descuento."
                                else ->
                                    "Ficha sin precio verificado de Mínimo: ${product.title}.\\nNo contiene una oferta ni un historial real confirmados."
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
                    .height(210.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            ) {
                ProductImageView(
                    product = product,
                    modifier = Modifier.fillMaxWidth().height(210.dp),
                    contentScale = ContentScale.Fit,
                    showAthRibbon = product.isPriceVerified && product.isAtAth
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

            if (!product.isPriceVerified) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Ficha sin precio verificado",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Mínimo todavía no ha confirmado el precio de este producto con una fuente y una fecha verificables. No uses los valores de referencia como una oferta real; el historial aparecerá cuando se guarden observaciones fiables.",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (product.isPriceVerified) {
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
                                text = "Precio Actual",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${String.format("%.2f", product.currentPrice)} €",
                                fontWeight = FontWeight.Black,
                                fontSize = 20.sp,
                                color = if (product.isAtAth) Color(0xFF059669) else MaterialTheme.colorScheme.onSurface
                            )
                            product.priceObservedAt?.let { observedAt ->
                                Text(
                                    text = "Consultado " + SimpleDateFormat("dd/MM HH:mm", Locale("es", "ES"))
                                        .format(Date(observedAt)),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (product.isPriceVerified && !product.isPriceFresh) {
                                Text(
                                    text = product.priceFreshnessLabel,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            Text(
                                text = "$currentSourceLabel · confirma precio final y stock en la tienda.",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Enlace: $currentLinkType",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Do not infer a historical low from a single query.
                        Column(horizontalAlignment = Alignment.End) {
                            if (product.hasSufficientHistory) {
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
                                    text = "${String.format("%.2f", product.athMinPrice)} €",
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
                                        product.priceHistory.size == 1 -> "1 observación real · la gráfica ya está disponible"
                                        product.priceHistory.size > 1 -> "${product.priceHistory.size} fechas registradas · evolución en curso"
                                        else -> "Se necesitan observaciones reales para mostrar la evolución"
                                    },
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (product.hasSufficientHistory || product.officialPvp > 0.0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (product.hasSufficientHistory) {
                                Column {
                                    Text(
                                        text = "Media de 90 días",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${String.format("%.2f", product.avg90dPrice)} €",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                            if (product.officialPvp > 0.0) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "PVP de referencia",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${String.format("%.2f", product.officialPvp)} €",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        textDecoration = TextDecoration.LineThrough
                                    )
                                }
                            }
                            if (product.officialPvp > product.currentPrice) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "Diferencia frente al PVP",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${String.format("%.2f", product.realSavingsAmount)} € (${product.realSavingsPercent}%)",
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
                priceHistory = product.priceHistory,
                avg90dPrice = product.avg90dPrice
            )

            // Only show offers that came back from the latest provider query.
            if (product.offers.isNotEmpty()) {
                StoreComparisonView(
                    offers = product.offers,
                    athPrice = product.athMinPrice,
                    hasSufficientHistory = product.hasSufficientHistory
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
                    onClick = onSearchFreeHistory,
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
                    OutlinedButton(
                        onClick = {
                            val destination = if (product.isPriceVerified) product.storeUrl else product.currentStore.searchUrlFor(product.title)
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(destination))
                            context.startActivity(intent)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        enabled = true,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            if (product.isPriceVerified) "Ver en ${product.currentStore.name}" else "Buscar en web oficial",
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
