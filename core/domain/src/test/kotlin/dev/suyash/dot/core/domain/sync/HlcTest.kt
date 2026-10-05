package dev.suyash.dot.core.domain.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HlcTest {

    @Test
    fun `encoding round-trips and sorts like the clock`() {
        val a = Hlc(1_790_000_000_000, 0, "phone")
        val b = Hlc(1_790_000_000_000, 7, "phone")
        val c = Hlc(1_790_000_000_001, 0, "alpha")
        assertThat(Hlc.decode(a.encode())).isEqualTo(a)
        assertThat(listOf(c, a, b).sorted()).containsExactly(a, b, c).inOrder()
        assertThat(listOf(c.encode(), a.encode(), b.encode()).sorted())
            .containsExactly(a.encode(), b.encode(), c.encode()).inOrder()
    }

    @Test
    fun `clock is monotonic even when the wall clock goes backwards`() {
        var wall = 1_000L
        val clock = HlcClock("n1", wallClock = { wall })
        val first = clock.now()
        wall = 500
        val second = clock.now()
        val third = clock.now()
        assertThat(second).isGreaterThan(first)
        assertThat(third).isGreaterThan(second)
    }

    @Test
    fun `observing a remote timestamp moves the clock past it`() {
        val clock = HlcClock("local", wallClock = { 1_000L })
        val remote = Hlc(5_000, 3, "remote")
        clock.observe(remote)
        assertThat(clock.now()).isGreaterThan(remote)
    }

    @Test
    fun `wildly future remote clocks are not adopted`() {
        val clock = HlcClock("local", wallClock = { 1_000L }, maxForwardDriftMillis = 10_000)
        clock.observe(Hlc(1_000_000_000, 0, "broken"))
        assertThat(clock.now().wallMillis).isEqualTo(1_000L)
    }
}
