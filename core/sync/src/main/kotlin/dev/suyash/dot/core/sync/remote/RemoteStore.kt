package dev.suyash.dot.core.sync.remote

import dev.suyash.dot.core.crypto.Keyring
import kotlinx.coroutines.flow.Flow

/** One encrypted record as the server sees it. */
class RemoteDoc(
    val rid: String,
    val version: Long,
    val ciphertext: ByteArray,
    val deleted: Boolean,
    val updatedAtMillis: Long,
)

/**
 * What to store for a record. A deletion marker carries [expiresAtMillis]: the server erases it
 * on its own after that (a TTL policy), even if no device ever comes back to clean up.
 */
class RemoteWrite(val version: Long, val ciphertext: ByteArray, val deleted: Boolean, val expiresAtMillis: Long? = null)

class RemotePage(val docs: List<RemoteDoc>, val next: Any?)

/** The server refused this particular write (e.g. a rule check); other records may still go through. */
class RemoteRejectedException(cause: Throwable) : Exception(cause)

/**
 * The cloud as a dumb, per-user store of ciphertext. Keeping this an interface means the backend
 * (Firestore today) can be swapped without touching crypto or merge logic.
 */
interface RemoteStore {
    suspend fun keyring(uid: String): Keyring?

    /** Creates the keyring only if none exists. Returns false if another device created one first. */
    suspend fun createKeyring(uid: String, keyring: Keyring): Boolean

    suspend fun replaceKeyring(uid: String, keyring: Keyring)

    /**
     * Atomic read-modify-write of one record. [build] receives the current server doc (or null) and
     * returns what to write (null: write nothing) plus a result. May run more than once on contention,
     * so keep it pure. Throws [RemoteRejectedException] if the server refuses the write.
     */
    suspend fun <T> transact(uid: String, rid: String, build: (RemoteDoc?) -> Pair<RemoteWrite?, T>): T

    /** Records changed at or after [sinceMillis], oldest first; pass [RemotePage.next] for more. */
    suspend fun pull(uid: String, sinceMillis: Long, after: Any?, limit: Int): RemotePage

    /** Emits whenever someone else changes this user's records (used while the app is open). */
    fun changes(uid: String, sinceMillis: Long): Flow<Unit>

    /** Deletes every record and the keyring (account deletion / "start over"). */
    suspend fun deleteEverything(uid: String)

    /**
     * Erases deletion markers whose expiry has passed (for when the server has no TTL policy).
     * Each one is re-checked in a transaction, so a record restored meanwhile is never touched.
     * Returns how many were erased.
     */
    suspend fun deleteExpiredMarkers(uid: String, nowMillis: Long): Int
}
