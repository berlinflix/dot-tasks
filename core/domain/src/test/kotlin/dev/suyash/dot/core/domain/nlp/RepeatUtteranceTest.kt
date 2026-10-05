package dev.suyash.dot.core.domain.nlp

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Repeating phrases. "now" is Friday 2026-10-02 10:00; day parts as in utterances_en.tsv. */
class RepeatUtteranceTest {

    private val now = ZonedDateTime.of(LocalDateTime.parse("2026-10-02T10:00"), ZoneId.of("Asia/Kolkata"))
    private val parser = ReminderUtteranceParser()

    private data class Case(val input: String, val title: String, val at: String, val rrule: String?)

    private val cases = listOf(
        Case("take vitamins every day at 9 am", "Take vitamins", "2026-10-03T09:00", "FREQ=DAILY"),
        Case("remind me every monday at 8 to go to the gym", "Go to the gym", "2026-10-05T08:00", "FREQ=WEEKLY;BYDAY=MO"),
        Case("water plants every other day", "Water plants", "2026-10-03T09:00", "FREQ=DAILY;INTERVAL=2"),
        Case("pay rent on the 1st of every month", "Pay rent", "2026-11-01T09:00", "FREQ=MONTHLY;BYMONTHDAY=1"),
        Case("team sync every 2 weeks on tuesday at 3pm", "Team sync", "2026-10-06T15:00", "FREQ=WEEKLY;INTERVAL=2;BYDAY=TU"),
        Case("standup every weekday at 9:30", "Standup", "2026-10-05T09:30", "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"),
        Case("call grandma every sunday evening", "Call grandma", "2026-10-04T18:00", "FREQ=WEEKLY;BYDAY=SU"),
        Case("take out the trash on mondays and thursdays", "Take out the trash", "2026-10-05T09:00", "FREQ=WEEKLY;BYDAY=MO,TH"),
        Case("pay credit card bill monthly on the 15th", "Pay credit card bill", "2026-10-15T09:00", "FREQ=MONTHLY;BYMONTHDAY=15"),
        Case("book club every last friday of the month at 7 pm", "Book club", "2026-10-30T19:00", "FREQ=MONTHLY;BYDAY=-1FR"),
        Case("renew insurance every year on 12 march", "Renew insurance", "2027-03-12T09:00", "FREQ=YEARLY"),
        Case("meditate every morning", "Meditate", "2026-10-03T09:00", "FREQ=DAILY"),
        Case("gym every other friday", "Gym", "2026-10-09T09:00", "FREQ=WEEKLY;INTERVAL=2;BYDAY=FR"),
        Case("pay bills on the last day of every month", "Pay bills", "2026-10-31T09:00", "FREQ=MONTHLY;BYMONTHDAY=-1"),
        Case("every 3 months change the water filter", "Change the water filter", "2026-10-03T09:00", "FREQ=MONTHLY;INTERVAL=3;BYMONTHDAY=3"),
        Case("budget review every second tuesday of the month", "Budget review", "2026-10-13T09:00", "FREQ=MONTHLY;BYDAY=2TU"),
        Case("take vitamins daily", "Take vitamins", "2026-10-03T09:00", "FREQ=DAILY"),
        Case("yoga on weekends at 7", "Yoga", "2026-10-03T07:00", "FREQ=WEEKLY;BYDAY=SA,SU"),
        Case("birthday call every year on oct 2 at 9", "Birthday call", "2027-10-02T09:00", "FREQ=YEARLY"),
        // Not repeats.
        Case("prepare weekly report tomorrow", "Prepare weekly report", "2026-10-03T09:00", null),
        Case("buy milk tomorrow 8 am", "Buy milk", "2026-10-03T08:00", null),
    )

    @Test
    fun `repeat phrases`() {
        val failures = cases.mapNotNull { case ->
            val result = parser.parse(case.input, now)
            val problems = buildList {
                if (result.title != case.title) add("title '${result.title}' != '${case.title}'")
                if (result.at?.toString() != case.at) add("at ${result.at} != ${case.at}")
                if (result.repeat?.toRRule() != case.rrule) add("rule ${result.repeat?.toRRule()} != ${case.rrule}")
            }
            if (problems.isEmpty()) null else "\"${case.input}\": ${problems.joinToString("; ")}"
        }
        assertWithMessage("${failures.size}/${cases.size} failed:\n" + failures.joinToString("\n")).that(failures).isEmpty()
    }
}
