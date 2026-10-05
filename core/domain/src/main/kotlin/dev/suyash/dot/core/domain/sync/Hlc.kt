package dev.suyash.dot.core.domain.sync

/**
 * Hybrid Logical Clock timestamp: wall-clock millis + a logical counter + the device that produced it.
 * Gives a total order that respects causality even when device clocks are slightly off, which is what
 * makes "latest edit wins" merges deterministic across devices.
 */
data class Hlc(val wallMillis: Long, val counter: Int, val node: String) : Comparable<Hlc> {

    override fun compareTo(other: Hlc): Int =
        compareValuesBy(this, other, Hlc::wallMillis, Hlc::counter, Hlc::node)

    /** Fixed-width, lexicographically sortable encoding. */
    fun encode(): String = buildString {
        append(wallMillis.toString().padStart(WALL_DIGITS, '0'))
        append(SEPARATOR)
        append(counter.toString(radix = 36).padStart(COUNTER_DIGITS, '0'))
        append(SEPARATOR)
        append(node)
    }

    companion object {
        private const val WALL_DIGITS = 15
        private const val COUNTER_DIGITS = 5
        private const val SEPARATOR = ':'

        val ZERO = Hlc(0, 0, "")

        fun decode(encoded: String): Hlc {
            val parts = encoded.split(SEPARATOR, limit = 3)
            require(parts.size == 3) { "Malformed HLC: $encoded" }
            return Hlc(
                wallMillis = parts[0].toLong(),
                counter = parts[1].toInt(radix = 36),
                node = parts[2],
            )
        }
    }
}

/**
 * Thread-safe HLC generator for one device ([node]).
 *
 * @param maxForwardDriftMillis remote timestamps further than this ahead of our wall clock are not
 * adopted (they are still merged), so one device with a wildly wrong clock can't poison everyone's clock.
 */
class HlcClock(
    private val node: String,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val maxForwardDriftMillis: Long = 24 * 60 * 60 * 1000L,
) {
    private var last = Hlc(0, 0, node)

    @Synchronized
    fun now(): Hlc {
        val wall = wallClock()
        last = if (wall > last.wallMillis) {
            Hlc(wall, 0, node)
        } else {
            Hlc(last.wallMillis, last.counter + 1, node)
        }
        return last
    }

    /** Advances the clock past a timestamp received from another device. */
    @Synchronized
    fun observe(remote: Hlc) {
        val wall = wallClock()
        if (remote.wallMillis - wall > maxForwardDriftMillis) return
        val maxWall = maxOf(wall, last.wallMillis, remote.wallMillis)
        val counter = when {
            maxWall == last.wallMillis && maxWall == remote.wallMillis -> maxOf(last.counter, remote.counter) + 1
            maxWall == last.wallMillis -> last.counter + 1
            maxWall == remote.wallMillis -> remote.counter + 1
            else -> 0
        }
        last = Hlc(maxWall, counter, node)
    }
}
