package dev.suyash.dot.core.domain.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClockCodecTest {
    @Test
    fun `round trip`() {
        val clocks = mapOf(
            TaskField.TITLE to Hlc(1_790_000_000_000, 3, "dev-a"),
            TaskField.STATUS to Hlc(1_790_000_000_500, 0, "dev-b"),
        )
        val encoded = ClockCodec.encode(clocks)
        assertThat(ClockCodec.decode<TaskField>(encoded)).isEqualTo(clocks)
    }

    @Test
    fun `unknown fields are ignored for forward compatibility`() {
        val decoded = ClockCodec.decode<TaskField>("TITLE=000001790000000000:00000:a;FUTURE=000000000000001:00000:b")
        assertThat(decoded.keys).containsExactly(TaskField.TITLE)
    }
}
