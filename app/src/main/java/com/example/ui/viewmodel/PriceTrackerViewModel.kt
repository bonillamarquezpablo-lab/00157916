package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.remote.OnlineSearchCache
import com.example.data.remote.WaybackPriceHistoryClient
import com.example.data.remote.isGoogleShoppingUrl
import com.example.data.remote.onlineProductIdentityKey
import com.example.data.remote.RejectedOnlineOffer
import com.example.data.remote.SerpApiAccountInfo
import com.example.data.remote.SerpApiClient
import com.example.data.remote.SerpApiException
import com.example.data.remote.SerpApiRequestProduct
import com.example.data.remote.SerpApiShoppingResult
import com.example.data.remote.SerpApiTokenStore
import com.example.data.remote.extractGoogleShoppingProductId
import com.example.data.model.Product
import com.example.data.model.ProductCategory
import com.example.data.model.Store
import com.example.data.repository.ProductRepository
import com.example.notifications.PriceAlertScheduler
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date

enum class OnlineSearchProvider { GOOGLE_SHOPPING, AMAZON }

enum class SerpApiActionType { UPDATE_TRACKED, COMPARE_STORES }

data class SerpApiActionConfirmation(
    val type: SerpApiActionType,
    val title: String,
    val searchesToSpend: Int,
    val remainingSearches: Int,
    val remainingThisHour: Int?,
    val message: String
)

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
    val onlineSearchDiscardedOffers: List<RejectedOnlineOffer> = emptyList(),
    val showOnlineSearchDiscarded: Boolean = false,
    val isOnlineSearching: Boolean = false,
    val isSearchingFreeHistory: Boolean = false,
    val freeHistoryStatus: String? = null,
    val freeHistoryProductId: String? = null,
    val onlineSearchStatus: String? = null,
    val onlineSearchAmazonOnly: Boolean = false,
    val onlineSearchShowSecondHand: Boolean = false,
    val onlineSearchProvider: OnlineSearchProvider = OnlineSearchProvider.GOOGLE_SHOPPING,
    val isCheckingSerpApiAccount: Boolean = false,
    val serpApiAccountStatus: String? = null,
    val isPreparingSerpApiAction: Boolean = false,
    val isExecutingSerpApiAction: Boolean = false,
    val activeSerpApiAction: SerpApiActionType? = null,
    val serpApiActionConfirmation: SerpApiActionConfirmation? = null,
    val serpApiActionStatus: String? = null,
    val snackbarMessage: String? = null
)

class PriceTrackerViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val TRACKED_REFRESH_COOLDOWN_MS = 24L * 60L * 60L * 1000L
        const val MAX_TRACKED_REFRESH_PER_TAP = 10
        const val MAX_ARCHIVE_PAGES_PER_SEARCH = 2
        const val MAX_ARCHIVE_LOOKUP_PER_CANDIDATE_MS = 20_000L
    }

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
    private val waybackPriceHistoryClient = WaybackPriceHistoryClient(application)
    private val onlineDiscardedCache = mutableMapOf<String, List<RejectedOnlineOffer>>()

    private fun onlineSearchCacheKey(provider: OnlineSearchProvider, query: String): String =
        "${provider.name}|${query.trim().lowercase(Locale.ROOT)}"
    private val priceRefreshPreferences = application.getSharedPreferences(
        "minimo_serpapi_refresh_state",
        Context.MODE_PRIVATE
    )

    private var pendingTrackedRefreshProducts: List<Product> = emptyList()
    private var pendingCompareStoresProduct: Product? = null
    private val _uiState = MutableStateFlow(TrackerUiState())

    val uiState: StateFlow<TrackerUiState> = _uiState.asStateFlow()

    init {
        // Register one daily, quota-capped monitor; it remains idle without price targets.
        runCatching { PriceAlertScheduler.ensureScheduled(application) }
    }

    // Only show merchants represented by at least one valid saved price. Store.ALL_STORES
    // remains the internal directory used to resolve existing product and offer records.
    val spanishStores: StateFlow<List<Store>> = repository.storesWithSavedPricesStream
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Filtered products flow
    val filteredProducts: StateFlow<List<Product>> = combine(
        repository.allProductsStream,
        _uiState
    ) { allProducts, state ->
        allProducts
            // Keep demo catalogue rows out of Mercado. Genuine price observations and user-followed
            // products remain visible; an unverified followed item still clearly shows no price.
            .filter { product -> product.isPriceVerified || product.isTracked }
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
                    // Prefer a product with a distinctive alphanumeric model identifier and a
                    // brand. A barcode is useful only if the provider indexes it; querying the
                    // first EAN-bearing product from the user's saved online catalogue can otherwise produce a false-negative test.
                    val bestIdentified = allProducts
                        .filter { it.brand.isNotBlank() }
                        .maxByOrNull { product ->
                            val identity = "${product.brand} ${product.title}"
                            val modelCode = Regex("""\b(?=[a-z0-9-]*[a-z])(?=[a-z0-9-]*\d)[a-z0-9-]{4,}\b""", RegexOption.IGNORE_CASE)
                                .find(identity)?.value
                            (if (modelCode != null) 100 else 0) +
                                (if (!product.ean.isNullOrBlank()) 10 else 0) +
                                product.title.length.coerceAtMost(80)
                        }
                    listOf(bestIdentified ?: allProducts.first())
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
                val batchResult = serpApiClient.searchProducts(
                    token = token,
                    products = requests,
                    onStage = { stage ->
                        _uiState.value = _uiState.value.copy(priceRefreshStatus = stage)
                    }
                )
                val quotes = batchResult.quotes
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
                        val tested = selectedTargets.firstOrNull()?.let { "${it.brand} ${it.title}".trim() }
                            ?: "el producto seleccionado"
                        "La prueba de «$tested» respondió, pero SerpApi no devolvió una oferta que coincidiera de forma segura. No se ha cambiado ningún precio ni creado un punto de historial. El lote de 40 sigue bloqueado para proteger tu cuota. Prueba la búsqueda manual con el nombre/modelo exacto."
                    } else {
                        "SerpApi respondió, pero ${batchResult.queriesWithoutMatches} de ${batchResult.requestedQueries - batchResult.failedQueries} consultas no produjeron ofertas con coincidencia segura. No se han cambiado los precios."
                    }
                    _uiState.value = _uiState.value.copy(priceRefreshStatus = message)
                    showSnackbar(message)
                } else {
                    val updatedProducts = repository.recordSerpApiQuotes(quotes)
                    if (testOneProduct) {
                        priceRefreshPreferences.edit().apply {
                            if (updatedProducts > 0) {
                                putString("single_product_test_fingerprint", currentTokenFingerprint)
                            } else {
                                remove("single_product_test_fingerprint")
                            }
                        }.apply()
                    }
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

    /**
     * Account lookup is free. The paid refresh only begins after the dialog confirms the exact
     * query count and current balance.
     */
    fun prepareTrackedRefresh() {
        val current = _uiState.value
        if (current.isRefreshingPrices || current.isOnlineSearching ||
            current.isPreparingSerpApiAction || current.isExecutingSerpApiAction ||
            current.isCheckingSerpApiAccount
        ) return
        pendingTrackedRefreshProducts = emptyList()
        pendingCompareStoresProduct = null
        _uiState.value = current.copy(
            isPreparingSerpApiAction = true,
            serpApiActionConfirmation = null,
            serpApiActionStatus = "Comprobando productos guardados y saldo de SerpApi…"
        )
        viewModelScope.launch {
            try {
                val token = serpApiTokenStore.read()
                if (token.isNullOrBlank()) {
                    val message = serpApiTokenStore.lastReadErrorMessage()
                        ?: "Guarda una clave de SerpApi en Ajustes antes de actualizar los productos seguidos."
                    _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
                    showSnackbar(message)
                    return@launch
                }
                val accountInfo = readAccountInfo(token)
                if (accountInfo == null) {
                    val message = "No se pudo comprobar el saldo de SerpApi. No se ha iniciado ninguna búsqueda ni gastado cuota."
                    _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
                    showSnackbar(message)
                    return@launch
                }
                accountStatusBlockMessage(accountInfo)?.let { message ->
                    _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
                    showSnackbar(message)
                    return@launch
                }

                val now = System.currentTimeMillis()
                val tracked = withTimeout(10_000L) { repository.trackedProductsStream.first() }
                val eligible = tracked
                    .filter { product ->
                        val lastAttempt = priceRefreshPreferences.getLong("tracked_response_${product.id}", 0L)
                        lastAttempt <= 0L || now - lastAttempt >= TRACKED_REFRESH_COOLDOWN_MS
                    }
                    .sortedWith(
                        compareBy<Product> { priceRefreshPreferences.getLong("tracked_response_${it.id}", 0L) }
                            .thenBy { it.title.lowercase(Locale.ROOT) }
                    )
                if (eligible.isEmpty()) {
                    val message = if (tracked.isEmpty()) {
                        "No tienes productos guardados para actualizar."
                    } else {
                        "Todos los productos guardados ya se consultaron hace menos de 24 horas. No se ha gastado cuota."
                    }
                    _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
                    showSnackbar(message)
                    return@launch
                }

                val firstTen = eligible.take(MAX_TRACKED_REFRESH_PER_TAP)
                val searches = minOf(firstTen.size, accountCapacity(accountInfo))
                if (searches <= 0) {
                    val message = quotaBlockMessage(accountInfo)
                    _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
                    showSnackbar(message)
                    return@launch
                }
                pendingTrackedRefreshProducts = firstTen.take(searches)
                val selectionNote = when {
                    eligible.size > MAX_TRACKED_REFRESH_PER_TAP ->
                        "Hay ${eligible.size} productos pendientes; esta pulsación prioriza los 10 consultados hace más tiempo."
                    searches < firstTen.size ->
                        "Por el saldo disponible solo se podrán consultar ${searches} de ${firstTen.size} productos pendientes."
                    else -> "Se omiten los productos consultados en las últimas 24 horas."
                }
                _uiState.value = _uiState.value.copy(
                    serpApiActionConfirmation = SerpApiActionConfirmation(
                        type = SerpApiActionType.UPDATE_TRACKED,
                        title = "Actualizar guardados",
                        searchesToSpend = searches,
                        remainingSearches = accountInfo.searchesRemaining,
                        remainingThisHour = accountInfo.remainingThisHour,
                        message = "$selectionNote Cada producto consume 1 búsqueda, aunque SerpApi no encuentre una coincidencia segura."
                    ),
                    serpApiActionStatus = null
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SerpApiException) {
                val message = error.message ?: "No se pudo comprobar SerpApi."
                _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
                showSnackbar(message)
            } catch (_: Exception) {
                val message = "No se pudieron preparar los productos guardados. No se ha confirmado ni iniciado ninguna búsqueda."
                _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
                showSnackbar(message)
            } finally {
                _uiState.value = _uiState.value.copy(isPreparingSerpApiAction = false)
            }
        }
    }

    /** Prepare the single-search Google Product API request for a product detail. */
    fun prepareCompareStores(product: Product) {
        val current = _uiState.value
        if (current.isRefreshingPrices || current.isOnlineSearching ||
            current.isPreparingSerpApiAction || current.isExecutingSerpApiAction ||
            current.isCheckingSerpApiAccount
        ) return
        val googleProductId = product.googleProductId?.takeIf { it.isNotBlank() }
            ?: extractGoogleShoppingProductId(product.storeUrl)
            ?: product.offers.firstNotNullOfOrNull { extractGoogleShoppingProductId(it.url) }
        if (googleProductId.isNullOrBlank()) {
            val message = "Esta ficha todavía no tiene un ID de catálogo de Google Shopping. Repite la búsqueda online del producto para obtenerlo; no se ha gastado cuota."
            _uiState.value = current.copy(serpApiActionStatus = message)
            showSnackbar(message)
            return
        }
        val lastAttempt = priceRefreshPreferences.getLong("compare_stores_attempt_${product.id}", 0L)
        if (lastAttempt > 0L && System.currentTimeMillis() - lastAttempt < TRACKED_REFRESH_COOLDOWN_MS) {
            val message = "Las tiendas de este producto se consultaron hace menos de 24 horas. No se ha repetido la búsqueda ni gastado cuota."
            _uiState.value = current.copy(serpApiActionStatus = message)
            showSnackbar(message)
            return
        }
        pendingTrackedRefreshProducts = emptyList()
        pendingCompareStoresProduct = product.copy(googleProductId = googleProductId)
        _uiState.value = current.copy(
            isPreparingSerpApiAction = true,
            serpApiActionConfirmation = null,
            serpApiActionStatus = "Comprobando saldo de SerpApi…"
        )
        viewModelScope.launch {
            try {
                val token = serpApiTokenStore.read()
                if (token.isNullOrBlank()) {
                    val message = serpApiTokenStore.lastReadErrorMessage()
                        ?: "Guarda una clave de SerpApi en Ajustes antes de comparar tiendas."
                    _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
                    showSnackbar(message)
                    return@launch
                }
                val accountInfo = readAccountInfo(token)
                if (accountInfo == null) {
                    val message = "No se pudo comprobar el saldo. No se ha iniciado ninguna búsqueda ni gastado cuota."
                    _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
                    showSnackbar(message)
                    return@launch
                }
                accountStatusBlockMessage(accountInfo)?.let { message ->
                    _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
                    showSnackbar(message)
                    return@launch
                }
                if (accountCapacity(accountInfo) < 1) {
                    val message = quotaBlockMessage(accountInfo)
                    _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
                    showSnackbar(message)
                    return@launch
                }
                _uiState.value = _uiState.value.copy(
                    serpApiActionConfirmation = SerpApiActionConfirmation(
                        type = SerpApiActionType.COMPARE_STORES,
                        title = "Comparar tiendas",
                        searchesToSpend = 1,
                        remainingSearches = accountInfo.searchesRemaining,
                        remainingThisHour = accountInfo.remainingThisHour,
                        message = "Consultar todas las ofertas disponibles para «${product.title}». La petición no se repetirá durante 24 horas, incluso si Google no devuelve coincidencias válidas."
                    ),
                    serpApiActionStatus = null
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SerpApiException) {
                val message = error.message ?: "No se pudo comprobar SerpApi."
                _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
                showSnackbar(message)
            } catch (_: Exception) {
                val message = "No se pudo preparar la comparación de tiendas. No se ha iniciado ninguna búsqueda."
                _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
                showSnackbar(message)
            } finally {
                _uiState.value = _uiState.value.copy(isPreparingSerpApiAction = false)
            }
        }
    }

    fun cancelSerpApiAction() {
        pendingTrackedRefreshProducts = emptyList()
        pendingCompareStoresProduct = null
        _uiState.value = _uiState.value.copy(
            serpApiActionConfirmation = null,
            serpApiActionStatus = "Acción cancelada; no se ha gastado cuota."
        )
    }

    fun confirmSerpApiAction() {
        val confirmation = _uiState.value.serpApiActionConfirmation ?: return
        if (_uiState.value.isExecutingSerpApiAction) return
        _uiState.value = _uiState.value.copy(
            serpApiActionConfirmation = null,
            isExecutingSerpApiAction = true,
            activeSerpApiAction = confirmation.type,
            serpApiActionStatus = "Preparando la consulta confirmada…"
        )
        viewModelScope.launch {
            try {
                when (confirmation.type) {
                    SerpApiActionType.UPDATE_TRACKED -> executeTrackedRefresh()
                    SerpApiActionType.COMPARE_STORES -> executeCompareStores()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SerpApiException) {
                val message = error.message ?: "SerpApi no pudo completar la operación."
                _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
                showSnackbar(message)
            } catch (_: Exception) {
                val message = "No se completó la operación. Revisa tu conexión y el estado de SerpApi."
                _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
                showSnackbar(message)
            } finally {
                pendingTrackedRefreshProducts = emptyList()
                pendingCompareStoresProduct = null
                _uiState.value = _uiState.value.copy(
                    isExecutingSerpApiAction = false,
                    activeSerpApiAction = null
                )
            }
        }
    }

    private suspend fun executeTrackedRefresh() {
        val requestedTargets = pendingTrackedRefreshProducts
        if (requestedTargets.isEmpty()) {
            _uiState.value = _uiState.value.copy(serpApiActionStatus = "No quedan productos pendientes. No se ha gastado cuota.")
            return
        }
        val token = serpApiTokenStore.read()
        if (token.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(
                serpApiActionStatus = serpApiTokenStore.lastReadErrorMessage()
                    ?: "Guarda una clave SerpApi antes de actualizar guardados."
            )
            return
        }
        val accountInfo = readAccountInfo(token)
        if (accountInfo == null) {
            _uiState.value = _uiState.value.copy(serpApiActionStatus = "No se pudo validar el saldo actual. No se ha iniciado ninguna búsqueda.")
            return
        }
        accountStatusBlockMessage(accountInfo)?.let { message ->
            _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
            return
        }
        val capacity = accountCapacity(accountInfo)
        if (capacity <= 0) {
            _uiState.value = _uiState.value.copy(serpApiActionStatus = quotaBlockMessage(accountInfo))
            return
        }
        val targets = requestedTargets.take(minOf(requestedTargets.size, capacity, MAX_TRACKED_REFRESH_PER_TAP))
        val requests = targets.map { product ->
            SerpApiRequestProduct(id = product.id, title = product.title, brand = product.brand, ean = product.ean)
        }
        val attemptedIds = linkedSetOf<String>()
        val respondedIds = linkedSetOf<String>()
        val batch = serpApiClient.searchProducts(
            token = token,
            products = requests,
            onStage = { stage ->
                _uiState.value = _uiState.value.copy(serpApiActionStatus = stage)
            },
            onQueryStarted = { requested ->
                attemptedIds += requested.id
            },
            onQuerySucceeded = { requested ->
                respondedIds += requested.id
                priceRefreshPreferences.edit()
                    .putLong("tracked_response_${requested.id}", System.currentTimeMillis())
                    .apply()
            }
        )
        val validProductCount = if (batch.quotes.isNotEmpty()) repository.recordSerpApiQuotes(batch.quotes) else 0
        val quoteProductIds = batch.quotes.map { it.productId }.toSet()
        val unmatched = targets.count { it.id in respondedIds && it.id !in quoteProductIds }
        val failed = targets.count { it.id !in respondedIds }
        val unmatchedNote = if (unmatched > 0) {
            " SerpApi respondió, pero no encontró una oferta segura para $unmatched producto(s); se conservan sus precios e historial."
        } else {
            ""
        }
        val failedNote = if (failed > 0) {
            " No hubo respuesta válida de SerpApi para $failed producto(s); esos productos pueden reintentarse sin esperar 24 h."
        } else {
            ""
        }
        val after = runCatching { serpApiClient.getAccountInfo(token) }.getOrNull()
        if (after != null) _uiState.value = _uiState.value.copy(serpApiAccountStatus = quotaMessage(after))
        val quotaNote = after?.let { " ${it.quotaSummary()}" }.orEmpty()
        val message = "Actualizar guardados terminado: se encontraron ofertas válidas para $validProductCount de ${attemptedIds.size} producto(s); búsquedas lanzadas: ${attemptedIds.size}.$unmatchedNote$failedNote$quotaNote"
        _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
        showSnackbar(message)
    }

    private suspend fun executeCompareStores() {
        val product = pendingCompareStoresProduct ?: return
        val productId = product.googleProductId?.takeIf { it.isNotBlank() }
            ?: extractGoogleShoppingProductId(product.storeUrl)
            ?: product.offers.firstNotNullOfOrNull { extractGoogleShoppingProductId(it.url) }
        if (productId.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(
                serpApiActionStatus = "No se ha podido identificar el producto de Google Shopping. No se ha gastado cuota ni modificado el historial."
            )
            return
        }
        val token = serpApiTokenStore.read()
        if (token.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(
                serpApiActionStatus = serpApiTokenStore.lastReadErrorMessage()
                    ?: "Guarda una clave SerpApi antes de comparar tiendas."
            )
            return
        }
        val accountInfo = readAccountInfo(token)
        if (accountInfo == null) {
            _uiState.value = _uiState.value.copy(serpApiActionStatus = "No se pudo validar el saldo. No se ha lanzado ninguna búsqueda.")
            return
        }
        accountStatusBlockMessage(accountInfo)?.let { message ->
            _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
            return
        }
        if (accountCapacity(accountInfo) < 1) {
            _uiState.value = _uiState.value.copy(serpApiActionStatus = quotaBlockMessage(accountInfo))
            return
        }
        val now = System.currentTimeMillis()
        val lastAttempt = priceRefreshPreferences.getLong("compare_stores_attempt_${product.id}", 0L)
        if (lastAttempt > 0L && now - lastAttempt < TRACKED_REFRESH_COOLDOWN_MS) {
            _uiState.value = _uiState.value.copy(
                serpApiActionStatus = "Esta ficha ya se comparó durante las últimas 24 horas; no se ha repetido la consulta."
            )
            return
        }
        priceRefreshPreferences.edit()
            .putLong("compare_stores_attempt_${product.id}", now)
            .commit()
        _uiState.value = _uiState.value.copy(serpApiActionStatus = "Consultando las tiendas de ${product.title}…")
        val details = serpApiClient.searchGoogleProductDetails(
            token = token,
            productId = productId,
            expectedTitle = product.title,
            observedAt = now
        )
        val safeOffers = repository.filterProductComparisonOffers(details.offers, product.title)
        val savedHistory = repository.recordGooglePriceInsightsHistory(
            productId = product.id,
            expectedTitle = product.title,
            googleCatalogId = productId,
            insights = details.priceInsights
        )
        val savedOffers = if (safeOffers.isNotEmpty()) {
            repository.recordProductComparisonOffers(product.id, product.title, safeOffers)
        } else 0

        // Refresh the open detail sheet whenever current offers or Google historical points
        // exist. Historical source rows remain separate and never replace the current live price.
        if (details.priceInsights.isNotEmpty() || safeOffers.any { !it.isSuspiciousPrice }) {
            val updated = runCatching {
                withTimeout(8_000L) {
                    repository.allProductsStream.first { products ->
                        products.any { candidate ->
                            candidate.id == product.id && (
                                (safeOffers.any { !it.isSuspiciousPrice } &&
                                    (candidate.priceObservedAt ?: 0L) >= now) ||
                                (details.priceInsights.isNotEmpty() &&
                                    candidate.priceHistory.any {
                                        it.eventLabel?.contains("price_insights", ignoreCase = true) == true
                                    })
                            )
                        }
                    }.first { it.id == product.id }
                }
            }.getOrNull()
            if (updated != null) {
                _uiState.value = _uiState.value.copy(selectedProduct = updated)
            }
        }

        val offerNote = if (safeOffers.isNotEmpty()) {
            "${safeOffers.size} oferta(s) actuales válidas; $savedOffers observación(es) nuevas o actualizadas."
        } else {
            "Google no devolvió ofertas actuales utilizables."
        }
        val historyNote = when {
            savedHistory > 0 -> "Se han guardado $savedHistory puntos históricos reales devueltos por Google."
            details.priceInsights.isNotEmpty() -> "Google devolvió historial, pero esos puntos ya estaban guardados o no superaron la validación."
            details.priceHistoryAvailable -> "Google indica que hay una serie histórica, pero no se pudieron interpretar puntos fechados en euros."
            else -> "Google no publica una serie histórica para este producto."
        }
        val message = "Comparación completada: $offerNote $historyNote No se repetirá antes de 24 horas."
        _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
        showSnackbar(message)
        val after = runCatching { serpApiClient.getAccountInfo(token) }.getOrNull()
        if (after != null) _uiState.value = _uiState.value.copy(serpApiAccountStatus = quotaMessage(after))
    }

    fun onOnlineSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(
            onlineSearchQuery = query,
            onlineSearchResults = emptyList(),
            onlineSearchDiscardedOffers = emptyList(),
            showOnlineSearchDiscarded = false,
            onlineSearchStatus = null
        )
    }

    fun onOnlineSearchAmazonOnlyChange(onlyAmazon: Boolean) {
        _uiState.value = _uiState.value.copy(onlineSearchAmazonOnly = onlyAmazon)
    }

    fun onToggleOnlineSearchDiscarded() {
        _uiState.value = _uiState.value.copy(
            showOnlineSearchDiscarded = !_uiState.value.showOnlineSearchDiscarded
        )
    }

    fun onOnlineSearchShowSecondHandChange(show: Boolean) {
        _uiState.value = _uiState.value.copy(onlineSearchShowSecondHand = show)
    }

    /** Open the Room-backed detail sheet for a grouped online product card. */
    fun onOnlineProductSelected(identityKey: String) {
        if (identityKey.isBlank()) return
        viewModelScope.launch {
            try {
                val product = withTimeout(10_000L) {
                    repository.awaitProductForOnlineIdentity(identityKey)
                }
                _uiState.value = _uiState.value.copy(selectedProduct = product)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showSnackbar("No se ha podido abrir la ficha guardada. Repite la búsqueda para sincronizar el producto.")
            }
        }
    }

    fun searchOnlineShopping() = startOnlineSearch(OnlineSearchProvider.GOOGLE_SHOPPING)

    fun searchAmazonShopping() = startOnlineSearch(OnlineSearchProvider.AMAZON)

    /**
     * Search public Internet Archive captures only. This path never reads the SerpApi key,
     * checks quota, or spends a provider search credit.
     */
    fun searchFreeHistoricalPrices(product: Product) {
        val current = _uiState.value
        if (current.isSearchingFreeHistory) return

        // Archived snapshots are independent evidence; a product URL is sufficient to look for
        // them. Do not require or fabricate a current price to perform this free lookup.
        val offerCandidates = product.offers.mapNotNull { offer ->
            val destination = offer.url.ifBlank {
                if (offer.store.id == product.currentStore.id) product.storeUrl
                else offer.store.officialUrl
            }
            buildFreeArchiveCandidate(product.title, offer.store.name, destination)
        }
        val currentDestination = product.storeUrl.ifBlank { product.currentStore.officialUrl }
        val currentProductCandidate = buildFreeArchiveCandidate(
            product.title,
            product.currentStore.name,
            currentDestination
        )
        // The exact product URL is the strongest archive target. Put it first so the
        // bounded candidate limit cannot discard it behind cheaper cross-store offers.
        val candidates = prioritizeFreeArchiveCandidates(
            currentProductCandidate = currentProductCandidate,
            offerCandidates = offerCandidates,
            maxCandidates = MAX_ARCHIVE_PAGES_PER_SEARCH
        )

        if (candidates.isEmpty()) {
            _uiState.value = current.copy(
                isSearchingFreeHistory = false,
                freeHistoryStatus = "No hay una URL directa o una tienda identificable para buscar capturas gratuitas. No se ha gastado cuota."
            )
            return
        }

        _uiState.value = current.copy(
            isSearchingFreeHistory = true,
            freeHistoryStatus = "Buscando páginas históricas gratuitas; esta consulta no consume cuota de SerpApi.",
            freeHistoryProductId = product.id
        )
        startFreeArchiveBackfillCandidates(
            candidates = candidates,
            query = product.title,
            provider = current.onlineSearchProvider,
            targetProductId = product.id
        )
    }

    /**
     * Explicitly refresh the current provider/query. Unlike a normal search, this bypasses
     * the 24-hour snapshot and may consume one provider search credit.
     */
    fun refreshOnlineSearch() {
        val current = _uiState.value
        if (current.isOnlineSearching || current.isRefreshingPrices) return
        val query = current.onlineSearchQuery.trim()
        if (query.length < 2) {
            val message = "Escribe al menos 2 caracteres para actualizar la búsqueda."
            _uiState.value = current.copy(onlineSearchStatus = message)
            showSnackbar(message)
            return
        }
        onlineSearchCache.invalidate(current.onlineSearchProvider.name, query)
        startOnlineSearch(current.onlineSearchProvider)
    }

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
            onlineSearchDiscardedOffers = emptyList(),
            showOnlineSearchDiscarded = false,
            onlineSearchProvider = provider,
            onlineSearchAmazonOnly = provider == OnlineSearchProvider.AMAZON
        )
        viewModelScope.launch {
            try {
                // Cache lookup comes first: saved results remain usable even if the quota is empty
                // or the API key was removed after the previous search.
                val cached = withContext(Dispatchers.IO) {
                    onlineSearchCache.read(provider.name, query)
                }
                if (cached != null && (cached.rawResponse != null || cached.results.isNotEmpty())) {
                    // Parsing stored JSON and classifying offers can be CPU-heavy. Keep it off
                    // Android's main thread so the search screen continues drawing smoothly.
                    val cachedReport = withContext(Dispatchers.Default) {
                        if (cached.rawResponse != null) {
                            val cachedRoot = org.json.JSONObject(cached.rawResponse)
                            if (provider == OnlineSearchProvider.GOOGLE_SHOPPING) {
                                serpApiClient.parseShoppingResultsDetailed(cachedRoot, query, cached.savedAt)
                            } else {
                                serpApiClient.parseAmazonResultsDetailed(cachedRoot, query, cached.savedAt)
                            }
                        } else {
                            com.example.data.remote.SerpApiShoppingSearchReport(
                                results = cached.results,
                                rawResultCount = cached.results.size,
                                rejectedAsIrrelevant = 0,
                                rejectedForPriceOrCurrency = 0,
                                rejectedForLink = 0,
                                rejectedOther = 0,
                                providerName = if (provider == OnlineSearchProvider.AMAZON) "Amazon.es" else "Google Shopping"
                            )
                        }
                    }
                    val cachedResults = cachedReport.results
                    val (safeCachedResults, cachedDiscarded) = withContext(Dispatchers.Default) {
                        val safe = repository.filterOnlineSearchResults(cachedResults, query)
                        val discarded = (cachedReport.discardedOffers +
                            repository.diagnoseOnlineSearchRejections(cachedResults, query))
                            .distinctBy { it.provider + "|" + it.title + "|" + it.priceLabel + "|" + it.reason }
                        safe to discarded
                    }
                    onlineDiscardedCache[onlineSearchCacheKey(provider, query)] = cachedDiscarded
                    val ageMinutes = ((System.currentTimeMillis() - cached.savedAt) / 60_000L).coerceAtLeast(1L)
                    val base = if (safeCachedResults.isEmpty()) {
                        cachedReport.emptyResultMessage(query)
                    } else {
                        onlineSearchResultMessage(safeCachedResults, provider)
                    }
                    val cacheNotice = "Resultados guardados hace $ageMinutes min; no se ha consumido otra búsqueda."
                    val savedObservations = if (safeCachedResults.isNotEmpty()) {
                        withContext(Dispatchers.Default) {
                            repository.recordOnlineSearchResults(safeCachedResults, provider.name, query)
                        }
                    } else 0
                    val historyNotice = if (safeCachedResults.isNotEmpty()) {
                        onlineHistoryNotice(savedObservations, safeCachedResults.size)
                    } else ""
                    val filterNotice = onlineFilterNotice(cachedDiscarded)
                    val status = listOf(base, cacheNotice, filterNotice, historyNotice)
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                    _uiState.value = _uiState.value.copy(
                        onlineSearchResults = safeCachedResults,
                        onlineSearchDiscardedOffers = cachedDiscarded,
                        showOnlineSearchDiscarded = false,
                        onlineSearchStatus = status
                    )
                    startFreeArchiveBackfill(safeCachedResults, query, provider)
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

                val providerReport = if (provider == OnlineSearchProvider.GOOGLE_SHOPPING) {
                    serpApiClient.searchOnlineWithDiagnostics(token, query)
                } else {
                    serpApiClient.searchAmazonOnlineWithDiagnostics(token, query)
                }
                // The offer safety checks use many title/model rules. Run both passes on a
                // background dispatcher rather than blocking Compose while results arrive.
                val (results, discarded) = withContext(Dispatchers.Default) {
                    val rawResults = providerReport.results
                    val repositoryDiscarded = repository.diagnoseOnlineSearchRejections(rawResults, query)
                    // Use the same plausibility rules for visible cards, cache entries and Room history.
                    val accepted = repository.filterOnlineSearchResults(rawResults, query)
                    val rejected = (providerReport.discardedOffers + repositoryDiscarded)
                        .distinctBy { "${it.provider}|${it.title}|${it.priceLabel}|${it.reason}" }
                    accepted to rejected
                }
                onlineDiscardedCache[onlineSearchCacheKey(provider, query)] = discarded
                val savedObservations = if (results.isNotEmpty()) {
                    withContext(Dispatchers.Default) {
                        repository.recordOnlineSearchResults(results, provider.name, query)
                    }
                } else 0
                // Cache the raw provider response for 24 hours so future filters can reclassify it.
                providerReport.rawResponse?.let { rawResponse ->
                    withContext(Dispatchers.IO) {
                        onlineSearchCache.writeRaw(provider.name, query, rawResponse)
                    }
                }
                val base = if (results.isEmpty() && providerReport.rawResultCount > 0) {
                    providerReport.emptyResultMessage(query)
                } else {
                    onlineSearchResultMessage(results, provider)
                }
                // The balance was checked before the query. Skip a second post-search round trip
                // so results settle sooner; state clearly that this displayed balance is pre-query.
                val quotaNote = "Saldo comprobado antes de buscar: ${accountInfo.quotaSummary()} Esta consulta puede haber consumido una búsqueda."
                val filterNote = onlineFilterNotice(discarded)
                val historyNote = if (results.isNotEmpty()) {
                    onlineHistoryNotice(savedObservations, results.size)
                } else ""
                val message = listOf(base, quotaNote, filterNote, historyNote)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                _uiState.value = _uiState.value.copy(
                    onlineSearchResults = results,
                    onlineSearchDiscardedOffers = discarded,
                    showOnlineSearchDiscarded = false,
                    onlineSearchStatus = message
                )
                startFreeArchiveBackfill(results, query, provider)
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

    /**
     * Free Wayback lookup runs after live search results are displayed. The current offer price is
     * used only to prioritize ordinary search candidates; archived quotes are persisted separately.
     */
    internal data class FreeArchiveCandidate(
        val title: String,
        val storeName: String,
        val directUrl: String?,
        val merchantDomain: String?
    )

    private fun buildFreeArchiveCandidate(
        title: String,
        storeName: String,
        sourceUrl: String
    ): FreeArchiveCandidate? {
        if (title.isBlank()) return null
        val directUrl = sourceUrl.takeIf {
            !isGoogleShoppingUrl(it) && waybackPriceHistoryClient.isEligibleDirectUrl(it)
        }
        val resolvedDomain = waybackPriceHistoryClient.resolveMerchantDomain(
            storeName = storeName,
            sourceUrl = sourceUrl
        )
        // Some Google Shopping rows expose only Google's intermediate URL and label the source
        // as "Google Shopping", omitting the seller. If that happens, make a bounded fallback
        // to a recognized brand's own website; the archived page still needs an exact title and
        // EUR price, so this does not invent a quote or pretend to know the original seller.
        val brandFallback = if (resolvedDomain == null) {
            waybackPriceHistoryClient.resolveBrandMerchant(title)
        } else null
        val domain = resolvedDomain ?: brandFallback?.second
        val genericProviderName = storeName.trim().lowercase(Locale.ROOT) in setOf(
            "", "google", "google shopping", "google shopping españa", "serpapi", "serp api", "shopping"
        )
        val effectiveStoreName = when {
            brandFallback != null -> brandFallback.first
            genericProviderName && directUrl != null -> ""
            else -> storeName
        }
        if (directUrl == null && domain == null) return null
        return FreeArchiveCandidate(
            title = title,
            storeName = effectiveStoreName,
            directUrl = directUrl,
            merchantDomain = domain
        )
    }

    private fun startFreeArchiveBackfill(
        results: List<SerpApiShoppingResult>,
        query: String,
        provider: OnlineSearchProvider,
        targetProductId: String? = null
    ) {
        val candidates = results
            .filterNot { it.isSecondHand || it.isRentalOrMonthly || it.isFitmentUnverified || it.isCategoryUnverified }
            .filter { it.title.isNotBlank() && it.price.isFinite() && it.price > 0.0 }
            .sortedBy { it.price }
            .mapNotNull { result ->
                buildFreeArchiveCandidate(
                    title = result.title,
                    storeName = result.storeName,
                    sourceUrl = result.sourceUrl
                )
            }
            // One candidate per merchant domain prevents spending the small archive-search
            // budget repeatedly on multiple URL variants from the same retailer.
            .distinctBy {
                it.title.trim().lowercase(Locale.ROOT) + "|" + (it.merchantDomain ?: it.directUrl ?: "unknown")
            }
            .take(MAX_ARCHIVE_PAGES_PER_SEARCH)

        startFreeArchiveBackfillCandidates(candidates, query, provider, targetProductId)
    }

    private fun startFreeArchiveBackfillCandidates(
        candidates: List<FreeArchiveCandidate>,
        query: String,
        provider: OnlineSearchProvider,
        targetProductId: String? = null
    ) {
        if (candidates.isEmpty()) {
            appendArchiveNotice(
                query,
                provider,
                "No hay un enlace directo ni un dominio de comercio identificable para buscar capturas gratuitas. No se han inventado precios previos.",
                targetProductId
            )
            return
        }

        viewModelScope.launch {
            try {
                var savedCount = 0
                var checkedCount = 0
                var foundCount = 0
                var skippedCount = 0
                var timedOutCount = 0
                val archiveDates = mutableListOf<Long>()
                for (candidate in candidates) {
                    // Bound each candidate's direct + merchant-domain lookup so slow public
                    // archive responses cannot leave the detail sheet spinning indefinitely.
                    val attempts = withTimeoutOrNull(MAX_ARCHIVE_LOOKUP_PER_CANDIDATE_MS) {
                        val results = mutableListOf<com.example.data.remote.WaybackBackfillResult>()
                        if (candidate.directUrl != null) {
                            results += waybackPriceHistoryClient.fetchHistory(
                                originalUrl = candidate.directUrl,
                                expectedTitle = candidate.title
                            )
                        }
                        // If the exact URL has no usable old prices (or only a Google Shopping link was
                        // provided), try a bounded, product-term-filtered lookup on the known merchant.
                        if (candidate.merchantDomain != null && results.none { it.quotes.isNotEmpty() }) {
                            results += waybackPriceHistoryClient.fetchHistoryByMerchantDomain(
                                domain = candidate.merchantDomain,
                                expectedTitle = candidate.title
                            )
                        }
                        results
                    }
                    if (attempts == null) {
                        timedOutCount++
                        continue
                    }

                    if (attempts.isNotEmpty() && attempts.all { it.skippedRecently }) {
                        skippedCount++
                        continue
                    }
                    val quotes = attempts.flatMap { it.quotes }
                        .distinctBy { Triple(it.originalUrl, it.observedAt, it.price) }
                    checkedCount += attempts.sumOf { it.capturesChecked }
                    foundCount += quotes.size
                    quotes.map { it.observedAt }.let(archiveDates::addAll)

                    for ((archiveUrl, urlQuotes) in quotes.groupBy { it.originalUrl }) {
                        val newlySaved = repository.recordArchivedPriceHistory(
                            expectedTitle = candidate.title,
                            originalUrl = archiveUrl,
                            storeName = candidate.storeName,
                            quotes = urlQuotes
                        )
                        savedCount += newlySaved
                    }

                    if (quotes.isNotEmpty()) {
                        val targetIdentity = onlineProductIdentityKey(candidate.title)
                        val refreshedProduct = runCatching {
                            withTimeout(10_000L) {
                                repository.allProductsStream.first { products ->
                                    products.any { product ->
                                        (targetProductId?.let { product.id == it }
                                            ?: (onlineProductIdentityKey(product.title) == targetIdentity)) &&
                                            product.priceHistory.any {
                                                it.eventLabel.equals("Internet Archive", ignoreCase = true)
                                            }
                                    }
                                }.first { product ->
                                    (targetProductId?.let { product.id == it }
                                        ?: (onlineProductIdentityKey(product.title) == targetIdentity)) &&
                                        product.priceHistory.any {
                                            it.eventLabel.equals("Internet Archive", ignoreCase = true)
                                        }
                                }
                            }
                        }.getOrNull()
                        if (refreshedProduct != null) {
                            val currentState = _uiState.value
                            if (currentState.selectedProduct?.id == refreshedProduct.id) {
                                _uiState.value = currentState.copy(selectedProduct = refreshedProduct)
                            }
                        }
                    }
                }

                val notice = when {
                    skippedCount == candidates.size ->
                        "Estas consultas gratuitas ya se comprobaron recientemente. La caché evita repetirlas mientras siga vigente, según si había precios útiles o si la consulta falló."
                    savedCount > 0 -> {
                        val oldest = archiveDates.minOrNull()?.let { timestamp ->
                            SimpleDateFormat("dd/MM/yyyy", Locale("es", "ES")).format(Date(timestamp))
                        }
                        "Histórico gratuito recuperado de Internet Archive: $savedCount precios antiguos guardados" +
                            (oldest?.let { ", con capturas desde $it" } ?: "") +
                            ". Son precios anunciados en páginas archivadas; puede haber huecos y no garantizan envío, stock ni precio final."
                    }
                    foundCount > 0 ->
                        "Se encontraron capturas con precios previos, pero no se pudieron vincular a la ficha del producto o ya existían esos registros diarios."
                    checkedCount > 0 ->
                        "Se consultaron $checkedCount capturas gratuitas, pero ninguna expuso un precio EUR verificable para el producto exacto. No se han rellenado datos ausentes."
                    timedOutCount > 0 ->
                        "La consulta del archivo agotó el límite de tiempo para $timedOutCount tienda(s). No se ha gastado cuota de SerpApi; puedes volver a intentarlo más tarde."
                    else ->
                        "No se recuperó histórico gratuito: puede que el comercio no esté archivado o que no publique el precio en metadatos verificables. No se ha gastado cuota de SerpApi."
                }
                appendArchiveNotice(query, provider, notice, targetProductId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                appendArchiveNotice(
                    query,
                    provider,
                    "No se pudo completar la consulta gratuita del archivo. No se ha gastado cuota de SerpApi; puedes volver a intentarlo más adelante.",
                    targetProductId
                )
            } finally {
                if (targetProductId != null) {
                    _uiState.value = _uiState.value.copy(isSearchingFreeHistory = false)
                }
            }
        }
    }

    private fun appendArchiveNotice(
        query: String,
        provider: OnlineSearchProvider,
        notice: String,
        targetProductId: String? = null
    ) {
        val current = _uiState.value
        if (targetProductId != null) {
            _uiState.value = current.copy(
                isSearchingFreeHistory = false,
                freeHistoryStatus = notice.trim(),
                freeHistoryProductId = targetProductId
            )
            return
        }
        if (current.onlineSearchQuery.trim().equals(query, ignoreCase = true) &&
            current.onlineSearchProvider == provider &&
            current.onlineSearchStatus?.contains("Histórico gratuito recuperado de Internet Archive") != true
        ) {
            _uiState.value = current.copy(
                onlineSearchStatus = listOf(current.onlineSearchStatus.orEmpty().trim(), notice.trim())
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
            )
        }
    }

    private fun onlineFilterNotice(discarded: List<RejectedOnlineOffer>): String {
        if (discarded.isEmpty()) return ""
        val reasons = discarded.groupingBy { it.reason }.eachCount()
            .entries.joinToString("; ") { "${it.value} por ${it.key.lowercase(Locale.ROOT)}" }
        return "Se han descartado ${discarded.size} resultados: $reasons. Pulsa «Ver descartados» para revisar cada oferta."
    }

    private fun onlineHistoryNotice(saved: Int, resultCount: Int): String =
        if (saved > 0) {
            "Precios de esta búsqueda: $saved observaciones nuevas guardadas en Mercado. Son precios observados ahora, no un historial anterior a la búsqueda. Para consultar precios anteriores, abre la ficha y usa «Comparar tiendas»; solo se mostrarán si la fuente devuelve fechas verificables."
        } else {
            "Precios de esta búsqueda: no se añadieron observaciones nuevas porque ya estaban registradas hoy o no pasaron los filtros ($resultCount resultados revisados). El historial anterior se muestra por separado cuando existen fechas verificables."
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
                "Producto guardado. Fija un precio objetivo para activar los avisos automáticos."
            }
            showSnackbar(msg)
        }
    }

    fun saveTargetAlertPrice(product: Product, targetPrice: Double?) {
        viewModelScope.launch {
            repository.updateAlertTarget(product.id, targetPrice)
            _uiState.value = _uiState.value.copy(isSettingAlert = null)
            runCatching { PriceAlertScheduler.ensureScheduled(getApplication()) }
            val targetStr = if (targetPrice != null) "${String.format("%.2f", targetPrice)} €" else "sin objetivo"
            showSnackbar(
                if (targetPrice != null) "Objetivo guardado: $targetStr. Mínimo revisará precios automáticamente a diario; hasta 3 productos por día para cuidar tu cuota."
                else "Seguimiento guardado sin objetivo de precio. Edita el aviso para indicar a qué precio quieres recibir la notificación."
            )
        }
    }

    fun checkPriceAlertsNow() {
        try {
            val context = getApplication<Application>()
            PriceAlertScheduler.ensureScheduled(context)
            PriceAlertScheduler.enqueueImmediate(context)
            showSnackbar("Comprobación solicitada. Puede consumir hasta 3 búsquedas de SerpApi; Android puede retrasar la ejecución.")
        } catch (_: Exception) {
            showSnackbar("No se pudo programar la revisión de precios. Vuelve a abrir Mínimo e inténtalo de nuevo.")
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

    fun clearSnackbarMessage() {
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }

    private fun showSnackbar(msg: String) {
        _uiState.value = _uiState.value.copy(snackbarMessage = msg)
    }
}


/**
 * Prioritize the product's own URL before merchant offers when the archive lookup is bounded.
 * Exact URL deduplication preserves the first candidate, so the strongest source remains first.
 */
internal fun prioritizeFreeArchiveCandidates(
    currentProductCandidate: PriceTrackerViewModel.FreeArchiveCandidate?,
    offerCandidates: List<PriceTrackerViewModel.FreeArchiveCandidate>,
    maxCandidates: Int
): List<PriceTrackerViewModel.FreeArchiveCandidate> {
    if (maxCandidates <= 0) return emptyList()
    // A homepage fallback is weaker than an actual offer product page. Only prioritize the
    // product candidate when it contains an eligible direct URL; otherwise keep offers first.
    val orderedCandidates = if (currentProductCandidate?.directUrl != null) {
        listOf(currentProductCandidate) + offerCandidates
    } else {
        offerCandidates + listOfNotNull(currentProductCandidate)
    }
    return orderedCandidates
        .distinctBy {
            it.title.trim().lowercase(Locale.ROOT) + "|" + (it.directUrl ?: it.merchantDomain ?: "unknown")
        }
        .take(maxCandidates)
}
