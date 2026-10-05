package dev.suyash.dot.core.crypto

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.DeleteBytesRequest
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/** The unlocked keys for the signed-in account, held in memory while the app runs. */
class AccountKeys(val uid: String, val masterKey: ByteArray, val dataKeyset: KeysetHandle) {
    val recordAead: Aead by lazy { AccountCrypto.recordAead(dataKeyset) }
    fun recordCipher(): RecordCipher = RecordCipher(recordAead, uid)
}

/**
 * Device-local copy of the account key, wrapped by a non-exportable Android Keystore key and kept in
 * no-backup storage. The data keyset is stored encrypted under the account key (same bytes as the
 * server copy), so nothing here is readable without this device's Keystore.
 */
@Singleton
class LocalAccountKeyStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val wrapper = KeystoreWrapper(alias = "dot.account.kek")

    private fun file(uid: String) = File(context.noBackupFilesDir, "account_${uidTag(uid)}.keys")

    fun save(keys: AccountKeys, encryptedDataKeyset: ByteArray) {
        val wrappedMk = wrapper.wrap(keys.masterKey)
        val blob = ByteBuffer.allocate(1 + 4 + wrappedMk.size + 4 + encryptedDataKeyset.size)
            .put(FORMAT)
            .putInt(wrappedMk.size).put(wrappedMk)
            .putInt(encryptedDataKeyset.size).put(encryptedDataKeyset)
            .array()
        val target = file(keys.uid)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(blob)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not persist account keys" }
        }
    }

    /** Returns the keys, or null if absent/unreadable (e.g. the Keystore key was reset). */
    fun load(uid: String): AccountKeys? {
        val target = file(uid)
        if (!target.exists()) return null
        return runCatching {
            val buffer = ByteBuffer.wrap(target.readBytes())
            require(buffer.get() == FORMAT)
            val wrappedMk = ByteArray(buffer.int).also { buffer.get(it) }
            val encKeyset = ByteArray(buffer.int).also { buffer.get(it) }
            val masterKey = wrapper.unwrap(wrappedMk)
            AccountKeys(uid, masterKey, AccountCrypto.decryptKeyset(encKeyset, masterKey, uid))
        }.getOrNull()
    }

    fun delete(uid: String) {
        file(uid).delete()
    }

    private companion object {
        const val FORMAT: Byte = 1
    }
}

/**
 * Seamless restore after reinstall / on a new phone: the account key is saved in Android Block Store,
 * which Google backs up **end-to-end encrypted with the device's screen lock** (when available).
 * If the device can't do E2EE backup, the key stays local-only and the recovery key is the fallback.
 */
@Singleton
class BlockStoreKeyBackup @Inject constructor(@ApplicationContext private val context: Context) {

    private val client by lazy { Blockstore.getClient(context) }

    suspend fun isEndToEndEncryptionAvailable(): Boolean =
        runCatching { client.isEndToEndEncryptionAvailable.await() }.getOrDefault(false)

    /** Saves [masterKey] for [uid]. Returns true if it will be backed up (E2EE) to the cloud. */
    suspend fun save(uid: String, masterKey: ByteArray): Boolean {
        val cloud = isEndToEndEncryptionAvailable()
        val request = StoreBytesData.Builder()
            .setBytes(byteArrayOf(FORMAT) + masterKey)
            .setKey(key(uid))
            .setShouldBackupToCloud(cloud)
            .build()
        client.storeBytes(request).await()
        return cloud
    }

    suspend fun load(uid: String): ByteArray? = runCatching {
        val request = RetrieveBytesRequest.Builder().setKeys(listOf(key(uid))).build()
        val data = client.retrieveBytes(request).await().blockstoreDataMap[key(uid)]?.bytes ?: return null
        if (data.size != 33 || data[0] != FORMAT) return null
        data.copyOfRange(1, data.size)
    }.getOrNull()

    suspend fun delete(uid: String) {
        runCatching {
            client.deleteBytes(DeleteBytesRequest.Builder().setKeys(listOf(key(uid))).build()).await()
        }
    }

    /** Block Store keys are per app; scope by a hash of the uid so several accounts never collide. */
    private fun key(uid: String) = "dot.mk.v1.${uidTag(uid)}"

    private companion object {
        const val FORMAT: Byte = 1
    }
}

internal fun uidTag(uid: String): String =
    sha256(uid.utf8()).take(12).joinToString("") { "%02x".format(it) }
