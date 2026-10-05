package dev.suyash.dot.core.domain.view

import com.google.common.truth.Truth.assertThat
import dev.suyash.dot.core.domain.model.ListId
import dev.suyash.dot.core.domain.model.Reminder
import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.model.TaskId
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TaskViewsTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.parse("2026-10-05")
    private val label: (LocalDate) -> String = { it.toString() }
    private val t0 = Instant.parse("2026-10-01T00:00:00Z")

    private fun task(
        id: String,
        position: String = id,
        parent: String? = null,
        done: Boolean = false,
        due: String? = null,
        at: String? = null,
        starredAt: String? = null,
    ) = Task(
        id = TaskId(id),
        listId = ListId("l"),
        title = id,
        parentId = parent?.let(::TaskId),
        isDone = done,
        completedAt = if (done) t0 else null,
        isStarred = starredAt != null,
        starredAt = starredAt?.let(Instant::parse),
        dueDate = due?.let(LocalDate::parse),
        reminder = at?.let { Reminder(LocalDateTime.parse(it).atZone(zone).toInstant(), zone) },
        position = position,
        createdAt = t0,
        updatedAt = t0,
    )

    private fun List<TaskSection>.ids() = map { s -> s.title to s.groups.map { it.task.id.value } }

    @Test
    fun `my order nests open subtasks under their parent and counts progress`() {
        val tasks = listOf(
            task("b", position = "V2"),
            task("a", position = "V1"),
            task("a1", position = "V1", parent = "a"),
            task("a2", position = "V2", parent = "a", done = true),
            task("orphan", position = "V3", parent = "gone"),
            task("c", position = "V0", done = true),
        )
        val sections = TaskViews.list(tasks, SortOrder.MY_ORDER, today, zone, label)
        assertThat(sections.ids()).containsExactly(null to listOf("a", "b", "orphan"))
        val a = sections.single().groups.first()
        assertThat(a.subtasks.map { it.id.value }).containsExactly("a1")
        assertThat(a.doneSubtasks to a.totalSubtasks).isEqualTo(1 to 2)
    }

    @Test
    fun `date order groups by day with overdue first and undated last`() {
        val tasks = listOf(
            task("undated"),
            task("late", due = "2026-10-01"),
            task("tomorrow-9", at = "2026-10-06T09:00"),
            task("tomorrow-allday", due = "2026-10-06"),
            task("today-8", at = "2026-10-05T08:00"),
        )
        assertThat(TaskViews.list(tasks, SortOrder.DATE, today, zone, label).ids()).containsExactly(
            "Overdue" to listOf("late"),
            "2026-10-05" to listOf("today-8"),
            "2026-10-06" to listOf("tomorrow-9", "tomorrow-allday"),
            "No date" to listOf("undated"),
        ).inOrder()
    }

    @Test
    fun `starred recently puts the latest star first`() {
        val tasks = listOf(
            task("old-star", starredAt = "2026-10-02T00:00:00Z"),
            task("plain"),
            task("new-star", starredAt = "2026-10-04T00:00:00Z"),
        )
        assertThat(TaskViews.list(tasks, SortOrder.STARRED_RECENTLY, today, zone, label).ids()).containsExactly(
            "Starred" to listOf("new-star", "old-star"),
            "Others" to listOf("plain"),
        ).inOrder()
    }

    @Test
    fun `today shows overdue and today across lists, upcoming shows today onwards`() {
        val tasks = listOf(
            task("late", due = "2026-10-04"),
            task("now", at = "2026-10-05T18:00"),
            task("later", due = "2026-10-09"),
            task("done-today", due = "2026-10-05", done = true),
            task("undated"),
        )
        assertThat(TaskViews.today(tasks, today, zone).ids()).containsExactly(
            "Overdue" to listOf("late"),
            "Today" to listOf("now"),
        ).inOrder()
        assertThat(TaskViews.upcoming(tasks, today, zone, label).ids()).containsExactly(
            "2026-10-05" to listOf("now"),
            "2026-10-09" to listOf("later"),
        ).inOrder()
    }

    @Test
    fun `completed is newest first and includes subtasks`() {
        val first = task("first", done = true).copy(completedAt = t0)
        val second = task("second", parent = "x", done = true).copy(completedAt = t0.plusSeconds(60))
        assertThat(TaskViews.completed(listOf(first, task("open"), second)).map { it.id.value })
            .containsExactly("second", "first").inOrder()
    }
}
