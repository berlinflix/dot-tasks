package dev.suyash.dot.core.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How a reminder gets your attention when it fires. */
enum class RingMode {
    /** Rings like an incoming call (looping ringtone + full-screen screen), subject to silent/DND. */
    RING,

    /** A normal one-shot notification. */
    NOTIFY,
}

enum class TaskSource { MANUAL, VOICE }

/**
 * A reminder attached to a task.
 *
 * [at] is the absolute fire time. [zone] is the zone the user meant; reminders are "floating":
 * when the device time zone changes, the wall-clock time is kept (8:00 stays 8:00).
 */
data class Reminder(
    val at: Instant,
    val zone: ZoneId,
    val mode: RingMode = RingMode.RING,
    val snoozeCount: Int = 0,
    val lastFiredAt: Instant? = null,
) {
    /** True once the reminder has fired for its current [at] (i.e. not re-armed by a snooze/edit). */
    val hasFired: Boolean get() = lastFiredAt != null && !lastFiredAt.isBefore(at)
}

data class Task(
    val id: TaskId,
    val listId: ListId,
    val title: String,
    val notes: String = "",
    val parentId: TaskId? = null,
    val isDone: Boolean = false,
    val completedAt: Instant? = null,
    val isStarred: Boolean = false,
    val dueDate: LocalDate? = null,
    val reminder: Reminder? = null,
    /** Fractional-index sort key (see [dev.suyash.dot.core.domain.order.FractionalIndex]). */
    val position: String,
    val source: TaskSource = TaskSource.MANUAL,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class TaskList(
    val id: ListId,
    val title: String,
    val position: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
