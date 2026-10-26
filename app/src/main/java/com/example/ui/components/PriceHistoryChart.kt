package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.data.model.sanitizePriceHistory
import com.example.data.model.summarizePriceHistory
import com.example.ui.theme.FloatingSurfaceShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatChartPrice(price: Double): String =
    String.format(Locale("es", "ES"), "%.2f", price)

enum class ChartRange(val label: String, val days: Int?) {
    DAYS_30("30D", 30),
    DAYS_90("90D", 90),
    MONTHS_6("6M", 183),
    YEARS_1("1A", 365),
    YEARS_2("2A", 730),
    YEARS_3("3A", 1095),
    YEARS_4("4A", 1460),
    ALL("Todo", null)
}

/**
 * Filter actual observations by elapsed time. [nowMillis] is injectable for deterministic tests.
 * Future-dated points are not included in a time window.
 */
internal fun filterPricePointsForRange(
    priceHistory: List<PricePoint>,
    selectedRange: ChartRange,
    nowMillis: Long = System.currentTimeMillis()
): List<PricePoint> {
    val days = selectedRange.days ?: return priceHistory.filter { it.timestamp <= nowMillis }
    val windowMs = days.toLong() * 24L * 60L * 60L * 1000L
    val startAt = nowMillis - windowMs
    return priceHistory.filter { it.timestamp in startAt..nowMillis }
}

private enum class HistoryEvidenceSeries(val label: String, val description: String) {
    LIVE("Mínimo", "Observaciones válidas registradas por Mínimo; base de mínimos y medias."),
    WAYBACK("Wayback", "Capturas externas de Internet Archive; no son ofertas actuales."),
    GOOGLE_INSIGHTS("Google histórico", "Datos históricos externos de Google Shopping; no son cotizaciones en vivo.")
}

/**
 * A monotone cubic Bezier path: smoother than straight segments, with tangent limiting so
 * visual interpolation does not create artificial new highs/lows between captured observations.
 * The chart remains an O(n) Canvas draw, with no per-frame animation or blur filter.
 */
private fun createMonotoneBezierPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    if (points.size < 2) return path

    val count = points.size
    val dx = FloatArray(count - 1) { index ->
        (points[index + 1].x - points[index].x).coerceAtLeast(0.1f)
    }
    val secants = FloatArray(count - 1) { index ->
        (points[index + 1].y - points[index].y) / dx[index]
    }
    val tangents = FloatArray(count)
    tangents[0] = secants.first()
    tangents[count - 1] = secants.last()

    for (index in 1 until count - 1) {
        val before = secants[index - 1]
        val after = secants[index]
        if (before == 0f || after == 0f || before * after <= 0f) {
            tangents[index] = 0f
        } else {
            val beforeDx = dx[index - 1]
            val afterDx = dx[index]
            val w1 = 2f * afterDx + beforeDx
            val w2 = afterDx + 2f * beforeDx
            tangents[index] = (w1 + w2) / (w1 / before + w2 / after)
        }
    }

    for (index in 0 until count - 1) {
        val start = points[index]
        val end = points[index + 1]
        val third = dx[index] / 3f
        path.cubicTo(
            start.x + third,
            start.y + tangents[index] * third,
            end.x - third,
            end.y - tangents[index + 1] * third,
            end.x,
            end.y
        )
    }
    return path
}

@Composable
fun PriceHistoryChart(
    priceHistory: List<PricePoint>,
    avg90dPrice: Double,
    modifier: Modifier = Modifier,
    archiveHistory: List<PricePoint> = emptyList(),
    googleInsightHistory: List<PricePoint> = emptyList()
) {
    var selectedRange by remember { mutableStateOf(ChartRange.ALL) }
    var selectedSeries by remember { mutableStateOf(HistoryEvidenceSeries.LIVE) }
    var userSelectedSeries by remember { mutableStateOf(false) }
    var selectedPointIndex by remember { mutableIntStateOf(-1) }

    val availableSeries = buildList {
        add(HistoryEvidenceSeries.LIVE)
        if (archiveHistory.isNotEmpty()) add(HistoryEvidenceSeries.WAYBACK)
        if (googleInsightHistory.isNotEmpty()) add(HistoryEvidenceSeries.GOOGLE_INSIGHTS)
    }
    val validLivePointsForDefault = remember(priceHistory) {
        priceHistory.filter { it.timestamp > 0L && it.price.isFinite() && it.price > 0.0 }
    }
    val validArchivePointsForDefault = remember(archiveHistory) {
        archiveHistory.filter { it.timestamp > 0L && it.price.isFinite() && it.price > 0.0 }
    }
    val validGooglePointsForDefault = remember(googleInsightHistory) {
        googleInsightHistory.filter { it.timestamp > 0L && it.price.isFinite() && it.price > 0.0 }
    }
    val liveOldestForDefault = validLivePointsForDefault.minOfOrNull { it.timestamp }
    val archiveOldestForDefault = validArchivePointsForDefault.minOfOrNull { it.timestamp }
    val googleOldestForDefault = validGooglePointsForDefault.minOfOrNull { it.timestamp }
    val archiveExtendsTimeline = archiveOldestForDefault != null &&
        (liveOldestForDefault == null || archiveOldestForDefault < liveOldestForDefault)
    val googleExtendsTimeline = googleOldestForDefault != null &&
        (liveOldestForDefault == null || googleOldestForDefault < liveOldestForDefault)

    LaunchedEffect(
        availableSeries,
        selectedSeries,
        userSelectedSeries,
        validLivePointsForDefault.size,
        archiveOldestForDefault,
        googleOldestForDefault
    ) {
        val liveCount = validLivePointsForDefault.size
        val preferredSeries = when {
            archiveHistory.isNotEmpty() && (liveCount < 2 || archiveExtendsTimeline) ->
                HistoryEvidenceSeries.WAYBACK
            googleInsightHistory.isNotEmpty() && (liveCount < 2 || googleExtendsTimeline) ->
                HistoryEvidenceSeries.GOOGLE_INSIGHTS
            else -> HistoryEvidenceSeries.LIVE
        }
        if (selectedSeries !in availableSeries) {
            selectedSeries = preferredSeries
            userSelectedSeries = false
            selectedPointIndex = -1
        } else if (!userSelectedSeries && selectedSeries != preferredSeries) {
            // Prefer the longest available timeline automatically. A user's explicit series
            // selection always wins, and archives never merge into the live-price calculations.
            selectedSeries = preferredSeries
            selectedPointIndex = -1
        }
    }
    val selectedHistory = when (selectedSeries) {    val selectedHistory = when (selectedSeries) {
        HistoryEvidenceSeries.LIVE -> priceHistory
        HistoryEvidenceSeries.WAYBACK -> archiveHistory
        HistoryEvidenceSeries.GOOGLE_INSIGHTS -> googleInsightHistory
    }
    val validHistory = remember(selectedHistory, selectedSeries) { sanitizePriceHistory(selectedHistory) }
    val filteredPoints = remember(validHistory, selectedRange) {
        filterPricePointsForRange(validHistory, selectedRange)
    }
    val historySummary = remember(filteredPoints) { summarizePriceHistory(filteredPoints) }
    val valid90dAverage = avg90dPrice.takeIf {
        selectedSeries == HistoryEvidenceSeries.LIVE && it.isFinite() && it > 0.0
    }
    // Cache this summary for ordinary chip/selection recompositions; historical series can
    // contain many observations but the average only changes when the filtered data changes.
    val externalHistoricalAverage = remember(filteredPoints) {
        filteredPoints.asSequence().map { it.price }.average().takeIf { it.isFinite() }
    }

    // All labels and historical claims follow the currently selected range.
    val firstObservationLabel = remember(validHistory, historySummary, filteredPoints) {
        when {
            filteredPoints.isEmpty() && validHistory.isEmpty() -> "Sin datos todavía"
            filteredPoints.isEmpty() -> "Sin datos en este rango"
            else -> historySummary.firstObservedAt?.let { timestamp ->
                "Historial desde " + SimpleDateFormat("dd MMM yyyy", Locale("es", "ES"))
                    .format(Date(timestamp))
            } ?: "Sin datos todavía"
        }
    }

    val minPriceInView = remember(filteredPoints) {
        val min = filteredPoints.minOfOrNull { it.price } ?: 1.0
        val max = filteredPoints.maxOfOrNull { it.price } ?: (min * 1.3)
        // With one observation (or identical prices), give the point vertical breathing room.
        if (filteredPoints.isNotEmpty() && min == max) (min * 0.9).coerceAtLeast(0.0) else min
    }
    val maxPriceInView = remember(filteredPoints) {
        val min = filteredPoints.minOfOrNull { it.price } ?: 1.0
        val max = filteredPoints.maxOfOrNull { it.price } ?: (min * 1.3)
        if (filteredPoints.isNotEmpty() && min == max) max * 1.1 else max
    }

    val selectedPoint = remember(selectedPointIndex, filteredPoints) {
        when {
            selectedPointIndex in filteredPoints.indices -> filteredPoints[selectedPointIndex]
            // The most recent real observation is the useful default selection for a series.
            // This makes a restored history visibly useful immediately after opening the sheet,
            // instead of showing only dots until the user happens to tap the canvas. A later tap
            // still selects the exact point requested by the user.
            else -> filteredPoints.lastOrNull()
        }
    }
    // Follow the app's current theme accent (amber in Mínimo) instead of a fixed emerald line.
    val chartAccent = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(FloatingSurfaceShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), FloatingSurfaceShape)
            .padding(16.dp)
            .testTag("price_history_chart_container")
    ) {
        // Keep the title on a full-width line and put the metric chips in their own
        // horizontally scrollable row. A single Row for both title and long metrics gave the
        // metrics almost zero width on portrait sheets, wrapping every character vertically.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = when (selectedSeries) {
                        HistoryEvidenceSeries.LIVE -> "Evolución del precio observado"
                        HistoryEvidenceSeries.WAYBACK -> "Histórico externo · Wayback"
                        HistoryEvidenceSeries.GOOGLE_INSIGHTS -> "Histórico externo · Google"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = firstObservationLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = selectedSeries.description,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // A single quote is shown as a count, never dressed up as a historical minimum.
            if (filteredPoints.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                (if (historySummary.minimumObservedPoint != null) Color(0xFF10B981)
                                else MaterialTheme.colorScheme.outline).copy(alpha = 0.15f)
                            )
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        val observedMinimum = historySummary.minimumObservedPoint
                        Text(
                            text = observedMinimum?.let {
                                "Mín. del rango: ${formatChartPrice(it.price)} €"
                            } ?: "${historySummary.observationCount} observación${if (historySummary.observationCount == 1) "" else "es"}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (observedMinimum != null) Color(0xFF059669)
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (selectedSeries == HistoryEvidenceSeries.LIVE) {
                                "Media 90 d: ${valid90dAverage?.let { formatChartPrice(it) + " €" } ?: "—"}"
                            } else {
                                "Media histórica externa: ${externalHistoricalAverage?.let { formatChartPrice(it) + " €" } ?: "—"}"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (availableSeries.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                availableSeries.forEach { series ->
                    FilterChip(
                        selected = selectedSeries == series,
                        onClick = {
                            userSelectedSeries = true
                            selectedSeries = series
                            selectedPointIndex = -1
                        },
                        label = { Text(series.label, fontSize = 11.sp) },
                        modifier = Modifier.heightIn(min = 36.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Time Range Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
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

        // Tap inspection panel
        if (selectedPoint != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(12.dp)),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = formatChartPrice(selectedPoint.price) + " €",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = if (historySummary.minimumObservedPoint?.let { selectedPoint.price <= it.price + 0.005 } == true) Color(0xFF059669) else MaterialTheme.colorScheme.onSurface
                            )
                            if (historySummary.minimumObservedPoint?.let { selectedPoint.price <= it.price + 0.005 } == true) {
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
                    text = if (filteredPoints.isEmpty()) {
                        "No hay observaciones para este rango."
                    } else {
                        "Toca sobre el gráfico para ver el precio exacto en cada fecha"
                    },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Canvas Chart
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(filteredPoints) {
                        detectTapGestures { offset ->
                            if (filteredPoints.isNotEmpty()) {
                                val firstTimestamp = filteredPoints.first().timestamp
                                val timestampSpan = (filteredPoints.last().timestamp - firstTimestamp)
                                    .coerceAtLeast(1L).toDouble()
                                selectedPointIndex = filteredPoints.indices.minByOrNull { index ->
                                    val pointX = if (filteredPoints.size == 1) {
                                        size.width / 2f
                                    } else {
                                        (((filteredPoints[index].timestamp - firstTimestamp).toDouble() /
                                            timestampSpan) * size.width).toFloat()
                                    }
                                    kotlin.math.abs(pointX - offset.x)
                                } ?: -1
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

                val priceRange = (maxPriceInView - minPriceInView).coerceAtLeast(0.01)
                // A single real observation is plotted in the center until later days
                // extend it into a time series.
                val pointSpacing = if (filteredPoints.size > 1) width / (filteredPoints.size - 1) else 0f

                // Horizontal distance represents elapsed time, not row number. This matters
                // when a four-year series contains uneven gaps between real observations.
                val firstTimestamp = filteredPoints.first().timestamp
                val timestampSpan = (filteredPoints.last().timestamp - firstTimestamp)
                    .coerceAtLeast(1L).toDouble()
                val coords = filteredPoints.mapIndexed { index, pt ->
                    val x = if (filteredPoints.size == 1) {
                        width / 2f
                    } else {
                        (((pt.timestamp - firstTimestamp).toDouble() / timestampSpan) * width).toFloat()
                    }
                    val normalizedPrice = (pt.price - minPriceInView) / priceRange
                    val y = paddingTop + usableHeight - (normalizedPrice.toFloat() * usableHeight)
                    Offset(x, y)
                }

                // Draw an average guide only when an actual 90-day average exists.
                if (valid90dAverage != null) {
                    val avgNormalized = ((valid90dAverage - minPriceInView) / priceRange).coerceIn(0.0, 1.0)
                    val avgY = paddingTop + usableHeight - (avgNormalized.toFloat() * usableHeight)
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.5f),
                        start = Offset(0f, avgY),
                        end = Offset(width, avgY),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                }

                // The monotone Bezier path follows dated observations without overshooting them.
                val strokePath = createMonotoneBezierPath(coords)
                val baselineY = height - paddingBottom
                val fillPath = Path().apply {
                    addPath(strokePath)
                    lineTo(coords.last().x, baselineY)
                    lineTo(coords.first().x, baselineY)
                    close()
                }

                // Linear orange fade: strongest just below the highest point, fully transparent
                // at the baseline; clipped to the area under the curve.
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            chartAccent.copy(alpha = 0.38f),
                            chartAccent.copy(alpha = 0.0f)
                        ),
                        startY = minOf(coords.minOf { it.y }, baselineY - 1f),
                        endY = baselineY
                    )
                )

                // A wider translucent stroke creates a subtle glow without a costly blur/shadow.
                drawPath(
                    path = strokePath,
                    color = chartAccent.copy(alpha = 0.14f),
                    style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                )
                drawPath(
                    path = strokePath,
                    color = chartAccent,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )

                // One observation cannot form a line. Draw a subtle date guide and a clear
                // focus halo instead, so a real historical point is visible without implying
                // a fabricated price trend between dates.
                if (filteredPoints.size == 1) {
                    val onlyPoint = coords.single()
                    drawLine(
                        color = chartAccent.copy(alpha = 0.35f),
                        start = Offset(
                            onlyPoint.x,
                            (onlyPoint.y + 12.dp.toPx()).coerceAtMost(baselineY)
                        ),
                        end = Offset(onlyPoint.x, baselineY),
                        strokeWidth = 1.25.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                            0f
                        )
                    )
                    drawCircle(
                        color = chartAccent.copy(alpha = 0.18f),
                        radius = 11.dp.toPx(),
                        center = onlyPoint
                    )
                }

                // Draw node points
                coords.forEachIndexed { index, coord ->
                    val pt = filteredPoints[index]
                    val isAth = historySummary.minimumObservedPoint?.let { pt.price <= it.price + 0.005 } == true
                    val isSelected = index == selectedPointIndex ||
                        (filteredPoints.size == 1 && selectedPointIndex < 0)

                    when {
                        isSelected && isAth -> {
                            // Keep the green observed-minimum marker visible while selected.
                            drawCircle(
                                color = chartAccent,
                                radius = 7.dp.toPx(),
                                center = coord
                            )
                            drawCircle(
                                color = Color(0xFF10B981),
                                radius = 5.dp.toPx(),
                                center = coord
                            )
                            drawCircle(
                                color = Color(0xFF064E3B),
                                radius = 2.dp.toPx(),
                                center = coord
                            )
                        }
                        isSelected -> {
                            drawCircle(
                                color = chartAccent,
                                radius = 7.dp.toPx(),
                                center = coord
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 3.dp.toPx(),
                                center = coord
                            )
                        }
                        isAth -> {
                            // ATH marker
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
                                color = chartAccent,
                                radius = 2.5.dp.toPx(),
                                center = coord
                            )
                        }
                    }
                }
            }
            if (filteredPoints.isEmpty()) {
                Text(
                    text = if (selectedHistory.isEmpty()) "Sin datos todavía para esta fuente" else "Sin datos en este rango",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }


    }
}
