package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Toys
import androidx.compose.material.icons.filled.Tv
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Product
import com.example.data.model.MarketplaceCategorySummary
import com.example.data.model.ProductCategory

internal data class CategorySummary(
    val category: ProductCategory,
    val productCount: Int,
    val storeCount: Int
)

/**
 * Builds the category overview from the products already available in the local catalogue.
 * Store IDs from both primary products and their offers are de-duplicated for each category.
 */
internal fun buildCategorySummaries(products: List<Product>): List<CategorySummary> {
    val productsByCategory = products.groupBy { it.category }
    return ProductCategory.values()
        .filter { it != ProductCategory.ALL }
        .map { category ->
            val categoryProducts = productsByCategory[category].orEmpty()
            val storeIds = buildSet {
                categoryProducts.forEach { product ->
                    product.currentStore.id.takeIf { it.isNotBlank() }?.let(::add)
                    product.offers.forEach { offer ->
                        offer.store.id.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            CategorySummary(
                category = category,
                productCount = categoryProducts.size,
                storeCount = storeIds.size
            )
        }
}

@Composable
fun CategoryHomeScreen(
    summaries: List<MarketplaceCategorySummary>,
    totalProductCount: Int,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onCategorySelect: (ProductCategory) -> Unit,
    onViewAllProducts: () -> Unit,
    modifier: Modifier = Modifier
) {
    val categories = remember(summaries) { summaries }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            // Keep the existing market test hook while the entry screen changes.
            .testTag("market_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("category_home_screen"),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = "Explora por categorías",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (searchQuery.isBlank()) {
                        "$totalProductCount productos guardados, organizados para encontrarlos más rápido."
                    } else {
                        "Pulsa Buscar para abrir la lista y ver los resultados de «${searchQuery.trim()}»."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_input"),
                placeholder = { Text("Buscar producto, marca o código EAN…", fontSize = 13.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Buscar",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Limpiar búsqueda")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        }

        // Horizontal rows use less vertical space than tall icon-on-top cards.
        items(
            items = categories,
            key = { it.category.name }
        ) { summary ->
            CategoryCard(
                summary = summary,
                onClick = { onCategorySelect(summary.category) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OutlinedButton(
                onClick = onViewAllProducts,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("view_all_products_button"),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Store,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (searchQuery.isBlank()) "Ver todos los productos ($totalProductCount)"
                    else "Ver resultados de búsqueda"
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(
    summary: MarketplaceCategorySummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .heightIn(min = 68.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag("category_card_${summary.category.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(categoryIconBackground(summary.category)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = categoryIcon(summary.category),
                contentDescription = summary.category.displayName,
                modifier = Modifier.size(21.dp),
                tint = categoryIconTint(summary.category)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = summary.category.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val storeLabel = if (summary.storeCount == 1) "1 comercio" else "${summary.storeCount} comercios"
            val productLabel = if (summary.productCount == 1) "1 producto" else "${summary.productCount} productos"
            Text(
                text = "$productLabel · $storeLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "›",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 25.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Keep the current compact card grid, but give related catalog areas a quiet, distinct accent.
 * All colors come from the active Material theme, so light/dark mode remain consistent.
 */
@Composable
private fun categoryIconBackground(category: ProductCategory) = when (category) {
    ProductCategory.SMARTPHONES,
    ProductCategory.SPORTS -> MaterialTheme.colorScheme.primaryContainer
    ProductCategory.COMPUTING,
    ProductCategory.PERSONAL_CARE -> MaterialTheme.colorScheme.secondaryContainer
    ProductCategory.GAMING,
    ProductCategory.TOYS -> MaterialTheme.colorScheme.tertiaryContainer
    ProductCategory.TV_AUDIO,
    ProductCategory.REPLACEMENT_PARTS,
    ProductCategory.ACCESSORIES,
    ProductCategory.HOME_APPLIANCES,
    ProductCategory.OTHER,
    ProductCategory.ALL -> MaterialTheme.colorScheme.surfaceContainerHigh
}

@Composable
private fun categoryIconTint(category: ProductCategory) = when (category) {
    ProductCategory.SMARTPHONES,
    ProductCategory.SPORTS -> MaterialTheme.colorScheme.onPrimaryContainer
    ProductCategory.COMPUTING,
    ProductCategory.PERSONAL_CARE -> MaterialTheme.colorScheme.onSecondaryContainer
    ProductCategory.GAMING,
    ProductCategory.TOYS -> MaterialTheme.colorScheme.onTertiaryContainer
    ProductCategory.TV_AUDIO,
    ProductCategory.REPLACEMENT_PARTS,
    ProductCategory.ACCESSORIES,
    ProductCategory.HOME_APPLIANCES,
    ProductCategory.OTHER,
    ProductCategory.ALL -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun categoryIcon(category: ProductCategory): ImageVector = when (category) {
    ProductCategory.SMARTPHONES -> Icons.Default.Smartphone
    ProductCategory.COMPUTING -> Icons.Default.Laptop
    ProductCategory.GAMING -> Icons.Default.SportsEsports
    ProductCategory.TV_AUDIO -> Icons.Default.Tv
    ProductCategory.HOME_APPLIANCES -> Icons.Default.Kitchen
    ProductCategory.PERSONAL_CARE -> Icons.Default.Spa
    ProductCategory.ACCESSORIES -> Icons.Default.Category
    ProductCategory.REPLACEMENT_PARTS -> Icons.Default.Build
    ProductCategory.TOYS -> Icons.Default.Toys
    ProductCategory.SPORTS -> Icons.Default.FitnessCenter
    ProductCategory.OTHER, ProductCategory.ALL -> Icons.Default.Category
}
