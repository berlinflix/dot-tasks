package dev.suyash.dot.core.sync

import android.util.Log
import dev.suyash.dot.core.crypto.AccountKeys
import dev.suyash.dot.core.data.db.OutboxEntity
import dev.suyash.dot.core.data.db.RecordType
import dev.suyash.dot.core.data.sync.SyncStore
import dev.suyash.dot.core.domain.sync.ListRecord
import dev.suyash.dot.core.domain.sync.LwwMerge
import dev.suyash.dot.core.domain.sync.TaskRecord
import dev.suyash.dot.core.sync.payload.DecodedRecord
import dev.suyash.dot.core.sync.payload.RecordCodec
import dev.suyash.dot.core.sync.remote.RemoteDoc
import dev.suyash.dot.core.sync.remote.RemoteStore
import dev.suyash.dot.core.sync.remote.RemoteWrite
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.GeneralSecurityException
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
 */
@Singleton
class SyncEngine @Inject constructor(
    private val remote: RemoteStore,
    private val store: SyncStore,
    private val session: Provider<AccountSession>,
) {
    private val mutex = Mutex()

    suspend fun sync(): SyncOutcome = mutex.withLock {
        val keys = session.get().keysOrNull() ?: return@withLock SyncOutcome.NOT_READY
        try {
            push(keys)
            pull(keys)
            store.setLastSyncedAt(System.currentTimeMillis())
            SyncOutcome.DONE
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed: ${e.javaClass.simpleName}")
            SyncOutcome.RETRY
        }
    }

    /** Only pull (used by the live listener while the app is open). */
    suspend fun pullOnly(): SyncOutcome = mutex.withLock {
        val keys = session.get().keysOrNull() ?: return@withLock SyncOutcome.NOT_READY
        try {
            pull(keys)
            SyncOutcome.DONE
        } catch (e: Exception) {
            Log.w(TAG, "Pull failed: ${e.javaClass.simpleName}")
            SyncOutcome.RETRY
        }
    }

    private suspend fun push(keys: AccountKeys) {
        val cipher = keys.recordCipher()
        while (true) {
            val batch = store.pendingOutbox(BATCH)
            if (batch.isEmpty()) return
            for (entry in batch) pushOne(keys, entry, cipher)
            if (batch.size < BATCH) return
        }
    }

    private suspend fun pushOne(keys: AccountKeys, entry: OutboxEntity, cipher: dev.suyash.dot.core.crypto.RecordCipher) {
        when (entry.recordType) {
            RecordType.TASK -> {
                val local = store.localTask(entry.recordId) ?: return store.dropOutbox(entry)
                val rid = RecordCodec.taskRemoteId(local.record.task.id)
                val (merged, version) = remote.transact(keys.uid, rid) { doc ->
                    val base = if (doc != null && doc.version > local.serverVersion) {
                        (decode(cipher, doc) as? DecodedRecord.OfTask)?.record?.let { LwwMerge.merge(local.record, it) } ?: local.record
                    } else {
                        local.record
                    }
                    val next = (doc?.version ?: 0L) + 1
                    RemoteWrite(next, cipher.seal(rid, next, RecordCodec.encode(base)), base.deleted) to (base to next)
                }
                store.confirmTaskPush(entry, merged, version)
            }
            RecordType.LIST -> {
                val local = store.localList(entry.recordId) ?: return store.dropOutbox(entry)
                val rid = RecordCodec.listRemoteId(local.record.list.id)
                val (merged, version) = remote.transact(keys.uid, rid) { doc ->
                    val base = if (doc != null && doc.version > local.serverVersion) {
                        (decode(cipher, doc) as? DecodedRecord.OfList)?.record?.let { LwwMerge.merge(local.record, it) } ?: local.record
                    } else {
                        local.record
                    }
                    val next = (doc?.version ?: 0L) + 1
                    RemoteWrite(next, cipher.seal(rid, next, RecordCodec.encode(base)), base.deleted) to (base to next)
                }
                store.confirmListPush(entry, merged, version)
            }
        }
    }

    private suspend fun pull(keys: AccountKeys) {
        val cipher = keys.recordCipher()
        val cursor = store.cursor()
        var after: Any? = null
        var newest = cursor
        do {
            val page = remote.pull(keys.uid, sinceMillis = (cursor - OVERLAP_MS).coerceAtLeast(0), after = after, limit = PAGE)
            val tasks = mutableListOf<Pair<TaskRecord, Long>>()
            val lists = mutableListOf<Pair<ListRecord, Long>>()
            for (doc in page.docs) {
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
    }

    /** Decrypts and decodes; returns null (and skips) for anything that fails authentication. */
    private fun decode(cipher: dev.suyash.dot.core.crypto.RecordCipher, doc: RemoteDoc): DecodedRecord? = try {
        RecordCodec.decode(cipher.open(doc.rid, doc.version, doc.ciphertext))
    } catch (e: GeneralSecurityException) {
        Log.w(TAG, "Skipping a record that failed authentication")
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Skipping a malformed record")
        null
    }

    private companion object {
        const val TAG = "SyncEngine"
        const val BATCH = 50
        const val PAGE = 300
        const val OVERLAP_MS = 2_000L
    }
}
