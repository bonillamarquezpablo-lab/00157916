package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = BrandAmber,
    onPrimary = Color(0xFF281605),
    primaryContainer = BrandAmberContainer,
    onPrimaryContainer = SoftAmber,
    secondary = Color(0xFFD4BCA5),
    onSecondary = Color(0xFF2A2119),
    tertiary = Color(0xFFE8C07C),
    onTertiary = Color(0xFF251A06),
    background = OledBlack,
    onBackground = DarkOnSurface,
    surface = OledSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = OledSurfaceVariant,
    onSurfaceVariant = Color(0xFFB0A79D),
    outline = OledHairline,
    error = DangerRed,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = DarkBrandAmber,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE7D1),
    onPrimaryContainer = Color(0xFF71340B),
    secondary = Color(0xFF765943),
    onSecondary = Color.White,
    tertiary = Color(0xFF805F1F),
    onTertiary = Color.White,
    background = LightBg,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF62594F),
    outline = LightOutline,
    error = DangerRed,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Keep consistent branding colors for price tracking authenticity.
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
