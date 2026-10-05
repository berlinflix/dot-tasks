package dev.suyash.dot.core.domain.events

import dev.suyash.dot.core.domain.model.ListId
import dev.suyash.dot.core.domain.model.TaskId

/** Where a change came from. Remote (sync) changes must not be pushed back to the server. */
enum class ChangeOrigin { LOCAL, REMOTE }

/** Emitted after every committed write; drives alarms, widgets and sync. */
data class TaskChange(
    val origin: ChangeOrigin,
    val taskIds: Set<TaskId> = emptySet(),
    val listIds: Set<ListId> = emptySet(),
    /** True if any reminder time/state might have changed (alarms must be reconciled). */
    val remindersChanged: Boolean = true,
)

/**
 * A side effect that runs after each committed write: rescheduling alarms, refreshing widgets,
 * scheduling a sync push. Implementations must be idempotent and fast (they may run in a
 * BroadcastReceiver's goAsync window).
 */
fun interface TaskChangeObserver {
    suspend fun onChanged(change: TaskChange)
}
