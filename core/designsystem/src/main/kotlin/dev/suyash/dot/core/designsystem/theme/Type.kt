package dev.suyash.dot.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.suyash.dot.core.designsystem.R

private fun grotesk(weight: FontWeight) = Font(
    resId = R.font.space_grotesk,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Space Grotesk (OFL) — UI text. Variable font, one file for all weights. */
val SpaceGrotesk = FontFamily(
    grotesk(FontWeight.Light),
    grotesk(FontWeight.Normal),
    grotesk(FontWeight.Medium),
    grotesk(FontWeight.SemiBold),
    grotesk(FontWeight.Bold),
)

/** Space Mono (OFL) — times, counters and caps labels. */
val SpaceMono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

internal val DotTypography = Typography(
    displayLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Light, fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-0.02).em),
    displayMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Light, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.02).em),
    displaySmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.01).em),
    headlineLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = SpaceMono, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.04.em),
    labelMedium = TextStyle(fontFamily = SpaceMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.08.em),
    labelSmall = TextStyle(fontFamily = SpaceMono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.1.em),
)
