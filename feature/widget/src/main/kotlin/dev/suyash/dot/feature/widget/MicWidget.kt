package dev.suyash.dot.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/**
 * Nothing-style voice widget: a black (or white, following the system theme) circle with a ring of
 * dots and the red "record" dot. One tap opens voice capture. The wide size becomes a pill.
 */
class MicWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, SQUARE, PILL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { MicWidgetContent() }
    }

    companion object {
        private val SMALL = DpSize(48.dp, 48.dp)
        private val SQUARE = DpSize(110.dp, 110.dp)
        private val PILL = DpSize(230.dp, 56.dp)
    }
}

internal object WidgetColors {
    val surface = ColorProvider(day = Color.White, night = Color.Black)
    val onSurface = ColorProvider(day = Color.Black, night = Color.White)
    val muted = ColorProvider(day = Color(0xFF8A8A8F), night = Color(0xFF8A8A8F))
    val ring = ColorProvider(day = Color(0x40000000), night = Color(0x59FFFFFF))
    val red = ColorProvider(day = Color(0xFFD71921), night = Color(0xFFFF3B41))
    val chip = ColorProvider(day = Color(0xFFEDEDEF), night = Color(0xFF1C1C1F))
    val white = ColorProvider(day = Color.White, night = Color.White)
}

@Composable
private fun MicWidgetContent() {
    val context = LocalContext.current
    val size = LocalSize.current
    val wide = size.width > size.height * 1.6f
    val open = actionStartActivity(WidgetIntents.voice(context))

    if (wide) {
        MicPillContent(open)
        return
    }

    val diameter = minOf(size.width, size.height)
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(open)
            .semantics { contentDescription = "Add a task by voice" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = GlanceModifier
                .size(diameter)
                .background(WidgetColors.surface)
                .cornerRadius(diameter / 2),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.widget_dot_ring),
                contentDescription = null,
                colorFilter = ColorFilter.tint(WidgetColors.ring),
                modifier = GlanceModifier.size(diameter * 0.92f),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val dot = (diameter.value * 0.22f).coerceIn(10f, 30f).dp
                Box(GlanceModifier.size(dot).background(WidgetColors.red).cornerRadius(dot / 2)) {}
                if (diameter >= 96.dp) {
                    Spacer(GlanceModifier.height(10.dp))
                    Text(
                        "NEW TASK",
                        style = TextStyle(color = WidgetColors.muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}

class MicWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MicWidget()
}
