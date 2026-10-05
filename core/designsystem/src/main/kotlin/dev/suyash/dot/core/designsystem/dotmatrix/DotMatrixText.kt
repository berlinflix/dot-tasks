package dev.suyash.dot.core.designsystem.dotmatrix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.suyash.dot.core.designsystem.theme.DotTheme

/**
 * Text drawn as a dot matrix — the signature Nothing-style display face.
 *
 * @param dotSize preferred diameter of one dot; the cell pitch is dotSize × (1 + [gapRatio]).
 *   When the text would be wider than the available space, dots shrink so it always fits.
 * @param unlitColor colour of unlit dots (transparent hides them).
 */
@Composable
fun DotMatrixText(
    text: String,
    modifier: Modifier = Modifier,
    dotSize: Dp = 4.dp,
    color: Color = DotTheme.colors.dotLit,
    unlitColor: Color = Color.Transparent,
    gapRatio: Float = 0.45f,
) {
    val columns = DotMatrixFont.columns(text).coerceAtLeast(1)
    BoxWithConstraints(modifier) {
        val preferredPitch = dotSize * (1f + gapRatio)
        val pitch = if (constraints.hasBoundedWidth) minOf(preferredPitch, maxWidth / columns) else preferredPitch
        Canvas(
            modifier = Modifier
                .size(width = pitch * columns, height = pitch * DotMatrixFont.ROWS)
                .semantics { contentDescription = text },
        ) {
            val pitchPx = pitch.toPx()
            val radius = pitchPx / (1f + gapRatio) / 2f
            DotMatrixFont.forEachDot(text) { column, row, lit ->
                val dotColor = if (lit) color else unlitColor
                if (dotColor.alpha > 0f) {
                    drawCircle(
                        color = dotColor,
                        radius = radius,
                        center = Offset(column * pitchPx + pitchPx / 2f, row * pitchPx + pitchPx / 2f),
                    )
                }
            }
        }
    }
}
