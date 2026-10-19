package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.remote.OnlineSearchCache
import com.example.data.remote.OnlineSearchQuotaGuard
import com.example.data.remote.WaybackPriceHistoryClient
import com.example.data.remote.diagnosticIndexForWaybackTrace
import com.example.data.remote.summarizeCdxStep5Events
import com.example.data.remote.WaybackTraceEvent
import com.example.data.remote.WaybackTraceStatus
import com.example.data.remote.isGoogleShoppingUrl
import com.example.data.remote.extractDirectRetailerUrl
import com.example.data.remote.onlineProductIdentityKey
import com.example.data.remote.isGameSpainOffer
import com.example.data.remote.mergeGoogleShoppingWithGameSupplement
import com.example.data.remote.shouldSearchGameSupplement
import com.example.data.remote.RejectedOnlineOffer
import com.example.data.remote.SerpApiAccountInfo
import com.example.data.remote.SerpApiClient
import com.example.data.remote.SerpApiException
import com.example.data.remote.SerpApiRequestProduct
import com.example.data.remote.SerpApiShoppingResult
import com.example.data.remote.SerpApiTokenStore
import com.example.data.remote.extractGoogleShoppingProductId
import com.example.data.model.Product
import com.example.data.model.PriceBadgeType
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date

enum class OnlineSearchProvider { GOOGLE_SHOPPING, AMAZON }

/**
 * Refine only two known ambiguous catalogue searches. This remains one provider request and keeps
 * the original text as the relevance key so the broader wording does not over-filter result titles.
 */
internal fun optimizedOnlineShoppingQuery(query: String, provider: OnlineSearchProvider): String {
    if (provider != OnlineSearchProvider.GOOGLE_SHOPPING) return query
    val normalized = Normalizer.normalize(query.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
    return when (normalized) {
        "shimano" -> "Shimano bicycle components cycling"
        "anker prime" -> "Anker Prime power bank"
        else -> query
    }
}

private fun onlineSearchQueryOptimizationNotice(query: String, provider: OnlineSearchProvider): String =
    when (optimizedOnlineShoppingQuery(query, provider)) {
        "Shimano bicycle components cycling" ->
            "Búsqueda afinada a componentes de ciclismo en una sola consulta; no añade gasto de cuota."
        "Anker Prime power bank" ->
            "Búsqueda afinada a baterías Anker Prime en una sola consulta; no añade gasto de cuota."
        else -> ""
    }

enum class SerpApiActionType { UPDATE_TRACKED, COMPARE_STORES }

data class WaybackDiagnosticEntry(
    val step: Int,
    val title: String,
    val status: WaybackTraceStatus = WaybackTraceStatus.PENDING,
    val detail: String = "",
    val durationMs: Long? = null
)

private val WAYBACK_DIAGNOSTIC_TITLES = listOf(
    "Solicitud iniciada (botón pulsado)",
    "Ficha local y URL principal recuperadas",
    "URL normalizada y destinos candidatos preparados",
    "Precontrol de pausa global y preparación de consulta",
    "Petición enviada al índice CDX",
    "Respuesta CDX o recuperación alternativa",
    "Fallback: CDX alternativo / Availability API",
    "Extracción de capturas CDX (requiere respuesta válida)",
    "Normalización y deduplicación de URL candidatas",
    "Validación de fechas y período histórico",
    "Descarga del HTML de la captura 1",
    "Evaluación de barreras del HTML 1",
    "Descarga del HTML de la captura 2",
    "Evaluación de barreras del HTML 2",
    "Verificación del título e identidad del producto",
    "Extracción del precio",
    "Validación del importe y moneda EUR",
    "Consolidación de precios y eliminación de duplicados",
    "Guardado del histórico en la base de datos local",
    "Actualización de la ficha/gráfica y resultado final"
)

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
    val onlyHistoricalMinimumFilter: Boolean = false,
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
    val waybackDiagnosticProductId: String? = null,
    val waybackDiagnosticSteps: List<WaybackDiagnosticEntry> = emptyList(),
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
        // The CDX domain endpoint is frequently unavailable or rate-limited. Keep the exact
        // page lookup first, but reserve only a short, separate budget for the broad fallback.
        const val MAX_ARCHIVE_LOOKUP_PER_CANDIDATE_MS = 16_000L
        // Availability probes and archived HTML downloads have separate bounded network timeouts.
        // Reserve enough time for two captures plus request pacing; this affects only manual history.
        const val MAX_ARCHIVE_DOMAIN_FALLBACK_MS = 28_000L
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
    private val onlineSearchQuotaGuard = OnlineSearchQuotaGuard(application)
    private val waybackPriceHistoryClient = WaybackPriceHistoryClient(application)
    private val onlineDiscardedCache = mutableMapOf<String, List<RejectedOnlineOffer>>()
    private var lastDisplayedSearchIdentity: String? = null

    private fun onlineSearchIdentity(provider: OnlineSearchProvider, query: String): String =
        provider.name + "|" + query.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun quotaCooldownMessage(remainingMs: Long): String {
        val minutes = ((remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
        return "Para proteger la cuota, Mínimo no repetirá esta búsqueda de pago durante unos $minutes min. Se mantienen los resultados ya disponibles."
    }

    private fun searchCacheProviderKey(provider: OnlineSearchProvider): String =
        if (provider == OnlineSearchProvider.GOOGLE_SHOPPING) {
            // Do not reuse pre-update general-only snapshots that contain no GAME supplement.
            "GOOGLE_SHOPPING_UNIFIED_GAME_V1"
        } else provider.name

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

    /**
     * Publish short UI feedback messages for actions started by the user.
     * StateFlow.update keeps concurrent state changes from overwriting each other.
     */
    private fun showSnackbar(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    /** Clear a snackbar after the UI has displayed it. */
    fun clearSnackbarMessage() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun waybackRateLimitNotice(now: Long = System.currentTimeMillis()): String {
        val until = waybackPriceHistoryClient.activeRateLimitUntil(now)
            ?: return "Internet Archive ha limitado temporalmente las consultas (HTTP 429). Espera unos minutos antes de volver a intentarlo. No se ha gastado cuota de SerpApi."
        val remainingMinutes = ((until - now + 59_999L) / 60_000L).coerceAtLeast(1L)
        val retryAt = SimpleDateFormat("HH:mm", Locale("es", "ES")).format(Date(until))
        return "Internet Archive ha pedido una pausa temporal (HTTP 429). Mínimo no enviará más peticiones hasta aproximadamente las ${retryAt} (en unos ${remainingMinutes} min). Vuelve a pulsar «Buscar histórico gratis» entonces. No se consume cuota de SerpApi."
    }

    private fun startWaybackDiagnostic(product: Product) {
        val initial = WAYBACK_DIAGNOSTIC_TITLES.mapIndexed { index, title ->
            WaybackDiagnosticEntry(step = index + 1, title = title)
        }
        _uiState.value = _uiState.value.copy(
            waybackDiagnosticProductId = product.id,
            waybackDiagnosticSteps = initial
        )
        reportWaybackDiagnostic(
            product.id, 1, WaybackTraceStatus.PASSED,
            "Pulsación recibida por el ViewModel; se comprobarán los límites antes de consultar el archivo."
        )
        val hasSavedUrl = product.storeUrl.isNotBlank() || product.currentStore.officialUrl.isNotBlank()
        reportWaybackDiagnostic(
            product.id, 2,
            if (hasSavedUrl || product.offers.isNotEmpty()) WaybackTraceStatus.PASSED else WaybackTraceStatus.FAILED,
            "Ficha local leída: URL principal ${if (hasSavedUrl) "presente" else "ausente"}; ${product.offers.size} oferta(s) guardada(s). La URL completa no se registra."
        )
        reportWaybackDiagnostic(
            product.id, 3, WaybackTraceStatus.RUNNING,
            "Preparando destinos de tienda y eliminando intermediarios o redirecciones cuando se puede."
        )
        reportWaybackDiagnostic(
            product.id, 4, WaybackTraceStatus.RUNNING,
            "Comprobando configuración, caché y pausa global antes de solicitar datos al archivo."
        )
    }

    private fun reportWaybackDiagnostic(
        productId: String,
        step: Int,
        status: WaybackTraceStatus,
        detail: String,
        durationMs: Long? = null
    ) {
        if (step !in 1..20) return
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        val detailLine = "[$time] " + detail.trim() + (durationMs?.let { " · ${it} ms" } ?: "")
        // Trace callbacks can arrive from Dispatchers.IO while the ViewModel updates UI state.
        // Apply each event atomically so concurrent updates cannot erase earlier diagnostic rows.
        _uiState.update { state ->
            if (state.waybackDiagnosticProductId != productId) return@update state
            val rows = state.waybackDiagnosticSteps.toMutableList()
            val index = rows.indexOfFirst { it.step == step }
            if (index < 0) return@update state
            val old = rows[index]
            val combinedDetail = when {
                detailLine.isBlank() -> old.detail
                old.detail.isBlank() -> detailLine
                old.detail.lines().any { it == detailLine } -> old.detail
                else -> (old.detail.lines() + detailLine).takeLast(5).joinToString("\n")
            }
            rows[index] = old.copy(
                status = status,
                detail = combinedDetail,
                durationMs = durationMs ?: old.durationMs
            )
            state.copy(waybackDiagnosticSteps = rows)
        }
    }

    private fun recordWaybackTrace(productId: String, event: WaybackTraceEvent) {
        reportWaybackDiagnostic(
            productId,
            diagnosticIndexForWaybackTrace(event),
            event.status,
            event.detail,
            event.durationMs
        )
    }

    private fun finishWaybackDiagnostic(
        productId: String,
        finalStatus: WaybackTraceStatus,
        finalDetail: String
    ) {
        val initial = _uiState.value
        if (initial.waybackDiagnosticProductId != productId || initial.waybackDiagnosticSteps.isEmpty()) return
        for (row in initial.waybackDiagnosticSteps) {
            if (row.step == 20) continue
            when (row.status) {
                WaybackTraceStatus.PENDING ->
                    reportWaybackDiagnostic(
                        productId, row.step, WaybackTraceStatus.SKIPPED,
                        "No ejecutado: la cadena terminó antes de esta fase."
                    )
                WaybackTraceStatus.RUNNING ->
                    reportWaybackDiagnostic(
                        productId, row.step, WaybackTraceStatus.FAILED,
                        "La operación terminó sin completar la fase."
                    )
                else -> Unit
            }
        }
        reportWaybackDiagnostic(productId, 20, finalStatus, finalDetail)
    }

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
                    (product.isEanVerified && product.ean.contains(query)) ||
                    product.category.displayName.lowercase().contains(query) ||
                    product.currentStore.name.lowercase().contains(query) ||
                    product.currentStore.domain.lowercase().contains(query)
                }
            }
            .filter { product ->
                // ATH (all-time low) is a distinct filter from the evidence-based historical-minimum badge.
                if (state.onlyAthFilter) product.isAtAth else true
            }
            .filter { product ->
                // Only show products whose current verified quote equals or improves the observed history minimum.
                if (state.onlyHistoricalMinimumFilter) {
                    product.priceBadgeState.type == PriceBadgeType.HISTORICAL_MINIMUM
                } else true
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
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trackedProducts: StateFlow<List<Product>> = repository.trackedProductsStream
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val todayAthDeals: StateFlow<List<Product>> = repository.todayAthDealsStream
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Account checks are free at SerpApi. If the account endpoint is temporarily unavailable,
     * manual single searches can still be attempted and the provider remains the final authority.
     */
    private suspend fun readAccountInfo(token: String): SerpApiAccountInfo? = try {
        // The account endpoint is free, but it must not delay the interface indefinitely.
        withTimeoutOrNull(5_000L) { serpApiClient.getAccountInfo(token) }
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
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                // Timeouts are cancellation exceptions in Kotlin; handle this one before the
                // generic cancellation branch so the user sees a useful status instead of a
                // silently abandoned account check.
                val message = "La consulta de saldo de SerpApi ha tardado demasiado. Inténtalo de nuevo; no se ha iniciado ninguna búsqueda de productos."
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
                        val lastAttempt = priceRefreshPreferences.getLong("tracked_attempt_${product.id}", 0L)
                        lastAttempt <= 0L || now - lastAttempt >= TRACKED_REFRESH_COOLDOWN_MS
                    }
                    .sortedWith(
                        compareBy<Product> { priceRefreshPreferences.getLong("tracked_attempt_${it.id}", 0L) }
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
                        message = if (googleProductId != null) {
                            "Consultar las ofertas y el historial disponibles para «${product.title}» con una búsqueda. La consulta no se repetirá durante 24 horas."
                        } else {
                            "La ficha no tiene ID de catálogo; Mínimo buscará «${product.title}» por nombre con una búsqueda. La consulta no se repetirá durante 24 horas."
                        }
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
            SerpApiRequestProduct(id = product.id, title = product.title, brand = product.brand, ean = product.ean.takeIf { product.isEanVerified }.orEmpty())
        }
        val attemptedIds = linkedSetOf<String>()
        val batch = serpApiClient.searchProducts(
            token = token,
            products = requests,
            onStage = { stage ->
                _uiState.value = _uiState.value.copy(serpApiActionStatus = stage)
            },
            onQueryStarted = { requested ->
                attemptedIds += requested.id
                priceRefreshPreferences.edit()
                    .putLong("tracked_attempt_${requested.id}", System.currentTimeMillis())
                    .commit()
            }
        )
        val validProductCount = if (batch.quotes.isNotEmpty()) repository.recordSerpApiQuotes(batch.quotes) else 0
        val quoteProductIds = batch.quotes.map { it.productId }.toSet()
        val unmatched = targets.filter { it.id !in quoteProductIds }
        val unmatchedNote = if (unmatched.isNotEmpty()) {
            " Sin coincidencia segura para: ${unmatched.joinToString { it.title }}. Sus precios e historial no se han cambiado."
        } else {
            ""
        }
        val partial = if (batch.failedQueries > 0) " Fallaron ${batch.failedQueries} consultas." else ""
        val after = runCatching { serpApiClient.getAccountInfo(token) }.getOrNull()
        if (after != null) _uiState.value = _uiState.value.copy(serpApiAccountStatus = quotaMessage(after))
        val quotaNote = after?.let { " ${it.quotaSummary()}" }.orEmpty()
        val message = "Actualizar guardados terminado: se encontraron ofertas válidas para $validProductCount de ${attemptedIds.size} producto(s); búsquedas lanzadas: ${attemptedIds.size}.$unmatchedNote$partial$quotaNote"
        _uiState.value = _uiState.value.copy(serpApiActionStatus = message)
        showSnackbar(message)
    }

    private suspend fun executeCompareStores() {
        val product = pendingCompareStoresProduct ?: return
        val productId = product.googleProductId?.takeIf { it.isNotBlank() }
            ?: extractGoogleShoppingProductId(product.storeUrl)
            ?: product.offers.firstNotNullOfOrNull { extractGoogleShoppingProductId(it.url) }
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
        _uiState.value = _uiState.value.copy(
            serpApiActionStatus = if (productId != null) {
                "Consultando las tiendas de ${product.title}…"
            } else {
                "Buscando tiendas para ${product.title} por nombre…"
            }
        )
        // Use the catalog endpoint when this saved row has Google's product ID. Older or
        // intermediary Google Shopping links often have no ID; in that case use the same
        // single-search budget to search by the product title instead of sending the user back
        // to the search screen or launching a second paid request.
        val details = if (productId != null) {
            serpApiClient.searchGoogleProductDetails(
                token = token,
                productId = productId,
                expectedTitle = product.title,
                observedAt = now
            )
        } else {
            val report = serpApiClient.searchOnlineWithDiagnostics(token, product.title)
            com.example.data.remote.SerpApiGoogleProductDetails(
                offers = report.results,
                priceInsights = emptyList(),
                priceHistoryAvailable = false
            )
        }
        val safeOffers = repository.filterProductComparisonOffers(details.offers, product.title)
        val savedHistory = if (productId != null && details.priceInsights.isNotEmpty()) {
            repository.recordGooglePriceInsightsHistory(
                productId = product.id,
                expectedTitle = product.title,
                googleCatalogId = productId,
                insights = details.priceInsights
            )
        } else 0
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
        val modeNote = if (productId == null) "Búsqueda por nombre completada. " else ""
        val message = "${modeNote}Comparación completada: $offerNote $historyNote No se repetirá antes de 24 horas."
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
        val previousState = _uiState.value
        if (previousState.isSearchingFreeHistory) return
        startWaybackDiagnostic(product)
        // Re-read after starting the trace; otherwise copying the pre-trace state here would
        // immediately erase the 15 diagnostic rows from the UI.
        val current = _uiState.value

        // A 429 cooldown applies to Internet Archive globally, not just this product. Explain
        // the retry time locally instead of making a doomed request for every other product.
        val rateLimitUntil = waybackPriceHistoryClient.activeRateLimitUntil()
        if (rateLimitUntil != null) {
            val status = waybackRateLimitNotice()
            _uiState.value = _uiState.value.copy(
                isSearchingFreeHistory = false,
                freeHistoryStatus = status,
                freeHistoryProductId = product.id
            )
            reportWaybackDiagnostic(
                product.id, 3, WaybackTraceStatus.SKIPPED,
                "Pausa global de Internet Archive activa; no se envió otra petición."
            )
            reportWaybackDiagnostic(
                product.id, 4, WaybackTraceStatus.SKIPPED,
                "La pausa global bloqueó la petición antes de consultar CDX o Availability."
            )
            finishWaybackDiagnostic(
                product.id, WaybackTraceStatus.SKIPPED,
                "Reintento no enviado durante la pausa global. ${status}"
            )
            return
        }

        // Archived snapshots are independent evidence; use the saved retailer offers first.
        // Some older Google Shopping rows contain a generic Google URL and an incomplete
        // merchant-domain field. Resolve the merchant from the saved store directory before
        // giving up, without making a paid query or changing the stored offer.
        val candidateBuilder = mutableListOf<FreeArchiveCandidate>()
        candidateBuilder.apply {
            product.offers.forEach { offer ->
                val destination = offer.url.ifBlank {
                    if (offer.store.id == product.currentStore.id) product.storeUrl
                    else offer.store.officialUrl
                }
                buildFreeArchiveCandidate(
                    title = product.title,
                    storeName = offer.store.name,
                    sourceUrl = destination,
                    knownDomain = offer.store.domain
                )?.let(::add)
            }
            val currentDestination = product.storeUrl.ifBlank { product.currentStore.officialUrl }
            buildFreeArchiveCandidate(
                title = product.title,
                storeName = product.currentStore.name,
                sourceUrl = currentDestination,
                knownDomain = product.currentStore.domain
            )?.let(::add)

            // A Google Shopping intermediary is not an archive target. If it is the only
            // saved destination, retry using the known seller name/domain from the saved row.
            if (isGoogleShoppingUrl(product.storeUrl) || candidateBuilder.isEmpty()) {
                val savedStore = Store.findKnownByName(product.currentStore.name, currentDestination)
                    ?: Store.findKnownByName(product.currentStore.id.replace('_', ' '), currentDestination)
                val fallbackDomain = savedStore?.domain
                    ?.takeIf { it.isNotBlank() && !it.endsWith(".invalid") }
                val fallbackStore = savedStore ?: product.currentStore
                val resolved = waybackPriceHistoryClient.resolveMerchantDomain(
                    storeName = product.currentStore.name,
                    sourceUrl = "",
                    knownStoreDomain = fallbackDomain.orEmpty()
                )
                val domain = fallbackDomain ?: resolved
                    ?: waybackPriceHistoryClient.resolveBrandMerchant(product.title)?.second
                if (domain != null) {
                    add(
                        FreeArchiveCandidate(
                            title = product.title,
                            storeName = fallbackStore.name,
                            directUrl = null,
                            merchantDomain = domain
                        )
                    )
                }
            }
        }
        val orderedCandidates = candidateBuilder.sortedWith(
            compareBy<FreeArchiveCandidate> { if (it.directUrl != null) 0 else 1 }
                .thenBy { it.merchantDomain.orEmpty() }
        ).distinctBy {
            // Search one page per retailer: a seller's alternate URL must not consume another slot.
            it.title.trim().lowercase(Locale.ROOT) + "|" + (it.merchantDomain ?: it.directUrl ?: "unknown")
        }.take(MAX_ARCHIVE_PAGES_PER_SEARCH)

        val directCandidates = orderedCandidates.count { it.directUrl != null }
        val domainCandidates = orderedCandidates.count { it.merchantDomain != null }
        reportWaybackDiagnostic(
            product.id, 3,
            if (orderedCandidates.isNotEmpty()) WaybackTraceStatus.PASSED else WaybackTraceStatus.FAILED,
            "Destinos válidos: ${orderedCandidates.size} (directos: $directCandidates; por dominio: $domainCandidates). Límite: $MAX_ARCHIVE_PAGES_PER_SEARCH tienda(s); no se registran rutas ni parámetros."
        )

        if (orderedCandidates.isEmpty()) {
            reportWaybackDiagnostic(
                product.id, 4, WaybackTraceStatus.SKIPPED,
                "No se envió ninguna petición porque no se identificó un destino válido."
            )
            _uiState.value = _uiState.value.copy(
                isSearchingFreeHistory = false,
                freeHistoryStatus = "Mínimo no pudo identificar la tienda de esta oferta para buscar capturas gratuitas. No se ha gastado cuota de SerpApi.",
                freeHistoryProductId = product.id
            )
            finishWaybackDiagnostic(product.id, WaybackTraceStatus.FAILED, "No se generó ninguna URL de tienda válida.")
            return
        }

        reportWaybackDiagnostic(
            product.id, 4, WaybackTraceStatus.PASSED,
            "Precontrol completado: no hay pausa global activa. La consulta puede continuar; caché y límites se comprueban por destino."
        )
        _uiState.value = _uiState.value.copy(
            isSearchingFreeHistory = true,
            freeHistoryStatus = "Buscando páginas históricas gratuitas; esta consulta no consume cuota de SerpApi.",
            freeHistoryProductId = product.id
        )
        startFreeArchiveBackfillCandidates(
            candidates = orderedCandidates,
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
        val cooldown = onlineSearchQuotaGuard.cooldownRemainingMs(
            searchCacheProviderKey(current.onlineSearchProvider),
            query
        )
        if (cooldown > 0L) {
            val message = quotaCooldownMessage(cooldown)
            _uiState.value = current.copy(onlineSearchStatus = message)
            showSnackbar(message)
            return
        }
        // Keep the previous cache until a replacement search succeeds.
        startOnlineSearch(current.onlineSearchProvider, bypassCache = true)
    }

    /** Ignore late results when the user edits the query during an in-flight request. */
    private fun isOnlineSearchStillCurrent(query: String, provider: OnlineSearchProvider): Boolean {
        val current = _uiState.value
        return current.onlineSearchQuery.trim() == query && current.onlineSearchProvider == provider
    }

    /** Search Google Shopping or Amazon.es, reusing fresh local results whenever possible. */
    private fun startOnlineSearch(provider: OnlineSearchProvider, bypassCache: Boolean = false) {
        val current = _uiState.value
        val query = current.onlineSearchQuery.trim()
        if (current.isOnlineSearching || current.isRefreshingPrices) return
        if (query.length < 2) {
            val message = "Escribe al menos 2 caracteres para buscar."
            _uiState.value = current.copy(onlineSearchStatus = message)
            showSnackbar(message)
            return
        }

        val providerQuery = optimizedOnlineShoppingQuery(query, provider)
        // Include the tuned wording in the local cache identity so older broad-query snapshots
        // don't hide improved results. This is still one search, not an extra provider call.
        val cacheQuery = if (providerQuery == query) query else "$query|$providerQuery"
        val queryOptimizationNotice = onlineSearchQueryOptimizationNotice(query, provider)

        val searchingMessage = when (provider) {
            OnlineSearchProvider.AMAZON -> "Consultando resultados públicos de Amazon.es…"
            OnlineSearchProvider.GOOGLE_SHOPPING -> "Buscando ofertas en Google Shopping España…"
        }
        val keepPreviousSnapshot = lastDisplayedSearchIdentity == onlineSearchIdentity(provider, query)
        _uiState.value = current.copy(
            isOnlineSearching = true,
            onlineSearchStatus = searchingMessage,
            onlineSearchResults = if (keepPreviousSnapshot) current.onlineSearchResults else emptyList(),
            onlineSearchDiscardedOffers = if (keepPreviousSnapshot) current.onlineSearchDiscardedOffers else emptyList(),
            showOnlineSearchDiscarded = false,
            onlineSearchProvider = provider,
            onlineSearchAmazonOnly = provider == OnlineSearchProvider.AMAZON
        )
        viewModelScope.launch {
            try {
                withTimeout(35_000L) {
                    // Keep a bounded total timeout, but leave enough room for the 12-second HTTP
                    // call plus the free account/quota check and CPU/Room work on slower devices.
                    // The request itself remains bounded by OkHttp's 12-second call timeout.
                    // Cache lookup comes first: saved results remain usable even if the quota is empty
                    // or the API key was removed after the previous search.
                    val cached = if (bypassCache) null else withContext(Dispatchers.IO) {
                        onlineSearchCache.read(searchCacheProviderKey(provider), cacheQuery)
                    }
                    if (!isOnlineSearchStillCurrent(query, provider)) return@withTimeout
                    if (cached != null && (cached.rawResponse != null || cached.results.isNotEmpty())) {
                        // Parsing stored JSON and classifying offers can be CPU-heavy. Keep it off
                        // Android's main thread so the search screen continues drawing smoothly.
                        val cachedReport = withContext(Dispatchers.Default) {
                            if (cached.rawResponse != null) {
                                val cachedRoot = org.json.JSONObject(cached.rawResponse)
                                if (provider == OnlineSearchProvider.AMAZON) {
                                    serpApiClient.parseAmazonResultsDetailed(cachedRoot, query, cached.savedAt)
                                } else {
                                    serpApiClient.parseShoppingResultsDetailed(cachedRoot, query, cached.savedAt)
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
                            val eligible = repository.filterOnlineSearchResults(cachedResults, query)
                            val discarded = (cachedReport.discardedOffers +
                                repository.diagnoseOnlineSearchRejections(cachedResults, query))
                                .distinctBy { it.provider + "|" + it.title + "|" + it.priceLabel + "|" + it.reason }
                            eligible to discarded
                        }
                        onlineDiscardedCache[onlineSearchCacheKey(provider, query)] = cachedDiscarded
                        val ageMinutes = ((System.currentTimeMillis() - cached.savedAt) / 60_000L).coerceAtLeast(1L)
                        val base = if (safeCachedResults.isEmpty()) {
                            cachedReport.emptyResultMessage(query)
                        } else {
                            onlineSearchResultMessage(safeCachedResults, provider, query)
                        }
                        val cacheNotice = "Resultados guardados hace $ageMinutes min; no se ha consumido otra búsqueda."
                        val savedObservations = if (safeCachedResults.isNotEmpty()) {
                            withContext(Dispatchers.IO) {
                                repository.recordOnlineSearchResults(safeCachedResults, provider.name, query)
                            }
                        } else 0
                        val historyNotice = if (safeCachedResults.isNotEmpty()) {
                            onlineHistoryNotice(savedObservations, safeCachedResults.size)
                        } else ""
                        val filterNotice = onlineFilterNotice(cachedDiscarded)
                        val status = listOf(base, queryOptimizationNotice, cacheNotice, filterNotice, historyNotice)
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .joinToString("\n\n")
                        if (!isOnlineSearchStillCurrent(query, provider)) return@withTimeout
                        lastDisplayedSearchIdentity = onlineSearchIdentity(provider, query)
                        _uiState.value = _uiState.value.copy(
                            onlineSearchResults = safeCachedResults,
                            onlineSearchDiscardedOffers = cachedDiscarded,
                            showOnlineSearchDiscarded = false,
                            onlineSearchStatus = status
                        )
                        // Historical archive scans are user-triggered from a product detail sheet.
                        // Do not start a second crawl automatically during every online search.
                        return@withTimeout
                    }

                    val token = withContext(Dispatchers.IO) { serpApiTokenStore.read() }
                    if (!isOnlineSearchStillCurrent(query, provider)) return@withTimeout
                    if (token.isNullOrBlank()) {
                        val message = serpApiTokenStore.lastReadErrorMessage()
                            ?: "Para mostrar precios dentro de Mínimo, guarda tu clave de SerpApi en Ajustes. También puedes abrir Amazon con tu sesión iniciada."
                        _uiState.value = _uiState.value.copy(onlineSearchStatus = message)
                        showSnackbar(message)
                        return@withTimeout
                    }

                    // Validate the key and quota before the paid search request.
                    val accountInfo = readAccountInfo(token)
                    if (!isOnlineSearchStillCurrent(query, provider)) return@withTimeout
                    if (accountInfo == null) {
                        val message = "No se ha podido validar la cuenta SerpApi. No se ha lanzado ninguna búsqueda ni gastado cuota. Comprueba la conexión e inténtalo de nuevo."
                        _uiState.value = _uiState.value.copy(onlineSearchStatus = message)
                        showSnackbar(message)
                        return@withTimeout
                    }
                    accountStatusBlockMessage(accountInfo)?.let { message ->
                        _uiState.value = _uiState.value.copy(onlineSearchStatus = message)
                        showSnackbar(message)
                        return@withTimeout
                    }
                    if (accountCapacity(accountInfo) <= 0) {
                        val message = quotaBlockMessage(accountInfo)
                        _uiState.value = _uiState.value.copy(onlineSearchStatus = message)
                        showSnackbar(message)
                        return@withTimeout
                    }

                    // A failed/empty query might not create a cache entry. Keep a short local
                    // cooldown for the same query so repeated taps cannot burn repeated credits.
                    val cooldown = onlineSearchQuotaGuard.tryStartAttempt(
                        searchCacheProviderKey(provider),
                        query
                    )
                    if (cooldown > 0L) {
                        val message = quotaCooldownMessage(cooldown)
                        _uiState.value = _uiState.value.copy(onlineSearchStatus = message)
                        showSnackbar(message)
                        return@withTimeout
                    }

                    val providerReport = when (provider) {
                        OnlineSearchProvider.GOOGLE_SHOPPING ->
                            serpApiClient.searchOnlineWithDiagnostics(token, providerQuery, relevanceQuery = query)
                        OnlineSearchProvider.AMAZON ->
                            serpApiClient.searchAmazonOnlineWithDiagnostics(token, query)
                    }
                    if (!isOnlineSearchStillCurrent(query, provider)) return@withTimeout

                    var providerResults = providerReport.results
                    var providerDiscardedOffers = providerReport.discardedOffers
                    var providerRawResultCount = providerReport.rawResultCount
                    var gameSupplementRequired = false
                    var gameSupplementAttempted = false
                    var gameSupplementSucceeded = false
                    var gameSupplementNote = ""

                    // GAME is part of this same search flow. Only gaming-related queries with no
                    // identified GAME offer may trigger one optional Shopping supplement.
                    // At least two credits must have remained before the primary request.
                    if (provider == OnlineSearchProvider.GOOGLE_SHOPPING &&
                        shouldSearchGameSupplement(query, providerResults)
                    ) {
                        gameSupplementRequired = true
                        if (accountCapacity(accountInfo) >= 2) {
                            gameSupplementAttempted = true
                            try {
                                val gameReport = withTimeoutOrNull(8_000L) {
                                    serpApiClient.searchGameSupplementWithDiagnostics(token, query)
                                } ?: throw java.net.SocketTimeoutException("La consulta complementaria de GAME ha agotado el tiempo límite.")
                                val identifiedGameOffers = gameReport.results.count(::isGameSpainOffer)
                                providerResults = mergeGoogleShoppingWithGameSupplement(
                                    primaryOffers = providerResults,
                                    supplementalOffers = gameReport.results
                                )
                                providerDiscardedOffers = providerDiscardedOffers + gameReport.discardedOffers
                                providerRawResultCount += gameReport.rawResultCount
                                gameSupplementSucceeded = true
                                gameSupplementNote = if (identifiedGameOffers > 0) {
                                    "Búsqueda unificada: se han añadido $identifiedGameOffers ofertas identificadas como GAME España. La consulta complementaria puede consumir una búsqueda adicional."
                                } else {
                                    "Se consultó GAME dentro de la búsqueda unificada, pero Google Shopping no devolvió ofertas identificables de esa tienda. No se ha inventado ningún precio."
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // A secondary retailer lookup must never discard valid general results.
                                gameSupplementNote = "El complemento automático de GAME no respondió; se conservan los resultados generales."
                            }
                        } else {
                            gameSupplementNote = "No se ha consultado GAME por separado porque el saldo disponible solo permite priorizar la búsqueda general."
                        }
                    }

                    // Keep CPU-heavy product relevance filtering off Android's UI thread.
                    val (results, discarded) = withContext(Dispatchers.Default) {
                        val rawResults = providerResults
                        val repositoryDiscarded = repository.diagnoseOnlineSearchRejections(rawResults, query)
                        val accepted = repository.filterOnlineSearchResults(rawResults, query)
                        val rejected = (providerDiscardedOffers + repositoryDiscarded)
                            .distinctBy { "${it.provider}|${it.title}|${it.priceLabel}|${it.reason}" }
                        accepted to rejected
                    }
                    onlineDiscardedCache[onlineSearchCacheKey(provider, query)] = discarded
                    val savedObservations = if (results.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                                repository.recordOnlineSearchResults(results, provider.name, query)
                        }
                    } else 0

                    // Cache the merged snapshot only after the supplement succeeds. Otherwise retain
                    // the primary raw response so accepted/rejected offers can be reclassified.
                    if (gameSupplementSucceeded) {
                        withContext(Dispatchers.IO) {
                            onlineSearchCache.write(searchCacheProviderKey(provider), cacheQuery, providerResults)
                        }
                    } else if (!gameSupplementRequired) {
                        // Ordinary non-gaming searches and broad searches that already returned GAME
                        // can cache the provider response as usual.
                        val rawCached = providerReport.rawResponse?.let { rawResponse ->
                            withContext(Dispatchers.IO) {
                                onlineSearchCache.writeRaw(
                                    searchCacheProviderKey(provider),
                                    cacheQuery,
                                    rawResponse
                                )
                            }
                        } ?: false
                        if (!rawCached) {
                            // Oversized or malformed raw responses fall back to compact accepted offers.
                            // This preserves the useful cache without storing megabytes in SharedPreferences
                            // or triggering another paid provider search on the next visit.
                            withContext(Dispatchers.IO) {
                                onlineSearchCache.write(
                                    searchCacheProviderKey(provider),
                                    cacheQuery,
                                    results
                                )
                            }
                        }
                    } else {
                        // Do not cache a gaming search as complete when its GAME supplement failed
                        // or could not run due to quota: otherwise a later retry would reuse the
                        // general-only cache for 24 hours and never retry the missing retailer.
                        if (!bypassCache) {
                            withContext(Dispatchers.IO) {
                                onlineSearchCache.invalidate(searchCacheProviderKey(provider), cacheQuery)
                            }
                        }
                    }
                    val base = if (results.isEmpty() && providerRawResultCount > 0) {
                        providerReport.emptyResultMessage(query)
                    } else {
                        onlineSearchResultMessage(results, provider, query)
                    }
                    val quotaNote = if (gameSupplementAttempted) {
                        "Saldo comprobado antes de buscar: ${accountInfo.quotaSummary()} La búsqueda general y el complemento de GAME pueden consumir hasta dos búsquedas."
                    } else {
                        "Saldo comprobado antes de buscar: ${accountInfo.quotaSummary()} La búsqueda general puede haber consumido una búsqueda."
                    }
                    val uniqueStoreCount = results.map { it.storeName.trim().lowercase(Locale.ROOT) }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .size
                    val coverageNote = if (provider == OnlineSearchProvider.GOOGLE_SHOPPING) {
                        val queryDescription = if (gameSupplementAttempted) {
                            "entre la búsqueda general y el intento de complemento de GAME"
                        } else {
                            "en una sola búsqueda general"
                        }
                        "Cobertura: ${providerRawResultCount} resultados del proveedor ${queryDescription}; ${results.size} ofertas válidas de ${uniqueStoreCount} tiendas tras comprobar producto, precio y enlace. La consulta general solicita hasta 100 resultados sin hacer una petición por tienda."
                    } else {
                        ""
                    }
                    val filterNote = onlineFilterNotice(discarded)
                    val historyNote = if (results.isNotEmpty()) {
                        onlineHistoryNotice(savedObservations, results.size)
                    } else ""
                    val message = listOf(base, queryOptimizationNotice, coverageNote, quotaNote, gameSupplementNote, filterNote, historyNote)
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                    if (!isOnlineSearchStillCurrent(query, provider)) return@withTimeout
                    lastDisplayedSearchIdentity = onlineSearchIdentity(provider, query)
                    _uiState.value = _uiState.value.copy(
                        onlineSearchResults = results,
                        onlineSearchDiscardedOffers = discarded,
                        showOnlineSearchDiscarded = false,
                        onlineSearchStatus = message
                    )
                    // Keep the paid/live search responsive. Historical lookups remain available
                    // via the explicit free-history action in each product detail sheet.
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                val message = "La búsqueda ha tardado demasiado y se ha cancelado para evitar que quede bloqueada. Comprueba la conexión e inténtalo de nuevo; los resultados guardados se mantienen."
                if (isOnlineSearchStillCurrent(query, provider)) {
                    _uiState.value = _uiState.value.copy(onlineSearchStatus = message)
                    showSnackbar(message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SerpApiException) {
                val message = error.message ?: "No se pudo completar la búsqueda."
                if (isOnlineSearchStillCurrent(query, provider)) {
                    _uiState.value = _uiState.value.copy(onlineSearchStatus = message)
                    showSnackbar(message)
                }
            } catch (_: Exception) {
                val message = "No se pudo buscar. Comprueba la conexión y la clave guardada en Ajustes."
                if (isOnlineSearchStillCurrent(query, provider)) {
                    _uiState.value = _uiState.value.copy(onlineSearchStatus = message)
                    showSnackbar(message)
                }
            } finally {
                // A timeout or cancellation must never strand the search screen in its loading state.
                _uiState.value = _uiState.value.copy(isOnlineSearching = false)
            }
        }
    }

    /**
     * Free Wayback lookup runs after live search results are displayed. The current offer price is
     * used only to prioritize ordinary search candidates; archived quotes are persisted separately.
     */
    private data class FreeArchiveCandidate(
        val title: String,
        val storeName: String,
        val directUrl: String?,
        val merchantDomain: String?
    )

    private fun buildFreeArchiveCandidate(
        title: String,
        storeName: String,
        sourceUrl: String,
        knownDomain: String = ""
    ): FreeArchiveCandidate? {
        if (title.isBlank()) return null
        val genericProviderName = storeName.trim().lowercase(Locale.ROOT) in setOf(
            "", "google", "google shopping", "google shopping españa", "serpapi", "serp api", "shopping"
        )
        val extractedUrl = extractDirectRetailerUrl(sourceUrl)
        val resolvedDomain = waybackPriceHistoryClient.resolveMerchantDomain(
            storeName = storeName,
            sourceUrl = sourceUrl,
            knownStoreDomain = knownDomain
        )
        // Use an actual retailer URL embedded by Google when available. A known domain from
        // the saved Store record is authoritative; when no domain is known, require the extracted
        // registrable host to match the reported seller label (or the provider link to be generic).
        val directUrl = extractedUrl?.takeIf { candidateUrl ->
            if (!waybackPriceHistoryClient.isEligibleDirectUrl(candidateUrl)) {
                false
            } else if (!isGoogleShoppingUrl(sourceUrl)) {
                true
            } else {
                val actualHost = runCatching {
                    java.net.URI(candidateUrl).host.orEmpty()
                        .lowercase(Locale.ROOT)
                        .removePrefix("www.")
                }.getOrDefault("")
                val expectedDomain = resolvedDomain
                (expectedDomain != null &&
                    (actualHost == expectedDomain || actualHost.endsWith(".$expectedDomain"))) ||
                    genericProviderName || merchantNameMatchesHost(storeName, actualHost)
            }
        }
        val directDomain = directUrl?.let { url ->
            runCatching {
                java.net.URI(url).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        // Only fall back to the manufacturer's own domain when neither the seller's saved
        // domain nor an actual destination URL is available; do not search Sony's site for a
        // product sold by an unidentified third-party retailer.
        val brandFallback = if (resolvedDomain == null && directDomain == null) {
            waybackPriceHistoryClient.resolveBrandMerchant(title)
        } else null
        val domain = resolvedDomain ?: directDomain ?: brandFallback?.second
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

    private fun merchantNameMatchesHost(storeName: String, host: String): Boolean {
        if (storeName.isBlank() || host.isBlank()) return false
        fun normalize(value: String): String = Normalizer.normalize(
            value.lowercase(Locale.ROOT), Normalizer.Form.NFD
        ).replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

        val ignored = setOf(
            "store", "tienda", "shop", "online", "espana", "official", "oficial",
            "espanol", "market", "marketplace", "the", "com"
        )
        val nameTokens = normalize(storeName).split(' ')
            .filter { it.length >= 3 && it !in ignored }
        if (nameTokens.isEmpty()) return false

        // Compare the registrable-name labels, not arbitrary subdomains: pixmania.attacker.com
        // must not pass merely because "pixmania" occurs somewhere before the real domain.
        val labels = host.lowercase(Locale.ROOT).removePrefix("www.").split('.')
        if (labels.size < 2) return false
        val registrableLabel = when {
            labels.size >= 3 && labels[labels.lastIndex - 1] in setOf("co", "com", "net", "org") &&
                labels.last() in setOf("uk", "au", "nz", "jp", "in") -> labels[labels.lastIndex - 2]
            else -> labels[labels.lastIndex - 1]
        }.replace(Regex("[^a-z0-9]"), "")
        return nameTokens.all { token -> registrableLabel.contains(token) }
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

        val diagnosticProductId = targetProductId?.takeIf {
            _uiState.value.waybackDiagnosticProductId == it
        }
        // Collect terminal CDX outcomes so a slow secondary request cannot overwrite a
        // response already received and classified successfully for this product.
        val cdxStep5Events = java.util.Collections.synchronizedList(mutableListOf<WaybackTraceEvent>())
        // A bad/unknown domain on one fallback candidate must not mark the whole preflight as
        // failed when another direct URL has already reached Internet Archive successfully.
        val archiveRequestSent = java.util.concurrent.atomic.AtomicBoolean(false)
        val traceCallback: ((WaybackTraceEvent) -> Unit)? = diagnosticProductId?.let { id ->
            { event ->
                if (event.step == 4 && event.status == WaybackTraceStatus.PASSED) {
                    archiveRequestSent.set(true)
                }
                if (event.step == 5 && event.status != WaybackTraceStatus.RUNNING &&
                    event.status != WaybackTraceStatus.PENDING
                ) {
                    synchronized(cdxStep5Events) { cdxStep5Events.add(event) }
                }
                recordWaybackTrace(id, event)
            }
        }
        viewModelScope.launch {
            try {
                var savedCount = 0
                var checkedCount = 0
                var foundCount = 0
                var capturesFoundCount = 0
                var selectedProductRefreshed = false
                var successfulLookupsCount = 0
                var requestFailedCount = 0
                var skippedCount = 0
                var timedOutCount = 0
                val diagnosticCodes = linkedSetOf<String>()
                val archiveDates = mutableListOf<Long>()
                for (candidate in candidates) {
                    // Bound each candidate's direct + merchant-domain lookup so slow public
                    // archive responses cannot leave the detail sheet spinning indefinitely.
                    // Prefer the exact retailer URL because it is much cheaper than scanning
                    // the whole domain. A failing CDX domain fallback must never consume the
                    // entire per-store budget or starve the next candidate.
                    val attempts = mutableListOf<com.example.data.remote.WaybackBackfillResult>()
                    var candidateTimedOut = false
                    if (candidate.directUrl != null) {
                        val direct = withTimeoutOrNull(MAX_ARCHIVE_LOOKUP_PER_CANDIDATE_MS) {
                            waybackPriceHistoryClient.fetchHistory(
                                originalUrl = candidate.directUrl,
                                expectedTitle = candidate.title,
                                onTrace = traceCallback
                            )
                        }
                        if (direct == null) {
                            candidateTimedOut = true
                            diagnosticCodes.add("CDX_URL_PREFIX_CANDIDATE_TIMEOUT")
                        } else {
                            attempts += direct
                        }
                    }
                    // Try the broad fallback only if exact lookup did not find verified prices.
                    // Its shorter independent timeout leaves time for result rendering and other stores.
                    val rateLimitedAtDirectStage =
                        attempts.any { it.diagnosticCodes.contains("WAYBACK_RATE_LIMITED") }
                    if (candidate.merchantDomain != null &&
                        attempts.none { it.quotes.isNotEmpty() } &&
                        !rateLimitedAtDirectStage
                    ) {
                        if (diagnosticProductId != null) {
                            reportWaybackDiagnostic(
                                diagnosticProductId, 7, WaybackTraceStatus.RUNNING,
                                "Se activa la alternativa por dominio; se mantienen la caché y los límites de consultas."
                            )
                        }
                        val domainResult = withTimeoutOrNull(MAX_ARCHIVE_DOMAIN_FALLBACK_MS) {
                            waybackPriceHistoryClient.fetchHistoryByMerchantDomain(
                                domain = candidate.merchantDomain,
                                expectedTitle = candidate.title,
                                preferredUrl = candidate.directUrl,
                                // The direct URL's prefix-CDX lookup ran just above. Avoid
                                // repeating the same request before the Availability fallback.
                                preferredUrlAlreadyQueried = candidate.directUrl != null,
                                onTrace = traceCallback
                            )
                        }
                        if (domainResult == null) {
                            candidateTimedOut = true
                            diagnosticCodes.add("CDX_DOMAIN_CANDIDATE_TIMEOUT")
                            if (diagnosticProductId != null) {
                                reportWaybackDiagnostic(
                                    diagnosticProductId, 7, WaybackTraceStatus.FAILED,
                                    "La alternativa por dominio superó el límite de tiempo local."
                                )
                            }
                        } else {
                            attempts += domainResult
                            if (diagnosticProductId != null) {
                                val fallbackDiagnostics = domainResult.diagnosticCodes.filter { code ->
                                    code == "WAYBACK_RATE_LIMITED" ||
                                        code.startsWith("DOMAIN_AVAILABILITY_FALLBACK_")
                                }
                                val fallbackTransportFailed = fallbackDiagnostics.any { code ->
                                    code == "WAYBACK_RATE_LIMITED" ||
                                        code.contains("_TIMEOUT") ||
                                        code.contains("_DNS") ||
                                        code.contains("_NETWORK") ||
                                        code.contains("_HTTP_") ||
                                        code.endsWith("_UNEXPECTED_ERROR") ||
                                        code.endsWith("_INVALID_RESPONSE") ||
                                        code.endsWith("_BODY_EMPTY_OR_LIMIT") ||
                                        code.endsWith("_NO_CANDIDATES")
                                }
                                // The combined result retains CDX_DOMAIN_TIMEOUT even if the
                                // Availability API itself answered correctly. Step 6 must describe
                                // the fallback's outcome; step 7 distinguishes a genuine empty
                                // capture list from a transport failure.
                                val fallbackReturnedNoCaptures =
                                    fallbackDiagnostics.contains("DOMAIN_AVAILABILITY_FALLBACK_NO_CAPTURES")
                                val step6Succeeded = domainResult.quotes.isNotEmpty() ||
                                    domainResult.capturesFound > 0 ||
                                    fallbackReturnedNoCaptures ||
                                    !fallbackTransportFailed
                                reportWaybackDiagnostic(
                                    diagnosticProductId, 7,
                                    if (step6Succeeded) WaybackTraceStatus.PASSED else WaybackTraceStatus.FAILED,
                                    when {
                                        domainResult.quotes.isNotEmpty() ->
                                            "Alternativa por dominio devolvió ${domainResult.quotes.size} precio(s) verificado(s)."
                                        fallbackReturnedNoCaptures && fallbackTransportFailed ->
                                            "Availability devolvió respuestas válidas sin capturas, pero también falló alguna URL candidata: ${fallbackDiagnostics.joinToString(", ").take(180)}. Revisa el detalle del paso 7."
                                        fallbackReturnedNoCaptures ->
                                            "La alternativa respondió correctamente, pero no encontró capturas; revisa el paso 7."
                                        domainResult.capturesFound > 0 ->
                                            "La alternativa encontró capturas; revisa los pasos 8–14 para saber por qué no se aceptó un precio."
                                        fallbackTransportFailed ->
                                            "La alternativa por dominio falló: ${fallbackDiagnostics.joinToString(", ").take(180)}."
                                        else ->
                                            "Alternativa por dominio ejecutada; no devolvió precio verificado. Revisa los pasos 7–14."
                                    }
                                )
                            }
                        }
                    }
                    if (candidateTimedOut && attempts.isEmpty()) {
                        timedOutCount++
                        diagnosticCodes.add("CANDIDATE_TIMEOUT")
                        continue
                    }

                    if (attempts.isNotEmpty() && attempts.all { it.skippedRecently }) {
                        skippedCount++
                        // Preserve the reason for a cached skip: a previous timeout is not a
                        // successful "no history" result and should be explained differently.
                        attempts.flatMap { it.diagnosticCodes }.forEach(diagnosticCodes::add)
                        continue
                    }
                    // Prefer diagnostics from the last-stage outcome. A domain fallback may
                    // succeed after a direct URL's CDX failure; don't report the recovered failure
                    // as if it were the final reason for not finding history.
                    val latestResults = attempts.asReversed()
                    val latestDiagnostic = latestResults.firstOrNull { result ->
                        result.quotes.isNotEmpty() ||
                            result.diagnosticCodes.any { it != "CACHE_SKIPPED" }
                    }
                    latestDiagnostic?.diagnosticCodes?.forEach(diagnosticCodes::add)
                    if (latestDiagnostic == null) {
                        attempts.flatMap { it.diagnosticCodes }
                            .filter { it == "CACHE_SKIPPED" }
                            .forEach(diagnosticCodes::add)
                    }
                    val quotes = attempts.flatMap { it.quotes }
                        .distinctBy { Triple(it.originalUrl, it.observedAt, it.price) }
                    checkedCount += attempts.sumOf { it.capturesChecked }
                    foundCount += quotes.size
                    capturesFoundCount += attempts.sumOf { it.capturesFound }
                    successfulLookupsCount += attempts.count { it.available }
                    requestFailedCount += attempts.count { it.requestFailed }
                    quotes.map { it.observedAt }.let(archiveDates::addAll)

                    if (diagnosticProductId != null && quotes.isNotEmpty()) {
                        reportWaybackDiagnostic(
                            diagnosticProductId, 19, WaybackTraceStatus.RUNNING,
                            "Preparando ${quotes.size} observación(es) de precio ya verificadas para guardar localmente."
                        )
                    }
                    for ((archiveUrl, urlQuotes) in quotes.groupBy { it.originalUrl }) {
                        val newlySaved = repository.recordArchivedPriceHistory(
                            expectedTitle = candidate.title,
                            originalUrl = archiveUrl,
                            storeName = candidate.storeName,
                            quotes = urlQuotes,
                            targetProductId = targetProductId
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
                            selectedProductRefreshed = true
                            val currentState = _uiState.value
                            if (currentState.selectedProduct?.id == refreshedProduct.id) {
                                _uiState.value = currentState.copy(selectedProduct = refreshedProduct)
                            }
                        }
                    }

                    // An HTTP 429 applies to the archive service, not only this product.
                    // Stop the batch now instead of iterating through the remaining candidates.
                    if (attempts.any { it.diagnosticCodes.contains("WAYBACK_RATE_LIMITED") }) {
                        break
                    }
                }

                if (diagnosticProductId != null) {
                    val persistenceStatus = when {
                        savedCount > 0 || foundCount > 0 -> WaybackTraceStatus.PASSED
                        else -> WaybackTraceStatus.SKIPPED
                    }
                    val persistenceDetail = when {
                        savedCount > 0 ->
                            "Guardado completado: ${savedCount} nuevo(s) punto(s) de precio histórico en la base local."
                        foundCount > 0 ->
                            "Se verificaron ${foundCount} punto(s); no se añadieron duplicados porque los registros ya existían."
                        else ->
                            "No se guardaron registros: ninguna captura produjo un precio verificado."
                    }
                    reportWaybackDiagnostic(
                        diagnosticProductId, 19, persistenceStatus, persistenceDetail
                    )
                    val gateStatus = when {
                        // Network activity is stronger evidence than aggregate error codes: the
                        // latter may include a rejected secondary merchant-domain candidate.
                        archiveRequestSent.get() -> WaybackTraceStatus.PASSED
                        diagnosticCodes.contains("WAYBACK_RATE_LIMITED") ||
                            diagnosticCodes.contains("CACHE_SKIPPED") ||
                            diagnosticCodes.contains("CACHE_SKIPPED_AFTER_FAILURE") ->
                            WaybackTraceStatus.SKIPPED
                        diagnosticCodes.contains("WAYBACK_DISABLED") ||
                            diagnosticCodes.contains("DIRECT_URL_INVALID") ||
                            diagnosticCodes.contains("DOMAIN_UNRESOLVED") ->
                            WaybackTraceStatus.FAILED
                        else -> WaybackTraceStatus.PASSED
                    }
                    reportWaybackDiagnostic(
                        diagnosticProductId, 4, gateStatus,
                        when {
                            archiveRequestSent.get() ->
                                "Validación previa superada: al menos una petición CDX salió. Los destinos secundarios no válidos se aíslan y se detallan en su fase; los fallos de red se muestran en los pasos 5–8."
                            gateStatus == WaybackTraceStatus.SKIPPED ->
                                "Consulta omitida por pausa HTTP 429 o caché; no se enviaron peticiones duplicadas."
                            gateStatus == WaybackTraceStatus.FAILED ->
                                "No se superó la validación previa. Diagnóstico: ${diagnosticCodes.filter {
                                    it in setOf("WAYBACK_DISABLED", "DIRECT_URL_INVALID", "DOMAIN_UNRESOLVED")
                                }.joinToString(", ")}."
                            else ->
                                "La consulta pasó las comprobaciones de configuración, destino y control de repetición; los errores de red se detallan en los pasos CDX."
                        }
                    )
                    val availabilityRecovered = capturesFoundCount > 0 || successfulLookupsCount > 0
                    val cdxSummary = synchronized(cdxStep5Events) {
                        summarizeCdxStep5Events(
                            events = cdxStep5Events.toList(),
                            availabilityRecovered = availabilityRecovered,
                            availabilityCapturesFound = capturesFoundCount
                        )
                    }
                    if (cdxSummary != null) {
                        reportWaybackDiagnostic(
                            diagnosticProductId,
                            diagnosticIndexForWaybackTrace(cdxSummary),
                            cdxSummary.status,
                            cdxSummary.detail,
                            cdxSummary.durationMs
                        )
                    }
                }

                val notice = when {
                    skippedCount == candidates.size && diagnosticCodes.contains("CACHE_SKIPPED_AFTER_FAILURE") ->
                        "Internet Archive no respondió en el intento anterior. Mínimo evita repetir enseguida una consulta que agotó el tiempo; vuelve a probar dentro de unos minutos. Esto no significa que no existan capturas."
                    skippedCount == candidates.size ->
                        "Estas consultas gratuitas ya se comprobaron recientemente. La caché evita repetir búsquedas completadas sin resultados útiles."
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
                    requestFailedCount > 0 && diagnosticCodes.contains("WAYBACK_RATE_LIMITED") ->
                        waybackRateLimitNotice() + " Esto no significa que no existan capturas."
                    capturesFoundCount > 0 && checkedCount == 0 && requestFailedCount > 0 ->
                        "Internet Archive encontró $capturesFoundCount capturas, pero las descargas fallaron. No se puede confirmar el precio histórico; puedes volver a intentarlo."
                    checkedCount > 0 && requestFailedCount > 0 ->
                        "Se revisaron $checkedCount capturas, pero algunas consultas fallaron. Los resultados son parciales; vuelve a intentarlo para completar el histórico."
                    requestFailedCount > 0 ->
                        "Falló una o más consultas de Internet Archive. El diagnóstico de abajo indica la fase; esto no confirma que no existan capturas."
                    timedOutCount > 0 ->
                        "La búsqueda histórica agotó el tiempo disponible para $timedOutCount tienda(s). Las consultas están limitadas para mantener la fluidez; prueba de nuevo más tarde."
                    capturesFoundCount == 0 && successfulLookupsCount > 0 ->
                        "Internet Archive respondió correctamente, pero no encontró capturas públicas en el período consultado. No hay precios antiguos verificables que mostrar."
                    checkedCount > 0 ->
                        "Se consultaron $checkedCount capturas gratuitas, pero ninguna publicó un precio EUR verificable que coincidiera con el producto exacto."
                    else ->
                        "No se recuperó histórico gratuito. Comprueba el diagnóstico de Wayback para conocer la fase que falló."
                }
                val diagnosticLabels = diagnosticCodes.distinct().take(8).map { code ->
                    when {
                        code.startsWith("CDX_DOMAIN_EXACT_HTTP_") -> "$code (respuesta HTTP de la consulta exacta de una URL candidata)"
                        code.startsWith("CDX_URL_PREFIX_HTTP_") -> "$code (respuesta HTTP de la búsqueda por prefijo de URL)"
                        code.startsWith("CDX_DOMAIN_HTTP_") -> "$code (respuesta HTTP de la búsqueda por dominio)"
                        code.startsWith("SNAPSHOT_HTTP_") -> "$code (respuesta HTTP de una captura)"
                        else -> when (code) {
                            "WAYBACK_RATE_LIMITED" -> "$code (HTTP 429: consultas pausadas temporalmente para respetar el límite de Internet Archive)"
                            "WAYBACK_DISABLED" -> "$code (búsqueda desactivada)"
                            "DIRECT_URL_INVALID" -> "$code (la URL guardada no es una página de comercio válida)"
                            "PRODUCT_TITLE_EMPTY" -> "$code (falta el nombre del producto)"
                            "CDX_URL_BUILD_FAILED" -> "$code (no se pudo construir la consulta por URL)"
                            "DOMAIN_UNRESOLVED" -> "$code (no se identificó el dominio de la tienda)"
                            "CDX_QUERY_NOT_BUILDABLE" -> "$code (faltan términos útiles para buscar en el dominio)"
                            "CACHE_SKIPPED" -> "$code (resultado reciente en caché)"
                            "CDX_URL_PREFIX_BODY_EMPTY_OR_LIMIT" -> "$code (respuesta de la consulta por prefijo vacía o demasiado grande)"
                            "CDX_DOMAIN_BODY_EMPTY_OR_LIMIT" -> "$code (respuesta de la consulta por dominio vacía o demasiado grande)"
                            "CDX_URL_PREFIX_INVALID_RESPONSE" -> "$code (la consulta por prefijo no devolvió el formato esperado)"
                            "CDX_DOMAIN_INVALID_RESPONSE" -> "$code (la consulta por dominio no devolvió el formato esperado)"
                            "CDX_URL_PREFIX_TIMEOUT" -> "$code (agotó el tiempo la consulta por prefijo de URL)"
                            "CDX_DOMAIN_TIMEOUT" -> "$code (agotó el tiempo la búsqueda de URLs de producto en el índice)"
                            "CDX_DOMAIN_EXACT_TIMEOUT" -> "$code (agotó el tiempo al consultar una URL de producto encontrada)"
                            "CDX_DOMAIN_EXACT_DNS" -> "$code (no se pudo resolver el servidor al consultar una URL candidata)"
                            "CDX_DOMAIN_EXACT_NETWORK" -> "$code (fallo de conexión al consultar una URL candidata)"
                            "CDX_DOMAIN_EXACT_UNEXPECTED_ERROR" -> "$code (error inesperado al consultar una URL candidata)"
                            "CDX_DOMAIN_EXACT_BODY_EMPTY_OR_LIMIT" -> "$code (respuesta de la URL candidata vacía o demasiado grande)"
                            "CDX_DOMAIN_EXACT_INVALID_RESPONSE" -> "$code (la URL candidata devolvió un formato de índice no válido)"
                            "CDX_NO_PRODUCT_URLS" -> "$code (el índice no devolvió URLs de producto que coincidieran con la búsqueda)"
                            "CDX_URL_PREFIX_DNS" -> "$code (no se pudo resolver el servidor del índice en la consulta por prefijo)"
                            "CDX_DOMAIN_DNS" -> "$code (no se pudo resolver el servidor del índice en la búsqueda por dominio)"
                            "CDX_URL_PREFIX_NETWORK" -> "$code (fallo de conexión en la consulta por prefijo)"
                            "CDX_DOMAIN_NETWORK" -> "$code (fallo de conexión en la búsqueda por dominio)"
                            "CDX_URL_PREFIX_UNEXPECTED_ERROR" -> "$code (error inesperado en la consulta por prefijo)"
                            "CDX_DOMAIN_UNEXPECTED_ERROR" -> "$code (error inesperado en la búsqueda por dominio)"
                            "CDX_NO_CAPTURES" -> "$code (el índice respondió, pero no encontró capturas)"
                            "NO_VERIFIED_EUR_PRICE" -> "$code (capturas sin título coincidente y precio EUR verificable)"
                            "SNAPSHOT_URL_INVALID" -> "$code (URL de captura no válida)"
                            "SNAPSHOT_BODY_EMPTY_OR_LIMIT" -> "$code (contenido vacío o demasiado grande)"
                            "SNAPSHOT_TIMEOUT" -> "$code (la descarga de la captura agotó el tiempo)"
                            "SNAPSHOT_DNS" -> "$code (no se resolvió el servidor de la captura)"
                            "SNAPSHOT_NETWORK" -> "$code (fallo de red al descargar la captura)"
                            "ALL_SNAPSHOTS_FAILED" -> "$code (ninguna captura se pudo descargar)"
                            "CANDIDATE_TIMEOUT" -> "$code (se agotó el tiempo total de la búsqueda de esta tienda)"
                            "CDX_URL_PREFIX_CANDIDATE_TIMEOUT" -> "$code (la consulta por prefijo superó su límite de tiempo)"
                            "CDX_DOMAIN_CANDIDATE_TIMEOUT" -> "$code (la búsqueda amplia por dominio superó su límite de tiempo)"
                            else -> code
                        }
                    }
                }
                val noticeWithDiagnostics = if (diagnosticLabels.isNotEmpty() && skippedCount != candidates.size) {
                    "$notice\nDiagnóstico Wayback: ${diagnosticLabels.joinToString(" · ")}."
                } else notice
                appendArchiveNotice(query, provider, noticeWithDiagnostics, targetProductId)
                if (diagnosticProductId != null) {
                    when {
                        savedCount > 0 -> finishWaybackDiagnostic(
                            diagnosticProductId, WaybackTraceStatus.PASSED,
                            "$savedCount precio(s) histórico(s) guardado(s) en la base de datos local."
                        )
                        foundCount > 0 && selectedProductRefreshed -> finishWaybackDiagnostic(
                            diagnosticProductId, WaybackTraceStatus.PASSED,
                            "Se recuperaron precios, pero ya estaban guardados; la ficha se refrescó con el historial existente."
                        )
                        foundCount > 0 -> finishWaybackDiagnostic(
                            diagnosticProductId, WaybackTraceStatus.FAILED,
                            "Se extrajeron precios, pero no se confirmó el guardado o la actualización de la ficha."
                        )
                        skippedCount == candidates.size -> finishWaybackDiagnostic(
                            diagnosticProductId, WaybackTraceStatus.SKIPPED,
                            "No se repitió la búsqueda porque había un resultado reciente en caché."
                        )
                        else -> finishWaybackDiagnostic(
                            diagnosticProductId, WaybackTraceStatus.SKIPPED,
                            "No se obtuvo ningún precio válido; el guardado no llegó a ejecutarse."
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                if (diagnosticProductId != null) {
                    finishWaybackDiagnostic(diagnosticProductId, WaybackTraceStatus.FAILED, "La operación fue cancelada.")
                }
                throw cancelled
            } catch (error: Exception) {
                if (diagnosticProductId != null) {
                    finishWaybackDiagnostic(
                        diagnosticProductId, WaybackTraceStatus.FAILED,
                        "Error interno controlado: ${error.javaClass.simpleName}."
                    )
                }
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
            "Precios de esta búsqueda: $saved observaciones nuevas guardadas en Mercado. Son precios observados ahora, no un historial anterior a la búsqueda. Para buscar precios anteriores gratis, abre la ficha y pulsa «Buscar histórico gratis · Internet Archive»; solo aparecerán si hay capturas con precios verificables."
        } else {
            "Precios de esta búsqueda: no se añadieron observaciones nuevas porque ya estaban registradas hoy o no pasaron los filtros ($resultCount resultados revisados). El historial anterior se muestra por separado cuando existen fechas verificables."
        }

    private fun onlineSearchResultMessage(
        results: List<SerpApiShoppingResult>,
        provider: OnlineSearchProvider,
        query: String
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

    fun onToggleOnlyHistoricalMinimum() {
        _uiState.value = _uiState.value.copy(
            onlyHistoricalMinimumFilter = !_uiState.value.onlyHistoricalMinimumFilter
        )
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
        if (query.isBlank() || _uiState.value.isVerifying) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isVerifying = true)
            try {
                // Catalog matching is CPU work; keep it off the main thread.
                val result = withContext(Dispatchers.Default) {
                    repository.analyzeSpanishUrlOrQuery(query)
                }
                // Do not publish an outdated result if the user has changed the input meanwhile.
                if (_uiState.value.verifierInput.trim() == query) {
                    _uiState.value = _uiState.value.copy(verifierResult = result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (_uiState.value.verifierInput.trim() == query) {
                    _uiState.value = _uiState.value.copy(
                        verifierResult = UrlAnalysisResult(
                            queryOrUrl = query,
                            identifiedStore = null,
                            detectedTitle = query,
                            matchedProduct = null,
                            isKnownRetailerDomain = false,
                            adviceMessage = "No se pudo completar la comprobación. Vuelve a intentarlo."
                        )
                    )
                }
            } finally {
                _uiState.value = _uiState.value.copy(isVerifying = false)
            }
        }
    }

    fun clearVerifierResult() {
        _uiState.value = _uiState.value.copy(verifierResult = null, verifierInput = "")
    }
}
