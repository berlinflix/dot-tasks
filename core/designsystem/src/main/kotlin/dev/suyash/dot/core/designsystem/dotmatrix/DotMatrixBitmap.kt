package dev.suyash.dot.core.designsystem.dotmatrix

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.annotation.ColorInt
import androidx.core.graphics.createBitmap

/**
 * Renders dot-matrix text into a [Bitmap] for places that can't run Compose drawing — home-screen
 * widgets (RemoteViews can't load app fonts) and notification large icons.
 *
 * Keep bitmaps small: widget RemoteViews have a total bitmap memory cap (enforced on Android 17).
 */
object DotMatrixBitmap {

    private const val DEFAULT_GAP_RATIO = 0.45f

    fun render(
        text: String,
        dotSizePx: Float,
        @ColorInt litColor: Int,
        @ColorInt unlitColor: Int = 0,
        gapRatio: Float = DEFAULT_GAP_RATIO,
    ): Bitmap {
        val pitch = dotSizePx * (1f + gapRatio)
        val columns = DotMatrixFont.columns(text).coerceAtLeast(1)
        val width = (columns * pitch).toInt().coerceAtLeast(1)
        val height = (DotMatrixFont.ROWS * pitch).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = litColor }
        val unlit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = unlitColor }
        val radius = dotSizePx / 2f
        DotMatrixFont.forEachDot(text) { column, row, isLit ->
            val paint = if (isLit) lit else unlit
            if (paint.alpha > 0) {
                canvas.drawCircle(column * pitch + pitch / 2f, row * pitch + pitch / 2f, radius, paint)
            }
        }
        return bitmap
    }

    /** The largest [render] dot size at which [text] is at most [width] wide (any unit, e.g. dp). */
    fun dotSizeToFit(text: String, width: Float, gapRatio: Float = DEFAULT_GAP_RATIO): Float =
        width / (DotMatrixFont.columns(text).coerceAtLeast(1) * (1f + gapRatio))
}
