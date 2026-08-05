package com.autoregistershift.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF087F73),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5F5EF),
    onPrimaryContainer = Color(0xFF063E39),
    secondary = Color(0xFF4E5FC7),
    background = Color(0xFFF4F7F7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE7EFED),
    outlineVariant = Color(0xFFD5E1DE),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF67DCCB),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF07534B),
    onPrimaryContainer = Color(0xFFB6EFE6),
    secondary = Color(0xFFBAC3FF),
    background = Color(0xFF0D1519),
    surface = Color(0xFF151F24),
    surfaceVariant = Color(0xFF263337),
    outlineVariant = Color(0xFF35464A)
)

@Composable
fun AutoRegisterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
