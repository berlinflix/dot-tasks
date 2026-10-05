package dev.suyash.dot.core.sync

import com.google.common.truth.Truth.assertThat
import dev.suyash.dot.core.domain.model.ListId
import dev.suyash.dot.core.domain.model.Reminder
import dev.suyash.dot.core.domain.model.RingMode
import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.model.TaskId
import dev.suyash.dot.core.domain.model.TaskList
import dev.suyash.dot.core.domain.model.TaskSource
import dev.suyash.dot.core.domain.sync.Hlc
import dev.suyash.dot.core.domain.sync.ListField
import dev.suyash.dot.core.domain.sync.ListRecord
import dev.suyash.dot.core.domain.sync.TaskField
import dev.suyash.dot.core.domain.sync.TaskRecord
import dev.suyash.dot.core.sync.payload.DecodedRecord
import dev.suyash.dot.core.sync.payload.RecordCodec
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class RecordCodecTest {

    private val now = Instant.parse("2026-10-02T10:00:00Z")

    @Test
    fun `task survives a round trip, including reminder and clocks`() {
        val record = TaskRecord(
            task = Task(
                id = TaskId("1c0d3a3e-0000-4000-8000-000000000001"),
                listId = ListId("default"),
                title = "Buy milk",
                notes = "2 litres",
                isStarred = true,
                dueDate = LocalDate.parse("2026-10-03"),
                reminder = Reminder(at = Instant.parse("2026-10-03T02:30:00Z"), zone = ZoneId.of("Asia/Kolkata"), mode = RingMode.RING, snoozeCount = 2),
                position = "V",
                source = TaskSource.VOICE,
                createdAt = now,
                updatedAt = now,
            ),
            deleted = false,
            clocks = mapOf(TaskField.TITLE to Hlc(1, 2, "a"), TaskField.REMINDER to Hlc(3, 0, "b")),
        )
        val decoded = RecordCodec.decode(RecordCodec.encode(record))
        assertThat(decoded).isEqualTo(DecodedRecord.OfTask(record))
    }

    @Test
    fun `list survives a round trip`() {
        val record = ListRecord(
            list = TaskList(id = ListId("default"), title = "My Tasks", position = "V", createdAt = now, updatedAt = now),
            deleted = true,
            clocks = mapOf(ListField.DELETED to Hlc(9, 1, "c")),
        )
        assertThat(RecordCodec.decode(RecordCodec.encode(record))).isEqualTo(DecodedRecord.OfList(record))
    }

    @Test
    fun `remote ids are opaque, stable and distinct per kind`() {
        val taskId = RecordCodec.taskRemoteId(TaskId("default"))
        val listId = RecordCodec.listRemoteId(ListId("default"))
        assertThat(taskId).hasLength(32)
        assertThat(taskId).isNotEqualTo(listId)
        assertThat(RecordCodec.listRemoteId(ListId("default"))).isEqualTo(listId)
        assertThat(listId).doesNotContain("default")
    }
}
