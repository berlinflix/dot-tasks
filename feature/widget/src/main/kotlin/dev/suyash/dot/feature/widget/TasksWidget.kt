package dev.suyash.dot.feature.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.CheckboxDefaults
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
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
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dagger.hilt.android.EntryPointAccessors
import dev.suyash.dot.core.designsystem.dotmatrix.DotMatrixBitmap
import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.model.TaskId
import dev.suyash.dot.core.ui.TimeFormats
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * "What's next" widget: dot-matrix open-task counter, voice + add buttons, and the upcoming tasks with
 * one-tap complete. Honours the "hide titles in widgets" privacy setting.
 */
class TasksWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        val snapshot = WidgetSnapshot(
            tasks = deps.repository().upcomingSnapshot(limit = 8),
            openCount = deps.repository().openCountSnapshot(),
            hideTitles = deps.settings().current().hideWidgetTitles,
            now = ZonedDateTime.now(ZoneId.systemDefault()),
        )
        provideContent { TasksWidgetContent(snapshot) }
    }
}

internal data class WidgetSnapshot(
    val tasks: List<Task>,
    val openCount: Int,
    val hideTitles: Boolean,
    val now: ZonedDateTime,
)

private val TaskIdKey = ActionParameters.Key<String>("taskId")

@Composable
private fun TasksWidgetContent(snapshot: WidgetSnapshot) {
    val context = LocalContext.current
    val size = LocalSize.current
    val formats = TimeFormats.from(context)
    val density = context.resources.displayMetrics.density
    val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    // Dot-matrix counter rendered as a small bitmap in the accent red (reads well on black and white).
    val counter = DotMatrixBitmap.render(
        text = snapshot.openCount.coerceAtMost(99).toString().padStart(2, '0'),
        dotSizePx = 3.4f * density,
        litColor = if (night) 0xFFFF3B41.toInt() else 0xFFD71921.toInt(),
        unlitColor = if (night) 0x26FFFFFF else 0x1A000000,
    )

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetColors.surface)
            .cornerRadius(28.dp)
            .padding(16.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(counter),
                contentDescription = "${snapshot.openCount} open tasks",
                modifier = GlanceModifier.clickable(actionStartActivity(WidgetIntents.openApp(context))),
            )
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text("OPEN", style = TextStyle(color = WidgetColors.muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace))
                Text(
                    snapshot.now.format(DateTimeFormatter.ofPattern("EEE d MMM", context.resources.configuration.locales[0])).uppercase(),
                    style = TextStyle(color = WidgetColors.onSurface, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                )
            }
            RoundButton(R.drawable.ic_widget_plus, "Type a new task", filled = false, action = actionStartActivity(WidgetIntents.type(context)))
            Spacer(GlanceModifier.width(8.dp))
            RoundButton(R.drawable.ic_widget_mic, "Add a task by voice", filled = true, action = actionStartActivity(WidgetIntents.voice(context)))
        }
        Spacer(GlanceModifier.height(10.dp))

        if (snapshot.tasks.isEmpty()) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing scheduled", style = TextStyle(color = WidgetColors.muted, fontSize = 13.sp))
            }
        } else if (snapshot.hideTitles) {
            Text(
                "${snapshot.tasks.size} upcoming · titles hidden",
                style = TextStyle(color = WidgetColors.muted, fontSize = 13.sp),
                modifier = GlanceModifier.clickable(actionStartActivity(WidgetIntents.openApp(context))),
            )
        } else {
            val rows = if (size.height < 160.dp) snapshot.tasks.take(2) else snapshot.tasks
            LazyColumn {
                items(rows, itemId = { it.id.value.hashCode().toLong() }) { task ->
                    TaskLine(task, snapshot.now, formats)
                }
            }
        }
    }
}

@Composable
private fun TaskLine(task: Task, now: ZonedDateTime, formats: TimeFormats) {
    val context = LocalContext.current
    val at = task.reminder?.at?.atZone(ZoneId.systemDefault())
    val overdue = at != null && at.isBefore(now)
    Row(
        GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(
            checked = false,
            onCheckedChange = actionRunCallback<CompleteTaskAction>(actionParametersOf(TaskIdKey to task.id.value)),
            colors = CheckboxDefaults.colors(checkedColor = WidgetColors.onSurface, uncheckedColor = WidgetColors.muted),
            modifier = GlanceModifier.semantics { contentDescription = "Complete ${task.title}" },
        )
        Column(
            GlanceModifier
                .defaultWeight()
                .clickable(actionStartActivity(WidgetIntents.openApp(context, task.id.value))),
        ) {
            Text(
                task.title,
                maxLines = 1,
                style = TextStyle(color = WidgetColors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
            val meta = at?.let { formats.dayAndTime(it, now) } ?: task.dueDate?.let { formats.day(it, now.toLocalDate()) }
            if (meta != null) {
                Text(
                    meta,
                    maxLines = 1,
                    style = TextStyle(color = if (overdue) WidgetColors.red else WidgetColors.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

@Composable
private fun RoundButton(icon: Int, description: String, filled: Boolean, action: androidx.glance.action.Action) {
    Box(
        modifier = GlanceModifier
            .size(36.dp)
            .background(if (filled) WidgetColors.red else WidgetColors.chip)
            .cornerRadius(18.dp)
            .clickable(action)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (filled) WidgetColors.white else WidgetColors.onSurface),
            modifier = GlanceModifier.size(18.dp),
        )
    }
}

/** Ticking a task's checkbox in the widget. */
class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TaskIdKey]?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        deps.commands().setDone(TaskId(id), true)
        TasksWidget().updateAll(context)
    }
}

class TasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TasksWidget()
}

