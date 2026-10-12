package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.example.ui.screens.ProductDetailSheet
import com.example.ui.screens.StoresScreen
import com.example.ui.screens.VerifierScreen
import com.example.ui.viewmodel.PriceTrackerViewModel

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

    // Handle back button when not on main tab
    BackHandler(enabled = currentTab != MainTab.MARKET) {
        currentTab = MainTab.MARKET
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Mínimo",
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp,
                        letterSpacing = (-0.5).sp
                    )
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .background(Color(0xFF10B981).copy(alpha = 0.15f), MaterialTheme.shapes.small)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${spanishStores.size} TIENDAS ESP",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF047857)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("main_bottom_nav"),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
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
