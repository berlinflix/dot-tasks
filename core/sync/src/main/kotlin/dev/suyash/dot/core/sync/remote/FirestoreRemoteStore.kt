package dev.suyash.dot.core.sync.remote

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import dev.suyash.dot.core.crypto.Keyring
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore implementation. Layout:
 * ```
 * users/{uid}/meta/keyring        { v, wrappedMk, kcv, encDataKeyset, createdAt, updatedAt }
 * users/{uid}/records/{rid}       { v, ct, updatedAt, del }
 * ```
 * The SDK's disk cache is disabled (memory only): Room is the offline store, so there is no second
 * on-disk copy — and everything Firestore holds is ciphertext anyway.
 */
@Singleton
class FirestoreRemoteStore @Inject constructor() : RemoteStore {

    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance().apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()
        }
    }

    private fun user(uid: String) = db.collection("users").document(uid)
    private fun records(uid: String) = user(uid).collection("records")
    private fun keyringRef(uid: String) = user(uid).collection("meta").document("keyring")

    override suspend fun keyring(uid: String): Keyring? {
        val snapshot = keyringRef(uid).get(Source.SERVER).await()
        if (!snapshot.exists()) return null
        return snapshot.toKeyring()
    }

    override suspend fun createKeyring(uid: String, keyring: Keyring): Boolean =
        db.runTransaction { tx ->
            val ref = keyringRef(uid)
            if (tx.get(ref).exists()) {
                false
            } else {
                tx.set(ref, keyring.toMap() + ("createdAt" to FieldValue.serverTimestamp()))
                true
            }
        }.await()

    override suspend fun replaceKeyring(uid: String, keyring: Keyring) {
        keyringRef(uid).set(keyring.toMap()).await()
    }

    override suspend fun <T> transact(uid: String, rid: String, build: (RemoteDoc?) -> Pair<RemoteWrite, T>): T =
        db.runTransaction { tx ->
            val ref = records(uid).document(rid)
            val snapshot = tx.get(ref)
            val current = if (snapshot.exists()) snapshot.toRemoteDoc() else null
            val (write, result) = build(current)
            tx.set(
                ref,
                mapOf(
                    "v" to write.version,
                    "ct" to Blob.fromBytes(write.ciphertext),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "del" to write.deleted,
                ),
            )
            result
        }.await()

    override suspend fun pull(uid: String, sinceMillis: Long, after: Any?, limit: Int): RemotePage {
        var query: Query = records(uid)
            .whereGreaterThanOrEqualTo("updatedAt", Timestamp(Date(sinceMillis)))
            .orderBy("updatedAt")
            .limit(limit.toLong())
        if (after is DocumentSnapshot) query = query.startAfter(after)
        val snapshot = query.get(Source.SERVER).await()
        val docs = snapshot.documents.mapNotNull { it.toRemoteDoc() }
        val next = if (snapshot.size() >= limit) snapshot.documents.lastOrNull() else null
        return RemotePage(docs, next)
    }

    override fun changes(uid: String, sinceMillis: Long): Flow<Unit> = callbackFlow {
        val registration = records(uid)
            .whereGreaterThan("updatedAt", Timestamp(Date(sinceMillis)))
            .addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error == null && snapshot != null && !snapshot.metadata.hasPendingWrites() && !snapshot.isEmpty) {
                    trySend(Unit)
                }
            }
        awaitClose { registration.remove() }
    }

    override suspend fun deleteEverything(uid: String) {
        while (true) {
            val page = records(uid).limit(400).get(Source.SERVER).await()
            if (page.isEmpty) break
            val batch = db.batch()
            page.documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
        keyringRef(uid).delete().await()
    }

    private fun DocumentSnapshot.toRemoteDoc(): RemoteDoc? {
        val version = getLong("v") ?: return null
        val blob = getBlob("ct") ?: return null
        return RemoteDoc(
            rid = id,
            version = version,
            ciphertext = blob.toBytes(),
            deleted = getBoolean("del") ?: false,
            updatedAtMillis = getTimestamp("updatedAt")?.toDate()?.time ?: 0L,
        )
    }

    private fun DocumentSnapshot.toKeyring(): Keyring? {
        return Keyring(
            version = getLong("v") ?: return null,
            wrappedMasterKey = getBlob("wrappedMk")?.toBytes() ?: return null,
            keyCheckValue = getBlob("kcv")?.toBytes() ?: return null,
            encryptedDataKeyset = getBlob("encDataKeyset")?.toBytes() ?: return null,
        )
    }

    private fun Keyring.toMap(): Map<String, Any> = mapOf(
        "v" to version,
        "wrappedMk" to Blob.fromBytes(wrappedMasterKey),
        "kcv" to Blob.fromBytes(keyCheckValue),
        "encDataKeyset" to Blob.fromBytes(encryptedDataKeyset),
        "updatedAt" to FieldValue.serverTimestamp(),
    )
}
