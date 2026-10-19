package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * One compact shape scale for the whole app. Cards have enough rounding to feel modern without
 * looking inflated; nested surfaces are smaller so the visual hierarchy stays clear.
 */
val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

val ProductCardShape = RoundedCornerShape(22.dp)
val ProductImageShape = RoundedCornerShape(16.dp)
val FloatingSurfaceShape = RoundedCornerShape(24.dp)
