package com.disp06.rc.mobile.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
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

/** Dark-first Material You theme: dynamic colour on Android 12+, a calm blue palette otherwise. */
@Composable
fun AppTheme(followSystem: Boolean, content: @Composable () -> Unit) {
    val dark = if (followSystem) isSystemInDarkTheme() else true
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkFallback
        else -> LightFallback
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
