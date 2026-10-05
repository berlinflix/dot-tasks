package dev.suyash.dot.feature.reminders.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.suyash.dot.core.data.settings.UserSettings
import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.model.TaskId
import dev.suyash.dot.core.ui.TimeFormats
import dev.suyash.dot.feature.reminders.R
import dev.suyash.dot.feature.reminders.alarm.ReminderIntents
import dev.suyash.dot.feature.reminders.ring.RingingActivity
import dev.suyash.dot.feature.reminders.ring.SnoozePickerActivity
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import dev.suyash.dot.core.designsystem.R as DesignR

/**
 * Posts every reminder notification.
 *
 * Ringing works like a VoIP incoming call: an IMPORTANCE_HIGH channel whose sound is the ringtone with
 * USAGE_NOTIFICATION_RINGTONE, posted with FLAG_INSISTENT (loops) and a full-screen intent. Because the
 * *system* plays the sound on the ring stream, Android itself also silences it in silent/vibrate mode
 * and Do Not Disturb, and app background-audio restrictions (Android 17) don't apply.
 *
 * Deliberately has no database dependency so it can run before the user unlocks after a reboot.
 */
@Singleton
class ReminderNotifier @Inject constructor(@ApplicationContext private val context: Context) {

    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        val ring = NotificationChannel(CHANNEL_RING, context.getString(R.string.channel_ring_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_ring_description)
            setSound(
                Settings.System.DEFAULT_RINGTONE_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = CALL_VIBRATION
            setBypassDnd(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val quiet = NotificationChannel(CHANNEL_QUIET, context.getString(R.string.channel_quiet_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_quiet_description)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val notify = NotificationChannel(CHANNEL_NOTIFY, context.getString(R.string.channel_notify_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_notify_description)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val missed = NotificationChannel(CHANNEL_MISSED, context.getString(R.string.channel_missed_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.channel_missed_description)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannels(listOf(ring, quiet, notify, missed))
    }

    /** Rings (or vibrates, per the ringer switch) for a batch of reminders, like an incoming call. */
    fun postRinging(tasks: List<Task>, settings: UserSettings, now: ZonedDateTime) {
        if (tasks.isEmpty() || !canPost()) return
        ensureChannels()
        val ids = tasks.map { it.id }
        val formats = TimeFormats.from(context)
        val first = tasks.first()
        val at = first.reminder?.at?.atZone(ZoneId.systemDefault()) ?: now
        val title = if (tasks.size == 1) first.title.ifBlank { context.getString(R.string.reminder_public_title) }
        else context.getString(R.string.reminder_many, tasks.size)
        val ringScreen = ringScreenIntent(ids)

        val builder = NotificationCompat.Builder(context, CHANNEL_RING)
            .setSmallIcon(DesignR.drawable.ic_stat_dot)
            .setContentTitle(title)
            .setContentText(formats.dayAndTime(at, now))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(ringScreen, true)
            .setContentIntent(ringScreen)
            .setDeleteIntent(ReminderIntents.action(context, ReminderIntents.ACTION_DISMISS, ids, REQUEST_RING_DISMISS))
            .setTimeoutAfter(Duration.ofSeconds(settings.ringDurationSeconds.toLong() + 5).toMillis())
            .addAction(0, context.getString(R.string.action_snooze_10), ReminderIntents.action(context, ReminderIntents.ACTION_SNOOZE_QUICK, ids, REQUEST_RING_SNOOZE))
            .addAction(0, context.getString(R.string.action_later), snoozePickerIntent(ids, REQUEST_RING_LATER))
            .addAction(0, context.getString(R.string.action_done), ReminderIntents.action(context, ReminderIntents.ACTION_DONE, ids, REQUEST_RING_DONE))
            .addExtras(android.os.Bundle().apply { putStringArray(ReminderIntents.EXTRA_TASK_IDS, ids.map { it.value }.toTypedArray()) })
            .applyPrivacy(settings)

        val notification = builder.build().apply { flags = flags or Notification.FLAG_INSISTENT }
        notifySafely(RING_NOTIFICATION_ID, notification)
    }

    /** Before first unlock after a reboot we can't read the (encrypted) task list — ring generically. */
    fun postLockedGenericRing(ringSeconds: Int = 60) {
        if (!canPost()) return
        ensureChannels()
        val ringScreen = ringScreenIntent(emptyList())
        val notification = NotificationCompat.Builder(context, CHANNEL_RING)
            .setSmallIcon(DesignR.drawable.ic_stat_dot)
            .setContentTitle(context.getString(R.string.reminder_public_title))
            .setContentText(context.getString(R.string.reminder_locked_body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(ringScreen, true)
            .setContentIntent(ringScreen)
            .setOngoing(true)
            .setTimeoutAfter(ringSeconds * 1000L + 5000L)
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }
        notifySafely(RING_NOTIFICATION_ID, notification)
    }

    /** Silent heads-up (phone on silent / DND). */
    fun postQuiet(tasks: List<Task>, settings: UserSettings, now: ZonedDateTime) =
        tasks.forEach { postSingle(it, CHANNEL_QUIET, settings, now, missed = false) }

    /** One-shot notification for "notify only" tasks. */
    fun postNotify(tasks: List<Task>, settings: UserSettings, now: ZonedDateTime) =
        tasks.forEach { postSingle(it, CHANNEL_NOTIFY, settings, now, missed = false) }

    fun postMissed(tasks: List<Task>, settings: UserSettings, now: ZonedDateTime) =
        tasks.forEach { postSingle(it, CHANNEL_MISSED, settings, now, missed = true) }

    private fun postSingle(task: Task, channel: String, settings: UserSettings, now: ZonedDateTime, missed: Boolean) {
        if (!canPost()) return
        ensureChannels()
        val ids = listOf(task.id)
        val formats = TimeFormats.from(context)
        val at = task.reminder?.at?.atZone(ZoneId.systemDefault()) ?: now
        val title = task.title.ifBlank { context.getString(R.string.reminder_public_title) }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(DesignR.drawable.ic_stat_dot)
            .setContentTitle(if (missed) context.getString(R.string.reminder_missed_title, title) else title)
            .setContentText(formats.dayAndTime(at, now))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setWhen(at.toInstant().toEpochMilli())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(ReminderIntents.openApp(context, task.id, ReminderIntents.requestCode(task.id, SLOT_OPEN)))
            .addAction(0, context.getString(R.string.action_snooze_10), ReminderIntents.action(context, ReminderIntents.ACTION_SNOOZE_QUICK, ids, ReminderIntents.requestCode(task.id, SLOT_SNOOZE)))
            .addAction(0, context.getString(R.string.action_later), snoozePickerIntent(ids, ReminderIntents.requestCode(task.id, SLOT_LATER)))
            .addAction(0, context.getString(R.string.action_done), ReminderIntents.action(context, ReminderIntents.ACTION_DONE, ids, ReminderIntents.requestCode(task.id, SLOT_DONE)))
            .applyPrivacy(settings)
            .build()
        notifySafely(notificationIdFor(task.id), notification)
    }

    /** Stops the ring and removes the ringing notification. */
    fun cancelRinging() = manager.cancel(RING_NOTIFICATION_ID)

    /** Volume key / "silence": stop the sound but keep the reminders visible as quiet notifications. */
    fun silenceRinging(tasks: List<Task>, settings: UserSettings, now: ZonedDateTime) {
        cancelRinging()
        postQuiet(tasks, settings, now)
    }

    fun cancel(ids: Collection<TaskId>) = ids.forEach { manager.cancel(notificationIdFor(it)) }

    /** Ids in the currently ringing notification, if any. */
    fun ringingIds(): List<TaskId> = manager.activeNotifications
        .firstOrNull { it.id == RING_NOTIFICATION_ID }
        ?.notification?.extras?.getStringArray(ReminderIntents.EXTRA_TASK_IDS)
        ?.map(::TaskId)
        .orEmpty()

    private fun NotificationCompat.Builder.applyPrivacy(settings: UserSettings): NotificationCompat.Builder {
        val public = NotificationCompat.Builder(context, CHANNEL_QUIET)
            .setSmallIcon(DesignR.drawable.ic_stat_dot)
            .setContentTitle(context.getString(R.string.reminder_public_title))
            .setContentText(context.getString(R.string.reminder_locked_body))
            .build()
        return setVisibility(
            if (settings.showTitlesOnLockScreen) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_SECRET,
        ).setPublicVersion(public)
    }

    private fun ringScreenIntent(ids: List<TaskId>): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_RING_SCREEN,
        Intent(context, RingingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            .putExtra(ReminderIntents.EXTRA_TASK_IDS, ids.map { it.value }.toTypedArray()),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun snoozePickerIntent(ids: List<TaskId>, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, SnoozePickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ReminderIntents.EXTRA_TASK_IDS, ids.map { it.value }.toTypedArray()),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission")
    private fun notifySafely(id: Int, notification: Notification) {
        if (canPost()) manager.notify(id, notification)
    }

    companion object {
        const val CHANNEL_RING = "reminders_ring_v1"
        const val CHANNEL_QUIET = "reminders_quiet_v1"
        const val CHANNEL_NOTIFY = "reminders_notify_v1"
        const val CHANNEL_MISSED = "reminders_missed_v1"

        const val RING_NOTIFICATION_ID = 0x0D07

        private const val REQUEST_RING_SCREEN = 10
        private const val REQUEST_RING_SNOOZE = 11
        private const val REQUEST_RING_LATER = 12
        private const val REQUEST_RING_DONE = 13
        private const val REQUEST_RING_DISMISS = 14

        private const val SLOT_OPEN = 1
        private const val SLOT_SNOOZE = 2
        private const val SLOT_LATER = 3
        private const val SLOT_DONE = 4

        /** Call-like: 0.9 s buzz, 0.7 s pause (repeats while insistent). */
        private val CALL_VIBRATION = longArrayOf(0, 900, 700, 900, 700)

        fun notificationIdFor(taskId: TaskId): Int = (taskId.value.hashCode() and 0x7FFFFFFF) or 0x10000
    }
}
