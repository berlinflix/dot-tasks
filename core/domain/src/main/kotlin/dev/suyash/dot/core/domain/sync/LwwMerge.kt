package dev.suyash.dot.core.domain.sync

import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.model.TaskList

/**
 * Field-group "last writer wins" merge (an LWW-map CRDT). Merging is commutative, associative and
 * idempotent, so every device converges to the same state no matter the order updates arrive in.
 */
object LwwMerge {

    fun merge(a: TaskRecord, b: TaskRecord): TaskRecord {
        require(a.task.id == b.task.id) { "Cannot merge different tasks" }
        fun winner(field: TaskField): TaskRecord = if (b.clock(field) > a.clock(field)) b else a

        val title = winner(TaskField.TITLE)
        val notes = winner(TaskField.NOTES)
        val status = winner(TaskField.STATUS)
        val starred = winner(TaskField.STARRED)
        val due = winner(TaskField.DUE)
        val reminder = winner(TaskField.REMINDER)
        val placement = winner(TaskField.PLACEMENT)
        val deleted = winner(TaskField.DELETED)

        val merged: Task = a.task.copy(
            title = title.task.title,
            notes = notes.task.notes,
            isDone = status.task.isDone,
            completedAt = status.task.completedAt,
            isStarred = starred.task.isStarred,
            dueDate = due.task.dueDate,
            reminder = reminder.task.reminder,
            listId = placement.task.listId,
            parentId = placement.task.parentId,
            position = placement.task.position,
            source = minOf(a.task.source, b.task.source),
            createdAt = minOf(a.task.createdAt, b.task.createdAt),
            updatedAt = maxOf(a.task.updatedAt, b.task.updatedAt),
        )
        val clocks = TaskField.entries.associateWith { maxOf(a.clock(it), b.clock(it)) }
            .filterValues { it != Hlc.ZERO }
        return TaskRecord(task = merged, deleted = deleted.deleted, clocks = clocks)
    }

    fun merge(a: ListRecord, b: ListRecord): ListRecord {
        require(a.list.id == b.list.id) { "Cannot merge different lists" }
        fun winner(field: ListField): ListRecord = if (b.clock(field) > a.clock(field)) b else a

        val merged: TaskList = a.list.copy(
            title = winner(ListField.TITLE).list.title,
            position = winner(ListField.POSITION).list.position,
            createdAt = minOf(a.list.createdAt, b.list.createdAt),
            updatedAt = maxOf(a.list.updatedAt, b.list.updatedAt),
        )
        val clocks = ListField.entries.associateWith { maxOf(a.clock(it), b.clock(it)) }
            .filterValues { it != Hlc.ZERO }
        return ListRecord(list = merged, deleted = winner(ListField.DELETED).deleted, clocks = clocks)
    }
}
