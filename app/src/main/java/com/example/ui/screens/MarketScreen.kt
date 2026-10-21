package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Product
import com.example.data.model.ProductCategory
import com.example.data.model.Store
import com.example.ui.components.ProductCard
import com.example.ui.viewmodel.SortOption
import com.example.ui.viewmodel.TrackerUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(
    products: List<Product>,
    stores: List<Store>,
    uiState: TrackerUiState,
    onSearchChange: (String) -> Unit,
    onCategorySelect: (ProductCategory) -> Unit,
    onToggleOnlyAth: () -> Unit,
    onToggleOnlyHistoricalMinimum: () -> Unit,
    onStoreFilterSelect: (String?) -> Unit,
    onSortChange: (SortOption) -> Unit,
    onProductClick: (Product) -> Unit,
    onToggleTrack: (Product) -> Unit,
    onPrepareTrackedRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSortMenu by remember { mutableStateOf(false) }
    var showStoreMenu by remember { mutableStateOf(false) }
    val verifiedProductCount = remember(products) { products.count { it.isPriceVerified } }
    val savedFamilies = remember(products, uiState.sortOption) {
        buildSavedProductFamilyBuckets(products, uiState.sortOption)
    }
    // Repeated products stay tucked away until the user chooses a family, version, then capacity.
    var expandedFamilyKeys by remember { mutableStateOf(emptySet<String>()) }
    var expandedVariantKeys by remember { mutableStateOf(emptySet<String>()) }
    var expandedStorageKeys by remember { mutableStateOf(emptySet<String>()) }

    // Collapse marketplace-generated aliases (for example AliExpress-239640258)
    // into one canonical display/filter option without deleting any underlying seller records.
    val displayStores = remember(stores) {
        stores.map { store ->
            val canonical = Store.findKnownByName(store.name, "https://${store.domain}")
            val label = canonical?.name ?: store.name
            Triple(store, label, label.lowercase(java.util.Locale.ROOT))
        }.distinctBy { it.third }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("market_screen_list"),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Compact section heading: remove the large decorative banner so products and
        // filters appear sooner, with less drawing and less vertical scrolling.
        item {
            Text(
                text = "Productos guardados",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Search Bar
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 5.dp)
            ) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_input"),
                    placeholder = {
                        Text(
                            "Buscar producto, marca o código EAN...",
                            fontSize = 13.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Buscar",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchChange("") }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Limpiar búsqueda"
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }

        // Spanish Stores Horizontal Quick Scroll
        item {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tiendas con precios",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.selectedStoreName != null) {
                        androidx.compose.material3.TextButton(
                            onClick = { onStoreFilterSelect(null) },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = "Limpiar filtro",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    // All stores chip
                    item {
                        FilterChip(
                            selected = uiState.selectedStoreName == null,
                            onClick = { onStoreFilterSelect(null) },
                            label = { Text("Todas (${displayStores.size})", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }

                    items(displayStores, key = { it.first.id }) { (store, displayName, _) ->
                        val isSelected = uiState.selectedStoreName.equals(displayName, ignoreCase = true)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) onStoreFilterSelect(null)
                                else onStoreFilterSelect(displayName)
                            },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(store.brandColor)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = displayName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 160.dp)
                                    )
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            modifier = Modifier.testTag("store_chip_${store.id}")
                        )
                    }
                }
            }
        }

        // Category Filter Chips
        item {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ProductCategory.values()) { cat ->
                    FilterChip(
                        selected = uiState.selectedCategory == cat,
                        onClick = { onCategorySelect(cat) },
                        label = { Text(cat.displayName, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.testTag("category_chip_${cat.name}")
                    )
                }
            }
        }

        // Secondary Filters: Only ATH toggle, Store filter, Sort
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Only ATH toggle
                FilterChip(
                    selected = uiState.onlyAthFilter,
                    onClick = onToggleOnlyAth,
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (uiState.onlyAthFilter) Color(0xFF047857) else Color(0xFFEAB308)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Solo mínimos verificados", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF10B981).copy(alpha = 0.2f),
                        selectedLabelColor = Color(0xFF047857)
                    ),
                    modifier = Modifier.testTag("filter_only_ath")
                )

                // Dedicated filter for the verified "MÍNIMO OBSERVADO" badge.
                FilterChip(
                    selected = uiState.onlyHistoricalMinimumFilter,
                    onClick = onToggleOnlyHistoricalMinimum,
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (uiState.onlyHistoricalMinimumFilter) Color(0xFF047857) else Color(0xFF10B981)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Mínimo observado", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF10B981).copy(alpha = 0.2f),
                        selectedLabelColor = Color(0xFF047857)
                    ),
                    modifier = Modifier.testTag("filter_historical_minimum")
                )

                // Store Filter Dropdown
                Box {
                    FilterChip(
                        selected = uiState.selectedStoreName != null,
                        onClick = { showStoreMenu = true },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Store,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = uiState.selectedStoreName ?: "Tienda: Todas (${displayStores.size})",
                                    fontSize = 12.sp
                                )
                            }
                        },
                        modifier = Modifier.testTag("filter_store_chip")
                    )

                    DropdownMenu(
                        expanded = showStoreMenu,
                        onDismissRequest = { showStoreMenu = false }
                    ) {
                        // DropdownMenu may ask children for intrinsic size while positioning.
                        // A LazyColumn does not support intrinsic measurement and can crash on
                        // some Compose paths, so use a bounded scrollable Column for this small menu.
                        Column(
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            DropdownMenuItem(
                                text = { Text("Todas las tiendas (${displayStores.size})") },
                                onClick = {
                                    onStoreFilterSelect(null)
                                    showStoreMenu = false
                                }
                            )
                            displayStores.forEach { (store, displayName, _) ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(store.brandColor)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(displayName)
                                        }
                                    },
                                    onClick = {
                                        onStoreFilterSelect(displayName)
                                        showStoreMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Sort Dropdown
                Box {
                    FilterChip(
                        selected = false,
                        onClick = { showSortMenu = true },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.FilterList,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(uiState.sortOption.label, fontSize = 12.sp)
                            }
                        },
                        modifier = Modifier.testTag("sort_filter_chip")
                    )

                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        SortOption.values().forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    onSortChange(option)
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // Manual refresh for followed products; no background work or hidden quota usage.
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OutlinedButton(
                    onClick = onPrepareTrackedRefresh,
                    enabled = !uiState.isRefreshingPrices &&
                        !uiState.isOnlineSearching &&
                        !uiState.isPreparingSerpApiAction &&
                        !uiState.isExecutingSerpApiAction &&
                        !uiState.isCheckingSerpApiAccount,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("update_tracked_products_button")
                ) {
                    Text(
                        when {
                            uiState.isPreparingSerpApiAction -> "Comprobando saldo…"
                            uiState.isExecutingSerpApiAction -> "Actualizando guardados…"
                            else -> "Actualizar guardados"
                        }
                    )
                }
                Text(
                    "Manual · solo productos seguidos · máximo 10 por actualización; espera 24 h entre consultas.",
                    modifier = Modifier.padding(horizontal = 4.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                uiState.serpApiActionStatus?.let { status ->
                    Text(
                        status,
                        modifier = Modifier.padding(horizontal = 4.dp),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Section header with counter
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${products.size} productos · $verifiedProductCount con precio verificado",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        if (products.isNotEmpty() &&
            products.none { it.isPriceVerified } &&
            uiState.sortOption in setOf(SortOption.PRICE_LOW_HIGH, SortOption.PRICE_HIGH_LOW)
        ) {
            item {
                Text(
                    text = "La ordenación por precio solo utiliza observaciones reales guardadas en Room.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Product cards are presented as a compact drill-down: family -> version -> capacity -> SKU.
        if (products.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Tu Mercado está vacío",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Pulsa la lupa, busca un producto real y Mínimo lo añadirá automáticamente a este escaparate.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(savedFamilies, key = { it.key }) { family ->
                if (family.productCount == 1) {
                    val product = family.products.single()
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                        ProductCard(
                            product = product,
                            onClick = { onProductClick(product) },
                            onToggleTrack = { onToggleTrack(product) }
                        )
                    }
                } else {
                    val familyExpanded = family.key in expandedFamilyKeys
                    val familyPrefix = family.key + "::"
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f))
                                .clickable {
                                    if (familyExpanded) {
                                        expandedFamilyKeys = expandedFamilyKeys - family.key
                                        expandedVariantKeys = expandedVariantKeys.filterNot { it.startsWith(familyPrefix) }
                                        expandedStorageKeys = expandedStorageKeys.filterNot { it.startsWith(familyPrefix) }
                                    } else {
                                        expandedFamilyKeys = expandedFamilyKeys + family.key
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = family.label,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${family.productCount} fichas · ${family.variants.size} versiones · ${family.storeCount} tiendas",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = if (familyExpanded) "Ocultar ▴" else "Elegir versión ▾",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (familyExpanded) {
                            family.variants.forEach { variant ->
                                val variantExpansionKey = "${family.key}::${variant.key}"
                                val variantExpanded = variantExpansionKey in expandedVariantKeys
                                Column(modifier = Modifier.padding(start = 8.dp, top = 7.dp)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                            .clickable {
                                                if (variantExpanded) {
                                                    expandedVariantKeys = expandedVariantKeys - variantExpansionKey
                                                    expandedStorageKeys = expandedStorageKeys.filterNot {
                                                        it.startsWith("$variantExpansionKey::")
                                                    }
                                                } else {
                                                    expandedVariantKeys = expandedVariantKeys
                                                        .filterNot { it.startsWith(familyPrefix) } + variantExpansionKey
                                                    expandedStorageKeys = expandedStorageKeys
                                                        .filterNot { it.startsWith(familyPrefix) }
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = variant.label,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = if (variant.storages.size == 1) "1 capacidad disponible" else "${variant.storages.size} capacidades disponibles",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = if (variantExpanded) "Ocultar ▴" else "Elegir capacidad ▾",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    if (variantExpanded) {
                                        variant.storages.forEach { storage ->
                                            val storageExpansionKey = "$variantExpansionKey::${storage.label}"
                                            val storageExpanded = storageExpansionKey in expandedStorageKeys
                                            Column(modifier = Modifier.padding(start = 8.dp, top = 5.dp, bottom = 3.dp)) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(9.dp))
                                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))
                                                        .clickable {
                                                            if (storageExpanded) {
                                                                expandedStorageKeys = expandedStorageKeys - storageExpansionKey
                                                            } else {
                                                                expandedStorageKeys = expandedStorageKeys
                                                                    .filterNot { it.startsWith("$variantExpansionKey::") } + storageExpansionKey
                                                            }
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 9.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = storage.label,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Text(
                                                        text = "${storage.products.size} ficha(s)  ${if (storageExpanded) "▴" else "▾"}",
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                if (storageExpanded) {
                                                    storage.products.forEach { product ->
                                                        Box(modifier = Modifier.padding(start = 4.dp, top = 5.dp, bottom = 3.dp)) {
                                                            ProductCard(
                                                                product = product,
                                                                onClick = { onProductClick(product) },
                                                                onToggleTrack = { onToggleTrack(product) }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
