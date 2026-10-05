package dev.suyash.dot.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** "Pending" = open, not deleted, has a reminder that hasn't fired for its current time yet. */
private const val PENDING_REMINDER =
    "deleted = 0 AND done = 0 AND remind_at IS NOT NULL AND (last_fired_at IS NULL OR last_fired_at < remind_at)"

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE list_id = :listId AND deleted = 0 ORDER BY done ASC, position ASC, id ASC")
    fun observeInList(listId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE starred = 1 AND deleted = 0 ORDER BY done ASC, position ASC, id ASC")
    fun observeStarred(): Flow<List<TaskEntity>>

    /** Open tasks that have a reminder or due date, soonest first — for widgets and "Upcoming". */
    @Query(
        """SELECT * FROM tasks WHERE deleted = 0 AND done = 0 AND (remind_at IS NOT NULL OR due_date IS NOT NULL)
           ORDER BY CASE WHEN remind_at IS NULL THEN 1 ELSE 0 END, remind_at ASC, due_date ASC, position ASC
           LIMIT :limit""",
    )
    fun observeUpcoming(limit: Int): Flow<List<TaskEntity>>

    @Query("SELECT COUNT(*) FROM tasks WHERE deleted = 0 AND done = 0")
    fun observeOpenCount(): Flow<Int>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id IN (:ids)")
    suspend fun getAll(ids: Collection<String>): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE $PENDING_REMINDER ORDER BY remind_at ASC LIMIT 1")
    suspend fun nextPendingReminder(): TaskEntity?

    @Query("SELECT * FROM tasks WHERE $PENDING_REMINDER AND remind_at <= :until ORDER BY remind_at ASC")
    suspend fun dueReminders(until: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE $PENDING_REMINDER")
    suspend fun pendingReminders(): List<TaskEntity>

    @Query("SELECT remind_at FROM tasks WHERE $PENDING_REMINDER ORDER BY remind_at ASC LIMIT :limit")
    suspend fun pendingReminderTimes(limit: Int): List<Long>

    @Query("SELECT position FROM tasks WHERE list_id = :listId AND deleted = 0 AND parent_id IS NULL ORDER BY position ASC, id ASC LIMIT 1")
    suspend fun firstPosition(listId: String): String?

    @Query("SELECT * FROM tasks WHERE list_id = :listId AND deleted = 0")
    suspend fun allInList(listId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks")
    suspend fun allIncludingDeleted(): List<TaskEntity>

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Upsert
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Query("UPDATE tasks SET server_version = :version WHERE id = :id")
    suspend fun setServerVersion(id: String, version: Long)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()
}

@Dao
interface TaskListDao {
    @Query("SELECT * FROM task_lists WHERE deleted = 0 ORDER BY position ASC, id ASC")
    fun observeAll(): Flow<List<TaskListEntity>>

    @Query("SELECT * FROM task_lists WHERE id = :id")
    suspend fun get(id: String): TaskListEntity?

    @Query("SELECT position FROM task_lists WHERE deleted = 0 ORDER BY position DESC, id DESC LIMIT 1")
    suspend fun lastPosition(): String?

    @Query("SELECT * FROM task_lists")
    suspend fun allIncludingDeleted(): List<TaskListEntity>

    @Upsert
    suspend fun upsert(list: TaskListEntity)

    @Query("UPDATE task_lists SET server_version = :version WHERE id = :id")
    suspend fun setServerVersion(id: String, version: Long)

    @Query("DELETE FROM task_lists")
    suspend fun deleteAll()
}

@Dao
interface OutboxDao {
    /** Re-enqueueing an already-pending record keeps one row (the push reads the latest state anyway). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(entry: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY enqueued_at ASC LIMIT :limit")
    suspend fun peek(limit: Int): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE record_id = :recordId AND enqueued_at = :enqueuedAt")
    suspend fun remove(recordId: String, enqueuedAt: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE record_id = :recordId")
    suspend fun incrementAttempts(recordId: String)

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    @Query("DELETE FROM outbox")
    suspend fun deleteAll()
}

@Dao
interface SyncMetaDao {
    @Query("SELECT value FROM sync_meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(entry: SyncMetaEntity)

    @Query("DELETE FROM sync_meta")
    suspend fun deleteAll()
}
