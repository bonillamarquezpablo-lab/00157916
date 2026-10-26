package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                    text = "Elige qué tipo de producto quieres consultar. Los productos se cargarán al abrir cada apartado.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(
            items = subcategories.chunked(2),
            key = { row -> row.joinToString("-") { it.name } }
        ) { rowSubcategories ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                rowSubcategories.forEach { subcategory ->
                    val shape = RoundedCornerShape(18.dp)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .heightIn(min = 150.dp)
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                                shape
                            )
                            .clickable { onSelect(subcategory) }
                            .padding(14.dp)
                            .testTag("subcategory_card_${subcategory.name}"),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when (subcategory) {
                                    SmartphoneSubcategory.PHONES -> "M"
                                    SmartphoneSubcategory.TABLETS -> "T"
                                    SmartphoneSubcategory.POWER_BANKS -> "B"
                                    SmartphoneSubcategory.CHARGERS -> "C"
                                    SmartphoneSubcategory.CABLES -> "USB"
                                    SmartphoneSubcategory.CASES_PROTECTORS -> "F"
                                    SmartphoneSubcategory.OTHER_ACCESSORIES -> "+"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = if (subcategory == SmartphoneSubcategory.CABLES) 12.sp else 17.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            text = subcategory.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            minLines = 2
                        )
                        Text(
                            text = subcategory.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                        Spacer(modifier = Modifier.weight(1f).fillMaxHeight())
                        Text(
                            text = "Ver productos  ›",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (rowSubcategories.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
