package dev.suyash.dot.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Nothing-inspired palette: monochrome surfaces, one red accent reserved for live states. */
internal object DotPalette {
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)
    val Red = Color(0xFFD71921)
    val RedDark = Color(0xFFFF3B41)

    val Grey950 = Color(0xFF0B0B0C)
    val Grey900 = Color(0xFF151517)
    val Grey850 = Color(0xFF1C1C1F)
    val Grey800 = Color(0xFF26262A)
    val Grey700 = Color(0xFF3A3A3F)
    val Grey500 = Color(0xFF8A8A8F)
    val Grey400 = Color(0xFFA6A6AB)
    val Grey300 = Color(0xFFC9C9CD)
    val Grey200 = Color(0xFFDDDDE0)
    val Grey100 = Color(0xFFEDEDEF)
    val Grey050 = Color(0xFFF5F5F6)
}

/** Colors outside Material's scheme that the Dot look needs. */
@Immutable
data class DotColors(
    /** Red: recording, ringing, overdue. Never used decoratively. */
    val accent: Color,
    val onAccent: Color,
    /** Secondary text (timestamps, captions). */
    val muted: Color,
    /** Hairline outlines and dividers. */
    val hairline: Color,
    /** Lit and unlit dots of dot-matrix graphics. */
    val dotLit: Color,
    val dotUnlit: Color,
    /** Raised card surface. */
    val card: Color,
)

internal val DarkDotColors = DotColors(
    accent = DotPalette.RedDark,
    onAccent = DotPalette.White,
    muted = DotPalette.Grey500,
    hairline = DotPalette.Grey800,
    dotLit = DotPalette.White,
    dotUnlit = DotPalette.Grey800,
    card = DotPalette.Grey900,
)

internal val LightDotColors = DotColors(
    accent = DotPalette.Red,
    onAccent = DotPalette.White,
    muted = DotPalette.Grey500,
    hairline = DotPalette.Grey200,
    dotLit = DotPalette.Black,
    dotUnlit = DotPalette.Grey200,
    card = DotPalette.White,
)

val LocalDotColors = staticCompositionLocalOf { DarkDotColors }
