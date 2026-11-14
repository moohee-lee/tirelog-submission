package com.tirelog.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp
import com.tirelog.app.R
import com.tirelog.core.engine.WheelState

/** Pleos의 중립색 표면을 사용하고 색은 측정값·주의 상태에 남긴다. */
data class TirelogPalette(
    val Background: Color, val Surface: Color, val SurfaceHigh: Color,
    val OnSurface: Color, val Muted: Color, val Outline: Color,
    val Amber: Color, val Yellow: Color, val Red: Color, val Orange: Color, val Green: Color,
    val Series: List<Color>, val dark: Boolean,
) {
    val Primary get() = OnSurface
    val Selection get() = if (dark) Color(0xFF50535B) else Color(0xFFE0E3E8)
    val TireSelection get() = if (dark) Color(0xFF293F49) else Color(0xFFE9F1F5)
    val OnTireSelection get() = if (dark) Color(0xFFA0CCDE) else Color(0xFF4C788B)
    fun forState(state: WheelState): Color = when (state) {
        WheelState.WAITING -> Muted
        WheelState.NORMAL -> OnSurface
        WheelState.LOW -> Yellow
        WheelState.REFILL -> Amber
        WheelState.LEAK -> Red
        WheelState.RECHECK -> Orange
    }
}

private val lightPalette = TirelogPalette(
    Background = Color(0xFFF6F7F9), Surface = Color.White, SurfaceHigh = Color(0xFFEBEDF0),
    OnSurface = Color(0xFF121315), Muted = Color(0xFF62656D), Outline = Color(0xFFCDD0D6),
    Amber = Color(0xFF946100), Yellow = Color(0xFF886000), Red = Color(0xFFB72D3A),
    Orange = Color(0xFFAF4C19), Green = Color(0xFF237548),
    Series = listOf(Color(0xFF006D9C), Color(0xFF986000), Color(0xFF237548), Color(0xFFA93C70)), dark = false,
)
private val darkPalette = TirelogPalette(
    Background = Color(0xFF1E1F21), Surface = Color(0xFF303235), SurfaceHigh = Color(0xFF404249),
    OnSurface = Color(0xFFF6F7F9), Muted = Color(0xFFC1C3C9), Outline = Color(0xFF666973),
    Amber = Color(0xFFFFC663), Yellow = Color(0xFFFFD675), Red = Color(0xFFFF9099),
    Orange = Color(0xFFFFAC80), Green = Color(0xFF92D8AE),
    Series = listOf(Color(0xFF7CCDEB), Color(0xFFFFC663), Color(0xFF92D8AE), Color(0xFFF2A3C7)), dark = true,
)
private val LocalTirelogPalette = staticCompositionLocalOf { lightPalette }
val TirelogColors: TirelogPalette
    @Composable @ReadOnlyComposable get() = LocalTirelogPalette.current

@OptIn(ExperimentalTextApi::class)
private val astaSans = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(R.font.asta_sans, weight = weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    },
)
private fun text(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = astaSans, fontSize = size.sp, lineHeight = height.sp, fontWeight = weight,
    lineBreak = LineBreak.Heading,
)
private val typography = Typography(
    displayLarge = text(80, 92, FontWeight.Bold), displayMedium = text(64, 74, FontWeight.Bold),
    headlineMedium = text(40, 52, FontWeight.SemiBold),
    titleLarge = text(36, 46, FontWeight.SemiBold), titleMedium = text(30, 40, FontWeight.SemiBold),
    titleSmall = text(26, 36, FontWeight.SemiBold), bodyLarge = text(28, 40), bodyMedium = text(24, 34),
    bodySmall = text(20, 30), labelLarge = text(26, 36, FontWeight.SemiBold),
    labelMedium = text(22, 32, FontWeight.Medium), labelSmall = text(20, 30, FontWeight.Medium),
)

@Composable
fun TirelogTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (darkTheme) darkPalette else lightPalette
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.OnSurface, onPrimary = p.Background, primaryContainer = p.Selection, onPrimaryContainer = p.OnSurface,
        secondary = p.OnSurface, onSecondary = p.Background, secondaryContainer = p.Selection, onSecondaryContainer = p.OnSurface,
        tertiary = p.Green, background = p.Background, onBackground = p.OnSurface,
        surface = p.Surface, onSurface = p.OnSurface, surfaceVariant = p.SurfaceHigh, onSurfaceVariant = p.Muted,
        surfaceContainer = p.Surface, surfaceContainerHigh = p.SurfaceHigh, surfaceContainerHighest = p.SurfaceHigh,
        surfaceContainerLow = p.Background, surfaceContainerLowest = p.Background, surfaceTint = Color.Transparent,
        outline = p.Outline, outlineVariant = p.Outline.copy(alpha = .6f), error = p.Red,
        inverseSurface = if (darkTheme) p.OnSurface else Color(0xFF44464D), inverseOnSurface = p.Background,
    )
    CompositionLocalProvider(LocalTirelogPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            CompositionLocalProvider(LocalContentColor provides p.OnSurface, content = content)
        }
    }
}
