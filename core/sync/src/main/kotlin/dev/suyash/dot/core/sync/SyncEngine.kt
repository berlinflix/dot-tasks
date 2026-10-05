package dev.suyash.dot.core.sync

import android.util.Log
import dev.suyash.dot.core.crypto.AccountKeys
import dev.suyash.dot.core.crypto.RecordCipher
import dev.suyash.dot.core.data.db.OutboxEntity
import dev.suyash.dot.core.data.db.RecordType
import dev.suyash.dot.core.data.sync.SyncStore
import dev.suyash.dot.core.domain.model.ListId
import dev.suyash.dot.core.domain.model.TaskId
import dev.suyash.dot.core.domain.sync.ListRecord
import dev.suyash.dot.core.domain.sync.LwwMerge
import dev.suyash.dot.core.domain.sync.TaskRecord
import dev.suyash.dot.core.sync.payload.DecodedRecord
import dev.suyash.dot.core.sync.payload.RecordCodec
import dev.suyash.dot.core.sync.remote.RemoteDoc
import dev.suyash.dot.core.sync.remote.RemoteRejectedException
import dev.suyash.dot.core.sync.remote.RemoteStore
import dev.suyash.dot.core.sync.remote.RemoteWrite
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.GeneralSecurityException
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

enum class SyncOutcome { DONE, NOT_READY, RETRY }

/**
 * Push (outbox → cloud) then pull (cloud → local).
 *
 * - **Push** is one Firestore transaction per record: if the server has a newer version, decrypt it,
 *   merge field-by-field (LWW by HLC), and write version + 1. Rules reject any other version jump.
 * - **Pull** reads records changed since the cursor (with a small overlap), verifies & decrypts each
 *   (AAD binds uid + record id + version, so tampering/rollback fails), and merges locally.
 *
 * Retention: deletion markers are uploaded with an expiry ([MARKER_LIFETIME_MS]) after which the
 * server erases them, and they are forgotten locally after the same time. Because a device that
 * was away longer may have missed some, a full download every [FULL_SYNC_INTERVAL_MS] drops
 * whatever this device synced that no longer exists in the cloud (the server keeps markers for at
 * least that long, which the security rules enforce).
 */
@Singleton
class SyncEngine @Inject constructor(
    private val remote: RemoteStore,
    private val store: SyncStore,
    private val session: Provider<AccountSession>,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    suspend fun sync(): SyncOutcome = mutex.withLock {
        val keys = session.get().keysOrNull() ?: return@withLock SyncOutcome.NOT_READY
        try {
            val rejected = push(keys)
            val now = clock.millis()
            val full = now - store.lastFullSyncAt() > FULL_SYNC_INTERVAL_MS
            pull(keys, full)
            if (full) {
                store.setLastFullSyncAt(now)
                // Server-side cleanup of expired markers (a TTL policy does this too, where enabled).
                remote.deleteExpiredMarkers(keys.uid, now)
            }
            store.purgeTombstones(cutoffMillis = now - MARKER_LIFETIME_MS, includeUnpushed = false)
            store.setLastSyncedAt(now)
            if (rejected == 0) {
                SyncOutcome.DONE
            } else {
                session.get().checkAccountStillExists()
                SyncOutcome.RETRY
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed: ${e.javaClass.simpleName}")
            session.get().checkAccountStillExists()
            SyncOutcome.RETRY
        }
    }

    /** Only pull (used by the live listener while the app is open). */
    suspend fun pullOnly(): SyncOutcome = mutex.withLock {
        val keys = session.get().keysOrNull() ?: return@withLock SyncOutcome.NOT_READY
        try {
            pull(keys, full = false)
            SyncOutcome.DONE
        } catch (e: Exception) {
            Log.w(TAG, "Pull failed: ${e.javaClass.simpleName}")
            SyncOutcome.RETRY
        }
    }

    /** Pushes everything queued. A record the server refuses is skipped (and retried next time). */
    private suspend fun push(keys: AccountKeys): Int {
        val cipher = keys.recordCipher()
        val skipped = mutableSetOf<String>()
        while (true) {
            val batch = store.pendingOutbox(BATCH + skipped.size).filterNot { it.recordId in skipped }
            if (batch.isEmpty()) return skipped.size
            for (entry in batch) {
                try {
                    pushOne(keys, entry, cipher)
                } catch (e: RemoteRejectedException) {
                    Log.w(TAG, "The server refused a record")
                    skipped += entry.recordId
                }
            }
            if (batch.size < BATCH) return skipped.size
        }
    }

    private sealed interface Pushed<out R> {
        data class Stored<R>(val record: R, val version: Long) : Pushed<R>

        /** Not in the cloud although it was synced before (deleted elsewhere), or never worth uploading. */
        data object Gone : Pushed<Nothing>
    }

    private suspend fun pushOne(keys: AccountKeys, entry: OutboxEntity, cipher: RecordCipher) {
        when (entry.recordType) {
            RecordType.TASK -> {
                val local = store.localTask(entry.recordId) ?: return store.dropOutbox(entry)
                val rid = RecordCodec.taskRemoteId(local.record.task.id)
                val result = remote.transact<Pushed<TaskRecord>>(keys.uid, rid) { doc ->
                    if (doc == null && (local.serverVersion > 0 || local.record.deleted)) return@transact null to Pushed.Gone
                    val base = if (doc != null && doc.version > local.serverVersion) {
                        (decode(cipher, doc) as? DecodedRecord.OfTask)?.record?.let { LwwMerge.merge(local.record, it) } ?: local.record
                    } else {
                        local.record
                    }
                    val next = (doc?.version ?: 0L) + 1
                    write(next, cipher.seal(rid, next, RecordCodec.encode(base)), base.deleted) to Pushed.Stored(base, next)
                }
                when (result) {
                    is Pushed.Stored -> store.confirmTaskPush(entry, result.record, result.version)
                    Pushed.Gone -> store.dropGone(RecordType.TASK, entry.recordId)
                }
            }
            RecordType.LIST -> {
                val local = store.localList(entry.recordId) ?: return store.dropOutbox(entry)
                val rid = RecordCodec.listRemoteId(local.record.list.id)
                val result = remote.transact<Pushed<ListRecord>>(keys.uid, rid) { doc ->
                    if (doc == null && (local.serverVersion > 0 || local.record.deleted)) return@transact null to Pushed.Gone
                    val base = if (doc != null && doc.version > local.serverVersion) {
                        (decode(cipher, doc) as? DecodedRecord.OfList)?.record?.let { LwwMerge.merge(local.record, it) } ?: local.record
                    } else {
                        local.record
                    }
                    val next = (doc?.version ?: 0L) + 1
                    write(next, cipher.seal(rid, next, RecordCodec.encode(base)), base.deleted) to Pushed.Stored(base, next)
                }
                when (result) {
                    is Pushed.Stored -> store.confirmListPush(entry, result.record, result.version)
                    Pushed.Gone -> store.dropGone(RecordType.LIST, entry.recordId)
                }
            }
        }
    }

    private fun write(version: Long, ciphertext: ByteArray, deleted: Boolean) = RemoteWrite(
        version = version,
        ciphertext = ciphertext,
        deleted = deleted,
        expiresAtMillis = if (deleted) clock.millis() + MARKER_LIFETIME_MS else null,
    )

    /**
     * Applies cloud changes. A [full] pull reads everything and then drops local records that this
     * device had synced but that no longer exist in the cloud.
     */
    private suspend fun pull(keys: AccountKeys, full: Boolean) {
        val cipher = keys.recordCipher()
        val cursor = store.cursor()
        val since = if (full) 0L else (cursor - OVERLAP_MS).coerceAtLeast(0)
        val inCloud = if (full) HashSet<String>() else null
        var after: Any? = null
        var newest = cursor
        do {
            val page = remote.pull(keys.uid, sinceMillis = since, after = after, limit = PAGE)
            val tasks = mutableListOf<Pair<TaskRecord, Long>>()
            val lists = mutableListOf<Pair<ListRecord, Long>>()
            for (doc in page.docs) {
                inCloud?.add(doc.rid)
                when (val decoded = decode(cipher, doc)) {
                    is DecodedRecord.OfTask -> if (RecordCodec.taskRemoteId(decoded.record.task.id) == doc.rid) tasks += decoded.record to doc.version
                    is DecodedRecord.OfList -> if (RecordCodec.listRemoteId(decoded.record.list.id) == doc.rid) lists += decoded.record to doc.version
                    DecodedRecord.Unknown, null -> Unit
                }
                newest = maxOf(newest, doc.updatedAtMillis)
            }
            store.applyRemote(tasks, lists)
            after = page.next
        } while (after != null)
        if (newest > cursor) store.setCursor(newest)
        if (inCloud != null) {
            val dropped = store.dropAllGone { type, id ->
                when (type) {
                    RecordType.TASK -> RecordCodec.taskRemoteId(TaskId(id))
                    RecordType.LIST -> RecordCodec.listRemoteId(ListId(id))
                } in inCloud
            }
            if (dropped > 0) Log.i(TAG, "Dropped $dropped records deleted on other devices")
        }
    }

    /** Decrypts and decodes; returns null (and skips) for anything that fails authentication. */
    private fun decode(cipher: RecordCipher, doc: RemoteDoc): DecodedRecord? = try {
        RecordCodec.decode(cipher.open(doc.rid, doc.version, doc.ciphertext))
    } catch (e: GeneralSecurityException) {
        Log.w(TAG, "Skipping a record that failed authentication")
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Skipping a malformed record")
        null
    }

    companion object {
        private const val TAG = "SyncEngine"
        private const val BATCH = 50
        private const val PAGE = 300
        private const val OVERLAP_MS = 2_000L

        /** How long deletion markers live (server TTL and local purge). */
        val MARKER_LIFETIME_MS: Long = TimeUnit.DAYS.toMillis(30)

        /** Must not exceed the shortest marker lifetime the security rules allow (7 days). */
        val FULL_SYNC_INTERVAL_MS: Long = TimeUnit.DAYS.toMillis(7)
    }
}
