package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Identidad visual Mínimo: carbón y ámbar. Los verdes se reservan para estados positivos
// (precio verificado, ahorro o mínimo observado), nunca como segundo color de marca.
val BrandAmber = Color(0xFFFFA64D)
val DarkBrandAmber = Color(0xFFAD4D08)
val BrandAmberContainer = Color(0xFF4A2912)
val SoftAmber = Color(0xFFFFDDBA)

// Semantic success colours retained for evidence, verified prices and positive savings.
val EmeraldGreen = Color(0xFF10B981)
val DarkEmerald = Color(0xFF047857)
val BrightMint = Color(0xFF34D399)
val TrueDealGreen = Color(0xFF059669)
val WarningAmber = Color(0xFFD97706)
val DangerRed = Color(0xFFDC2626)
val ElectricBlue = Color(0xFF2563EB)
val CyanAccent = Color(0xFF06B6D4)

// Existing neutral aliases retained for screens and components that already use them.
val DeepNavy = Color(0xFF0B132B)
val SlateDark = Color(0xFF1E293B)
val SlateMedium = Color(0xFF334155)
val SlateLight = Color(0xFF64748B)
val SlateUltraLight = Color(0xFFF1F5F9)

// Light theme: warm off-white backgrounds and paper-like cards with restrained contrast.
val LightBg = Color(0xFFF7F5F2)
val LightSurface = Color(0xFFFFFEFC)
val LightSurfaceVariant = Color(0xFFEEEAE4)
val LightOnSurface = Color(0xFF1F1B16)
val LightOutline = Color(0xFFD8D0C6)

// Dark theme: true black background for OLED, lifted graphite surfaces for clear card layers.
val DarkBg = Color(0xFF070707)
val DarkSurface = Color(0xFF101012)
val DarkSurfaceVariant = Color(0xFF1B1B1F)
val DarkOnSurface = Color(0xFFF5F3F0)
val DarkOutline = Color(0xFF3B332B)

// OLED-safe surfaces; no blur or translucent full-screen rendering required.
val OledBlack = Color(0xFF000000)
val OledSurface = DarkSurface
val OledSurfaceVariant = DarkSurfaceVariant
val OledHairline = Color(0x26FFFFFF)
