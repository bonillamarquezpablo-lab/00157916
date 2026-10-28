package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.SmartphoneSubcategory

/**
 * Category-only landing screen: product flows are not requested until a subcategory is opened.
 */
@Composable
fun SmartphoneSubcategoryHomeScreen(
    onBack: () -> Unit,
    onSelect: (SmartphoneSubcategory) -> Unit,
    modifier: Modifier = Modifier
) {
    val subcategories = SmartphoneSubcategory.values().toList()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("smartphone_subcategory_home"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("return_to_categories_button")
            ) {
                Text("Volver a categorías")
            }
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = "Móviles y tablets",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Elige el tipo de producto. La lista se carga al abrir cada apartado.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Compact horizontal rows keep the icon visible while giving each description
        // enough width to wrap naturally without consuming a tall two-column card.
        items(
            items = subcategories,
            key = { it.name }
        ) { subcategory ->
            val shape = RoundedCornerShape(14.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 76.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                        shape
                    )
                    .clickable { onSelect(subcategory) }
                    .padding(horizontal = 11.dp, vertical = 9.dp)
                    .testTag("subcategory_card_${subcategory.name}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(subcategoryIconBackground(subcategory)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = subcategoryIcon(subcategory),
                        contentDescription = "Icono de ${subcategory.title.lowercase()}",
                        modifier = Modifier.size(22.dp),
                        tint = subcategoryIconTint(subcategory)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = subcategory.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subcategory.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "Ver ›",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}


/** Vector icons are drawn locally: no bitmap assets, network loads or per-card image decoding. */
private fun subcategoryIcon(subcategory: SmartphoneSubcategory) = when (subcategory) {
    SmartphoneSubcategory.PHONES -> Icons.Default.Smartphone
    SmartphoneSubcategory.TABLETS -> Icons.Default.TabletAndroid
    SmartphoneSubcategory.POWER_BANKS -> Icons.Default.BatteryChargingFull
    SmartphoneSubcategory.CHARGERS -> Icons.Default.Power
    SmartphoneSubcategory.CABLES -> Icons.Default.Cable
    SmartphoneSubcategory.CASES_PROTECTORS -> Icons.Default.Shield
    SmartphoneSubcategory.OTHER_ACCESSORIES -> Icons.Default.DevicesOther
}

@Composable
private fun subcategoryIconBackground(subcategory: SmartphoneSubcategory) =
    when (subcategory) {
        SmartphoneSubcategory.PHONES,
        SmartphoneSubcategory.CHARGERS -> MaterialTheme.colorScheme.primaryContainer
        SmartphoneSubcategory.TABLETS,
        SmartphoneSubcategory.CASES_PROTECTORS -> MaterialTheme.colorScheme.secondaryContainer
        SmartphoneSubcategory.POWER_BANKS,
        SmartphoneSubcategory.OTHER_ACCESSORIES -> MaterialTheme.colorScheme.tertiaryContainer
        SmartphoneSubcategory.CABLES -> MaterialTheme.colorScheme.surfaceContainerHigh
    }

@Composable
private fun subcategoryIconTint(subcategory: SmartphoneSubcategory) =
    when (subcategory) {
        SmartphoneSubcategory.PHONES,
        SmartphoneSubcategory.CHARGERS -> MaterialTheme.colorScheme.onPrimaryContainer
        SmartphoneSubcategory.TABLETS,
        SmartphoneSubcategory.CASES_PROTECTORS -> MaterialTheme.colorScheme.onSecondaryContainer
        SmartphoneSubcategory.POWER_BANKS,
        SmartphoneSubcategory.OTHER_ACCESSORIES -> MaterialTheme.colorScheme.onTertiaryContainer
        SmartphoneSubcategory.CABLES -> MaterialTheme.colorScheme.onSurfaceVariant
    }
