package com.example.ui.screens

import com.example.data.model.PriceBadgeType
import com.example.data.model.Product
import com.example.data.model.ProductCategory
import com.example.data.model.Store
import com.example.ui.viewmodel.SortOption
import com.example.ui.viewmodel.TrackerUiState
import java.util.Locale

/** Visible category rows only; demo catalogue entries never become live marketplace results. */
internal fun marketplaceCategoryProducts(
    products: List<Product>,
    category: ProductCategory
): List<Product> = products.filter { product ->
    product.isPriceVerified ||
        product.isTracked ||
        product.priceHistory.isNotEmpty() ||
        product.savedHistoricalPrice != null ||
        product.isUnverifiedLiveListing
}.filter { product ->
    category == ProductCategory.ALL || product.category == category
}

/** Applies only interactive filters to already-scoped category rows. */
internal fun filterMarketplaceProductsForState(
    products: List<Product>,
    state: TrackerUiState
): List<Product> =
    products
        .filter { product ->
            if (state.searchQuery.isBlank()) true
            else {
                val query = state.searchQuery.trim().lowercase(Locale.ROOT)
                product.title.lowercase(Locale.ROOT).contains(query) ||
                    product.brand.lowercase(Locale.ROOT).contains(query) ||
                    (product.isEanVerified && product.ean.contains(query, ignoreCase = true)) ||
                    product.category.displayName.lowercase(Locale.ROOT).contains(query) ||
                    product.currentStore.name.lowercase(Locale.ROOT).contains(query) ||
                    product.currentStore.domain.lowercase(Locale.ROOT).contains(query)
            }
        }
        .filter { product -> !state.onlyAthFilter || product.isAtAth }
        .filter { product ->
            !state.onlyHistoricalMinimumFilter ||
                product.priceBadgeState.type == PriceBadgeType.HISTORICAL_MINIMUM
        }
        .filter { product ->
            val selectedStore = state.selectedStoreName
            selectedStore == null ||
                product.currentStore.name.contains(selectedStore, ignoreCase = true) ||
                product.offers.any { it.store.name.contains(selectedStore, ignoreCase = true) }
        }
        .let { list ->
            when (state.sortOption) {
                SortOption.BEST_ATH_DISCOUNT -> list.sortedWith(
                    compareByDescending<Product> { it.isPriceVerified }
                        .thenByDescending { it.realSavingsPercent }
                        .thenBy { it.title.lowercase(Locale.ROOT) }
                )
                SortOption.PRICE_LOW_HIGH -> list.sortedWith(
                    compareByDescending<Product> { it.isPriceVerified }
                        .thenBy { if (it.isPriceVerified) it.currentPrice else Double.POSITIVE_INFINITY }
                        .thenBy { it.title.lowercase(Locale.ROOT) }
                )
                SortOption.PRICE_HIGH_LOW -> list.sortedWith(
                    compareByDescending<Product> { it.isPriceVerified }
                        .thenByDescending { if (it.isPriceVerified) it.currentPrice else Double.NEGATIVE_INFINITY }
                        .thenBy { it.title.lowercase(Locale.ROOT) }
                )
                SortOption.NAME_AZ -> list.sortedBy { it.title.lowercase(Locale.ROOT) }
                SortOption.NAME_ZA -> list.sortedByDescending { it.title.lowercase(Locale.ROOT) }
                SortOption.CATEGORY_AZ -> list.sortedWith(
                    compareBy<Product> { it.category.displayName.lowercase(Locale.ROOT) }
                        .thenBy { it.title.lowercase(Locale.ROOT) }
                )
                SortOption.RATING -> list.sortedWith(
                    compareByDescending<Product> { it.isRatingVerified }
                        .thenByDescending { if (it.isRatingVerified) it.rating else Double.NEGATIVE_INFINITY }
                        .thenBy { it.title.lowercase(Locale.ROOT) }
                )
            }
        }

/** Store-filter options come only from the selected category's visible products. */
internal fun marketplaceStoresForProducts(products: List<Product>): List<Store> =
    products.asSequence()
        .flatMap { product -> sequenceOf(product.currentStore) + product.offers.asSequence().map { it.store } }
        .filter { it.name.isNotBlank() }
        .distinctBy {
            it.id.takeIf(String::isNotBlank)?.lowercase(Locale.ROOT)
                ?: "${it.name.trim().lowercase(Locale.ROOT)}|${it.domain.trim().lowercase(Locale.ROOT)}"
        }
        .toList()
