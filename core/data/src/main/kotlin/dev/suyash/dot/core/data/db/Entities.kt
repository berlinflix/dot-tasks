package dev.suyash.dot.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Rows mirror the synced records 1:1. No foreign keys: sync may deliver a task before its list,
 * so referential integrity is enforced in [dev.suyash.dot.core.data.TaskCommands] instead.
 */
@Entity(tableName = "task_lists")
data class TaskListEntity(
    @PrimaryKey val id: String,
    val title: String,
    val position: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val deleted: Boolean,
    /** Per-field HLCs, see [dev.suyash.dot.core.domain.sync.ClockCodec]. */
    val clocks: String,
    /** Last version acknowledged by the server (0 = never synced). */
    @ColumnInfo(name = "server_version") val serverVersion: Long,
)

@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["list_id", "deleted"]),
        Index(value = ["deleted", "done", "remind_at"]),
    ],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "list_id") val listId: String,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    val title: String,
    val notes: String,
    val done: Boolean,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    val starred: Boolean,
    /** ISO-8601 local date. */
    @ColumnInfo(name = "due_date") val dueDate: String?,
    @ColumnInfo(name = "remind_at") val remindAt: Long?,
    @ColumnInfo(name = "remind_zone") val remindZone: String?,
    @ColumnInfo(name = "ring_mode") val ringMode: String?,
    @ColumnInfo(name = "snooze_count") val snoozeCount: Int,
    @ColumnInfo(name = "last_fired_at") val lastFiredAt: Long?,
    /** RFC 5545 RRULE, reserved for recurring tasks. */
    val recurrence: String?,
    val position: String,
    val source: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val deleted: Boolean,
    val clocks: String,
    @ColumnInfo(name = "server_version") val serverVersion: Long,
)

enum class RecordType { TASK, LIST }

/** Records changed locally and not yet pushed. Written in the same transaction as the change. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey @ColumnInfo(name = "record_id") val recordId: String,
    @ColumnInfo(name = "record_type") val recordType: RecordType,
    @ColumnInfo(name = "enqueued_at") val enqueuedAt: Long,
    val attempts: Int = 0,
)

@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)
