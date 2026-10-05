package dev.suyash.dot.core.domain.sync

import com.google.common.truth.Truth.assertThat
import dev.suyash.dot.core.domain.model.ListId
import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.model.TaskId
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class LwwMergeTest {

    private val id = TaskId("t1")
    private val created = Instant.parse("2026-10-02T00:00:00Z")
    private fun hlc(ms: Long, node: String = "a") = Hlc(ms, 0, node)

    private fun base(title: String = "Buy milk") = TaskRecord(
        task = Task(
            id = id,
            listId = ListId("l1"),
            title = title,
            position = "V",
            createdAt = created,
            updatedAt = created,
        ),
        deleted = false,
        clocks = TaskField.entries.associateWith { hlc(1) },
    )

    @Test
    fun `concurrent edits to different fields are both kept`() {
        val start = base()
        val renamed = start.copy(
            task = start.task.copy(title = "Buy oat milk"),
            clocks = start.clocks + (TaskField.TITLE to hlc(10, "phone")),
        )
        val completed = start.copy(
            task = start.task.copy(isDone = true, completedAt = created),
            clocks = start.clocks + (TaskField.STATUS to hlc(11, "tablet")),
        )
        val merged = LwwMerge.merge(renamed, completed)
        assertThat(merged.task.title).isEqualTo("Buy oat milk")
        assertThat(merged.task.isDone).isTrue()
        assertThat(LwwMerge.merge(completed, renamed)).isEqualTo(merged)
    }

    @Test
    fun `the later edit to the same field wins`() {
        val start = base()
        val older = start.copy(task = start.task.copy(title = "A"), clocks = start.clocks + (TaskField.TITLE to hlc(5)))
        val newer = start.copy(task = start.task.copy(title = "B"), clocks = start.clocks + (TaskField.TITLE to hlc(6)))
        assertThat(LwwMerge.merge(older, newer).task.title).isEqualTo("B")
        assertThat(LwwMerge.merge(newer, older).task.title).isEqualTo("B")
    }

    @Test
    fun `merge is commutative, associative and idempotent`() {
        val random = Random(7)
        val variants = (1..30).map { i ->
            val start = base()
            val field = TaskField.entries[random.nextInt(TaskField.entries.size)]
            // Real HLCs are unique per edit (one node never reuses a timestamp), so give each variant its own.
            val clock = hlc(10L + i, node = "n${i % 3}")
            val task = when (field) {
                TaskField.TITLE -> start.task.copy(title = "title $i")
                TaskField.NOTES -> start.task.copy(notes = "notes $i")
                TaskField.STATUS -> start.task.copy(isDone = i % 2 == 0)
                TaskField.STARRED -> start.task.copy(isStarred = i % 2 == 0)
                TaskField.DUE -> start.task.copy(dueDate = java.time.LocalDate.of(2026, 10, 1 + i % 28))
                TaskField.REMINDER -> start.task
                TaskField.PLACEMENT -> start.task.copy(position = "V$i".replace('0', '1'))
                TaskField.DELETED -> start.task
                TaskField.REPEAT -> start.task.copy(
                    repeat = dev.suyash.dot.core.domain.repeat.RepeatRule(
                        dev.suyash.dot.core.domain.repeat.Frequency.DAILY,
                        interval = 1 + i % 5,
                    ),
                )
            }
            start.copy(
                task = task,
                deleted = if (field == TaskField.DELETED) i % 2 == 0 else start.deleted,
                clocks = start.clocks + (field to clock),
            )
        }
        repeat(200) {
            val a = variants.random(random)
            val b = variants.random(random)
            val c = variants.random(random)
            assertThat(LwwMerge.merge(a, b)).isEqualTo(LwwMerge.merge(b, a))
            assertThat(LwwMerge.merge(LwwMerge.merge(a, b), c)).isEqualTo(LwwMerge.merge(a, LwwMerge.merge(b, c)))
            assertThat(LwwMerge.merge(a, a)).isEqualTo(a)
        }
    }
}
