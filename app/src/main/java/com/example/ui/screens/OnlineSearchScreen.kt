package com.example.ui.screens

import android.content.ActivityNotFoundException
import com.example.security.safeExternalHttpsUrl
import com.example.security.openExternalHttpsUrl
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.example.data.model.Store
import com.example.data.remote.OnlineProductGroup
import com.example.data.remote.SerpApiShoppingResult
import com.example.data.remote.groupOnlineShoppingResults
import com.example.data.remote.extractDirectRetailerUrl
import com.example.data.remote.isAccessorySearchQuery
import com.example.data.remote.isWheelFitmentQuery
import com.example.ui.viewmodel.OnlineSearchProvider
import com.example.ui.viewmodel.TrackerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    // Grouping and sorting can be CPU-heavy on a tablet. Do the work off the UI thread,
    // and build only the currently requested view (all sellers or Amazon-only). Building both
    // lists for every search doubled CPU work and allocations even when one list was never shown.
    val visibleGroupsState = produceState(
        initialValue = emptyList<OnlineSearchDisplayGroup>(),
        state.onlineSearchResults,
        state.onlineSearchQuery,
        state.onlineSearchShowSecondHand,
        state.onlineSearchAmazonOnly
    ) {
        value = withContext(Dispatchers.Default) {
            buildOnlineSearchDisplayGroups(
                results = state.onlineSearchResults,
                query = state.onlineSearchQuery,
                showSecondHand = state.onlineSearchShowSecondHand,
                amazonOnly = state.onlineSearchAmazonOnly
            )
        }
    }
    val visibleGroups = visibleGroupsState.value
    // Store selection is a local view filter over the common Google Shopping response.
    // GAME is not queried separately; it appears only when Shopping reports that merchant.
    val selectedStoreKey = remember(state.onlineSearchQuery, state.onlineSearchProvider) {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    val selectedConsoleModelKey = remember(state.onlineSearchQuery, state.onlineSearchProvider) {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    fun storeKey(offer: SerpApiShoppingResult): String = onlineOfferStoreKey(offer)

    // These transformations can touch every offer and merchant. Keep them off the
    // Compose/UI thread so filter taps and scrolling remain responsive on large responses.
    val storeChoicesState = produceState(
        initialValue = emptyList<StoreFilterChoice>(),
        visibleGroups
    ) {
        value = withContext(Dispatchers.Default) {
            visibleGroups.flatMap { it.group.offers }
                .distinctBy(::storeKey)
                .map { offer ->
                    val known = Store.findKnownByName(offer.storeName, offer.sourceUrl)
                    StoreFilterChoice(
                        key = storeKey(offer),
                        label = known?.name ?: offer.storeName.trim()
                    )
                }
                .sortedBy { it.label.lowercase(Locale.ROOT) }
        }
    }
    val storeChoices = storeChoicesState.value

    val filteredGroupsState = produceState(
        initialValue = visibleGroups,
        visibleGroups,
        selectedStoreKey.value
    ) {
        val selectedKey = selectedStoreKey.value
        value = withContext(Dispatchers.Default) {
            if (selectedKey == null) {
                visibleGroups
            } else {
                visibleGroups.mapNotNull { displayGroup ->
                    val offers = displayGroup.group.offers.filter { offer ->
                        storeKey(offer) == selectedKey
                    }
                    if (offers.isEmpty()) null
                    else displayGroup.copy(group = displayGroup.group.copy(offers = offers))
                }.sortedBy { it.group.minPrice }
            }
        }
    }
    val filteredGroups = filteredGroupsState.value

    // Family bucket classification can scan all grouped titles; do not perform it during composition.
    // Model choices stay independent of the selected individual store filter.
    val familyBucketsState = produceState(
        initialValue = emptyList<ConsoleModelBucket>(),
        visibleGroups,
        state.onlineSearchQuery
    ) {
        value = withContext(Dispatchers.Default) {
            buildConsoleModelBuckets(state.onlineSearchQuery, visibleGroups.map { it.group })
        }
    }
    val familyBuckets = familyBucketsState.value
    val selectedBucket = familyBuckets.firstOrNull { it.key == selectedConsoleModelKey.value }
    val showConsoleModelPicker =
        isConsoleFamilyOverviewQuery(state.onlineSearchQuery) && familyBuckets.isNotEmpty()



    fun openOffer(offer: SerpApiShoppingResult) {
        val destination = retailerDestinationForOffer(
            sourceUrl = offer.sourceUrl,
            storeName = offer.storeName,
            productTitle = offer.title
        ) ?: return
        openStoreUrl(
            context,
            destination,
            preferAmazonApp = offer.isAmazonOffer && destination.contains("amazon.es", ignoreCase = true)
        )
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
                        Text("Buscador online", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    }
                    Text("Consulta Google Shopping o Amazon.es. Las ofertas aceptadas se guardan en Mercado; los precios se verifican en la tienda.")
                    OutlinedTextField(
                        value = state.onlineSearchQuery,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("¿Qué producto buscas?") },
                        placeholder = { Text("Ej. teclado Hall, Volkswagen Polo 9N3, Anker Prime") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() })
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onSearch,
                            enabled = !state.isOnlineSearching && !state.isRefreshingPrices && state.onlineSearchQuery.trim().length >= 2,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (state.isOnlineSearching && state.onlineSearchProvider == OnlineSearchProvider.GOOGLE_SHOPPING) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(5.dp))
                            } else {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(5.dp))
                            }
                            Text("Buscar ofertas", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = onAmazonSearch,
                            enabled = !state.isOnlineSearching && !state.isRefreshingPrices && state.onlineSearchQuery.trim().length >= 2,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (state.isOnlineSearching && state.onlineSearchProvider == OnlineSearchProvider.AMAZON) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(5.dp))
                            } else {
                                Icon(Icons.Default.ShoppingBag, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(5.dp))
                            }
                            Text("Amazon.es", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.onlineSearchResults.isNotEmpty()) {
                            TextButton(
                                onClick = onForceRefresh,
                                enabled = !state.isOnlineSearching && !state.isRefreshingPrices &&
                                    state.onlineSearchQuery.trim().length >= 2,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Actualizar resultados ahora (puede consumir cuota)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        TextButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Configurar precios", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(
                        "Se consulta el catálogo público, no tus cupones ni precios personalizados de Prime.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { openAmazonSearch(context, state.onlineSearchQuery) },
                        modifier = Modifier.align(Alignment.End),
                        enabled = !state.isOnlineSearching && !state.isRefreshingPrices
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Abrir Amazon con mi sesión Prime")
                    }
                }
            }
        }
        if (state.isOnlineSearching) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        state.onlineSearchStatus?.let { status ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Text(
                        status,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                                label = { Text("Todas las ofertas") }
                            )
                            FilterChip(
                                selected = state.onlineSearchAmazonOnly,
                                onClick = { onAmazonOnlyChange(true) },
                                label = { Text("Solo Amazon") }
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
            if (storeChoices.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Tiendas encontradas", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedStoreKey.value == null,
                                onClick = { selectedStoreKey.value = null },
                                label = { Text("Todas las tiendas") }
                            )
                            storeChoices.forEach { choice ->
                                FilterChip(
                                    selected = selectedStoreKey.value == choice.key,
                                    onClick = { selectedStoreKey.value = if (selectedStoreKey.value == choice.key) null else choice.key },
                                    label = { Text(choice.label, maxLines = 1) }
                                )
                            }
                        }
                    }
                }
            }
            if (showConsoleModelPicker && selectedBucket == null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Elige una versión de consola", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Después podrás escoger almacenamiento/edición y comparar las tiendas. Si falta una versión, podrás lanzar una búsqueda específica.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(familyBuckets, key = { it.key }) { bucket ->
                    ConsoleModelBucketCard(bucket = bucket, onClick = { selectedConsoleModelKey.value = bucket.key })
                }
            } else {
                if (showConsoleModelPicker && selectedBucket != null) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { selectedConsoleModelKey.value = null }) {
                                Text("Todas las versiones")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(selectedBucket.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Elige almacenamiento/edición", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                val groupsToShow = if (showConsoleModelPicker && selectedBucket != null) {
                    filteredGroups.filter { item -> selectedBucket.groups.any { it.identityKey == item.group.identityKey } }
                } else {
                    filteredGroups
                }
                if (showConsoleModelPicker && selectedBucket != null && selectedBucket.groups.isEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "No se encontraron ofertas de ${selectedBucket.label} en la búsqueda general. No se muestra ningún precio estimado.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = {
                                    selectedStoreKey.value = null
                                    onAmazonOnlyChange(false)
                                    onQueryChange(selectedBucket.searchQuery)
                                    onSearch()
                                },
                                enabled = !state.isOnlineSearching && !state.isRefreshingPrices
                            ) {
                                Text("Buscar esta versión · puede consumir cuota")
                            }
                        }
                    }
                } else if (groupsToShow.isEmpty()) {
                    item {
                        Text(
                            if (selectedStoreKey.value != null)
                                "No hay ofertas de esa tienda para esta versión en los resultados devueltos por el proveedor. Selecciona «Todas las tiendas» para quitar el filtro. Mínimo no inventa precios."
                            else if (state.onlineSearchProvider == OnlineSearchProvider.AMAZON)
                                "Amazon.es no ha devuelto productos con precio en euros para esta búsqueda."
                            else
                                "No hay ofertas con precio en euros y un enlace utilizable para esta búsqueda.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(groupsToShow, key = { it.stableKey }) { displayGroup ->
                    val group = displayGroup.group
                    ShoppingResultCard(
                        group = group,
                        onOpenProduct = if (group.offers.all { it.isFitmentUnverified || it.isCategoryUnverified }) {
                            null
                        } else {
                            { onOpenProduct(group.identityKey) }
                        },
                        onOpenOffer = ::openOffer,
                        onOpenBestOffer = { openOffer(group.bestPurchaseOffer) }
                    )
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
private fun ShoppingResultCard(
    group: OnlineProductGroup,
    onOpenProduct: (() -> Unit)?,
    onOpenOffer: (SerpApiShoppingResult) -> Unit,
    onOpenBestOffer: () -> Unit
) {
    val best = group.cheapestOffer
    // Shared by the card details and its action button; keep it in the composable scope.
    val purchaseOffer = group.bestPurchaseOffer
    val directRetailerUrl = extractDirectRetailerUrl(purchaseOffer.sourceUrl)
    val knownRetailer = Store.findKnownByName(
        name = purchaseOffer.storeName,
        sourceUrl = purchaseOffer.sourceUrl
    )
    val canOpenRetailer = directRetailerUrl != null || knownRetailer != null
    val fitmentUnverified = group.offers.all { it.isFitmentUnverified }
    val categoryUnverified = group.offers.all { it.isCategoryUnverified }
    val wheelSetCount = group.wheelSetCount
    val hasTrustedPrice = group.offers.any {
        !it.isSuspiciousPrice && !it.isFitmentUnverified && !it.isCategoryUnverified
    }
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
            if (fitmentUnverified || categoryUnverified) {
                Text(
                    when {
                        fitmentUnverified -> "El título no confirma todas las medidas solicitadas. Comprueba diámetro, anclaje, ancho y ET. No se añade a Mercado ni al historial hasta confirmar la compatibilidad."
                        else -> "El anuncio mezcla varias medidas o variantes. Se muestra para revisión, pero no se guarda en Mercado ni se usa para el historial hasta confirmar la variante exacta."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                if (group.representativeImageUrl.isNotBlank()) {
                    AsyncImage(
                        model = group.representativeImageUrl,
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
                        if (categoryUnverified || fitmentUnverified) {
                            "Variante por confirmar · " + NumberFormat.getCurrencyInstance(Locale("es", "ES")).format(best.price)
                        } else if (!hasTrustedPrice) {
                            "Precio no verificado · " + NumberFormat.getCurrencyInstance(Locale("es", "ES")).format(best.price)
                        } else if (wheelSetCount != null) {
                            "Desde " + NumberFormat.getCurrencyInstance(Locale("es", "ES")).format(group.minPrice) + " / llanta"
                        } else {
                            "Desde " + NumberFormat.getCurrencyInstance(Locale("es", "ES")).format(group.minPrice)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        if (best.isGoogleShoppingProductLink) {
                            "${group.storeCount} tiendas · precio más bajo listado en Google Shopping"
                        } else if (wheelSetCount != null) {
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
                    if (!purchaseOffer.isGoogleShoppingProductLink && purchaseOffer.sourceUrl != best.sourceUrl) {
                        Text(
                            "El precio más bajo está en Google Shopping; enlace directo: ${purchaseOffer.storeName} · " +
                                NumberFormat.getCurrencyInstance(Locale("es", "ES")).format(purchaseOffer.price),
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
            val displayedOffers = buildList {
                addAll(group.offers.filterNot { it.isSuspiciousPrice }.sortedBy { it.price }.take(4))
                group.offers.firstOrNull { Store.findKnownByName(it.storeName, it.sourceUrl)?.id == Store.GAME.id }?.let(::add)
            }.distinctBy { onlineOfferStoreKey(it) + "|" + it.price }
            if (displayedOffers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Tiendas y precios", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    displayedOffers.forEach { offer ->
                        val seller = Store.findKnownByName(offer.storeName, offer.sourceUrl)?.name ?: offer.storeName
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onOpenOffer(offer) }
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(seller, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                if (offer.isGoogleShoppingProductLink) {
                                    Text("Precio listado en Google Shopping · confirmar en tienda", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(
                                NumberFormat.getCurrencyInstance(Locale("es", "ES")).format(offer.price),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (group.offers.size > displayedOffers.size) {
                        Text("${group.offers.size - displayedOffers.size} ofertas más · abre la ficha para verlas todas", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (best.primeEligible) {
                Text("Entrega Prime disponible en la oferta más barata", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(onClick = onOpenBestOffer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        (categoryUnverified || fitmentUnverified) && !canOpenRetailer && purchaseOffer.isGoogleShoppingProductLink -> "Revisar variante en Google Shopping"
                        (categoryUnverified || fitmentUnverified) && knownRetailer != null && directRetailerUrl == null -> "Buscar variante en ${knownRetailer.name}"
                        (categoryUnverified || fitmentUnverified) -> "Revisar variante · " + purchaseOffer.storeName
                        !hasTrustedPrice && !canOpenRetailer && purchaseOffer.isGoogleShoppingProductLink -> "Revisar oferta sin verificar en Google Shopping"
                        !hasTrustedPrice && knownRetailer != null && directRetailerUrl == null -> "Buscar oferta sin verificar en ${knownRetailer.name}"
                        !hasTrustedPrice -> "Revisar oferta sin verificar · ${purchaseOffer.storeName}"
                        purchaseOffer.isGoogleShoppingProductLink && knownRetailer != null && directRetailerUrl == null -> "Buscar en ${knownRetailer.name}"
                        purchaseOffer.isGoogleShoppingProductLink && directRetailerUrl == null -> "Abrir en Google Shopping"
                        else -> "Abrir oferta directa · ${purchaseOffer.storeName}"
                    }
                )
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}


private data class StoreFilterChoice(val key: String, val label: String)

private fun onlineOfferStoreKey(offer: SerpApiShoppingResult): String =
    Store.findKnownByName(offer.storeName, offer.sourceUrl)?.id
        ?: "seller:" + offer.storeName.trim().lowercase(Locale.ROOT)

@Composable
private fun ConsoleModelBucketCard(bucket: ConsoleModelBucket, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(bucket.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (bucket.groups.isEmpty()) {
                Text(
                    "Sin ofertas en la búsqueda actual",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "${bucket.groups.size} variantes de almacenamiento/edición · ${bucket.storeCount} tiendas",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (bucket.cheapestPrice.isFinite() && bucket.cheapestPrice > 0.0) {
                    Text(
                        "Desde " + NumberFormat.getCurrencyInstance(Locale("es", "ES")).format(bucket.cheapestPrice),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
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
    // Search results are provider-controlled. Validate before passing any URI to an app/browser.
    val safeUrl = safeExternalHttpsUrl(url) ?: return
    val uri = Uri.parse(safeUrl)
    if (preferAmazonApp) {
        val amazonIntent = Intent(Intent.ACTION_VIEW, uri).setPackage(AMAZON_SHOPPING_PACKAGE)
        try {
            context.startActivity(amazonIntent)
            return
        } catch (_: ActivityNotFoundException) {
            // Amazon app isn't installed; continue with the validated website below.
        } catch (_: SecurityException) {
            // If Android refuses the app-specific intent, use the validated browser fallback.
        }
    }
    openExternalHttpsUrl(context, safeUrl)
}

