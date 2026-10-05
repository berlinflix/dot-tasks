package dev.suyash.dot.core.data

import androidx.room.withTransaction
import dev.suyash.dot.core.data.db.DotDatabase
import dev.suyash.dot.core.data.db.OutboxEntity
import dev.suyash.dot.core.data.db.RecordType
import dev.suyash.dot.core.data.db.TaskEntity
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
import dev.suyash.dot.core.domain.repeat.RepeatRule
import dev.suyash.dot.core.domain.sync.Hlc
import dev.suyash.dot.core.domain.sync.HlcClock
import dev.suyash.dot.core.domain.sync.ListField
import dev.suyash.dot.core.domain.sync.ListRecord
import dev.suyash.dot.core.domain.sync.TaskField
import dev.suyash.dot.core.domain.sync.TaskRecord
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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
    /** Makes it a subtask (it then lives in the parent's list). */
    val parentId: TaskId? = null,
    /** A repeating task always has a date: the due date, else the reminder's date, else today. */
    val repeat: RepeatRule? = null,
)

/**
 * The single write path. Every mutation — from the UI, widgets, notification actions, voice capture
 * or sync — goes through here, so every write:
 *  1. happens in one Room transaction together with its outbox row (nothing is lost if the app dies),
 *  2. stamps the changed field groups with a fresh HLC (conflict-free merging across devices),
 *  3. then notifies [TaskChangeObserver]s (alarms, widgets, sync) after the commit.
 *
 * Deleting erases a task's content at once: only an empty "deleted" marker stays (so other devices
 * learn about it), and the sync layer drops those markers for good after a retention period.
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
    // Creating
    // ------------------------------------------------------------------------------------------

    suspend fun createTask(draft: TaskDraft): TaskId {
        val id = TaskId.random()
        var hasReminder = false
        database.withTransaction {
            val now = clock.instant()
            val parent = draft.parentId?.let { tasks.get(it.value) }?.takeUnless { it.deleted }
            val listId = parent?.let { ListId(it.listId) } ?: draft.listId
            ensureListExists(listId, now)
            val position = if (parent != null) {
                FractionalIndex.between(tasks.lastSubtaskPosition(parent.id), null) // subtasks: at the bottom
            } else {
                FractionalIndex.between(null, tasks.firstPosition(listId.value)) // tasks: on top, like Google Tasks
            }
            val repeatDate = draft.repeat?.let { draft.dueDate ?: draft.reminder?.localDate() ?: LocalDate.now(clock) }
            val task = Task(
                id = id,
                listId = listId,
                title = draft.title.trim(),
                notes = draft.notes.trim(),
                parentId = parent?.let { TaskId(it.id) },
                isStarred = draft.starred,
                dueDate = repeatDate ?: draft.dueDate,
                reminder = draft.reminder,
                position = position,
                source = draft.source,
                createdAt = now,
                updatedAt = now,
                repeat = draft.repeat?.let { rule -> repeatDate?.let(rule::anchoredTo) },
            )
            insert(task, now, hlc.now())
            hasReminder = task.reminder != null
        }
        notify(TaskChange(ChangeOrigin.LOCAL, taskIds = setOf(id), remindersChanged = hasReminder))
        return id
    }

    suspend fun addSubtask(parentId: TaskId, title: String): TaskId? {
        val parent = tasks.get(parentId.value)?.takeUnless { it.deleted } ?: return null
        return createTask(TaskDraft(listId = ListId(parent.listId), title = title, parentId = parentId))
    }

    /** Copies a task (and its subtasks) to just below the original. Returns the copy's id. */
    suspend fun duplicate(id: TaskId): TaskId? {
        val copies = mutableSetOf<TaskId>()
        database.withTransaction {
            val row = tasks.get(id.value)?.takeUnless { it.deleted } ?: return@withTransaction
            val now = clock.instant()
            val stamp = hlc.now()
            val next = tasks.nextSiblingPosition(row.listId, row.parentId, row.position)
            val original = row.toRecord().task
            val copy = original.freshCopy(TaskId.random(), now).copy(position = FractionalIndex.between(row.position, next))
            insert(copy, now, stamp)
            copies += copy.id
            for (sub in tasks.subtasksOf(row.id)) {
                val subCopy = sub.toRecord().task.freshCopy(TaskId.random(), now).copy(parentId = copy.id)
                insert(subCopy, now, stamp)
                copies += subCopy.id
            }
        }
        if (copies.isEmpty()) return null
        notify(TaskChange(ChangeOrigin.LOCAL, taskIds = copies))
        return copies.first()
    }

    // ------------------------------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------------------------------

    suspend fun rename(id: TaskId, title: String) =
        mutate(id, TaskField.TITLE, remindersChanged = false) { it.copy(title = title.trim()) }

    suspend fun setNotes(id: TaskId, notes: String) =
        mutate(id, TaskField.NOTES, remindersChanged = false) { it.copy(notes = notes.trim()) }

    suspend fun setStarred(id: TaskId, starred: Boolean) =
        mutate(id, TaskField.STARRED, remindersChanged = false) { it.copy(isStarred = starred) }

    /** Sets or clears the due date. A repeating task needs one, so clearing it also ends the series. */
    suspend fun setDueDate(id: TaskId, date: LocalDate?) =
        mutate(id, setOf(TaskField.DUE, TaskField.REPEAT), remindersChanged = false) { task ->
            task.copy(dueDate = date, repeat = task.repeat?.let { rule -> date?.let { rule.movedFrom(task.dueDate, it) } })
        }

    suspend fun setReminder(id: TaskId, reminder: Reminder?) =
        mutate(id, TaskField.REMINDER) { it.copy(reminder = reminder) }

    suspend fun setRingMode(id: TaskId, mode: RingMode) = mutate(id, TaskField.REMINDER) { task ->
        task.copy(reminder = task.reminder?.copy(mode = mode))
    }

    /**
     * Google-Tasks-style "date/time": a due [date] with an optional [time] (which is the reminder).
     * Clearing the date clears the time and ends a repeat; moving it moves the repeat's weekday.
     */
    suspend fun setSchedule(id: TaskId, date: LocalDate?, time: LocalTime?, defaultMode: RingMode, zone: ZoneId = clock.zone) =
        mutate(id, setOf(TaskField.DUE, TaskField.REMINDER, TaskField.REPEAT)) { task ->
            val reminder = if (date != null && time != null) {
                val at = date.atTime(time).atZone(zone).toInstant()
                if (task.reminder?.at == at) task.reminder else Reminder(at = at, zone = zone, mode = task.reminder?.mode ?: defaultMode)
            } else {
                null
            }
            task.copy(
                dueDate = date,
                reminder = reminder,
                repeat = task.repeat?.let { rule -> date?.let { rule.movedFrom(task.dueDate, it) } },
            )
        }

    /** Makes the task repeat (giving it a date if it has none), or stops it repeating. */
    suspend fun setRepeat(id: TaskId, rule: RepeatRule?) =
        mutate(id, setOf(TaskField.REPEAT, TaskField.DUE)) { task ->
            if (rule == null) {
                task.copy(repeat = null)
            } else {
                val date = task.dueDate ?: task.reminder?.localDate() ?: LocalDate.now(clock)
                task.copy(dueDate = date, repeat = rule.anchoredTo(date))
            }
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
            val now = clock.instant()
            for (row in tasks.getAll(ids.map { it.value })) {
                val record = row.toRecord()
                val reminder = record.task.reminder ?: continue
                val updated = record.copy(
                    task = record.task.copy(reminder = reminder.copy(lastFiredAt = at), updatedAt = now),
                    clocks = record.clocks + (TaskField.REMINDER to stamp),
                )
                write(updated, row.serverVersion, now)
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
                write(updated, row.serverVersion, now)
                changed += record.task.id
            }
        }
        if (changed.isNotEmpty()) notify(TaskChange(ChangeOrigin.LOCAL, taskIds = changed))
    }

    // ------------------------------------------------------------------------------------------
    // Completing
    // ------------------------------------------------------------------------------------------

    /**
     * Completing a task also completes its open subtasks; completing a repeating task creates its next
     * occurrence (with a fresh checklist). Reopening right after undoes both.
     */
    suspend fun setDone(id: TaskId, done: Boolean) = if (done) complete(id) else reopen(id)

    private suspend fun complete(id: TaskId) {
        val changed = mutableSetOf<TaskId>()
        database.withTransaction {
            val row = tasks.get(id.value)?.takeUnless { it.deleted || it.done } ?: return@withTransaction
            val now = clock.instant()
            val stamp = hlc.now()
            val record = row.toRecord()
            val task = record.task
            val successor = nextOccurrence(task, now)
            // The series moves on to the successor; the last occurrence keeps its rule.
            val fields = if (successor != null) setOf(TaskField.STATUS, TaskField.REPEAT) else setOf(TaskField.STATUS)
            write(
                record.copy(
                    task = task.copy(isDone = true, completedAt = now, updatedAt = now, repeat = if (successor != null) null else task.repeat),
                    clocks = record.clocks + fields.associateWith { stamp },
                ),
                row.serverVersion,
                now,
            )
            changed += id

            // Same completion time as the parent, so reopening can tell which ones to take back.
            val subtasks = tasks.subtasksOf(row.id)
            for (sub in subtasks.filterNot { it.done }) {
                val subRecord = sub.toRecord()
                write(
                    subRecord.copy(
                        task = subRecord.task.copy(isDone = true, completedAt = now, updatedAt = now),
                        clocks = subRecord.clocks + (TaskField.STATUS to stamp),
                    ),
                    sub.serverVersion,
                    now,
                )
                changed += subRecord.task.id
            }

            if (successor != null && insertIfAbsent(successor, now, stamp)) {
                changed += successor.id
                for (sub in subtasks) {
                    val subTask = sub.toRecord().task
                    val fresh = subTask.freshCopy(subTask.id.successor(), now)
                        .copy(parentId = successor.id, dueDate = null, reminder = null, repeat = null)
                    if (insertIfAbsent(fresh, now, stamp)) changed += fresh.id
                }
            }
        }
        if (changed.isNotEmpty()) notify(TaskChange(ChangeOrigin.LOCAL, taskIds = changed))
    }

    private suspend fun reopen(id: TaskId) {
        val changed = mutableSetOf<TaskId>()
        database.withTransaction {
            val row = tasks.get(id.value)?.takeUnless { it.deleted || !it.done } ?: return@withTransaction
            val now = clock.instant()
            val stamp = hlc.now()
            val record = row.toRecord()
            var repeat = record.task.repeat

            // Undo right after completing: take back the occurrence it created, if nobody touched it.
            val successorRow = tasks.get(id.successor().value)
                ?.takeIf { !it.deleted && !it.done && it.createdAt == it.updatedAt && repeat == null }
            if (successorRow != null) {
                val successor = successorRow.toRecord()
                repeat = successor.task.repeat?.forPreviousOccurrence()
                for (doomed in listOf(successorRow) + tasks.subtasksOf(successorRow.id)) {
                    tombstone(doomed.toRecord(), doomed.serverVersion, now, stamp)
                    changed += TaskId(doomed.id)
                }
            }
            val fields = if (repeat != record.task.repeat) setOf(TaskField.STATUS, TaskField.REPEAT) else setOf(TaskField.STATUS)
            write(
                record.copy(
                    task = record.task.copy(isDone = false, completedAt = null, updatedAt = now, repeat = repeat),
                    clocks = record.clocks + fields.associateWith { stamp },
                ),
                row.serverVersion,
                now,
            )
            changed += id

            for (sub in tasks.subtasksOf(row.id)) {
                if (!sub.done || sub.completedAt != row.completedAt) continue
                val subRecord = sub.toRecord()
                write(
                    subRecord.copy(
                        task = subRecord.task.copy(isDone = false, completedAt = null, updatedAt = now),
                        clocks = subRecord.clocks + (TaskField.STATUS to stamp),
                    ),
                    sub.serverVersion,
                    now,
                )
                changed += subRecord.task.id
            }
        }
        if (changed.isNotEmpty()) notify(TaskChange(ChangeOrigin.LOCAL, taskIds = changed))
    }

    /**
     * The next occurrence of a repeating task, or null if the series is over. Occurrences that are
     * already past (a daily task completed days late) are skipped; the reminder keeps its time of day.
     */
    private fun nextOccurrence(task: Task, now: Instant): Task? {
        val rule = task.repeat ?: return null
        val nextRule = rule.forNextOccurrence() ?: return null
        val zone = task.reminder?.zone ?: clock.zone
        val today = now.atZone(zone).toLocalDate()
        val time = task.reminder?.at?.atZone(zone)?.toLocalTime()
        fun isOver(date: LocalDate) = date.isBefore(today) || (time != null && !date.atTime(time).atZone(zone).toInstant().isAfter(now))

        var next = rule.nextAfter(task.dueDate ?: task.reminder?.localDate() ?: today) ?: return null
        var steps = 0
        while (isOver(next)) {
            next = rule.nextAfter(next) ?: return null
            if (++steps > MAX_CATCH_UP) return null
        }
        return task.freshCopy(task.id.successor(), now).copy(
            dueDate = next,
            reminder = task.reminder?.let { r -> time?.let { Reminder(at = next.atTime(it).atZone(zone).toInstant(), zone = zone, mode = r.mode) } },
            repeat = nextRule,
        )
    }

    // ------------------------------------------------------------------------------------------
    // Moving
    // ------------------------------------------------------------------------------------------

    /** Reorders a task between its new neighbours' positions [before] and [after] in the same list. */
    suspend fun reorder(id: TaskId, before: String?, after: String?) {
        if (before != null && after != null && before >= after) return
        mutate(id, TaskField.PLACEMENT, remindersChanged = false) {
            it.copy(position = FractionalIndex.between(before, after))
        }
    }

    /** Moves a task and its subtasks to the top of another list (a moved subtask becomes a task there). */
    suspend fun moveToList(id: TaskId, toList: ListId) {
        val changed = mutableSetOf<TaskId>()
        database.withTransaction {
            val row = tasks.get(id.value)?.takeUnless { it.deleted || it.listId == toList.value } ?: return@withTransaction
            val target = lists.get(toList.value)?.takeUnless { it.deleted } ?: return@withTransaction
            val now = clock.instant()
            val stamp = hlc.now()
            val record = row.toRecord()
            val position = FractionalIndex.between(null, tasks.firstPosition(target.id))
            write(
                record.copy(
                    task = record.task.copy(listId = toList, parentId = null, position = position, updatedAt = now),
                    clocks = record.clocks + (TaskField.PLACEMENT to stamp),
                ),
                row.serverVersion,
                now,
            )
            changed += id
            for (sub in tasks.subtasksOf(row.id)) {
                val subRecord = sub.toRecord()
                write(
                    subRecord.copy(task = subRecord.task.copy(listId = toList, updatedAt = now), clocks = subRecord.clocks + (TaskField.PLACEMENT to stamp)),
                    sub.serverVersion,
                    now,
                )
                changed += subRecord.task.id
            }
        }
        if (changed.isNotEmpty()) notify(TaskChange(ChangeOrigin.LOCAL, taskIds = changed, remindersChanged = false))
    }

    // ------------------------------------------------------------------------------------------
    // Deleting
    // ------------------------------------------------------------------------------------------

    /** Deletes a task and its subtasks. Returns them as they were, for [restore] (undo). */
    suspend fun delete(id: TaskId): List<Task> {
        val deleted = mutableListOf<Task>()
        database.withTransaction {
            val row = tasks.get(id.value)?.takeUnless { it.deleted } ?: return@withTransaction
            val now = clock.instant()
            val stamp = hlc.now()
            for (doomed in listOf(row) + tasks.subtasksOf(row.id)) {
                val record = doomed.toRecord()
                deleted += record.task
                tombstone(record, doomed.serverVersion, now, stamp)
            }
        }
        if (deleted.isNotEmpty()) notify(TaskChange(ChangeOrigin.LOCAL, taskIds = deleted.mapTo(mutableSetOf()) { it.id }))
        return deleted
    }

    /** Undoes [delete], putting the content back. */
    suspend fun restore(originals: List<Task>) {
        val restored = mutableSetOf<TaskId>()
        database.withTransaction {
            val now = clock.instant()
            val stamp = hlc.now()
            for (original in originals) {
                val row = tasks.get(original.id.value)?.takeIf { it.deleted } ?: continue
                val record = row.toRecord()
                write(
                    record.copy(
                        task = original.copy(updatedAt = now),
                        deleted = false,
                        clocks = record.clocks + ERASED_FIELDS.associateWith { stamp },
                    ),
                    row.serverVersion,
                    now,
                )
                restored += original.id
            }
        }
        if (restored.isNotEmpty()) notify(TaskChange(ChangeOrigin.LOCAL, taskIds = restored))
    }

    /** "Delete all completed tasks" in a list (with their subtasks). Returns how many tasks were deleted. */
    suspend fun deleteCompleted(listId: ListId): Int {
        val deleted = mutableSetOf<TaskId>()
        database.withTransaction {
            val now = clock.instant()
            val stamp = hlc.now()
            val doomed = LinkedHashMap<String, TaskEntity>()
            for (row in tasks.completedInList(listId.value)) {
                doomed[row.id] = row
                tasks.subtasksOf(row.id).forEach { doomed.putIfAbsent(it.id, it) }
            }
            for (row in doomed.values) {
                tombstone(row.toRecord(), row.serverVersion, now, stamp)
                deleted += TaskId(row.id)
            }
        }
        if (deleted.isNotEmpty()) notify(TaskChange(ChangeOrigin.LOCAL, taskIds = deleted))
        return deleted.size
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

    /** Deletes a list and every task in it (content erased). The default list can't be deleted. */
    suspend fun deleteList(id: ListId) {
        require(id != DEFAULT_LIST_ID) { "The default list can't be deleted" }
        val taskIds = mutableSetOf<TaskId>()
        database.withTransaction {
            val row = lists.get(id.value) ?: return@withTransaction
            val now = clock.instant()
            val stamp = hlc.now()
            val record = row.toRecord()
            lists.upsert(
                record.copy(
                    list = record.list.copy(title = "", updatedAt = now),
                    deleted = true,
                    clocks = record.clocks + mapOf(ListField.DELETED to stamp, ListField.TITLE to stamp),
                ).toEntity(row.serverVersion),
            )
            enqueue(row.id, RecordType.LIST, now)
            for (taskRow in tasks.allInList(id.value)) {
                tombstone(taskRow.toRecord(), taskRow.serverVersion, now, stamp)
                taskIds += TaskId(taskRow.id)
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
            write(record.copy(task = changed, clocks = record.clocks + fields.associateWith { stamp }), row.serverVersion, now)
            result = changed
            wrote = true
        }
        if (wrote) {
            notify(TaskChange(ChangeOrigin.LOCAL, taskIds = setOf(id), remindersChanged = remindersChanged))
        }
        return result
    }

    /** Marks deleted and erases the content, so nothing but an empty marker stays behind or syncs. */
    private suspend fun tombstone(record: TaskRecord, serverVersion: Long, now: Instant, stamp: Hlc) {
        val erased = record.task.copy(
            title = "",
            notes = "",
            isStarred = false,
            dueDate = null,
            reminder = null,
            repeat = null,
            updatedAt = now,
        )
        write(record.copy(task = erased, deleted = true, clocks = record.clocks + ERASED_FIELDS.associateWith { stamp }), serverVersion, now)
    }

    private suspend fun insert(task: Task, now: Instant, stamp: Hlc) =
        write(TaskRecord(task = task, deleted = false, clocks = TaskField.entries.associateWith { stamp }), serverVersion = 0, now = now)

    /** Inserts [task] unless a live task with its id exists (e.g. synced from a device that got there first). */
    private suspend fun insertIfAbsent(task: Task, now: Instant, stamp: Hlc): Boolean {
        val existing = tasks.get(task.id.value)
        if (existing != null && !existing.deleted) return false
        write(TaskRecord(task = task, deleted = false, clocks = TaskField.entries.associateWith { stamp }), existing?.serverVersion ?: 0, now)
        return true
    }

    private suspend fun write(record: TaskRecord, serverVersion: Long, now: Instant) {
        tasks.upsert(record.toEntity(serverVersion))
        enqueue(record.task.id.value, RecordType.TASK, now)
    }

    /** A copy that is new in every sense: open, unfired, created [now]. */
    private fun Task.freshCopy(newId: TaskId, now: Instant): Task = copy(
        id = newId,
        isDone = false,
        completedAt = null,
        reminder = reminder?.copy(snoozeCount = 0, lastFiredAt = null),
        createdAt = now,
        updatedAt = now,
        starredAt = null,
    )

    private fun Reminder.localDate(): LocalDate = at.atZone(zone).toLocalDate()

    private fun RepeatRule.movedFrom(from: LocalDate?, to: LocalDate): RepeatRule = if (from == null) anchoredTo(to) else moved(from, to)

    /** Returns true if the list had to be created. Must run inside a transaction. */
    private suspend fun ensureListExists(id: ListId, now: Instant, title: String = DEFAULT_LIST_TITLE): Boolean {
        val existing = lists.get(id.value)
        if (existing != null && !existing.deleted) return false
        val stamp = hlc.now()
        val position = existing?.position ?: FractionalIndex.between(null, null)
        val record = ListRecord(
            list = TaskList(id = id, title = existing?.title?.takeIf { it.isNotEmpty() } ?: title, position = position, createdAt = now, updatedAt = now),
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

        /** Field groups a deletion erases (and a restore writes back). */
        private val ERASED_FIELDS = setOf(
            TaskField.TITLE, TaskField.NOTES, TaskField.STARRED, TaskField.DUE, TaskField.REMINDER, TaskField.REPEAT, TaskField.DELETED,
        )

        /** Enough to skip ~27 years of daily occurrences when catching up. */
        private const val MAX_CATCH_UP = 10_000
    }
}
