package dev.suyash.dot.core.data.export

import dev.suyash.dot.core.data.db.DotDatabase
import dev.suyash.dot.core.data.db.toTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes every list and task as JSON, so your data is never locked in. The file is plain text (not
 * encrypted) and goes only where the user saves it.
 */
@Singleton
class TaskExporter @Inject constructor(
    private val database: DotDatabase,
    private val clock: Clock,
) {
    /** Returns how many tasks were written. */
    suspend fun exportJson(out: OutputStream): Int = withContext(Dispatchers.IO) {
        val lists = database.taskListDao().allIncludingDeleted().filterNot { it.deleted }.sortedBy { it.position }
        val tasks = database.taskDao().allIncludingDeleted().filterNot { it.deleted }.map { it.toTask() }
        val json = JSONObject()
            .put("format", "dot-tasks")
            .put("version", 1)
            .put("exportedAt", clock.instant().toString())
            .put("lists", JSONArray().apply { lists.forEach { put(JSONObject().put("id", it.id).put("title", it.title)) } })
            .put(
                "tasks",
                JSONArray().apply {
                    tasks.sortedWith(compareBy({ it.listId.value }, { it.position })).forEach { task ->
                        put(
                            JSONObject()
                                .put("id", task.id.value)
                                .put("listId", task.listId.value)
                                .putOpt("parentId", task.parentId?.value)
                                .put("title", task.title)
                                .put("notes", task.notes)
                                .put("done", task.isDone)
                                .putOpt("completedAt", task.completedAt?.toString())
                                .put("starred", task.isStarred)
                                .putOpt("due", task.dueDate?.toString())
                                .putOpt("remindAt", task.reminder?.at?.atZone(task.reminder?.zone)?.toOffsetDateTime()?.toString())
                                .putOpt("ringMode", task.reminder?.mode?.name)
                                .putOpt("repeat", task.repeat?.toRRule())
                                .put("createdAt", task.createdAt.toString())
                                .put("updatedAt", task.updatedAt.toString()),
                        )
                    }
                },
            )
        out.bufferedWriter(Charsets.UTF_8).use { it.write(json.toString(2)) }
        tasks.size
    }
}
