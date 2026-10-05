package dev.suyash.dot.core.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.security.MessageDigest
import java.security.SecureRandom

/** What the server stores about the user's keys. Nothing here reveals a key. */
class Keyring(
    val version: Long,
    /** Account key wrapped (AES-256-GCM) by a key derived from the recovery key. */
    val wrappedMasterKey: ByteArray,
    /** HMAC(accountKey, "dot.kcv.v1")[0..16]: confirms a restored account key belongs to this account. */
    val keyCheckValue: ByteArray,
    /** The Tink data keyset, encrypted under the account key. */
    val encryptedDataKeyset: ByteArray,
)

/** A freshly created account: the keys to keep plus the keyring to upload. */
class NewAccountKeys(
    val masterKey: ByteArray,
    val recoveryKey: RecoveryKey,
    val keyring: Keyring,
)

/**
 * Account key hierarchy (see docs/crypto-spec.md):
 *
 * ```
 * recovery key (128-bit) ─HKDF(salt = uid)→ wraps account key (256-bit) ─encrypts→ data keyset (Tink)
 * data keyset (XChaCha20-Poly1305, rotatable) ─encrypts→ every record
 * ```
 */
object AccountCrypto {

    init {
        AeadConfig.register()
    }

    private const val MASTER_KEY_BYTES = 32
    private val KCV_LABEL = "dot.kcv.v1".utf8()
    private val RECOVERY_INFO = "dot.rk-wrap.v1".utf8()
    private val WRAP_AAD_LABEL = "dot.keyring.v1".utf8()
    private val KEYSET_AAD_LABEL = "dot.keyset.v1".utf8()

    fun createAccount(uid: String, random: SecureRandom = SecureRandom()): NewAccountKeys {
        val masterKey = ByteArray(MASTER_KEY_BYTES).also(random::nextBytes)
        val recoveryKey = RecoveryKey.generate(random)
        val dataKeyset = KeysetHandle.generateNew(PredefinedAeadParameters.XCHACHA20_POLY1305)
        return NewAccountKeys(
            masterKey = masterKey,
            recoveryKey = recoveryKey,
            keyring = Keyring(
                version = 1,
                wrappedMasterKey = wrapMasterKey(masterKey, recoveryKey, uid),
                keyCheckValue = keyCheckValue(masterKey),
                encryptedDataKeyset = encryptKeyset(dataKeyset, masterKey, uid),
            ),
        )
    }

    fun keyCheckValue(masterKey: ByteArray): ByteArray = hmacSha256(masterKey, KCV_LABEL).copyOf(16)

    fun matches(masterKey: ByteArray, keyring: Keyring): Boolean =
        MessageDigest.isEqual(keyCheckValue(masterKey), keyring.keyCheckValue)

    fun wrapMasterKey(masterKey: ByteArray, recoveryKey: RecoveryKey, uid: String): ByteArray =
        AesGcm.seal(recoveryWrappingKey(recoveryKey, uid), masterKey, aad(WRAP_AAD_LABEL, uid.utf8()))

    /** @throws java.security.GeneralSecurityException if the recovery key is wrong. */
    fun unwrapMasterKey(keyring: Keyring, recoveryKey: RecoveryKey, uid: String): ByteArray {
        val masterKey = AesGcm.open(recoveryWrappingKey(recoveryKey, uid), keyring.wrappedMasterKey, aad(WRAP_AAD_LABEL, uid.utf8()))
        check(matches(masterKey, keyring)) { "Key check failed" }
        return masterKey
    }

    /** Re-wraps the account key under a new recovery key (rotation); the data keyset is unchanged. */
    fun rotateRecoveryKey(masterKey: ByteArray, keyring: Keyring, uid: String, random: SecureRandom = SecureRandom()): Pair<RecoveryKey, Keyring> {
        val recoveryKey = RecoveryKey.generate(random)
        return recoveryKey to Keyring(
            version = keyring.version + 1,
            wrappedMasterKey = wrapMasterKey(masterKey, recoveryKey, uid),
            keyCheckValue = keyring.keyCheckValue,
            encryptedDataKeyset = keyring.encryptedDataKeyset,
        )
    }

    fun encryptKeyset(handle: KeysetHandle, masterKey: ByteArray, uid: String): ByteArray =
        TinkProtoKeysetFormat.serializeEncryptedKeyset(handle, RawKeyAead(masterKey), aad(KEYSET_AAD_LABEL, uid.utf8()))

    fun decryptKeyset(encrypted: ByteArray, masterKey: ByteArray, uid: String): KeysetHandle =
        TinkProtoKeysetFormat.parseEncryptedKeyset(encrypted, RawKeyAead(masterKey), aad(KEYSET_AAD_LABEL, uid.utf8()))

    fun recordAead(keyset: KeysetHandle): Aead = keyset.getPrimitive(RegistryConfiguration.get(), Aead::class.java)

    private fun recoveryWrappingKey(recoveryKey: RecoveryKey, uid: String): ByteArray =
        Hkdf.sha256(ikm = recoveryKey.toBytes(), salt = uid.utf8(), info = RECOVERY_INFO, length = 32)
}
