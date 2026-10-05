package dev.suyash.dot.core.data

import dev.suyash.dot.core.data.db.DotDatabase
import dev.suyash.dot.core.data.db.toList
import dev.suyash.dot.core.data.db.toTask
import dev.suyash.dot.core.domain.model.ListId
import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.model.TaskId
import dev.suyash.dot.core.domain.model.TaskList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Read side. All flows re-emit automatically when the underlying rows change. */
@Singleton
class TaskRepository @Inject constructor(private val database: DotDatabase) {

    private val tasks get() = database.taskDao()
    private val lists get() = database.taskListDao()

    fun observeLists(): Flow<List<TaskList>> =
        lists.observeAll().map { rows -> rows.map { it.toList() } }.distinctUntilChanged()

    fun observeTasks(listId: ListId): Flow<List<Task>> =
        tasks.observeInList(listId.value).map { rows -> rows.map { it.toTask() } }.distinctUntilChanged()

    fun observeStarred(): Flow<List<Task>> =
        tasks.observeStarred().map { rows -> rows.map { it.toTask() } }.distinctUntilChanged()

    /** Every task in every list (for Today / Upcoming). */
    fun observeAll(): Flow<List<Task>> =
        tasks.observeAll().map { rows -> rows.map { it.toTask() } }.distinctUntilChanged()

    /** Tasks whose title or notes contain [query] (case-insensitive), open ones first. */
    fun search(query: String): Flow<List<Task>> {
        val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return tasks.search("%$escaped%").map { rows -> rows.map { it.toTask() } }.distinctUntilChanged()
    }

    fun observeUpcoming(limit: Int): Flow<List<Task>> =
        tasks.observeUpcoming(limit).map { rows -> rows.map { it.toTask() } }.distinctUntilChanged()

    fun observeOpenCount(): Flow<Int> = tasks.observeOpenCount().distinctUntilChanged()

    /** One-shot reads for widgets (which render snapshots, not live flows). */
    suspend fun upcomingSnapshot(limit: Int): List<Task> = observeUpcoming(limit).first()

    suspend fun openCountSnapshot(): Int = tasks.observeOpenCount().first()

    fun observeTask(id: TaskId): Flow<Task?> = tasks.observe(id.value).map { it?.takeUnless { row -> row.deleted }?.toTask() }

    suspend fun getTask(id: TaskId): Task? = tasks.get(id.value)?.takeUnless { it.deleted }?.toTask()

    suspend fun getTasks(ids: Collection<TaskId>): List<Task> =
        tasks.getAll(ids.map { it.value }).filterNot { it.deleted }.map { it.toTask() }

    /** The earliest reminder that still has to fire. */
    suspend fun nextPendingReminder(): Task? = tasks.nextPendingReminder()?.toTask()

    /** Reminders whose time is at or before [until] and haven't fired yet. */
    suspend fun dueReminders(until: Instant): List<Task> = tasks.dueReminders(until.toEpochMilli()).map { it.toTask() }

    suspend fun pendingReminders(): List<Task> = tasks.pendingReminders().map { it.toTask() }

    /** Epoch-millis of the next [limit] pending reminders, soonest first. */
    suspend fun pendingReminderTimes(limit: Int): List<Long> = tasks.pendingReminderTimes(limit)
}
