package dev.suyash.dot.core.data.db

import dev.suyash.dot.core.domain.model.ListId
import dev.suyash.dot.core.domain.model.Reminder
import dev.suyash.dot.core.domain.model.RingMode
import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.model.TaskId
import dev.suyash.dot.core.domain.model.TaskList
import dev.suyash.dot.core.domain.model.TaskSource
import dev.suyash.dot.core.domain.repeat.RepeatRule
import dev.suyash.dot.core.domain.sync.ClockCodec
import dev.suyash.dot.core.domain.sync.Hlc
import dev.suyash.dot.core.domain.sync.ListField
import dev.suyash.dot.core.domain.sync.ListRecord
import dev.suyash.dot.core.domain.sync.TaskField
import dev.suyash.dot.core.domain.sync.TaskRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun TaskEntity.toTask(): Task = toTask(ClockCodec.decode<TaskField>(clocks))

private fun TaskEntity.toTask(clocks: Map<TaskField, Hlc>): Task = Task(
    id = TaskId(id),
    listId = ListId(listId),
    title = title,
    notes = notes,
    parentId = parentId?.let(::TaskId),
    isDone = done,
    completedAt = completedAt?.let(Instant::ofEpochMilli),
    isStarred = starred,
    dueDate = dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    reminder = remindAt?.let { at ->
        Reminder(
            at = Instant.ofEpochMilli(at),
            zone = remindZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault(),
            mode = ringMode?.let { runCatching { RingMode.valueOf(it) }.getOrNull() } ?: RingMode.RING,
            snoozeCount = snoozeCount,
            lastFiredAt = lastFiredAt?.let(Instant::ofEpochMilli),
        )
    },
    position = position,
    source = runCatching { TaskSource.valueOf(source) }.getOrDefault(TaskSource.MANUAL),
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    repeat = RepeatRule.parse(recurrence),
    starredAt = clocks[TaskField.STARRED]?.takeIf { starred }?.let { Instant.ofEpochMilli(it.wallMillis) },
)

fun TaskEntity.toRecord(): TaskRecord {
    val decoded = ClockCodec.decode<TaskField>(clocks)
    return TaskRecord(task = toTask(decoded), deleted = deleted, clocks = decoded)
}

fun TaskRecord.toEntity(serverVersion: Long): TaskEntity = TaskEntity(
    id = task.id.value,
    listId = task.listId.value,
    parentId = task.parentId?.value,
    title = task.title,
    notes = task.notes,
    done = task.isDone,
    completedAt = task.completedAt?.toEpochMilli(),
    starred = task.isStarred,
    dueDate = task.dueDate?.toString(),
    remindAt = task.reminder?.at?.toEpochMilli(),
    remindZone = task.reminder?.zone?.id,
    ringMode = task.reminder?.mode?.name,
    snoozeCount = task.reminder?.snoozeCount ?: 0,
    lastFiredAt = task.reminder?.lastFiredAt?.toEpochMilli(),
    recurrence = task.repeat?.toRRule(),
    position = task.position,
    source = task.source.name,
    createdAt = task.createdAt.toEpochMilli(),
    updatedAt = task.updatedAt.toEpochMilli(),
    deleted = deleted,
    clocks = ClockCodec.encode(clocks),
    serverVersion = serverVersion,
)

fun TaskListEntity.toList(): TaskList = TaskList(
    id = ListId(id),
    title = title,
    position = position,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

fun TaskListEntity.toRecord(): ListRecord =
    ListRecord(list = toList(), deleted = deleted, clocks = ClockCodec.decode<ListField>(clocks))

fun ListRecord.toEntity(serverVersion: Long): TaskListEntity = TaskListEntity(
    id = list.id.value,
    title = list.title,
    position = list.position,
    createdAt = list.createdAt.toEpochMilli(),
    updatedAt = list.updatedAt.toEpochMilli(),
    deleted = deleted,
    clocks = ClockCodec.encode(clocks),
    serverVersion = serverVersion,
)
