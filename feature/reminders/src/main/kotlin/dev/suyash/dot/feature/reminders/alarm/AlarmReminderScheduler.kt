package dev.suyash.dot.feature.reminders.alarm

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.suyash.dot.core.data.TaskRepository
import dev.suyash.dot.core.domain.events.TaskChange
import dev.suyash.dot.core.domain.events.TaskChangeObserver
import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.model.TaskId
import dev.suyash.dot.feature.reminders.notify.ReminderNotifier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps exactly **one** OS alarm armed: the earliest pending reminder. When it fires, every reminder
 * due by then is handled and the next one is armed. The schedule is derived purely from the database,
 * so reconciling is idempotent and safe to call from anywhere (boot, time change, sync, edits).
 */
@Singleton
class AlarmReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TaskRepository,
    private val notifier: ReminderNotifier,
) : TaskChangeObserver {

    private val mutex = Mutex()
    private val directBoot = DirectBootStore(context)

    override suspend fun onChanged(change: TaskChange) {
        if (change.taskIds.isNotEmpty()) dismissResolved(change.taskIds)
        if (change.remindersChanged) reconcile()
    }

    suspend fun reconcile() = mutex.withLock {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val operation = ReminderIntents.fire(context)
        val upcoming = repository.pendingReminderTimes(limit = 16)
        val at = upcoming.firstOrNull()
        if (at == null) {
            alarms.cancel(operation)
            directBoot.clear()
            return@withLock
        }
        if (canScheduleExact(alarms)) {
            // Alarm-clock alarms are never deferred by Doze or standby buckets. The status bar shows the
            // next time (never the title); tapping it opens the app.
            val show = ReminderIntents.openApp(context, taskId = null, requestCode = SHOW_REQUEST_CODE)
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation)
        } else {
            Log.w(TAG, "Exact alarms not allowed; reminders may be delayed")
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
        directBoot.saveUpcoming(upcoming)
    }

    /**
     * Removes notifications for tasks that no longer need attention (completed, deleted, snoozed or
     * edited elsewhere — e.g. from the app or another device while the phone was ringing).
     */
    private suspend fun dismissResolved(ids: Set<TaskId>) {
        val ringing = notifier.ringingIds()
        val current = repository.getTasks(ids + ringing).associateBy { it.id }
        fun resolved(id: TaskId): Boolean {
            val task: Task = current[id] ?: return true
            return task.isDone || task.reminder?.hasFired != true
        }
        notifier.cancel(ids.filter(::resolved))
        if (ringing.any { it in ids } && ringing.all(::resolved)) notifier.cancelRinging()
    }

    companion object {
        private const val TAG = "AlarmScheduler"
        private const val SHOW_REQUEST_CODE = 3

        fun canScheduleExact(alarms: AlarmManager): Boolean = alarms.canScheduleExactAlarms()
    }
}
