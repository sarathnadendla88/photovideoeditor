package com.sarath.photovideo

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

/** App colour themes. Violet is free; the rest are ReelMint Pro. A theme changes the accent colours, the moving backdrop and the hero cards. */
enum class AppTheme(val label: String, val pro: Boolean, val darkPrimary: Color, val darkContainer: Color, val lightPrimary: Color, val hero: List<Color>, val aurora: List<Color>) {
    VIOLET("Violet", false, Color(0xFFB7A6FF), Color(0xFF3A2D8F), Studio.Indigo, listOf(Studio.Coral, Studio.Violet, Studio.Indigo), listOf(Studio.Violet, Studio.Coral, Studio.Teal)),
    OCEAN("Ocean", true, Color(0xFF7CC4FF), Color(0xFF0B4F7A), Color(0xFF0369A1), listOf(Color(0xFF0EA5E9), Color(0xFF2563EB), Color(0xFF0F766E)), listOf(Color(0xFF0EA5E9), Color(0xFF2DD4BF), Color(0xFF4B3BDB))),
    SUNSET("Sunset", true, Color(0xFFFFB27A), Color(0xFF7A3A12), Color(0xFFC2410C), listOf(Color(0xFFFF7A18), Color(0xFFFF3D6E), Color(0xFF7C3AED)), listOf(Color(0xFFFF7A18), Color(0xFFFF3D6E), Color(0xFFFFB84D))),
    FOREST("Forest", true, Color(0xFF86E0A0), Color(0xFF14532D), Color(0xFF15803D), listOf(Color(0xFF16A34A), Color(0xFF0D9488), Color(0xFF65A30D)), listOf(Color(0xFF16A34A), Color(0xFF2DD4BF), Color(0xFFA3E635))),
    ROSE("Rose", true, Color(0xFFFF9EC4), Color(0xFF7A1F4D), Color(0xFFBE185D), listOf(Color(0xFFEC4899), Color(0xFFF43F5E), Color(0xFFA855F7)), listOf(Color(0xFFEC4899), Color(0xFFFF6B8A), Color(0xFF7C5CFF))),
    GRAPHITE("Graphite", true, Color(0xFFD0D4DC), Color(0xFF3A3F4B), Color(0xFF334155), listOf(Color(0xFF475569), Color(0xFF1E293B), Color(0xFF64748B)), listOf(Color(0xFF64748B), Color(0xFF94A3B8), Color(0xFF475569))),
}

/** The chosen theme. A free user always gets Violet, whatever was saved. */
object ThemePrefs {
    var current by mutableStateOf(AppTheme.VIOLET)
    val active: AppTheme get() = if (Entitlements.theme(current, Pro.isPro)) current else AppTheme.VIOLET
    private const val KEY = "app_theme"
    fun load(prefs: android.content.SharedPreferences) { current = runCatching { AppTheme.valueOf(prefs.getString(KEY, "") ?: "") }.getOrDefault(AppTheme.VIOLET) }
    fun set(prefs: android.content.SharedPreferences, t: AppTheme) { current = t; prefs.edit().putString(KEY, t.name).apply() }
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
        colorScheme = ThemePrefs.active.let { t -> if (isSystemInDarkTheme()) DarkScheme.copy(primary = t.darkPrimary, primaryContainer = t.darkContainer) else LightScheme.copy(primary = t.lightPrimary) },
        typography = Type,
    ) {
        // Without a Surface at the root, text falls back to black (invisible on the dark background).
        Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
            // Any screen can open the Pro sheet with LocalProGate.current("reason"); it is shown above whatever is on screen.
            var proReason by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) { runCatching { Payments.coordinator.retryUnverified() } }
            CompositionLocalProvider(LocalProGate provides { proReason = it }) {
                content()
                proReason?.let { ProSheet(it) { proReason = null } }
            }
        }
    }
}
