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
import com.example.data.remote.isAccessorySearchQuery
import com.example.data.remote.isWheelFitmentQuery
import com.example.ui.viewmodel.OnlineSearchProvider
import com.example.ui.viewmodel.TrackerUiState
import java.text.NumberFormat
import java.util.Locale

internal data class OnlineSearchDisplayGroup(
    val group: OnlineProductGroup,
    val stableKey: String
)

/**
 * Apply view-only filters to the already loaded snapshot. Split groups by condition so used
 * offers can be appended as separate cards instead of replacing a new offer for the same model.
 */
internal fun buildOnlineSearchDisplayGroups(
    results: List<SerpApiShoppingResult>,
    query: String,
    showSecondHand: Boolean,
    amazonOnly: Boolean
): List<OnlineSearchDisplayGroup> {
    val filtered = results.filter { result ->
        (showSecondHand || !result.isSecondHand) &&
            (!amazonOnly || result.isAmazonOffer)
    }
    val treatAccessoriesSeparately = !isAccessorySearchQuery(query)
    val accessories = if (treatAccessoriesSeparately) {
        filtered.filter { it.isAccessoryOffer }
    } else {
        emptyList()
    }
    val mainProductOffers = if (treatAccessoriesSeparately) {
        filtered.filterNot { it.isAccessoryOffer }
    } else {
        filtered
    }

    val mainDisplayGroups = mutableListOf<OnlineSearchDisplayGroup>()
    val accessoryDisplayGroups = mutableListOf<OnlineSearchDisplayGroup>()
    fun appendGroups(
        destination: MutableList<OnlineSearchDisplayGroup>,
        segment: String,
        offers: List<SerpApiShoppingResult>
    ) {
        groupOnlineShoppingResults(offers).forEach { group ->
            destination += OnlineSearchDisplayGroup(
                group = group,
                stableKey = "${segment}|${group.identityKey}"
            )
        }
    }

    val newOffers = mainProductOffers.filterNot { it.isSecondHand }
    val secondHandOffers = mainProductOffers.filter { it.isSecondHand }

    // Keep new, used, and fitment-unconfirmed offers in separate cards, but rank all main-product
    // cards together by comparable price. The second-hand toggle must not force used offers to the
    // bottom of the list regardless of price.
    appendGroups(mainDisplayGroups, "new", newOffers.filterNot { it.isFitmentUnverified })
    appendGroups(mainDisplayGroups, "fitment-unconfirmed", newOffers.filter { it.isFitmentUnverified })
    appendGroups(mainDisplayGroups, "second-hand", secondHandOffers.filterNot { it.isFitmentUnverified })
    appendGroups(mainDisplayGroups, "second-hand-fitment-unconfirmed", secondHandOffers.filter { it.isFitmentUnverified })

    // Accessories remain visible but never take priority over the searched-for main product.
    appendGroups(accessoryDisplayGroups, "accessory", accessories)
    val priceOrder = compareBy<OnlineSearchDisplayGroup> { it.group.minPrice }
        .thenBy { it.group.title.lowercase(Locale.ROOT) }
        .thenBy { it.stableKey }
    val sortedMain = mainDisplayGroups.sortedWith(priceOrder)
    val sortedAccessories = accessoryDisplayGroups.sortedWith(priceOrder)
    val isWheelFitmentSearch = isWheelFitmentQuery(query)
    // For wheel-fitment searches, used wheels and new wheels are comparable offers: show them
    // together in one price order when the opt-in filter is enabled. Keep accessory prioritization
    // for other product searches so unrelated low-cost accessories do not outrank the main product.
    return if (isWheelFitmentSearch) {
        (sortedMain + sortedAccessories).sortedWith(priceOrder)
    } else {
        sortedMain + sortedAccessories
    }
}

@Composable
fun OnlineSearchScreen(
    state: TrackerUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAmazonSearch: () -> Unit,
    onForceRefresh: () -> Unit,
    onAmazonOnlyChange: (Boolean) -> Unit,
    onToggleDiscarded: () -> Unit,
    onShowSecondHandChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProduct: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val allGroups = buildOnlineSearchDisplayGroups(
        results = state.onlineSearchResults,
        query = state.onlineSearchQuery,
        showSecondHand = state.onlineSearchShowSecondHand,
        amazonOnly = false
    )
    val amazonGroups = buildOnlineSearchDisplayGroups(
        results = state.onlineSearchResults,
        query = state.onlineSearchQuery,
        showSecondHand = state.onlineSearchShowSecondHand,
        amazonOnly = true
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
                    if (state.onlineSearchResults.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onForceRefresh,
                            enabled = !state.isOnlineSearching && !state.isRefreshingPrices &&
                                state.onlineSearchQuery.trim().length >= 2,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Actualizar resultados ahora (puede consumir cuota)")
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
        if (state.onlineSearchDiscardedOffers.isNotEmpty()) {
            item {
                OutlinedButton(
                    onClick = onToggleDiscarded,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (state.showOnlineSearchDiscarded) "Ocultar descartados"
                        else "Ver descartados (${state.onlineSearchDiscardedOffers.size})"
                    )
                }
            }
            if (state.showOnlineSearchDiscarded) {
                items(
                    count = state.onlineSearchDiscardedOffers.size,
                    key = { index -> "discarded-${state.onlineSearchDiscardedOffers[index].provider}-${index}" }
                ) { index ->
                    val rejected = state.onlineSearchDiscardedOffers[index]
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(rejected.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                rejected.priceLabel.ifBlank { "Precio no disponible" },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                rejected.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                rejected.provider,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
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
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            items(visibleGroups, key = { it.stableKey }) { displayGroup ->
                val group = displayGroup.group
                ShoppingResultCard(
                    group = group,
                    onOpenProduct = if (group.offers.all { it.isFitmentUnverified }) {
                        null
                    } else {
                        { onOpenProduct(group.identityKey) }
                    },
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
    onOpenProduct: (() -> Unit)?,
    onOpenBestOffer: () -> Unit
) {
    val best = group.cheapestOffer
    val fitmentUnverified = group.offers.all { it.isFitmentUnverified }
    val categoryUnverified = group.offers.all { it.isCategoryUnverified }
    val wheelSetCount = group.wheelSetCount
    Card(
        modifier = Modifier.fillMaxWidth().then(
            if (onOpenProduct == null) Modifier else Modifier.clickable(onClick = onOpenProduct)
        ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (group.offers.all { it.isSecondHand } ||
                group.offers.all { it.isAccessoryOffer } || fitmentUnverified ||
                categoryUnverified || wheelSetCount != null
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (group.offers.all { it.isSecondHand }) {
                        ResultKindBadge("Segunda mano", accessory = false)
                    }
                    if (group.offers.all { it.isAccessoryOffer }) {
                        ResultKindBadge("Accesorio", accessory = true)
                    }
                    if (wheelSetCount != null) {
                        ResultKindBadge("Juego de $wheelSetCount", accessory = false)
                    }
                    if (fitmentUnverified) {
                        ResultKindBadge("Medidas por confirmar", accessory = false)
                    }
                    if (categoryUnverified) {
                        ResultKindBadge("Por confirmar", accessory = false)
                    }
                }
            }
            if (fitmentUnverified) {
                Text(
                    "El título no confirma todas las medidas solicitadas. Abre la oferta y verifica diámetro y anclaje. No se añade a Mercado ni al historial hasta confirmarlos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
                        if (wheelSetCount != null) {
                            "Desde " + NumberFormat.getCurrencyInstance(Locale("es", "ES")).format(group.minPrice) + " / llanta"
                        } else {
                            "Desde " + NumberFormat.getCurrencyInstance(Locale("es", "ES")).format(group.minPrice)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        if (wheelSetCount != null) {
                            "${group.storeCount} tiendas · precio por llanta más bajo en ${best.storeName}"
                        } else {
                            "${group.storeCount} tiendas · precio más bajo en ${best.storeName}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (wheelSetCount != null) {
                        Text(
                            "Precio total del juego de $wheelSetCount: " +
                                NumberFormat.getCurrencyInstance(Locale("es", "ES")).format(best.price),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "${group.offers.size} ofertas · toca para ver tiendas e historial",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (group.offers.any { it.isSuspiciousPrice }) {
                        Text(
                            "Hay una oferta con precio sospechoso; se excluye del mejor precio",
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

@Composable
private fun ResultKindBadge(label: String, accessory: Boolean) {
    val background = if (accessory) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val foreground = if (accessory) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = foreground
        )
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

