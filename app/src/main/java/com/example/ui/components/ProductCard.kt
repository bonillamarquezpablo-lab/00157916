package com.example.ui.components

import androidx.compose.foundation.background
import com.example.security.openExternalHttpsUrl
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.data.model.Product
import com.example.data.model.Store
import com.example.ui.theme.ProductCardShape
import com.example.ui.theme.ProductImageShape

@Composable
fun ProductCard(
    product: Product,
    onClick: () -> Unit,
    onToggleTrack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showStoreComparison by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(ProductCardShape)
            .border(
                width = if (product.isAtAth) 1.5.dp else 1.dp,
                color = if (product.isAtAth) Color(0xFF10B981).copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                shape = ProductCardShape
            )
            .clickable(onClick = onClick)
            .testTag("product_card_${product.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (product.isPriceFresh) {
                lerp(MaterialTheme.colorScheme.surface, product.currentStore.brandColor, 0.05f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Top Section: Product Photo + Product Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Product Photo
                ProductImageView(
                    product = product,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(ProductImageShape)
                        .testTag("product_photo_${product.id}"),
                    showAthRibbon = product.isPriceVerified && product.isAtAth
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Metadata & Title
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Store pill
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (product.isPriceVerified) product.currentStore.brandColor.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (product.isPriceVerified) product.currentStore.name else "Sin precio verificado",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (product.isPriceVerified) product.currentStore.brandColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Bookmark / Track
                        IconButton(
                            onClick = onToggleTrack,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("track_button_${product.id}")
                        ) {
                            Icon(
                                imageVector = if (product.isTracked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = if (product.isTracked) "Siguiendo" else "Seguir precio",
                                tint = if (product.isTracked) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Title
                    Text(
                        text = product.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        letterSpacing = (-0.15).sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Category & Rating
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = product.category.displayName,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (product.isRatingVerified) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = Color(0xFFEAB308),
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "${product.rating}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Evidence-based hero badge: never call the first quote a historical minimum.
            HeroPriceBadge(
                state = product.priceBadgeState,
                modifier = Modifier.fillMaxWidth(),
                compact = true
            )

            // Existing deal analysis certificate.
            DealVerdictBadge(verdict = product.verdict, compact = false)

            Spacer(modifier = Modifier.height(10.dp))

            // Price Section
            when (product.priceDisplayStatus) {
                com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT -> Unit
                com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION -> Text(
                    text = "PRECIO GUARDADO · PENDIENTE DE REVALIDACIÓN",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
                com.example.data.model.PriceDisplayStatus.DEMONSTRATION -> Text(
                    text = "DATOS DE DEMOSTRACIÓN · SIN PRECIO REAL VERIFICADO",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        val displayedSavedPrice = when (product.priceDisplayStatus) {
                            com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT -> product.currentPrice
                            com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION ->
                                if (!product.isPriceVerified) product.savedHistoricalPrice else product.currentPrice
                            com.example.data.model.PriceDisplayStatus.DEMONSTRATION -> null
                        }
                        Text(
                            text = displayedSavedPrice?.let { "${String.format("%.2f", it)} €" } ?: "Precio no disponible",
                            fontWeight = FontWeight.Black,
                            fontSize = if (displayedSavedPrice != null) 24.sp else 15.sp,
                            letterSpacing = if (displayedSavedPrice != null) (-0.65).sp else 0.sp,
                            color = if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT && product.isAtAth) Color(0xFF34D399) else MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT && product.isOfficialPvpVerified && product.officialPvp > product.currentPrice) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${String.format("%.2f", product.officialPvp)} €",
                                fontSize = 13.sp,
                                textDecoration = TextDecoration.LineThrough,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT && product.realSavingsPercent > 0) {
                        Text(
                            text = "Ahorro real: -${product.realSavingsPercent}% (-${String.format("%.2f", product.realSavingsAmount)} €)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF059669)
                        )
                    }
                    if (product.priceDisplayStatus != com.example.data.model.PriceDisplayStatus.DEMONSTRATION && product.priceObservedAt != null) {
                        Text(
                            text = "Consultado " + SimpleDateFormat("dd/MM HH:mm", Locale("es", "ES"))
                                .format(Date(product.priceObservedAt)),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION) {
                        Text(
                            text = if (!product.isPriceVerified && product.savedHistoricalPrice != null) {
                                "Precio histórico guardado · ${product.savedHistoricalPriceObservedAt?.let { SimpleDateFormat("dd/MM/yyyy", Locale("es", "ES")).format(Date(it)) } ?: "fecha desconocida"} · no es una oferta actual"
                            } else product.priceFreshnessLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (product.priceDisplayStatus != com.example.data.model.PriceDisplayStatus.DEMONSTRATION) {
                        val savedSource = if (!product.isPriceVerified && product.savedHistoricalPrice != null) {
                            product.savedHistoricalPriceSource.ifBlank { "historial archivado" }
                        } else product.priceSource.ifBlank { "buscador online" }
                        Text(
                            text = if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION) {
                                "Última fuente guardada: $savedSource · precio antiguo, no confirmado como actual."
                            } else {
                                "Fuente: $savedSource · confirma el precio final y el stock."
                            },
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = { showStoreComparison = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Buscar ofertas en ${Store.ALL_STORES.size} tiendas",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            // ATH Historical Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (product.isAtAth) Color(0xFF10B981).copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.TrendingDown,
                            contentDescription = null,
                            tint = if (product.isAtAth) Color(0xFF059669) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (product.hasSufficientHistory) "Mínimo observado:" else "Historial:",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (product.hasSufficientHistory) "${String.format("%.2f", product.athMinPrice)} €" else "En construcción",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (product.isAtAth) Color(0xFF059669) else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Text(
                        text = if (product.hasSufficientHistory) "${product.athMinDate} (${product.athMinStore})" else if (product.isPriceVerified) "Se necesitan varias consultas durante 90 días" else "Sin historial verificable",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showStoreComparison) {
        AlertDialog(
            onDismissRequest = { showStoreComparison = false },
            title = { Text("Buscar ${product.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Puedes abrir las webs de los comercios desde aquí. Para precios observados, consulta Google Shopping desde Ajustes y confirma el precio final, envío y disponibilidad en la tienda.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(Store.ALL_STORES, key = { it.id }) { store ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        store.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        store.category,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        val searchUrl = store.searchUrlFor(product.title)
                                        if (openExternalHttpsUrl(context, searchUrl)) {
                                            showStoreComparison = false
                                        }
                                    }
                                ) {
                                    Text("Buscar")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showStoreComparison = false }) {
                    Text("Cerrar")
                }
            }
        )
    }
}
