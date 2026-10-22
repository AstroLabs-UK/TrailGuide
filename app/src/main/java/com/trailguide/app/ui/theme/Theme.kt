package com.trailguide.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Parchment = Color(0xFFF5E8C7)
private val ParchmentDark = Color(0xFFE8D5A3)
private val Ink = Color(0xFF2C1E12)
private val InkLight = Color(0xFF5C4033)
private val TrailBrown = Color(0xFF8B5E3C)

private val LightColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Parchment,
    secondary = TrailBrown,
    onSecondary = Parchment,
    background = Parchment,
    onBackground = Ink,
    surface = ParchmentDark,
    onSurface = Ink,
    surfaceVariant = Color(0xFFD9C8A0),
    onSurfaceVariant = InkLight
)

private val DarkColorScheme = darkColorScheme(
    primary = Parchment,
    onPrimary = Ink,
    secondary = TrailBrown,
    onSecondary = Parchment,
    background = Ink,
    onBackground = Parchment,
    surface = Color(0xFF3D2B1F),
    onSurface = Parchment,
    surfaceVariant = Color(0xFF4A3728),
    onSurfaceVariant = ParchmentDark
)

@Composable
fun TrailGuideTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = colorScheme, content = content)
}
