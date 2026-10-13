package com.example.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import com.example.data.remote.SerpApiShoppingResult
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
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val visibleResults = state.onlineSearchResults.filter {
        !state.onlineSearchAmazonOnly || it.isAmazonOffer
    }

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
                    Text("Busca cualquier producto aunque no exista en el catálogo. Consulta Google Shopping o Amazon.es y muestra los precios aquí mismo.")
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
                                label = { Text("Todos (${state.onlineSearchResults.size})") }
                            )
                            FilterChip(
                                selected = state.onlineSearchAmazonOnly,
                                onClick = { onAmazonOnlyChange(true) },
                                label = { Text("Solo Amazon (${state.onlineSearchResults.count { it.isAmazonOffer }})") }
                            )
                        }
                    }
                }
            }
            if (visibleResults.isEmpty()) {
                item {
                    Text(
                        if (state.onlineSearchProvider == OnlineSearchProvider.AMAZON)
                            "Amazon.es no ha devuelto productos con precio en euros para esta búsqueda."
                        else
                            "No hay ofertas identificadas como Amazon en esta consulta."
                    )
                }
            }
            items(visibleResults, key = { "${it.sourceUrl}|${it.price}" }) { result ->
                ShoppingResultCard(result = result) {
                    openStoreUrl(context, result.sourceUrl, preferAmazonApp = result.isAmazonOffer)
                }
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
private fun ShoppingResultCard(result: SerpApiShoppingResult, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                if (result.thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = result.thumbnailUrl,
                        contentDescription = "Imagen de ${result.title}",
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
                    Text(result.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(
                        NumberFormat.getCurrencyInstance(Locale("es", "ES")).format(result.price),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        if (result.isGoogleShoppingProductLink) {
                            "Vendedor indicado: ${result.storeName} · enlace de Google Shopping"
                        } else {
                            result.storeName
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    result.rating?.let { rating ->
                        Text(
                            "★ ${String.format(Locale("es", "ES"), "%.1f", rating)}" + (result.reviewCount?.let { " · $it opiniones" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (result.primeEligible) {
                Text("Entrega Prime disponible", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            if (result.delivery.isNotBlank()) {
                Text(result.delivery, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (result.isGoogleShoppingProductLink) "Abrir resultado en Google Shopping"
                    else "Ver oferta en ${result.storeName}"
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

