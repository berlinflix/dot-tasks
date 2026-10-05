package dev.suyash.dot.core.crypto

import com.google.crypto.tink.Aead
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM with a random 96-bit nonce. Output: nonce ‖ ciphertext ‖ tag. */
internal object AesGcm {
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key required" }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key required" }
        require(sealed.size > NONCE_BYTES + TAG_BITS / 8) { "Ciphertext too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
    }
}

/** Adapts a raw 256-bit key to Tink's [Aead] (used to encrypt the Tink data keyset itself). */
internal class RawKeyAead(private val key: ByteArray) : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray =
        AesGcm.seal(key, plaintext, associatedData ?: ByteArray(0))

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray =
        AesGcm.open(key, ciphertext, associatedData ?: ByteArray(0))
}

/** HKDF-SHA256 (RFC 5869). */
object Hkdf {
    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        return out
    }
}

internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

internal fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

/** Unambiguous associated data: every part length-prefixed, so ("ab","c") ≠ ("a","bc"). */
internal fun aad(vararg parts: ByteArray): ByteArray {
    val buffer = ByteBuffer.allocate(parts.sumOf { 4 + it.size })
    for (part in parts) {
        buffer.putInt(part.size)
        buffer.put(part)
    }
    return buffer.array()
}

internal fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)

internal fun Long.bytes(): ByteArray = ByteBuffer.allocate(8).putLong(this).array()
