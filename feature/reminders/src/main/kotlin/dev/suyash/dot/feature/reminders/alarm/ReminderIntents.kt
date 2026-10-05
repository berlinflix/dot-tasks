package dev.suyash.dot.feature.reminders.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.suyash.dot.core.domain.model.TaskId

/**
 * Every PendingIntent the reminder system hands to the OS: explicit component, immutable, and
 * carrying only task ids (never titles) as extras.
 */
internal object ReminderIntents {
    const val ACTION_FIRE = "dev.suyash.dot.action.REMINDER_FIRE"
    const val ACTION_SNOOZE_QUICK = "dev.suyash.dot.action.SNOOZE_QUICK"
    const val ACTION_DONE = "dev.suyash.dot.action.DONE"
    const val ACTION_DISMISS = "dev.suyash.dot.action.DISMISS"
    const val ACTION_RING_TIMEOUT = "dev.suyash.dot.action.RING_TIMEOUT"

    const val EXTRA_TASK_IDS = "dev.suyash.dot.extra.TASK_IDS"
    const val EXTRA_OPEN_TASK_ID = "dev.suyash.dot.extra.OPEN_TASK_ID"

    private const val REQUEST_FIRE = 1
    private const val REQUEST_RING_TIMEOUT = 2
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun fire(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_FIRE,
        Intent(context, ReminderAlarmReceiver::class.java).setAction(ACTION_FIRE),
        FLAGS,
    )

    fun ringTimeout(context: Context, ids: List<TaskId>): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_RING_TIMEOUT,
        Intent(context, ReminderActionReceiver::class.java)
            .setAction(ACTION_RING_TIMEOUT)
            .putExtra(EXTRA_TASK_IDS, ids.map { it.value }.toTypedArray()),
        FLAGS,
    )

    fun action(context: Context, action: String, ids: List<TaskId>, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ReminderActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_TASK_IDS, ids.map { it.value }.toTypedArray()),
            FLAGS,
        )

    /** Opens the app (its launcher activity), optionally focused on a task. */
    fun openApp(context: Context, taskId: TaskId?, requestCode: Int): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setPackage(context.packageName)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (taskId != null) launch.putExtra(EXTRA_OPEN_TASK_ID, taskId.value)
        return PendingIntent.getActivity(context, requestCode, launch, FLAGS)
    }

    fun idsFrom(intent: Intent): List<TaskId> =
        intent.getStringArrayExtra(EXTRA_TASK_IDS)?.filter { it.isNotBlank() && it.length <= 64 }?.map(::TaskId).orEmpty()

    /** Stable request code per (task, action) so per-task PendingIntents don't overwrite each other. */
    fun requestCode(taskId: TaskId, slot: Int): Int = (taskId.value.hashCode() and 0x0FFFFFFF) * 8 + slot
}
