package dev.suyash.dot.core.crypto

import com.google.crypto.tink.Aead
import java.nio.ByteBuffer

/**
 * Encrypts one synced record.
 *
 * - **AEAD**: Tink XChaCha20-Poly1305 (192-bit random nonces, keyset rotation via key ids).
 * - **Associated data** binds the ciphertext to `uid`, record id and version, so the server cannot
 *   move a record to another slot/user or serve an older version as the current one.
 * - **Padding** to 256-byte buckets hides exact title/notes lengths.
 *
 * Plaintext frame: `format(1) ‖ length(4, BE) ‖ payload ‖ zero padding`.
 */
class RecordCipher(private val aead: Aead, private val uid: String) {

    fun seal(recordId: String, version: Long, payload: ByteArray): ByteArray {
        val framedLength = HEADER_BYTES + payload.size
        val padded = ((framedLength + BUCKET - 1) / BUCKET) * BUCKET
        val frame = ByteBuffer.allocate(padded)
            .put(FORMAT_V1)
            .putInt(payload.size)
            .put(payload)
            .array()
        return aead.encrypt(frame, associatedData(recordId, version))
    }

    /** @throws java.security.GeneralSecurityException on any tampering, wrong key, slot or version. */
    fun open(recordId: String, version: Long, ciphertext: ByteArray): ByteArray {
        val frame = aead.decrypt(ciphertext, associatedData(recordId, version))
        val buffer = ByteBuffer.wrap(frame)
        require(buffer.get() == FORMAT_V1) { "Unknown record format" }
        val length = buffer.int
        require(length >= 0 && length <= frame.size - HEADER_BYTES) { "Corrupt record frame" }
        return frame.copyOfRange(HEADER_BYTES, HEADER_BYTES + length)
    }

    private fun associatedData(recordId: String, version: Long): ByteArray =
        aad(LABEL, uid.utf8(), recordId.utf8(), version.bytes())

    private companion object {
        const val FORMAT_V1: Byte = 1
        const val HEADER_BYTES = 5
        const val BUCKET = 256
        val LABEL = "dot.rec.v1".utf8()
    }
}
