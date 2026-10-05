package dev.suyash.dot.core.domain.time

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class SnoozePreset {
    PLUS_10_MINUTES,
    PLUS_1_HOUR,
    LATER_TODAY,
    THIS_EVENING,
    THIS_MORNING,
    TOMORROW,
    THIS_WEEKEND,
    NEXT_WEEKEND,
    NEXT_WEEK,
}

/** A resolved snooze choice. The UI shows [at] under the label so there is never any ambiguity. */
data class SnoozeOption(val preset: SnoozePreset, val at: ZonedDateTime)

/**
 * Turns snooze presets into concrete times. Pure and DST-safe: all wall-clock arithmetic is done on
 * [ZonedDateTime], which resolves gaps (spring forward) by shifting later and overlaps by taking the
 * earlier offset.
 */
class SnoozeCalculator(private val dayParts: DayParts = DayParts()) {

    /** Quick choices shown on the ringing screen and in the notification. */
    fun quickOptions(now: ZonedDateTime): List<SnoozeOption> =
        listOf(SnoozePreset.PLUS_10_MINUTES, SnoozePreset.PLUS_1_HOUR)
            .mapNotNull { preset -> resolve(preset, now)?.let { SnoozeOption(preset, it) } }

    /** The full snooze sheet (day-level choices), de-duplicated by resulting time. */
    fun options(now: ZonedDateTime): List<SnoozeOption> {
        val presets = listOf(
            SnoozePreset.LATER_TODAY,
            SnoozePreset.THIS_EVENING,
            SnoozePreset.THIS_MORNING,
            SnoozePreset.TOMORROW,
            SnoozePreset.THIS_WEEKEND,
            SnoozePreset.NEXT_WEEKEND,
            SnoozePreset.NEXT_WEEK,
        )
        val seen = HashSet<ZonedDateTime>()
        return presets.mapNotNull { preset ->
            resolve(preset, now)
                ?.takeIf { seen.add(it) }
                ?.let { SnoozeOption(preset, it) }
        }
    }

    /** Resolves [preset] relative to [now], or `null` when the preset doesn't apply right now. */
    fun resolve(preset: SnoozePreset, now: ZonedDateTime): ZonedDateTime? {
        val today = now.toLocalDate()
        return when (preset) {
            SnoozePreset.PLUS_10_MINUTES -> now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(10)
            SnoozePreset.PLUS_1_HOUR -> now.truncatedTo(ChronoUnit.MINUTES).plusHours(1)

            SnoozePreset.LATER_TODAY -> {
                val candidate = roundUpToHalfHour(now.plus(LATER_TODAY_OFFSET))
                candidate.takeIf {
                    it.toLocalDate() == today && !it.toLocalTime().isAfter(LATER_TODAY_LATEST)
                }
            }

            SnoozePreset.THIS_EVENING ->
                at(today, dayParts.evening, now).takeIf { now.isBefore(it.minusMinutes(30)) }

            SnoozePreset.THIS_MORNING ->
                // Only meaningful in the small hours ("it's 1 AM, remind me this morning").
                if (now.toLocalTime().isBefore(EARLY_MORNING_CUTOFF)) at(today, dayParts.morning, now) else null

            SnoozePreset.TOMORROW -> at(today.plusDays(1), dayParts.morning, now)

            SnoozePreset.THIS_WEEKEND ->
                if (today.dayOfWeek in dayParts.weekendDays) {
                    null
                } else {
                    at(today.with(TemporalAdjusters.next(dayParts.weekendStart)), dayParts.morning, now)
                }

            SnoozePreset.NEXT_WEEKEND -> {
                val comingWeekendStart = today.with(TemporalAdjusters.next(dayParts.weekendStart))
                val date = if (today.dayOfWeek in dayParts.weekendDays) {
                    comingWeekendStart
                } else {
                    comingWeekendStart.plusWeeks(1)
                }
                at(date, dayParts.morning, now)
            }

            SnoozePreset.NEXT_WEEK ->
                at(today.with(TemporalAdjusters.next(dayParts.weekStart)), dayParts.morning, now)
        }
    }

    private fun at(date: LocalDate, time: LocalTime, now: ZonedDateTime): ZonedDateTime =
        ZonedDateTime.of(date, time, now.zone)

    private fun roundUpToHalfHour(t: ZonedDateTime): ZonedDateTime {
        val truncated = t.truncatedTo(ChronoUnit.MINUTES)
        val minute = truncated.minute
        val needsRounding = minute % 30 != 0 || t.second != 0 || t.nano != 0
        if (!needsRounding) return truncated
        return truncated.plusMinutes((30 - minute % 30).toLong())
    }

    private companion object {
        val LATER_TODAY_OFFSET: Duration = Duration.ofHours(3)
        val LATER_TODAY_LATEST: LocalTime = LocalTime.of(21, 0)
        val EARLY_MORNING_CUTOFF: LocalTime = LocalTime.of(4, 0)
    }
}
