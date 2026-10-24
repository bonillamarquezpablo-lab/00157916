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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.data.model.Product
import com.example.data.model.ProductCategory
import com.example.data.model.Store
import com.example.ui.theme.ProductCardShape

private val SPANISH_LOCALE = Locale("es", "ES")

@Composable
fun ProductCard(
    product: Product,
    onClick: () -> Unit,
    onToggleTrack: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val context = LocalContext.current
    var showStoreComparison by remember { mutableStateOf(false) }
    // Reuse derived historical state during ordinary UI recompositions. These model getters
    // filter/sort a product's timeline, so evaluating them repeatedly in every card is avoidable.
    val freshnessKey = product.isPriceFresh
    val historyDayKey = System.currentTimeMillis() / 86_400_000L
    val priceBadgeState = remember(product, freshnessKey, historyDayKey) { product.priceBadgeState }
    val hasSufficientHistory = remember(product, freshnessKey, historyDayKey) { product.hasSufficientHistory }
    val isVerifiedMinimum =
        product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT &&
            product.isAtAth
    val observedAtFormatter = remember { SimpleDateFormat("dd/MM HH:mm", SPANISH_LOCALE) }
    val savedDateFormatter = remember { SimpleDateFormat("dd/MM/yyyy", SPANISH_LOCALE) }

    Card(
        shape = ProductCardShape,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (isVerifiedMinimum) 1.5.dp else 1.dp,
                color = if (isVerifiedMinimum) {
                    Color(0xFF10B981).copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                },
                shape = ProductCardShape
            )
            .clickable(onClick = onClick)
            .testTag("product_card_${product.id}"),
        colors = CardDefaults.cardColors(
            // Keep cards visually consistent; retailer colours belong in small metadata accents.
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .then(if (compact) Modifier.fillMaxHeight() else Modifier)
                .padding(horizontal = if (compact) 9.dp else 12.dp, vertical = if (compact) 9.dp else 11.dp)
        ) {
            if (compact) {
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        ProductImageView(
                            product = product,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("product_photo_${product.id}"),
                            showAthRibbon = product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT && product.isAtAth
                        )
                        IconButton(
                            onClick = onToggleTrack,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                                .testTag("track_button_${product.id}")
                        ) {
                            Icon(
                                imageVector = if (product.isTracked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = if (product.isTracked) "Siguiendo" else "Seguir precio",
                                tint = if (product.isTracked) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Text(
                        text = when {
                            product.isPriceVerified -> product.currentStore.name
                            product.isUnverifiedLiveListing -> "Listado sin confirmar"
                            else -> "Precio guardado"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = if (product.isPriceVerified) product.currentStore.brandColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = product.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (product.isRatingVerified) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Valoración verificada",
                                tint = Color(0xFFEAB308),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${product.rating}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    HeroPriceBadge(
                        state = priceBadgeState,
                        compact = true
                    )
                    val compactPrice = when (product.priceDisplayStatus) {
                        com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT -> product.currentPrice
                        com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION ->
                            if (!product.isPriceVerified) product.savedHistoricalPrice else product.currentPrice
                        com.example.data.model.PriceDisplayStatus.UNVERIFIED_LIVE_LISTING -> product.currentPrice
                        com.example.data.model.PriceDisplayStatus.DEMONSTRATION -> null
                    }
                    Text(
                        text = compactPrice?.let { "${String.format(SPANISH_LOCALE, "%.2f", it)} €" } ?: "Precio no disponible",
                        fontWeight = FontWeight.Black,
                        fontSize = if (compactPrice != null) 19.sp else 12.sp,
                        letterSpacing = (-0.4).sp,
                        color = if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT && product.isAtAth) Color(0xFF059669) else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (
                        product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT &&
                        product.realSavingsPercent > 0
                    ) {
                        Text(
                            text = "Ahorro real: -${product.realSavingsPercent}%",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF059669),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (
                        product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT &&
                        product.isOfficialPvpVerified &&
                        product.officialPvp > product.currentPrice
                    ) {
                        Text(
                            text = "PVP: ${String.format(SPANISH_LOCALE, "%.2f", product.officialPvp)} €",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textDecoration = TextDecoration.LineThrough,
                            maxLines = 1
                        )
                    }

                    val compactIsArchivedPrice =
                        product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION &&
                            !product.isPriceVerified && product.savedHistoricalPrice != null
                    val compactObservedAt = if (compactIsArchivedPrice) {
                        product.savedHistoricalPriceObservedAt
                    } else {
                        product.priceObservedAt
                    }
                    if (
                        product.priceDisplayStatus != com.example.data.model.PriceDisplayStatus.DEMONSTRATION &&
                        compactObservedAt != null
                    ) {
                        Text(
                            text = when {
                                compactIsArchivedPrice -> "Guardado " + savedDateFormatter.format(Date(compactObservedAt))
                                product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION ->
                                    "Guardado " + observedAtFormatter.format(Date(compactObservedAt))
                                else -> "Consultado " + observedAtFormatter.format(Date(compactObservedAt))
                            },
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (product.priceDisplayStatus != com.example.data.model.PriceDisplayStatus.DEMONSTRATION) {
                        val compactSource = if (!product.isPriceVerified && product.savedHistoricalPrice != null) {
                            product.savedHistoricalPriceSource.ifBlank { "historial archivado" }
                        } else {
                            product.priceSource.ifBlank { "buscador online" }
                        }
                        Text(
                            text = when (product.priceDisplayStatus) {
                                com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION ->
                                    "Fuente guardada: $compactSource · no actual."
                                com.example.data.model.PriceDisplayStatus.UNVERIFIED_LIVE_LISTING ->
                                    "Listado sin confirmar · stock y envío pendientes."
                                else -> "Fuente: $compactSource"
                            },
                            fontSize = 9.sp,
                            lineHeight = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (
                        product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT &&
                        hasSufficientHistory
                    ) {
                        Text(
                            text = "Mínimo observado: ${String.format(SPANISH_LOCALE, "%.2f", product.athMinPrice)} €",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = { showStoreComparison = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 38.dp)
                            .testTag("choose_store_button"),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Elegir tienda", fontWeight = FontWeight.SemiBold, fontSize = 10.sp, maxLines = 1)
                    }
                }
            } else {
            // Top Section: Product Photo + Product Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Product Photo
                ProductImageView(
                    product = product,
                    modifier = Modifier
                        .size(84.dp)
                        .testTag("product_photo_${product.id}"),
                    showAthRibbon = product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT && product.isAtAth
                )

                Spacer(modifier = Modifier.width(10.dp))

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
                                .widthIn(max = 176.dp)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = when {
                                    product.isPriceVerified -> product.currentStore.name
                                    product.isUnverifiedLiveListing -> "Listado sin confirmar"
                                    else -> "Sin precio verificado"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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

                    // "Todos" is a filter option, not a meaningful product category.
                    if (product.category != ProductCategory.ALL || product.isRatingVerified) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (product.category != ProductCategory.ALL) {
                                Text(
                                    text = product.category.displayName,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (product.isRatingVerified) {
                                if (product.category != ProductCategory.ALL) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Valoración verificada",
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
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Evidence-based hero badge: never call the first quote a historical minimum.
            HeroPriceBadge(
                state = priceBadgeState,
                // A compact status chip is easier to scan than a full-width ribbon.
                modifier = Modifier,
                compact = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // The detail sheet contains the full verdict explanation. Avoid repeating that
            // paragraph on every market card; the evidence chip and concise source line suffice.
            // Price Section
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
                            com.example.data.model.PriceDisplayStatus.UNVERIFIED_LIVE_LISTING -> product.currentPrice
                            com.example.data.model.PriceDisplayStatus.DEMONSTRATION -> null
                        }
                        Text(
                            text = displayedSavedPrice?.let { "${String.format(SPANISH_LOCALE, "%.2f", it)} €" } ?: "Precio no disponible",
                            fontWeight = FontWeight.Black,
                            fontSize = if (displayedSavedPrice != null) 24.sp else 15.sp,
                            letterSpacing = if (displayedSavedPrice != null) (-0.65).sp else 0.sp,
                            color = if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT && product.isAtAth) Color(0xFF34D399) else MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT && product.isOfficialPvpVerified && product.officialPvp > product.currentPrice) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${String.format(SPANISH_LOCALE, "%.2f", product.officialPvp)} €",
                                fontSize = 13.sp,
                                textDecoration = TextDecoration.LineThrough,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT && product.realSavingsPercent > 0) {
                        Text(
                            text = "Ahorro real: -${product.realSavingsPercent}% (-${String.format(SPANISH_LOCALE, "%.2f", product.realSavingsAmount)} €)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF059669)
                        )
                    }
                    val isDisplayingSavedArchivePrice =
                        product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION &&
                            !product.isPriceVerified && product.savedHistoricalPrice != null
                    val displayedObservedAt = if (isDisplayingSavedArchivePrice) {
                        product.savedHistoricalPriceObservedAt
                    } else {
                        product.priceObservedAt
                    }
                    if (product.priceDisplayStatus != com.example.data.model.PriceDisplayStatus.DEMONSTRATION &&
                        displayedObservedAt != null
                    ) {
                        Text(
                            text = if (isDisplayingSavedArchivePrice) {
                                "Precio guardado el " + savedDateFormatter.format(Date(displayedObservedAt))
                            } else if (product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION) {
                                "Guardado " + observedAtFormatter.format(Date(displayedObservedAt))
                            } else {
                                "Consultado " + observedAtFormatter.format(Date(displayedObservedAt))
                            },
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (product.priceDisplayStatus != com.example.data.model.PriceDisplayStatus.DEMONSTRATION) {
                        val savedSource = if (!product.isPriceVerified && product.savedHistoricalPrice != null) {
                            product.savedHistoricalPriceSource.ifBlank { "historial archivado" }
                        } else product.priceSource.ifBlank { "buscador online" }
                        Text(
                            text = when (product.priceDisplayStatus) {
                                com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION ->
                                    "Fuente guardada: $savedSource · precio no actual."
                                com.example.data.model.PriceDisplayStatus.UNVERIFIED_LIVE_LISTING ->
                                    "Precio listado sin confirmar · vendedor, stock y envío pendientes."
                                else -> "Fuente: $savedSource · confirma precio final y stock."
                            },
                            fontSize = 10.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Button(
                onClick = { showStoreComparison = true },
                modifier = Modifier.fillMaxWidth().testTag("choose_store_button"),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Elegir tienda",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            // Show a second history row only when it adds a verified, useful number.
            // First-observation, stale, and unverified states are already communicated by the
            // badge/date/source above and would only repeat the same warning here.
            if (
                product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT &&
                hasSufficientHistory
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.TrendingDown,
                        contentDescription = null,
                        tint = if (isVerifiedMinimum) Color(0xFF059669) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Mínimo observado: ${String.format(SPANISH_LOCALE, "%.2f", product.athMinPrice)} €",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                        "«Buscar» abre el buscador del comercio cuando está disponible; «Web» abre su página oficial. Comprueba el precio final, el envío y el stock.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Store.ALL_STORES.forEach { store ->
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
                                    Text(if (store.supportsProductSearch) "Buscar" else "Web")
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
