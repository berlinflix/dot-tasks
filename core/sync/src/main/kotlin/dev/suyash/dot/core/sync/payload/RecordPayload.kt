@file:OptIn(ExperimentalSerializationApi::class)

package dev.suyash.dot.core.sync.payload

import dev.suyash.dot.core.domain.model.ListId
import dev.suyash.dot.core.domain.model.Reminder
import dev.suyash.dot.core.domain.model.RingMode
import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.model.TaskId
import dev.suyash.dot.core.domain.model.TaskList
import dev.suyash.dot.core.domain.model.TaskSource
import dev.suyash.dot.core.domain.repeat.RepeatRule
import dev.suyash.dot.core.domain.sync.Hlc
import dev.suyash.dot.core.domain.sync.ListField
import dev.suyash.dot.core.domain.sync.ListRecord
import dev.suyash.dot.core.domain.sync.TaskField
import dev.suyash.dot.core.domain.sync.TaskRecord
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The plaintext inside every encrypted record (protobuf; field numbers are forever — only add new
 * ones). Everything about a task, including whether it's deleted and its per-field clocks, lives in
 * here, so the server learns nothing beyond "some record changed".
 */
@Serializable
internal class RecordPayload(
    @ProtoNumber(1) val kind: Int,
    @ProtoNumber(2) val task: TaskPayload? = null,
    @ProtoNumber(3) val list: ListPayload? = null,
    @ProtoNumber(4) val deleted: Boolean = false,
    @ProtoNumber(5) val clocks: Map<String, String> = emptyMap(),
)

@Serializable
internal class TaskPayload(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val listId: String,
    @ProtoNumber(3) val parentId: String? = null,
    @ProtoNumber(4) val title: String,
    @ProtoNumber(5) val notes: String = "",
    @ProtoNumber(6) val done: Boolean = false,
    @ProtoNumber(7) val completedAt: Long? = null,
    @ProtoNumber(8) val starred: Boolean = false,
    @ProtoNumber(9) val dueDate: String? = null,
    @ProtoNumber(10) val remindAt: Long? = null,
    @ProtoNumber(11) val remindZone: String? = null,
    @ProtoNumber(12) val ringMode: String? = null,
    @ProtoNumber(13) val snoozeCount: Int = 0,
    @ProtoNumber(14) val lastFiredAt: Long? = null,
    @ProtoNumber(15) val position: String,
    @ProtoNumber(16) val source: String = "MANUAL",
    @ProtoNumber(17) val createdAt: Long,
    @ProtoNumber(18) val updatedAt: Long,
    /** RFC 5545 RRULE subset (see RepeatRule). */
    @ProtoNumber(19) val repeat: String? = null,
)

@Serializable
internal class ListPayload(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val title: String,
    @ProtoNumber(3) val position: String,
    @ProtoNumber(4) val createdAt: Long,
    @ProtoNumber(5) val updatedAt: Long,
)

sealed interface DecodedRecord {
    data class OfTask(val record: TaskRecord) : DecodedRecord
    data class OfList(val record: ListRecord) : DecodedRecord
    data object Unknown : DecodedRecord
}

/** Converts records to/from payload bytes, and derives opaque server-side record ids. */
object RecordCodec {
    private const val KIND_TASK = 1
    private const val KIND_LIST = 2

    /** Opaque id: the server can't tell tasks from lists or link them to app ids. */
    fun remoteId(kind: String, localId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$kind:$localId".toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    fun taskRemoteId(id: TaskId) = remoteId("task", id.value)
    fun listRemoteId(id: ListId) = remoteId("list", id.value)

    fun encode(record: TaskRecord): ByteArray {
        val t = record.task
        return ProtoBuf.encodeToByteArray(
            RecordPayload(
                kind = KIND_TASK,
                task = TaskPayload(
                    id = t.id.value,
                    listId = t.listId.value,
                    parentId = t.parentId?.value,
                    title = t.title,
                    notes = t.notes,
                    done = t.isDone,
                    completedAt = t.completedAt?.toEpochMilli(),
                    starred = t.isStarred,
                    dueDate = t.dueDate?.toString(),
                    remindAt = t.reminder?.at?.toEpochMilli(),
                    remindZone = t.reminder?.zone?.id,
                    ringMode = t.reminder?.mode?.name,
                    snoozeCount = t.reminder?.snoozeCount ?: 0,
                    lastFiredAt = t.reminder?.lastFiredAt?.toEpochMilli(),
                    position = t.position,
                    source = t.source.name,
                    createdAt = t.createdAt.toEpochMilli(),
                    updatedAt = t.updatedAt.toEpochMilli(),
                    repeat = t.repeat?.toRRule(),
                ),
                deleted = record.deleted,
                clocks = record.clocks.entries.associate { (field, hlc) -> field.name to hlc.encode() },
            ),
        )
    }

    fun encode(record: ListRecord): ByteArray {
        val l = record.list
        return ProtoBuf.encodeToByteArray(
            RecordPayload(
                kind = KIND_LIST,
                list = ListPayload(
                    id = l.id.value,
                    title = l.title,
                    position = l.position,
                    createdAt = l.createdAt.toEpochMilli(),
                    updatedAt = l.updatedAt.toEpochMilli(),
                ),
                deleted = record.deleted,
                clocks = record.clocks.entries.associate { (field, hlc) -> field.name to hlc.encode() },
            ),
        )
    }

    fun decode(bytes: ByteArray): DecodedRecord {
        val payload = ProtoBuf.decodeFromByteArray<RecordPayload>(bytes)
        return when (payload.kind) {
            KIND_TASK -> payload.task?.let { DecodedRecord.OfTask(it.toRecord(payload)) } ?: DecodedRecord.Unknown
            KIND_LIST -> payload.list?.let { DecodedRecord.OfList(it.toRecord(payload)) } ?: DecodedRecord.Unknown
            else -> DecodedRecord.Unknown // written by a newer app version; ignore safely
        }
    }

    private fun TaskPayload.toRecord(payload: RecordPayload): TaskRecord = TaskRecord(
        task = Task(
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
            repeat = RepeatRule.parse(repeat),
        ),
        deleted = payload.deleted,
        clocks = payload.clocks.mapNotNull { (name, value) ->
            val field = TaskField.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            runCatching { field to Hlc.decode(value) }.getOrNull()
        }.toMap(),
    )

    private fun ListPayload.toRecord(payload: RecordPayload): ListRecord = ListRecord(
        list = TaskList(
            id = ListId(id),
            title = title,
            position = position,
            createdAt = Instant.ofEpochMilli(createdAt),
            updatedAt = Instant.ofEpochMilli(updatedAt),
        ),
        deleted = payload.deleted,
        clocks = payload.clocks.mapNotNull { (name, value) ->
            val field = ListField.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            runCatching { field to Hlc.decode(value) }.getOrNull()
        }.toMap(),
    )
}
