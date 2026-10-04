package com.keyzai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF052E2B),
    primaryContainer = Color(0xFF0F3D39),
    onPrimaryContainer = Color(0xFFA7F3E8),
    secondary = Color(0xFF7DD3FC),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF11151D),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1B2230),
    onSurfaceVariant = Color(0xFF9AA7BD),
    surfaceContainerHighest = Color(0xFF232C3D),
    outline = Color(0xFF3A4458),
    error = Color(0xFFF87171),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0D7C6F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB8F1E6),
    secondary = Color(0xFF0369A1),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE8EDF5),
    onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFFCBD5E1),
)

@Composable
fun KeyzAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
