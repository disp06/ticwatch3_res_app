package com.disp06.rc.mobile.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils

private val DarkFallback = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1D36),
    primaryContainer = Color(0xFF1F3550),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFB7C6DC),
    tertiary = Color(0xFFF2B8B5),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF22262D),
    surfaceContainer = Color(0xFF1A1D23),
    surfaceContainerHigh = Color(0xFF22262D),
    onSurface = Color(0xFFE2E2E9),
    onSurfaceVariant = Color(0xFFA9AEB8),
    outline = Color(0xFF5B616B),
    outlineVariant = Color(0xFF33383F),
)

private val LightFallback = lightColorScheme(
    primary = Color(0xFF2E5EAA),
    secondary = Color(0xFF545F71),
    tertiary = Color(0xFF8C4A47),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.Light),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.Medium),
    )
}

/** Accent choices. DYNAMIC follows the wallpaper (Android 12+), the rest are fixed seed colours. */
enum class Accent(val label: String, val seed: Long?) {
    DYNAMIC("Wallpaper", null),
    BLUE("Blue", 0xFF4C8DF6),
    RED("Red", 0xFFE5484D),
    ORANGE("Orange", 0xFFF5822A),
    AMBER("Amber", 0xFFE8B23A),
    GREEN("Green", 0xFF3DBE7A),
    TEAL("Teal", 0xFF1FB8B0),
    PURPLE("Purple", 0xFF8E6CEF),
    PINK("Pink", 0xFFE75A9B);

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: DYNAMIC
    }
}

private fun tone(h: Float, s: Float, l: Float) =
    Color(ColorUtils.HSLToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))

/** Builds a complete Material 3 scheme (accents + tinted neutrals) from one seed colour. */
fun schemeFromSeed(seed: Long, dark: Boolean): ColorScheme {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seed.toInt(), hsl)
    val h = hsl[0]
    val s = hsl[1].coerceAtMost(0.85f)
    return if (dark) darkColorScheme(
        primary = tone(h, s, 0.72f), onPrimary = tone(h, s, 0.14f),
        primaryContainer = tone(h, s * 0.55f, 0.27f), onPrimaryContainer = tone(h, s, 0.90f),
        secondary = tone(h, 0.25f, 0.76f), onSecondary = tone(h, 0.3f, 0.15f),
        secondaryContainer = tone(h, 0.22f, 0.25f), onSecondaryContainer = tone(h, 0.3f, 0.90f),
        tertiary = tone(h + 60f, 0.45f, 0.76f), onTertiary = tone(h + 60f, 0.4f, 0.15f),
        background = tone(h, 0.12f, 0.07f), onBackground = tone(h, 0.08f, 0.90f),
        surface = tone(h, 0.12f, 0.07f), onSurface = tone(h, 0.08f, 0.90f),
        surfaceVariant = tone(h, 0.12f, 0.18f), onSurfaceVariant = tone(h, 0.10f, 0.72f),
        surfaceContainerLowest = tone(h, 0.12f, 0.05f), surfaceContainerLow = tone(h, 0.12f, 0.09f),
        surfaceContainer = tone(h, 0.12f, 0.11f), surfaceContainerHigh = tone(h, 0.12f, 0.15f),
        surfaceContainerHighest = tone(h, 0.12f, 0.19f),
        outline = tone(h, 0.08f, 0.45f), outlineVariant = tone(h, 0.10f, 0.23f),
        inverseSurface = tone(h, 0.08f, 0.90f), inverseOnSurface = tone(h, 0.12f, 0.15f), inversePrimary = tone(h, s, 0.40f),
    ) else lightColorScheme(
        primary = tone(h, s, 0.40f), onPrimary = Color.White,
        primaryContainer = tone(h, s, 0.90f), onPrimaryContainer = tone(h, s, 0.12f),
        secondary = tone(h, 0.20f, 0.38f), onSecondary = Color.White,
        secondaryContainer = tone(h, 0.30f, 0.90f), onSecondaryContainer = tone(h, 0.3f, 0.12f),
        tertiary = tone(h + 60f, 0.40f, 0.38f), onTertiary = Color.White,
        background = tone(h, 0.30f, 0.98f), onBackground = tone(h, 0.10f, 0.10f),
        surface = tone(h, 0.30f, 0.98f), onSurface = tone(h, 0.10f, 0.10f),
        surfaceVariant = tone(h, 0.20f, 0.90f), onSurfaceVariant = tone(h, 0.10f, 0.32f),
        surfaceContainerLowest = Color.White, surfaceContainerLow = tone(h, 0.25f, 0.96f),
        surfaceContainer = tone(h, 0.25f, 0.94f), surfaceContainerHigh = tone(h, 0.22f, 0.92f),
        surfaceContainerHighest = tone(h, 0.20f, 0.90f),
        outline = tone(h, 0.08f, 0.50f), outlineVariant = tone(h, 0.12f, 0.80f),
    )
}

/** Dark-first Material 3 theme: wallpaper colour on Android 12+, or a user-picked accent. */
@Composable
fun AppTheme(followSystem: Boolean, accent: Accent = Accent.DYNAMIC, content: @Composable () -> Unit) {
    val dark = if (followSystem) isSystemInDarkTheme() else true
    val ctx = LocalContext.current
    val scheme = when {
        accent.seed != null -> schemeFromSeed(accent.seed, dark)
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkFallback
        else -> LightFallback
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
