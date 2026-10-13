package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

enum class ChartRange(val label: String, val takePoints: Int) {
    DAYS_30("30 Días", 3),
    DAYS_90("90 Días", 5),
    YEAR_1("1 Año", 8),
    YEAR_2("2 Años", 14),
    YEARS_4("4+ Años", 100)
}

enum class ChartLineMode(val label: String) {
    SMOOTH("Suave"),
    STEPPED("Escalonado")
}

@Composable
fun PriceHistoryChart(
    priceHistory: List<PricePoint>,
    athMinPrice: Double,
    avg90dPrice: Double,
    modifier: Modifier = Modifier
) {
    var selectedRange by remember { mutableStateOf(ChartRange.YEARS_4) }
    var selectedLineMode by remember { mutableStateOf(ChartLineMode.SMOOTH) }
    var selectedPointIndex by remember { mutableIntStateOf(-1) }

    val filteredPoints = remember(priceHistory, selectedRange) {
        if (priceHistory.isEmpty()) emptyList()
        else {
            val count = minOf(priceHistory.size, selectedRange.takePoints)
            priceHistory.takeLast(count)
        }
    }

    val minPriceInView = remember(filteredPoints) {
        val min = filteredPoints.minOfOrNull { it.price } ?: athMinPrice
        // Leave a 5% margin at bottom
        (min * 0.95).coerceAtLeast(0.0)
    }

    val maxPriceInView = remember(filteredPoints) {
        val max = filteredPoints.maxOfOrNull { it.price } ?: (athMinPrice * 1.3)
        // Leave a 5% margin at top
        max * 1.05
    }

    val midPriceInView = remember(minPriceInView, maxPriceInView) {
        (minPriceInView + maxPriceInView) / 2.0
    }

    // Default to the last point or the currently scrubbed point
    val activePoint = remember(selectedPointIndex, filteredPoints) {
        if (selectedPointIndex in filteredPoints.indices) {
            filteredPoints[selectedPointIndex]
        } else {
            filteredPoints.lastOrNull()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Timeline,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Evolución Real del Precio",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "Historial continuo de más de 4 años auditado en España",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Mode Toggle (Suave vs Escalonado)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selectedLineMode == ChartLineMode.SMOOTH) Color(0xFF047857) else Color.Transparent)
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "Curva",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedLineMode == ChartLineMode.SMOOTH) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures { selectedLineMode = ChartLineMode.SMOOTH }
                        }
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selectedLineMode == ChartLineMode.STEPPED) Color(0xFF047857) else Color.Transparent)
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "Escalón",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedLineMode == ChartLineMode.STEPPED) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures { selectedLineMode = ChartLineMode.STEPPED }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Range Filter Chips
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
                        selectedContainerColor = Color(0xFF047857),
                        selectedLabelColor = Color.White
                    ),
                    modifier = Modifier.height(30.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Dynamic Interactive Tooltip Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF10B981).copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surface
        ) {
            if (activePoint != null) {
                val isPointAth = activePoint.price <= athMinPrice + 0.05
                val diffFromAth = activePoint.price - athMinPrice

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${String.format("%.2f", activePoint.price)} €",
                                fontWeight = FontWeight.Black,
                                fontSize = 17.sp,
                                color = if (isPointAth) Color(0xFF059669) else MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            if (isPointAth) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF059669))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "★ MÍNIMO HISTÓRICO",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            } else {
                                Text(
                                    text = "+${String.format("%.2f", diffFromAth)} € vs récord",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFD97706)
                                )
                            }
                        }

                        Text(
                            text = "${activePoint.dateLabel} • ${activePoint.storeName}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (activePoint.eventLabel != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF10B981).copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = activePoint.eventLabel,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF047857)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Main Line Chart Area with Y-Axis and Canvas
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        ) {
            // Interactive Canvas Line Chart
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(filteredPoints) {
                            detectTapGestures { offset ->
                                if (filteredPoints.isNotEmpty()) {
                                    val spacing = size.width / (filteredPoints.size - 1).coerceAtLeast(1)
                                    val index = ((offset.x + (spacing / 2)) / spacing).toInt()
                                    selectedPointIndex = index.coerceIn(0, filteredPoints.size - 1)
                                }
                            }
                        }
                        .pointerInput(filteredPoints) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    if (filteredPoints.isNotEmpty()) {
                                        val spacing = size.width / (filteredPoints.size - 1).coerceAtLeast(1)
                                        val index = ((offset.x + (spacing / 2)) / spacing).toInt()
                                        selectedPointIndex = index.coerceIn(0, filteredPoints.size - 1)
                                    }
                                },
                                onDrag = { change, _ ->
                                    if (filteredPoints.isNotEmpty()) {
                                        val spacing = size.width / (filteredPoints.size - 1).coerceAtLeast(1)
                                        val index = ((change.position.x + (spacing / 2)) / spacing).toInt()
                                        selectedPointIndex = index.coerceIn(0, filteredPoints.size - 1)
                                    }
                                }
                            )
                        }
                ) {
                    if (filteredPoints.isEmpty()) return@Canvas

                    val width = size.width
                    val height = size.height
                    val paddingTop = 12.dp.toPx()
                    val paddingBottom = 16.dp.toPx()
                    val usableHeight = height - paddingTop - paddingBottom

                    val priceRange = (maxPriceInView - minPriceInView).coerceAtLeast(1.0)
                    val pointSpacing = if (filteredPoints.size > 1) width / (filteredPoints.size - 1) else width

                    // Horizontal Dotted Grid Lines (Top, Mid, Bottom)
                    val yTop = paddingTop
                    val yMid = paddingTop + (usableHeight / 2)
                    val yBottom = paddingTop + usableHeight

                    val gridPaint = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

                    drawLine(
                        color = Color.Gray.copy(alpha = 0.2f),
                        start = Offset(0f, yTop),
                        end = Offset(width, yTop),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = gridPaint
                    )
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.2f),
                        start = Offset(0f, yMid),
                        end = Offset(width, yMid),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = gridPaint
                    )
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.2f),
                        start = Offset(0f, yBottom),
                        end = Offset(width, yBottom),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = gridPaint
                    )

                    // Average Price Dotted Guideline (Amber/Yellow)
                    val avgNormalized = ((avg90dPrice - minPriceInView) / priceRange).coerceIn(0.0, 1.0)
                    val avgY = paddingTop + usableHeight - (avgNormalized.toFloat() * usableHeight)
                    drawLine(
                        color = Color(0xFFD97706).copy(alpha = 0.55f),
                        start = Offset(0f, avgY),
                        end = Offset(width, avgY),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                    )

                    // Historical Minimum Guideline (Emerald Green)
                    val athNormalized = ((athMinPrice - minPriceInView) / priceRange).coerceIn(0.0, 1.0)
                    val athY = paddingTop + usableHeight - (athNormalized.toFloat() * usableHeight)
                    drawLine(
                        color = Color(0xFF10B981).copy(alpha = 0.65f),
                        start = Offset(0f, athY),
                        end = Offset(width, athY),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                    )

                    // Calculate point coordinates
                    val coords = filteredPoints.mapIndexed { index, pt ->
                        val x = index * pointSpacing
                        val normalizedPrice = ((pt.price - minPriceInView) / priceRange).coerceIn(0.0, 1.0)
                        val y = paddingTop + usableHeight - (normalizedPrice.toFloat() * usableHeight)
                        Offset(x, y)
                    }

                    // Build Curve / Stepped Path
                    val linePath = Path()
                    val fillPath = Path()

                    if (coords.isNotEmpty()) {
                        linePath.moveTo(coords.first().x, coords.first().y)
                        fillPath.moveTo(coords.first().x, coords.first().y)

                        if (selectedLineMode == ChartLineMode.SMOOTH) {
                            for (i in 1 until coords.size) {
                                val prev = coords[i - 1]
                                val curr = coords[i]
                                val cx = (prev.x + curr.x) / 2f
                                linePath.cubicTo(cx, prev.y, cx, curr.y, curr.x, curr.y)
                                fillPath.cubicTo(cx, prev.y, cx, curr.y, curr.x, curr.y)
                            }
                        } else {
                            // Stepped Line Path (reflects discrete price changes in retail)
                            for (i in 1 until coords.size) {
                                val prev = coords[i - 1]
                                val curr = coords[i]
                                linePath.lineTo(curr.x, prev.y)
                                linePath.lineTo(curr.x, curr.y)

                                fillPath.lineTo(curr.x, prev.y)
                                fillPath.lineTo(curr.x, curr.y)
                            }
                        }

                        // Close fill area towards chart bottom
                        fillPath.lineTo(coords.last().x, height - paddingBottom)
                        fillPath.lineTo(coords.first().x, height - paddingBottom)
                        fillPath.close()

                        // Draw Gradient Fill beneath line
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF10B981).copy(alpha = 0.38f),
                                    Color(0xFF047857).copy(alpha = 0.12f),
                                    Color.Transparent
                                ),
                                startY = paddingTop,
                                endY = height - paddingBottom
                            )
                        )

                        // Draw Main Line Stroke
                        drawPath(
                            path = linePath,
                            color = Color(0xFF059669),
                            style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    // Draw Node points
                    coords.forEachIndexed { index, coord ->
                        val pt = filteredPoints[index]
                        val isAth = pt.price <= (athMinPrice + 0.05)
                        val isSelected = (index == selectedPointIndex) || (selectedPointIndex == -1 && index == coords.lastIndex)

                        if (isAth) {
                            // Highlight ATH point with green outer ring
                            drawCircle(
                                color = Color(0xFF10B981),
                                radius = 7.dp.toPx(),
                                center = coord
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 3.dp.toPx(),
                                center = coord
                            )
                        } else if (!isSelected) {
                            drawCircle(
                                color = Color(0xFF059669),
                                radius = 3.5.dp.toPx(),
                                center = coord
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 1.5.dp.toPx(),
                                center = coord
                            )
                        }
                    }

                    // Draw Interactive Cursor (Vertical Crosshair + Glowing Dot)
                    val activeIndex = if (selectedPointIndex in coords.indices) selectedPointIndex else (coords.lastIndex)
                    if (activeIndex in coords.indices) {
                        val activeCoord = coords[activeIndex]

                        // Vertical dashed crosshair line
                        drawLine(
                            color = Color(0xFF0284C7).copy(alpha = 0.7f),
                            start = Offset(activeCoord.x, paddingTop),
                            end = Offset(activeCoord.x, height - paddingBottom),
                            strokeWidth = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                        )

                        // Outer glowing cursor ring
                        drawCircle(
                            color = Color(0xFF0284C7).copy(alpha = 0.25f),
                            radius = 12.dp.toPx(),
                            center = activeCoord
                        )
                        // Middle cursor ring
                        drawCircle(
                            color = Color(0xFF0284C7),
                            radius = 6.dp.toPx(),
                            center = activeCoord
                        )
                        // Inner white pupil
                        drawCircle(
                            color = Color.White,
                            radius = 3.dp.toPx(),
                            center = activeCoord
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Y-Axis Scale Labels (Native Compose layout)
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "${String.format("%.0f", maxPriceInView)} €",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${String.format("%.0f", midPriceInView)} €",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Text(
                    text = "${String.format("%.0f", minPriceInView)} €",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF059669)
                )
            }
        }

        // X-Axis Timeline Dates (Earliest, Mid, Today)
        if (filteredPoints.size >= 2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = filteredPoints.first().dateLabel,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (filteredPoints.size > 2) {
                    val midPoint = filteredPoints[filteredPoints.size / 2]
                    Text(
                        text = midPoint.dateLabel,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = filteredPoints.last().dateLabel,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Bottom Stats Reference Banner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF10B981))
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Mínimo: ${String.format("%.0f", athMinPrice)} €",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF047857)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFD97706))
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Media 90d: ${String.format("%.0f", avg90dPrice)} €",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "Arrastra para explorar",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
