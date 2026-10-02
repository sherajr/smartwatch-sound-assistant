package com.peaceantz.stagescope.phone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Phosphor = Color(0xFF59E68A)

private val Dark = darkColorScheme(
    primary = Phosphor, onPrimary = Color(0xFF00210E),
    primaryContainer = Color(0xFF0F3A22), onPrimaryContainer = Color(0xFFB6F5CB),
    secondary = Color(0xFF9CCBAE), tertiary = Color(0xFFFFC857),
    background = Color(0xFF0B0F0C), onBackground = Color(0xFFE2E8E3),
    surface = Color(0xFF0B0F0C), onSurface = Color(0xFFE2E8E3),
    surfaceVariant = Color(0xFF1B241E), onSurfaceVariant = Color(0xFFB8C4BB),
    surfaceContainer = Color(0xFF121914), surfaceContainerHigh = Color(0xFF18211B),
    error = Color(0xFFFF8A80), outline = Color(0xFF6E7B72),
)

private val Light = lightColorScheme(
    primary = Color(0xFF006D38), onPrimary = Color.White,
    primaryContainer = Color(0xFFB6F5CB), onPrimaryContainer = Color(0xFF00210E),
    secondary = Color(0xFF4D6355), tertiary = Color(0xFF7A5900),
    background = Color(0xFFF7FBF7), onBackground = Color(0xFF181D19),
    surface = Color(0xFFF7FBF7), onSurface = Color(0xFF181D19),
    surfaceVariant = Color(0xFFDDE5DD), onSurfaceVariant = Color(0xFF414942),
    surfaceContainer = Color(0xFFEBF1EB), surfaceContainerHigh = Color(0xFFE5ECE5),
    error = Color(0xFFBA1A1A), outline = Color(0xFF717972),
)

/** Dark by default for use in a dark theatre; follows the system setting otherwise. */
@Composable
fun StageScopePhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
