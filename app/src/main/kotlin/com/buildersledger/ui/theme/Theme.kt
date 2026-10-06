package com.buildersledger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Warm honey and slate: easy on the eyes for the long evening sessions this app is used in.
private val LightColors = lightColorScheme(
    primary = Color(0xFF8C5A00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB0),
    onPrimaryContainer = Color(0xFF2C1700),
    secondary = Color(0xFF6F5B40),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBDEBC),
    onSecondaryContainer = Color(0xFF271905),
    tertiary = Color(0xFF3F6B2E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC0F0A8),
    onTertiaryContainer = Color(0xFF0A2000),
    background = Color(0xFFFFF8F2),
    onBackground = Color(0xFF211A13),
    surface = Color(0xFFFFF8F2),
    onSurface = Color(0xFF211A13),
    surfaceVariant = Color(0xFFF0E0D0),
    onSurfaceVariant = Color(0xFF50453A),
    outline = Color(0xFF82746A),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB950),
    onPrimary = Color(0xFF482900),
    primaryContainer = Color(0xFF6A3C00),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFDDC2A1),
    onSecondary = Color(0xFF3E2D16),
    secondaryContainer = Color(0xFF574329),
    onSecondaryContainer = Color(0xFFFBDEBC),
    tertiary = Color(0xFFA5D48E),
    onTertiary = Color(0xFF123800),
    tertiaryContainer = Color(0xFF285018),
    onTertiaryContainer = Color(0xFFC0F0A8),
    background = Color(0xFF19130D),
    onBackground = Color(0xFFEDE0D4),
    surface = Color(0xFF19130D),
    onSurface = Color(0xFFEDE0D4),
    surfaceVariant = Color(0xFF50453A),
    onSurfaceVariant = Color(0xFFD4C4B5),
    outline = Color(0xFF9C8E80),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun LedgerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
