package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.example.data.model.MarketplaceCategorySummary
import kotlinx.coroutines.flow.StateFlow
import com.example.data.model.Store
import com.example.ui.components.ProductCard
import com.example.ui.components.ProductImageView
import com.example.ui.viewmodel.SortOption
import com.example.ui.viewmodel.marketplaceLabel
import com.example.ui.viewmodel.marketplaceStoresForProducts
import com.example.ui.viewmodel.TrackerUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(
    productsForCategory: (ProductCategory) -> StateFlow<List<Product>>,
    filteredProductsForCategory: (ProductCategory) -> StateFlow<List<Product>>,
    categorySummaries: List<MarketplaceCategorySummary>,
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
    var forceShowAllProducts by rememberSaveable { mutableStateOf(false) }

    val hasActiveMarketFilter = uiState.selectedStoreName != null ||
        uiState.onlyAthFilter ||
        uiState.onlyHistoricalMinimumFilter
    val shouldShowCategoryHome = uiState.selectedCategory == ProductCategory.ALL &&
        !hasActiveMarketFilter && !forceShowAllProducts
    val canReturnToCategoryHome = uiState.searchQuery.isBlank() &&
        uiState.selectedStoreName == null &&
        !uiState.onlyAthFilter &&
        !uiState.onlyHistoricalMinimumFilter &&
        (uiState.selectedCategory != ProductCategory.ALL || forceShowAllProducts)

    // Repeated products stay tucked away until the user chooses a family, version, then capacity.
    var expandedFamilyKeys by remember { mutableStateOf(emptySet<String>()) }
    var expandedVariantKeys by remember { mutableStateOf(emptySet<String>()) }
    var expandedStorageKeys by remember { mutableStateOf(emptySet<String>()) }

    if (shouldShowCategoryHome) {
        CategoryHomeScreen(
            summaries = categorySummaries,
            totalProductCount = categorySummaries.sumOf { it.productCount },
            searchQuery = uiState.searchQuery,
            onSearchChange = onSearchChange,
            onSearchSubmit = { forceShowAllProducts = true },
            onCategorySelect = { category ->
                forceShowAllProducts = false
                onCategorySelect(category)
            },
            onViewAllProducts = { forceShowAllProducts = true },
            modifier = modifier
        )
        return
    }

    // This branch is only reached after opening a category or explicitly requesting all results.
    // The StateFlow is scoped to the selected category and remains cached after first use.
    val loadedProducts by productsForCategory(uiState.selectedCategory).collectAsStateWithLifecycle()
    val products by filteredProductsForCategory(uiState.selectedCategory).collectAsStateWithLifecycle()

    // Store choices are derived from the same cached, visible category flow, never the global directory.
    val displayStores = remember(loadedProducts) {
        marketplaceStoresForProducts(loadedProducts).map { store ->
            val label = marketplaceLabel(store)
            Triple(store, label, label.lowercase(java.util.Locale.ROOT))
        }.distinctBy { it.third }
    }

    val verifiedProductCount = remember(products) { products.count { it.isPriceVerified } }
    val savedFamilies = remember(products, uiState.sortOption) {
        buildSavedProductFamilyBuckets(products, uiState.sortOption)
    }

    // Keep Mercado as a drill-down, not a wall of expanded cards. Changing filters
    // resets the selection so expansions from a previous result set cannot reappear.
    LaunchedEffect(uiState.selectedCategory, uiState.searchQuery, uiState.selectedStoreName) {
        expandedFamilyKeys = emptySet()
        expandedVariantKeys = emptySet()
        expandedStorageKeys = emptySet()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("market_screen_list"),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        if (canReturnToCategoryHome) {
            item {
                OutlinedButton(
                    onClick = {
                        forceShowAllProducts = false
                        if (uiState.selectedCategory != ProductCategory.ALL) {
                            onCategorySelect(ProductCategory.ALL)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("return_to_categories_button")
                ) {
                    Text("Volver a categorías")
                }
            }
        }

        // Compact section heading: remove the large decorative banner so products and
        // filters appear sooner, with less drawing and less vertical scrolling.
        item {
            Text(
                text = "Productos guardados",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
                    .padding(horizontal = 16.dp, vertical = 6.dp)
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
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.38f),
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        // One compact horizontal filter rail replaces the three separate, cluttered rows.
        // Store options are limited to sellers represented by products from the active category.
        item {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("market_filter_rail"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(ProductCategory.values(), key = { it.name }) { category ->
                    FilterChip(
                        selected = uiState.selectedCategory == category,
                        onClick = {
                            if (category != uiState.selectedCategory) onStoreFilterSelect(null)
                            onCategorySelect(category)
                        },
                        label = {
                            Text(
                                category.displayName,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.testTag("category_chip_${category.name}")
                    )
                }

                item {
                    Box {
                        FilterChip(
                            selected = uiState.selectedStoreName != null,
                            onClick = { showStoreMenu = true },
                            label = {
                                Text(
                                    uiState.selectedStoreName ?: "Tienda (${displayStores.size})",
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.testTag("filter_store_chip")
                        )
                        DropdownMenu(
                            expanded = showStoreMenu,
                            onDismissRequest = { showStoreMenu = false }
                        ) {
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 420.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text("Todas las tiendas de esta categoría (${displayStores.size})")
                                    },
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
                                        },
                                        modifier = Modifier.testTag("store_menu_item_${store.id}")
                                    )
                                }
                            }
                        }
                    }
                }

                item {
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
                                Text("Solo mínimos verificados", fontSize = 11.sp)
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF10B981).copy(alpha = 0.2f),
                            selectedLabelColor = Color(0xFF047857)
                        ),
                        modifier = Modifier.testTag("filter_only_ath")
                    )
                }

                item {
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
                                Text("Mínimo observado", fontSize = 11.sp)
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF10B981).copy(alpha = 0.2f),
                            selectedLabelColor = Color(0xFF047857)
                        ),
                        modifier = Modifier.testTag("filter_historical_minimum")
                    )
                }

                item {
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
                                    Text(uiState.sortOption.label, fontSize = 11.sp)
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
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                            MaterialTheme.shapes.large
                        )
                        .padding(24.dp),
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
            items(
                items = savedFamilies.chunked(2),
                key = { row -> row.joinToString("|") { it.key } }
            ) { familyRow ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                        .testTag("market_product_grid_row"),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    familyRow.forEach { family ->
                        val familyExpanded = family.key in expandedFamilyKeys
                        if (family.productCount == 1) {
                            val product = family.products.single()
                            ProductCard(
                                product = product,
                                onClick = { onProductClick(product) },
                                onToggleTrack = { onToggleTrack(product) },
                                modifier = Modifier.weight(1f),
                                compact = true
                            )
                        } else {
                            SavedProductFamilyGridTile(
                                family = family,
                                expanded = familyExpanded,
                                expandedVariantKeys = expandedVariantKeys,
                                expandedStorageKeys = expandedStorageKeys,
                                onToggleFamily = {
                                    if (familyExpanded) {
                                        expandedFamilyKeys = emptySet()
                                        expandedVariantKeys = emptySet()
                                        expandedStorageKeys = emptySet()
                                    } else {
                                        expandedFamilyKeys = setOf(family.key)
                                        expandedVariantKeys = emptySet()
                                        expandedStorageKeys = emptySet()
                                    }
                                },
                                onToggleVariant = { key, isExpanded ->
                                    expandedVariantKeys = if (isExpanded) emptySet() else setOf(key)
                                    expandedStorageKeys = emptySet()
                                },
                                onToggleStorage = { key, isExpanded ->
                                    expandedStorageKeys = if (isExpanded) emptySet() else setOf(key)
                                },
                                onProductClick = onProductClick,
                                onToggleTrack = onToggleTrack,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (familyRow.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SavedProductFamilyGridTile(
    family: SavedProductFamily,
    expanded: Boolean,
    expandedVariantKeys: Set<String>,
    expandedStorageKeys: Set<String>,
    onToggleFamily: () -> Unit,
    onToggleVariant: (String, Boolean) -> Unit,
    onToggleStorage: (String, Boolean) -> Unit,
    onProductClick: (Product) -> Unit,
    onToggleTrack: (Product) -> Unit,
    modifier: Modifier = Modifier
) {
    val representative = family.products.firstOrNull {
        it.imageUrl.isNotBlank() || !it.imageResName.isNullOrBlank()
    } ?: family.products.firstOrNull()
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f), shape)
            .padding(10.dp)
            .testTag("saved_family_card_${family.key.hashCode()}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onToggleFamily)
                .padding(2.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (representative != null) {
                ProductImageView(
                    product = representative,
                    modifier = Modifier
                        .size(68.dp)
                        .testTag("family_photo_${family.key.hashCode()}"),
                    showAthRibbon = false
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = family.label,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${family.productCount} fichas · ${family.variants.size} versiones",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${family.storeCount} tiendas",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (expanded) "Ocultar ▴" else "Elegir versión ▾",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (expanded) {
            family.variants.forEach { variant ->
                val variantKey = "${family.key}::${variant.key}"
                val variantExpanded = variantKey in expandedVariantKeys
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { onToggleVariant(variantKey, variantExpanded) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = variant.label,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (variant.storages.size == 1) "1 configuración" else "${variant.storages.size} configuraciones",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(text = if (variantExpanded) "▴" else "▾", color = MaterialTheme.colorScheme.primary)
                    }

                    if (variantExpanded) {
                        variant.storages.forEach { storage ->
                            val storageKey = "${variantKey}::${storage.label}"
                            val storageExpanded = storageKey in expandedStorageKeys
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                        .clickable { onToggleStorage(storageKey, storageExpanded) }
                                        .padding(horizontal = 8.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = storage.label,
                                        modifier = Modifier.weight(1f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${storage.products.size} fichas ${if (storageExpanded) "▴" else "▾"}",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (storageExpanded) {
                                    storage.products.forEach { product ->
                                        ProductCard(
                                            product = product,
                                            onClick = { onProductClick(product) },
                                            onToggleTrack = { onToggleTrack(product) },
                                            modifier = Modifier.padding(top = 3.dp),
                                            compact = true
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

