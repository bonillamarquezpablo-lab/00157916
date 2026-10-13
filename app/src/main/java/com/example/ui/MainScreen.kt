package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.SetAlertDialog
import com.example.ui.screens.AlertsScreen
import com.example.ui.screens.DealsScreen
import com.example.ui.screens.MarketScreen
import com.example.ui.screens.OnlineSearchScreen
import com.example.ui.screens.ProductDetailSheet
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StoresScreen
import com.example.ui.screens.VerifierScreen
import com.example.ui.viewmodel.PriceTrackerViewModel
import com.example.ui.theme.FloatingSurfaceShape

enum class MainTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    MARKET("Mercado", Icons.Filled.Store, Icons.Outlined.Storefront),
    ATH_DEALS("Mínimos", Icons.Filled.CheckCircle, Icons.Outlined.CheckCircleOutline),
    STORES("Tiendas", Icons.Filled.Business, Icons.Outlined.Business),
    VERIFIER("Auditor", Icons.Filled.Shield, Icons.Outlined.Shield),
    ALERTS("Alertas", Icons.Filled.Bookmark, Icons.Outlined.BookmarkBorder)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: PriceTrackerViewModel
) {
    var currentTab by remember { mutableStateOf(MainTab.MARKET) }
    var showSettings by remember { mutableStateOf(false) }
    var showOnlineSearch by remember { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filteredProducts by viewModel.filteredProducts.collectAsStateWithLifecycle()
    val trackedProducts by viewModel.trackedProducts.collectAsStateWithLifecycle()
    val todayAthDeals by viewModel.todayAthDeals.collectAsStateWithLifecycle()
    val spanishStores by viewModel.spanishStores.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbarMessage()
        }
    }

    // Return from settings before leaving the current marketplace section.
    BackHandler(enabled = showSettings || showOnlineSearch || currentTab != MainTab.MARKET) {
        when {
            showSettings -> showSettings = false
            showOnlineSearch -> showOnlineSearch = false
            else -> currentTab = MainTab.MARKET
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (showSettings || showOnlineSearch) {
                        IconButton(onClick = { if (showSettings) showSettings = false else showOnlineSearch = false }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                        }
                    }
                },
                title = {
                    Text(
                        text = when {
                            showSettings -> "Ajustes"
                            showOnlineSearch -> "Buscador online"
                            else -> "Mínimo"
                        },
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp,
                        letterSpacing = (-0.5).sp
                    )
                },
                actions = {
                    if (!showSettings && !showOnlineSearch) {
                        IconButton(
                            onClick = { showOnlineSearch = true },
                            modifier = Modifier.padding(end = 1.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Buscar productos online",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Surface(
                            modifier = Modifier.padding(end = 4.dp),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "2 fuentes online",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        IconButton(
                            onClick = { showSettings = true },
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Abrir ajustes",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            if (!showSettings && !showOnlineSearch) {
            NavigationBar(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 12.dp, vertical = 5.dp)
                    .clip(FloatingSurfaceShape)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        shape = FloatingSurfaceShape
                    )
                    .testTag("main_bottom_nav"),
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 3.dp
            ) {
                MainTab.values().forEach { tab ->
                    val isSelected = currentTab == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentTab = tab },
                        icon = {
                            if (tab == MainTab.ATH_DEALS && todayAthDeals.isNotEmpty()) {
                                BadgedBox(
                                    badge = {
                                        Badge(
                                            containerColor = Color(0xFF059669),
                                            contentColor = Color.White
                                        ) {
                                            Text("${todayAthDeals.size}")
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.title,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            } else if (tab == MainTab.ALERTS && trackedProducts.isNotEmpty()) {
                                BadgedBox(
                                    badge = {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        ) {
                                            Text("${trackedProducts.size}")
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.title,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.title,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF047857),
                            selectedTextColor = Color(0xFF047857),
                            indicatorColor = Color(0xFF10B981).copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                    )
                }
            }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (showSettings) {
                SettingsScreen(
                    storeCount = spanishStores.size,
                    isRefreshingPrices = uiState.isRefreshingPrices,
                    isTestingSingleProduct = uiState.isTestingSingleProduct,
                    priceRefreshStatus = uiState.priceRefreshStatus,
                    onRefreshPrices = viewModel::refreshLivePrices,
                    isCheckingSerpApiAccount = uiState.isCheckingSerpApiAccount,
                    serpApiAccountStatus = uiState.serpApiAccountStatus,
                    onCheckSerpApiAccount = viewModel::checkSerpApiAccount
                )
            } else if (showOnlineSearch) {
                OnlineSearchScreen(
                    state = uiState,
                    onQueryChange = viewModel::onOnlineSearchQueryChange,
                    onSearch = viewModel::searchOnlineShopping,
                    onAmazonSearch = viewModel::searchAmazonShopping,
                    onAmazonOnlyChange = viewModel::onOnlineSearchAmazonOnlyChange,
                    onOpenSettings = {
                        showOnlineSearch = false
                        showSettings = true
                    },
                    onOpenProduct = viewModel::onOnlineProductSelected
                )
            } else when (currentTab) {
                MainTab.MARKET -> {
                    MarketScreen(
                        products = filteredProducts,
                        stores = spanishStores,
                        uiState = uiState,
                        onSearchChange = viewModel::onSearchQueryChange,
                        onCategorySelect = viewModel::onCategorySelect,
                        onToggleOnlyAth = viewModel::onToggleOnlyAth,
                        onStoreFilterSelect = viewModel::onStoreFilterSelect,
                        onSortChange = viewModel::onSortOptionChange,
                        onProductClick = viewModel::onSelectProduct,
                        onToggleTrack = { prod -> viewModel.toggleTracking(prod) }
                    )
                }
                MainTab.ATH_DEALS -> {
                    DealsScreen(
                        athProducts = todayAthDeals,
                        onProductClick = viewModel::onSelectProduct,
                        onToggleTrack = { prod -> viewModel.toggleTracking(prod) }
                    )
                }
                MainTab.STORES -> {
                    StoresScreen(
                        stores = spanishStores,
                        onSelectStoreForMarket = { storeName ->
                            viewModel.onStoreFilterSelect(storeName)
                            currentTab = MainTab.MARKET
                        }
                    )
                }
                MainTab.VERIFIER -> {
                    VerifierScreen(
                        inputText = uiState.verifierInput,
                        onInputChange = viewModel::onVerifierInputChange,
                        onVerifyClick = viewModel::runUrlOrReferenceVerification,
                        isVerifying = uiState.isVerifying,
                        analysisResult = uiState.verifierResult,
                        onClearResult = viewModel::clearVerifierResult,
                        onSelectProduct = viewModel::onSelectProduct,
                        onToggleTrack = { prod -> viewModel.toggleTracking(prod) }
                    )
                }
                MainTab.ALERTS -> {
                    AlertsScreen(
                        trackedProducts = trackedProducts,
                        onProductClick = viewModel::onSelectProduct,
                        onToggleTrack = { prod -> viewModel.toggleTracking(prod) },
                        onEditAlert = viewModel::onOpenAlertSetting,
                        onSimulatePriceDrop = viewModel::simulatePriceDrop,
                        onNavigateToMarket = { currentTab = MainTab.MARKET }
                    )
                }
            }
        }
    }

    // Modal Product Detail Sheet
    uiState.selectedProduct?.let { product ->
        ProductDetailSheet(
            product = product,
            onDismiss = { viewModel.onSelectProduct(null) },
            onOpenSetAlert = {
                viewModel.onOpenAlertSetting(product)
            },
            onSimulatePriceDrop = {
                viewModel.simulatePriceDrop(product.id)
            }
        )
    }

    // Set Alert Dialog
    uiState.isSettingAlert?.let { product ->
        SetAlertDialog(
            product = product,
            onDismiss = { viewModel.onOpenAlertSetting(null) },
            onSaveAlert = { targetPrice ->
                viewModel.saveTargetAlertPrice(product, targetPrice)
            }
        )
    }
}
