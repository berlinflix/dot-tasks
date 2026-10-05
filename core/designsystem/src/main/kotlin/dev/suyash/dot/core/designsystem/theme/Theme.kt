package dev.suyash.dot.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

private val DarkScheme = darkColorScheme(
    primary = DotPalette.White,
    onPrimary = DotPalette.Black,
    primaryContainer = DotPalette.Grey850,
    onPrimaryContainer = DotPalette.White,
    secondary = DotPalette.Grey300,
    onSecondary = DotPalette.Black,
    secondaryContainer = DotPalette.Grey800,
    onSecondaryContainer = DotPalette.White,
    tertiary = DotPalette.RedDark,
    onTertiary = DotPalette.White,
    error = DotPalette.RedDark,
    onError = DotPalette.White,
    background = DotPalette.Black,
    onBackground = DotPalette.White,
    surface = DotPalette.Black,
    onSurface = DotPalette.White,
    surfaceVariant = DotPalette.Grey900,
    onSurfaceVariant = DotPalette.Grey400,
    surfaceContainerLowest = DotPalette.Black,
    surfaceContainerLow = DotPalette.Grey950,
    surfaceContainer = DotPalette.Grey900,
    surfaceContainerHigh = DotPalette.Grey850,
    surfaceContainerHighest = DotPalette.Grey800,
    outline = DotPalette.Grey700,
    outlineVariant = DotPalette.Grey800,
    inverseSurface = DotPalette.White,
    inverseOnSurface = DotPalette.Black,
    inversePrimary = DotPalette.Red, // snackbar actions on the white snackbar
    scrim = DotPalette.Black,
)

private val LightScheme = lightColorScheme(
    primary = DotPalette.Black,
    onPrimary = DotPalette.White,
    primaryContainer = DotPalette.Grey100,
    onPrimaryContainer = DotPalette.Black,
    secondary = DotPalette.Grey700,
    onSecondary = DotPalette.White,
    secondaryContainer = DotPalette.Grey100,
    onSecondaryContainer = DotPalette.Black,
    tertiary = DotPalette.Red,
    onTertiary = DotPalette.White,
    error = DotPalette.Red,
    onError = DotPalette.White,
    background = DotPalette.Grey050,
    onBackground = DotPalette.Black,
    surface = DotPalette.Grey050,
    onSurface = DotPalette.Black,
    surfaceVariant = DotPalette.White,
    onSurfaceVariant = DotPalette.Grey700,
    surfaceContainerLowest = DotPalette.White,
    surfaceContainerLow = DotPalette.White,
    surfaceContainer = DotPalette.White,
    surfaceContainerHigh = DotPalette.Grey100,
    surfaceContainerHighest = DotPalette.Grey200,
    outline = DotPalette.Grey300,
    outlineVariant = DotPalette.Grey200,
    inverseSurface = DotPalette.Black,
    inverseOnSurface = DotPalette.White,
    inversePrimary = DotPalette.RedDark, // snackbar actions on the black snackbar
    scrim = DotPalette.Black,
)

private val DotShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/**
 * The Dot theme. Deliberately ignores dynamic color: the monochrome + red look is the brand.
 */
@Composable
fun DotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDotColors provides if (darkTheme) DarkDotColors else LightDotColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = DotTypography,
            shapes = DotShapes,
            content = content,
        )
    }
}

object DotTheme {
    val colors: DotColors
        @Composable @ReadOnlyComposable get() = LocalDotColors.current
}
