package dev.suyash.dot.core.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets (keys) so they can be stored at rest. */
interface SecretWrapper {
    fun wrap(plaintext: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/**
 * Wraps secrets with a non-exportable AES-256-GCM key held by the Android Keystore
 * (StrongBox secure element when the device has one, otherwise the TEE).
 *
 * The key is usable after the first unlock without user presence — reminders must be able to read
 * the database while the phone sits locked on the nightstand.
 *
 * Wire format: version(1) ‖ iv(12) ‖ ciphertext+tag.
 */
class KeystoreWrapper(private val alias: String) : SecretWrapper {

    override fun wrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected IV length ${iv.size}" }
        val sealed = cipher.doFinal(plaintext)
        return byteArrayOf(VERSION) + iv + sealed
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > 1 + IV_BYTES && wrapped[0] == VERSION) { "Unsupported wrapped secret" }
        val iv = wrapped.copyOfRange(1, 1 + IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: error("Keystore key '$alias' is missing"), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(wrapped, 1 + IV_BYTES, wrapped.size - 1 - IV_BYTES)
    }

    fun deleteKey() {
        keyStore().deleteEntry(alias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    @Synchronized
    private fun key(): SecretKey = existingKey() ?: generate(strongBox = hasStrongBox())

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .setUnlockedDeviceRequired(false)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                .apply { init(spec) }
                .generateKey()
        } catch (e: StrongBoxUnavailableException) {
            if (strongBox) generate(strongBox = false) else throw e
        }
    }

    private fun hasStrongBox(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && strongBoxSupported

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val VERSION: Byte = 1

        /** Set once at startup from PackageManager.FEATURE_STRONGBOX_KEYSTORE. */
        @Volatile
        var strongBoxSupported: Boolean = false
    }
}
