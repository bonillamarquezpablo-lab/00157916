package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import com.example.data.model.Product

@Composable
fun SetAlertDialog(
    product: Product,
    onDismiss: () -> Unit,
    onSaveAlert: (Double?) -> Unit
) {
    val spanishLocale = remember { Locale("es", "ES") }
    val hasCurrentVerifiedPrice =
        product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.VERIFIED_CURRENT
    val hasCurrentVerifiedHistory = hasCurrentVerifiedPrice && product.hasSufficientHistory
    val isDisplayingSavedArchivePrice =
        product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION &&
            !product.isPriceVerified && product.savedHistoricalPrice != null
    val savedPrice = when {
        isDisplayingSavedArchivePrice -> product.savedHistoricalPrice
        product.priceDisplayStatus == com.example.data.model.PriceDisplayStatus.SAVED_PENDING_REVALIDATION &&
            product.isPriceVerified -> product.currentPrice
        else -> null
    }
    var targetPriceText by remember(
        product.id,
        product.alertTargetPrice,
        product.priceDisplayStatus,
        product.currentPrice,
        product.athMinPrice,
        product.savedHistoricalPrice
    ) {
        mutableStateOf(
            product.alertTargetPrice?.let { String.format(spanishLocale, "%.2f", it) }
                ?: when {
                    hasCurrentVerifiedHistory -> String.format(spanishLocale, "%.2f", product.athMinPrice)
                    hasCurrentVerifiedPrice -> String.format(spanishLocale, "%.2f", product.currentPrice)
                    savedPrice != null -> String.format(spanishLocale, "%.2f", savedPrice)
                    else -> ""
                }
        )
    }
    val parsedTargetPrice = targetPriceText.replace(",", ".").toDoubleOrNull()
    val isTargetPriceValid = targetPriceText.isBlank() ||
        (parsedTargetPrice != null && parsedTargetPrice.isFinite() && parsedTargetPrice > 0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = Color(0xFF047857)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Alerta de precio",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = product.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = when {
                        hasCurrentVerifiedHistory ->
                            "Precio actual verificado: ${String.format(spanishLocale, "%.2f", product.currentPrice)} €. Mínimo observado: ${String.format(spanishLocale, "%.2f", product.athMinPrice)} €."
                        hasCurrentVerifiedPrice ->
                            "Precio actual verificado: ${String.format(spanishLocale, "%.2f", product.currentPrice)} €. El historial aún está en construcción."
                        savedPrice != null ->
                            "Último precio guardado: ${String.format(spanishLocale, "%.2f", savedPrice ?: product.currentPrice)} €. No es una oferta actual."
                        else ->
                            "Todavía no hay un precio verificado. Puedes establecer un objetivo manualmente."
                    },
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Text(
                    text = "El objetivo se guarda en este móvil. Las revisiones automáticas requieren una clave SerpApi válida y conexión; Android puede retrasarlas. Activa las notificaciones para recibir avisos.",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = targetPriceText,
                    onValueChange = { targetPriceText = it },
                    label = { Text("Precio objetivo deseado (€)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("alert_target_input")
                )

                // Only offer a historic-low preset after sufficient history exists.
                if (hasCurrentVerifiedPrice || savedPrice != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (hasCurrentVerifiedHistory) {
                            SuggestionChip(
                                onClick = {
                                    targetPriceText = String.format(spanishLocale, "%.2f", product.athMinPrice)
                                },
                                label = { Text("Mínimo observado", fontSize = 10.sp) }
                            )
                            SuggestionChip(
                                onClick = {
                                    targetPriceText = String.format(spanishLocale, "%.2f", product.athMinPrice * 0.95)
                                },
                                label = { Text("-5% del mínimo observado", fontSize = 10.sp) }
                            )
                        } else if (hasCurrentVerifiedPrice) {
                            SuggestionChip(
                                onClick = {
                                    targetPriceText = String.format(spanishLocale, "%.2f", product.currentPrice)
                                },
                                label = { Text("Precio actual", fontSize = 10.sp) }
                            )
                        } else if (savedPrice != null) {
                            SuggestionChip(
                                onClick = {
                                    targetPriceText = String.format(spanishLocale, "%.2f", savedPrice ?: product.currentPrice)
                                },
                                label = { Text("Último guardado", fontSize = 10.sp) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = targetPriceText.replace(",", ".").toDoubleOrNull()
                    onSaveAlert(parsed)
                },
                enabled = isTargetPriceValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF047857)
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("save_alert_button")
            ) {
                Text("Guardar objetivo", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Cancelar")
            }
        }
    )
}
