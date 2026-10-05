package dev.suyash.dot.core.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatabaseKeyProviderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** XOR "wrapping" stand-in for the Keystore (Robolectric has no AndroidKeyStore). */
    private class FakeWrapper(var broken: Boolean = false) : SecretWrapper {
        override fun wrap(plaintext: ByteArray) = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun unwrap(wrapped: ByteArray): ByteArray {
            if (broken) throw IllegalStateException("key gone")
            return wrapped.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    @Test
    fun `key is stable across calls and stored wrapped`() {
        val file = tmp.root.resolve("db.key")
        val provider = DatabaseKeyProvider(file, FakeWrapper())
        val first = provider.obtainKey() as DatabaseKeyProvider.Result.Ready
        val second = provider.obtainKey() as DatabaseKeyProvider.Result.Ready
        assertThat(first.passphrase).isEqualTo(second.passphrase)
        val text = String(first.passphrase, Charsets.US_ASCII)
        assertThat(text).matches("x'[0-9a-f]{64}'")
        // The file holds the wrapped key, never the raw key.
        val raw = text.substring(2, 66).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertThat(file.readBytes()).hasLength(32)
        assertThat(file.readBytes()).isNotEqualTo(raw)
    }

    @Test
    fun `lost keystore key yields a fresh key and reports the loss`() {
        val file = tmp.root.resolve("db.key")
        val wrapper = FakeWrapper()
        val original = (DatabaseKeyProvider(file, wrapper).obtainKey() as DatabaseKeyProvider.Result.Ready).passphrase
        wrapper.broken = true
        val result = DatabaseKeyProvider(file, wrapper).obtainKey()
        assertThat(result).isInstanceOf(DatabaseKeyProvider.Result.Lost::class.java)
        assertThat((result as DatabaseKeyProvider.Result.Lost).passphrase).isNotEqualTo(original)
    }
}
