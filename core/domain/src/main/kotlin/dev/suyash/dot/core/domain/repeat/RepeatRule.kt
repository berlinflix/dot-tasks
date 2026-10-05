package dev.suyash.dot.core.domain.repeat

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/** Where in the month a monthly repeat lands. */
sealed interface MonthlyBy {
    /** The same date every month; `-1` means the last day. Months without that date are skipped (RFC 5545). */
    data class DayOfMonth(val day: Int) : MonthlyBy {
        init {
            require(day == LAST || day in 1..31) { "Day of month out of range: $day" }
        }
    }

    /** The [ordinal]-th [weekday] of the month (1–5, or `-1` for the last). Months without it are skipped. */
    data class NthWeekday(val ordinal: Int, val weekday: DayOfWeek) : MonthlyBy {
        init {
            require(ordinal == LAST || ordinal in 1..5) { "Weekday ordinal out of range: $ordinal" }
        }
    }

    fun dateIn(month: YearMonth): LocalDate? = when (this) {
        is DayOfMonth -> when {
            day == LAST -> month.atEndOfMonth()
            day <= month.lengthOfMonth() -> month.atDay(day)
            else -> null
        }
        is NthWeekday -> if (ordinal == LAST) {
            month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(weekday))
        } else {
            month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(ordinal, weekday)).takeIf { YearMonth.from(it) == month }
        }
    }

    companion object {
        const val LAST = -1
    }
}

sealed interface RepeatEnd {
    data object Never : RepeatEnd

    /** No occurrences after [date]. */
    data class OnDate(val date: LocalDate) : RepeatEnd

    /** How many occurrences are left, counting the current one. */
    data class AfterCount(val remaining: Int) : RepeatEnd {
        init {
            require(remaining in 1..RepeatRule.MAX_COUNT) { "Count out of range: $remaining" }
        }
    }
}

/**
 * How a task repeats. Like Google Tasks there is one open occurrence at a time: completing it creates
 * the next one (see [nextAfter]). Stored and synced as a subset of an RFC 5545 RRULE ([toRRule]/[parse]);
 * `COUNT` is kept as "occurrences left", decremented on each new occurrence.
 */
data class RepeatRule(
    val frequency: Frequency,
    val interval: Int = 1,
    /** WEEKLY only: the days it falls on (empty = the weekday of the current occurrence). */
    val weekdays: Set<DayOfWeek> = emptySet(),
    /** MONTHLY only: where in the month (null = the day of month of the current occurrence). */
    val monthlyBy: MonthlyBy? = null,
    val end: RepeatEnd = RepeatEnd.Never,
) {
    init {
        require(interval in 1..MAX_INTERVAL) { "Interval out of range: $interval" }
        require(weekdays.isEmpty() || frequency == Frequency.WEEKLY) { "Weekdays only apply to weekly rules" }
        require(monthlyBy == null || frequency == Frequency.MONTHLY) { "monthlyBy only applies to monthly rules" }
    }

    /**
     * The first occurrence strictly after [current], or null when the series has ended (by date, or
     * because no valid date exists, e.g. "every 12 months on the 31st" starting in April). [current] is
     * normally an occurrence of this series; for an arbitrary date, its week/month/year counts as the
     * aligned one.
     */
    fun nextAfter(current: LocalDate): LocalDate? {
        val next = when (frequency) {
            Frequency.DAILY -> current.plusDays(interval.toLong())
            Frequency.WEEKLY -> nextWeekly(current)
            Frequency.MONTHLY -> nextMonthly(current)
            Frequency.YEARLY -> nextYearly(current)
        } ?: return null
        val end = end
        return next.takeUnless { end is RepeatEnd.OnDate && it.isAfter(end.date) }
    }

    /**
     * The first occurrence of a series starting on [date]: [date] itself if it fits the pattern, else the
     * nearest later date that does (the interval only applies from there on).
     */
    fun firstOnOrAfter(date: LocalDate): LocalDate? {
        val first = when (frequency) {
            Frequency.DAILY, Frequency.YEARLY -> date
            Frequency.WEEKLY -> if (weekdays.isEmpty()) date else (0L..6L).map(date::plusDays).first { it.dayOfWeek in weekdays }
            Frequency.MONTHLY -> monthlyBy?.let { by ->
                generateSequence(YearMonth.from(date)) { it.plusMonths(1) }
                    .take(MAX_STEPS)
                    .firstNotNullOfOrNull { month -> by.dateIn(month)?.takeUnless { it.isBefore(date) } }
            } ?: date.takeIf { monthlyBy == null }
        } ?: return null
        val end = end
        return first.takeUnless { end is RepeatEnd.OnDate && it.isAfter(end.date) }
    }

    /** The rule the next occurrence carries, or null if the current occurrence is the last one. */
    fun forNextOccurrence(): RepeatRule? = when (val end = end) {
        is RepeatEnd.AfterCount -> if (end.remaining <= 1) null else copy(end = RepeatEnd.AfterCount(end.remaining - 1))
        else -> this
    }

    /** Inverse of [forNextOccurrence], for when a completion is undone. */
    fun forPreviousOccurrence(): RepeatRule = when (val end = end) {
        is RepeatEnd.AfterCount -> copy(end = RepeatEnd.AfterCount((end.remaining + 1).coerceAtMost(MAX_COUNT)))
        else -> this
    }

    /**
     * The rule after its occurrence is moved from [from] to [to]: a weekday or day of month that just
     * followed the old date follows the new one ("weekly on Monday" moved to a Tuesday → on Tuesday).
     */
    fun moved(from: LocalDate, to: LocalDate): RepeatRule = when {
        frequency == Frequency.WEEKLY && weekdays == setOf(from.dayOfWeek) -> copy(weekdays = setOf(to.dayOfWeek))
        frequency == Frequency.MONTHLY && monthlyBy == MonthlyBy.DayOfMonth(from.dayOfMonth) ->
            copy(monthlyBy = MonthlyBy.DayOfMonth(to.dayOfMonth))
        else -> this
    }

    /** A pinned-down copy: fills in the implicit weekday / day of month from [occurrence]. */
    fun anchoredTo(occurrence: LocalDate): RepeatRule = when (frequency) {
        Frequency.WEEKLY -> if (weekdays.isEmpty()) copy(weekdays = setOf(occurrence.dayOfWeek)) else this
        Frequency.MONTHLY -> if (monthlyBy == null) copy(monthlyBy = MonthlyBy.DayOfMonth(occurrence.dayOfMonth)) else this
        else -> this
    }

    fun toRRule(): String = buildList {
        add("FREQ=${frequency.name}")
        if (interval != 1) add("INTERVAL=$interval")
        if (frequency == Frequency.WEEKLY && weekdays.isNotEmpty()) {
            add("BYDAY=" + weekdays.sortedBy { it.value }.joinToString(",") { it.code })
        }
        when (val by = monthlyBy) {
            is MonthlyBy.DayOfMonth -> add("BYMONTHDAY=${by.day}")
            is MonthlyBy.NthWeekday -> add("BYDAY=${by.ordinal}${by.weekday.code}")
            null -> Unit
        }
        when (val end = end) {
            is RepeatEnd.OnDate -> add("UNTIL=" + end.date.format(DateTimeFormatter.BASIC_ISO_DATE))
            is RepeatEnd.AfterCount -> add("COUNT=${end.remaining}")
            RepeatEnd.Never -> Unit
        }
    }.joinToString(";")

    /** A short summary such as "Every 2 weeks on Mon, Thu" or "Monthly on the last Friday, until 31 Dec 2026". */
    fun describe(occurrence: LocalDate?, locale: Locale = Locale.getDefault()): String {
        val rule = if (occurrence != null) anchoredTo(occurrence) else this
        val everyWeekday = rule.frequency == Frequency.WEEKLY && rule.interval == 1 && rule.weekdays == WEEKDAYS
        val base = when {
            everyWeekday -> "Every weekday"
            rule.interval == 1 -> when (rule.frequency) {
                Frequency.DAILY -> "Daily"
                Frequency.WEEKLY -> "Weekly"
                Frequency.MONTHLY -> "Monthly"
                Frequency.YEARLY -> "Yearly"
            }
            else -> "Every ${rule.interval} " + when (rule.frequency) {
                Frequency.DAILY -> "days"
                Frequency.WEEKLY -> "weeks"
                Frequency.MONTHLY -> "months"
                Frequency.YEARLY -> "years"
            }
        }
        val detail = when (rule.frequency) {
            Frequency.DAILY -> ""
            Frequency.WEEKLY -> if (everyWeekday || rule.weekdays.isEmpty()) {
                ""
            } else {
                " on " + rule.weekdays.sortedBy { it.value }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
            }
            Frequency.MONTHLY -> when (val by = rule.monthlyBy) {
                is MonthlyBy.DayOfMonth -> if (by.day == MonthlyBy.LAST) " on the last day" else " on day ${by.day}"
                is MonthlyBy.NthWeekday -> " on the ${ordinalWord(by.ordinal)} ${by.weekday.getDisplayName(TextStyle.FULL, locale)}"
                null -> ""
            }
            Frequency.YEARLY -> occurrence?.let { " on " + it.format(DateTimeFormatter.ofPattern("MMM d", locale)) } ?: ""
        }
        val ending = when (val end = rule.end) {
            RepeatEnd.Never -> ""
            is RepeatEnd.OnDate -> ", until " + end.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
            is RepeatEnd.AfterCount -> if (end.remaining == 1) ", last time" else ", ${end.remaining} times"
        }
        return base + detail + ending
    }

    private fun nextWeekly(current: LocalDate): LocalDate {
        val days = weekdays.ifEmpty { setOf(current.dayOfWeek) }.sortedBy { it.value }
        // Later in the same week (weeks start on Monday, RFC 5545's default WKST).
        days.firstOrNull { it.value > current.dayOfWeek.value }?.let {
            return current.plusDays((it.value - current.dayOfWeek.value).toLong())
        }
        val weekStart = current.with(DayOfWeek.MONDAY).plusWeeks(interval.toLong())
        return weekStart.plusDays((days.first().value - 1).toLong())
    }

    private fun nextMonthly(current: LocalDate): LocalDate? {
        val by = monthlyBy ?: MonthlyBy.DayOfMonth(current.dayOfMonth)
        var month = YearMonth.from(current)
        by.dateIn(month)?.takeIf { it.isAfter(current) }?.let { return it }
        repeat(MAX_STEPS) {
            month = month.plusMonths(interval.toLong())
            by.dateIn(month)?.let { return it }
        }
        return null
    }

    private fun nextYearly(current: LocalDate): LocalDate? {
        var year = current.year
        repeat(MAX_STEPS) {
            year += interval
            val month = YearMonth.of(year, current.month)
            if (current.dayOfMonth <= month.lengthOfMonth()) return month.atDay(current.dayOfMonth)
        }
        return null
    }

    companion object {
        const val MAX_INTERVAL = 999
        const val MAX_COUNT = 9_999
        private const val MAX_STEPS = 48

        val WEEKDAYS: Set<DayOfWeek> = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )

        private val SUPPORTED_KEYS = setOf("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "UNTIL", "COUNT", "WKST")
        private val NTH_WEEKDAY = Regex("""([+-]?\d)([A-Z]{2})""")

        /**
         * Parses the RRULE subset written by [toRRule]. Returns null for anything else (unknown parts,
         * out-of-range values), so a rule from a newer app version is never misread.
         */
        fun parse(text: String?): RepeatRule? {
            if (text.isNullOrBlank()) return null
            val parts = text.trim().removePrefix("RRULE:").split(';').filter { it.isNotBlank() }.map { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) return null
                part.substring(0, eq).uppercase(Locale.ROOT) to part.substring(eq + 1).uppercase(Locale.ROOT)
            }
            if (parts.map { it.first }.toSet().size != parts.size) return null
            val map = parts.toMap()
            if (!SUPPORTED_KEYS.containsAll(map.keys)) return null
            if (map["WKST"].let { it != null && it != "MO" }) return null

            val frequency = Frequency.entries.firstOrNull { it.name == map["FREQ"] } ?: return null
            val interval = map["INTERVAL"]?.let { it.toIntOrNull() ?: return null } ?: 1
            if (interval !in 1..MAX_INTERVAL) return null

            var weekdays = emptySet<DayOfWeek>()
            var monthlyBy: MonthlyBy? = null
            map["BYDAY"]?.let { byDay ->
                when (frequency) {
                    Frequency.WEEKLY -> weekdays = byDay.split(',').map { dayOf(it) ?: return null }.toSet()
                    Frequency.MONTHLY -> {
                        val match = NTH_WEEKDAY.matchEntire(byDay) ?: return null
                        val ordinal = match.groupValues[1].toInt()
                        if (ordinal != MonthlyBy.LAST && ordinal !in 1..5) return null
                        monthlyBy = MonthlyBy.NthWeekday(ordinal, dayOf(match.groupValues[2]) ?: return null)
                    }
                    else -> return null
                }
            }
            map["BYMONTHDAY"]?.let { value ->
                if (frequency != Frequency.MONTHLY || monthlyBy != null) return null
                val day = value.toIntOrNull() ?: return null
                if (day != MonthlyBy.LAST && day !in 1..31) return null
                monthlyBy = MonthlyBy.DayOfMonth(day)
            }
            val until = map["UNTIL"]
            val count = map["COUNT"]
            val end = when {
                until != null && count != null -> return null
                until != null -> RepeatEnd.OnDate(
                    runCatching { LocalDate.parse(until.take(8), DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() ?: return null,
                )
                count != null -> RepeatEnd.AfterCount(count.toIntOrNull()?.takeIf { it in 1..MAX_COUNT } ?: return null)
                else -> RepeatEnd.Never
            }
            return RepeatRule(frequency, interval, weekdays, monthlyBy, end)
        }

        private val DayOfWeek.code: String get() = name.take(2)

        private fun dayOf(code: String): DayOfWeek? = DayOfWeek.entries.firstOrNull { it.name.take(2) == code }

        private fun ordinalWord(ordinal: Int): String = when (ordinal) {
            1 -> "first"
            2 -> "second"
            3 -> "third"
            4 -> "fourth"
            5 -> "fifth"
            else -> "last"
        }
    }
}
