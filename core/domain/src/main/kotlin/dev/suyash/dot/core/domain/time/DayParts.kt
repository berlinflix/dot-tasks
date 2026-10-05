package dev.suyash.dot.core.domain.time

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * The user's notion of "morning", "evening", etc. Used by snooze presets and the voice parser so that
 * "tomorrow" and "tonight" mean the same thing everywhere in the app.
 */
data class DayParts(
    val morning: LocalTime = LocalTime.of(9, 0),
    val afternoon: LocalTime = LocalTime.of(13, 0),
    val evening: LocalTime = LocalTime.of(18, 0),
    val night: LocalTime = LocalTime.of(20, 0),
    /** First day of the weekend (Saturday in most regions; Friday in some). */
    val weekendStart: DayOfWeek = DayOfWeek.SATURDAY,
    /** First day of the working week, used for "next week". */
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
) {
    val weekendDays: Set<DayOfWeek> get() = setOf(weekendStart, weekendStart.plus(1))
}
