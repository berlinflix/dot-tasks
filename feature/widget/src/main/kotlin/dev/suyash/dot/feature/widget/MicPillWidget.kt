package dev.suyash.dot.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import dev.suyash.dot.core.designsystem.dotmatrix.DotMatrixBitmap

/**
 * The compact, horizontal voice widget: a pill with fully rounded (U-shaped) ends — red record dot,
 * a dot-matrix "NEW TASK" label and a round mic button. Sits on one row of the home screen; can be
 * used instead of, or alongside, the round [MicWidget].
 */
class MicPillWidget : GlanceAppWidget() {

    // Compact = record dot + mic only. The wider sizes let the label grow in steps as the user
    // stretches the widget (the launcher picks the largest size that fits, so content never overflows).
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(DpSize(110.dp, 48.dp)) + listOf(175, 190, 205, 220, 245).map { DpSize(it.dp, 56.dp) },
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { MicPillContent(actionStartActivity(WidgetIntents.voice(context))) }
    }
}

class MicPillWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MicPillWidget()
}

/** Pill layout shared by [MicPillWidget] and the wide size of [MicWidget]. */
@Composable
internal fun MicPillContent(open: Action) {
    val context = LocalContext.current
    val size = LocalSize.current
    // Keep a slim, elegant pill even when the launcher gives the row more height.
    val pillHeight: Dp = size.height.coerceIn(40.dp, 60.dp)
    val buttonSize = pillHeight - 12.dp
    val startPadding = pillHeight / 2 - 4.dp
    // The label gets whatever the dot, the mic button and the paddings leave, and is dropped when its
    // dots would be too small to read.
    val labelRoom = size.width - startPadding - RECORD_DOT - DOT_GAP - LABEL_GAP - buttonSize - END_PADDING
    val labelDot = DotMatrixBitmap.dotSizeToFit(LABEL, labelRoom.value).dp.coerceAtMost(MAX_LABEL_DOT)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(open)
            .semantics { contentDescription = "Add a task by voice" },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(pillHeight)
                .background(WidgetColors.surface)
                .cornerRadius(pillHeight / 2)
                .padding(start = startPadding, end = END_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(GlanceModifier.size(RECORD_DOT).background(WidgetColors.red).cornerRadius(RECORD_DOT / 2)) {}
            Spacer(GlanceModifier.width(DOT_GAP))
            // Weighted, so the label can only shrink (Image scales to fit) — it never pushes the mic off the pill.
            Box(GlanceModifier.defaultWeight(), contentAlignment = Alignment.CenterStart) {
                if (labelDot >= MIN_LABEL_DOT) {
                    // Dot-matrix label in neutral grey: readable on both the black (dark) and white (light) pill.
                    val density = context.resources.displayMetrics.density
                    val label = DotMatrixBitmap.render(
                        text = LABEL,
                        dotSizePx = labelDot.value * density,
                        litColor = 0xFF8A8A8F.toInt(),
                    )
                    Image(provider = ImageProvider(label), contentDescription = null)
                }
            }
            Spacer(GlanceModifier.width(LABEL_GAP))
            Box(
                modifier = GlanceModifier
                    .size(buttonSize)
                    .background(WidgetColors.onSurface)
                    .cornerRadius(buttonSize / 2),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_mic),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(WidgetColors.surface),
                    modifier = GlanceModifier.size(buttonSize * 0.5f),
                )
            }
        }
    }
}

private const val LABEL = "NEW TASK"
private val RECORD_DOT = 12.dp
private val DOT_GAP = 12.dp
private val LABEL_GAP = 8.dp
private val END_PADDING = 6.dp
private val MIN_LABEL_DOT = 1.dp
private val MAX_LABEL_DOT = 2.1.dp
