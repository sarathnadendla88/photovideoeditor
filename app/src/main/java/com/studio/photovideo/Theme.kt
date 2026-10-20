package com.studio.photovideo

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens: one recognisable identity (violet → coral), dark-first like pro creative tools. */
object Studio {
    val Violet = Color(0xFF7C5CFF)
    val Indigo = Color(0xFF4B3BDB)
    val Coral = Color(0xFFFF6B8A)
    val Amber = Color(0xFFFFB84D)
    val Teal = Color(0xFF2DD4BF)
    val Sky = Color(0xFF38BDF8)
    val Ink = Color(0xFF0E0B1F)
    val InkRaised = Color(0xFF1A1630)
    val InkHigh = Color(0xFF262042)

    val HeroGradient = Brush.linearGradient(listOf(Indigo, Violet, Coral))
    fun tile(a: Color, b: Color) = Brush.linearGradient(listOf(a, b))

    val CardShape = RoundedCornerShape(24.dp)
    val ChipShape = RoundedCornerShape(14.dp)
    val Gutter = 16.dp
}

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB7A6FF), onPrimary = Color(0xFF1B0F5C),
    primaryContainer = Color(0xFF3A2D8F), onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Studio.Teal, tertiary = Studio.Coral,
    background = Studio.Ink, surface = Studio.Ink,
    surfaceContainer = Studio.InkRaised, surfaceContainerHigh = Studio.InkHigh,
    surfaceVariant = Studio.InkHigh, onSurface = Color(0xFFEDE9FF), onSurfaceVariant = Color(0xFFB4ADD6),
    outline = Color(0xFF6F678F),
)
private val LightScheme = lightColorScheme(
    primary = Studio.Indigo, secondary = Color(0xFF0F9E8E), tertiary = Color(0xFFD6366A),
    background = Color(0xFFF7F5FF), surface = Color(0xFFF7F5FF),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFECE8FF),
)

private val Type = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    // Fixed brand palette (no dynamic colour) so the identity stays consistent across devices.
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = Type, content = content,
    )
}
