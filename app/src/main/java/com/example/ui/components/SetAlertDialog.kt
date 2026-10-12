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
import com.example.data.model.Product

@Composable
fun SetAlertDialog(
    product: Product,
    onDismiss: () -> Unit,
    onSaveAlert: (Double?) -> Unit
) {
    var targetPriceText by remember {
        mutableStateOf(
            product.alertTargetPrice?.let { String.format("%.2f", it) }
                ?: when {
                    product.hasSufficientHistory -> String.format("%.2f", product.athMinPrice)
                    product.isPriceVerified -> String.format("%.2f", product.currentPrice)
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
                    text = "Guardar Seguimiento de Precio",
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
                        product.hasSufficientHistory ->
                            "Precio observado: ${String.format("%.2f", product.currentPrice)} €\\nMínimo observado en los datos disponibles: ${String.format("%.2f", product.athMinPrice)} €.\\n\\nEl objetivo se guarda en este móvil; esta versión todavía no comprueba precios en segundo plano ni envía avisos automáticos."
                        product.isPriceVerified ->
                            "Precio observado: ${String.format("%.2f", product.currentPrice)} €. Aún no hay suficientes datos para calcular un mínimo histórico.\\n\\nEl objetivo se guarda en este móvil; esta versión todavía no comprueba precios en segundo plano ni envía avisos automáticos."
                        else ->
                            "Esta ficha contiene datos de demostración y no tiene un precio actual consultado. Puedes guardar un objetivo manual, pero esta versión todavía no comprueba precios en segundo plano ni envía avisos automáticos."
                    },
                    fontSize = 12.sp,
                    lineHeight = 16.sp
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
                if (product.isPriceVerified) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (product.hasSufficientHistory) {
                            SuggestionChip(
                                onClick = {
                                    targetPriceText = String.format("%.2f", product.athMinPrice)
                                },
                                label = { Text("Mínimo observado", fontSize = 10.sp) }
                            )
                            SuggestionChip(
                                onClick = {
                                    targetPriceText = String.format("%.2f", product.athMinPrice * 0.95)
                                },
                                label = { Text("-5% del mínimo observado", fontSize = 10.sp) }
                            )
                        } else {
                            SuggestionChip(
                                onClick = {
                                    targetPriceText = String.format("%.2f", product.currentPrice)
                                },
                                label = { Text("Precio actual", fontSize = 10.sp) }
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
