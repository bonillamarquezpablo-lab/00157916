package com.example.ui.components

import androidx.compose.foundation.background
import com.example.security.safeExternalHttpsUrl
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.StoreOffer
import com.example.data.model.Store
import com.example.data.remote.extractDirectRetailerUrl
import java.text.NumberFormat
import java.util.Locale

@Composable
fun StoreComparisonView(
    offers: List<StoreOffer>,
    athPrice: Double,
    hasSufficientHistory: Boolean = false,
    productTitle: String = "",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val priceFormatter = remember { NumberFormat.getCurrencyInstance(Locale("es", "ES")) }
    val minOfferPrice = offers.minOfOrNull { it.price } ?: athPrice

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("store_comparison_view")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Comparador de Tiendas en España",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Ofertas encontradas en la última consulta",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF10B981).copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "${offers.size} ofertas",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF059669)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        offers.sortedBy { it.price }.forEachIndexed { index, offer ->
            val isBestCurrent = offer.price == minOfferPrice
            val isAtHistoricalMin = hasSufficientHistory && offer.price <= athPrice + 0.05
            val directDestination = offer.url.takeIf { it.isNotBlank() }?.let(::extractDirectRetailerUrl)
            val knownStore = Store.findKnownByName(offer.store.name, offer.url)
            val storeSearchDestination = knownStore?.takeIf { productTitle.isNotBlank() }
                ?.searchUrlFor(productTitle)
            val actionDestination = directDestination
                ?: storeSearchDestination
                ?: offer.url.takeIf { it.isNotBlank() }
                ?: knownStore?.officialUrl

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        width = if (isBestCurrent) 1.5.dp else 1.dp,
                        color = if (isBestCurrent) Color(0xFF10B981) else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable(enabled = safeExternalHttpsUrl(actionDestination) != null) {
                        openExternalHttpsUrl(context, actionDestination)
                    },
                color = if (isBestCurrent) Color(0xFF10B981).copy(alpha = 0.06f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Store color dot
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(offer.store.brandColor)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = offer.store.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                softWrap = true
                            )
                            if (isBestCurrent) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0xFF059669),
                                    contentColor = Color.White
                                ) {
                                    Text(
                                        text = "MENOR PRECIO",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                            Text(
                                text = "${offer.shippingInfo} • ${if (offer.inStock) "Disponibilidad indicada" else "Stock por confirmar"}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                            Text(
                                text = when {
                                    directDestination != null -> "Enlace directo a ${knownStore?.name ?: offer.store.name}"
                                    storeSearchDestination != null -> "Precio de ${offer.linkType} · buscar en ${knownStore?.name ?: offer.store.name}"
                                    else -> "Enlace: ${offer.linkType}"
                                },
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Column(
                            modifier = Modifier.widthIn(min = 112.dp, max = 132.dp),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = priceFormatter.format(offer.price),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp,
                                color = if (isBestCurrent) Color(0xFF059669) else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false
                            )
                            if (isAtHistoricalMin) {
                                Text(
                                    text = "Mínimo observado",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF059669),
                                    maxLines = 2
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Ir a ${offer.store.name}",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
