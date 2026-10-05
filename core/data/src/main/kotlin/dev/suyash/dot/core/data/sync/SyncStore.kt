package dev.suyash.dot.core.data.sync

import androidx.room.withTransaction
import dev.suyash.dot.core.data.ChangeNotifier
import dev.suyash.dot.core.data.db.DotDatabase
import dev.suyash.dot.core.data.db.OutboxEntity
import dev.suyash.dot.core.data.db.RecordType
import dev.suyash.dot.core.data.db.SyncMetaEntity
import dev.suyash.dot.core.data.db.toEntity
import dev.suyash.dot.core.data.db.toRecord
import dev.suyash.dot.core.domain.events.ChangeOrigin
import dev.suyash.dot.core.domain.events.TaskChange
import dev.suyash.dot.core.domain.model.ListId
import dev.suyash.dot.core.domain.model.TaskId
import dev.suyash.dot.core.domain.sync.HlcClock
import dev.suyash.dot.core.domain.sync.ListRecord
import dev.suyash.dot.core.domain.sync.LwwMerge
import dev.suyash.dot.core.domain.sync.TaskRecord
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** A local record plus the server version it is based on. */
data class LocalTask(val record: TaskRecord, val serverVersion: Long)
data class LocalList(val record: ListRecord, val serverVersion: Long)

/**
 * Database operations used only by the sync engine. Remote changes are merged with the local state
 * using the same LWW-map merge everywhere, so applying the same remote record twice, or in any order,
 * converges to the same result. Remote changes are announced with [ChangeOrigin.REMOTE] (alarms and
 * widgets update; nothing is pushed back).
 */
@Singleton
class SyncStore @Inject constructor(
    private val database: DotDatabase,
    private val hlc: HlcClock,
    private val notifier: ChangeNotifier,
) {
    private val tasks get() = database.taskDao()
    private val lists get() = database.taskListDao()
    private val outbox get() = database.outboxDao()
    private val meta get() = database.syncMetaDao()

    fun observePendingCount(): Flow<Int> = outbox.observeCount()

    suspend fun pendingCount(): Int = outbox.count()

    suspend fun pendingOutbox(limit: Int): List<OutboxEntity> = outbox.peek(limit)

    suspend fun dropOutbox(entry: OutboxEntity) = outbox.remove(entry.recordId, entry.enqueuedAt)

    suspend fun localTask(id: String): LocalTask? = tasks.get(id)?.let { LocalTask(it.toRecord(), it.serverVersion) }

    suspend fun localList(id: String): LocalList? = lists.get(id)?.let { LocalList(it.toRecord(), it.serverVersion) }

    /** After a successful push: fold [pushed] into whatever is local now and record the server version. */
    suspend fun confirmTaskPush(entry: OutboxEntity, pushed: TaskRecord, version: Long) {
        database.withTransaction {
            val row = tasks.get(pushed.task.id.value)
            val merged = row?.let { LwwMerge.merge(it.toRecord(), pushed) } ?: pushed
            tasks.upsert(merged.toEntity(serverVersion = version))
            outbox.remove(entry.recordId, entry.enqueuedAt) // keeps the row if the user edited again meanwhile
        }
    }

    suspend fun confirmListPush(entry: OutboxEntity, pushed: ListRecord, version: Long) {
        database.withTransaction {
            val row = lists.get(pushed.list.id.value)
            val merged = row?.let { LwwMerge.merge(it.toRecord(), pushed) } ?: pushed
            lists.upsert(merged.toEntity(serverVersion = version))
            outbox.remove(entry.recordId, entry.enqueuedAt)
        }
    }

    /** Applies remote records (from a pull). Returns how many local rows actually changed. */
    suspend fun applyRemote(remoteTasks: List<Pair<TaskRecord, Long>>, remoteLists: List<Pair<ListRecord, Long>>): Int {
        if (remoteTasks.isEmpty() && remoteLists.isEmpty()) return 0
        val changedTasks = mutableSetOf<dev.suyash.dot.core.domain.model.TaskId>()
        val changedLists = mutableSetOf<dev.suyash.dot.core.domain.model.ListId>()
        database.withTransaction {
            for ((remote, version) in remoteLists) {
                remote.clocks.values.maxOrNull()?.let(hlc::observe)
                val row = lists.get(remote.list.id.value)
                if (row != null && version <= row.serverVersion) continue
                val local = row?.toRecord()
                val merged = local?.let { LwwMerge.merge(it, remote) } ?: remote
                if (merged != local || row?.serverVersion != version) {
                    lists.upsert(merged.toEntity(serverVersion = version))
                    if (merged != local) changedLists += remote.list.id
                }
            }
            for ((remote, version) in remoteTasks) {
                remote.latest.let(hlc::observe)
                val row = tasks.get(remote.task.id.value)
                if (row != null && version <= row.serverVersion) continue
                val local = row?.toRecord()
                val merged = local?.let { LwwMerge.merge(it, remote) } ?: remote
                if (merged != local || row?.serverVersion != version) {
                    tasks.upsert(merged.toEntity(serverVersion = version))
                    if (merged != local) changedTasks += remote.task.id
                }
            }
        }
        if (changedTasks.isNotEmpty() || changedLists.isNotEmpty()) {
            notifier.notify(TaskChange(ChangeOrigin.REMOTE, taskIds = changedTasks, listIds = changedLists))
        }
        return changedTasks.size + changedLists.size
    }

    /**
     * A record this device had synced is gone from the cloud: it was deleted elsewhere and its marker
     * has since expired (or the cloud copy was wiped). Drops it here too, with any unsent edit.
     */
    suspend fun dropGone(type: RecordType, id: String) {
        database.withTransaction {
            when (type) {
                RecordType.TASK -> tasks.hardDelete(listOf(id))
                RecordType.LIST -> lists.hardDelete(listOf(id))
            }
            outbox.removeAll(listOf(id))
        }
        notifier.notify(
            when (type) {
                RecordType.TASK -> TaskChange(ChangeOrigin.REMOTE, taskIds = setOf(TaskId(id)))
                RecordType.LIST -> TaskChange(ChangeOrigin.REMOTE, listIds = setOf(ListId(id)), remindersChanged = false)
            },
        )
    }

    /**
     * After downloading the whole cloud copy: drops every record this device had synced that isn't in
     * it any more ([isInCloud] says which are). Returns how many were dropped.
     */
    suspend fun dropAllGone(isInCloud: (RecordType, String) -> Boolean): Int {
        val goneTasks = mutableSetOf<TaskId>()
        val goneLists = mutableSetOf<ListId>()
        database.withTransaction {
            tasks.allIncludingDeleted().filter { it.serverVersion > 0 && !isInCloud(RecordType.TASK, it.id) }
                .forEach { goneTasks += TaskId(it.id) }
            lists.allIncludingDeleted().filter { it.serverVersion > 0 && !isInCloud(RecordType.LIST, it.id) }
                .forEach { goneLists += ListId(it.id) }
            goneTasks.map { it.value }.chunked(CHUNK).forEach { tasks.hardDelete(it); outbox.removeAll(it) }
            goneLists.map { it.value }.chunked(CHUNK).forEach { lists.hardDelete(it); outbox.removeAll(it) }
        }
        if (goneTasks.isNotEmpty() || goneLists.isNotEmpty()) {
            notifier.notify(TaskChange(ChangeOrigin.REMOTE, taskIds = goneTasks, listIds = goneLists))
        }
        return goneTasks.size + goneLists.size
    }

    /**
     * Forgets deletion markers last changed before [cutoffMillis]. Markers not yet pushed are kept,
     * unless [includeUnpushed] (when signed out nothing will ever push them).
     */
    suspend fun purgeTombstones(cutoffMillis: Long, includeUnpushed: Boolean): Int = database.withTransaction {
        val pending = if (includeUnpushed) emptySet() else outbox.pendingIds().toSet()
        val taskIds = tasks.tombstonesBefore(cutoffMillis).map { it.id }.filterNot { it in pending }
        val listIds = lists.tombstonesBefore(cutoffMillis).map { it.id }.filterNot { it in pending }
        taskIds.chunked(CHUNK).forEach { tasks.hardDelete(it); outbox.removeAll(it) }
        listIds.chunked(CHUNK).forEach { lists.hardDelete(it); outbox.removeAll(it) }
        taskIds.size + listIds.size
    }

    suspend fun lastFullSyncAt(): Long = meta.get(KEY_LAST_FULL_SYNC)?.toLongOrNull() ?: 0L

    suspend fun setLastFullSyncAt(millis: Long) = meta.put(SyncMetaEntity(KEY_LAST_FULL_SYNC, millis.toString()))

    suspend fun cursor(): Long = meta.get(KEY_CURSOR)?.toLongOrNull() ?: 0L

    suspend fun setCursor(millis: Long) = meta.put(SyncMetaEntity(KEY_CURSOR, millis.toString()))

    suspend fun lastSyncedAt(): Long? = meta.get(KEY_LAST_SYNC)?.toLongOrNull()

    suspend fun setLastSyncedAt(millis: Long) = meta.put(SyncMetaEntity(KEY_LAST_SYNC, millis.toString()))

    /** Everything belonging to the signed-in account is removed from this device (sign-out / delete). */
    suspend fun wipe() {
        database.withTransaction {
            tasks.deleteAll()
            lists.deleteAll()
            outbox.deleteAll()
            meta.deleteAll()
        }
        notifier.notify(TaskChange(ChangeOrigin.REMOTE, remindersChanged = true))
    }

    /** Marks every local record as never-synced so it is pushed to a (new) account. */
    suspend fun enqueueEverything(nowMillis: Long) {
        database.withTransaction {
            for (row in database.taskDao().allIncludingDeleted()) {
                outbox.enqueue(OutboxEntity(row.id, RecordType.TASK, nowMillis))
            }
            for (row in database.taskListDao().allIncludingDeleted()) {
                outbox.enqueue(OutboxEntity(row.id, RecordType.LIST, nowMillis))
            }
        }
    }

    private companion object {
        const val KEY_CURSOR = "pull_cursor_ms"
        const val KEY_LAST_SYNC = "last_sync_ms"
        const val KEY_LAST_FULL_SYNC = "last_full_sync_ms"

        /** Stays well under SQLite's bound-variable limit. */
        const val CHUNK = 500
    }
}
