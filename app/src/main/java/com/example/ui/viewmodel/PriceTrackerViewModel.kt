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
    val isTestingSingleProduct: Boolean = false,
    val priceRefreshStatus: String? = null,
    val onlineSearchQuery: String = "",
    val onlineSearchResults: List<SerpApiShoppingResult> = emptyList(),
    val isOnlineSearching: Boolean = false,
    val onlineSearchStatus: String? = null,
    val onlineSearchAmazonOnly: Boolean = false,
    val onlineSearchProvider: OnlineSearchProvider = OnlineSearchProvider.GOOGLE_SHOPPING,
    val isCheckingSerpApiAccount: Boolean = false,
    val serpApiAccountStatus: String? = null,
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

    private fun quotaBlockMessage(info: SerpApiAccountInfo): String = when {
        info.searchesRemaining <= 0 -> {
            "Has agotado las búsquedas disponibles de tu cuenta SerpApi. Mínimo no hará nuevas consultas para evitar errores." +
                (info.renewalDate?.let { " Tu cuota se renueva el $it." } ?: " Revisa el saldo o el plan de tu cuenta.")
        }
        info.remainingThisHour == 0 -> {
            "Has alcanzado el límite horario de SerpApi (${info.hourlyLimit} búsquedas/h). Espera a que se renueve. " +
                info.quotaSummary()
        }
        else -> quotaMessage(info)
    }

    private fun accountStatusBlockMessage(info: SerpApiAccountInfo): String? {
        val status = info.accountStatus.trim()
        return if (status.isNotEmpty() && !status.equals("active", ignoreCase = true)) {
            "La cuenta SerpApi figura como «$status». No se ha realizado ninguna búsqueda ni gastado cuota."
        } else {
            null
        }
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
                val message = serpApiTokenStore.lastReadErrorMessage()
                    ?: "Guarda una clave de SerpApi cifrada en Ajustes antes de consultar precios."
                _uiState.value = _uiState.value.copy(priceRefreshStatus = message)
                showSnackbar(message)
                return@launch
            }

            val currentTokenFingerprint = tokenFingerprint(token)
            if (!testOneProduct &&
                priceRefreshPreferences.getString("single_product_test_fingerprint", null) != currentTokenFingerprint
            ) {
                val message = "Por seguridad, primero usa «Probar con 1 producto» y confirma que se guarda una oferta. No se ha lanzado el lote de 40 búsquedas."
                _uiState.value = _uiState.value.copy(priceRefreshStatus = message)
                showSnackbar(message)
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isRefreshingPrices = true,
                isTestingSingleProduct = testOneProduct,
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
                // Account validation is free. Never spend a product search if the account
                // endpoint cannot confirm that the key works and quota is available.
                val accountInfo = readAccountInfo(token)
                if (accountInfo == null) {
                    val message = "No se ha podido validar la cuenta SerpApi. No se ha lanzado ninguna búsqueda ni gastado cuota. Comprueba la conexión e inténtalo de nuevo."
                    _uiState.value = _uiState.value.copy(priceRefreshStatus = message)
                    showSnackbar(message)
                    return@launch
                }
                accountStatusBlockMessage(accountInfo)?.let { message ->
                    _uiState.value = _uiState.value.copy(priceRefreshStatus = message)
                    showSnackbar(message)
                    return@launch
                }
                val capacity = accountCapacity(accountInfo)
                if (capacity <= 0) {
                    val message = quotaBlockMessage(accountInfo)
                    _uiState.value = _uiState.value.copy(priceRefreshStatus = message)
                    showSnackbar(message)
                    return@launch
                }
                val selectedTargets = targets.take(minOf(targets.size, capacity))
                if (selectedTargets.size < targets.size) {
                    val message = "Para respetar tu cuota actual, esta actualización consultará ${selectedTargets.size} de ${targets.size} productos."
                    _uiState.value = _uiState.value.copy(priceRefreshStatus = message)
                }
                val requests = selectedTargets.map { product ->
                    SerpApiRequestProduct(
                        id = product.id,
                        title = product.title,
                        brand = product.brand,
                        ean = product.ean
                    )
                }
                val batchResult = serpApiClient.searchProducts(token, requests) { stage ->
                    _uiState.value = _uiState.value.copy(priceRefreshStatus = stage)
                }
                val quotes = batchResult.quotes
                if (testOneProduct) {
                    priceRefreshPreferences.edit().apply {
                        if (quotes.isNotEmpty()) putString("single_product_test_fingerprint", currentTokenFingerprint)
                        else remove("single_product_test_fingerprint")
                    }.apply()
                }
                val quotaAfter = runCatching { serpApiClient.getAccountInfo(token) }.getOrNull()
                if (quotaAfter != null) {
                    _uiState.value = _uiState.value.copy(serpApiAccountStatus = quotaMessage(quotaAfter))
                }
                val quotaNote = quotaAfter?.let { " ${it.quotaSummary()}" }.orEmpty()
                val attemptedAt = System.currentTimeMillis()
                priceRefreshPreferences.edit().apply {
                    selectedTargets.forEach { product ->
                        putLong("attempt_${product.id}", attemptedAt)
                    }
                }.apply()

                if (quotes.isEmpty()) {
                    val message = if (testOneProduct) {
                        "La prueba respondió, pero no encontró una oferta que coincidiera de forma segura con ese producto. No se ha cambiado ningún precio y el lote de 40 sigue bloqueado para proteger tu cuota."
                    } else {
                        "SerpApi respondió, pero ${batchResult.queriesWithoutMatches} de ${batchResult.requestedQueries - batchResult.failedQueries} consultas no produjeron ofertas con coincidencia segura. No se han cambiado los precios."
                    }
                    _uiState.value = _uiState.value.copy(priceRefreshStatus = message)
                    showSnackbar(message)
                } else {
                    val updatedProducts = repository.recordSerpApiQuotes(quotes)
                    val partialNotice = if (batchResult.failedQueries > 0) {
                        " Consulta parcial: fallaron ${batchResult.failedQueries} de ${batchResult.requestedQueries} consultas; se han conservado y guardado las ofertas válidas."
                    } else {
                        ""
                    }
                    val message = "Actualización terminada: precios guardados para $updatedProducts producto(s).$partialNotice Es una instantánea de Google Shopping; verifica el precio final y la disponibilidad en la tienda." + quotaNote
                    _uiState.value = _uiState.value.copy(priceRefreshStatus = message)
                    showSnackbar(message)
                }
            } catch (e: SerpApiException) {
                if (testOneProduct) priceRefreshPreferences.edit().remove("single_product_test_fingerprint").apply()
                refreshAccountStatusAfterFailure(token)
                val message = e.message ?: "No se pudo consultar SerpApi."
                _uiState.value = _uiState.value.copy(priceRefreshStatus = message)
                showSnackbar(message)
            } catch (e: Exception) {
                if (testOneProduct) priceRefreshPreferences.edit().remove("single_product_test_fingerprint").apply()
                refreshAccountStatusAfterFailure(token)
                val message = when (e) {
                    is java.net.SocketTimeoutException -> "Se agotó el tiempo de espera de SerpApi. No se ha guardado ningún precio."
                    is java.net.UnknownHostException -> "No se pudo localizar SerpApi. Comprueba la conexión a Internet y vuelve a probar."
                    is java.io.IOException -> "Falló la conexión con SerpApi (${e.javaClass.simpleName}). No se ha guardado ningún precio."
                    else -> "Error técnico al actualizar el catálogo (${e.javaClass.simpleName}). No se ha guardado ningún precio."
                }
                _uiState.value = _uiState.value.copy(priceRefreshStatus = message)
                showSnackbar(message)
            } finally {
                _uiState.value = _uiState.value.copy(
                    isRefreshingPrices = false,
                    isTestingSingleProduct = false
                )
            }
        }
    }

    private suspend fun refreshAccountStatusAfterFailure(token: String) {
        // The account endpoint is free. Refresh displayed quota even when product queries fail,
        // so the UI never leaves a stale pre-batch balance after an unsuccessful batch.
        val info = runCatching { serpApiClient.getAccountInfo(token) }.getOrNull()
        val status = info?.let(::quotaMessage)
            ?: "No se pudo actualizar el saldo tras el fallo; la cifra anterior puede estar desactualizada."
        _uiState.value = _uiState.value.copy(serpApiAccountStatus = status)
    }

    private fun tokenFingerprint(token: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun checkSerpApiAccount() {
        if (_uiState.value.isCheckingSerpApiAccount || _uiState.value.isRefreshingPrices || _uiState.value.isOnlineSearching) return
        _uiState.value = _uiState.value.copy(
            isCheckingSerpApiAccount = true,
            serpApiAccountStatus = "Comprobando la clave y el saldo de SerpApi…"
        )
        viewModelScope.launch {
            try {
                val token = serpApiTokenStore.read()
                if (token.isNullOrBlank()) {
                    val message = serpApiTokenStore.lastReadErrorMessage()
                        ?: "No hay una clave de SerpApi guardada. Pégala arriba y pulsa «Guardar clave cifrada»."
                    _uiState.value = _uiState.value.copy(serpApiAccountStatus = message)
                    return@launch
                }
                val info = serpApiClient.getAccountInfo(token)
                val message = if (info.accountStatus.isNotBlank() && !info.accountStatus.equals("active", ignoreCase = true)) {
                    "Estado de la cuenta SerpApi: ${info.accountStatus}. ${info.quotaSummary()}"
                } else {
                    quotaMessage(info)
                }
                _uiState.value = _uiState.value.copy(serpApiAccountStatus = message)
                showSnackbar(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SerpApiException) {
                val message = error.message ?: "No se pudo validar la clave de SerpApi."
                _uiState.value = _uiState.value.copy(serpApiAccountStatus = message)
                showSnackbar(message)
            } catch (_: Exception) {
                val message = "No se pudo consultar la cuenta. Comprueba la conexión e inténtalo de nuevo."
                _uiState.value = _uiState.value.copy(serpApiAccountStatus = message)
                showSnackbar(message)
            } finally {
                _uiState.value = _uiState.value.copy(isCheckingSerpApiAccount = false)
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

    /** Search Google Shopping or Amazon.es, reusing fresh local results whenever possible. */
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
                // Cache lookup comes first: saved results remain usable even if the quota is empty
                // or the API key was removed after the previous search.
                val cached = onlineSearchCache.read(provider.name, query)
                if (cached != null) {
                    val ageMinutes = ((System.currentTimeMillis() - cached.savedAt) / 60_000L).coerceAtLeast(1L)
                    val base = onlineSearchResultMessage(cached.results, provider)
                    val cacheNotice = "Resultados guardados hace $ageMinutes min; no se ha consumido otra búsqueda."
                    _uiState.value = _uiState.value.copy(
                        onlineSearchResults = cached.results,
                        onlineSearchStatus = "$base $cacheNotice"
                    )
                    return@launch
                }

                val token = serpApiTokenStore.read()
                if (token.isNullOrBlank()) {
                    val message = serpApiTokenStore.lastReadErrorMessage()
                        ?: "Para mostrar precios dentro de Mínimo, guarda tu clave de SerpApi en Ajustes. También puedes abrir Amazon con tu sesión iniciada."
                    _uiState.value = _uiState.value.copy(onlineSearchStatus = message)
                    showSnackbar(message)
                    return@launch
                }

                // Validate the key and quota before the paid search request.
                val accountInfo = readAccountInfo(token)
                if (accountInfo == null) {
                    val message = "No se ha podido validar la cuenta SerpApi. No se ha lanzado ninguna búsqueda ni gastado cuota. Comprueba la conexión e inténtalo de nuevo."
                    _uiState.value = _uiState.value.copy(onlineSearchStatus = message)
                    showSnackbar(message)
                    return@launch
                }
                accountStatusBlockMessage(accountInfo)?.let { message ->
                    _uiState.value = _uiState.value.copy(onlineSearchStatus = message)
                    showSnackbar(message)
                    return@launch
                }
                if (accountCapacity(accountInfo) <= 0) {
                    val message = quotaBlockMessage(accountInfo)
                    _uiState.value = _uiState.value.copy(onlineSearchStatus = message)
                    showSnackbar(message)
                    return@launch
                }

                val results = when (provider) {
                    OnlineSearchProvider.GOOGLE_SHOPPING -> serpApiClient.searchOnline(token, query)
                    OnlineSearchProvider.AMAZON -> serpApiClient.searchAmazonOnline(token, query)
                }
                onlineSearchCache.write(provider.name, query, results)
                val base = onlineSearchResultMessage(results, provider)
                // The account endpoint is free; refresh the displayed balance after a live request.
                val quotaAfter = runCatching { serpApiClient.getAccountInfo(token) }.getOrNull()
                val quotaNote = quotaAfter?.let { " ${it.quotaSummary()}" }.orEmpty()
                val message = base + quotaNote
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

    private fun onlineSearchResultMessage(
        results: List<SerpApiShoppingResult>,
        provider: OnlineSearchProvider
    ): String = when {
        results.isEmpty() && provider == OnlineSearchProvider.AMAZON ->
            "Amazon.es no ha devuelto productos con precio en euros y enlace válido. Prueba con el nombre o modelo completo."
        results.isEmpty() ->
            "No se han encontrado ofertas con precio en euros y enlace seguro. Prueba con otra búsqueda."
        provider == OnlineSearchProvider.AMAZON ->
            "Amazon.es: ${results.size} productos encontrados, ordenados por precio. El precio final y los cupones se confirman en Amazon."
        else ->
            "Google Shopping: ${results.size} ofertas encontradas, ordenadas por precio. Confirma el precio final antes de comprar."
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
