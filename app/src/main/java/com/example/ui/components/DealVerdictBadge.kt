package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DealVerdict
import com.example.data.model.VerdictLevel

@Composable
fun DealVerdictBadge(
    verdict: DealVerdict,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }

    val icon = when (verdict.level) {
        VerdictLevel.ATH_RECORD -> Icons.Default.CheckCircle
        VerdictLevel.NEAR_ATH -> Icons.Default.TrendingDown
        VerdictLevel.FAIR_DISCOUNT -> Icons.Default.ArrowDownward
        VerdictLevel.INFLATED_FAKE_DEAL -> Icons.Default.Warning
        else -> Icons.Default.Info
    }

    val containerColor = verdict.color.copy(alpha = 0.12f)
    val borderColor = verdict.color.copy(alpha = 0.45f)

    if (compact) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(containerColor)
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = verdict.badgeText,
                tint = verdict.color,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = verdict.badgeText,
                color = verdict.color,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }
    } else {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .testTag("verdict_card"),
            color = containerColor
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(verdict.color.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = verdict.color,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = verdict.title,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp,
                                color = verdict.color
                            )
                            Text(
                                text = if (verdict.level == VerdictLevel.UNVERIFIED) {
                                    "Datos de demostración · sin consulta en directo"
                                } else {
                                    "Resultado basado en datos verificados"
                                },
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (verdict.level == VerdictLevel.UNVERIFIED) Icons.Default.Info else Icons.Default.Shield,
                            contentDescription = if (verdict.level == VerdictLevel.UNVERIFIED) "Sin verificar" else "Verificado",
                            tint = verdict.color,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (verdict.level == VerdictLevel.UNVERIFIED) "Sin verificar" else "Verificado",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = verdict.color
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = verdict.explanation,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            text = "💡 ¿Cómo funciona el análisis?",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "El análisis solo puede confirmar una oferta cuando existen observaciones de precio con fuente y fecha. En esta versión, el catálogo inicial contiene datos de demostración y no se consultan automáticamente las tiendas.",
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
