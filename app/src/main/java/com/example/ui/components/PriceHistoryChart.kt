package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PricePoint

enum class ChartRange(val label: String, val windowDays: Long?) {
    DAYS_30("30 días", 30),
    DAYS_90("90 días", 90),
    MONTHS_6("6 meses", 180),
    ALL("Todo el historial", null)
}

@Composable
fun PriceHistoryChart(
    priceHistory: List<PricePoint>,
    modifier: Modifier = Modifier
) {
    var selectedRange by remember { mutableStateOf(ChartRange.ALL) }
    var selectedPointIndex by remember { mutableIntStateOf(-1) }

    val filteredPoints = remember(priceHistory, selectedRange) {
        val startAt = selectedRange.windowDays?.let { days ->
            System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        }
        if (startAt == null) priceHistory
        else priceHistory.filter { it.timestamp >= startAt }
    }

    val minPriceInView = remember(filteredPoints) {
        filteredPoints.minOfOrNull { it.price } ?: 0.0
    }
    val maxPriceInView = remember(filteredPoints) {
        val max = filteredPoints.maxOfOrNull { it.price } ?: 1.0
        if (max == minPriceInView) max * 1.1 else max
    }
    val averagePriceInView = remember(filteredPoints) {
        filteredPoints.map { it.price }.average().takeIf { filteredPoints.isNotEmpty() } ?: 0.0
    }

    val selectedPoint = remember(selectedPointIndex, filteredPoints) {
        if (selectedPointIndex in filteredPoints.indices) {
            filteredPoints[selectedPointIndex]
        } else null
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("price_history_chart_container")
    ) {
        // Chart Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Evolución del precio observado",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Solo incluye observaciones verificadas guardadas en Mínimo.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Stat pills
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF10B981).copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (filteredPoints.isNotEmpty()) "Mín. del rango: ${String.format("%.2f", minPriceInView)}€" else "Mín. del rango: —",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF059669)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (filteredPoints.isNotEmpty()) "Media del rango: ${String.format("%.2f", averagePriceInView)}€" else "Media del rango: —",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Time Range Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ChartRange.values().forEach { range ->
                FilterChip(
                    selected = selectedRange == range,
                    onClick = {
                        selectedRange = range
                        selectedPointIndex = -1
                    },
                    label = { Text(range.label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.height(30.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Canvas Chart
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(filteredPoints) {
                        detectTapGestures { offset ->
                            if (filteredPoints.isNotEmpty()) {
                                val spacing = size.width / (filteredPoints.size - 1).coerceAtLeast(1)
                                val index = ((offset.x + (spacing / 2)) / spacing).toInt()
                                selectedPointIndex = index.coerceIn(0, filteredPoints.size - 1)
                            }
                        }
                    }
            ) {
                if (filteredPoints.isEmpty()) return@Canvas

                val width = size.width
                val height = size.height
                val paddingBottom = 24.dp.toPx()
                val paddingTop = 16.dp.toPx()
                val usableHeight = height - paddingBottom - paddingTop

                val priceRange = (maxPriceInView - minPriceInView).coerceAtLeast(1.0)
                val pointSpacing = if (filteredPoints.size > 1) width / (filteredPoints.size - 1) else width

                // Calculate point coordinates
                val coords = filteredPoints.mapIndexed { index, pt ->
                    val x = index * pointSpacing
                    val normalizedPrice = (pt.price - minPriceInView) / priceRange
                    val y = paddingTop + usableHeight - (normalizedPrice.toFloat() * usableHeight)
                    Offset(x, y)
                }

                // Draw the average of points inside the selected time window.
                if (filteredPoints.isNotEmpty()) {
                    val avgNormalized = ((averagePriceInView - minPriceInView) / priceRange).coerceIn(0.0, 1.0)
                    val avgY = paddingTop + usableHeight - (avgNormalized.toFloat() * usableHeight)
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.5f),
                        start = Offset(0f, avgY),
                        end = Offset(width, avgY),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                }

                // Fill gradient under curve
                val fillPath = Path().apply {
                    moveTo(coords.first().x, coords.first().y)
                    for (i in 1 until coords.size) {
                        val prev = coords[i - 1]
                        val curr = coords[i]
                        val cx = (prev.x + curr.x) / 2f
                        cubicTo(cx, prev.y, cx, curr.y, curr.x, curr.y)
                    }
                    lineTo(coords.last().x, height - paddingBottom)
                    lineTo(coords.first().x, height - paddingBottom)
                    close()
                }

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF10B981).copy(alpha = 0.35f),
                            Color(0xFF10B981).copy(alpha = 0.02f)
                        ),
                        startY = paddingTop,
                        endY = height - paddingBottom
                    )
                )

                // Stroke line
                val strokePath = Path().apply {
                    moveTo(coords.first().x, coords.first().y)
                    for (i in 1 until coords.size) {
                        val prev = coords[i - 1]
                        val curr = coords[i]
                        val cx = (prev.x + curr.x) / 2f
                        cubicTo(cx, prev.y, cx, curr.y, curr.x, curr.y)
                    }
                }

                drawPath(
                    path = strokePath,
                    color = Color(0xFF059669),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )

                // Draw node points
                coords.forEachIndexed { index, coord ->
                    val pt = filteredPoints[index]
                    val isRangeMinimum = pt.price <= (minPriceInView + 0.05)
                    val isSelected = index == selectedPointIndex

                    when {
                        isSelected -> {
                            drawCircle(
                                color = Color(0xFF0284C7),
                                radius = 7.dp.toPx(),
                                center = coord
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 3.dp.toPx(),
                                center = coord
                            )
                        }
                        isRangeMinimum -> {
                            // Lowest observed point inside the selected range
                            drawCircle(
                                color = Color(0xFF10B981),
                                radius = 6.dp.toPx(),
                                center = coord
                            )
                            drawCircle(
                                color = Color(0xFF064E3B),
                                radius = 2.5.dp.toPx(),
                                center = coord
                            )
                        }
                        else -> {
                            drawCircle(
                                color = Color(0xFF64748B),
                                radius = 3.5.dp.toPx(),
                                center = coord
                            )
                        }
                    }
                }
            }
            if (filteredPoints.isEmpty()) {
                Text(
                    text = "No hay observaciones verificadas en este intervalo.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }

        // Tap inspection panel
        if (selectedPoint != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp)),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${String.format("%.2f", selectedPoint.price)} €",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = if (selectedPoint.price <= minPriceInView + 0.05) Color(0xFF059669) else MaterialTheme.colorScheme.onSurface
                            )
                            if (selectedPoint.price <= minPriceInView + 0.05) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "★ MÍNIMO DEL RANGO",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF059669)
                                )
                            }
                        }
                        Text(
                            text = "${selectedPoint.dateLabel} en ${selectedPoint.storeName}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (selectedPoint.eventLabel != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = selectedPoint.eventLabel,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (filteredPoints.isNotEmpty()) "Toca el gráfico para consultar cada observación" else "Elige un rango con datos disponibles",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
