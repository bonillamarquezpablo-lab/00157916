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
import androidx.compose.material.icons.filled.FlashOn
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
import com.example.ui.components.DealVerdictBadge
import com.example.ui.components.PriceHistoryChart
import com.example.ui.components.ProductImageView
import com.example.ui.components.StoreComparisonView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailSheet(
    product: Product,
    onDismiss: () -> Unit,
    onOpenSetAlert: () -> Unit,
    onSimulatePriceDrop: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scrollState = rememberScrollState()

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
                            val shareText = if (product.hasSufficientHistoryForDealVerdict) {
                                "📊 Informe de precio para ${product.title}:\n" +
                                    "• Precio observado: ${String.format("%.2f", product.currentPrice)} € en ${product.currentStore.name}\n" +
                                    "• Mínimo observado en los datos disponibles: ${String.format("%.2f", product.athMinPrice)} € (${product.athMinDate} en ${product.athMinStore})\n" +
                                    "• Diagnóstico: ${product.verdict.badgeText} - ${product.verdict.explanation}" +
                                    if (product.isOfficialPvpVerified) "\n• PVP fuente verificada: ${String.format("%.2f", product.officialPvp)} €" else ""
                            } else if (product.isPriceVerified) {
                                "📊 Precio observado para ${product.title}:\n" +
                                    "• Precio actual: ${String.format("%.2f", product.currentPrice)} € en ${product.currentStore.name}\n" +
                                    "• Aún no hay suficientes observaciones verificadas para afirmar un mínimo histórico o descuento."
                            } else {
                                "Ficha de demostración de Mínimo: ${product.title}.\nNo contiene precios verificados ni un historial real. No debe usarse como referencia de compra."
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

            // Verdict Certificate Badge
            DealVerdictBadge(verdict = product.verdict, compact = false)

            if (!product.isPriceVerified) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Precio e historial no verificados",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Los valores numéricos de esta ficha son datos de demostración. Se ocultan el precio, las ofertas y el gráfico hasta disponer de observaciones reales con fuente y fecha.",
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
                        }

                        // Minimum observed. Do not claim a historic record without 90-day coverage.
                        Column(horizontalAlignment = Alignment.End) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (product.hasSufficientHistoryForDealVerdict) "MÍNIMO OBSERVADO" else "HISTORIAL",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = if (product.hasSufficientHistoryForDealVerdict) "${String.format("%.2f", product.athMinPrice)} €" else "Insuficiente",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (product.hasSufficientHistoryForDealVerdict) "${product.athMinDate} en ${product.athMinStore}" else "Se requieren 90 días de datos",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Media de 90 días",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (product.hasSufficientHistoryForDealVerdict) "${String.format("%.2f", product.avg90dPrice)} €" else "Insuficiente",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        if (product.isOfficialPvpVerified) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "PVP oficial verificado",
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

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Ahorro frente al PVP",
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

            // Do not draw a chart from one snapshot or make an empty offer list look like a comparator.
            if (product.priceHistory.size >= 2) {
                PriceHistoryChart(priceHistory = product.priceHistory)
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("price_history_insufficient"),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Historial de precios aún insuficiente", fontWeight = FontWeight.Bold)
                        Text(
                            "El precio actual está verificado, pero hacen falta varias observaciones fechadas para dibujar una evolución. No se crearán puntos artificiales.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (product.offers.isNotEmpty()) {
                StoreComparisonView(
                    offers = product.offers,
                    athPrice = product.athMinPrice
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("store_comparison_unavailable"),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Comparación entre tiendas no disponible", fontWeight = FontWeight.Bold)
                        Text(
                            "Solo mostraremos otras ofertas cuando dispongamos de precios comparables, con fuente y fecha de consulta. No se mostrarán precios de ejemplo.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // EAN/GTIN metadata is separate from the verification of a current price.
            if (product.ean.isNotBlank()) {
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
                        text = if (product.isEanVerified) {
                            "EAN/GTIN verificado: ${product.ean}"
                        } else {
                            "EAN/GTIN de referencia (no verificado independientemente): ${product.ean}"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

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
                            if (product.isPriceVerified) "Ver en ${product.currentStore.name}" else "Buscar en Google",
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    OutlinedButton(
                        onClick = onSimulatePriceDrop,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlashOn,
                            contentDescription = null,
                            tint = Color(0xFFD97706),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Simular caída (demo)", fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
