package dev.suyash.dot.feature.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager

/** Asks the launcher to add a widget (the launcher shows its own confirmation). */
object WidgetPinning {

    fun isSupported(context: Context): Boolean =
        context.getSystemService(android.appwidget.AppWidgetManager::class.java).isRequestPinAppWidgetSupported

    suspend fun pinMic(context: Context): Boolean =
        GlanceAppWidgetManager(context).requestPinGlanceAppWidget(MicWidgetReceiver::class.java)

    suspend fun pinMicPill(context: Context): Boolean =
        GlanceAppWidgetManager(context).requestPinGlanceAppWidget(MicPillWidgetReceiver::class.java)

    suspend fun pinTasks(context: Context): Boolean =
        GlanceAppWidgetManager(context).requestPinGlanceAppWidget(TasksWidgetReceiver::class.java)
}
