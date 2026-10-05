package dev.suyash.dot.core.crypto

import java.security.SecureRandom

/**
 * The 128-bit recovery key the user saves once. Shown as 26 Crockford Base32 characters in groups,
 * e.g. `7K2Q-M9XW-4HTV-1B8N-QZ3R-6PD0-JA`. Crockford's alphabet has no I, L, O or U, and parsing
 * forgives the usual look-alikes (O→0, I/L→1), case and separators.
 */
class RecoveryKey private constructor(private val bytes: ByteArray) {

    fun toBytes(): ByteArray = bytes.copyOf()

    /** Display form, grouped in fours. */
    fun formatted(): String = encode(bytes).chunked(4).joinToString("-")

    override fun equals(other: Any?): Boolean = other is RecoveryKey && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "RecoveryKey(****)"

    companion object {
        const val BYTES = 16
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val CHARS = 26

        fun generate(random: SecureRandom = SecureRandom()): RecoveryKey =
            RecoveryKey(ByteArray(BYTES).also(random::nextBytes))

        fun fromBytes(bytes: ByteArray): RecoveryKey {
            require(bytes.size == BYTES)
            return RecoveryKey(bytes.copyOf())
        }

        /** Parses user input; returns null if it isn't a well-formed key. */
        fun parse(input: String): RecoveryKey? {
            val cleaned = input.uppercase()
                .filter { it.isLetterOrDigit() }
                .map { c ->
                    when (c) {
                        'O' -> '0'
                        'I', 'L' -> '1'
                        else -> c
                    }
                }
                .joinToString("")
            if (cleaned.length != CHARS || cleaned.any { ALPHABET.indexOf(it) < 0 }) return null
            return decode(cleaned)?.let(::RecoveryKey)
        }

        private fun encode(data: ByteArray): String {
            val sb = StringBuilder(CHARS)
            var buffer = 0
            var bits = 0
            for (b in data) {
                buffer = (buffer shl 8) or (b.toInt() and 0xFF)
                bits += 8
                while (bits >= 5) {
                    sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                    bits -= 5
                }
            }
            if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
            return sb.toString()
        }

        private fun decode(text: String): ByteArray? {
            val out = ByteArray(BYTES)
            var buffer = 0
            var bits = 0
            var index = 0
            for (c in text) {
                buffer = (buffer shl 5) or ALPHABET.indexOf(c)
                bits += 5
                if (bits >= 8) {
                    if (index >= BYTES) return null
                    out[index++] = (buffer shr (bits - 8)).toByte()
                    bits -= 8
                }
            }
            // 26 chars = 130 bits: the final 2 padding bits must be zero.
            if (index != BYTES || (buffer and ((1 shl bits) - 1)) != 0) return null
            return out
        }
    }
}
