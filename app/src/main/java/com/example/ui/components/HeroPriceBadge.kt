package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PriceBadgeState
import com.example.data.model.PriceBadgeType

private data class BadgePalette(
    val background: Color,
    val foreground: Color,
    val outline: Color
)

private fun paletteFor(type: PriceBadgeType): BadgePalette = when (type) {
    PriceBadgeType.UNVERIFIED -> BadgePalette(Color(0xFF252B38), Color(0xFFB8C2D4), Color(0xFF475569))
    PriceBadgeType.FIRST_OBSERVATION -> BadgePalette(Color(0xFF172B43), Color(0xFF9DD2FF), Color(0xFF3978A8))
    PriceBadgeType.HISTORICAL_MINIMUM -> BadgePalette(Color(0xFF073D30), Color(0xFF59FFC0), Color(0xFF14F1A0))
    PriceBadgeType.GOOD_PRICE -> BadgePalette(Color(0xFF123D30), Color(0xFF6EE7B7), Color(0xFF34D399))
    PriceBadgeType.HIGH_PRICE -> BadgePalette(Color(0xFF3A242C), Color(0xFFFFA5B0), Color(0xFFB85C6A))
    PriceBadgeType.AT_AVERAGE -> BadgePalette(Color(0xFF292D38), Color(0xFFD0D5E1), Color(0xFF667085))
    PriceBadgeType.HISTORY_IN_PROGRESS -> BadgePalette(Color(0xFF242C39), Color(0xFFBCC7D8), Color(0xFF58677C))
}

/** The badge communicates evidence level, not just a visual deal score. */
@Composable
fun HeroPriceBadge(
    state: PriceBadgeState,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val palette = paletteFor(state.type)
    Surface(
        modifier = modifier.testTag("hero_price_badge_\u0024{state.type.name}"),
        shape = RoundedCornerShape(50),
        color = palette.background,
        contentColor = palette.foreground,
        border = BorderStroke(
            width = if (state.type == PriceBadgeType.HISTORICAL_MINIMUM) 1.5.dp else 1.dp,
            color = palette.outline
        ),
        shadowElevation = if (state.type == PriceBadgeType.HISTORICAL_MINIMUM) 5.dp else 1.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 5.dp else 8.dp)) {
            Text(
                text = state.label,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (compact) 10.sp else 12.sp,
                lineHeight = if (compact) 12.sp else 15.sp
            )
            if (!compact && state.type != PriceBadgeType.UNVERIFIED) {
                Text(
                    text = state.description,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = palette.foreground.copy(alpha = 0.88f)
                )
            }
        }
    }
}
