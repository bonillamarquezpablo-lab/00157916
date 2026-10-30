package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.remote.OnlineSearchCache
import com.example.data.remote.formatOnlineSnapshotAge
import com.example.data.remote.OnlineSearchQuotaGuard
import com.example.data.remote.ArchivedPriceQuote
import com.example.data.remote.MementoClient
import com.example.data.remote.shouldAttemptMementoFallback
import com.example.data.remote.hasMatchingExplicitStorageCapacity
import com.example.data.remote.shouldSkipDomainFallbackAfterSnapshotTimeout
import com.example.data.remote.shouldSkipDomainFallbackAfterRepeatedCdxTimeouts
import com.example.data.remote.shouldOpenArchiveDomainFallbackCircuit
import com.example.data.remote.shouldTryDomainFallbackForArchiveCandidate
import com.example.data.remote.shouldUseAvailabilityOnlyFallbackAfterCdxTimeout
import com.example.data.remote.WaybackPriceHistoryClient
import com.example.data.remote.diagnosticIndexForWaybackTrace
import com.example.data.remote.summarizeCdxStep5Events
import com.example.data.remote.WaybackTraceEvent
import com.example.data.remote.WaybackTraceStatus
import com.example.data.remote.isGoogleShoppingUrl
import com.example.data.remote.extractDirectRetailerUrl
import com.example.data.remote.onlineProductIdentityKey
import com.example.data.remote.queryNamesSpecificDysonHairToolModel
import com.example.data.remote.returnedTitleMatchesSpecificDysonHairToolModel
import com.example.data.remote.onlineProductRelevanceScore
import com.example.data.remote.isBroadDysonHairToolsQueryMatch
import com.example.data.remote.isGameSpainOffer
import com.example.data.remote.mergeGoogleShoppingWithGameSupplement
import com.example.data.remote.shouldSearchGameSupplement
import com.example.data.remote.RejectedOnlineOffer
import com.example.data.remote.SerpApiAccountInfo
import com.example.data.remote.SerpApiClient
import com.example.data.remote.SerpApiException
import com.example.data.remote.SerpApiRequestProduct
import com.example.data.remote.SerpApiShoppingResult
import com.example.data.remote.SerpApiShoppingSearchReport
import com.example.data.remote.SerpApiTokenStore
import com.example.data.remote.extractGoogleShoppingProductId
import com.example.data.model.Product
import com.example.data.model.PriceBadgeType
import com.example.data.model.ProductCategory
import com.example.data.model.SmartphoneSubcategory
import com.example.data.model.MarketplaceCategorySummary
import com.example.data.model.Store
import com.example.data.repository.ProductRepository
import com.example.data.repository.ProductDiscoveryRepository
import com.example.data.local.ProductDiscoveryEntity
import com.example.notifications.PriceAlertScheduler
import com.example.data.repository.UrlAnalysisResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

/**
 * Known retailer candidates used only to fill unused slots in a manual historical lookup.
 * Existing direct/store URLs always keep their priority, and the caller still applies the
 * three-store cap. The final HTML verifier remains responsible for product identity and price.
 */
internal fun historicalRetailerFallbackStores(category: ProductCategory, title: String): List<Store> {
    val normalizedTitle = Normalizer.normalize(title.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
    val consoleProduct = Regex(
        """\b(playstation|ps[345]|xbox|nintendo\s+switch|steam\s+deck|consola)\b"""
    ).containsMatchIn(normalizedTitle)
    val gamingProduct = category == ProductCategory.GAMING || consoleProduct ||
        Regex("""\b(videojuegos?|gaming)\b""").containsMatchIn(normalizedTitle)
    val effectiveCategory = if (gamingProduct) ProductCategory.GAMING else category

    return when (effectiveCategory) {
        ProductCategory.GAMING -> if (consoleProduct) {
            // Console lookups prioritize broad electronics shops with known product-search URLs.
            listOf(
                Store.MEDIA_MARKT, Store.PC_COMPONENTES, Store.FNAC, Store.AMAZON_ES, Store.GAME,
                Store.EL_CORTE_INGLES, Store.CARREFOUR, Store.WORTEN, Store.MIRAVIA,
                Store.NEOBYTE, Store.COOLMOD, Store.ALTERNATE, Store.LDLC
            )
        } else {
            // Preserve the existing order for other gaming products such as games and steering wheels.
            listOf(
                Store.GAME, Store.MEDIA_MARKT, Store.PC_COMPONENTES, Store.FNAC, Store.AMAZON_ES,
                Store.EL_CORTE_INGLES, Store.CARREFOUR, Store.WORTEN, Store.MIRAVIA,
                Store.NEOBYTE, Store.COOLMOD, Store.ALTERNATE, Store.LDLC
            )
        }
        ProductCategory.SMARTPHONES -> listOf(
            Store.MEDIA_MARKT, Store.PC_COMPONENTES, Store.AMAZON_ES, Store.FNAC,
            Store.EL_CORTE_INGLES, Store.CARREFOUR, Store.WORTEN, Store.MIRAVIA,
            Store.POWERPLANET, Store.LA_MANZANITA
        )
        ProductCategory.COMPUTING -> listOf(
            Store.PC_COMPONENTES, Store.AMAZON_ES, Store.MEDIA_MARKT, Store.COOLMOD,
            Store.NEOBYTE, Store.ALTERNATE, Store.LDLC, Store.FNAC, Store.EL_CORTE_INGLES,
            Store.LENOVO_STORE, Store.DELL_STORE, Store.HP_STORE
        )
        ProductCategory.TV_AUDIO -> listOf(
            Store.MEDIA_MARKT, Store.AMAZON_ES, Store.PC_COMPONENTES, Store.FNAC,
            Store.WORTEN, Store.EL_CORTE_INGLES, Store.CARREFOUR, Store.ALCAMPO
        )
        ProductCategory.HOME_APPLIANCES -> listOf(
            Store.MEDIA_MARKT, Store.AMAZON_ES, Store.WORTEN, Store.CARREFOUR,
            Store.EL_CORTE_INGLES, Store.ALCAMPO, Store.EROSKI, Store.CONFORAMA
        )
        ProductCategory.PERSONAL_CARE -> listOf(
            Store.AMAZON_ES, Store.MEDIA_MARKT, Store.EL_CORTE_INGLES,
            Store.CARREFOUR, Store.ALCAMPO, Store.WORTEN
        )
        ProductCategory.ACCESSORIES -> listOf(
            Store.AMAZON_ES, Store.PC_COMPONENTES, Store.MEDIA_MARKT, Store.FNAC,
            Store.MIRAVIA, Store.COOLMOD
        )
        ProductCategory.REPLACEMENT_PARTS -> listOf(
            Store.AMAZON_ES, Store.PC_COMPONENTES, Store.LEROY_MERLIN,
            Store.CARREFOUR, Store.ALCAMPO, Store.MIRAVIA
        )
        ProductCategory.TOYS -> listOf(
            Store.AMAZON_ES, Store.FNAC, Store.CARREFOUR, Store.EL_CORTE_INGLES,
            Store.ALCAMPO, Store.MIRAVIA, Store.GAME
        )
        ProductCategory.SPORTS -> listOf(
            Store.DECATHLON, Store.AMAZON_ES, Store.SPRINTER,
            Store.TRADEINN, Store.MIRAVIA, Store.CARREFOUR
        )
        ProductCategory.ALL, ProductCategory.OTHER -> listOf(
            Store.AMAZON_ES, Store.PC_COMPONENTES, Store.MEDIA_MARKT, Store.FNAC,
            Store.EL_CORTE_INGLES, Store.CARREFOUR, Store.WORTEN, Store.MIRAVIA
        )
    }
}

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
    val durationMs: Long? = null,
    val isCaptureDetail: Boolean = false,
    val captureKey: String? = null
)

/**
 * One diagnostic row aggregates repeated attempts for the same product. A later CDX timeout can
 * skip parsing even though Availability already supplied captures and the HTML was downloaded.
 * Keep the strongest terminal outcome instead of letting that later, unrelated route erase it.
 */
internal fun mergeWaybackDiagnosticStatus(
    current: WaybackTraceStatus,
    incoming: WaybackTraceStatus,
    diagnosticStep: Int? = null,
    detail: String = ""
): WaybackTraceStatus = when {
    // Audit phases 23–29 are consistency gates. Any failure must remain visible even if a
    // later sub-check passes; green status must never erase a real rejection or mismatch.
    diagnosticStep in 23..29 &&
        (current == WaybackTraceStatus.FAILED || incoming == WaybackTraceStatus.FAILED) ->
        WaybackTraceStatus.FAILED
    // Capture rows (download and barrier evaluation) represent several individually labelled
    // captures. If any one fails, keep the aggregate row red even if another capture succeeded;
    // the per-capture detail lines show exactly which dates succeeded and which DNS/download failed.
    (diagnosticStep in 11..14 || diagnosticStep in 21..22) &&
        (current == WaybackTraceStatus.FAILED || incoming == WaybackTraceStatus.FAILED) ->
        WaybackTraceStatus.FAILED
    // Stages 6 and 7 describe recovery, not mere network reachability. A final explicit
    // no-capture result must override an earlier green sub-attempt so the UI cannot display
    // "superado" when no historical captures were recovered.
    (diagnosticStep == 6 || diagnosticStep == 7) &&
        incoming == WaybackTraceStatus.FAILED &&
        (
            detail.contains("no encontraron capturas utilizables", ignoreCase = true) ||
            detail.contains("no hay histórico que validar", ignoreCase = true) ||
            detail.contains("no encontró capturas utilizables", ignoreCase = true) ||
            detail.contains("no encontró capturas", ignoreCase = true) ||
            detail.contains("no se encontraron capturas", ignoreCase = true) ||
            detail.contains("sin capturas", ignoreCase = true) ||
            detail.contains("no devolvió capturas", ignoreCase = true) ||
            detail.contains("no encontró capturas y alguna consulta falló", ignoreCase = true) ||
            detail.contains("no se recuperaron capturas históricas utilizables", ignoreCase = true)
        ) -> WaybackTraceStatus.FAILED
    current == WaybackTraceStatus.PASSED || incoming == WaybackTraceStatus.PASSED ->
        WaybackTraceStatus.PASSED
    current == WaybackTraceStatus.FAILED || incoming == WaybackTraceStatus.FAILED ->
        WaybackTraceStatus.FAILED
    incoming == WaybackTraceStatus.RUNNING -> WaybackTraceStatus.RUNNING
    current == WaybackTraceStatus.RUNNING && incoming == WaybackTraceStatus.PENDING ->
        WaybackTraceStatus.RUNNING
    current == WaybackTraceStatus.SKIPPED || incoming == WaybackTraceStatus.SKIPPED ->
        WaybackTraceStatus.SKIPPED
    else -> incoming
}

/**
 * Stops trying secondary retailer destinations once the selected product already has enough
 * verified dates. Extra inferred URLs can trigger redundant Availability calls and HTTP 429 even
 * after the direct CDX route has completed a usable three-date history.
 */
/**
 * Persistence is verified when a new point is inserted or a valid existing duplicate is found.
 * A duplicate proves the historical point is already stored, but does not mean the chart grew.
 * Merely extracting a quote or refreshing the product card is not proof of persistence.
 */
internal fun archivePersistenceStatus(
    savedPoints: Int,
    confirmedDuplicates: Int = 0,
    rejectedQuotes: Int = 0,
    attempted: Boolean = false,
    completed: Boolean = true
): WaybackTraceStatus = when {
    savedPoints > 0 || confirmedDuplicates > 0 -> WaybackTraceStatus.PASSED
    attempted && (!completed || rejectedQuotes > 0) -> WaybackTraceStatus.FAILED
    else -> WaybackTraceStatus.SKIPPED
}

internal fun hasEnoughVerifiedArchiveDates(
    timestamps: Iterable<Long>,
    minimumDistinctDates: Int = 3
): Boolean {
    if (minimumDistinctDates <= 0) return false
    return timestamps.asSequence()
        // Persistence groups captures by the device's local calendar day, not UTC day numbers.
        // Keep the early-exit threshold aligned or captures straddling UTC midnight can count twice.
        .map { timestamp ->
            java.util.Calendar.getInstance().apply {
                timeInMillis = timestamp
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        .distinct()
        .take(minimumDistinctDates)
        .count() >= minimumDistinctDates
}

/**
 * A historical series is sufficient to skip alternate sources only when it contains both
 * distinct verified dates and at least that many newly inserted Room points. Old dates replayed
 * by Wayback must never be treated as completion.
 */
internal fun hasSufficientNewVerifiedArchiveHistory(
    timestamps: Iterable<Long>,
    newPointsSaved: Int,
    minimumDistinctDates: Int = 3
): Boolean = newPointsSaved >= minimumDistinctDates &&
    hasEnoughVerifiedArchiveDates(timestamps, minimumDistinctDates)

internal fun isSupersededWaybackSkipDetail(detailLine: String): Boolean {
    val message = detailLine.substringAfter("] ", detailLine).trim().lowercase(Locale.ROOT)
    return message.startsWith("no ejecutado:") ||
        message.startsWith("extracción no ejecutada:") ||
        message.startsWith("no se pudo iniciar la descarga") ||
        message.startsWith("no hay html de captura") ||
        message.startsWith("no hay una segunda captura que evaluar") ||
        message.startsWith("no existe una segunda captura para descargar") ||
        message.startsWith("evaluación de la captura 2 omitida") ||
        message.startsWith("consolidación omitida:") ||
        message.startsWith("no hay url candidatas que normalizar") ||
        message.startsWith("no había fechas de capturas") ||
        message.startsWith("no hay fechas que validar") ||
        message.startsWith("no se pudo validar el período") ||
        message.startsWith("normalización omitida") ||
        message.startsWith("validación de fechas omitida") ||
        message.startsWith("no se encontró una segunda captura independiente") ||
        message.startsWith("no había capturas disponibles") ||
        message.startsWith("no se pudo comprobar la identidad: cdx no proporcionó ninguna captura") ||
        message.startsWith("extracción de precio omitida: no se descargó html de una captura cdx") ||
        message.startsWith("validación de importe omitida: no se obtuvo ningún precio de una captura cdx") ||
        message.startsWith("verificación del producto omitida: availability no devolvió una captura") ||
        message.startsWith("extracción del precio omitida: no hay html de una captura histórica") ||
        message.startsWith("validación de importe omitida: no se extrajo ningún precio")
}

private val WAYBACK_DIAGNOSTIC_TITLES = listOf(
    "Solicitud iniciada (botón pulsado)",
    "Ficha local y URL principal recuperadas",
    "URL normalizada y destinos candidatos preparados",
    "Precontrol de pausa global y preparación de consulta",
    "Petición enviada al índice CDX",
    "Respuesta CDX o respuesta alternativa recibida",
    "Fallback: CDX alternativo / Availability API",
    "Extracción de capturas (índice CDX o Availability)",
    "Normalización y deduplicación de URL candidatas",
    "Validación de fechas y período histórico",
    "Descarga HTML de capturas (resumen)",
    "Evaluación HTML de capturas (resumen)",
    "Descarga HTML de rutas/capturas adicionales (resumen)",
    "Evaluación HTML de rutas/capturas adicionales (resumen)",
    "Verificación del título e identidad del producto",
    "Extracción del precio",
    "Validación de importe, EUR y cambio histórico",
    "Consolidación de precios y eliminación de duplicados",
    "Guardado del histórico en la base de datos local",
    "Actualización de la ficha/gráfica y resultado final",
    "Perfil y decodificación de la página archivada",
    "Decisión del extractor y causa de rechazo del precio",
    "Identidad del producto y vínculo URL comprobados",
    "Precio, moneda y fecha validados",
    "Motivos de rechazo registrados y contabilizados",
    "Resultado de guardado local confirmado",
    "Duplicados existentes e integridad de filas revisados",
    "Coherencia entre capturas, guardados y rechazos",
    "Actualización de ficha y gráfico comprobada",
    "Diagnóstico final y estado de la operación"
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
    /** Existing provisional catalogue rows related to the active live-search query. */
    val onlineSearchRelatedMarketCandidates: List<Product> = emptyList(),
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
        const val MAX_RELATED_UNVERIFIED_MARKET_CANDIDATES = 8
        // Keep all sampled snapshot labels (ordinal/date/route/host) visible across overlapping
        // exact, prefix and merchant-domain probes. Ordinary diagnostic stages remain concise.
        const val MAX_CAPTURE_DIAGNOSTIC_LINES = 120
        const val MAX_CAPTURE_DIAGNOSTIC_ROWS = 80
        const val TRACKED_REFRESH_COOLDOWN_MS = 24L * 60L * 60L * 1000L
        const val MAX_TRACKED_REFRESH_PER_TAP = 10
        const val MAX_ARCHIVE_PAGES_PER_SEARCH = 3
        // Once direct CDX routes time out, don't send another CDX request. Every retailer
        // slot already admitted by the three-store search budget may switch to Availability-only.
        // The first store's domain fallback is still that same store, not an extra retailer;
        // subtracting one here accidentally left the final (often manufacturer) candidate unused.
        const val MAX_AVAILABILITY_ONLY_FALLBACKS_AFTER_CDX_TIMEOUT =
            MAX_ARCHIVE_PAGES_PER_SEARCH
        // Historical lookup is a user-triggered background action. Reserve time for the
        // CDX query and a representative set of dated captures; a too-short outer timeout
        // cancels useful HTML downloads before they can be validated and saved.
        const val MAX_ARCHIVE_LOOKUP_PER_CANDIDATE_MS = 130_000L
        // Domain recovery may include paced Availability probes and up to eight dated snapshots.
        // This is separate from live shopping search and still bounded per retailer.
        const val MAX_ARCHIVE_DOMAIN_FALLBACK_MS = 90_000L
        // Memento is a separate archive provider and gets one short fallback window per manual lookup.
        const val MAX_ARCHIVE_MEMENTO_FALLBACK_MS = 25_000L
    }

    private val database = AppDatabase.getInstance(application)
    private val productDiscoveryRepository = ProductDiscoveryRepository(database)
    val pendingDiscoveries: StateFlow<List<ProductDiscoveryEntity>> =
        productDiscoveryRepository.pendingDiscoveries.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            emptyList()
        )
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
    private val mementoClient = MementoClient(application)
    private val onlineDiscardedCache = mutableMapOf<String, List<RejectedOnlineOffer>>()
    /** Prevent a streaming callback and the final consolidation pass from scheduling the same staged write twice. */
    private val scheduledDiscoveryQuoteKeys =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())
    /** Prevent duplicate current-price lookups when several archive candidates fail together. */
    private val inFlightArchiveFailureFallbacks =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())
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
            ?: return "Internet Archive devolvió HTTP 429, pero la pausa local ya ha vencido. Mínimo no repetirá automáticamente la consulta; si la caché lo permite, puedes volver a pulsar «Buscar histórico gratis». Las consultas al archivo son gratuitas; el respaldo del precio actual, si se activa, puede consumir una búsqueda de SerpApi."
        val remainingMinutes = ((until - now + 59_999L) / 60_000L).coerceAtLeast(1L)
        val retryAt = SimpleDateFormat("HH:mm", Locale("es", "ES")).format(Date(until))
        return "Internet Archive ha pedido una pausa temporal (HTTP 429). Mínimo no enviará más peticiones al archivo hasta aproximadamente las ${retryAt} (en unos ${remainingMinutes} min). El respaldo del precio actual, si se activa y SerpApi tiene saldo, puede consumir una búsqueda de SerpApi."
    }

    private fun mementoRateLimitNotice(now: Long = System.currentTimeMillis()): String {
        val until = mementoClient.activeRateLimitUntil(now)
            ?: return "Arquivo.pt (Memento) devolvió HTTP 429, pero no hay una pausa Memento activa confirmada. Esto no significa que la pausa global de Wayback esté activa."
        val remainingMinutes = ((until - now + 59_999L) / 60_000L).coerceAtLeast(1L)
        val retryAt = SimpleDateFormat("HH:mm", Locale("es", "ES")).format(Date(until))
        return "Arquivo.pt (Memento) devolvió HTTP 429. Mínimo pausará las consultas a esa fuente hasta aproximadamente las ${retryAt} (en unos ${remainingMinutes} min). Esta pausa es independiente de la pausa global de Internet Archive/Wayback."
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
        if (step !in 1..30) return
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
            // A stage can be attempted by direct CDX, Availability and an alternate CDX
            // route. Once a real attempt passed or failed at this stage, a later SKIPPED event
            // from another route is not evidence that the stage was never executed.
            val mergedStatus = mergeWaybackDiagnosticStatus(old.status, status, step, detail)
            val redundantSkip = status == WaybackTraceStatus.SKIPPED &&
                old.status in setOf(WaybackTraceStatus.PASSED, WaybackTraceStatus.FAILED)
            // If this same stage later really runs or fails on another route, remove obsolete
            // "not executed" messages from earlier CDX-only attempts. Keep genuine failures from
            // other attempts as audit detail; only stale skip explanations are superseded.
            val oldDetailLines = when {
                status == WaybackTraceStatus.PASSED || status == WaybackTraceStatus.FAILED ->
                    old.detail.lines().filterNot(::isSupersededWaybackSkipDetail)
                // Multiple archive routes share one 22-stage UI. If an earlier route failed to
                // download HTML, a later route can emit a generic "CDX supplied no capture" skip
                // for the same identity stage. Do not retain that contradictory stale skip beside
                // the real timeout; it makes the report imply the index was empty.
                status == WaybackTraceStatus.SKIPPED &&
                    old.status == WaybackTraceStatus.SKIPPED ->
                    old.detail.lines().filterNot(::isSupersededWaybackSkipDetail)
                status in setOf(WaybackTraceStatus.SKIPPED, WaybackTraceStatus.FAILED) &&
                    old.status == WaybackTraceStatus.RUNNING ->
                    old.detail.lines().filterNot { line ->
                        line.contains("Se activa la alternativa por dominio", ignoreCase = true) ||
                            line.contains("se activa la alternativa limitada", ignoreCase = true)
                    }
                else -> old.detail.lines()
            }
            val retainedOldDetail = oldDetailLines.joinToString("\n")
            val combinedDetail = when {
                detailLine.isBlank() || redundantSkip -> retainedOldDetail
                retainedOldDetail.isBlank() -> detailLine
                oldDetailLines.any { it == detailLine } -> retainedOldDetail
                else -> (oldDetailLines + detailLine)
                    .takeLast(if (step in 11..14) MAX_CAPTURE_DIAGNOSTIC_LINES else 5)
                    .joinToString("\n")
            }
            rows[index] = old.copy(
                status = mergedStatus,
                detail = combinedDetail,
                durationMs = durationMs ?: old.durationMs
            )
            state.copy(waybackDiagnosticSteps = rows)
        }
    }

    private fun recordCaptureWaybackDiagnostic(productId: String, event: WaybackTraceEvent) {
        val step = diagnosticIndexForWaybackTrace(event)
        if (step !in 11..14 && step !in 21..22) return
        val captureLabel = Regex(
            """^captura \d+ de \d+ · id [a-j]{8} · fecha \d{14} · ruta [A-Za-z0-9_-]+ · tienda [A-Za-z0-9.-]+"""
        ).find(event.detail)?.value ?: return
        val phase = when (step) {
            11, 13 -> "Descarga HTML"
            12, 14 -> "Evaluación HTML"
            21 -> "Perfil HTML"
            else -> "Resultado del extractor"
        }
        val key = "$captureLabel|$step"
        val eventText = event.detail.removePrefix(captureLabel).trim().removePrefix("·").trim()
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        val detailLine = "[$time] " + eventText +
            (event.durationMs?.let { " · ${it} ms" } ?: "")
        _uiState.update { state ->
            if (state.waybackDiagnosticProductId != productId) return@update state
            val rows = state.waybackDiagnosticSteps.toMutableList()
            val index = rows.indexOfFirst { it.isCaptureDetail && it.captureKey == key }
            val previous = rows.getOrNull(index)
            val nextStatus = previous?.let {
                mergeWaybackDiagnosticStatus(it.status, event.status, step, event.detail)
            } ?: event.status
            val previousLines = previous?.detail?.lines().orEmpty()
            val combined = if (detailLine in previousLines) previousLines.joinToString("\n")
                else (previousLines + detailLine).takeLast(5).joinToString("\n")
            val row = WaybackDiagnosticEntry(
                step = 0,
                title = "$captureLabel · $phase",
                status = nextStatus,
                detail = combined,
                durationMs = event.durationMs ?: previous?.durationMs,
                isCaptureDetail = true,
                captureKey = key
            )
            if (index >= 0) rows[index] = row else rows += row
            val fixedRows = rows.filterNot { it.isCaptureDetail }
            val captureRows = rows.filter { it.isCaptureDetail }.takeLast(MAX_CAPTURE_DIAGNOSTIC_ROWS)
            state.copy(waybackDiagnosticSteps = fixedRows + captureRows)
        }
    }

    private fun recordWaybackTrace(productId: String, event: WaybackTraceEvent) {
        recordCaptureWaybackDiagnostic(productId, event)
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
        for (row in initial.waybackDiagnosticSteps.filterNot { it.isCaptureDetail }) {
            // Steps 20 and 30 are terminal summaries, not executable phases.
            if (row.step == 20 || row.step == 30) continue
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
        val finishedAt = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        _uiState.update { state ->
            if (state.waybackDiagnosticProductId != productId) return@update state
            state.copy(waybackDiagnosticSteps = state.waybackDiagnosticSteps.map { row ->
                if (row.isCaptureDetail &&
                    row.status in setOf(WaybackTraceStatus.PENDING, WaybackTraceStatus.RUNNING)
                ) {
                    row.copy(
                        status = WaybackTraceStatus.FAILED,
                        detail = (row.detail.lines() +
                            "[$finishedAt] La operación terminó antes de completar esta captura.")
                            .takeLast(5).joinToString("\n")
                    )
                } else row
            })
        }
        reportWaybackDiagnostic(productId, 20, finalStatus, finalDetail)
        reportWaybackDiagnostic(
            productId, 30, finalStatus,
            "Resultado final=$finalStatus. $finalDetail"
        )
    }

    init {
        // Register one daily, quota-capped monitor; it remains idle without price targets.
        runCatching { PriceAlertScheduler.ensureScheduled(application) }
    }

    // The home page subscribes only to a compact SQL aggregate, not to the complete catalogue.
    private val emptyMarketplaceSummaries = ProductCategory.values()
        .filter { it != ProductCategory.ALL }
        .map { MarketplaceCategorySummary(it, productCount = 0, storeCount = 0) }

    val marketplaceCategorySummaries: StateFlow<List<MarketplaceCategorySummary>> =
        repository.marketplaceCategorySummariesStream.stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            emptyMarketplaceSummaries
        )

    /** Lightweight badge count; never opens the complete product stream. */
    val trackedProductCount: StateFlow<Int> = repository.trackedProductCountStream
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), 0)

    // Only show merchants represented by at least one valid saved price. Store.ALL_STORES
    // remains the internal directory used to resolve existing product and offer records.
    val spanishStores: StateFlow<List<Store>> = repository.storesWithSavedPricesStream
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val marketplaceProductFlows = mutableMapOf<ProductCategory, StateFlow<List<Product>>>()
    private val marketplaceFilteredProductFlows = mutableMapOf<ProductCategory, StateFlow<List<Product>>>()

    /**
     * Cache only visible rows for the active category. The Room query and this conversion/filter
     * run off the UI thread and do not start until Mercado actually opens a category.
     */
    @Synchronized
    fun productsForCategory(category: ProductCategory): StateFlow<List<Product>> =
        marketplaceProductFlows.getOrPut(category) {
            repository.productsForCategoryStream(category)
                .map { products -> marketplaceCategoryProducts(products, category) }
                .flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
        }

    /**
     * Search, store, price and sorting filters also run off the UI thread, while sharing the same
     * cached category flow used to build this category's merchant selector.
     */
    @Synchronized
    fun filteredProductsForCategory(category: ProductCategory): StateFlow<List<Product>> =
        marketplaceFilteredProductFlows.getOrPut(category) {
            combine(productsForCategory(category), _uiState) { products, state ->
                filterMarketplaceProductsForState(products, state)
            }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
        }

    private val marketplaceSubcategoryProductFlows =
        mutableMapOf<SmartphoneSubcategory, StateFlow<List<Product>>>()
    private val marketplaceFilteredSubcategoryProductFlows =
        mutableMapOf<SmartphoneSubcategory, StateFlow<List<Product>>>()

    /**
     * Load only the parent category streams required by the selected smartphone subcategory.
     * The home screen never calls this, so it does not load product rows merely to draw the cards.
     */
    @Synchronized
    fun productsForSmartphoneSubcategory(
        subcategory: SmartphoneSubcategory
    ): StateFlow<List<Product>> = marketplaceSubcategoryProductFlows.getOrPut(subcategory) {
        val sourceFlows = subcategory.sourceCategories.map(::productsForCategory)
        combine(sourceFlows) { categoryRows ->
            categoryRows.asSequence()
                .flatten()
                .distinctBy { it.id }
                .filter(subcategory::matches)
                .toList()
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    }

    /** Search, seller, price and sorting controls operate on only the selected subcategory. */
    @Synchronized
    fun filteredProductsForSmartphoneSubcategory(
        subcategory: SmartphoneSubcategory
    ): StateFlow<List<Product>> =
        marketplaceFilteredSubcategoryProductFlows.getOrPut(subcategory) {
            combine(productsForSmartphoneSubcategory(subcategory), _uiState) { products, state ->
                filterMarketplaceProductsForState(products, state)
            }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
        }

    // Legacy all-catalogue flow kept for non-market actions and compatibility. The home screen
    // deliberately does not collect it.
    val filteredProducts: StateFlow<List<Product>> = combine(
        repository.allProductsStream,
        _uiState
    ) { allProducts, state ->
        filterMarketplaceProducts(allProducts, state, state.selectedCategory)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    // These flows are subscribed only when their tabs are opened. Once started, their StateFlow
    // cache remains warm across temporary backgrounding rather than re-reading Room after 5 sec.
    val trackedProducts: StateFlow<List<Product>> = repository.trackedProductsStream
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val todayAthDeals: StateFlow<List<Product>> = repository.todayAthDealsStream
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

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
            onlineSearchRelatedMarketCandidates = emptyList(),
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
                val current = _uiState.value
                val selectedOffers = current.onlineSearchResults.filter { offer ->
                    onlineProductIdentityKey(offer.title) == identityKey
                }
                val searchQuery = current.onlineSearchQuery.ifBlank {
                    selectedOffers.firstOrNull()?.title.orEmpty()
                }

                if (selectedOffers.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        // A transient write error during search must not permanently lose the
                        // seller prices. Retry the normal validated persistence path on tap;
                        // its daily upsert makes this safe when the first write already worked.
                        try {
                            repository.recordOnlineSearchResults(
                                results = selectedOffers,
                                sourceType = current.onlineSearchProvider.name,
                                searchQuery = searchQuery
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Keep opening the result even if Room is temporarily unavailable.
                        }

                        try {
                            repository.ensureVisibleOnlineSearchProducts(
                                results = selectedOffers,
                                searchQuery = searchQuery,
                                allowAccessories = true
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // A transient save failure must not prevent opening a ficha already
                            // in Room. The resolution below remains the source of truth.
                        }
                    }
                }

                val savedProduct = withTimeout(10_000L) {
                    repository.awaitProductForOnlineIdentity(identityKey)
                }

                // Search cards can contain a perfectly useful price even when the provider only
                // returns a generic Shopping link. Do not overwrite a trusted Room row with that
                // weaker evidence; show the just-found amount as a transient, clearly unverified
                // listing in this detail sheet instead.
                val displayableOffers = selectedOffers.filter { offer ->
                    offer.price.isFinite() && offer.price in 0.5..10_000.0 &&
                        !offer.isSuspiciousPrice &&
                        !offer.isRentalOrMonthly &&
                        !offer.isSecondHand &&
                        !offer.isFitmentUnverified &&
                        !offer.isCategoryUnverified
                }
                val directOffer = displayableOffers
                    .filterNot { it.isSearchFallbackDestination || it.isGoogleShoppingProductLink }
                    .minByOrNull { it.price / (it.wheelSetCount ?: 1) }
                val displayOffer = directOffer ?: displayableOffers
                    .minByOrNull { it.price / (it.wheelSetCount ?: 1) }

                val detailProduct = if (displayOffer != null) {
                    val listedPrice = displayOffer.price / (displayOffer.wheelSetCount ?: 1)
                    val samePriceAlreadySaved = savedProduct.currentPrice.isFinite() &&
                        kotlin.math.abs(savedProduct.currentPrice - listedPrice) < 0.005
                    val isGenericShoppingDestination =
                        displayOffer.isSearchFallbackDestination || displayOffer.isGoogleShoppingProductLink
                    val shouldShowListing = when {
                        savedProduct.isPriceFresh && savedProduct.isPriceVerified && samePriceAlreadySaved -> false
                        isGenericShoppingDestination && savedProduct.isPriceFresh -> false
                        else -> true
                    }
                    if (shouldShowListing) {
                        val listedStore = Store.findKnownByName(
                            displayOffer.storeName,
                            displayOffer.sourceUrl
                        ) ?: Store.findByName(displayOffer.storeName)
                        savedProduct.withUnverifiedLiveSearchListing(
                            listedPrice = listedPrice,
                            observedAt = displayOffer.observedAt,
                            sourceUrl = displayOffer.sourceUrl,
                            sourceName = displayOffer.storeName,
                            sourceStore = listedStore
                        )
                    } else {
                        savedProduct
                    }
                } else {
                    savedProduct
                }

                _uiState.value = _uiState.value.copy(selectedProduct = detailProduct)
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
    fun acceptDiscovery(discoveryId: String) {
        if (discoveryId.isBlank()) return
        viewModelScope.launch {
            try {
                val result = productDiscoveryRepository.accept(discoveryId)
                val message = when {
                    result == null ->
                        "No se ha podido añadir el producto: la ficha puede haber desaparecido o su identidad ya no ser inequívoca."
                    result.createdProduct ->
                        "Producto añadido al catálogo. Se han importado ${result.savedObservations} punto(s) histórico(s); el precio actual sigue sin verificar."
                    else ->
                        "El producto ya existía. Se han incorporado ${result.savedObservations} punto(s) histórico(s) a su ficha."
                }
                showSnackbar(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showSnackbar("No se ha podido añadir el descubrimiento. La cuarentena se conserva para volver a intentarlo.")
            }
        }
    }

    fun dismissDiscovery(discoveryId: String) {
        if (discoveryId.isBlank()) return
        viewModelScope.launch {
            try {
                val dismissed = productDiscoveryRepository.dismiss(discoveryId)
                showSnackbar(
                    if (dismissed) "Descubrimiento descartado; no se ha añadido al catálogo."
                    else "El descubrimiento ya no está pendiente."
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showSnackbar("No se ha podido descartar el descubrimiento.")
            }
        }
    }

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
                "Pausa global de Internet Archive activa. ${status}"
            )
            reportWaybackDiagnostic(
                product.id, 4, WaybackTraceStatus.SKIPPED,
                "La pausa global bloqueó la petición antes de consultar CDX o Availability. ${status}"
            )
            finishWaybackDiagnostic(
                product.id, WaybackTraceStatus.SKIPPED,
                "Reintento de archivo no enviado durante la pausa global (HTTP 429). Se intentará una búsqueda separada del precio actual si SerpApi tiene saldo; esa búsqueda puede consumir una consulta de SerpApi."
            )
            viewModelScope.launch {
                runLivePriceFallbackAfterArchiveFailure(
                    product = product,
                    archiveFailureReason = "Internet Archive está temporalmente limitado (HTTP 429)"
                )
            }
            return
        }
        val backendUnavailableUntil = waybackPriceHistoryClient.activeBackendUnavailableUntil()
        if (backendUnavailableUntil != null) {
            val remainingMinutes = ((backendUnavailableUntil - System.currentTimeMillis() + 59_999L) / 60_000L).coerceAtLeast(1L)
            val status = "Internet Archive no está respondiendo de forma estable. Mínimo ha pausado las consultas históricas durante unos $remainingMinutes min; no enviará más peticiones ni gastará consultas de pago por este fallo."
            _uiState.value = _uiState.value.copy(
                isSearchingFreeHistory = false,
                freeHistoryStatus = status,
                freeHistoryProductId = product.id
            )
            reportWaybackDiagnostic(product.id, 3, WaybackTraceStatus.SKIPPED, status)
            reportWaybackDiagnostic(product.id, 4, WaybackTraceStatus.SKIPPED, "Circuito de protección activo: se evita consultar CDX y Availability mientras el servicio se recupera.")
            finishWaybackDiagnostic(product.id, WaybackTraceStatus.SKIPPED, status)
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
            // The current-store field can be stale on legacy/catalogue rows. Do not let a
            // manufacturer/homepage URL displace actual sellers already present in saved offers.
            // If the current store is represented by an offer (or there are no offers), it remains
            // eligible here; otherwise the brand fallback is appended after real retailers below.
            val currentStoreIsRepresentedInOffers =
                product.offers.any { it.store.id == product.currentStore.id }
            if (product.offers.isEmpty() || currentStoreIsRepresentedInOffers) {
                buildFreeArchiveCandidate(
                    title = product.title,
                    storeName = product.currentStore.name,
                    sourceUrl = currentDestination,
                    knownDomain = product.currentStore.domain
                )?.let(::add)
            }

            // A Google Shopping intermediary is not an archive target. If it is the only
            // saved destination, retry using the known seller name/domain from the saved row.
            if (candidateBuilder.isEmpty() ||
                (isGoogleShoppingUrl(product.storeUrl) && candidateBuilder.none { it.directUrl != null })
            ) {
                val savedStore = Store.findKnownByName(product.currentStore.name, currentDestination)
                    ?: Store.findKnownByName(product.currentStore.id.replace('_', ' '), currentDestination)
                val fallbackStore = savedStore ?: product.currentStore
                // Never copy Store.domain directly into a candidate. Older saved rows may contain
                // a display/provider label instead of a host (for example "Google Shopping").
                // Re-resolve it through the same strict hostname normalizer used by the client.
                val resolved = waybackPriceHistoryClient.resolveMerchantDomain(
                    storeName = fallbackStore.name,
                    sourceUrl = currentDestination,
                    knownStoreDomain = savedStore?.domain.orEmpty()
                )
                val domain = resolved
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
        // A product history lookup should also check its own manufacturer archive, even
        // when the saved live offer belongs to a marketplace retailer. Manufacturer URLs often
        // preserve canonical model/variant pages that the retailer never archived. Keep this
        // as a separate, last-priority candidate and only for products (not cases/cables/etc.).
        val accessoryQuery = Regex(
            """(?i)\b(funda|carcasa|case|cover|protector|cable|cargador|adaptador|hub|filtro|recambio|repuesto|soporte|screen protector|glass protector)\b"""
        ).containsMatchIn(product.title)
        val manufacturer = if (accessoryQuery) null
            else waybackPriceHistoryClient.resolveBrandMerchant(product.title)
        val existingDomains = candidateBuilder.mapNotNull { it.merchantDomain }
            .map { it.lowercase(Locale.ROOT).removePrefix("www.") }.toSet()
        val manufacturerCandidate = manufacturer?.takeIf { (_, domain) ->
            val normalized = domain.lowercase(Locale.ROOT).removePrefix("www.")
            normalized !in existingDomains &&
                existingDomains.none { it.endsWith(".$normalized") || normalized.endsWith(".$it") }
        }?.let { (brandName, domain) ->
            FreeArchiveCandidate(
                title = product.title,
                storeName = brandName,
                directUrl = null,
                merchantDomain = domain
            )
        }

        // Keep every existing candidate first. Only when the selected product has fewer
        // retailer destinations than the existing budget, fill the unused slots with known
        // merchants that commonly sell this product family. No extra slots or retries are added.
        val selectedArchiveDomains = existingDomains.toMutableSet()
        manufacturerCandidate?.merchantDomain?.let {
            selectedArchiveDomains += it.lowercase(Locale.ROOT).removePrefix("www.")
        }
        val supplementalRetailerCandidates = historicalRetailerFallbackStores(product.category, product.title)
            .mapNotNull { store ->
                val domain = waybackPriceHistoryClient.resolveMerchantDomain(
                    storeName = store.name,
                    sourceUrl = store.officialUrl,
                    knownStoreDomain = store.domain
                ) ?: return@mapNotNull null
                val normalizedDomain = domain.lowercase(Locale.ROOT).removePrefix("www.")
                val alreadyCovered = selectedArchiveDomains.any { existing ->
                    normalizedDomain == existing ||
                        normalizedDomain.endsWith(".$existing") ||
                        existing.endsWith(".$normalizedDomain")
                }
                if (alreadyCovered) return@mapNotNull null
                selectedArchiveDomains += normalizedDomain
                FreeArchiveCandidate(
                    title = product.title,
                    storeName = store.name,
                    directUrl = null,
                    merchantDomain = domain
                )
            }
        val retailerCandidates = (
            candidateBuilder.sortedWith(
                compareBy<FreeArchiveCandidate> { if (it.directUrl != null) 0 else 1 }
                    .thenBy { it.merchantDomain.orEmpty() }
            ) + supplementalRetailerCandidates
        ).distinctBy {
            // Search one page per retailer: a seller's alternate URL must not consume another slot.
            it.title.trim().lowercase(Locale.ROOT) + "|" + (it.merchantDomain ?: it.directUrl ?: "unknown")
        }
        // Preserve the established priority: use the saved retailer destinations first and
        // reserve the final slot for a distinct, recognized manufacturer shop. For PlayStation,
        // resolve that slot to PlayStation Direct rather than guessing a product path on sony.es.
        val retailerBudget = if (manufacturerCandidate != null) {
            MAX_ARCHIVE_PAGES_PER_SEARCH - 1
        } else {
            MAX_ARCHIVE_PAGES_PER_SEARCH
        }
        val orderedCandidates = (retailerCandidates.take(retailerBudget) +
            listOfNotNull(manufacturerCandidate)).distinctBy {
            it.title.trim().lowercase(Locale.ROOT) + "|" +
                (it.merchantDomain ?: it.directUrl ?: "unknown")
        }

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
                freeHistoryStatus = "No se encontró una URL válida para el archivo histórico. Se intentará buscar el precio actual por separado si SerpApi tiene saldo.",
                freeHistoryProductId = product.id
            )
            finishWaybackDiagnostic(product.id, WaybackTraceStatus.FAILED, "No se generó ninguna URL válida para consultar el archivo.")
            // The historical archive has no safe URL, but the product title is still enough
            // to attempt one separately budgeted live-price lookup. This does not call Wayback.
            viewModelScope.launch {
                runLivePriceFallbackAfterArchiveFailure(
                    product = product,
                    archiveFailureReason = "No se pudo identificar una URL archivada válida"
                )
            }
            return
        }

        reportWaybackDiagnostic(
            product.id, 4, WaybackTraceStatus.PASSED,
            "Precontrol completado: no hay pausa global activa. Esta búsqueda manual vuelve a revisar fechas aunque exista caché por producto; se mantienen el límite global y el ritmo de peticiones."
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
            targetProductId = product.id,
            forceRefresh = true,
            liveFallbackProduct = product
        )
    }

    /**
     * Archive lookup and current-price discovery are separate jobs. If the free archive has no
     * verified point (or is paused by HTTP 429), run one independently budgeted live lookup rather
     * than silently ending the user's "find price" action. This never calls Wayback or edits its
     * cache/history; only trusted live retailer offers can be saved.
     */
    private suspend fun runLivePriceFallbackAfterArchiveFailure(
        product: Product,
        archiveFailureReason: String
    ) {
        val productId = product.id.trim()
        if (productId.isBlank()) return
        if (!inFlightArchiveFailureFallbacks.add(productId)) {
            val message = "La búsqueda del precio actual de «${product.title}» ya está en curso; no se lanzará una segunda consulta."
            _uiState.update { current -> current.copy(
                freeHistoryProductId = productId,
                freeHistoryStatus = message,
                serpApiActionStatus = message
            ) }
            return
        }
        val attemptKey = "archive_live_price_fallback_attempt_$productId"
        val successKey = "archive_live_price_fallback_success_$productId"
        try {
            val now = System.currentTimeMillis()
            val previousAttempt = priceRefreshPreferences.getLong(attemptKey, 0L)
            val previousSuccess = priceRefreshPreferences.getLong(successKey, 0L)
            val cooldownRemaining = archiveLiveFallbackCooldownRemainingMs(
                nowMillis = now,
                previousAttemptAtMillis = previousAttempt,
                previousSuccessAtMillis = previousSuccess
            )
            if (cooldownRemaining > 0L) {
                val minutes = ((cooldownRemaining + 59_999L) / 60_000L).coerceAtLeast(1L)
                val wasSuccessful = previousSuccess > 0L &&
                    now >= previousSuccess &&
                    now - previousSuccess < ARCHIVE_LIVE_FALLBACK_SUCCESS_COOLDOWN_MS
                val message = if (wasSuccessful) {
                    "El histórico gratuito no dio un precio histórico verificable ($archiveFailureReason). " +
                        "La búsqueda del precio actual sí guardó ofertas verificadas recientemente; para no gastar otra " +
                        "consulta de pago, se podrá repetir esa búsqueda en unas $minutes min. El histórico no se ha completado."
                } else {
                    "El histórico gratuito no dio un precio verificable ($archiveFailureReason). " +
                        "La última búsqueda alternativa no obtuvo un resultado confirmado; se podrá reintentar " +
                        "en unas $minutes min. No se repetirá una consulta de pago en bucle."
                }
                _uiState.update { current -> current.copy(
                    isSearchingFreeHistory = false,
                    freeHistoryProductId = productId,
                    freeHistoryStatus = message,
                    serpApiActionStatus = message
                ) }
                showSnackbar(message)
                return
            }

            if (hasConflictingSerpApiWork(_uiState.value)) {
                val queued = "El histórico gratuito no dio un precio verificable ($archiveFailureReason). " +
                    "La búsqueda del precio actual queda en cola hasta que termine la consulta activa; no se perderá el evento."
                _uiState.update { current -> current.copy(
                    freeHistoryProductId = productId,
                    freeHistoryStatus = queued,
                    serpApiActionStatus = queued
                ) }
                val becameIdle = withTimeoutOrNull(ARCHIVE_LIVE_FALLBACK_WAIT_FOR_IDLE_MS) {
                    _uiState.first { !hasConflictingSerpApiWork(it) }
                }
                if (becameIdle == null) {
                    val message = "La búsqueda del precio actual quedó pendiente porque otra consulta sigue activa tras esperar 60 segundos. " +
                        "No se ha gastado una búsqueda duplicada; vuelve a intentarlo cuando termine la consulta activa."
                    _uiState.update { current -> current.copy(
                        isSearchingFreeHistory = false,
                        freeHistoryProductId = productId,
                        freeHistoryStatus = message,
                        serpApiActionStatus = message
                    ) }
                    showSnackbar(message)
                    return
                }
            }

            val token = withContext(Dispatchers.IO) { serpApiTokenStore.read() }
            if (token.isNullOrBlank()) {
                val message = "El histórico gratuito no encontró precio verificable ($archiveFailureReason). " +
                    "No se pudo activar la búsqueda del precio actual porque falta la clave de SerpApi en Ajustes."
                _uiState.update { current -> current.copy(
                    isSearchingFreeHistory = false,
                    freeHistoryProductId = productId,
                    freeHistoryStatus = message,
                    serpApiActionStatus = message
                ) }
                showSnackbar(message)
                return
            }

            val accountInfo = readAccountInfo(token)
            if (accountInfo == null) {
                val message = "El histórico gratuito falló ($archiveFailureReason) y no se pudo comprobar el saldo de SerpApi. " +
                    "No se lanzó la búsqueda alternativa ni se consumió cuota."
                _uiState.update { current -> current.copy(
                    isSearchingFreeHistory = false,
                    freeHistoryProductId = productId,
                    freeHistoryStatus = message,
                    serpApiActionStatus = message
                ) }
                showSnackbar(message)
                return
            }
            val blockedAccount = accountStatusBlockMessage(accountInfo)
            if (blockedAccount != null || accountCapacity(accountInfo) < 1) {
                val message = blockedAccount ?: quotaBlockMessage(accountInfo)
                val combined = "El histórico gratuito falló ($archiveFailureReason). " +
                    "No se ha lanzado la búsqueda del precio actual: $message"
                _uiState.update { current -> current.copy(
                    isSearchingFreeHistory = false,
                    freeHistoryProductId = productId,
                    freeHistoryStatus = combined,
                    serpApiActionStatus = combined,
                    serpApiAccountStatus = quotaMessage(accountInfo)
                ) }
                showSnackbar(combined)
                return
            }

            // Persist the cooldown only after the API key/account/quota are validated and immediately
            // before the one paid lookup, so missing credentials or an empty quota can be retried later.
            val attemptStartedAt = System.currentTimeMillis()
            if (!priceRefreshPreferences.edit().putLong(attemptKey, attemptStartedAt).commit()) {
                val message = "No se pudo registrar el límite de seguridad de la búsqueda alternativa. " +
                    "No se ha lanzado otra consulta ni gastado cuota."
                _uiState.update { current -> current.copy(
                    isSearchingFreeHistory = false,
                    freeHistoryProductId = productId,
                    freeHistoryStatus = message,
                    serpApiActionStatus = message
                ) }
                showSnackbar(message)
                return
            }

            val progress = "El histórico gratuito no encontró precio verificable ($archiveFailureReason). " +
                "Se activa ahora una búsqueda independiente del precio actual de «${product.title}» " +
                "con una consulta SerpApi; Wayback permanece en pausa."
            _uiState.update { current -> current.copy(
                isSearchingFreeHistory = true,
                freeHistoryProductId = productId,
                freeHistoryStatus = progress,
                serpApiActionStatus = progress,
                serpApiAccountStatus = quotaMessage(accountInfo)
            ) }

            val googleProductId = sequenceOf(
                product.googleProductId,
                extractGoogleShoppingProductId(product.storeUrl),
                product.offers.firstNotNullOfOrNull { extractGoogleShoppingProductId(it.url) }
            ).mapNotNull { candidate ->
                candidate?.trim()?.takeIf { it.matches(Regex("[0-9]{5,}")) }
            }.firstOrNull()

            // Retain diagnostic counts from the name-based fallback. Previously the report was
            // discarded, so "no offer" could mean zero provider results, rejected prices/links,
            // or offers rejected later by the catalogue trust filter.
            var fallbackShoppingReport: SerpApiShoppingSearchReport? = null
            val details = withTimeoutOrNull(25_000L) {
                if (googleProductId != null) {
                    serpApiClient.searchGoogleProductDetails(
                        token = token,
                        productId = googleProductId,
                        expectedTitle = product.title,
                        observedAt = attemptStartedAt
                    )
                } else {
                    val report = serpApiClient.searchOnlineWithDiagnostics(token, product.title)
                    fallbackShoppingReport = report
                    com.example.data.remote.SerpApiGoogleProductDetails(
                        offers = report.results,
                        priceInsights = emptyList(),
                        priceHistoryAvailable = false
                    )
                }
            } ?: throw SerpApiException("La búsqueda del precio actual agotó los 25 segundos de espera.")

            // Keep this as a current-price refresh only. We deliberately do not import archive
            // points or replace the historical engine's validation/retention rules here.
            val safeOffers = repository.filterProductComparisonOffers(details.offers, product.title)
            val savedOffers = if (safeOffers.isNotEmpty()) {
                repository.recordProductComparisonOffers(productId, product.title, safeOffers)
            } else 0
            if (safeOffers.isNotEmpty()) {
                priceRefreshPreferences.edit()
                    .putLong(successKey, System.currentTimeMillis())
                    .apply()
            }

            if (savedOffers > 0) {
                val refreshed = withTimeoutOrNull(6_000L) {
                    repository.allProductsStream.first { products ->
                        products.any { candidate ->
                            candidate.id == productId && (candidate.priceObservedAt ?: 0L) >= attemptStartedAt
                        }
                    }.first { it.id == productId }
                }
                if (refreshed != null && _uiState.value.selectedProduct?.id == productId) {
                    _uiState.update { current -> current.copy(selectedProduct = refreshed) }
                }
            }

            val after = runCatching { serpApiClient.getAccountInfo(token) }.getOrNull()
            val fallbackSearchDiagnosis = fallbackShoppingReport?.let { report ->
                " Diagnóstico Google Shopping: resultados recibidos=${report.rawResultCount}; " +
                    "ofertas que superaron el filtro del proveedor=${report.results.size}; " +
                    "descartadas por relevancia=${report.rejectedAsIrrelevant}; " +
                    "sin precio/moneda EUR verificable=${report.rejectedForPriceOrCurrency}; " +
                    "sin enlace utilizable=${report.rejectedForLink}; otros datos inválidos=${report.rejectedOther}; " +
                    "descartadas por la validación final del catálogo=${(report.results.size - safeOffers.size).coerceAtLeast(0)}."
            } ?: if (googleProductId != null) {
                " Diagnóstico: se consultó un ID de producto de Google Shopping; ninguna oferta superó todas " +
                    "las validaciones de producto, precio EUR y enlace directo."
            } else ""
            val resultMessage = when {
                safeOffers.isNotEmpty() -> "Respaldo automático completado: se encontraron ${safeOffers.size} oferta(s) " +
                    "actuales para «${product.title}» y se guardaron $savedOffers observación(es) nuevas o actualizadas. " +
                    "El histórico de Wayback no se ha modificado."
                else -> "Se lanzó la búsqueda alternativa del precio actual de «${product.title}», pero no apareció " +
                    "ninguna oferta con precio y enlace directo verificables. No se ha inventado un precio ni se ha " +
                    "guardado un resultado de búsqueda genérico.$fallbackSearchDiagnosis El histórico de Wayback no se ha modificado."
            }
            _uiState.update { current -> current.copy(
                isSearchingFreeHistory = false,
                freeHistoryProductId = productId,
                freeHistoryStatus = resultMessage,
                serpApiActionStatus = resultMessage,
                serpApiAccountStatus = after?.let(::quotaMessage) ?: quotaMessage(accountInfo)
            ) }
            showSnackbar(resultMessage)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SerpApiException) {
            val message = "El histórico gratuito falló ($archiveFailureReason) y se intentó la búsqueda del precio actual, " +
                "pero SerpApi respondió: ${error.message.orEmpty()}. No se han alterado los datos históricos."
            _uiState.update { current -> current.copy(
                isSearchingFreeHistory = false,
                freeHistoryProductId = productId,
                freeHistoryStatus = message,
                serpApiActionStatus = message
            ) }
            showSnackbar(message)
        } catch (_: Exception) {
            val message = "El histórico gratuito falló ($archiveFailureReason) y la búsqueda del precio actual no se completó. " +
                "No se ha guardado un precio sin verificar; puedes revisar el estado de SerpApi e intentarlo más adelante."
            _uiState.update { current -> current.copy(
                isSearchingFreeHistory = false,
                freeHistoryProductId = productId,
                freeHistoryStatus = message,
                serpApiActionStatus = message
            ) }
            showSnackbar(message)
        } finally {
            inFlightArchiveFailureFallbacks.remove(productId)
            _uiState.update { current -> current.copy(isSearchingFreeHistory = false) }
        }
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

    /**
     * Explain only results that are actually hidden from the live list.
     *
     * The repository's stricter diagnostics also identify offers that are visible but not suitable
     * for trusted price history (e.g. a Google Shopping query fallback or a price above the
     * historical ceiling). Those must not be misleadingly presented as discarded products.
     */
    private fun diagnoseLiveSearchHiddenOffers(
        candidates: List<SerpApiShoppingResult>,
        visibleResults: List<SerpApiShoppingResult>,
        query: String
    ): List<RejectedOnlineOffer> {
        val repositoryRejected = repository.diagnoseOnlineSearchRejections(candidates, query)
        fun parseDiagnosticPrice(label: String): Double? =
            Regex("""\d+(?:[.,]\d+)?""").find(label)
                ?.value
                ?.replace(',', '.')
                ?.toDoubleOrNull()

        return repositoryRejected.filterNot { rejected ->
            val rejectedPrice = parseDiagnosticPrice(rejected.priceLabel)
            visibleResults.any { visible ->
                visible.title.equals(rejected.title, ignoreCase = true) &&
                    (rejectedPrice == null || kotlin.math.abs(visible.price - rejectedPrice) < 0.011)
            }
        }
    }

    /**
     * Historical trust rules are intentionally stricter than live-search visibility. State this
     * distinction plainly rather than implying the remaining priced offers disappeared.
     */
    private fun liveHistoryEligibilityNotice(
        visibleCount: Int,
        historyCandidateCount: Int
    ): String =
        if (visibleCount > historyCandidateCount) {
            "$visibleCount ofertas se mantienen en la búsqueda en vivo; $historyCandidateCount pasan los filtros previos del histórico. Las demás siguen visibles y no se usan como precios históricos confirmados."
        } else {
            ""
        }

    private fun onlineCatalogueNotice(insertedProducts: Int): String =
        if (insertedProducts > 0) {
            "$insertedProducts ficha(s) de catálogo guardada(s) o recuperada(s) en Mercado. Los importes sin enlace directo quedan marcados como no verificados y no se añaden al histórico."
        } else {
            ""
        }

    /**
     * Return provisional market fichas relevant to this search but absent from the provider result
     * set. A broad brand lookup may surface related products; an exact multi-word/model lookup must
     * share enough identity terms to avoid mixing in a different variant of the same brand.
     */
    private suspend fun findRelatedUnverifiedMarketCandidates(
        query: String,
        visibleOffers: List<SerpApiShoppingResult>
    ): List<Product> = withContext(Dispatchers.IO) {
        val queryText = query.trim()
        if (queryText.length < 2) return@withContext emptyList()
        val representedIdentityKeys = visibleOffers
            .map { onlineProductIdentityKey(it.title) }
            .toSet()
        val significantTokens = queryText.lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.length > 1 && it !in setOf("de", "del", "la", "el", "para", "con", "and", "the") }
        val minimumScore = if (significantTokens.size <= 1) 5 else 10

        repository.allProductsStream.first()
            .asSequence()
            .filter { it.isUnverifiedLiveListing }
            .filter { onlineProductIdentityKey(it.title) !in representedIdentityKeys }
            .mapNotNull { product ->
                // An explicit capacity in the query is a hard discriminator. Do not
                // show a sibling device whose title omits or contradicts that storage option.
                if (!hasMatchingExplicitStorageCapacity(queryText, product.title)) return@mapNotNull null
                // Exact Dyson model searches must not pull sibling models from Mercado just
                // because the live result set happens to be empty for that model.
                if (queryNamesSpecificDysonHairToolModel(queryText) &&
                    !returnedTitleMatchesSpecificDysonHairToolModel(queryText, product.title)
                ) return@mapNotNull null
                val broadDysonMatch = isBroadDysonHairToolsQueryMatch(queryText, product.title) &&
                    returnedTitleMatchesSpecificDysonHairToolModel(queryText, product.title)
                val score = onlineProductRelevanceScore(queryText, product.title)
                if (broadDysonMatch || score >= minimumScore) {
                    (product to if (broadDysonMatch) score + 1000 else score)
                } else {
                    null
                }
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { onlineProductIdentityKey(it.title) }
            .take(MAX_RELATED_UNVERIFIED_MARKET_CANDIDATES)
            .toList()
    }

    /**
     * Matching saved unverified fichas is auxiliary UI enrichment. It must not hide live offers
     * or consume the whole search timeout if Room is slow or fails.
     */
    private suspend fun safeRelatedMarketCandidates(
        query: String,
        visibleOffers: List<SerpApiShoppingResult>
    ): List<Product> = try {
        withTimeoutOrNull(3_000L) {
            findRelatedUnverifiedMarketCandidates(query, visibleOffers)
        } ?: emptyList()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w("PriceTrackerViewModel", "Could not load related provisional Mercado fichas.", error)
        emptyList()
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
            onlineSearchRelatedMarketCandidates = if (keepPreviousSnapshot) current.onlineSearchRelatedMarketCandidates else emptyList(),
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
                        val (visibleCachedResults, cachedHistoryCandidates, cachedDiscarded) =
                            withContext(Dispatchers.Default) {
                                // A result can be suitable for showing now without being safe enough
                                // to write into verified price history. Don't hide it just because its
                                // seller URL or price evidence fails the stricter history policy.
                                val visible = repository.filterVisibleLiveSearchResults(cachedResults, query)
                                val historyCandidates = repository.filterOnlineSearchResults(visible, query)
                                    .filterNot { it.isSearchFallbackDestination }
                                val discarded = (cachedReport.discardedOffers +
                                    diagnoseLiveSearchHiddenOffers(cachedResults, visible, query))
                                    .distinctBy { it.provider + "|" + it.title + "|" + it.priceLabel + "|" + it.reason }
                                Triple(visible, historyCandidates, discarded)
                            }
                        onlineDiscardedCache[onlineSearchCacheKey(provider, query)] = cachedDiscarded
                        val base = if (visibleCachedResults.isEmpty()) {
                            cachedReport.emptyResultMessage(query)
                        } else {
                            onlineSearchResultMessage(visibleCachedResults, provider, query)
                        }
                        val cacheNotice = "Resultados recuperados de caché: consulta capturada ${formatOnlineSnapshotAge(cached.savedAt)}. No se ha consumido otra búsqueda; los precios pueden haber cambiado."
                        val savedObservations = if (cachedHistoryCandidates.isNotEmpty()) {
                            try {
                                withContext(Dispatchers.IO) {
                                    repository.recordOnlineSearchResults(cachedHistoryCandidates, provider.name, query)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Log.w("PriceTrackerViewModel", "Could not save cached online-search prices.", error)
                                // A Room write failure must not hide a valid cached result list.
                                // Opening a result retries the validated persistence path.
                                0
                            }
                        } else 0
                        val newCatalogueProducts = try {
                            withContext(Dispatchers.IO) {
                                repository.ensureVisibleOnlineSearchProducts(visibleCachedResults, query)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Log.w("PriceTrackerViewModel", "Could not save online-search candidates from cache.", error)
                            // The live result view must remain usable even if Room cannot save a candidate.
                            // A card tap will retry the same safe, non-destructive persistence path.
                            0
                        }
                        val catalogueNotice = onlineCatalogueNotice(newCatalogueProducts)
                        val historyNotice = if (cachedHistoryCandidates.isNotEmpty()) {
                            onlineHistoryNotice(savedObservations, cachedHistoryCandidates.size)
                        } else ""
                        val eligibilityNotice = liveHistoryEligibilityNotice(
                            visibleCachedResults.size,
                            cachedHistoryCandidates.size
                        )
                        val filterNotice = onlineFilterNotice(cachedDiscarded)
                        val status = listOf(
                            base, queryOptimizationNotice, cacheNotice, filterNotice,
                            historyNotice, catalogueNotice, eligibilityNotice
                        ).map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n\n")
                        if (!isOnlineSearchStillCurrent(query, provider)) return@withTimeout
                        val relatedMarketCandidates = safeRelatedMarketCandidates(query, visibleCachedResults)
                        if (!isOnlineSearchStillCurrent(query, provider)) return@withTimeout
                        lastDisplayedSearchIdentity = onlineSearchIdentity(provider, query)
                        _uiState.value = _uiState.value.copy(
                            onlineSearchResults = visibleCachedResults,
                            onlineSearchRelatedMarketCandidates = relatedMarketCandidates,
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

                    // Keep display classification off Android's UI thread, but do not let
                    // history trust rules hide real priced listings from the live results.
                    val (results, historyCandidates, discarded) = withContext(Dispatchers.Default) {
                        val rawResults = providerResults
                        val visible = repository.filterVisibleLiveSearchResults(rawResults, query)
                        val eligibleForHistory = repository.filterOnlineSearchResults(visible, query)
                            .filterNot { it.isSearchFallbackDestination }
                        val repositoryDiscarded = diagnoseLiveSearchHiddenOffers(rawResults, visible, query)
                        val rejected = (providerDiscardedOffers + repositoryDiscarded)
                            .distinctBy { "${it.provider}|${it.title}|${it.priceLabel}|${it.reason}" }
                        Triple(visible, eligibleForHistory, rejected)
                    }
                    onlineDiscardedCache[onlineSearchCacheKey(provider, query)] = discarded
                    val savedObservations = if (historyCandidates.isNotEmpty()) {
                        try {
                            withContext(Dispatchers.IO) {
                                repository.recordOnlineSearchResults(historyCandidates, provider.name, query)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Log.w("PriceTrackerViewModel", "Could not save live online-search prices.", error)
                            // Keep presenting the newly returned offers if Room temporarily fails.
                            // The detail-sheet tap retries persistence before resolving the product.
                            0
                        }
                    } else 0
                    val newCatalogueProducts = try {
                        withContext(Dispatchers.IO) {
                            repository.ensureVisibleOnlineSearchProducts(results, query)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w("PriceTrackerViewModel", "Could not save online-search candidates from live results.", error)
                        // Never discard valid live results just because catalogue persistence failed.
                        // Tapping a visible result attempts to create/resume its ficha separately.
                        0
                    }

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
                    val historyNote = if (historyCandidates.isNotEmpty()) {
                        onlineHistoryNotice(savedObservations, historyCandidates.size)
                    } else ""
                    val catalogueNote = onlineCatalogueNotice(newCatalogueProducts)
                    val eligibilityNotice = liveHistoryEligibilityNotice(results.size, historyCandidates.size)
                    val freshnessNote = "Búsqueda recién realizada; la antigüedad indica cuándo se capturaron estos resultados, no una comprobación en tiempo real del precio de la tienda."
                    val message = listOf(base, queryOptimizationNotice, freshnessNote, coverageNote, quotaNote, gameSupplementNote, filterNote, historyNote, catalogueNote, eligibilityNotice)
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                    if (!isOnlineSearchStillCurrent(query, provider)) return@withTimeout
                    val relatedMarketCandidates = safeRelatedMarketCandidates(query, results)
                    if (!isOnlineSearchStillCurrent(query, provider)) return@withTimeout
                    lastDisplayedSearchIdentity = onlineSearchIdentity(provider, query)
                    _uiState.value = _uiState.value.copy(
                        onlineSearchResults = results,
                        onlineSearchRelatedMarketCandidates = relatedMarketCandidates,
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
        targetProductId: String? = null,
        forceRefresh: Boolean = false,
        liveFallbackProduct: Product? = null
    ) {
        if (candidates.isEmpty()) {
            appendArchiveNotice(
                query,
                provider,
                "No hay un enlace directo ni un dominio de comercio identificable para buscar capturas gratuitas. No se han inventado precios previos.",
                targetProductId
            )
            if (liveFallbackProduct != null) {
                viewModelScope.launch {
                    runLivePriceFallbackAfterArchiveFailure(
                        product = liveFallbackProduct,
                        archiveFailureReason = "no se encontraron destinos válidos para el archivo histórico"
                    )
                }
            }
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
            // An extracted quote is not enough to suppress the live-price fallback: only a new
            // durable point or a confirmed valid duplicate proves the archive result was usable.
            var selectedProductHistoricalPointPersisted = false
            try {
                var savedCount = 0
                // Aggregate per-capture Room write failures outside the inner candidate loop;
                // the terminal diagnostic needs the whole search's result, not a loop-local flag.
                var overallPersistenceCompleted = true
                var crossSavedVariantObservationCount = 0
                var confirmedPrimaryDuplicateCount = 0
                var confirmedVariantDuplicateCount = 0
                var rejectedArchiveQuoteCount = 0
                var duplicateArchiveInputCount = 0
                val archiveQuoteRejectionReasons = linkedMapOf<String, Int>()
                val invalidArchivedRowIds = linkedSetOf<Long>()
                val stagedDiscoveryObservationsCount = java.util.concurrent.atomic.AtomicInteger(0)
                var checkedCount = 0
                var foundPrimaryCount = 0
                var foundSecondaryCount = 0
                var capturesFoundCount = 0
                var selectedProductRefreshed = false
                var selectedProductRefreshNeeded = false
                var selectedProductRefreshTitle: String? = null
                var mementoAttempted = false
                // Search-wide circuit breaker: one unhealthy direct CDX route must not be
                // followed by another candidate's merchant-domain requests in the same run.
                var skipDomainFallbacksForSearch = false
                var availabilityOnlyFallbacksUsed = 0
                var availabilityOnlyFallbackAfterCdxTimeoutAvailable = false
                val searchCdxTimeoutDiagnostics = linkedSetOf<String>()
                var successfulLookupsCount = 0
                var requestFailedCount = 0
                var skippedCount = 0
                var timedOutCount = 0
                val diagnosticCodes = linkedSetOf<String>()
                val archiveDates = mutableListOf<Long>()
                val countedArchiveQuoteKeys = mutableSetOf<String>()
                val processedArchiveQuoteKeys = mutableSetOf<String>()
                fun archiveQuoteIdentity(candidate: FreeArchiveCandidate, quote: ArchivedPriceQuote): String {
                    val productScope = quote.targetProductId
                        ?: targetProductId
                        ?: onlineProductIdentityKey(candidate.title)
                    return listOf(
                        productScope,
                        onlineProductIdentityKey(quote.productTitle),
                        quote.originalUrl,
                        quote.observedAt.toString(),
                        quote.price.toString(),
                        quote.currency.uppercase(Locale.ROOT),
                        quote.productUrl.orEmpty(),
                        quote.isDiscoveryCandidate.toString()
                    ).joinToString("|")
                }
                suspend fun persistCandidateQuotes(
                    candidate: FreeArchiveCandidate,
                    incomingQuotes: List<ArchivedPriceQuote>
                ) {
                    val uniqueQuotes = incomingQuotes.distinctBy { archiveQuoteIdentity(candidate, it) }
                    if (uniqueQuotes.isEmpty()) return
                    for (quote in uniqueQuotes) {
                        if (countedArchiveQuoteKeys.add(archiveQuoteIdentity(candidate, quote))) {
                            when {
                                quote.isDiscoveryCandidate -> Unit
                                quote.targetProductId.isNullOrBlank() || quote.targetProductId == targetProductId -> {
                                    foundPrimaryCount++
                                    // Only the selected product's observations determine the
                                    // "capturas desde" date shown in its own history message.
                                    archiveDates.add(quote.observedAt)
                                }
                                else -> foundSecondaryCount++
                            }
                        }
                    }
                    if (diagnosticProductId != null) {
                        reportWaybackDiagnostic(
                            diagnosticProductId, 19, WaybackTraceStatus.RUNNING,
                            "Se recibió una captura con ${uniqueQuotes.size} precio(s) verificado(s); se guardan antes de descargar la siguiente fecha."
                        )
                    }
                    val toPersist = uniqueQuotes.filter {
                        archiveQuoteIdentity(candidate, it) !in processedArchiveQuoteKeys
                    }
                    var persistenceCompleted = true
                    try {
                        for ((archiveUrl, urlQuotes) in toPersist.groupBy { it.originalUrl }) {
                            // Route discoveries separately, but don't write them yet. The
                            // primary and known-catalogue quotes below are persisted first so
                            // secondary Room work cannot delay the result the user asked for.
                            val discoveryQuotes = urlQuotes.filter { it.isDiscoveryCandidate }
                            val knownProductQuotes = urlQuotes.filterNot { it.isDiscoveryCandidate }
                            val primaryQuotes = knownProductQuotes.filter {
                                it.targetProductId.isNullOrBlank() || it.targetProductId == targetProductId
                            }
                            if (primaryQuotes.isNotEmpty()) {
                                val outcome = repository.recordArchivedPriceHistoryDetailed(
                                    expectedTitle = candidate.title,
                                    originalUrl = archiveUrl,
                                    storeName = candidate.storeName,
                                    quotes = primaryQuotes.map { it.copy(targetProductId = null) },
                                    targetProductId = targetProductId
                                )
                                savedCount += outcome.insertedPoints
                                confirmedPrimaryDuplicateCount += outcome.confirmedExistingDuplicates
                                if (outcome.insertedPoints > 0 || outcome.confirmedExistingDuplicates > 0) {
                                    selectedProductHistoricalPointPersisted = true
                                }
                                invalidArchivedRowIds.addAll(outcome.invalidExistingRowIds)
                                rejectedArchiveQuoteCount += outcome.rejectedQuotes
                                outcome.rejectionReasons.forEach { (reason, count) ->
                                    archiveQuoteRejectionReasons[reason] =
                                        (archiveQuoteRejectionReasons[reason] ?: 0) + count
                                }
                                duplicateArchiveInputCount += outcome.duplicateQuotesInInput
                            }
                            val secondaryQuotes = knownProductQuotes.filter {
                                !it.targetProductId.isNullOrBlank() && it.targetProductId != targetProductId
                            }
                            for ((secondaryProductId, variantQuotes) in secondaryQuotes.groupBy { it.targetProductId!! }) {
                                val outcome = repository.recordArchivedPriceHistoryDetailed(
                                    expectedTitle = variantQuotes.first().productTitle,
                                    originalUrl = archiveUrl,
                                    storeName = candidate.storeName,
                                    quotes = variantQuotes.map { it.copy(targetProductId = null) },
                                    targetProductId = secondaryProductId
                                )
                                crossSavedVariantObservationCount += outcome.insertedPoints
                                confirmedVariantDuplicateCount += outcome.confirmedExistingDuplicates
                                invalidArchivedRowIds.addAll(outcome.invalidExistingRowIds)
                                rejectedArchiveQuoteCount += outcome.rejectedQuotes
                                outcome.rejectionReasons.forEach { (reason, count) ->
                                    archiveQuoteRejectionReasons[reason] =
                                        (archiveQuoteRejectionReasons[reason] ?: 0) + count
                                }
                                duplicateArchiveInputCount += outcome.duplicateQuotesInInput
                            }

                            val discoveryKeysToSchedule = discoveryQuotes.filter { quote ->
                                scheduledDiscoveryQuoteKeys.add(archiveQuoteIdentity(candidate, quote))
                            }
                            if (discoveryKeysToSchedule.isNotEmpty()) {
                                val discoveryKeys = discoveryKeysToSchedule
                                    .map { archiveQuoteIdentity(candidate, it) }
                                    .toSet()
                                viewModelScope.launch(Dispatchers.IO) {
                                    var stagedInBatch = 0
                                    var failuresInBatch = 0
                                    try {
                                        // Serialize the Room writes off the search path. A large
                                        // secondary catalogue card must not block the primary quote
                                        // or flood the UI with one snackbar per snapshot.
                                        for (quote in discoveryKeysToSchedule) {
                                            var attempt = 0
                                            var completed = false
                                            while (attempt < 2 && !completed) {
                                                try {
                                                    if (productDiscoveryRepository.stageArchivedQuote(
                                                            quote = quote,
                                                            fallbackStoreName = candidate.storeName
                                                        )
                                                    ) {
                                                        stagedDiscoveryObservationsCount.incrementAndGet()
                                                        stagedInBatch++
                                                    }
                                                    completed = true
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (_: Exception) {
                                                    attempt++
                                                    if (attempt < 2) kotlinx.coroutines.delay(250L)
                                                    else failuresInBatch++
                                                }
                                            }
                                        }
                                        if (stagedInBatch > 0) {
                                            showSnackbar(
                                                "Radar: $stagedInBatch observación(es) de productos nuevos guardadas en cuarentena; no se han añadido a Mercado."
                                            )
                                        }
                                        if (failuresInBatch > 0) {
                                            showSnackbar(
                                                "No se pudieron guardar $failuresInBatch observación(es) en Radar. El precio principal ya se ha conservado."
                                            )
                                        }
                                    } finally {
                                        // A later capture/replay may retry if the asynchronous
                                        // Room write failed; the primary search never waits for it.
                                        scheduledDiscoveryQuoteKeys.removeAll(discoveryKeys)
                                    }
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        persistenceCompleted = false
                        overallPersistenceCompleted = false
                        diagnosticCodes.add("ARCHIVE_INCREMENTAL_SAVE_FAILED")
                        if (diagnosticProductId != null) {
                            reportWaybackDiagnostic(
                                diagnosticProductId, 19, WaybackTraceStatus.FAILED,
                                "No se pudo guardar una observación histórica durante la descarga; se conservará para el intento final si la operación continúa."
                            )
                        }
                    }
                    // Never mark a quote as processed after a failed write: the final pass must
                    // be allowed to retry persistence for a verified quote retained in memory.
                    if (persistenceCompleted) {
                        processedArchiveQuoteKeys.addAll(toPersist.map { archiveQuoteIdentity(candidate, it) })
                    }
                    val hasPrimaryProductQuote = uniqueQuotes.any {
                        !it.isDiscoveryCandidate &&
                            (it.targetProductId.isNullOrBlank() || it.targetProductId == targetProductId)
                    }
                    if (hasPrimaryProductQuote) {
                        // Room writes are already durable here. Refresh the selected catalogue
                        // card once after all bounded routes finish, not once per capture; the
                        // repeated Flow wait used to consume most of the candidate timeout.
                        selectedProductRefreshNeeded = true
                        selectedProductRefreshTitle = candidate.title
                    }
                }
                // Use persisted catalogue titles to harvest other structured offers from the same
                // archived HTML. Ambiguous duplicate listings are rejected by the parser.
                val knownProductTitlesById = withTimeoutOrNull(4_000L) {
                    repository.allProductsStream.first().associate { it.id to it.title }
                }.orEmpty()
                for (candidate in candidates) {
                    // When CDX is unhealthy, keep a small Availability-only recovery path.
                    // It does not repeat index scans and remains subject to the shared 429 gate.
                    val archiveBackendCoolingDown =
                        waybackPriceHistoryClient.activeBackendUnavailableUntil() != null
                    var availabilityOnlyFallbackForCandidate =
                        shouldUseAvailabilityOnlyFallbackAfterCdxTimeout(
                            searchCircuitOpen = skipDomainFallbacksForSearch || archiveBackendCoolingDown,
                            timeoutFallbackAvailable =
                                availabilityOnlyFallbackAfterCdxTimeoutAvailable || archiveBackendCoolingDown,
                            rateLimited = waybackPriceHistoryClient.activeRateLimitUntil() != null,
                            allDirectSnapshotsTimedOut = false,
                            directLookupTimedOut = false,
                            hasMerchantDomain = candidate.merchantDomain != null,
                            usedFallbacks = availabilityOnlyFallbacksUsed,
                            maxFallbacks = MAX_AVAILABILITY_ONLY_FALLBACKS_AFTER_CDX_TIMEOUT
                        )
                    // Decide whether a route completed the job from newly inserted Room points,
                    // not from the number of captures returned by CDX. Repeated dates must not
                    // suppress independent retailer Availability lookups after a CDX outage.
                    val savedCountBeforeCandidate = savedCount
                    // Bound each candidate's direct + merchant-domain lookup so slow public
                    // archive responses cannot leave the detail sheet spinning indefinitely.
                    // Prefer the exact retailer URL because it is much cheaper than scanning
                    // the whole domain. A failing CDX domain fallback must never consume the
                    // entire per-store budget or starve the next candidate.
                    val attempts = mutableListOf<com.example.data.remote.WaybackBackfillResult>()
                    // Keep verified quotes outside the cancellable network call. If its local
                    // timeout fires after one capture succeeded, the final persistence pass still
                    // receives those quotes instead of treating the entire candidate as empty.
                    val candidateQuotesObserved = linkedMapOf<String, ArchivedPriceQuote>()
                    val persistQuotesAsTheyArrive: suspend (List<ArchivedPriceQuote>) -> Unit = { batch ->
                        for (quote in batch) {
                            candidateQuotesObserved.putIfAbsent(archiveQuoteIdentity(candidate, quote), quote)
                        }
                        persistCandidateQuotes(candidate, batch)
                    }
                    val stopWhenEnoughNewPoints: () -> Boolean = {
                        val currentPrimaryQuotes = candidateQuotesObserved.values.filter {
                            !it.isDiscoveryCandidate &&
                                (it.targetProductId.isNullOrBlank() || it.targetProductId == targetProductId)
                        }
                        hasSufficientNewVerifiedArchiveHistory(
                            timestamps = currentPrimaryQuotes.map { it.observedAt },
                            newPointsSaved = (savedCount - savedCountBeforeCandidate).coerceAtLeast(0)
                        )
                    }
                    var candidateTimedOut = false
                    if (candidate.directUrl != null && !availabilityOnlyFallbackForCandidate) {
                        val direct = withTimeoutOrNull(MAX_ARCHIVE_LOOKUP_PER_CANDIDATE_MS) {
                            waybackPriceHistoryClient.fetchHistory(
                                originalUrl = candidate.directUrl,
                                expectedTitle = candidate.title,
                                onTrace = traceCallback,
                                knownProductTitlesById = knownProductTitlesById,
                                onVerifiedQuotes = persistQuotesAsTheyArrive,
                                forceRefresh = forceRefresh,
                                stopWhenEnoughNewPoints = stopWhenEnoughNewPoints
                            )
                        }
                        if (direct == null) {
                            candidateTimedOut = true
                            diagnosticCodes.add("CDX_URL_PREFIX_CANDIDATE_TIMEOUT")
                            if (candidateQuotesObserved.isNotEmpty()) {
                                attempts += com.example.data.remote.WaybackBackfillResult(
                                    quotes = candidateQuotesObserved.values.toList(),
                                    capturesFound = candidateQuotesObserved.values.map { it.observedAt }.distinct().size,
                                    requestFailed = true,
                                    diagnosticCodes = setOf("CANDIDATE_TIMEOUT_PARTIAL_QUOTES")
                                )
                            }
                        } else {
                            attempts += direct
                        }
                    }
                    // Use Memento when Wayback did not add at least three new dates to Room.
                    // Three recovered timestamps are not completion when they already existed locally.
                    val currentCandidateQuotes = attempts.flatMap { it.quotes }.filter {
                        !it.isDiscoveryCandidate &&
                            (it.targetProductId.isNullOrBlank() || it.targetProductId == targetProductId)
                    }
                    val sufficientHistoricalCoverage = hasSufficientNewVerifiedArchiveHistory(
                        timestamps = currentCandidateQuotes.map { it.observedAt },
                        newPointsSaved = (savedCount - savedCountBeforeCandidate).coerceAtLeast(0)
                    )
                    if (!availabilityOnlyFallbackForCandidate &&
                        candidate.directUrl != null && shouldAttemptMementoFallback(
                            alreadyAttempted = mementoAttempted,
                            hasEligibleDirectUrl = true,
                            hasSufficientHistory = sufficientHistoricalCoverage,
                            rateLimited = attempts.any { it.diagnosticCodes.contains("MEMENTO_RATE_LIMITED") }
                        )
                    ) {
                        mementoAttempted = true
                        if (diagnosticProductId != null) {
                            val dateCount = currentCandidateQuotes
                                .map { Math.floorDiv(it.observedAt, WaybackPriceHistoryClient.DAY_MS) }
                                .distinct().size
                            reportWaybackDiagnostic(
                                diagnosticProductId, 7, WaybackTraceStatus.RUNNING,
                                if (dateCount > 0) {
                                    "Wayback recuperó $dateCount fecha(s) histórica(s); se consulta Arquivo.pt para intentar completar la serie."
                                } else {
                                    "Wayback no proporcionó un precio verificable; se prueba una fuente de archivo independiente (Memento)."
                                }
                            )
                        }
                        val mementoResult = withTimeoutOrNull(MAX_ARCHIVE_MEMENTO_FALLBACK_MS) {
                            mementoClient.fetchHistory(
                                originalUrl = candidate.directUrl,
                                expectedTitle = candidate.title,
                                knownProductTitlesById = knownProductTitlesById,
                                maxSnapshots = 4,
                                onVerifiedQuotes = persistQuotesAsTheyArrive
                            )
                        }
                        if (mementoResult == null) {
                            diagnosticCodes.add("MEMENTO_LOOKUP_TIMEOUT")
                            if (diagnosticProductId != null) {
                                reportWaybackDiagnostic(
                                    diagnosticProductId, 7, WaybackTraceStatus.FAILED,
                                    "La alternativa Memento superó su límite de 25 segundos; solo cuentan las capturas verificadas y guardadas antes del límite."
                                )
                            }
                        } else {
                            attempts += mementoResult
                            diagnosticCodes.addAll(mementoResult.diagnosticCodes)
                            if (diagnosticProductId != null) {
                                val mementoStatus = when {
                                    mementoResult.quotes.isNotEmpty() -> WaybackTraceStatus.PASSED
                                    mementoResult.diagnosticCodes.contains("MEMENTO_RATE_LIMITED") -> WaybackTraceStatus.SKIPPED
                                    mementoResult.capturesFound == 0 && !mementoResult.requestFailed -> WaybackTraceStatus.FAILED
                                    else -> WaybackTraceStatus.FAILED
                                }
                                reportWaybackDiagnostic(
                                    diagnosticProductId, 7, mementoStatus,
                                    when {
                                        mementoResult.quotes.isNotEmpty() ->
                                            "Memento recuperó ${mementoResult.quotes.size} precio(s) archivado(s) y los envió al guardado durante esta búsqueda. La fase 19 confirma cuántos se añadieron y cuántos ya existían." +
                                                if (mementoResult.diagnosticCodes.contains("MEMENTO_RATE_LIMITED")) {
                                                    " Arquivo.pt también devolvió HTTP 429 y activó su pausa independiente de Memento; esto no implica una pausa global de Wayback."
                                                } else {
                                                    ""
                                                }
                                        mementoResult.diagnosticCodes.contains("MEMENTO_RATE_LIMITED") ->
                                            "Arquivo.pt pidió limitar las consultas; Mínimo detuvo el respaldo Memento y aplica una pausa propia, independiente de Wayback."
                                        mementoResult.capturesFound == 0 && !mementoResult.requestFailed ->
                                            "Memento respondió correctamente, pero no encontró capturas para esta URL."
                                        else ->
                                            "Memento no pudo recuperar un precio verificable: ${mementoResult.diagnosticCodes.joinToString(", ").take(180)}."
                                    }
                                )
                            }
                        }
                    }

                    // Prefer the direct URL and Memento before the slower merchant-wide fallback.
                    // Domain discovery remains eligible when prior results were repeated Room dates.
                    val quotesBeforeDomain = (
                        attempts.flatMap { it.quotes } + candidateQuotesObserved.values
                    ).distinctBy { archiveQuoteIdentity(candidate, it) }.filter {
                        !it.isDiscoveryCandidate &&
                            (it.targetProductId.isNullOrBlank() || it.targetProductId == targetProductId)
                    }
                    val hasSufficientHistoryBeforeDomain = hasSufficientNewVerifiedArchiveHistory(
                        timestamps = quotesBeforeDomain.map { it.observedAt },
                        newPointsSaved = (savedCount - savedCountBeforeCandidate).coerceAtLeast(0)
                    )
                    val rateLimitedAtDirectStage =
                        attempts.any { it.diagnosticCodes.contains("WAYBACK_RATE_LIMITED") }
                    val priorDirectCdxDiagnostics = attempts.flatMap { it.diagnosticCodes }.toSet()
                    val cdxBackendUnhealthy =
                        shouldSkipDomainFallbackAfterRepeatedCdxTimeouts(priorDirectCdxDiagnostics)
                    val allDirectSnapshotsTimedOut =
                        shouldSkipDomainFallbackAfterSnapshotTimeout(attempts)
                    val merchantDomainForFallback = candidate.merchantDomain
                    val circuitWasAlreadyOpen = skipDomainFallbacksForSearch
                    val openCircuitNow = shouldOpenArchiveDomainFallbackCircuit(
                        repeatedCdxTimeouts = cdxBackendUnhealthy,
                        rateLimited = rateLimitedAtDirectStage,
                        allDirectSnapshotsTimedOut = allDirectSnapshotsTimedOut,
                        directLookupTimedOut = candidateTimedOut
                    )
                    if (openCircuitNow) skipDomainFallbacksForSearch = true
                    if (cdxBackendUnhealthy &&
                        !rateLimitedAtDirectStage &&
                        !allDirectSnapshotsTimedOut &&
                        !candidateTimedOut
                    ) {
                        val transientPrefixFailures = priorDirectCdxDiagnostics.filter { code ->
                            (code.startsWith("CDX_URL_PREFIX") ||
                                code.startsWith("CDX_PRODUCT_PREFIX") ||
                                code.startsWith("CDX_ALTERNATE_PREFIX")) &&
                                (code.endsWith("_TIMEOUT") || code.endsWith("_DNS") ||
                                    (code.contains("_HTTP_") &&
                                        code.substringAfterLast('_').toIntOrNull()
                                            ?.let { it in setOf(500, 502, 503, 504) } == true))
                        }
                        searchCdxTimeoutDiagnostics.addAll(transientPrefixFailures)
                        if (transientPrefixFailures.isNotEmpty()) {
                            // A CDX timeout or transient HTTP 5xx indicates shared Archive.org backend trouble.
                            // Persist a short global circuit so another product cannot immediately
                            // repeat the same failing traffic after this search completes.
                            waybackPriceHistoryClient.activateBackendUnavailableCooldown()
                            // Keep recovery available for this same candidate: the decision made
                            // at loop entry predates the CDX timeouts observed during its direct lookup.
                            // The fallback uses the remaining slots in the three-store budget and never repeats CDX.
                            availabilityOnlyFallbackAfterCdxTimeoutAvailable = true
                            availabilityOnlyFallbackForCandidate =
                                shouldUseAvailabilityOnlyFallbackAfterCdxTimeout(
                                    searchCircuitOpen = true,
                                    timeoutFallbackAvailable = true,
                                    rateLimited = rateLimitedAtDirectStage,
                                    allDirectSnapshotsTimedOut = allDirectSnapshotsTimedOut,
                                    directLookupTimedOut = candidateTimedOut,
                                    hasMerchantDomain = merchantDomainForFallback != null,
                                    usedFallbacks = availabilityOnlyFallbacksUsed,
                                    maxFallbacks = MAX_AVAILABILITY_ONLY_FALLBACKS_AFTER_CDX_TIMEOUT
                                )
                        }
                    }
                    if (rateLimitedAtDirectStage || allDirectSnapshotsTimedOut) {
                        availabilityOnlyFallbackAfterCdxTimeoutAvailable = false
                    }
                    if (cdxBackendUnhealthy) {
                        diagnosticCodes.add("CDX_DOMAIN_FALLBACK_SKIPPED_AFTER_REPEATED_TIMEOUTS")
                        if (diagnosticProductId != null) {
                            reportWaybackDiagnostic(
                                diagnosticProductId, 7, WaybackTraceStatus.SKIPPED,
                                "CDX no responde de forma fiable (timeout o error HTTP 5xx). Se detienen nuevas consultas al índice, pero se conserva una recuperación limitada mediante Availability API. Se mantienen los precios ya verificados."
                            )
                        }
                    } else if (!circuitWasAlreadyOpen && skipDomainFallbacksForSearch &&
                        diagnosticProductId != null
                    ) {
                        val reason = when {
                            rateLimitedAtDirectStage -> "Internet Archive indicó una pausa HTTP 429"
                            candidateTimedOut -> "la búsqueda directa alcanzó su límite de tiempo"
                            allDirectSnapshotsTimedOut -> "todas las descargas de capturas directas agotaron el tiempo"
                            else -> "la consulta directa no está respondiendo de forma fiable"
                        }
                        reportWaybackDiagnostic(
                            diagnosticProductId, 7, WaybackTraceStatus.SKIPPED,
                            "Se detienen las búsquedas por dominio restantes de esta ejecución: $reason. Se conservan los precios verificados y se evita generar más peticiones."
                        )
                    }
                    val shouldTryDomainFallback = if (availabilityOnlyFallbackForCandidate) {
                        merchantDomainForFallback != null &&
                            !hasSufficientHistoryBeforeDomain &&
                            !rateLimitedAtDirectStage &&
                            !allDirectSnapshotsTimedOut &&
                            !candidateTimedOut &&
                            waybackPriceHistoryClient.activeRateLimitUntil() == null
                    } else {
                        shouldTryDomainFallbackForArchiveCandidate(
                            hasMerchantDomain = merchantDomainForFallback != null,
                            hasSufficientHistory = hasSufficientHistoryBeforeDomain,
                            rateLimited = rateLimitedAtDirectStage,
                            allDirectSnapshotsTimedOut = allDirectSnapshotsTimedOut,
                            searchCircuitOpen = skipDomainFallbacksForSearch
                        )
                    }
                    if (shouldTryDomainFallback && merchantDomainForFallback != null) {
                        if (availabilityOnlyFallbackForCandidate) {
                            availabilityOnlyFallbacksUsed++
                        }
                        if (diagnosticProductId != null) {
                            reportWaybackDiagnostic(
                                diagnosticProductId, 7, WaybackTraceStatus.RUNNING,
                                if (availabilityOnlyFallbackForCandidate) {
                                    "CDX ha fallado temporalmente; se prueba Availability API sin repetir consultas al índice."
                                } else {
                                    "Se activa la alternativa por dominio; se mantienen la caché y los límites de consultas."
                                }
                            )
                        }
                        val inheritedCdxFailures = if (availabilityOnlyFallbackForCandidate) {
                            searchCdxTimeoutDiagnostics
                        } else {
                            emptySet()
                        }
                        val domainResult = withTimeoutOrNull(MAX_ARCHIVE_DOMAIN_FALLBACK_MS) {
                            waybackPriceHistoryClient.fetchHistoryByMerchantDomain(
                                domain = merchantDomainForFallback,
                                expectedTitle = candidate.title,
                                preferredUrl = candidate.directUrl,
                                // Do not repeat the direct query; if the search-wide CDX circuit is
                                // open, the client will go straight to bounded Availability probes.
                                preferredUrlAlreadyQueried =
                                    candidate.directUrl != null && !availabilityOnlyFallbackForCandidate,
                                priorCdxDiagnostics = (
                                    attempts.flatMap { it.diagnosticCodes } +
                                        inheritedCdxFailures +
                                        if (candidateTimedOut) setOf("CDX_URL_PREFIX_TIMEOUT") else emptySet()
                                    ).filter { code ->
                                        code.startsWith("CDX_URL_PREFIX") ||
                                            code.startsWith("CDX_PRODUCT_PREFIX") ||
                                            code.startsWith("CDX_ALTERNATE_PREFIX")
                                    }.toSet(),
                                availabilityOnlyAfterPriorTimeout = availabilityOnlyFallbackForCandidate,
                                onTrace = traceCallback,
                                knownProductTitlesById = knownProductTitlesById,
                                onVerifiedQuotes = persistQuotesAsTheyArrive,
                                forceRefresh = forceRefresh,
                                stopWhenEnoughNewPoints = stopWhenEnoughNewPoints
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
                                    code != "CDX_DIRECT_URL_ALREADY_QUERIED" &&
                                        code != "CACHE_SKIPPED" &&
                                        code != "CACHE_SKIPPED_AFTER_FAILURE"
                                }
                                val fallbackTransportFailed = fallbackDiagnostics.any { code ->
                                    code == "WAYBACK_RATE_LIMITED" ||
                                        code == "DOMAIN_UNRESOLVED" ||
                                        code.contains("_TIMEOUT") ||
                                        code.contains("_DNS") ||
                                        code.contains("_NETWORK") ||
                                        code.contains("_HTTP_") ||
                                        code.endsWith("_UNEXPECTED_ERROR") ||
                                        code.endsWith("_INVALID_RESPONSE") ||
                                        code.endsWith("_BODY_EMPTY_OR_LIMIT") ||
                                        code.endsWith("_NO_CANDIDATES") ||
                                        code.endsWith("_CANDIDATES_EMPTY")
                                }
                                // A recent cache entry is not a successful fallback: no new API
                                // request was made. Likewise, an unresolvable merchant string means
                                // no domain lookup started at all. Only label a real response as
                                // passed; keep an empty-but-valid Availability response distinct.
                                val fallbackReturnedNoCaptures =
                                    fallbackDiagnostics.contains("DOMAIN_AVAILABILITY_FALLBACK_NO_CAPTURES")
                                val domainWasCached = domainResult.skippedRecently ||
                                    domainResult.diagnosticCodes.any {
                                        it == "CACHE_SKIPPED" || it == "CACHE_SKIPPED_AFTER_FAILURE"
                                    }
                                val domainWasUnresolved =
                                    domainResult.diagnosticCodes.contains("DOMAIN_UNRESOLVED")
                                val step7Status = when {
                                    domainWasCached -> WaybackTraceStatus.SKIPPED
                                    domainWasUnresolved -> WaybackTraceStatus.FAILED
                                    domainResult.quotes.isNotEmpty() -> WaybackTraceStatus.PASSED
                                    // Network reachability is not historical recovery. A valid
                                    // Availability response with zero captures must be red, not
                                    // green: otherwise the report says "superado" while the chart
                                    // remains empty, exactly the failure seen on-device.
                                    fallbackReturnedNoCaptures -> WaybackTraceStatus.FAILED
                                    domainResult.capturesFound > 0 -> WaybackTraceStatus.PASSED
                                    fallbackTransportFailed -> WaybackTraceStatus.FAILED
                                    else -> WaybackTraceStatus.FAILED
                                }
                                reportWaybackDiagnostic(
                                    diagnosticProductId, 7,
                                    step7Status,
                                    when {
                                        domainWasCached ->
                                            "Alternativa omitida: ya existe un intento reciente en caché; no se enviaron nuevas peticiones."
                                        domainWasUnresolved ->
                                            "Alternativa no ejecutada: no se pudo resolver un dominio válido de tienda (DOMAIN_UNRESOLVED)."
                                        domainResult.quotes.isNotEmpty() ->
                                            "Alternativa por dominio devolvió ${domainResult.quotes.size} precio(s) verificado(s)."
                                        fallbackReturnedNoCaptures && fallbackTransportFailed ->
                                            "Availability respondió, pero no encontró capturas y alguna consulta falló: ${fallbackDiagnostics.joinToString(", ").take(180)}."
                                        fallbackReturnedNoCaptures ->
                                            "La alternativa respondió correctamente, pero no encontró capturas."
                                        domainResult.capturesFound > 0 ->
                                            "La alternativa encontró ${domainResult.capturesFound} captura(s); revisa los pasos 8–17 para saber por qué no se aceptó un precio."
                                        fallbackTransportFailed ->
                                            "La alternativa falló durante una consulta: ${fallbackDiagnostics.joinToString(", ").take(180)}."
                                        else ->
                                            "Alternativa ejecutada: no devolvió un precio verificado. Revisa los pasos 8–17."
                                    }
                                )
                            }
                        }
                    } else if (allDirectSnapshotsTimedOut && diagnosticProductId != null) {
                        reportWaybackDiagnostic(
                            diagnosticProductId, 7, WaybackTraceStatus.SKIPPED,
                            "CDX encontró capturas, pero todas las descargas HTML agotaron el tiempo; no se repite Availability en esta ejecución para evitar solicitudes duplicadas."
                        )
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
                        .distinctBy {
                            listOf(
                                it.targetProductId.orEmpty(), it.productTitle, it.originalUrl,
                                it.productUrl.orEmpty(), it.isDiscoveryCandidate.toString(),
                                it.observedAt.toString(), it.price.toString(), it.currency.uppercase(Locale.ROOT)
                            )
                        }
                    checkedCount += attempts.sumOf { it.capturesChecked }
                    capturesFoundCount += attempts.sumOf { it.capturesFound }
                    successfulLookupsCount += attempts.count { it.available }
                    requestFailedCount += attempts.count { it.requestFailed }
                    // Any quote already persisted by the per-capture callback is skipped here.
                    // This final pass catches results from routes that did not stream their points.
                    persistCandidateQuotes(candidate, quotes)

                    // An HTTP 429 applies to the shared archive service, so stop trying more
                    // merchant candidates after it is reported. A valid quote from one merchant
                    // must not prevent collecting history for the remaining candidates.
                    if (attempts.any { it.diagnosticCodes.contains("WAYBACK_RATE_LIMITED") }) {
                        break
                    }
                }

                if (selectedProductRefreshNeeded) {
                    val targetIdentity = selectedProductRefreshTitle?.let(::onlineProductIdentityKey)
                    val refreshedProduct = runCatching {
                        withTimeout(10_000L) {
                            // Refresh by stable product identity first. Requiring the mapped
                            // archive point in this wait predicate can time out even after Room
                            // has committed the rows (for example while the combined stream is
                            // reconciling observations). UI refresh and history verification are
                            // separate checks and must not block one another.
                            repository.allProductsStream.first { products ->
                                products.any { product ->
                                    (targetProductId?.let { product.id == it }
                                        ?: (targetIdentity != null &&
                                            onlineProductIdentityKey(product.title) == targetIdentity))
                                }
                            }.first { product ->
                                targetProductId?.let { product.id == it }
                                    ?: (targetIdentity != null &&
                                        onlineProductIdentityKey(product.title) == targetIdentity)
                            }
                        }
                    }.getOrNull()
                    if (refreshedProduct != null) {
                        val hasArchiveHistory = refreshedProduct.priceHistory.any { it.isArchiveEvidence }
                        selectedProductRefreshed = hasArchiveHistory
                        val currentState = _uiState.value
                        if (currentState.selectedProduct?.id == refreshedProduct.id) {
                            _uiState.value = currentState.copy(selectedProduct = refreshedProduct)
                        }
                    }
                }

                if (diagnosticProductId != null) {
                    // Only durable writes are proof that persistence succeeded. Quotes
                    // extracted from the archive can still be rejected or collide with malformed
                    // legacy rows; those cases must not be reported as a successful save.
                    val confirmedDuplicateTotal = confirmedPrimaryDuplicateCount + confirmedVariantDuplicateCount
                    val persistenceStatus = archivePersistenceStatus(
                        savedPoints = savedCount,
                        confirmedDuplicates = confirmedDuplicateTotal,
                        rejectedQuotes = rejectedArchiveQuoteCount,
                        attempted = foundPrimaryCount > 0 || foundSecondaryCount > 0,
                        completed = overallPersistenceCompleted
                    )
                    val persistenceDetail = when {
                        savedCount > 0 && crossSavedVariantObservationCount > 0 &&
                            stagedDiscoveryObservationsCount.get() > 0 ->
                            "Guardado completado: $savedCount punto(s) para el producto seleccionado, " +
                                "$crossSavedVariantObservationCount punto(s) en variantes relacionadas y " +
                                "${stagedDiscoveryObservationsCount.get()} observación(es) nueva(s) en cuarentena."
                        savedCount > 0 && crossSavedVariantObservationCount > 0 ->
                            "Guardado completado: $savedCount punto(s) para el producto seleccionado y " +
                                "$crossSavedVariantObservationCount punto(s) en variantes relacionadas."
                        savedCount > 0 && stagedDiscoveryObservationsCount.get() > 0 ->
                            "Guardado completado: $savedCount punto(s) para el producto seleccionado y " +
                                "${stagedDiscoveryObservationsCount.get()} observación(es) secundaria(s) en cuarentena."
                        savedCount > 0 ->
                            "Guardado completado: $savedCount punto(s) nuevo(s) en el histórico del producto seleccionado."
                        crossSavedVariantObservationCount > 0 && stagedDiscoveryObservationsCount.get() > 0 ->
                            "El producto seleccionado no recibió puntos nuevos. Se guardaron " +
                                "$crossSavedVariantObservationCount punto(s) en variantes relacionadas y " +
                                "${stagedDiscoveryObservationsCount.get()} observación(es) en cuarentena."
                        crossSavedVariantObservationCount > 0 ->
                            "Guardado cruzado: $crossSavedVariantObservationCount punto(s) nuevo(s) en fichas de variantes relacionadas; " +
                                "no se añadieron puntos nuevos al producto seleccionado."
                        confirmedDuplicateTotal > 0 ->
                            "Se confirmó que ya existe al menos un punto archivado válido para el mismo producto, tienda y día; no se creó otra fila."
                        stagedDiscoveryObservationsCount.get() > 0 ->
                            "Radar de Descubrimientos: ${stagedDiscoveryObservationsCount.get()} observación(es) de productos nuevos guardadas en cuarentena; siguen fuera del catálogo principal."
                        foundPrimaryCount > 0 ->
                            "Se recuperaron $foundPrimaryCount precio(s) del producto seleccionado, pero no se confirmó ningún punto nuevo guardado. " +
                                "No se da el guardado por superado: comprueba las fechas existentes y el motivo de rechazo en este informe."
                        foundSecondaryCount > 0 ->
                            "Se recuperaron $foundSecondaryCount precio(s) de variantes relacionadas, pero no se confirmó su guardado. " +
                                "No se da por hecho que las filas existentes sean duplicados válidos."
                        else ->
                            "No se guardaron registros: ninguna captura produjo un precio verificado."
                    }
                    val rejectionReasonSummary = archiveQuoteRejectionReasons.entries
                        .sortedBy { it.key }
                        .joinToString(", ") { (reason, count) -> "$reason=$count" }
                        .ifBlank { "ninguno" }
                    val persistenceCounters =
                        "Conteo de persistencia: nuevos producto seleccionado=$savedCount; " +
                            "nuevos variantes=$crossSavedVariantObservationCount; " +
                            "duplicados válidos producto seleccionado=$confirmedPrimaryDuplicateCount; " +
                            "duplicados válidos variantes=$confirmedVariantDuplicateCount; " +
                            "filas antiguas inválidas ignoradas=${invalidArchivedRowIds.size}; " +
                            "capturas rechazadas=$rejectedArchiveQuoteCount; " +
                            "motivos de rechazo=$rejectionReasonSummary; " +
                            "repetidas dentro de la respuesta=$duplicateArchiveInputCount."
                    val persistenceDetailWithCounts = "$persistenceDetail\n$persistenceCounters"
                    reportWaybackDiagnostic(
                        diagnosticProductId, 19, persistenceStatus, persistenceDetailWithCounts
                    )

                    // Expanded diagnostic gates (23–29) separate product identity, data quality,
                    // persistence outcomes and UI refresh. They use counters already collected by
                    // this search, so they do not perform additional network or paid requests.
                    val extractedForReport = foundPrimaryCount + foundSecondaryCount
                    val savedTotalForReport = savedCount + crossSavedVariantObservationCount
                    val confirmedDuplicateTotalForReport =
                        confirmedPrimaryDuplicateCount + confirmedVariantDuplicateCount
                    val identityRejectCount = archiveQuoteRejectionReasons
                        .filterKeys { key ->
                            key.contains("IDENTITY", ignoreCase = true) ||
                                key.contains("URL", ignoreCase = true) ||
                                key.contains("TARGET_PRODUCT", ignoreCase = true)
                        }.values.sum()
                    val valueRejectCount = archiveQuoteRejectionReasons
                        .filterKeys { key ->
                            listOf("PRICE", "CURRENCY", "DATE", "EUR", "EXCHANGE", "IMPLAUSIBLE")
                                .any { marker -> key.contains(marker, ignoreCase = true) }
                        }.values.sum()
                    val rejectionReasonTotal = archiveQuoteRejectionReasons.values.sum()
                    val rejectionCountsConsistent = rejectionReasonTotal == rejectedArchiveQuoteCount

                    reportWaybackDiagnostic(
                        diagnosticProductId, 23,
                        when {
                            identityRejectCount > 0 -> WaybackTraceStatus.FAILED
                            extractedForReport > 0 -> WaybackTraceStatus.PASSED
                            else -> WaybackTraceStatus.SKIPPED
                        },
                        "Precios extraídos=$extractedForReport; rechazos relacionados con identidad/URL=$identityRejectCount. " +
                            "Motivos relevantes: " + archiveQuoteRejectionReasons.entries
                            .filter { it.key.contains("IDENTITY", true) || it.key.contains("URL", true) ||
                                it.key.contains("TARGET_PRODUCT", true) }
                            .joinToString(", ") { "${it.key}=${it.value}" }.ifBlank { "ninguno" } + "."
                    )
                    reportWaybackDiagnostic(
                        diagnosticProductId, 24,
                        when {
                            valueRejectCount > 0 -> WaybackTraceStatus.FAILED
                            extractedForReport > 0 -> WaybackTraceStatus.PASSED
                            else -> WaybackTraceStatus.SKIPPED
                        },
                        "Precios extraídos=$extractedForReport; rechazos por precio/moneda/fecha=$valueRejectCount. " +
                            "Motivos: " + archiveQuoteRejectionReasons.entries
                            .filter { entry -> listOf("PRICE", "CURRENCY", "DATE", "EUR", "EXCHANGE", "IMPLAUSIBLE")
                                .any { entry.key.contains(it, true) } }
                            .joinToString(", ") { "${it.key}=${it.value}" }.ifBlank { "ninguno" } + "."
                    )
                    reportWaybackDiagnostic(
                        diagnosticProductId, 25,
                        when {
                            !rejectionCountsConsistent -> WaybackTraceStatus.FAILED
                            extractedForReport > 0 || rejectedArchiveQuoteCount > 0 ->
                                WaybackTraceStatus.PASSED
                            else -> WaybackTraceStatus.SKIPPED
                        },
                        "Rechazos totales=$rejectedArchiveQuoteCount; suma de motivos=$rejectionReasonTotal; " +
                            "coinciden=${rejectionCountsConsistent}. Ningún contenido rechazado se añade al historial."
                    )
                    reportWaybackDiagnostic(
                        diagnosticProductId, 26,
                        when {
                            savedTotalForReport > 0 || confirmedDuplicateTotalForReport > 0 ||
                                stagedDiscoveryObservationsCount.get() > 0 -> WaybackTraceStatus.PASSED
                            !overallPersistenceCompleted || extractedForReport > 0 -> WaybackTraceStatus.FAILED
                            else -> WaybackTraceStatus.SKIPPED
                        },
                        "Guardados nuevos=$savedTotalForReport; duplicados confirmados=$confirmedDuplicateTotalForReport; " +
                            "observaciones en cuarentena=${stagedDiscoveryObservationsCount.get()}; " +
                            "rechazos=$rejectedArchiveQuoteCount; operación de persistencia completada=$overallPersistenceCompleted."
                    )
                    reportWaybackDiagnostic(
                        diagnosticProductId, 27,
                        when {
                            invalidArchivedRowIds.isNotEmpty() -> WaybackTraceStatus.FAILED
                            confirmedDuplicateTotalForReport > 0 -> WaybackTraceStatus.PASSED
                            else -> WaybackTraceStatus.SKIPPED
                        },
                        "Duplicados válidos del producto seleccionado=$confirmedPrimaryDuplicateCount; " +
                            "duplicados válidos de variantes=$confirmedVariantDuplicateCount; " +
                            "filas antiguas inválidas ignoradas=${invalidArchivedRowIds.size}. " +
                            "Una fila inválida no se cuenta como duplicado válido."
                    )
                    val accountingIsExplained = extractedForReport == 0 ||
                        savedTotalForReport > 0 || confirmedDuplicateTotalForReport > 0 ||
                        rejectedArchiveQuoteCount > 0 || stagedDiscoveryObservationsCount.get() > 0
                    reportWaybackDiagnostic(
                        diagnosticProductId, 28,
                        when {
                            !overallPersistenceCompleted || !rejectionCountsConsistent || !accountingIsExplained ->
                                WaybackTraceStatus.FAILED
                            extractedForReport > 0 || savedTotalForReport > 0 ->
                                WaybackTraceStatus.PASSED
                            else -> WaybackTraceStatus.SKIPPED
                        },
                        "Extraídos=$extractedForReport; nuevos=$savedTotalForReport; " +
                            "duplicados confirmados=$confirmedDuplicateTotalForReport; " +
                            "rechazos=$rejectedArchiveQuoteCount (motivos=$rejectionReasonTotal); " +
                            "cuarentena=${stagedDiscoveryObservationsCount.get()}; " +
                            "cuentas explicadas=$accountingIsExplained."
                    )
                    reportWaybackDiagnostic(
                        diagnosticProductId, 29,
                        when {
                            selectedProductRefreshed -> WaybackTraceStatus.PASSED
                            selectedProductRefreshNeeded || savedCount > 0 -> WaybackTraceStatus.FAILED
                            else -> WaybackTraceStatus.SKIPPED
                        },
                        "Actualización solicitada=$selectedProductRefreshNeeded; ficha refrescada=$selectedProductRefreshed; " +
                            "puntos nuevos del producto seleccionado=$savedCount."
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

                val selectedProductDistinctDateCount = archiveDates
                    .map { Math.floorDiv(it, com.example.data.remote.WaybackPriceHistoryClient.DAY_MS) }
                    .distinct()
                    .size
                val notice = when {
                    waybackPriceHistoryClient.activeRateLimitUntil() != null ->
                        waybackRateLimitNotice() + when {
                            savedCount > 0 ->
                                " Se conservaron $savedCount punto(s) del producto seleccionado antes de la pausa."
                            crossSavedVariantObservationCount > 0 ->
                                " Se guardaron $crossSavedVariantObservationCount punto(s) en variantes relacionadas; eso no añade historial al producto seleccionado."
                            foundPrimaryCount > 0 ->
                                " Se verificaron $foundPrimaryCount punto(s) del producto seleccionado, pero la pausa impide completar su histórico."
                            foundSecondaryCount > 0 ->
                                " Solo se verificaron capturas de variantes relacionadas; la pausa impide completar otras consultas."
                            else ->
                                " Esto no significa que no existan capturas."
                        }
                    mementoClient.activeRateLimitUntil() != null ->
                        mementoRateLimitNotice() + when {
                            savedCount > 0 ->
                                " Se conservaron ${savedCount} punto(s) nuevo(s) del producto seleccionado antes de la pausa de Memento."
                            foundPrimaryCount > 0 ->
                                " Se verificaron ${foundPrimaryCount} punto(s) del producto seleccionado; la fase 19 indica si eran nuevos o ya existían."
                            else ->
                                " Esto no significa que no existan capturas en Arquivo.pt ni en Wayback."
                        }
                    diagnosticCodes.contains("MEMENTO_RATE_LIMITED") ->
                        mementoRateLimitNotice() + " Esto no significa que no existan capturas."
                    skippedCount == candidates.size && diagnosticCodes.contains("CACHE_SKIPPED_AFTER_FAILURE") ->
                        "Internet Archive no respondió en el intento anterior. Mínimo evita repetir enseguida una consulta que agotó el tiempo; vuelve a probar dentro de unos minutos. Esto no significa que no existan capturas."
                    skippedCount == candidates.size ->
                        "Estas consultas gratuitas ya se comprobaron recientemente. La caché evita repetir búsquedas completadas sin resultados útiles."
                    requestFailedCount > 0 && selectedProductDistinctDateCount < 3 &&
                        (savedCount > 0 || foundPrimaryCount > 0) ->
                        "Histórico parcial: se conservaron los puntos válidos del producto seleccionado " +
                            "(nuevos guardados: $savedCount; precios verificados encontrados: $foundPrimaryCount), " +
                            "pero falló una o más consultas y solo se confirmaron $selectedProductDistinctDateCount " +
                            "fechas distintas. El histórico no se marca como completado; los puntos guardados se mantienen."
                    savedCount > 0 && crossSavedVariantObservationCount > 0 -> {
                        val oldest = archiveDates.minOrNull()?.let { timestamp ->
                            SimpleDateFormat("dd/MM/yyyy", Locale("es", "ES")).format(Date(timestamp))
                        }
                        "Histórico gratuito recuperado de Internet Archive: $savedCount precios antiguos guardados para el producto seleccionado" +
                            (oldest?.let { ", con capturas desde $it" } ?: "") +
                            "; además, se guardaron $crossSavedVariantObservationCount precios en fichas de variantes relacionadas." +
                            " Son precios anunciados en páginas archivadas; puede haber huecos y no garantizan envío, stock ni precio final."
                    }
                    savedCount > 0 -> {
                        val oldest = archiveDates.minOrNull()?.let { timestamp ->
                            SimpleDateFormat("dd/MM/yyyy", Locale("es", "ES")).format(Date(timestamp))
                        }
                        "Histórico gratuito recuperado de Internet Archive: $savedCount precios antiguos guardados" +
                            (oldest?.let { ", con capturas desde $it" } ?: "") +
                            ". Son precios anunciados en páginas archivadas; puede haber huecos y no garantizan envío, stock ni precio final."
                    }
                    foundPrimaryCount > 0 && crossSavedVariantObservationCount > 0 ->
                        "Se recuperaron $foundPrimaryCount precio(s) del producto seleccionado y se guardaron " +
                            "$crossSavedVariantObservationCount puntos en variantes relacionadas. " +
                            "La fase 19 separa duplicados válidos, filas inválidas y capturas rechazadas."
                    crossSavedVariantObservationCount > 0 ->
                        "Se guardaron $crossSavedVariantObservationCount precios históricos en fichas de variantes relacionadas; no se añadió historial nuevo al producto seleccionado."
                    foundPrimaryCount > 0 && confirmedPrimaryDuplicateCount > 0 ->
                        "Se recuperaron $foundPrimaryCount precio(s) del producto seleccionado y se confirmaron " +
                            "$confirmedPrimaryDuplicateCount duplicado(s) histórico(s) válido(s). No se añadieron fechas nuevas porque esos puntos ya existían." +
                            if (requestFailedCount > 0) " La búsqueda quedó parcial por una o más consultas fallidas o agotadas por tiempo; revisa los tiempos de espera CDX." else ""
                    foundPrimaryCount > 0 ->
                        "Se recuperaron $foundPrimaryCount precio(s) del producto seleccionado, pero no se confirmó ningún punto nuevo. " +
                            "No se asume que sean duplicados válidos: consulta la fase 19 para ver los duplicados confirmados, filas inválidas y capturas rechazadas."
                    foundSecondaryCount > 0 ->
                        "Se verificaron capturas de variantes relacionadas, pero no se confirmó su guardado. " +
                            "La fase 19 indica si había puntos válidos del mismo día o si las filas fueron rechazadas."
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
                // One seller in Shopping can be unresolved or cached while another seller
                // already sent a request. Do not let those secondary skips make the whole
                // search look like no request ever ran.
                val noticeDiagnosticCodes = diagnosticCodes.distinct().filterNot { code ->
                    archiveRequestSent.get() && code in setOf(
                        "CACHE_SKIPPED",
                        "CACHE_SKIPPED_AFTER_FAILURE",
                        "DOMAIN_UNRESOLVED"
                    )
                }
                val diagnosticLabels = noticeDiagnosticCodes.take(8).map { code ->
                    when {
                        code.startsWith("CDX_ALTERNATE_PREFIX_HTTP_") -> "$code (respuesta HTTP al consultar una ruta alternativa del producto)"
                        code.startsWith("CDX_DOMAIN_EXACT_HTTP_") -> "$code (respuesta HTTP de la consulta exacta de una URL candidata)"
                        code.startsWith("CDX_URL_PREFIX_QUARTER_HTTP_") -> "$code (respuesta HTTP de una ventana trimestral CDX)"
                        code.startsWith("CDX_URL_PREFIX_HTTP_") -> "$code (respuesta HTTP de la búsqueda por prefijo de URL)"
                        code.startsWith("CDX_DOMAIN_HTTP_") -> "$code (respuesta HTTP de la búsqueda por dominio)"
                        code.startsWith("SNAPSHOT_HTTP_") -> "$code (respuesta HTTP de una captura)"
                        else -> when (code) {
                            "ARCHIVE_AVAILABILITY_API_HTTP_429" -> "$code (HTTP 429 recibido en la Availability API de archive.org; fuente separada de CDX y Memento)"
                            "CDX_URL_PREFIX_QUARTER_TIMEOUT" -> "$code (agotó el tiempo una ventana trimestral de CDX)"
                            "WAYBACK_RATE_LIMITED" -> "$code (HTTP 429: consultas pausadas temporalmente para respetar el límite de Internet Archive)"
                            "WAYBACK_BACKEND_UNAVAILABLE_COOLDOWN" -> "$code (circuito global temporal tras varios tiempos de espera/HTTP 503; se bloquean nuevas consultas a Archive.org durante la recuperación)"
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
                            "CDX_ALTERNATE_PREFIX_TIMEOUT" -> "$code (se agotó el tiempo de la búsqueda CDX en una URL alternativa del producto)"
                            "CDX_ALTERNATE_PREFIX_DNS" -> "$code (no se resolvió el servidor al probar una URL alternativa)"
                            "CDX_ALTERNATE_PREFIX_NETWORK" -> "$code (falló la conexión al probar una URL alternativa)"
                            "CDX_ALTERNATE_PREFIX_UNEXPECTED_ERROR" -> "$code (error inesperado al consultar la ruta alternativa)"
                            "CDX_ALTERNATE_PREFIX_BODY_EMPTY_OR_LIMIT" -> "$code (la respuesta de la búsqueda alternativa estaba vacía o era demasiado grande)"
                            "CDX_ALTERNATE_PREFIX_INVALID_RESPONSE" -> "$code (la búsqueda alternativa no devolvió un índice válido)"
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
                            "CDX_DOMAIN_FALLBACK_SKIPPED_AFTER_REPEATED_TIMEOUTS" -> "Se pausó la búsqueda de más rutas CDX tras un timeout o un error HTTP 5xx; se mantuvo la recuperación limitada por Availability."
                            "CDX_EARLY_EXIT_VALID_PRICE" -> "CDX_EARLY_EXIT_VALID_PRICE (se cancelaron alternativas al confirmar un precio)"
                            else -> code
                        }
                    }
                }
                val discoveryNotice = if (stagedDiscoveryObservationsCount.get() > 0) {
                    " Radar de Descubrimientos: ${stagedDiscoveryObservationsCount.get()} observación(es) de productos nuevos quedaron en cuarentena, sin crear fichas en Mercado."
                } else ""
                val noticeWithDiagnostics = if (diagnosticLabels.isNotEmpty() && skippedCount != candidates.size) {
                    "$notice$discoveryNotice\nDiagnóstico Wayback: ${diagnosticLabels.joinToString(" · ")}."
                } else "$notice$discoveryNotice"
                appendArchiveNotice(query, provider, noticeWithDiagnostics, targetProductId)
                if (diagnosticProductId != null) {
                    when {
                        savedCount > 0 && crossSavedVariantObservationCount > 0 &&
                            stagedDiscoveryObservationsCount.get() > 0 -> finishWaybackDiagnostic(
                            diagnosticProductId, WaybackTraceStatus.PASSED,
                            "$savedCount punto(s) histórico(s) del producto seleccionado, " +
                                "$crossSavedVariantObservationCount punto(s) de variantes relacionadas y " +
                                "${stagedDiscoveryObservationsCount.get()} observación(es) nueva(s) en Radar."
                        )
                        savedCount > 0 && crossSavedVariantObservationCount > 0 -> finishWaybackDiagnostic(
                            diagnosticProductId, WaybackTraceStatus.PASSED,
                            "$savedCount punto(s) histórico(s) del producto seleccionado y " +
                                "$crossSavedVariantObservationCount punto(s) en variantes relacionadas."
                        )
                        savedCount > 0 -> finishWaybackDiagnostic(
                            diagnosticProductId, WaybackTraceStatus.PASSED,
                            "$savedCount precio(s) histórico(s) guardado(s) para el producto seleccionado."
                        )
                        crossSavedVariantObservationCount > 0 &&
                            stagedDiscoveryObservationsCount.get() > 0 -> finishWaybackDiagnostic(
                            diagnosticProductId, WaybackTraceStatus.PASSED,
                            "No se añadieron puntos nuevos al producto seleccionado. Se guardaron " +
                                "$crossSavedVariantObservationCount punto(s) en variantes relacionadas y " +
                                "${stagedDiscoveryObservationsCount.get()} observación(es) nueva(s) en Radar."
                        )
                        crossSavedVariantObservationCount > 0 -> finishWaybackDiagnostic(
                            diagnosticProductId, WaybackTraceStatus.PASSED,
                            "Solo se guardó historial de variantes relacionadas: " +
                                "$crossSavedVariantObservationCount punto(s). No se añadió historial nuevo al producto seleccionado."
                        )
                        stagedDiscoveryObservationsCount.get() > 0 -> finishWaybackDiagnostic(
                            diagnosticProductId, WaybackTraceStatus.PASSED,
                            "${stagedDiscoveryObservationsCount.get()} observación(es) de productos nuevos guardadas en el Radar; no se han añadido a Mercado automáticamente."
                        )
                        confirmedPrimaryDuplicateCount > 0 && selectedProductRefreshed && requestFailedCount == 0 -> finishWaybackDiagnostic(
                            diagnosticProductId, WaybackTraceStatus.PASSED,
                            "Se confirmó que los $confirmedPrimaryDuplicateCount punto(s) histórico(s) ya estaban guardados para el producto seleccionado. No se insertaron filas nuevas porque los puntos ya existían; la persistencia está comprobada."
                        )
                        confirmedPrimaryDuplicateCount > 0 && requestFailedCount > 0 -> finishWaybackDiagnostic(
                            diagnosticProductId, WaybackTraceStatus.FAILED,
                            "La persistencia está comprobada: $confirmedPrimaryDuplicateCount duplicado(s) válido(s) del producto seleccionado ya existían. No se añadieron fechas nuevas y la búsqueda quedó incompleta porque una o más consultas agotaron el tiempo o fallaron; esto no es un fallo del guardado."
                        )
                        confirmedPrimaryDuplicateCount > 0 -> finishWaybackDiagnostic(
                            diagnosticProductId, WaybackTraceStatus.FAILED,
                            "Se confirmaron $confirmedPrimaryDuplicateCount duplicado(s) histórico(s) válido(s), pero no se pudo confirmar la actualización de la ficha. No se añadieron fechas nuevas; revisa la fase 29."
                        )
                        foundPrimaryCount > 0 && selectedProductRefreshed -> finishWaybackDiagnostic(
                            diagnosticProductId, WaybackTraceStatus.FAILED,
                            "La fase de guardado SÍ se ejecutó, pero no se confirmó persistencia para el producto seleccionado. Revisa en la fase 19 los motivos de rechazo y las filas existentes."
                        )
                        foundSecondaryCount > 0 -> finishWaybackDiagnostic(
                            diagnosticProductId, WaybackTraceStatus.SKIPPED,
                            "Se verificaron capturas de variantes relacionadas, pero no se confirmó su guardado; no se marca la persistencia como superada."
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
                if (liveFallbackProduct != null &&
                    shouldTriggerLivePriceFallback(savedCount, confirmedPrimaryDuplicateCount)
                ) {
                    val archiveFailureReason = when {
                        diagnosticCodes.contains("WAYBACK_RATE_LIMITED") ->
                            "Internet Archive limitó las consultas (HTTP 429)"
                        capturesFoundCount == 0 ->
                            "el archivo no devolvió capturas"
                        else ->
                            "no se obtuvo un precio histórico verificable para el producto seleccionado"
                    }
                    runLivePriceFallbackAfterArchiveFailure(
                        product = liveFallbackProduct,
                        archiveFailureReason = archiveFailureReason
                    )
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
                    "No se pudo completar la consulta gratuita del archivo. Se comprobará por separado el precio actual; el histórico y sus datos guardados no se modificarán.",
                    targetProductId
                )
                if (shouldTriggerLiveFallbackAfterArchiveException(
                        hasFallbackProduct = liveFallbackProduct != null,
                        selectedProductHistoricalPointPersisted = selectedProductHistoricalPointPersisted
                    )
                ) {
                    runLivePriceFallbackAfterArchiveFailure(
                        product = liveFallbackProduct!!,
                        archiveFailureReason = "la consulta histórica terminó con un error controlado (${error.javaClass.simpleName})"
                    )
                }
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

/**
 * Trigger a current-price search whenever the archive run did not persist a new point and did
 * not confirm a valid existing point for the selected product. Extracted-but-rejected quotes do
 * not count as a successful historical result.
 */
internal fun shouldTriggerLivePriceFallback(
    selectedProductHistoricalQuotesSaved: Int,
    selectedProductHistoricalDuplicatesConfirmed: Int
): Boolean = selectedProductHistoricalQuotesSaved <= 0 &&
    selectedProductHistoricalDuplicatesConfirmed <= 0

/** A terminal archive exception must not bypass the current-price fallback. */
internal fun shouldTriggerLiveFallbackAfterArchiveException(
    hasFallbackProduct: Boolean,
    selectedProductHistoricalPointPersisted: Boolean
): Boolean = hasFallbackProduct && !selectedProductHistoricalPointPersisted

internal const val ARCHIVE_LIVE_FALLBACK_RETRY_COOLDOWN_MS = 15L * 60L * 1000L
internal const val ARCHIVE_LIVE_FALLBACK_SUCCESS_COOLDOWN_MS = 24L * 60L * 60L * 1000L
internal const val ARCHIVE_LIVE_FALLBACK_WAIT_FOR_IDLE_MS = 60L * 1000L

/**
 * Recent failed lookups may be retried after 15 minutes. Only a successful lookup with trusted
 * retailer offers gets the regular 24-hour cooldown. This prevents a failed first attempt from
 * suppressing the event for an entire day.
 */
internal fun archiveLiveFallbackCooldownRemainingMs(
    nowMillis: Long,
    previousAttemptAtMillis: Long,
    previousSuccessAtMillis: Long
): Long {
    fun remaining(timestamp: Long, cooldownMs: Long): Long {
        if (timestamp <= 0L) return 0L
        val elapsed = (nowMillis - timestamp).coerceAtLeast(0L)
        return (cooldownMs - elapsed).coerceAtLeast(0L)
    }
    val successRemaining = remaining(previousSuccessAtMillis, ARCHIVE_LIVE_FALLBACK_SUCCESS_COOLDOWN_MS)
    if (successRemaining > 0L) return successRemaining
    return remaining(previousAttemptAtMillis, ARCHIVE_LIVE_FALLBACK_RETRY_COOLDOWN_MS)
}

/** Used to queue, rather than silently abandon, a current-price fallback behind a live request. */
internal fun hasConflictingSerpApiWork(state: TrackerUiState): Boolean =
    state.isOnlineSearching ||
        state.isRefreshingPrices ||
        state.isPreparingSerpApiAction ||
        state.isExecutingSerpApiAction ||
        state.isCheckingSerpApiAccount

