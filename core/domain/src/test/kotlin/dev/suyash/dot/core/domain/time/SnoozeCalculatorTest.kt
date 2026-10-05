package dev.suyash.dot.core.domain.time

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class SnoozeCalculatorTest {

    private val ist = ZoneId.of("Asia/Kolkata")
    private val newYork = ZoneId.of("America/New_York")
    private val calc = SnoozeCalculator()

    private fun at(text: String, zone: ZoneId = ist): ZonedDateTime =
        ZonedDateTime.of(LocalDateTime.parse(text), zone)

    @Test
    fun `quick options are 10 minutes and 1 hour from now, truncated to the minute`() {
        val now = at("2026-10-02T08:00:37")
        val quick = calc.quickOptions(now).associate { it.preset to it.at.toLocalDateTime() }
        assertThat(quick[SnoozePreset.PLUS_10_MINUTES]).isEqualTo(LocalDateTime.parse("2026-10-02T08:10"))
        assertThat(quick[SnoozePreset.PLUS_1_HOUR]).isEqualTo(LocalDateTime.parse("2026-10-02T09:00"))
    }

    @Test
    fun `later today is three hours later rounded up to the half hour`() {
        val now = at("2026-10-02T10:07") // Friday
        assertThat(calc.resolve(SnoozePreset.LATER_TODAY, now)?.toLocalDateTime())
            .isEqualTo(LocalDateTime.parse("2026-10-02T13:30"))
        assertThat(calc.resolve(SnoozePreset.LATER_TODAY, at("2026-10-02T10:00"))?.toLocalDateTime())
            .isEqualTo(LocalDateTime.parse("2026-10-02T13:00"))
    }

    @Test
    fun `later today disappears in the evening`() {
        assertThat(calc.resolve(SnoozePreset.LATER_TODAY, at("2026-10-02T18:31"))).isNull()
        assertThat(calc.resolve(SnoozePreset.LATER_TODAY, at("2026-10-02T18:00"))).isNotNull()
    }

    @Test
    fun `this evening only before 17 30`() {
        assertThat(calc.resolve(SnoozePreset.THIS_EVENING, at("2026-10-02T17:29"))?.toLocalDateTime())
            .isEqualTo(LocalDateTime.parse("2026-10-02T18:00"))
        assertThat(calc.resolve(SnoozePreset.THIS_EVENING, at("2026-10-02T17:30"))).isNull()
    }

    @Test
    fun `this morning only in the small hours`() {
        assertThat(calc.resolve(SnoozePreset.THIS_MORNING, at("2026-10-03T01:15"))?.toLocalDateTime())
            .isEqualTo(LocalDateTime.parse("2026-10-03T09:00"))
        assertThat(calc.resolve(SnoozePreset.THIS_MORNING, at("2026-10-03T05:00"))).isNull()
    }

    @Test
    fun `tomorrow is the next day at the morning time`() {
        assertThat(calc.resolve(SnoozePreset.TOMORROW, at("2026-10-02T22:00"))?.toLocalDateTime())
            .isEqualTo(LocalDateTime.parse("2026-10-03T09:00"))
    }

    @Test
    fun `weekend presets on a weekday`() {
        val friday = at("2026-10-02T10:00")
        assertThat(calc.resolve(SnoozePreset.THIS_WEEKEND, friday)?.toLocalDate().toString()).isEqualTo("2026-10-03")
        assertThat(calc.resolve(SnoozePreset.NEXT_WEEKEND, friday)?.toLocalDate().toString()).isEqualTo("2026-10-10")
    }

    @Test
    fun `weekend presets on the weekend`() {
        val saturday = at("2026-10-03T10:00")
        assertThat(calc.resolve(SnoozePreset.THIS_WEEKEND, saturday)).isNull()
        assertThat(calc.resolve(SnoozePreset.NEXT_WEEKEND, saturday)?.toLocalDate().toString()).isEqualTo("2026-10-10")
        val sunday = at("2026-10-04T10:00")
        assertThat(calc.resolve(SnoozePreset.NEXT_WEEKEND, sunday)?.toLocalDate().toString()).isEqualTo("2026-10-10")
    }

    @Test
    fun `next week is next monday morning`() {
        assertThat(calc.resolve(SnoozePreset.NEXT_WEEK, at("2026-10-02T10:00"))?.toLocalDateTime())
            .isEqualTo(LocalDateTime.parse("2026-10-05T09:00"))
        // On a Monday it is the following Monday.
        assertThat(calc.resolve(SnoozePreset.NEXT_WEEK, at("2026-10-05T10:00"))?.toLocalDate().toString())
            .isEqualTo("2026-10-12")
    }

    @Test
    fun `options are de-duplicated by time`() {
        // Sunday: "tomorrow" and "next week" are both Monday 9:00 — only the first is kept.
        val options = calc.options(at("2026-10-04T10:00"))
        assertThat(options.map { it.at }).containsNoDuplicates()
        assertThat(options.map { it.preset }).contains(SnoozePreset.TOMORROW)
        assertThat(options.map { it.preset }).doesNotContain(SnoozePreset.NEXT_WEEK)
    }

    @Test
    fun `tomorrow morning survives a spring-forward gap`() {
        // 2027-03-14 02:00 → 03:00 in New York. A 02:30 morning time must shift forward, not vanish.
        val custom = SnoozeCalculator(DayParts(morning = java.time.LocalTime.of(2, 30)))
        val result = custom.resolve(SnoozePreset.TOMORROW, at("2027-03-13T20:00", newYork))!!
        assertThat(result.toLocalDateTime()).isEqualTo(LocalDateTime.parse("2027-03-14T03:30"))
    }

    @Test
    fun `plus one hour across fall-back is an absolute hour`() {
        // 2026-11-01 01:30 EDT + 1h = 01:30 EST (same wall clock, different offset).
        val now = ZonedDateTime.ofLocal(LocalDateTime.parse("2026-11-01T01:30"), newYork, null)
        val result = calc.resolve(SnoozePreset.PLUS_1_HOUR, now)!!
        assertThat(result.toInstant()).isEqualTo(now.toInstant().plusSeconds(3600))
    }
}
