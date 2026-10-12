package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.example.data.model.Product
import com.example.data.model.Store

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
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = if (product.isAtAth) 1.5.dp else 1.dp,
                color = if (product.isAtAth) Color(0xFF10B981).copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .testTag("product_card_${product.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        .clip(RoundedCornerShape(12.dp))
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
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (product.isPriceVerified) product.currentStore.brandColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (product.isPriceVerified) product.currentStore.name else "Ficha de demostración",
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
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
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
                        if (product.isPriceVerified) {
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

            // Verdict Badge
            DealVerdictBadge(verdict = product.verdict, compact = false)

            Spacer(modifier = Modifier.height(10.dp))

            // Price Section
            if (!product.isPriceVerified) {
                Text(
                    text = "SIN PRECIO ACTUAL · TIENDA NO CONSULTADA",
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
                        Text(
                            text = if (product.isPriceVerified) "${String.format("%.2f", product.currentPrice)} €" else "Precio no disponible",
                            fontWeight = FontWeight.Black,
                            fontSize = if (product.isPriceVerified) 22.sp else 15.sp,
                            color = if (product.isAtAth) Color(0xFF059669) else MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (product.isPriceVerified && product.officialPvp > product.currentPrice) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${String.format("%.2f", product.officialPvp)} €",
                                fontSize = 13.sp,
                                textDecoration = TextDecoration.LineThrough,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (product.isPriceVerified && product.realSavingsPercent > 0) {
                        Text(
                            text = "Ahorro real: -${product.realSavingsPercent}% (-${String.format("%.2f", product.realSavingsAmount)} €)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF059669)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = { showStoreComparison = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
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
                    .clip(RoundedCornerShape(8.dp))
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
                            text = if (product.isPriceVerified) "Mínimo histórico:" else "Historial:",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (product.isPriceVerified) "${String.format("%.2f", product.athMinPrice)} €" else "No verificado",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (product.isAtAth) Color(0xFF059669) else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Text(
                        text = if (product.isPriceVerified) "${product.athMinDate} (${product.athMinStore})" else "Sin historial verificable",
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
                        "Mínimo aún no obtiene los precios automáticamente. Abre una tienda para consultar el precio publicado actualmente.",
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
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl))
                                        )
                                        showStoreComparison = false
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
