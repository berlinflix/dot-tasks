package dev.suyash.dot.core.domain.order

/**
 * Fractional indexing: sort keys that always leave room between any two neighbours, so a task can be
 * moved by rewriting only its own key. Two devices reordering concurrently never conflict on other rows.
 *
 * Keys are base-62 fractions (`0.<digits>`) compared lexicographically (byte order == SQLite BINARY).
 * Keys never end in the zero digit, which guarantees a key strictly between any two distinct keys exists.
 */
object FractionalIndex {
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val ZERO = '0'

    /** Returns a key strictly between [before] and [after]; `null` means open-ended on that side. */
    fun between(before: String?, after: String?): String {
        val a = before.orEmpty()
        if (before != null) validate(before)
        if (after != null) validate(after)
        require(after == null || a < after) { "before ($before) must sort before after ($after)" }
        return midpoint(a, after)
    }

    fun first(): String = between(null, null)

    /** [count] evenly spread keys after [before] — handy for bulk inserts/migrations. */
    fun sequence(count: Int, before: String? = null): List<String> {
        val keys = ArrayList<String>(count)
        var last = before
        repeat(count) {
            val next = between(last, null)
            keys += next
            last = next
        }
        return keys
    }

    private fun validate(key: String) {
        require(key.isNotEmpty()) { "Empty key" }
        require(key.last() != ZERO) { "Key must not end with the zero digit: $key" }
        require(key.all { DIGITS.indexOf(it) >= 0 }) { "Invalid key: $key" }
    }

    private fun midpoint(a: String, b: String?): String {
        if (b != null) {
            // Strip the longest common prefix (treating a missing digit in `a` as zero).
            var n = 0
            while (n < b.length && (a.getOrNull(n) ?: ZERO) == b[n]) n++
            if (n > 0) return b.substring(0, n) + midpoint(a.drop(n), b.substring(n))
        }
        val digitA = if (a.isNotEmpty()) DIGITS.indexOf(a[0]) else 0
        val digitB = if (b != null && b.isNotEmpty()) DIGITS.indexOf(b[0]) else DIGITS.length
        return if (digitB - digitA > 1) {
            DIGITS[(digitA + digitB + 1) / 2].toString()
        } else if (b != null && b.length > 1) {
            b.substring(0, 1)
        } else {
            DIGITS[digitA] + midpoint(a.drop(1), null)
        }
    }
}
