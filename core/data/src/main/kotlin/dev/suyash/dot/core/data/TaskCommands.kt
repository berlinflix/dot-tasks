package dev.suyash.dot.core.data

import androidx.room.withTransaction
import dev.suyash.dot.core.data.db.DotDatabase
import dev.suyash.dot.core.data.db.OutboxEntity
import dev.suyash.dot.core.data.db.RecordType
import dev.suyash.dot.core.data.db.toEntity
import dev.suyash.dot.core.data.db.toRecord
import dev.suyash.dot.core.domain.events.ChangeOrigin
import dev.suyash.dot.core.domain.events.TaskChange
import dev.suyash.dot.core.domain.model.ListId
import dev.suyash.dot.core.domain.model.Reminder
import dev.suyash.dot.core.domain.model.RingMode
import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.model.TaskId
import dev.suyash.dot.core.domain.model.TaskList
import dev.suyash.dot.core.domain.model.TaskSource
import dev.suyash.dot.core.domain.order.FractionalIndex
import dev.suyash.dot.core.domain.sync.Hlc
import dev.suyash.dot.core.domain.sync.HlcClock
import dev.suyash.dot.core.domain.sync.ListField
import dev.suyash.dot.core.domain.sync.ListRecord
import dev.suyash.dot.core.domain.sync.TaskField
import dev.suyash.dot.core.domain.sync.TaskRecord
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Everything needed to create a task. */
data class TaskDraft(
    val listId: ListId,
    val title: String,
    val notes: String = "",
    val dueDate: LocalDate? = null,
    val reminder: Reminder? = null,
    val starred: Boolean = false,
    val source: TaskSource = TaskSource.MANUAL,
    val parentId: TaskId? = null,
)

/**
 * The single write path. Every mutation — from the UI, widgets, notification actions, voice capture
 * or sync — goes through here, so every write:
 *  1. happens in one Room transaction together with its outbox row (nothing is lost if the app dies),
 *  2. stamps the changed field groups with a fresh HLC (conflict-free merging across devices),
 *  3. then notifies [TaskChangeObserver]s (alarms, widgets, sync) after the commit.
 */
@Singleton
class TaskCommands @Inject constructor(
    private val database: DotDatabase,
    private val clock: Clock,
    private val hlc: HlcClock,
    private val notifier: ChangeNotifier,
) {
    private val tasks get() = database.taskDao()
    private val lists get() = database.taskListDao()
    private val outbox get() = database.outboxDao()

    // ------------------------------------------------------------------------------------------
    // Tasks
    // ------------------------------------------------------------------------------------------

    suspend fun createTask(draft: TaskDraft): TaskId {
        val id = TaskId.random()
        val now = clock.instant()
        database.withTransaction {
            ensureListExists(draft.listId, now)
            // New tasks go on top, like Google Tasks.
            val position = FractionalIndex.between(null, tasks.firstPosition(draft.listId.value))
            val stamp = hlc.now()
            val record = TaskRecord(
                task = Task(
                    id = id,
                    listId = draft.listId,
                    title = draft.title.trim(),
                    notes = draft.notes.trim(),
                    parentId = draft.parentId,
                    isStarred = draft.starred,
                    dueDate = draft.dueDate,
                    reminder = draft.reminder,
                    position = position,
                    source = draft.source,
                    createdAt = now,
                    updatedAt = now,
                ),
                deleted = false,
                clocks = TaskField.entries.associateWith { stamp },
            )
            tasks.upsert(record.toEntity(serverVersion = 0))
            enqueue(id.value, RecordType.TASK, now)
        }
        notify(TaskChange(ChangeOrigin.LOCAL, taskIds = setOf(id), remindersChanged = draft.reminder != null))
        return id
    }

    suspend fun rename(id: TaskId, title: String) =
        mutate(id, TaskField.TITLE, remindersChanged = false) { it.copy(title = title.trim()) }

    suspend fun setNotes(id: TaskId, notes: String) =
        mutate(id, TaskField.NOTES, remindersChanged = false) { it.copy(notes = notes.trim()) }

    suspend fun setDone(id: TaskId, done: Boolean) = mutate(id, TaskField.STATUS) {
        it.copy(isDone = done, completedAt = if (done) clock.instant() else null)
    }

    suspend fun setStarred(id: TaskId, starred: Boolean) =
        mutate(id, TaskField.STARRED, remindersChanged = false) { it.copy(isStarred = starred) }

    suspend fun setDueDate(id: TaskId, date: LocalDate?) =
        mutate(id, TaskField.DUE, remindersChanged = false) { it.copy(dueDate = date) }

    suspend fun setReminder(id: TaskId, reminder: Reminder?) =
        mutate(id, TaskField.REMINDER) { it.copy(reminder = reminder) }

    suspend fun setRingMode(id: TaskId, mode: RingMode) = mutate(id, TaskField.REMINDER) { task ->
        task.copy(reminder = task.reminder?.copy(mode = mode))
    }

    /**
     * Pushes the reminder to [until]. Tasks without a reminder get one (in [defaultMode]); a due date
     * earlier than the new day moves along with it.
     */
    suspend fun snooze(id: TaskId, until: ZonedDateTime, defaultMode: RingMode = RingMode.RING): Task? {
        val fields = setOf(TaskField.REMINDER, TaskField.DUE)
        return mutate(id, fields) { task ->
            val current = task.reminder
            val reminder = Reminder(
                at = until.toInstant(),
                zone = until.zone,
                mode = current?.mode ?: defaultMode,
                snoozeCount = (current?.snoozeCount ?: 0) + 1,
                lastFiredAt = null,
            )
            val newDay = until.toLocalDate()
            val due = task.dueDate?.let { if (it.isBefore(newDay)) newDay else it }
            task.copy(reminder = reminder, dueDate = due)
        }
    }

    /** Records that these reminders fired at [at] so they never ring twice (and other devices know). */
    suspend fun markFired(ids: Collection<TaskId>, at: Instant) {
        if (ids.isEmpty()) return
        val changed = mutableSetOf<TaskId>()
        database.withTransaction {
            val stamp = hlc.now()
            for (row in tasks.getAll(ids.map { it.value })) {
                val record = row.toRecord()
                val reminder = record.task.reminder ?: continue
                val updated = record.copy(
                    task = record.task.copy(reminder = reminder.copy(lastFiredAt = at), updatedAt = clock.instant()),
                    clocks = record.clocks + (TaskField.REMINDER to stamp),
                )
                tasks.upsert(updated.toEntity(row.serverVersion, row.recurrence))
                enqueue(row.id, RecordType.TASK, clock.instant())
                changed += record.task.id
            }
        }
        if (changed.isNotEmpty()) notify(TaskChange(ChangeOrigin.LOCAL, taskIds = changed))
    }

    /**
     * After a time-zone change, keeps pending reminders at the same wall-clock time ("8:00 stays 8:00")
     * by re-anchoring them in [zone].
     */
    suspend fun rebaseFloatingReminders(zone: ZoneId) {
        val changed = mutableSetOf<TaskId>()
        database.withTransaction {
            val stamp = hlc.now()
            val now = clock.instant()
            for (row in tasks.pendingReminders()) {
                val record = row.toRecord()
                val reminder = record.task.reminder ?: continue
                if (reminder.zone == zone) continue
                val wallClock = reminder.at.atZone(reminder.zone).toLocalDateTime()
                val rebased = reminder.copy(at = wallClock.atZone(zone).toInstant(), zone = zone)
                val updated = record.copy(
                    task = record.task.copy(reminder = rebased, updatedAt = now),
                    clocks = record.clocks + (TaskField.REMINDER to stamp),
                )
                tasks.upsert(updated.toEntity(row.serverVersion, row.recurrence))
                enqueue(row.id, RecordType.TASK, now)
                changed += record.task.id
            }
        }
        if (changed.isNotEmpty()) notify(TaskChange(ChangeOrigin.LOCAL, taskIds = changed))
    }

    /** Moves a task between [before] and [after] (positions of its new neighbours), optionally to another list. */
    suspend fun move(id: TaskId, toList: ListId, before: String?, after: String?) =
        mutate(id, TaskField.PLACEMENT, remindersChanged = false) {
            it.copy(listId = toList, position = FractionalIndex.between(before, after))
        }

    /** Soft-deletes (tombstones) a task so the deletion syncs. Returns the task for undo. */
    suspend fun delete(id: TaskId): Task? = setDeleted(id, true)

    suspend fun restore(id: TaskId): Task? = setDeleted(id, false)

    private suspend fun setDeleted(id: TaskId, deleted: Boolean): Task? {
        var result: Task? = null
        database.withTransaction {
            val row = tasks.get(id.value) ?: return@withTransaction
            if (row.deleted == deleted) return@withTransaction
            val record = row.toRecord()
            val now = clock.instant()
            val updated = record.copy(
                task = record.task.copy(updatedAt = now),
                deleted = deleted,
                clocks = record.clocks + (TaskField.DELETED to hlc.now()),
            )
            tasks.upsert(updated.toEntity(row.serverVersion, row.recurrence))
            enqueue(row.id, RecordType.TASK, now)
            result = record.task
        }
        if (result != null) notify(TaskChange(ChangeOrigin.LOCAL, taskIds = setOf(id)))
        return result
    }

    // ------------------------------------------------------------------------------------------
    // Lists
    // ------------------------------------------------------------------------------------------

    /** The default list has a fixed id, so two devices creating it offline converge on one list. */
    suspend fun ensureDefaultList(title: String = DEFAULT_LIST_TITLE): ListId {
        val id = DEFAULT_LIST_ID
        val created = database.withTransaction { ensureListExists(id, clock.instant(), title) }
        if (created) notify(TaskChange(ChangeOrigin.LOCAL, listIds = setOf(id), remindersChanged = false))
        return id
    }

    suspend fun createList(title: String): ListId {
        val id = ListId.random()
        val now = clock.instant()
        database.withTransaction {
            val position = FractionalIndex.between(lists.lastPosition(), null)
            val stamp = hlc.now()
            val record = ListRecord(
                list = TaskList(id = id, title = title.trim(), position = position, createdAt = now, updatedAt = now),
                deleted = false,
                clocks = ListField.entries.associateWith { stamp },
            )
            lists.upsert(record.toEntity(serverVersion = 0))
            enqueue(id.value, RecordType.LIST, now)
        }
        notify(TaskChange(ChangeOrigin.LOCAL, listIds = setOf(id), remindersChanged = false))
        return id
    }

    suspend fun renameList(id: ListId, title: String) {
        val changed = database.withTransaction {
            val row = lists.get(id.value) ?: return@withTransaction false
            val record = row.toRecord()
            val now = clock.instant()
            val updated = record.copy(
                list = record.list.copy(title = title.trim(), updatedAt = now),
                clocks = record.clocks + (ListField.TITLE to hlc.now()),
            )
            lists.upsert(updated.toEntity(row.serverVersion))
            enqueue(row.id, RecordType.LIST, now)
            true
        }
        if (changed) notify(TaskChange(ChangeOrigin.LOCAL, listIds = setOf(id), remindersChanged = false))
    }

    /** Deletes a list and every task in it (all as synced tombstones). The default list can't be deleted. */
    suspend fun deleteList(id: ListId) {
        require(id != DEFAULT_LIST_ID) { "The default list can't be deleted" }
        val taskIds = mutableSetOf<TaskId>()
        database.withTransaction {
            val row = lists.get(id.value) ?: return@withTransaction
            val now = clock.instant()
            val record = row.toRecord()
            lists.upsert(
                record.copy(
                    list = record.list.copy(updatedAt = now),
                    deleted = true,
                    clocks = record.clocks + (ListField.DELETED to hlc.now()),
                ).toEntity(row.serverVersion),
            )
            enqueue(row.id, RecordType.LIST, now)
            for (taskRow in tasks.allInList(id.value)) {
                val taskRecord = taskRow.toRecord()
                tasks.upsert(
                    taskRecord.copy(
                        task = taskRecord.task.copy(updatedAt = now),
                        deleted = true,
                        clocks = taskRecord.clocks + (TaskField.DELETED to hlc.now()),
                    ).toEntity(taskRow.serverVersion, taskRow.recurrence),
                )
                enqueue(taskRow.id, RecordType.TASK, now)
                taskIds += taskRecord.task.id
            }
        }
        notify(TaskChange(ChangeOrigin.LOCAL, taskIds = taskIds, listIds = setOf(id)))
    }

    // ------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------

    private suspend fun mutate(
        id: TaskId,
        field: TaskField,
        remindersChanged: Boolean = true,
        transform: (Task) -> Task,
    ): Task? = mutate(id, setOf(field), remindersChanged, transform)

    private suspend fun mutate(
        id: TaskId,
        fields: Set<TaskField>,
        remindersChanged: Boolean = true,
        transform: (Task) -> Task,
    ): Task? {
        var result: Task? = null
        var wrote = false
        database.withTransaction {
            val row = tasks.get(id.value) ?: return@withTransaction
            if (row.deleted) return@withTransaction
            val record = row.toRecord()
            val now = clock.instant()
            val changed = transform(record.task).copy(updatedAt = now)
            if (changed == record.task.copy(updatedAt = now)) {
                result = record.task
                return@withTransaction
            }
            val stamp: Hlc = hlc.now()
            val updated = record.copy(task = changed, clocks = record.clocks + fields.associateWith { stamp })
            tasks.upsert(updated.toEntity(row.serverVersion, row.recurrence))
            enqueue(row.id, RecordType.TASK, now)
            result = changed
            wrote = true
        }
        if (wrote) {
            notify(TaskChange(ChangeOrigin.LOCAL, taskIds = setOf(id), remindersChanged = remindersChanged))
        }
        return result
    }

    /** Returns true if the list had to be created. Must run inside a transaction. */
    private suspend fun ensureListExists(id: ListId, now: Instant, title: String = DEFAULT_LIST_TITLE): Boolean {
        val existing = lists.get(id.value)
        if (existing != null && !existing.deleted) return false
        val stamp = hlc.now()
        val position = existing?.position ?: FractionalIndex.between(null, null)
        val record = ListRecord(
            list = TaskList(id = id, title = existing?.title ?: title, position = position, createdAt = now, updatedAt = now),
            deleted = false,
            clocks = ListField.entries.associateWith { stamp },
        )
        lists.upsert(record.toEntity(existing?.serverVersion ?: 0))
        enqueue(id.value, RecordType.LIST, now)
        return true
    }

    private suspend fun enqueue(recordId: String, type: RecordType, now: Instant) {
        outbox.enqueue(OutboxEntity(recordId = recordId, recordType = type, enqueuedAt = now.toEpochMilli()))
    }

    private suspend fun notify(change: TaskChange) = notifier.notify(change)

    companion object {
        val DEFAULT_LIST_ID = ListId("default")
        const val DEFAULT_LIST_TITLE = "My Tasks"
    }
}
