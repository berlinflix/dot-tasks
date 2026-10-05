package dev.suyash.dot.core.domain.view

import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.model.TaskId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How a list is ordered (per list, like Google Tasks). */
enum class SortOrder { MY_ORDER, DATE, STARRED_RECENTLY }

/** A task with its open subtasks, plus checklist progress ([doneSubtasks] of [totalSubtasks]). */
data class TaskGroup(
    val task: Task,
    val subtasks: List<Task> = emptyList(),
    val doneSubtasks: Int = 0,
    val totalSubtasks: Int = 0,
)

/** A run of groups under an optional header. */
data class TaskSection(
    val key: String,
    val title: String?,
    val groups: List<TaskGroup>,
    val overdue: Boolean = false,
)

/**
 * Turns flat task lists into what the screens show. Pure, so it's unit-tested; [label] formats a
 * day header (e.g. "Tomorrow", "Sat 10 Oct").
 */
object TaskViews {

    /** The day a task is on: its reminder's day, else its due date. */
    fun dayOf(task: Task, zone: ZoneId): LocalDate? = task.reminder?.at?.atZone(zone)?.toLocalDate() ?: task.dueDate

    /** One list's open tasks, subtasks nested under their parents, in the list's [sort] order. */
    fun list(tasks: List<Task>, sort: SortOrder, today: LocalDate, zone: ZoneId, label: (LocalDate) -> String): List<TaskSection> {
        val groups = groups(tasks)
        return when (sort) {
            SortOrder.MY_ORDER -> listOf(TaskSection("all", null, groups.sortedWith(byPosition))).filter { it.groups.isNotEmpty() }
            SortOrder.DATE -> byDay(groups, today, zone, label, includeUndated = true)
            SortOrder.STARRED_RECENTLY -> {
                val (starred, others) = groups.partition { it.task.isStarred }
                listOfNotNull(
                    TaskSection("starred", "Starred", starred.sortedByDescending { it.task.starredAt ?: Instant.EPOCH })
                        .takeIf { it.groups.isNotEmpty() },
                    TaskSection("others", if (starred.isEmpty()) null else "Others", others.sortedWith(byPosition))
                        .takeIf { it.groups.isNotEmpty() },
                )
            }
        }
    }

    /** Across lists: everything overdue or due today. */
    fun today(tasks: List<Task>, today: LocalDate, zone: ZoneId): List<TaskSection> {
        val due = tasks.filter { !it.isDone && dayOf(it, zone)?.let { day -> !day.isAfter(today) } == true }
            .map { TaskGroup(it) }
        val (overdue, todays) = due.partition { dayOf(it.task, zone)!!.isBefore(today) }
        return listOfNotNull(
            TaskSection("overdue", "Overdue", overdue.sortedWith(byTime(zone)), overdue = true).takeIf { overdue.isNotEmpty() },
            TaskSection("today", if (overdue.isEmpty()) null else "Today", todays.sortedWith(byTime(zone))).takeIf { todays.isNotEmpty() },
        )
    }

    /** Across lists: everything scheduled from today on, by day. */
    fun upcoming(tasks: List<Task>, today: LocalDate, zone: ZoneId, label: (LocalDate) -> String): List<TaskSection> {
        val scheduled = tasks.filter { !it.isDone && dayOf(it, zone)?.let { day -> !day.isBefore(today) } == true }
            .map { TaskGroup(it) }
        return byDay(scheduled, today, zone, label, includeUndated = false)
    }

    /** Across lists: starred open tasks, most recently starred first. */
    fun starred(tasks: List<Task>): List<TaskSection> {
        val starred = tasks.filter { !it.isDone && it.isStarred }
            .sortedByDescending { it.starredAt ?: Instant.EPOCH }
            .map { TaskGroup(it) }
        return listOf(TaskSection("starred", null, starred)).filter { it.groups.isNotEmpty() }
    }

    /** Completed tasks (any level), most recently completed first. */
    fun completed(tasks: List<Task>): List<Task> =
        tasks.filter { it.isDone }.sortedByDescending { it.completedAt ?: Instant.EPOCH }

    /** Open top-level tasks, each with its open subtasks. A subtask whose parent isn't here stands alone. */
    fun groups(tasks: List<Task>): List<TaskGroup> {
        val ids: Set<TaskId> = tasks.mapTo(HashSet()) { it.id }
        val children = tasks.filter { it.parentId != null && it.parentId in ids }.groupBy { it.parentId }
        return tasks
            .filter { !it.isDone && (it.parentId == null || it.parentId !in ids) }
            .map { parent ->
                val subtasks = children[parent.id].orEmpty()
                TaskGroup(
                    task = parent,
                    subtasks = subtasks.filterNot { it.isDone }.sortedWith(compareBy<Task>({ it.position }, { it.id.value })),
                    doneSubtasks = subtasks.count { it.isDone },
                    totalSubtasks = subtasks.size,
                )
            }
    }

    private fun byDay(
        groups: List<TaskGroup>,
        today: LocalDate,
        zone: ZoneId,
        label: (LocalDate) -> String,
        includeUndated: Boolean,
    ): List<TaskSection> {
        val (dated, undated) = groups.partition { dayOf(it.task, zone) != null }
        val (overdue, onTime) = dated.partition { dayOf(it.task, zone)!!.isBefore(today) }
        val sections = mutableListOf<TaskSection>()
        if (overdue.isNotEmpty()) sections += TaskSection("overdue", "Overdue", overdue.sortedWith(byTime(zone)), overdue = true)
        onTime.groupBy { dayOf(it.task, zone)!! }.toSortedMap().forEach { (day, dayGroups) ->
            sections += TaskSection("day-$day", label(day), dayGroups.sortedWith(byTime(zone)))
        }
        if (includeUndated && undated.isNotEmpty()) sections += TaskSection("undated", "No date", undated.sortedWith(byPosition))
        return sections
    }

    private val byPosition: Comparator<TaskGroup> = compareBy({ it.task.position }, { it.task.id.value })

    /** Earliest first: tasks with a time by that time, all-day ones after, then by list order. */
    private fun byTime(zone: ZoneId): Comparator<TaskGroup> = compareBy(
        { dayOf(it.task, zone) },
        { it.task.reminder?.at ?: Instant.MAX },
        { it.task.position },
        { it.task.id.value },
    )
}
