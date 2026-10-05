package dev.suyash.dot.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Thin-stroke line icons in the Nothing spirit (24dp grid, 1.6 stroke, round caps).
 * Drawn here instead of pulling in the huge material-icons-extended artifact.
 */
object DotIcons {

    private fun icon(name: String, filled: Boolean = false, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                if (filled) {
                    path(fill = SolidColor(Color.Black), pathBuilder = block)
                } else {
                    path(
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = 1.6f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                        pathBuilder = block,
                    )
                }
            }
            .build()

    val Mic: ImageVector by lazy {
        icon("Mic") {
            moveTo(12f, 3f)
            curveTo(10.34f, 3f, 9f, 4.34f, 9f, 6f)
            verticalLineTo(12f)
            curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
            curveTo(13.66f, 15f, 15f, 13.66f, 15f, 12f)
            verticalLineTo(6f)
            curveTo(15f, 4.34f, 13.66f, 3f, 12f, 3f)
            close()
            moveTo(5.5f, 11.5f)
            curveTo(5.5f, 15.09f, 8.41f, 18f, 12f, 18f)
            curveTo(15.59f, 18f, 18.5f, 15.09f, 18.5f, 11.5f)
            moveTo(12f, 18f)
            verticalLineTo(21f)
        }
    }

    val Plus: ImageVector by lazy {
        icon("Plus") {
            moveTo(12f, 5f); verticalLineTo(19f)
            moveTo(5f, 12f); horizontalLineTo(19f)
        }
    }

    val Check: ImageVector by lazy {
        icon("Check") {
            moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7.5f)
        }
    }

    val Close: ImageVector by lazy {
        icon("Close") {
            moveTo(6f, 6f); lineTo(18f, 18f)
            moveTo(18f, 6f); lineTo(6f, 18f)
        }
    }

    val Back: ImageVector by lazy {
        icon("Back") {
            moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f)
        }
    }

    val More: ImageVector by lazy {
        icon("More", filled = true) {
            circle(12f, 5.5f, 1.5f)
            circle(12f, 12f, 1.5f)
            circle(12f, 18.5f, 1.5f)
        }
    }

    /** Snooze / time. */
    val Clock: ImageVector by lazy {
        icon("Clock") {
            circle(12f, 12f, 8.5f)
            moveTo(12f, 7.5f); verticalLineTo(12f); lineTo(15f, 14f)
        }
    }

    val Bell: ImageVector by lazy {
        icon("Bell") {
            moveTo(6f, 16.5f)
            verticalLineTo(11f)
            curveTo(6f, 7.69f, 8.69f, 5f, 12f, 5f)
            curveTo(15.31f, 5f, 18f, 7.69f, 18f, 11f)
            verticalLineTo(16.5f)
            lineTo(19.5f, 18f)
            horizontalLineTo(4.5f)
            close()
            moveTo(10f, 20.5f); horizontalLineTo(14f)
            moveTo(12f, 3f); verticalLineTo(5f)
        }
    }

    /** Ring like a call. */
    val Phone: ImageVector by lazy {
        icon("Phone") {
            moveTo(5f, 4.5f)
            horizontalLineTo(8.5f)
            lineTo(10f, 8.5f)
            lineTo(8f, 10f)
            curveTo(9f, 12.2f, 11.8f, 15f, 14f, 16f)
            lineTo(15.5f, 14f)
            lineTo(19.5f, 15.5f)
            verticalLineTo(19f)
            curveTo(19.5f, 19.83f, 18.83f, 20.5f, 18f, 20.5f)
            curveTo(10.54f, 20.5f, 3.5f, 13.46f, 3.5f, 6f)
            curveTo(3.5f, 5.17f, 4.17f, 4.5f, 5f, 4.5f)
            close()
        }
    }

    val Star: ImageVector by lazy {
        icon("Star") { star() }
    }

    val StarFilled: ImageVector by lazy {
        icon("StarFilled", filled = true) { star() }
    }

    val Calendar: ImageVector by lazy {
        icon("Calendar") {
            moveTo(5f, 6.5f); horizontalLineTo(19f); verticalLineTo(19.5f); horizontalLineTo(5f); close()
            moveTo(5f, 10.5f); horizontalLineTo(19f)
            moveTo(9f, 4.5f); verticalLineTo(8f)
            moveTo(15f, 4.5f); verticalLineTo(8f)
        }
    }

    val Trash: ImageVector by lazy {
        icon("Trash") {
            moveTo(4.5f, 7f); horizontalLineTo(19.5f)
            moveTo(9.5f, 7f); verticalLineTo(4.5f); horizontalLineTo(14.5f); verticalLineTo(7f)
            moveTo(6.5f, 7f); lineTo(7.5f, 19.5f); horizontalLineTo(16.5f); lineTo(17.5f, 7f)
        }
    }

    /** Sliders (a sun-like gear would look too much like "Today"). */
    val Settings: ImageVector by lazy {
        icon("Settings") {
            moveTo(4f, 7f); horizontalLineTo(11f)
            moveTo(17f, 7f); horizontalLineTo(20f)
            circle(14f, 7f, 2.5f)
            moveTo(4f, 17f); horizontalLineTo(7f)
            moveTo(13f, 17f); horizontalLineTo(20f)
            circle(10f, 17f, 2.5f)
        }
    }

    val Notes: ImageVector by lazy {
        icon("Notes") {
            moveTo(5f, 7f); horizontalLineTo(19f)
            moveTo(5f, 12f); horizontalLineTo(19f)
            moveTo(5f, 17f); horizontalLineTo(13f)
        }
    }

    val Keyboard: ImageVector by lazy {
        icon("Keyboard") {
            moveTo(3.5f, 6.5f); horizontalLineTo(20.5f); verticalLineTo(17.5f); horizontalLineTo(3.5f); close()
            moveTo(8f, 14f); horizontalLineTo(16f)
            moveTo(7f, 10f); horizontalLineTo(7.01f)
            moveTo(10.33f, 10f); horizontalLineTo(10.34f)
            moveTo(13.66f, 10f); horizontalLineTo(13.67f)
            moveTo(17f, 10f); horizontalLineTo(17.01f)
        }
    }

    val ChevronDown: ImageVector by lazy {
        icon("ChevronDown") {
            moveTo(6f, 9.5f); lineTo(12f, 15.5f); lineTo(18f, 9.5f)
        }
    }

    val Repeat: ImageVector by lazy {
        icon("Repeat") {
            moveTo(17f, 2.5f); lineTo(20f, 5.5f); lineTo(17f, 8.5f)
            moveTo(4f, 11f); verticalLineTo(9.5f)
            curveTo(4f, 7.29f, 5.79f, 5.5f, 8f, 5.5f); horizontalLineTo(20f)
            moveTo(7f, 21.5f); lineTo(4f, 18.5f); lineTo(7f, 15.5f)
            moveTo(20f, 13f); verticalLineTo(14.5f)
            curveTo(20f, 16.71f, 18.21f, 18.5f, 16f, 18.5f); horizontalLineTo(4f)
        }
    }

    val Search: ImageVector by lazy {
        icon("Search") {
            circle(11f, 11f, 6.5f)
            moveTo(16f, 16f); lineTo(20.5f, 20.5f)
        }
    }

    /** "Today". */
    val Sun: ImageVector by lazy {
        icon("Sun") {
            circle(12f, 12f, 3.8f)
            moveTo(12f, 2.5f); verticalLineTo(4.5f)
            moveTo(12f, 19.5f); verticalLineTo(21.5f)
            moveTo(2.5f, 12f); horizontalLineTo(4.5f)
            moveTo(19.5f, 12f); horizontalLineTo(21.5f)
            moveTo(5.28f, 5.28f); lineTo(6.7f, 6.7f)
            moveTo(17.3f, 17.3f); lineTo(18.72f, 18.72f)
            moveTo(5.28f, 18.72f); lineTo(6.7f, 17.3f)
            moveTo(17.3f, 6.7f); lineTo(18.72f, 5.28f)
        }
    }

    val Subtask: ImageVector by lazy {
        icon("Subtask") {
            moveTo(6f, 4f); verticalLineTo(12f)
            curveTo(6f, 13.1f, 6.9f, 14f, 8f, 14f); horizontalLineTo(18.5f)
            moveTo(15f, 10.5f); lineTo(18.5f, 14f); lineTo(15f, 17.5f)
        }
    }

    val Copy: ImageVector by lazy {
        icon("Copy") {
            moveTo(9f, 9f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(9f); close()
            moveTo(15f, 5f); verticalLineTo(4f); horizontalLineTo(4f); verticalLineTo(15f); horizontalLineTo(5f)
        }
    }

    val Share: ImageVector by lazy {
        icon("Share") {
            moveTo(12f, 3f); verticalLineTo(15f)
            moveTo(8f, 7f); lineTo(12f, 3f); lineTo(16f, 7f)
            moveTo(5f, 12f); verticalLineTo(19f)
            curveTo(5f, 20.1f, 5.9f, 21f, 7f, 21f); horizontalLineTo(17f)
            curveTo(18.1f, 21f, 19f, 20.1f, 19f, 19f); verticalLineTo(12f)
        }
    }

    val Lock: ImageVector by lazy {
        icon("Lock") {
            moveTo(6f, 11f); horizontalLineTo(18f); verticalLineTo(21f); horizontalLineTo(6f); close()
            moveTo(8f, 11f); verticalLineTo(8f)
            arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 16f, y1 = 8f)
            verticalLineTo(11f)
            moveTo(12f, 15f); verticalLineTo(17f)
        }
    }

    val Export: ImageVector by lazy {
        icon("Export") {
            moveTo(12f, 3f); verticalLineTo(15f)
            moveTo(8f, 11f); lineTo(12f, 15f); lineTo(16f, 11f)
            moveTo(5f, 20f); horizontalLineTo(19f)
        }
    }

    val DragHandle: ImageVector by lazy {
        icon("DragHandle") {
            moveTo(9f, 6f); lineTo(9.01f, 6f); moveTo(15f, 6f); lineTo(15.01f, 6f)
            moveTo(9f, 12f); lineTo(9.01f, 12f); moveTo(15f, 12f); lineTo(15.01f, 12f)
            moveTo(9f, 18f); lineTo(9.01f, 18f); moveTo(15f, 18f); lineTo(15.01f, 18f)
        }
    }

    val ListIcon: ImageVector by lazy {
        icon("List") {
            moveTo(9f, 6f); horizontalLineTo(20f)
            moveTo(9f, 12f); horizontalLineTo(20f)
            moveTo(9f, 18f); horizontalLineTo(20f)
            moveTo(4.5f, 6f); lineTo(4.51f, 6f)
            moveTo(4.5f, 12f); lineTo(4.51f, 12f)
            moveTo(4.5f, 18f); lineTo(4.51f, 18f)
        }
    }

    val Sort: ImageVector by lazy {
        icon("Sort") {
            moveTo(4f, 7f); horizontalLineTo(20f)
            moveTo(7f, 12f); horizontalLineTo(17f)
            moveTo(10f, 17f); horizontalLineTo(14f)
        }
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    private fun PathBuilder.star() {
        moveTo(12f, 3.8f)
        lineTo(14.45f, 8.93f)
        lineTo(20f, 9.6f)
        lineTo(15.9f, 13.42f)
        lineTo(16.98f, 19f)
        lineTo(12f, 16.25f)
        lineTo(7.02f, 19f)
        lineTo(8.1f, 13.42f)
        lineTo(4f, 9.6f)
        lineTo(9.55f, 8.93f)
        close()
    }
}
