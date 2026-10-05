package dev.suyash.dot.core.ui

import android.content.Context
import android.text.format.DateFormat
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Human-friendly, locale-aware date/time labels ("Today, 8:00 PM", "Sat 4 Oct, 09:00"). */
class TimeFormats(private val is24Hour: Boolean, private val locale: Locale) {

    private val time: DateTimeFormatter =
        DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale)
    private val weekdayDay: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    private val fullDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)

    fun time(at: ZonedDateTime): String = time.format(at)

    /** Clock face for dot-matrix displays, always zero-padded: "08:00". */
    fun clockFace(at: ZonedDateTime): String =
        if (is24Hour) "%02d:%02d".format(at.hour, at.minute) else "%02d:%02d".format(at.hour.to12(), at.minute)

    fun meridiem(at: ZonedDateTime): String? = if (is24Hour) null else if (at.hour < 12) "AM" else "PM"

    fun day(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> if (date.year == today.year) weekdayDay.format(date) else fullDate.format(date)
    }

    fun dayAndTime(at: ZonedDateTime, now: ZonedDateTime): String =
        "${day(at.toLocalDate(), now.toLocalDate())}, ${time(at)}"

    /** Compact label for chips, e.g. "Sat 4 Oct · 9:00 AM". */
    fun chip(at: ZonedDateTime, now: ZonedDateTime): String {
        val date = at.toLocalDate()
        val dayLabel = when (date) {
            now.toLocalDate() -> "Today"
            now.toLocalDate().plusDays(1) -> "Tomorrow"
            else -> weekdayDay.format(date)
        }
        return "$dayLabel · ${time(at)}"
    }

    private fun Int.to12(): Int = when (val h = this % 12) {
        0 -> 12
        else -> h
    }

    companion object {
        fun from(context: Context): TimeFormats {
            val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
            return TimeFormats(DateFormat.is24HourFormat(context), locale)
        }
    }
}
