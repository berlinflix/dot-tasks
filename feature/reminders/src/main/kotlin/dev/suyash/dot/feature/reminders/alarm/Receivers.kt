package dev.suyash.dot.feature.reminders.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import dagger.hilt.android.EntryPointAccessors
import dev.suyash.dot.core.domain.model.TaskId
import dev.suyash.dot.feature.reminders.di.ReminderEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.ZoneId
import java.time.ZonedDateTime

/** Runs [block] off the main thread while keeping the broadcast alive (goAsync), with a hard timeout. */
private fun BroadcastReceiver.runAsync(tag: String, block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try {
            withTimeout(9_000) { block() }
        } catch (e: Exception) {
            Log.e(tag, "Broadcast handling failed", e)
        } finally {
            pending.finish()
        }
    }
}

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private fun Context.isUnlocked(): Boolean = getSystemService(UserManager::class.java).isUserUnlocked

/** The single reminder alarm went off. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderIntents.ACTION_FIRE) return
        runAsync(TAG) { ReminderFiring.onAlarm(context) }
    }

    private companion object {
        const val TAG = "ReminderAlarm"
    }
}

/** Notification buttons (Snooze 10 min / Done), swipe-away, and the end of a ring. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val ids = ReminderIntents.idsFrom(intent)
        if (!context.isUnlocked()) {
            // Only the generic pre-unlock ring exists; there is nothing to update yet.
            return
        }
        runAsync(TAG) {
            val deps = EntryPointAccessors.fromApplication(context.applicationContext, ReminderEntryPoint::class.java)
            val notifier = deps.notifier()
            val commands = deps.commands()
            val now = ZonedDateTime.ofInstant(deps.clock().instant(), ZoneId.systemDefault())
            when (action) {
                ReminderIntents.ACTION_SNOOZE_QUICK -> {
                    notifier.cancelRinging()
                    notifier.cancel(ids)
                    val until = now.plusMinutes(10).withSecond(0).withNano(0)
                    ids.forEach { commands.snooze(it, until) }
                }
                ReminderIntents.ACTION_DONE -> {
                    notifier.cancelRinging()
                    notifier.cancel(ids)
                    ids.forEach { commands.setDone(it, true) }
                }
                ReminderIntents.ACTION_DISMISS -> {
                    // Swiped away: the user saw it. Stop the timeout; leave the tasks as they are.
                    cancelRingTimeout(context, ids)
                }
                ReminderIntents.ACTION_RING_TIMEOUT -> onRingTimeout(context, ids, now)
            }
        }
    }

    /** Nobody answered: stop ringing, then auto-snooze (if enabled) or leave a "missed" notification. */
    private suspend fun onRingTimeout(context: Context, ids: List<TaskId>, now: ZonedDateTime) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, ReminderEntryPoint::class.java)
        val notifier = deps.notifier()
        val settings = deps.settings().current()
        // Still "fired and not handled" = the user didn't snooze, complete or edit it.
        val unanswered = deps.repository().getTasks(ids).filter { !it.isDone && it.reminder?.hasFired == true }
        notifier.cancelRinging()
        if (unanswered.isEmpty()) return
        val autoSnooze = settings.autoSnoozeMinutes
        val (snoozable, missed) = unanswered.partition { autoSnooze > 0 && (it.reminder?.snoozeCount ?: 0) < MAX_AUTO_SNOOZES }
        val until = now.plusMinutes(autoSnooze.toLong()).withSecond(0).withNano(0)
        snoozable.forEach { deps.commands().snooze(it.id, until) }
        notifier.postMissed(missed, settings, now)
    }

    private fun cancelRingTimeout(context: Context, ids: List<TaskId>) {
        context.getSystemService(AlarmManager::class.java).cancel(ReminderIntents.ringTimeout(context, ids))
    }

    private companion object {
        const val TAG = "ReminderAction"
        const val MAX_AUTO_SNOOZES = 3
    }
}

/**
 * Re-arms reminders after events that clear or invalidate alarms: reboot (before and after unlock),
 * app update, clock/time-zone changes, and the user granting the exact-alarm permission.
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return
        val app = context.applicationContext
        runAsync(TAG) {
            if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
                if (!app.isUnlocked()) armFromDirectBootStore(app)
                return@runAsync
            }
            if (!app.isUnlocked()) return@runAsync
            val deps = EntryPointAccessors.fromApplication(app, ReminderEntryPoint::class.java)
            deps.notifier().ensureChannels()
            if (action == Intent.ACTION_TIMEZONE_CHANGED) {
                // Floating reminders: "8:00" stays 8:00 local time in the new zone.
                deps.commands().rebaseFloatingReminders(ZoneId.systemDefault())
            }
            deps.scheduler().reconcile()
        }
    }

    /** Before unlock: arm the next reminder time we mirrored to device-protected storage. */
    private fun armFromDirectBootStore(context: Context) {
        val next = DirectBootStore(context).next() ?: return
        ReminderFiring.armDirectBootAlarm(context, next)
    }

    private companion object {
        const val TAG = "SystemEvents"
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
