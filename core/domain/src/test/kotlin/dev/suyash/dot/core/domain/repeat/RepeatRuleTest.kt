package dev.suyash.dot.core.domain.repeat

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.util.Locale

class RepeatRuleTest {

    private fun d(text: String) = LocalDate.parse(text)

    private fun RepeatRule.occurrences(start: String, count: Int): List<LocalDate> =
        generateSequence(d(start)) { nextAfter(it) }.take(count).toList()

    @Test
    fun `daily with an interval`() {
        assertThat(RepeatRule(Frequency.DAILY).nextAfter(d("2026-10-05"))).isEqualTo(d("2026-10-06"))
        assertThat(RepeatRule(Frequency.DAILY, interval = 3).occurrences("2026-12-30", 3))
            .containsExactly(d("2026-12-30"), d("2027-01-02"), d("2027-01-05")).inOrder()
    }

    @Test
    fun `weekly on several days walks through the week, then jumps by the interval`() {
        val rule = RepeatRule(Frequency.WEEKLY, interval = 2, weekdays = setOf(MONDAY, WEDNESDAY))
        // 2026-10-05 is a Monday.
        assertThat(rule.occurrences("2026-10-05", 5)).containsExactly(
            d("2026-10-05"), d("2026-10-07"), d("2026-10-19"), d("2026-10-21"), d("2026-11-02"),
        ).inOrder()
    }

    @Test
    fun `weekly without days repeats on the occurrence's own weekday`() {
        assertThat(RepeatRule(Frequency.WEEKLY).nextAfter(d("2026-10-08"))).isEqualTo(d("2026-10-15"))
    }

    @Test
    fun `every weekday skips the weekend`() {
        val rule = RepeatRule(Frequency.WEEKLY, weekdays = RepeatRule.WEEKDAYS)
        assertThat(rule.nextAfter(d("2026-10-09"))).isEqualTo(d("2026-10-12")) // Fri -> Mon
        assertThat(rule.nextAfter(d("2026-10-06"))).isEqualTo(d("2026-10-07")) // Tue -> Wed
    }

    @Test
    fun `monthly on the 31st skips short months, like RFC 5545`() {
        val rule = RepeatRule(Frequency.MONTHLY, monthlyBy = MonthlyBy.DayOfMonth(31))
        assertThat(rule.occurrences("2027-01-31", 3)).containsExactly(d("2027-01-31"), d("2027-03-31"), d("2027-05-31")).inOrder()
    }

    @Test
    fun `monthly on the last day and on the last friday`() {
        val lastDay = RepeatRule(Frequency.MONTHLY, monthlyBy = MonthlyBy.DayOfMonth(MonthlyBy.LAST))
        assertThat(lastDay.occurrences("2027-01-31", 3)).containsExactly(d("2027-01-31"), d("2027-02-28"), d("2027-03-31")).inOrder()

        val lastFriday = RepeatRule(Frequency.MONTHLY, monthlyBy = MonthlyBy.NthWeekday(MonthlyBy.LAST, FRIDAY))
        assertThat(lastFriday.nextAfter(d("2026-10-30"))).isEqualTo(d("2026-11-27"))
    }

    @Test
    fun `monthly on the second tuesday, every three months`() {
        val rule = RepeatRule(Frequency.MONTHLY, interval = 3, monthlyBy = MonthlyBy.NthWeekday(2, TUESDAY))
        assertThat(rule.nextAfter(d("2026-10-13"))).isEqualTo(d("2027-01-12"))
    }

    @Test
    fun `fifth weekday only exists in some months`() {
        val rule = RepeatRule(Frequency.MONTHLY, monthlyBy = MonthlyBy.NthWeekday(5, THURSDAY))
        assertThat(rule.nextAfter(d("2026-10-29"))).isEqualTo(d("2026-12-31"))
    }

    @Test
    fun `monthly without a day keeps the occurrence's day, and finds a later day in the same month`() {
        assertThat(RepeatRule(Frequency.MONTHLY).nextAfter(d("2026-10-15"))).isEqualTo(d("2026-11-15"))
        val on20th = RepeatRule(Frequency.MONTHLY, monthlyBy = MonthlyBy.DayOfMonth(20))
        assertThat(on20th.nextAfter(d("2026-10-05"))).isEqualTo(d("2026-10-20"))
    }

    @Test
    fun `yearly on 29 February waits for a leap year`() {
        assertThat(RepeatRule(Frequency.YEARLY).nextAfter(d("2028-02-29"))).isEqualTo(d("2032-02-29"))
        assertThat(RepeatRule(Frequency.YEARLY, interval = 2).nextAfter(d("2026-10-05"))).isEqualTo(d("2028-10-05"))
    }

    @Test
    fun `an end date stops the series`() {
        val rule = RepeatRule(Frequency.DAILY, end = RepeatEnd.OnDate(d("2026-10-06")))
        assertThat(rule.nextAfter(d("2026-10-05"))).isEqualTo(d("2026-10-06"))
        assertThat(rule.nextAfter(d("2026-10-06"))).isNull()
    }

    @Test
    fun `a count is used up one occurrence at a time`() {
        val rule = RepeatRule(Frequency.DAILY, end = RepeatEnd.AfterCount(2))
        val next = rule.forNextOccurrence()
        assertThat(next?.end).isEqualTo(RepeatEnd.AfterCount(1))
        assertThat(next?.forNextOccurrence()).isNull()
        assertThat(RepeatRule(Frequency.DAILY).forNextOccurrence()).isEqualTo(RepeatRule(Frequency.DAILY))
    }

    @Test
    fun `undoing a completion gives the occurrence back`() {
        val rule = RepeatRule(Frequency.DAILY, end = RepeatEnd.AfterCount(2))
        assertThat(rule.forNextOccurrence()?.forPreviousOccurrence()).isEqualTo(rule)
    }

    @Test
    fun `moving an occurrence moves an implicit weekday or day of month, but not an explicit pattern`() {
        val monday = d("2026-10-05")
        val tuesday = d("2026-10-06")
        assertThat(RepeatRule(Frequency.WEEKLY, weekdays = setOf(MONDAY)).moved(monday, tuesday).weekdays).containsExactly(TUESDAY)
        val twoDays = RepeatRule(Frequency.WEEKLY, weekdays = setOf(MONDAY, THURSDAY))
        assertThat(twoDays.moved(monday, tuesday)).isEqualTo(twoDays)
        val on5th = RepeatRule(Frequency.MONTHLY, monthlyBy = MonthlyBy.DayOfMonth(5))
        assertThat(on5th.moved(monday, tuesday).monthlyBy).isEqualTo(MonthlyBy.DayOfMonth(6))
    }

    @Test
    fun `an impossible rule ends instead of looping forever`() {
        // Every 12 months on the 31st, starting in a 30-day month: there is no next occurrence.
        val rule = RepeatRule(Frequency.MONTHLY, interval = 12, monthlyBy = MonthlyBy.DayOfMonth(31))
        assertThat(rule.nextAfter(d("2026-04-30"))).isNull()
    }

    @Test
    fun `first occurrence of a new series`() {
        val mondays = RepeatRule(Frequency.WEEKLY, interval = 2, weekdays = setOf(MONDAY))
        assertThat(mondays.firstOnOrAfter(d("2026-10-07"))).isEqualTo(d("2026-10-12")) // Wed -> next Mon, not +2 weeks
        assertThat(mondays.firstOnOrAfter(d("2026-10-05"))).isEqualTo(d("2026-10-05"))
        val on15th = RepeatRule(Frequency.MONTHLY, monthlyBy = MonthlyBy.DayOfMonth(15))
        assertThat(on15th.firstOnOrAfter(d("2026-10-16"))).isEqualTo(d("2026-11-15"))
        assertThat(RepeatRule(Frequency.DAILY).firstOnOrAfter(d("2026-10-07"))).isEqualTo(d("2026-10-07"))
    }

    @Test
    fun `RRULE round trip`() {
        val rules = listOf(
            RepeatRule(Frequency.DAILY),
            RepeatRule(Frequency.WEEKLY, interval = 2, weekdays = setOf(WEDNESDAY, MONDAY), end = RepeatEnd.AfterCount(5)),
            RepeatRule(Frequency.MONTHLY, monthlyBy = MonthlyBy.DayOfMonth(MonthlyBy.LAST)),
            RepeatRule(Frequency.MONTHLY, monthlyBy = MonthlyBy.NthWeekday(2, TUESDAY), end = RepeatEnd.OnDate(d("2027-12-31"))),
            RepeatRule(Frequency.YEARLY, interval = 3),
        )
        for (rule in rules) assertThat(RepeatRule.parse(rule.toRRule())).isEqualTo(rule)
        assertThat(rules[1].toRRule()).isEqualTo("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=5")
        assertThat(rules[3].toRRule()).isEqualTo("FREQ=MONTHLY;BYDAY=2TU;UNTIL=20271231")
    }

    @Test
    fun `parse accepts the RRULE prefix and a timestamped UNTIL`() {
        assertThat(RepeatRule.parse("RRULE:FREQ=daily;UNTIL=20261231T235959Z"))
            .isEqualTo(RepeatRule(Frequency.DAILY, end = RepeatEnd.OnDate(d("2026-12-31"))))
    }

    @Test
    fun `parse rejects anything it can't honour exactly`() {
        listOf(
            null, "", "FREQ=HOURLY", "INTERVAL=2", "FREQ=DAILY;INTERVAL=0", "FREQ=DAILY;INTERVAL=1000",
            "FREQ=DAILY;BYSETPOS=1", "FREQ=WEEKLY;BYDAY=XX", "FREQ=MONTHLY;BYDAY=6MO", "FREQ=MONTHLY;BYMONTHDAY=32",
            "FREQ=DAILY;COUNT=0", "FREQ=DAILY;COUNT=2;UNTIL=20261231", "FREQ=DAILY;FREQ=WEEKLY", "FREQ=WEEKLY;WKST=SU",
            "FREQ=DAILY;BYDAY=MO", "garbage",
        ).forEach { assertThat(RepeatRule.parse(it)).isNull() }
    }

    @Test
    fun `describe reads naturally`() {
        val en = Locale.UK
        val monday = d("2026-10-05")
        assertThat(RepeatRule(Frequency.DAILY).describe(monday, en)).isEqualTo("Daily")
        assertThat(RepeatRule(Frequency.WEEKLY, weekdays = RepeatRule.WEEKDAYS).describe(monday, en)).isEqualTo("Every weekday")
        assertThat(RepeatRule(Frequency.WEEKLY).describe(monday, en)).isEqualTo("Weekly on Mon")
        assertThat(RepeatRule(Frequency.WEEKLY, interval = 2, weekdays = setOf(THURSDAY, MONDAY)).describe(monday, en))
            .isEqualTo("Every 2 weeks on Mon, Thu")
        assertThat(RepeatRule(Frequency.MONTHLY, monthlyBy = MonthlyBy.NthWeekday(MonthlyBy.LAST, FRIDAY)).describe(monday, en))
            .isEqualTo("Monthly on the last Friday")
        assertThat(RepeatRule(Frequency.MONTHLY, end = RepeatEnd.AfterCount(3)).describe(monday, en))
            .isEqualTo("Monthly on day 5, 3 times")
        assertThat(RepeatRule(Frequency.YEARLY).describe(monday, en)).isEqualTo("Yearly on Oct 5")
    }
}
