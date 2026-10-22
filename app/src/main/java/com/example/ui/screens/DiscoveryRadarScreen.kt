package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.ProductDiscoveryEntity
import com.example.data.model.Product
import com.example.data.model.ProductCategory
import com.example.ui.components.ProductCard
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class RadarSection(val label: String) {
    DISCOVERIES("Descubrimientos"),
    MINIMA("Mínimos")
}

/**
 * Incidental archived products live here until the user explicitly imports them.
 * The existing ATH/Deals screen remains available as the second inner section.
 */
@Composable
fun DiscoveryRadarScreen(
    discoveries: List<ProductDiscoveryEntity>,
    athProducts: List<Product>,
    onAcceptDiscovery: (String) -> Unit,
    onDismissDiscovery: (String) -> Unit,
    onProductClick: (Product) -> Unit,
    onToggleTrack: (Product) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSection by rememberSaveable { mutableStateOf(RadarSection.DISCOVERIES) }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedSection.ordinal) {
            RadarSection.values().forEach { section ->
                Tab(
                    selected = selectedSection == section,
                    onClick = { selectedSection = section },
                    text = {
                        Text(
                            text = if (section == RadarSection.DISCOVERIES && discoveries.isNotEmpty()) {
                                "${section.label} · ${discoveries.size}"
                            } else section.label,
                            fontWeight = if (selectedSection == section) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        when (selectedSection) {
            RadarSection.DISCOVERIES -> DiscoveryList(
                discoveries = discoveries,
                onAcceptDiscovery = onAcceptDiscovery,
                onDismissDiscovery = onDismissDiscovery,
                modifier = Modifier.fillMaxSize()
            )
            RadarSection.MINIMA -> DealsScreen(
                athProducts = athProducts,
                onProductClick = onProductClick,
                onToggleTrack = onToggleTrack,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun DiscoveryList(
    discoveries: List<ProductDiscoveryEntity>,
    onAcceptDiscovery: (String) -> Unit,
    onDismissDiscovery: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.size(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Radar de descubrimientos",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${discoveries.size} producto(s) pendiente(s) de revisar",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = "Mínimo guarda aquí productos nuevos hallados en páginas históricas. No aparecen en Mercado ni se mezclan con tus precios hasta que los añadas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (discoveries.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Inventory2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(38.dp)
                        )
                        Text(
                            text = "Aún no hay descubrimientos",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Cuando una captura archivada contenga una oferta de otro producto claramente identificable, aparecerá aquí para que decidas si quieres incorporarla.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(discoveries, key = { it.id }) { discovery ->
                DiscoveryCard(
                    discovery = discovery,
                    onAccept = { onAcceptDiscovery(discovery.id) },
                    onDismiss = { onDismissDiscovery(discovery.id) }
                )
            }
        }
    }
}

@Composable
private fun DiscoveryCard(
    discovery: ProductDiscoveryEntity,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale("es", "ES"))
    val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("es", "ES"))
    val categoryName = ProductCategory.values()
        .firstOrNull { it.name == discovery.category }?.displayName
        ?: ProductCategory.OTHER.displayName

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = discovery.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${discovery.brand} · $categoryName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = currencyFormat.format(discovery.latestPrice),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = "${discovery.latestStoreName} · última captura ${dateFormat.format(Date(discovery.lastObservedAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (discovery.captureCount == 1) {
                    "1 observación histórica · Precio antiguo, no es una oferta actual verificada"
                } else {
                    "${discovery.captureCount} observación(es) histórica(s) · Precio antiguo, no es una oferta actual verificada"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onAccept,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.AddShoppingCart,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(7.dp))
                    Text("Añadir")
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Descartar")
                }
            }
        }
    }
}
