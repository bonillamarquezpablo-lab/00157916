package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
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
    onStoreFilterSelect: (String?) -> Unit,
    onSortChange: (SortOption) -> Unit,
    onProductClick: (Product) -> Unit,
    onToggleTrack: (Product) -> Unit,
    onPrepareTrackedRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSortMenu by remember { mutableStateOf(false) }
    var showStoreMenu by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("market_screen_list"),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Hero Visual Banner
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF0B132B))
                    .border(1.dp, Color(0xFF10B981).copy(alpha = 0.3f), RoundedCornerShape(18.dp))
            ) {
                Image(
                    painter = painterResource(id = R.drawable.hero_price_banner),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                )

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color(0xFF0B132B).copy(alpha = 0.78f))
                )

                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF10B981))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "CATÁLOGO PERSONAL",
                                color = Color(0xFF0B132B),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${stores.size} tiendas configuradas",
                            color = Color(0xFF10B981),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Tu mercado de productos",
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                        lineHeight = 22.sp
                    )

                    Text(
                        text = "Los productos que consultes online se guardan aquí con sus ofertas y el historial real de precios.",
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
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
                    shape = RoundedCornerShape(14.dp),
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
                        text = "Tiendas Españolas en Base de Datos",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.selectedStoreName != null) {
                        Text(
                            text = "Limpiar filtro",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
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
                            label = { Text("Todas (${stores.size})", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF047857),
                                selectedLabelColor = Color.White
                            )
                        )
                    }

                    items(stores, key = { it.id }) { store ->
                        val isSelected = uiState.selectedStoreName.equals(store.name, ignoreCase = true)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) onStoreFilterSelect(null)
                                else onStoreFilterSelect(store.name)
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
                                    Text(store.name, fontSize = 11.sp)
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = store.brandColor.copy(alpha = 0.2f),
                                selectedLabelColor = MaterialTheme.colorScheme.onSurface
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
                                    text = uiState.selectedStoreName ?: "Tienda: Todas (${stores.size})",
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
                        DropdownMenuItem(
                            text = { Text("Todas las tiendas (${stores.size})") },
                            onClick = {
                                onStoreFilterSelect(null)
                                showStoreMenu = false
                            }
                        )
                        stores.forEach { store ->
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
                                        Text(store.name)
                                    }
                                },
                                onClick = {
                                    onStoreFilterSelect(store.name)
                                    showStoreMenu = false
                                }
                            )
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
                    "Manual · máximo 10 productos por pulsación · no repite consultas antes de 24 h",
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
                    text = "${products.size} productos · ${products.count { it.isPriceVerified }} precios observados",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = if (products.any { it.isPriceVerified }) "Precios online observados" else "Sin precios observados",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
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

        // Product Cards
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
            items(products, key = { it.id }) { product ->
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
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
