package dev.suyash.dot.core.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.SecureRandom

class AccountCryptoTest {

    private val uid = "user-123"

    @Test
    fun `recovery key round-trips and forgives look-alikes`() {
        val key = RecoveryKey.generate(SecureRandom())
        val text = key.formatted()
        assertThat(text).matches("([0-9A-HJKMNP-TV-Z]{4}-){6}[0-9A-HJKMNP-TV-Z]{2}")
        assertThat(RecoveryKey.parse(text)).isEqualTo(key)
        assertThat(RecoveryKey.parse(text.lowercase().replace("-", " "))).isEqualTo(key)
        val lookAlike = text.replace('0', 'O').replace('1', 'I')
        assertThat(RecoveryKey.parse(lookAlike)).isEqualTo(key)
        assertThat(RecoveryKey.parse("too-short")).isNull()
        assertThat(key.toString()).doesNotContain(text)
    }

    @Test
    fun `account key unwraps with the right recovery key only`() {
        val account = AccountCrypto.createAccount(uid)
        val unwrapped = AccountCrypto.unwrapMasterKey(account.keyring, account.recoveryKey, uid)
        assertThat(unwrapped).isEqualTo(account.masterKey)

        val wrong = RecoveryKey.generate()
        assertThrows(GeneralSecurityException::class.java) { AccountCrypto.unwrapMasterKey(account.keyring, wrong, uid) }
        // Bound to the uid: another account's id can't unwrap it.
        assertThrows(GeneralSecurityException::class.java) { AccountCrypto.unwrapMasterKey(account.keyring, account.recoveryKey, "someone-else") }
    }

    @Test
    fun `key check value identifies the right account key`() {
        val account = AccountCrypto.createAccount(uid)
        assertThat(AccountCrypto.matches(account.masterKey, account.keyring)).isTrue()
        assertThat(AccountCrypto.matches(ByteArray(32), account.keyring)).isFalse()
    }

    @Test
    fun `data keyset round-trips under the account key`() {
        val account = AccountCrypto.createAccount(uid)
        val keyset = AccountCrypto.decryptKeyset(account.keyring.encryptedDataKeyset, account.masterKey, uid)
        val cipher = RecordCipher(AccountCrypto.recordAead(keyset), uid)
        val sealed = cipher.seal("rec-1", 1, "hello".toByteArray())
        assertThat(String(cipher.open("rec-1", 1, sealed))).isEqualTo("hello")
    }

    @Test
    fun `rotating the recovery key keeps the same account key`() {
        val account = AccountCrypto.createAccount(uid)
        val (newKey, keyring) = AccountCrypto.rotateRecoveryKey(account.masterKey, account.keyring, uid)
        assertThat(keyring.version).isEqualTo(2)
        assertThat(AccountCrypto.unwrapMasterKey(keyring, newKey, uid)).isEqualTo(account.masterKey)
        assertThrows(GeneralSecurityException::class.java) { AccountCrypto.unwrapMasterKey(keyring, account.recoveryKey, uid) }
    }
}
