package dev.suyash.dot.feature.voice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Dot-matrix voice level meter: one column of dots per recent level sample, lit from the middle out
 * (Nothing's recorder look). Purely visual — it reads levels only, never audio.
 */
@Composable
internal fun LevelMeter(levels: List<Float>, lit: Color, unlit: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        val columns = 28
        val rows = 7
        val pitch = minOf(size.width / columns, size.height / rows)
        val radius = pitch * 0.32f
        val startX = (size.width - pitch * columns) / 2f
        val startY = (size.height - pitch * rows) / 2f
        val samples = List(columns) { i -> levels.getOrNull(levels.size - columns + i) ?: -2f }
        samples.forEachIndexed { column, db ->
            // Map roughly -2…10 dB onto 0…rows/2 lit dots on each side of the centre row.
            val half = (((db + 2f) / 12f).coerceIn(0f, 1f) * (rows / 2 + 1)).toInt()
            for (row in 0 until rows) {
                val distance = kotlin.math.abs(row - rows / 2)
                val isLit = distance < half || (distance == 0 && half == 0 && db > -1.5f)
                drawCircle(
                    color = if (isLit) lit else unlit,
                    radius = radius,
                    center = Offset(startX + column * pitch + pitch / 2f, startY + row * pitch + pitch / 2f),
                )
            }
        }
    }
}
