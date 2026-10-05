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

    val Settings: ImageVector by lazy {
        icon("Settings") {
            circle(12f, 12f, 3f)
            moveTo(12f, 3.5f); verticalLineTo(6f)
            moveTo(12f, 18f); verticalLineTo(20.5f)
            moveTo(3.5f, 12f); horizontalLineTo(6f)
            moveTo(18f, 12f); horizontalLineTo(20.5f)
            moveTo(6f, 6f); lineTo(7.8f, 7.8f)
            moveTo(16.2f, 16.2f); lineTo(18f, 18f)
            moveTo(6f, 18f); lineTo(7.8f, 16.2f)
            moveTo(16.2f, 7.8f); lineTo(18f, 6f)
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
