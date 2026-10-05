package dev.suyash.dot.core.domain.ring

import com.google.common.truth.Truth.assertThat
import dev.suyash.dot.core.domain.model.RingMode
import org.junit.Test
import java.time.Instant

class RingPolicyTest {

    private val scheduled = Instant.parse("2026-10-03T02:30:00Z")

    private fun decide(
        ringer: RingerMode,
        dnd: Boolean,
        mode: RingMode = RingMode.RING,
        lateBySeconds: Long = 0,
    ) = RingPolicy.decide(mode, AttentionState(ringer, dnd), scheduled, scheduled.plusSeconds(lateBySeconds))

    @Test
    fun `normal ringer without DND rings`() {
        assertThat(decide(RingerMode.NORMAL, dnd = false)).isEqualTo(AlertStyle.RING)
    }

    @Test
    fun `vibrate ringer vibrates`() {
        assertThat(decide(RingerMode.VIBRATE, dnd = false)).isEqualTo(AlertStyle.VIBRATE)
    }

    @Test
    fun `silent ringer never rings`() {
        assertThat(decide(RingerMode.SILENT, dnd = false)).isEqualTo(AlertStyle.QUIET)
    }

    @Test
    fun `do not disturb never rings whatever the ringer says`() {
        RingerMode.entries.forEach { ringer ->
            assertThat(decide(ringer, dnd = true)).isEqualTo(AlertStyle.QUIET)
        }
    }

    @Test
    fun `notify-only tasks use a normal notification when allowed to make noise`() {
        assertThat(decide(RingerMode.NORMAL, dnd = false, mode = RingMode.NOTIFY)).isEqualTo(AlertStyle.NOTIFY)
        assertThat(decide(RingerMode.VIBRATE, dnd = false, mode = RingMode.NOTIFY)).isEqualTo(AlertStyle.NOTIFY)
        assertThat(decide(RingerMode.SILENT, dnd = false, mode = RingMode.NOTIFY)).isEqualTo(AlertStyle.QUIET)
    }

    @Test
    fun `reminders delivered more than ten minutes late are missed, not rung`() {
        assertThat(decide(RingerMode.NORMAL, dnd = false, lateBySeconds = 600)).isEqualTo(AlertStyle.RING)
        assertThat(decide(RingerMode.NORMAL, dnd = false, lateBySeconds = 601)).isEqualTo(AlertStyle.MISSED)
    }
}
