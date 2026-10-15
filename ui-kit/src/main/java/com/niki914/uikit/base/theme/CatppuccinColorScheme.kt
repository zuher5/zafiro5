package com.niki914.uikit.base.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Catppuccin Mocha (Dark mode). */
val CatppuccinMochaColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFCBA6F7), // Mauve
    onPrimary = Color(0xFF11111B), // Crust
    primaryContainer = Color(0xFF45475A), // Surface 1
    onPrimaryContainer = Color(0xFFCBA6F7), // Mauve
    secondary = Color(0xFFB4BEFE), // Lavender
    onSecondary = Color(0xFF11111B), // Crust
    secondaryContainer = Color(0xFF313244), // Surface 0
    onSecondaryContainer = Color(0xFFB4BEFE), // Lavender
    tertiary = Color(0xFF74C7EC), // Sapphire
    onTertiary = Color(0xFF11111B), // Crust
    tertiaryContainer = Color(0xFF313244), // Surface 0
    onTertiaryContainer = Color(0xFF74C7EC), // Sapphire
    error = Color(0xFFF38BA8), // Red
    onError = Color(0xFF11111B), // Crust
    errorContainer = Color(0xFF45475A), // Surface 1
    onErrorContainer = Color(0xFFF38BA8), // Red
    background = Color(0xFF1E1E2E), // Base
    onBackground = Color(0xFFCDD6F4), // Text
    surface = Color(0xFF1E1E2E), // Base
    onSurface = Color(0xFFCDD6F4), // Text
    surfaceVariant = Color(0xFF313244), // Surface 0
    onSurfaceVariant = Color(0xFFA6ADC8), // Subtext 0
    outline = Color(0xFF6C7086), // Overlay 0
    outlineVariant = Color(0xFF45475A), // Surface 1
    scrim = Color(0xFF11111B), // Crust
    inverseSurface = Color(0xFFCDD6F4), // Text
    inverseOnSurface = Color(0xFF1E1E2E), // Base
    inversePrimary = Color(0xFF8839EF),
    surfaceDim = Color(0xFF181825), // Mantle
    surfaceBright = Color(0xFF313244), // Surface 0
    surfaceContainerLowest = Color(0xFF11111B), // Crust
    surfaceContainerLow = Color(0xFF181825), // Mantle
    surfaceContainer = Color(0xFF313244), // Surface 0
    surfaceContainerHigh = Color(0xFF45475A), // Surface 1
    surfaceContainerHighest = Color(0xFF585B70), // Surface 2
)

/** Catppuccin Latte (Light mode). */
val CatppuccinLatteColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF8839EF), // Mauve
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCD0DA), // Surface 0
    onPrimaryContainer = Color(0xFF8839EF), // Mauve
    secondary = Color(0xFF7287FD), // Lavender
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFBCC0CC), // Surface 1
    onSecondaryContainer = Color(0xFF7287FD), // Lavender
    tertiary = Color(0xFF209FB5), // Sapphire
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCD0DA), // Surface 0
    onTertiaryContainer = Color(0xFF209FB5), // Sapphire
    error = Color(0xFFD20F39), // Red
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFCCD0DA), // Surface 0
    onErrorContainer = Color(0xFFD20F39), // Red
    background = Color(0xFFEFF1F5), // Base
    onBackground = Color(0xFF4C4F69), // Text
    surface = Color(0xFFEFF1F5), // Base
    onSurface = Color(0xFF4C4F69), // Text
    surfaceVariant = Color(0xFFCCD0DA), // Surface 0
    onSurfaceVariant = Color(0xFF6C6F85), // Subtext 0
    outline = Color(0xFF9CA0B0), // Overlay 0
    outlineVariant = Color(0xFFBCC0CC), // Surface 1
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF4C4F69), // Text
    inverseOnSurface = Color(0xFFEFF1F5), // Base
    inversePrimary = Color(0xFFCBA6F7),
    surfaceDim = Color(0xFFE6E9EF), // Mantle
    surfaceBright = Color(0xFFCCD0DA), // Surface 0
    surfaceContainerLowest = Color(0xFFDCE0E8), // Crust
    surfaceContainerLow = Color(0xFFE6E9EF), // Mantle
    surfaceContainer = Color(0xFFCCD0DA), // Surface 0
    surfaceContainerHigh = Color(0xFFBCC0CC), // Surface 1
    surfaceContainerHighest = Color(0xFFACB0BE), // Surface 2
)
