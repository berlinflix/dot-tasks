package dev.suyash.dot.feature.reminders.alarm

import android.app.AlarmManager
import android.content.Context
import android.os.UserManager
import dagger.hilt.android.EntryPointAccessors
import dev.suyash.dot.core.domain.model.RingMode
import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.model.TaskId
import dev.suyash.dot.core.domain.ring.AlertStyle
import dev.suyash.dot.core.domain.ring.RingPolicy
import dev.suyash.dot.feature.reminders.AttentionStateReader
import dev.suyash.dot.feature.reminders.di.ReminderEntryPoint
import dev.suyash.dot.feature.reminders.notify.ReminderNotifier
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** What happens when the reminder alarm goes off. */
internal object ReminderFiring {

    /** Alarms can be delivered a hair early; treat anything due within this window as due now. */
    private const val EARLY_TOLERANCE_SECONDS = 2L

    suspend fun onAlarm(context: Context) {
        val app = context.applicationContext
        if (!app.getSystemService(UserManager::class.java).isUserUnlocked) {
            ringBeforeUnlock(app)
            return
        }
        handleDue(app)
    }

    /** Handles every reminder that is due, then (via markFired → observers) arms the next one. */
    suspend fun handleDue(context: Context) {
        val deps = EntryPointAccessors.fromApplication(context, ReminderEntryPoint::class.java)
        val now = deps.clock().instant()
        val due = deps.repository().dueReminders(now.plusSeconds(EARLY_TOLERANCE_SECONDS))
        val heardGenericRingAt = DirectBootStore(context).takeGenericRing()
        if (due.isEmpty()) {
            deps.scheduler().reconcile()
            return
        }

        // Mark first: even if posting fails, the reminder never rings twice.
        deps.commands().markFired(due.map { it.id }, now)

        val settings = deps.settings().current()
        val attention = deps.attention().read()
        val zonedNow = ZonedDateTime.ofInstant(now, ZoneId.systemDefault())
        val byStyle = due.groupBy { task ->
            val reminder = checkNotNull(task.reminder)
            val heardAlready = heardGenericRingAt != null && reminder.at.toEpochMilli() <= heardGenericRingAt
            if (heardAlready) AlertStyle.QUIET else RingPolicy.decide(reminder.mode, attention, reminder.at, now)
        }

        val notifier = deps.notifier()
        notifier.postMissed(byStyle[AlertStyle.MISSED].orEmpty(), settings, zonedNow)
        notifier.postQuiet(byStyle[AlertStyle.QUIET].orEmpty(), settings, zonedNow)
        notifier.postNotify(byStyle[AlertStyle.NOTIFY].orEmpty(), settings, zonedNow)

        // RING and VIBRATE are the same notification: the system plays sound or only vibrates
        // according to the ringer switch.
        val ringing: List<Task> = byStyle[AlertStyle.RING].orEmpty() + byStyle[AlertStyle.VIBRATE].orEmpty()
        if (ringing.isNotEmpty()) {
            notifier.postRinging(ringing, settings, zonedNow)
            scheduleRingTimeout(context, ringing.map { it.id }, now.plusSeconds(settings.ringDurationSeconds.toLong()))
        }
    }

    /**
     * Before the first unlock after a reboot the database is still encrypted with the user's
     * credential, so we only know *that* a reminder is due (from device-protected storage), not what it
     * says. Ring generically — still respecting silent/DND — and show details after unlock.
     */
    private fun ringBeforeUnlock(context: Context) {
        val store = DirectBootStore(context)
        val now = Instant.now()
        val horizon = now.plusSeconds(EARLY_TOLERANCE_SECONDS).toEpochMilli()
        val latestDue = store.upcoming().filter { it <= horizon }.maxOrNull()
        if (latestDue != null) {
            val attention = AttentionStateReader.read(context)
            val style = RingPolicy.decide(RingMode.RING, attention, Instant.ofEpochMilli(latestDue), now)
            if (style == AlertStyle.RING || style == AlertStyle.VIBRATE) {
                ReminderNotifier(context).postLockedGenericRing()
                store.markGenericRing(now.toEpochMilli())
            }
        }
        store.dropThrough(horizon)?.let { next -> armDirectBootAlarm(context, next) }
    }

    /** Arms the fire alarm without touching the database (usable before unlock). */
    fun armDirectBootAlarm(context: Context, atMillis: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val operation = ReminderIntents.fire(context)
        if (alarms.canScheduleExactAlarms()) {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, null), operation)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
        }
    }

    private fun scheduleRingTimeout(context: Context, ids: List<TaskId>, at: Instant) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val operation = ReminderIntents.ringTimeout(context, ids)
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation)
        }
    }
}
