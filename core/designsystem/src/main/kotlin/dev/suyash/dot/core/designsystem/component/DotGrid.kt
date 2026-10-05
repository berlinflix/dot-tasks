package dev.suyash.dot.core.designsystem.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Paints a faint dot grid behind the content (the Nothing texture). */
fun Modifier.dotGrid(color: Color, spacing: Dp = 14.dp, dotRadius: Dp = 1.dp): Modifier = drawBehind {
    val step = spacing.toPx()
    val radius = dotRadius.toPx()
    var y = step / 2f
    while (y < size.height) {
        var x = step / 2f
        while (x < size.width) {
            drawCircle(color = color, radius = radius, center = Offset(x, y))
            x += step
        }
        y += step
    }
}
