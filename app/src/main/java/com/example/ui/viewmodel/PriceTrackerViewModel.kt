package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.remote.OnlineSearchCache
import com.example.data.remote.SerpApiAccountInfo
import com.example.data.remote.SerpApiClient
import com.example.data.remote.SerpApiException
import com.example.data.remote.SerpApiRequestProduct
import com.example.data.remote.SerpApiShoppingResult
import com.example.data.remote.SerpApiTokenStore
import com.example.data.model.Product
import com.example.data.model.ProductCategory
import com.example.data.model.Store
import com.example.data.repository.ProductRepository
import com.example.data.repository.UrlAnalysisResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

enum class OnlineSearchProvider { GOOGLE_SHOPPING, AMAZON }

enum class SortOption(val label: String) {
    BEST_ATH_DISCOUNT("Mejores ofertas verificadas"),
    PRICE_LOW_HIGH("Precio: menor a mayor (verificados)"),
    PRICE_HIGH_LOW("Precio: mayor a menor (verificados)"),
    NAME_AZ("Nombre: A-Z"),
    NAME_ZA("Nombre: Z-A"),
    CATEGORY_AZ("Categoría: A-Z"),
    RATING("Mejor valorados (verificados)")
}

data class TrackerUiState(
    val searchQuery: String = "",
    val selectedCategory: ProductCategory = ProductCategory.ALL,
    val onlyAthFilter: Boolean = false,
    val selectedStoreName: String? = null,
    val sortOption: SortOption = SortOption.BEST_ATH_DISCOUNT,
    val selectedProduct: Product? = null,
    val isSettingAlert: Product? = null,
    val verifierInput: String = "",
    val verifierResult: UrlAnalysisResult? = null,
    val isVerifying: Boolean = false,
    val isRefreshingPrices: Boolean = false,
    val priceRefreshStatus: String? = null,
    val onlineSearchQuery: String = "",
    val onlineSearchResults: List<SerpApiShoppingResult> = emptyList(),
    val isOnlineSearching: Boolean = false,
    val onlineSearchStatus: String? = null,
    val onlineSearchAmazonOnly: Boolean = false,
    val onlineSearchProvider: OnlineSearchProvider = OnlineSearchProvider.GOOGLE_SHOPPING,
    val snackbarMessage: String? = null
)

class PriceTrackerViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val repository = ProductRepository(
        storeDao = database.storeDao(),
        productDao = database.productDao(),
        trackedDao = database.trackedProductDao(),
        priceObservationDao = database.priceObservationDao(),
        externalScope = viewModelScope
    )

    private val serpApiTokenStore = SerpApiTokenStore(application)
    private val serpApiClient = SerpApiClient()
    private val onlineSearchCache = OnlineSearchCache(application)
    private val priceRefreshPreferences = application.getSharedPreferences(
        "minimo_serpapi_refresh_state",
        Context.MODE_PRIVATE
    )

    private val _uiState = MutableStateFlow(TrackerUiState())

    val uiState: StateFlow<TrackerUiState> = _uiState.asStateFlow()

    val spanishStores: StateFlow<List<Store>> = repository.allStoresStream
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Store.ALL_STORES)

    // Filtered products flow
    val filteredProducts: StateFlow<List<Product>> = combine(
        repository.allProductsStream,
        _uiState
    ) { allProducts, state ->
        allProducts
            .filter { product ->
                // Category filter
                (state.selectedCategory == ProductCategory.ALL || product.category == state.selectedCategory)
            }
            .filter { product ->
                // Search query
                if (state.searchQuery.isBlank()) true
                else {
                    val query = state.searchQuery.trim().lowercase()
                    product.title.lowercase().contains(query) ||
                    product.brand.lowercase().contains(query) ||
                    product.ean.contains(query) ||
                    product.category.displayName.lowercase().contains(query) ||
                    product.currentStore.name.lowercase().contains(query) ||
                    product.currentStore.domain.lowercase().contains(query)
                }
            }
            .filter { product ->
                // Only ATH filter
                if (state.onlyAthFilter) product.isAtAth else true
            }
            .filter { product ->
                // Store filter
                if (state.selectedStoreName != null) {
                    product.currentStore.name.contains(state.selectedStoreName, ignoreCase = true) ||
                    product.offers.any { it.store.name.contains(state.selectedStoreName, ignoreCase = true) }
                } else true
            }
            .let { list ->
                // Sorting
                when (state.sortOption) {
                    SortOption.BEST_ATH_DISCOUNT -> list.sortedWith(
                        compareByDescending<Product> { it.isPriceVerified }
                            .thenByDescending { it.realSavingsPercent }
                            .thenBy { it.title.lowercase() }
                    )
                    SortOption.PRICE_LOW_HIGH -> list.sortedWith(
                        compareByDescending<Product> { it.isPriceVerified }
                            .thenBy { if (it.isPriceVerified) it.currentPrice else Double.POSITIVE_INFINITY }
                            .thenBy { it.title.lowercase() }
                    )
                    SortOption.PRICE_HIGH_LOW -> list.sortedWith(
                        compareByDescending<Product> { it.isPriceVerified }
                            .thenByDescending { if (it.isPriceVerified) it.currentPrice else Double.NEGATIVE_INFINITY }
                            .thenBy { it.title.lowercase() }
                    )
                    SortOption.NAME_AZ -> list.sortedBy { it.title.lowercase() }
                    SortOption.NAME_ZA -> list.sortedByDescending { it.title.lowercase() }
                    SortOption.CATEGORY_AZ -> list.sortedWith(
                        compareBy<Product> { it.category.displayName.lowercase() }
                            .thenBy { it.title.lowercase() }
                    )
                    SortOption.RATING -> list.sortedWith(
                        compareByDescending<Product> { it.isRatingVerified }
                            .thenByDescending { if (it.isRatingVerified) it.rating else Double.NEGATIVE_INFINITY }
                            .thenBy { it.title.lowercase() }
                    )
                }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trackedProducts: StateFlow<List<Product>> = repository.trackedProductsStream
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val todayAthDeals: StateFlow<List<Product>> = repository.todayAthDealsStream
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Account checks are free at SerpApi. If the account endpoint is temporarily unavailable,
     * manual single searches can still be attempted and the provider remains the final authority.
     */
    private suspend fun readAccountInfo(token: String): SerpApiAccountInfo? = try {
        serpApiClient.getAccountInfo(token)
    } catch (error: SerpApiException) {
        if (error.httpCode == 401 || error.httpCode == 403) throw error
        null
    }

    private fun accountCapacity(info: SerpApiAccountInfo): Int {
        val hourly = info.remainingThisHour ?: Int.MAX_VALUE
        return minOf(info.searchesRemaining, hourly).coerceAtLeast(0)
    }

    private fun quotaMessage(info: SerpApiAccountInfo): String {
        val renewal = info.renewalDate?.let { " Se renueva el $it." }.orEmpty()
        val hourly = info.remainingThisHour?.let { " Disponibles esta hora: $it de ${info.hourlyLimit}." }.orEmpty()
        return info.quotaSummary() + hourly + renewal
    }

    /**
     * Run a manual price refresh. A single-product test is offered first so the user can verify
     * the provider configuration before spending credits on the full catalogue.
     */
    fun refreshLivePrices(testOneProduct: Boolean) {
        if (_uiState.value.isRefreshingPrices) return
        viewModelScope.launch {
            val token = serpApiTokenStore.read()
            if (token.isNullOrBlank()) {
                val message = "Guarda una clave de SerpApi cifrada en Ajustes antes de consultar precios."
                _uiState.value = _uiState.value.copy(priceRefreshStatus = message)
                showSnackbar(message)
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isRefreshingPrices = true,
                priceRefreshStatus = if (testOneProduct) "Preparando una prueba de 1 producto…" else "Preparando la actualización del catálogo…"
            )
            try {
                val allProducts = withTimeout(20_000L) {
                    repository.allProductsStream.first { products -> products.isNotEmpty() }
                }
                val targets = if (testOneProduct) {
                    listOf(
                        allProducts.firstOrNull { product -> product.id == "demo_galaxy_s26_ultra" }
                            ?: allProducts.first()
                    )
                } else {
                    // Spread catalog lookups over multiple manual batches, prioritizing products
                    // never queried or queried longest ago. This keeps one tap within 40 searches
                    // on the advertised free tier and prevents repeated no-match items from starving
                    // the rest of the catalog.
                    allProducts.sortedWith(
                        compareBy<Product> { priceRefreshPreferences.getLong("attempt_${it.id}", 0L) }
                            .thenBy { it.title.lowercase() }
                    ).take(40)
                }
                val requests = targets.map { product ->
                    SerpApiRequestProduct(
                        id = product.id,
                        title = product.title,
                        brand = product.brand,
                        ean = product.ean
                    )
                }
                if (!reserveSerpApiSearches(requests.size)) {
                    val message = "Para respetar el límite gratuito, Mínimo limita las consultas a 45 por hora en este dispositivo. Espera a que se renueve la ventana y vuelve a intentarlo."
                    _uiState.value = _uiState.value.copy(priceRefreshStatus = message)
                    showSnackbar(message)
                    return@launch
                }
                val batchResult = serpApiClient.searchProducts(token, requests) { stage ->
                    _uiState.value = _uiState.value.copy(priceRefreshStatus = stage)
                }
                val quotes = batchResult.quotes
                val attemptedAt = System.currentTimeMillis()
                priceRefreshPreferences.edit().apply {
                    targets.forEach { product ->
                        putLong("attempt_${product.id}", attemptedAt)
                    }
                }.apply()

                if (quotes.isEmpty()) {
                    val message = "La fuente no devolvió ofertas que coincidan con seguridad con los productos consultados. No se han cambiado los precios."
                    _uiState.value = _uiState.value.copy(priceRefreshStatus = message)
                    showSnackbar(message)
                } else {
                    val updatedProducts = repository.recordSerpApiQuotes(quotes)
                    val partialNotice = if (batchResult.failedQueries > 0) {
                        " Consulta parcial: fallaron ${batchResult.failedQueries} de ${batchResult.requestedQueries} consultas; se han conservado y guardado las ofertas válidas."
                    } else {
                        ""
                    }
                    val message = "Actualización terminada: precios guardados para $updatedProducts producto(s).$partialNotice Es una instantánea de Google Shopping; verifica el precio final y la disponibilidad en la tienda."
                    _uiState.value = _uiState.value.copy(priceRefreshStatus = message)
                    showSnackbar(message)
                }
            } catch (e: SerpApiException) {
                val message = e.message ?: "No se pudo consultar SerpApi."
                _uiState.value = _uiState.value.copy(priceRefreshStatus = message)
                showSnackbar(message)
            } catch (_: Exception) {
                val message = "No se pudo actualizar el catálogo. Comprueba tu clave, la conexión y vuelve a intentarlo más tarde."
                _uiState.value = _uiState.value.copy(priceRefreshStatus = message)
                showSnackbar(message)
            } finally {
                _uiState.value = _uiState.value.copy(isRefreshingPrices = false)
            }
        }
    }

    fun onOnlineSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(
            onlineSearchQuery = query,
            onlineSearchResults = emptyList(),
            onlineSearchStatus = null
        )
    }

    fun onOnlineSearchAmazonOnlyChange(onlyAmazon: Boolean) {
        _uiState.value = _uiState.value.copy(onlineSearchAmazonOnly = onlyAmazon)
    }

    fun searchOnlineShopping() = startOnlineSearch(OnlineSearchProvider.GOOGLE_SHOPPING)

    fun searchAmazonShopping() = startOnlineSearch(OnlineSearchProvider.AMAZON)

    /** Each explicit Google Shopping or Amazon.es search reserves one local SerpApi request. */
    private fun startOnlineSearch(provider: OnlineSearchProvider) {
        val current = _uiState.value
        val query = current.onlineSearchQuery.trim()
        if (current.isOnlineSearching || current.isRefreshingPrices) return
        if (query.length < 2) {
            val message = "Escribe al menos 2 caracteres para buscar."
            _uiState.value = current.copy(onlineSearchStatus = message)
            showSnackbar(message)
            return
        }

        val searchingMessage = if (provider == OnlineSearchProvider.AMAZON) {
            "Consultando resultados públicos de Amazon.es…"
        } else {
            "Buscando ofertas en Google Shopping España…"
        }
        _uiState.value = current.copy(
            isOnlineSearching = true,
            onlineSearchStatus = searchingMessage,
            onlineSearchResults = emptyList(),
            onlineSearchProvider = provider,
            onlineSearchAmazonOnly = provider == OnlineSearchProvider.AMAZON
        )
        viewModelScope.launch {
            try {
                val token = serpApiTokenStore.read()
                if (token.isNullOrBlank()) {
                    val message = "Configura tu clave de SerpApi en Ajustes para consultar precios dentro de Mínimo. La comprobación de precios personalizados sigue disponible al abrir Amazon con tu sesión."
                    _uiState.value = _uiState.value.copy(onlineSearchStatus = message)
                    showSnackbar(message)
                    return@launch
                }
                if (!reserveSerpApiSearches(1)) {
                    val message = "Límite local alcanzado: máximo 45 búsquedas por hora entre Google Shopping, Amazon.es y la actualización del catálogo."
                    _uiState.value = _uiState.value.copy(onlineSearchStatus = message)
                    showSnackbar(message)
                    return@launch
                }

                val results = when (provider) {
                    OnlineSearchProvider.GOOGLE_SHOPPING -> serpApiClient.searchOnline(token, query)
                    OnlineSearchProvider.AMAZON -> serpApiClient.searchAmazonOnline(token, query)
                }
                val message = if (results.isEmpty()) {
                    if (provider == OnlineSearchProvider.AMAZON) {
                        "Amazon.es no ha devuelto resultados con precio en euros y enlace válido. Prueba una búsqueda más concreta."
                    } else {
                        "No se han encontrado ofertas con precio en euros y enlace seguro. Prueba otra búsqueda."
                    }
                } else {
                    val source = if (provider == OnlineSearchProvider.AMAZON) "productos de Amazon.es" else "resultados de Google Shopping"
                    "Se han encontrado ${results.size} ${source}, ordenados por precio. Confirma el precio final antes de comprar."
                }
                _uiState.value = _uiState.value.copy(
                    onlineSearchResults = results,
                    onlineSearchStatus = message
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SerpApiException) {
                val message = error.message ?: "No se pudo completar la búsqueda."
                _uiState.value = _uiState.value.copy(onlineSearchStatus = message)
                showSnackbar(message)
            } catch (_: Exception) {
                val message = "No se pudo buscar. Comprueba la conexión y la clave guardada en Ajustes."
                _uiState.value = _uiState.value.copy(onlineSearchStatus = message)
                showSnackbar(message)
            } finally {
                _uiState.value = _uiState.value.copy(isOnlineSearching = false)
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun onCategorySelect(category: ProductCategory) {
        _uiState.value = _uiState.value.copy(selectedCategory = category)
    }

    fun onToggleOnlyAth() {
        _uiState.value = _uiState.value.copy(onlyAthFilter = !_uiState.value.onlyAthFilter)
    }

    fun onStoreFilterSelect(storeName: String?) {
        _uiState.value = _uiState.value.copy(selectedStoreName = storeName)
    }

    fun onSortOptionChange(sortOption: SortOption) {
        _uiState.value = _uiState.value.copy(sortOption = sortOption)
    }

    fun onSelectProduct(product: Product?) {
        _uiState.value = _uiState.value.copy(selectedProduct = product)
    }

    fun onOpenAlertSetting(product: Product?) {
        _uiState.value = _uiState.value.copy(isSettingAlert = product)
    }

    fun toggleTracking(product: Product, targetPrice: Double? = null) {
        viewModelScope.launch {
            repository.toggleTracking(product.id, targetPrice)
            val msg = if (product.isTracked) {
                "Producto eliminado del seguimiento local"
            } else {
                "Producto guardado localmente. Los avisos automáticos aún no están disponibles."
            }
            showSnackbar(msg)
        }
    }

    fun saveTargetAlertPrice(product: Product, targetPrice: Double?) {
        viewModelScope.launch {
            repository.updateAlertTarget(product.id, targetPrice)
            _uiState.value = _uiState.value.copy(isSettingAlert = null)
            val targetStr = if (targetPrice != null) "${String.format("%.2f", targetPrice)} €" else "nuevo mínimo"
            showSnackbar(if (targetPrice != null) "Objetivo guardado: $targetStr. Aún no hay monitorización ni avisos automáticos." else "Seguimiento guardado. Aún no hay monitorización ni avisos automáticos.")
        }
    }

    fun onVerifierInputChange(input: String) {
        _uiState.value = _uiState.value.copy(verifierInput = input)
    }

    fun runUrlOrReferenceVerification() {
        val query = _uiState.value.verifierInput.trim()
        if (query.isBlank()) return

        _uiState.value = _uiState.value.copy(isVerifying = true)
        val result = repository.analyzeSpanishUrlOrQuery(query)
        _uiState.value = _uiState.value.copy(
            isVerifying = false,
            verifierResult = result
        )
    }

    fun clearVerifierResult() {
        _uiState.value = _uiState.value.copy(verifierResult = null, verifierInput = "")
    }

    fun simulatePriceDrop(productId: String) {
        repository.simulatePriceDrop(productId)
        showSnackbar("Simulación local de demostración. No se ha registrado un mínimo real ni se enviará ninguna alerta.")
    }

    fun clearSnackbarMessage() {
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }

    private fun showSnackbar(msg: String) {
        _uiState.value = _uiState.value.copy(snackbarMessage = msg)
    }
}
