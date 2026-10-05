package dev.suyash.dot.core.domain.nlp

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Runs every case in `utterances_en.tsv` and reports all failures at once. */
class ReminderUtteranceParserTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val defaultNow = ZonedDateTime.of(LocalDateTime.parse("2026-10-02T10:00"), zone)
    private val parser = ReminderUtteranceParser()

    private data class Case(val line: Int, val now: ZonedDateTime, val input: String, val title: String, val at: String, val ring: Boolean)

    private fun cases(): List<Case> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("utterances_en.tsv")) { "corpus missing" }
        return stream.bufferedReader().readLines().mapIndexedNotNull { index, raw ->
            if (raw.isBlank() || raw.startsWith("#")) return@mapIndexedNotNull null
            val cols = raw.split('\t')
            require(cols.size == 5) { "Line ${index + 1}: expected 5 columns, got ${cols.size}: $raw" }
            Case(
                line = index + 1,
                now = if (cols[0] == "-") defaultNow else ZonedDateTime.of(LocalDateTime.parse(cols[0]), zone),
                input = cols[1],
                title = cols[2],
                at = cols[3],
                ring = cols[4] == "y",
            )
        }
    }

    @Test
    fun `golden corpus`() {
        val all = cases()
        val failures = all.mapNotNull { case ->
            val result = parser.parse(case.input, case.now)
            val actualAt = result.at?.toString() ?: "-"
            val problems = buildList {
                if (result.title != case.title) add("title '${result.title}' != '${case.title}'")
                if (actualAt != case.at) add("at $actualAt != ${case.at}")
                if (result.ringRequested != case.ring) add("ring ${result.ringRequested} != ${case.ring}")
            }
            if (problems.isEmpty()) null else "line ${case.line} \"${case.input}\": ${problems.joinToString("; ")}"
        }
        assertWithMessage("${failures.size}/${all.size} cases failed:\n" + failures.joinToString("\n"))
            .that(failures).isEmpty()
    }

    @Test
    fun `month-day order for US users`() {
        val us = ReminderUtteranceParser(dateOrder = DateOrder.MONTH_DAY)
        val result = us.parse("dentist on 3/10 at 9 am", defaultNow)
        assertWithMessage("3/10 in M/D order").that(result.at).isEqualTo(LocalDateTime.parse("2027-03-10T09:00"))
    }

    @Test
    fun `empty input yields nothing`() {
        val result = parser.parse("   ", defaultNow)
        assertWithMessage("title").that(result.title).isEmpty()
        assertWithMessage("at").that(result.at).isNull()
    }
}
