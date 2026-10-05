package dev.suyash.dot.core.crypto

import java.io.File
import java.security.SecureRandom

/**
 * Supplies the SQLCipher key for the on-device database.
 *
 * A random 256-bit key is generated once, wrapped by [SecretWrapper] (Android Keystore), and stored
 * in no-backup storage. The database therefore never exists in readable form on disk, and a copied
 * database file is useless without this device's Keystore.
 */
class DatabaseKeyProvider(
    private val keyFile: File,
    private val wrapper: SecretWrapper,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Outcome of [obtainKey]: a [Lost] key means the old database can't be opened and must be recreated. */
    sealed interface Result {
        class Ready(val passphrase: ByteArray) : Result
        class Lost(val passphrase: ByteArray, val cause: Throwable) : Result
    }

    @Synchronized
    fun obtainKey(): Result {
        if (keyFile.exists()) {
            try {
                return Result.Ready(passphraseFor(wrapper.unwrap(keyFile.readBytes())))
            } catch (e: Exception) {
                // Keystore key gone (e.g. app data partially restored). The local DB is unreadable;
                // the caller wipes it and sync restores the data from the encrypted cloud copy.
                return Result.Lost(passphraseFor(createAndStore()), e)
            }
        }
        return Result.Ready(passphraseFor(createAndStore()))
    }

    private fun createAndStore(): ByteArray {
        val raw = ByteArray(KEY_BYTES).also(random::nextBytes)
        val tmp = File(keyFile.parentFile, keyFile.name + ".tmp")
        tmp.writeBytes(wrapper.wrap(raw))
        check(tmp.renameTo(keyFile) || (keyFile.delete() && tmp.renameTo(keyFile))) { "Could not persist database key" }
        return raw
    }

    /** SQLCipher raw-key syntax (x'hex'): skips PBKDF2 since the key is already full-entropy. */
    private fun passphraseFor(raw: ByteArray): ByteArray {
        val hex = raw.joinToString("") { "%02x".format(it) }
        raw.fill(0)
        return "x'$hex'".toByteArray(Charsets.US_ASCII)
    }

    private companion object {
        const val KEY_BYTES = 32
    }
}
