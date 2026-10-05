package dev.suyash.dot.core.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

class RecordCipherTest {

    private val uid = "user-123"
    private val account = AccountCrypto.createAccount(uid)
    private val keyset = AccountCrypto.decryptKeyset(account.keyring.encryptedDataKeyset, account.masterKey, uid)
    private val cipher = RecordCipher(AccountCrypto.recordAead(keyset), uid)

    @Test
    fun `round trip`() {
        val payload = "Buy milk".toByteArray()
        assertThat(cipher.open("task-1", 3, cipher.seal("task-1", 3, payload))).isEqualTo(payload)
    }

    @Test
    fun `ciphertexts are padded to hide length`() {
        val short = cipher.seal("t", 1, "a".toByteArray())
        val longer = cipher.seal("t", 1, ByteArray(200) { 'x'.code.toByte() })
        assertThat(short.size).isEqualTo(longer.size)
    }

    @Test
    fun `same plaintext encrypts differently every time`() {
        val a = cipher.seal("t", 1, "same".toByteArray())
        val b = cipher.seal("t", 1, "same".toByteArray())
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `tampering is detected`() {
        val sealed = cipher.seal("task-1", 1, "secret".toByteArray())
        sealed[sealed.size / 2] = (sealed[sealed.size / 2].toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) { cipher.open("task-1", 1, sealed) }
    }

    @Test
    fun `a ciphertext cannot be moved to another record, version or user`() {
        val sealed = cipher.seal("task-1", 5, "secret".toByteArray())
        assertThrows(GeneralSecurityException::class.java) { cipher.open("task-2", 5, sealed) }
        // Server replaying an old version under a newer number (rollback) fails.
        assertThrows(GeneralSecurityException::class.java) { cipher.open("task-1", 6, sealed) }
        val otherUser = RecordCipher(AccountCrypto.recordAead(keyset), "user-999")
        assertThrows(GeneralSecurityException::class.java) { otherUser.open("task-1", 5, sealed) }
    }

    @Test
    fun `a different account key cannot decrypt`() {
        val other = AccountCrypto.createAccount(uid)
        val otherKeyset = AccountCrypto.decryptKeyset(other.keyring.encryptedDataKeyset, other.masterKey, uid)
        val sealed = cipher.seal("task-1", 1, "secret".toByteArray())
        assertThrows(GeneralSecurityException::class.java) {
            RecordCipher(AccountCrypto.recordAead(otherKeyset), uid).open("task-1", 1, sealed)
        }
    }
}
