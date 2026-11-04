package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
  primary = NovaPrimary,
  onPrimary = Color.White,
  primaryContainer = NovaPrimaryContainer,
  onPrimaryContainer = NovaTextNavy,
  secondary = NovaAuroraViolet,
  onSecondary = Color.White,
  tertiary = NovaAuroraBlue,
  onTertiary = NovaTextNavy,
  background = NovaLightBg,
  onBackground = NovaTextNavy,
  surface = NovaSurfaceWhite,
  onSurface = NovaTextNavy,
  surfaceVariant = NovaLightBg,
  onSurfaceVariant = NovaTextMuted,
  outline = NovaBorderLight
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = false, // Forced light theme per specification
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = LightColorScheme,
    typography = Typography,
    content = content
  )
}
