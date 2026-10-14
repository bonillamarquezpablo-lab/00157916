package com.example.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.remote.OnlineProductGroup
import com.example.data.remote.SerpApiShoppingResult
import com.example.data.remote.groupOnlineShoppingResults
import com.example.ui.viewmodel.OnlineSearchProvider
import com.example.ui.viewmodel.TrackerUiState
import java.text.NumberFormat
import java.util.Locale

@Composable
fun OnlineSearchScreen(
    state: TrackerUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAmazonSearch: () -> Unit,
    onAmazonOnlyChange: (Boolean) -> Unit,
    onShowSecondHandChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProduct: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val conditionFilteredResults = state.onlineSearchResults.filter {
        state.onlineSearchShowSecondHand || !it.isSecondHand
    }
    val allGroups = groupOnlineShoppingResults(conditionFilteredResults)
    val amazonGroups = groupOnlineShoppingResults(
        conditionFilteredResults.filter { it.isAmazonOffer }
    )
    val visibleGroups = if (state.onlineSearchAmazonOnly) amazonGroups else allGroups

    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(25.dp))
                        Spacer(Modifier.width(9.dp))
                        Text("Encuentra productos y ofertas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    }
                    Text("Busca cualquier producto. Las ofertas válidas se guardan en Mercado; toca una tarjeta para comparar tiendas y consultar el historial real.")
                    OutlinedTextField(
                        value = state.onlineSearchQuery,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("¿Qué quieres comprar?") },
                        placeholder = { Text("Ej. Anker Prime 20.000 mAh 200 W") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() })
                    )
                    Button(
                        onClick = onSearch,
                        enabled = !state.isOnlineSearching && !state.isRefreshingPrices && state.onlineSearchQuery.trim().length >= 2,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isOnlineSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.onlineSearchProvider == OnlineSearchProvider.AMAZON) "Consultando Amazon.es…" else "Buscando ofertas…")
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Buscar y comparar precios")
                        }
                    }
                    OutlinedButton(
                        onClick = onAmazonSearch,
                        enabled = !state.isOnlineSearching && !state.isRefreshingPrices && state.onlineSearchQuery.trim().length >= 2,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isOnlineSearching && state.onlineSearchProvider == OnlineSearchProvider.AMAZON) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Consultando Amazon.es…")
                        } else {
                            Icon(Icons.Default.ShoppingBag, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Buscar precios de Amazon.es en Mínimo")
                        }
                    }
                    Text(
                        "Consulta el catálogo público de Amazon.es y muestra los precios aquí. No necesita abrir la app de Amazon, pero no puede leer tus cupones o precios personalizados de la cuenta Prime.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { openAmazonSearch(context, state.onlineSearchQuery) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isOnlineSearching && !state.isRefreshingPrices
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Abrir Amazon con mi sesión Prime")
                    }
                    OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("Configurar la fuente de precios")
                    }
                }
            }
        }
        if (state.isOnlineSearching) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        state.onlineSearchStatus?.let { status ->
            item {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.onlineSearchResults.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (state.onlineSearchProvider == OnlineSearchProvider.AMAZON) "Resultados de Amazon.es" else "Resultados de Google Shopping",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (state.onlineSearchProvider == OnlineSearchProvider.GOOGLE_SHOPPING) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !state.onlineSearchAmazonOnly,
                                onClick = { onAmazonOnlyChange(false) },
                                label = { Text("Todos (${allGroups.size})") }
                            )
                            FilterChip(
                                selected = state.onlineSearchAmazonOnly,
                                onClick = { onAmazonOnlyChange(true) },
                                label = { Text("Solo Amazon (${amazonGroups.size})") }
                            )
                            FilterChip(
                                selected = state.onlineSearchShowSecondHand,
                                onClick = { onShowSecondHandChange(!state.onlineSearchShowSecondHand) },
                                label = { Text("Mostrar segunda mano") }
                            )
                        }
                    }
                }
            }
            if (visibleGroups.isEmpty()) {
                item {
                    Text(
                        if (state.onlineSearchProvider == OnlineSearchProvider.AMAZON)
                            "Amazon.es no ha devuelto productos con precio en euros para esta búsqueda."
                        else
                            "No hay ofertas identificadas como Amazon en esta consulta."
                    )
                }
            }
            items(visibleGroups, key = { it.identityKey }) { group ->
                ShoppingResultCard(
                    group = group,
                    onOpenProduct = { onOpenProduct(group.identityKey) },
                    onOpenBestOffer = {
                        val best = group.cheapestOffer
                        openStoreUrl(context, best.sourceUrl, preferAmazonApp = best.isAmazonOffer)
                    }
                )
            }
            item {
                Text(
                    "Precios observados en el momento de la consulta. El precio final, envío, vendedor y disponibilidad pueden variar al abrir la tienda.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ShoppingResultCard(
    group: OnlineProductGroup,
    onOpenProduct: () -> Unit,
    onOpenBestOffer: () -> Unit
) {
    val best = group.cheapestOffer
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenProduct),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                if (best.thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = best.thumbnailUrl,
                        contentDescription = "Imagen de ${group.title}",
                        modifier = Modifier.size(94.dp).clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.size(94.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Storefront, contentDescription = null, modifier = Modifier.size(30.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(group.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Desde " + NumberFormat.getCurrencyInstance(Locale("es", "ES")).format(group.minPrice),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "${group.storeCount} tiendas · precio más bajo en ${best.storeName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "${group.offers.size} ofertas · toca para ver tiendas e historial",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (group.offers.any { it.isSuspiciousPrice }) {
                        Text(
                            "Precio sospechoso · no cuenta como mejor precio",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "Enlace: ${best.linkType}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            if (best.primeEligible) {
                Text("Entrega Prime disponible en la oferta más barata", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(onClick = onOpenBestOffer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (best.isGoogleShoppingProductLink) "Abrir oferta más barata en Google Shopping"
                    else "Abrir oferta más barata · ${best.storeName}"
                )
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

private const val AMAZON_SHOPPING_PACKAGE = "com.amazon.mShop.android.shopping"

/**
 * Prefer the official Amazon Shopping app so the user's existing sign-in can be used.
 * Fall back to the HTTPS page when the app is not installed or does not handle the URL.
 */
private fun openAmazonSearch(context: Context, query: String) {
    val trimmedQuery = query.trim()
    val url = if (trimmedQuery.length >= 2) {
        "https://www.amazon.es/s?k=${Uri.encode(trimmedQuery)}"
    } else {
        "https://www.amazon.es/"
    }
    openStoreUrl(context, url, preferAmazonApp = true)
}

private fun openStoreUrl(context: Context, url: String, preferAmazonApp: Boolean) {
    val uri = Uri.parse(url)
    if (preferAmazonApp) {
        val amazonIntent = Intent(Intent.ACTION_VIEW, uri).setPackage(AMAZON_SHOPPING_PACKAGE)
        try {
            context.startActivity(amazonIntent)
            return
        } catch (_: ActivityNotFoundException) {
            // Amazon app isn't installed; continue with the website below.
        }
    }
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
}

