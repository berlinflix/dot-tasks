package dev.suyash.dot.core.domain.nlp

import dev.suyash.dot.core.domain.repeat.Frequency
import dev.suyash.dot.core.domain.repeat.MonthlyBy
import dev.suyash.dot.core.domain.repeat.RepeatRule
import dev.suyash.dot.core.domain.time.DayParts
import dev.suyash.dot.core.domain.time.SnoozeCalculator
import dev.suyash.dot.core.domain.time.SnoozePreset
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** How ambiguous numeric dates like 5/10 are read. */
enum class DateOrder { DAY_MONTH, MONTH_DAY }

data class ParsedUtterance(
    /** The task text with command words and time phrases removed, in the user's original casing. */
    val title: String,
    /** When to remind, in the device's local time; `null` if no date/time was said. */
    val at: LocalDateTime?,
    /** True when only a day was said ("tomorrow") and the time fell back to a default. */
    val timeDefaulted: Boolean,
    /** True when the phrasing asked for a reminder/alarm ("remind me…", "wake me…", "set an alarm…"). */
    val ringRequested: Boolean,
    /** The recognized time phrases, for highlighting in the confirmation UI. */
    val timePhrases: List<String>,
    /** Set for "every Monday", "daily", "on the 1st of every month"…; [at] is then its first occurrence. */
    val repeat: RepeatRule? = null,
)

/**
 * Parses spoken or typed task phrases such as
 * "set a reminder for buy milk tomorrow 8 am" → title "Buy milk", tomorrow 08:00.
 *
 * Pure, deterministic and fully on-device. English only for now. Resolution is future-biased:
 * a bare "at 6" means the next 6 o'clock; "tomorrow at 8" means 8 AM.
 */
class ReminderUtteranceParser(
    private val dayParts: DayParts = DayParts(),
    private val dateOrder: DateOrder = DateOrder.DAY_MONTH,
) {
    private val snooze = SnoozeCalculator(dayParts)

    fun parse(input: String, now: ZonedDateTime): ParsedUtterance {
        val tokens = tokenize(input)
        if (tokens.isEmpty()) return ParsedUtterance("", null, false, false, emptyList())

        val consumed = BooleanArray(tokens.size) { tokens[it].norm.isEmpty() }
        val command = consumeCommandPrefix(tokens, consumed)
        consumeTrailingPoliteness(tokens, consumed)

        val text = NormalizedText(tokens)
        val state = TemporalState()
        val phrases = mutableListOf<String>()

        for (extractor in extractors) {
            for (match in extractor.regex.findAll(text.value)) {
                val tokenRange = text.tokenRange(match.range) ?: continue
                if (tokenRange.any { consumed[it] && tokens[it].norm.isNotEmpty() }) continue
                if (!extractor.apply(match, state, now)) continue
                tokenRange.forEach { consumed[it] = true }
                phrases += tokenRange.joinToString(" ") { tokens[it].original }
            }
        }

        var (at, defaulted) = resolve(state, now, preferMorning = command.wake)
        val repeat = state.repeat?.let { rule ->
            val (first, timeDefaulted) = firstOccurrence(rule, state, now, at, command.wake) ?: return@let null
            at = first
            defaulted = timeDefaulted
            rule.anchoredTo(first.toLocalDate())
        }
        return ParsedUtterance(
            title = buildTitle(tokens, consumed, command),
            at = at,
            timeDefaulted = defaulted,
            ringRequested = command.ringRequested,
            timePhrases = phrases,
            repeat = repeat,
        )
    }

    /**
     * When a new series starts: the said time (a bare hour reads like on a future day, so "every day at
     * 8" is 8 AM), else the day part, else the morning; on the first date that fits the rule and is
     * still ahead.
     */
    private fun firstOccurrence(
        rule: RepeatRule,
        s: TemporalState,
        now: ZonedDateTime,
        resolved: LocalDateTime?,
        preferMorning: Boolean,
    ): Pair<LocalDateTime, Boolean>? {
        val hour = s.hour
        val part = s.dayPart
        val time = when {
            s.relative != null -> resolved?.toLocalTime()
            hour != null && s.midnight -> LocalTime.MIDNIGHT
            hour != null -> preferredTime(s, hour, candidateTimes(s, hour, preferMorning), preferMorning)
            part != null -> timeOf(part)
            else -> null
        }
        val nowLocal = now.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
        val at = time ?: dayParts.morning
        var date = rule.firstOnOrAfter(s.date ?: resolved?.toLocalDate() ?: now.toLocalDate()) ?: return null
        if (!date.atTime(at).isAfter(nowLocal)) {
            // That slot has passed, so the series starts at the next one.
            date = when {
                s.date == null -> rule.firstOnOrAfter(date.plusDays(1)) // no day said: from tomorrow
                rule.frequency == Frequency.YEARLY -> rule.nextAfter(date) // that date next year
                else -> rule.anchoredTo(date).firstOnOrAfter(date.plusDays(1)) // next slot on the said day(s)
            } ?: return null
        }
        return date.atTime(at) to (time == null)
    }

    // ---------------------------------------------------------------------------------------------
    // Tokenization & normalization
    // ---------------------------------------------------------------------------------------------

    private data class Token(val original: String, val norm: String)

    private fun tokenize(input: String): List<Token> {
        val cleaned = input
            .replace('’', '\'')
            .replace('“', ' ').replace('”', ' ').replace('"', ' ')
            .replace(Regex("(?i)\\b([ap])\\.\\s?m\\b\\.?"), "$1m")
            .trim()
        if (cleaned.isEmpty()) return emptyList()
        val tokens = cleaned.split(Regex("\\s+")).map { Token(it, normalizeWord(it)) }
        return mergeCompoundNumbers(tokens)
    }

    private fun normalizeWord(word: String): String {
        val w = word.lowercase(Locale.ROOT)
            .trim('\'', '(', ')', '[', ']')
            .trimEnd(',', '.', '!', '?', ';')
        if (w == "o'clock" || w == "oclock") return ""
        if (w == "@") return "at"
        NUMBER_WORDS[w]?.let { return it.toString() }
        return w
    }

    /** "twenty five" → "25" (keeping both original words for the phrase list). */
    private fun mergeCompoundNumbers(tokens: List<Token>): List<Token> {
        val out = ArrayList<Token>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val next = tokens.getOrNull(i + 1)
            val tens = NUMBER_WORDS[t.original.lowercase(Locale.ROOT)]
            val unit = next?.let { NUMBER_WORDS[it.original.lowercase(Locale.ROOT)] }
            if (tens != null && unit != null && tens in COMPOUND_TENS && unit in 1..9) {
                out += Token("${t.original} ${next.original}", (tens + unit).toString())
                i += 2
            } else {
                out += t
                i++
            }
        }
        return out
    }

    /** The normalized tokens joined by single spaces, with a map back to token indices. */
    private class NormalizedText(tokens: List<Token>) {
        val value: String
        private val starts = IntArray(tokens.size)
        private val ends = IntArray(tokens.size)

        init {
            val sb = StringBuilder()
            tokens.forEachIndexed { index, token ->
                if (token.norm.isEmpty()) {
                    starts[index] = -1
                    ends[index] = -1
                } else {
                    if (sb.isNotEmpty()) sb.append(' ')
                    starts[index] = sb.length
                    sb.append(token.norm)
                    ends[index] = sb.length
                }
            }
            value = sb.toString()
        }

        /** Indices of the tokens overlapped by [range], extended over trailing filler tokens ("o'clock"). */
        fun tokenRange(range: IntRange): IntRange? {
            var first = -1
            var last = -1
            for (i in starts.indices) {
                if (starts[i] < 0) continue
                if (starts[i] <= range.last && ends[i] - 1 >= range.first) {
                    if (first < 0) first = i
                    last = i
                }
            }
            if (first < 0) return null
            while (last + 1 < starts.size && starts[last + 1] < 0) last++
            return first..last
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Command phrases ("remind me to …")
    // ---------------------------------------------------------------------------------------------

    private data class CommandResult(val ringRequested: Boolean, val wake: Boolean, val defaultTitle: String)

    private fun consumeCommandPrefix(tokens: List<Token>, consumed: BooleanArray): CommandResult {
        var index = 0
        while (index < tokens.size && tokens[index].norm in LEADING_FILLERS) {
            consumed[index] = true
            index++
        }
        for (prefix in COMMAND_PREFIXES) {
            if (index + prefix.words.size > tokens.size) continue
            if (prefix.words.indices.all { tokens[index + it].norm == prefix.words[it] }) {
                for (i in prefix.words.indices) consumed[index + i] = true
                return CommandResult(prefix.ring, prefix.wake, prefix.defaultTitle)
            }
        }
        return CommandResult(ringRequested = false, wake = false, defaultTitle = "")
    }

    private fun consumeTrailingPoliteness(tokens: List<Token>, consumed: BooleanArray) {
        var i = tokens.lastIndex
        if (i >= 1 && tokens[i - 1].norm == "thank" && tokens[i].norm == "you") {
            consumed[i] = true
            consumed[i - 1] = true
            i -= 2
        }
        while (i >= 0 && tokens[i].norm in TRAILING_FILLERS) {
            consumed[i] = true
            i--
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Temporal extraction
    // ---------------------------------------------------------------------------------------------

    private enum class DayPart { MORNING, AFTERNOON, EVENING, NIGHT }
    private enum class Meridiem { AM, PM }
    private enum class DateSource { EXPLICIT, DAY_WORD, WEEKDAY, RELATIVE_PERIOD }

    private class TemporalState {
        var relative: Duration? = null
        var date: LocalDate? = null
        var dateSource: DateSource? = null
        var hour: Int? = null
        var minute: Int = 0
        var meridiem: Meridiem? = null
        var twentyFourHour = false
        var dayPart: DayPart? = null
        var midnight = false
        var repeat: RepeatRule? = null
    }

    private class Extractor(
        pattern: String,
        val apply: (MatchResult, TemporalState, ZonedDateTime) -> Boolean,
    ) {
        val regex = Regex(pattern)
    }

    /** Ordered from most to least specific; a later extractor never re-uses tokens an earlier one took. */
    private val extractors: List<Extractor> = listOf(
        // ---- Repeats first, so "every monday" is a series rather than next Monday. ----
        // every other friday / every second tuesday (without "of the month": fortnightly)
        Extractor("""\b(?:every|each)\s+(?:other|second|alternate)\s+($WEEKDAY_WORDS)\b(?!\s+of\s+(?:the|every|each)\s+month)""") { m, s, now ->
            val day = WEEKDAY_NAMES[m.groupValues[1]] ?: return@Extractor false
            setRepeat(s, now, RepeatRule(Frequency.WEEKLY, interval = 2, weekdays = setOf(day)))
        },
        // every 2nd tuesday (of the month) / the last friday of every month
        Extractor("""\b(?:every|each)\s+($ORDINALS)\s+($WEEKDAY_WORDS)(?:\s+of\s+(?:the|every|each)\s+month)?\b""") { m, s, now ->
            setRepeat(s, now, monthlyNth(m.groupValues[1], m.groupValues[2]) ?: return@Extractor false)
        },
        Extractor("""\b(?:on\s+)?(?:the\s+)?($ORDINALS)\s+($WEEKDAY_WORDS)\s+of\s+(?:every|each)\s+month\b""") { m, s, now ->
            setRepeat(s, now, monthlyNth(m.groupValues[1], m.groupValues[2]) ?: return@Extractor false)
        },
        // (on the) last day of every month
        Extractor("""\b(?:(?:on\s+)?(?:the\s+)?last\s+day\s+of\s+(?:every|each)\s+month|(?:every|each)\s+last\s+day\s+of\s+the\s+month)\b""") { _, s, now ->
            setRepeat(s, now, RepeatRule(Frequency.MONTHLY, monthlyBy = MonthlyBy.DayOfMonth(MonthlyBy.LAST)))
        },
        // every 5th (of the month) / the 1st of every month / every month on the 3rd
        Extractor("""\b(?:every|each)\s+(\d{1,2})(?:st|nd|rd|th)\b(?:\s+of\s+(?:the|every|each)\s+month\b)?""") { m, s, now ->
            setRepeat(s, now, monthlyOn(m.groupValues[1]) ?: return@Extractor false)
        },
        Extractor("""\b(?:on\s+)?(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)\s+of\s+(?:every|each)\s+month\b""") { m, s, now ->
            setRepeat(s, now, monthlyOn(m.groupValues[1]) ?: return@Extractor false)
        },
        Extractor("""\b(?:every|each)\s+month\s+on\s+the\s+(\d{1,2})(?:st|nd|rd|th)\b""") { m, s, now ->
            setRepeat(s, now, monthlyOn(m.groupValues[1]) ?: return@Extractor false)
        },
        // every weekday / on weekdays / workdays
        Extractor(
            """\b(?:(?:every|each)\s+(?:week\s?day|work\s?day|working\s+day|business\s+day)s?|on\s+(?:week\s?days|work\s?days)|week\s?days)\b""",
        ) { _, s, now ->
            setRepeat(s, now, RepeatRule(Frequency.WEEKLY, weekdays = RepeatRule.WEEKDAYS))
        },
        // every weekend / on weekends
        Extractor("""\b(?:(?:every|each)\s+weekends?|on\s+weekends|weekends)\b""") { _, s, now ->
            setRepeat(s, now, RepeatRule(Frequency.WEEKLY, weekdays = dayParts.weekendDays))
        },
        // every monday and thursday / each fri / on mondays
        Extractor("""\b(?:every|each)\s+($WEEKDAY_WORD_S(?:\s+(?:and\s+|or\s+|&\s+)?$WEEKDAY_WORD_S)*)\b""") { m, s, now ->
            setRepeat(s, now, weeklyOn(m.groupValues[1]) ?: return@Extractor false)
        },
        Extractor("""\bon\s+((?:$WEEKDAYS)s(?:\s+(?:and\s+|or\s+|&\s+)?(?:$WEEKDAYS)s)*)\b""") { m, s, now ->
            setRepeat(s, now, weeklyOn(m.groupValues[1]) ?: return@Extractor false)
        },
        // every morning / each evening
        Extractor("""\b(?:every|each)\s+(morning|afternoon|evening|night)\b""") { m, s, now ->
            val part = dayPartOf(m.groupValues[1])
            if (s.dayPart != null && s.dayPart != part) return@Extractor false
            if (!setRepeat(s, now, RepeatRule(Frequency.DAILY))) return@Extractor false
            s.dayPart = part
            true
        },
        // every day / every other week / every 3 months / each year
        Extractor("""\b(?:every|each)\s+(?:(other|second|third|\d{1,3})\s+)?(day|week|month|year)s?\b""") { m, s, now ->
            val interval = when (val n = m.groupValues[1]) {
                "" -> 1
                "other", "second" -> 2
                "third" -> 3
                else -> n.toInt()
            }
            if (interval !in 1..RepeatRule.MAX_INTERVAL) return@Extractor false
            val frequency = when (m.groupValues[2]) {
                "day" -> Frequency.DAILY
                "week" -> Frequency.WEEKLY
                "month" -> Frequency.MONTHLY
                else -> Frequency.YEARLY
            }
            setRepeat(s, now, RepeatRule(frequency, interval))
        },
        // take vitamins daily / pay rent monthly on the 1st — only last or before a time phrase, so
        // "weekly report" stays a title.
        Extractor(
            """\b(daily|weekly|monthly|yearly|annually|fortnightly|biweekly)\b(?=\s*$|\s+(?:at|on|in|from|starting|by|around|before|please|thanks|pls|\d))""",
        ) { m, s, now ->
            val rule = when (m.groupValues[1]) {
                "daily" -> RepeatRule(Frequency.DAILY)
                "weekly" -> RepeatRule(Frequency.WEEKLY)
                "monthly" -> RepeatRule(Frequency.MONTHLY)
                "fortnightly", "biweekly" -> RepeatRule(Frequency.WEEKLY, interval = 2)
                else -> RepeatRule(Frequency.YEARLY)
            }
            setRepeat(s, now, rule)
        },
        // ---- One-off dates and times. ----
        // in 20 minutes / after 2 hours / in half an hour / in a couple of days
        Extractor(
            """\b(?:in|after|within)\s+(\d{1,3}|an|a|half an|half a|a couple of|couple of)\s*(minutes?|mins?|hours?|hrs?|hr|days?|weeks?)\b""",
        ) { m, s, _ ->
            if (s.relative != null) return@Extractor false
            val amount: Double = when (val qty = m.groupValues[1]) {
                "an", "a" -> 1.0
                "half an", "half a" -> 0.5
                "a couple of", "couple of" -> 2.0
                else -> qty.toDouble()
            }
            val unit = m.groupValues[2]
            val minutes = when {
                unit.startsWith("min") -> amount
                unit.startsWith("h") -> amount * 60
                unit.startsWith("d") -> amount * 60 * 24
                else -> amount * 60 * 24 * 7
            }
            if (minutes <= 0) return@Extractor false
            s.relative = Duration.ofMinutes(minutes.toLong())
            true
        },
        // 5th october / 5 oct 2027 / the 5th of october
        Extractor("""\b(?:on\s+)?(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?($MONTHS)\b(?:\s+(\d{4}))?""") { m, s, now ->
            setExplicitDate(s, now, m.groupValues[1].toInt(), monthOf(m.groupValues[2]), m.groupValues[3])
        },
        // october 5th / oct 5, 2027
        Extractor("""\b(?:on\s+)?($MONTHS)\s+(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)?\b(?:,?\s+(\d{4}))?""") { m, s, now ->
            setExplicitDate(s, now, m.groupValues[2].toInt(), monthOf(m.groupValues[1]), m.groupValues[3])
        },
        // half past 8 / quarter past 8
        Extractor("""\b(?:at\s+)?(half|quarter)\s+past\s+(\d{1,2})\b""") { m, s, _ ->
            val hour = m.groupValues[2].toInt()
            if (hour !in 1..12 || s.hour != null) return@Extractor false
            s.hour = hour
            s.minute = if (m.groupValues[1] == "half") 30 else 15
            true
        },
        // quarter to 9
        Extractor("""\b(?:at\s+)?quarter\s+to\s+(\d{1,2})\b""") { m, s, _ ->
            val hour = m.groupValues[1].toInt()
            if (hour !in 1..12 || s.hour != null) return@Extractor false
            s.hour = if (hour == 1) 12 else hour - 1
            s.minute = 45
            true
        },
        // 8 am / at 8:30pm / 8.15 pm
        Extractor("""\b(?:(?:at|by|around|before)\s+)?(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)\b""") { m, s, _ ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (hour !in 1..12 || minute !in 0..59 || s.hour != null) return@Extractor false
            s.hour = hour
            s.minute = minute
            s.meridiem = if (m.groupValues[3] == "am") Meridiem.AM else Meridiem.PM
            true
        },
        // at 7:30 / at 19.45
        Extractor("""\b(?:at|by|around|before)\s+(\d{1,2})[:.](\d{2})\b""") { m, s, _ ->
            setClockTime(s, m.groupValues[1], m.groupValues[2])
        },
        // 07:30 / 19:30 (colon only, no preposition)
        Extractor("""\b(\d{1,2}):(\d{2})\b""") { m, s, _ ->
            setClockTime(s, m.groupValues[1], m.groupValues[2])
        },
        // 5/10 or 5-10-2027 (day/month order from locale)
        Extractor("""\b(?:on\s+)?(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?\b""") { m, s, now ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val (day, month) = if (dateOrder == DateOrder.DAY_MONTH) a to b else b to a
            if (month !in 1..12) return@Extractor false
            val year = m.groupValues[3].let { if (it.length == 2) "20$it" else it }
            setExplicitDate(s, now, day, month, year)
        },
        // on the 15th
        Extractor("""\b(?:on\s+)?the\s+(\d{1,2})(?:st|nd|rd|th)\b""") { m, s, now ->
            if (s.date != null) return@Extractor false
            val day = m.groupValues[1].toInt()
            val today = now.toLocalDate()
            var candidate = safeDate(today.year, today.monthValue, day) ?: return@Extractor false
            if (candidate.isBefore(today)) {
                val next = today.plusMonths(1)
                candidate = safeDate(next.year, next.monthValue, day) ?: return@Extractor false
            }
            s.date = candidate
            s.dateSource = DateSource.EXPLICIT
            true
        },
        // noon / midday / midnight
        Extractor("""\b(?:at\s+|by\s+)?(noon|midday|midnight)\b""") { m, s, _ ->
            if (s.hour != null) return@Extractor false
            s.hour = if (m.groupValues[1] == "midnight") 0 else 12
            s.midnight = m.groupValues[1] == "midnight"
            s.minute = 0
            s.twentyFourHour = true
            true
        },
        // 8 in the evening / 7 this evening / 10 at night / 9 tomorrow morning
        Extractor(
            """\b(?:(?:at|by|around)\s+)?(\d{1,2})(?:[:.](\d{2}))?\s+(in the|at|this|tomorrow)\s+(morning|afternoon|evening|night)\b""",
        ) { m, s, now ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (hour !in 1..12 || minute !in 0..59 || s.hour != null) return@Extractor false
            s.hour = hour
            s.minute = minute
            s.dayPart = dayPartOf(m.groupValues[4])
            applyDayQualifier(s, now, m.groupValues[3])
            true
        },
        // tomorrow 8 / monday 9:30-less (a bare hour right after a day word)
        Extractor(
            """\b(today|tomorrow|tmrw|tmr|tonight|$WEEKDAYS)\s+(?:at\s+)?(\d{1,2})\b(?!\s*(?:[:.]\d|%|percent|minutes?|mins?|hours?|days?|weeks?|people|times))""",
        ) { m, s, now ->
            val hour = m.groupValues[2].toInt()
            if (hour !in 0..23 || s.hour != null || s.date != null) return@Extractor false
            val word = m.groupValues[1]
            val weekday = WEEKDAY_NAMES[word]
            if (weekday != null) setWeekday(s, now, "", weekday) else setDayWord(s, now, word)
            s.hour = hour
            s.minute = 0
            s.twentyFourHour = hour == 0 || hour > 12
            true
        },
        // this morning / in the evening / tomorrow night / at night
        Extractor("""\b(?:(this|in the|at|tomorrow|every)\s+)?(morning|afternoon|evening|night)\b""") { m, s, now ->
            if (m.groupValues[1] == "every") return@Extractor false
            val part = dayPartOf(m.groupValues[2])
            if (s.dayPart != null && s.dayPart != part) return@Extractor false
            s.dayPart = part
            applyDayQualifier(s, now, m.groupValues[1])
            true
        },
        // today / tomorrow / day after tomorrow / tonight
        Extractor("""\b(?:the\s+)?(day after tomorrow|tomorrow|tmrw|tmr|tomorow|today|tonight|tonite)\b""") { m, s, now ->
            if (s.date != null) return@Extractor false
            setDayWord(s, now, m.groupValues[1])
            true
        },
        // this weekend / next weekend / on the weekend
        Extractor("""\b(?:(this|next|coming|the|on the|over the|at the)\s+)?weekend\b""") { m, s, now ->
            if (s.date != null) return@Extractor false
            val today = now.toLocalDate()
            val date = when {
                m.groupValues[1] == "next" -> snooze.resolve(SnoozePreset.NEXT_WEEKEND, now)?.toLocalDate()
                today.dayOfWeek in dayParts.weekendDays -> today
                else -> snooze.resolve(SnoozePreset.THIS_WEEKEND, now)?.toLocalDate()
            } ?: return@Extractor false
            s.date = date
            s.dateSource = DateSource.RELATIVE_PERIOD
            true
        },
        // next week / next month
        Extractor("""\b(next|this coming)\s+(week|month)\b""") { m, s, now ->
            if (s.date != null) return@Extractor false
            val today = now.toLocalDate()
            s.date = if (m.groupValues[2] == "week") {
                today.with(TemporalAdjusters.next(dayParts.weekStart))
            } else {
                today.plusMonths(1)
            }
            s.dateSource = DateSource.RELATIVE_PERIOD
            true
        },
        // (this|next|on) monday
        Extractor("""\b(?:(this|next|coming|on|by)\s+)?($WEEKDAYS)s?\b""") { m, s, now ->
            setWeekday(s, now, m.groupValues[1], WEEKDAY_NAMES.getValue(m.groupValues[2]))
        },
        // (this|next|on) mon — abbreviations only with a qualifier, so "sat exam" stays a title
        Extractor("""\b(this|next|coming|on)\s+($WEEKDAY_ABBREVIATIONS)\b""") { m, s, now ->
            setWeekday(s, now, m.groupValues[1], WEEKDAY_NAMES.getValue(m.groupValues[2]))
        },
        // at 8 / by 6 (bare hour after a preposition)
        Extractor(
            """\b(?:at|by|around)\s+(\d{1,2})\b(?!\s*(?:[:.]\d|%|percent|minutes?|mins?|hours?|days?|people|times))""",
        ) { m, s, _ ->
            val hour = m.groupValues[1].toInt()
            if (hour !in 0..23 || s.hour != null) return@Extractor false
            s.hour = hour
            s.minute = 0
            s.twentyFourHour = hour == 0 || hour > 12
            true
        },
    )

    private fun dayPartOf(word: String): DayPart = DayPart.valueOf(word.uppercase(Locale.ROOT))

    private fun applyDayQualifier(s: TemporalState, now: ZonedDateTime, qualifier: String) {
        if (s.date != null) return
        when (qualifier) {
            "this" -> setDate(s, now.toLocalDate(), DateSource.DAY_WORD)
            "tomorrow" -> setDate(s, now.toLocalDate().plusDays(1), DateSource.DAY_WORD)
        }
    }

    private fun setDayWord(s: TemporalState, now: ZonedDateTime, word: String) {
        val today = now.toLocalDate()
        when (word) {
            "day after tomorrow" -> setDate(s, today.plusDays(2), DateSource.DAY_WORD)
            "today" -> setDate(s, today, DateSource.DAY_WORD)
            "tonight", "tonite" -> {
                setDate(s, today, DateSource.DAY_WORD)
                if (s.dayPart == null) s.dayPart = DayPart.NIGHT
            }
            else -> setDate(s, today.plusDays(1), DateSource.DAY_WORD)
        }
    }

    private fun setDate(s: TemporalState, date: LocalDate, source: DateSource) {
        s.date = date
        s.dateSource = source
    }

    private fun setClockTime(s: TemporalState, hourText: String, minuteText: String): Boolean {
        val hour = hourText.toInt()
        val minute = minuteText.toInt()
        if (hour !in 0..23 || minute !in 0..59 || s.hour != null) return false
        s.hour = hour
        s.minute = minute
        s.twentyFourHour = hour == 0 || hour > 12 || (hourText.length == 2 && hourText.startsWith("0"))
        return true
    }

    private fun setExplicitDate(s: TemporalState, now: ZonedDateTime, day: Int, month: Int, yearText: String): Boolean {
        if (s.date != null) return false
        val today = now.toLocalDate()
        val explicitYear = yearText.toIntOrNull()
        var date = safeDate(explicitYear ?: today.year, month, day) ?: return false
        if (explicitYear == null && date.isBefore(today)) {
            date = safeDate(today.year + 1, month, day) ?: return false
        }
        setDate(s, date, DateSource.EXPLICIT)
        return true
    }

    /** Records the series; rules tied to particular days also pin the first occurrence's date. */
    private fun setRepeat(s: TemporalState, now: ZonedDateTime, rule: RepeatRule): Boolean {
        if (s.repeat != null) return false
        s.repeat = rule
        if ((rule.weekdays.isNotEmpty() || rule.monthlyBy != null) && s.date == null) {
            // EXPLICIT: resolution must not shift it; firstOccurrence() moves on along the rule instead.
            rule.firstOnOrAfter(now.toLocalDate())?.let { setDate(s, it, DateSource.EXPLICIT) }
        }
        return true
    }

    private fun weeklyOn(text: String): RepeatRule? {
        val days = text.split(' ')
            .filter { it.isNotBlank() && it != "and" && it != "or" && it != "&" }
            .map { word -> WEEKDAY_NAMES[word] ?: WEEKDAY_NAMES[word.removeSuffix("s")] ?: return null }
            .toSet()
        return RepeatRule(Frequency.WEEKLY, weekdays = days)
    }

    private fun monthlyOn(dayText: String): RepeatRule? =
        dayText.toIntOrNull()?.takeIf { it in 1..31 }?.let { RepeatRule(Frequency.MONTHLY, monthlyBy = MonthlyBy.DayOfMonth(it)) }

    private fun monthlyNth(ordinalText: String, weekdayText: String): RepeatRule? {
        val ordinal = ORDINAL_WORDS[ordinalText] ?: return null
        val day = WEEKDAY_NAMES[weekdayText] ?: return null
        return RepeatRule(Frequency.MONTHLY, monthlyBy = MonthlyBy.NthWeekday(ordinal, day))
    }

    private fun setWeekday(s: TemporalState, now: ZonedDateTime, qualifier: String, day: DayOfWeek): Boolean {
        if (s.date != null) return false
        val today = now.toLocalDate()
        val date = if (qualifier == "next") {
            // "next Friday" = Friday of next week (weeks start on the user's week start).
            today.with(TemporalAdjusters.next(dayParts.weekStart)).with(TemporalAdjusters.nextOrSame(day))
        } else {
            today.with(TemporalAdjusters.nextOrSame(day))
        }
        setDate(s, date, DateSource.WEEKDAY)
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Resolution
    // ---------------------------------------------------------------------------------------------

    private fun resolve(s: TemporalState, now: ZonedDateTime, preferMorning: Boolean): Pair<LocalDateTime?, Boolean> {
        val nowLocal = now.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
        s.relative?.let { return nowLocal.plus(it) to false }

        val today = now.toLocalDate()
        val hour = s.hour
        val date = s.date

        if (hour != null) {
            if (s.midnight) return (date ?: today).plusDays(1).atTime(LocalTime.MIDNIGHT) to false
            val candidates = candidateTimes(s, hour, preferMorning)
            if (date == null) {
                // Time only → the nearest future occurrence (today or tomorrow).
                val next = (candidates.map { today.atTime(it) } + candidates.map { today.plusDays(1).atTime(it) })
                    .filter { it.isAfter(nowLocal) }
                    .minOrNull()
                return next to false
            }
            if (date == today) {
                candidates.map { today.atTime(it) }.filter { it.isAfter(nowLocal) }.minOrNull()
                    ?.let { return it to false }
            }
            val time = preferredTime(s, hour, candidates, preferMorning)
            var result = date.atTime(time)
            if (!result.isAfter(nowLocal)) {
                result = when (s.dateSource) {
                    DateSource.WEEKDAY -> result.plusWeeks(1)
                    DateSource.EXPLICIT -> result
                    else -> result.plusDays(1)
                }
            }
            return result to false
        }

        val part = s.dayPart
        if (part != null) {
            var result = (date ?: today).atTime(timeOf(part))
            if (!result.isAfter(nowLocal) && (date == null || date == today)) result = result.plusDays(1)
            return result to false
        }

        if (date == null) return null to false
        if (date == today) {
            val evening = today.atTime(dayParts.evening)
            val result = if (nowLocal.isBefore(evening.minusMinutes(30))) {
                evening
            } else {
                roundUpToHalfHour(nowLocal.plusHours(1))
            }
            return result to true
        }
        return date.atTime(dayParts.morning) to true
    }

    /** All 24-hour interpretations of [hour] consistent with what was said. */
    private fun candidateTimes(s: TemporalState, hour: Int, preferMorning: Boolean): List<LocalTime> {
        val minute = s.minute
        if (s.twentyFourHour) return listOf(LocalTime.of(hour % 24, minute))
        s.meridiem?.let { return listOf(LocalTime.of(to24(hour, it), minute)) }
        s.dayPart?.let { part ->
            val meridiem = when (part) {
                DayPart.MORNING -> Meridiem.AM
                DayPart.NIGHT -> if (hour == 12) Meridiem.AM else Meridiem.PM
                else -> Meridiem.PM
            }
            return listOf(LocalTime.of(to24(hour, meridiem), minute))
        }
        // "Wake me up at 6" means 6 AM.
        if (preferMorning) return listOf(LocalTime.of(to24(hour, Meridiem.AM), minute))
        return listOf(LocalTime.of(to24(hour, Meridiem.AM), minute), LocalTime.of(to24(hour, Meridiem.PM), minute))
    }

    /** A future day without am/pm: 7–11 → morning, 12 → noon, 1–6 → afternoon (wake-ups → morning). */
    private fun preferredTime(s: TemporalState, hour: Int, candidates: List<LocalTime>, preferMorning: Boolean): LocalTime {
        if (candidates.size == 1) return candidates.single()
        val meridiem = if (preferMorning || hour in 7..11) Meridiem.AM else Meridiem.PM
        return LocalTime.of(to24(hour, meridiem), s.minute)
    }

    private fun to24(hour: Int, meridiem: Meridiem): Int = when (meridiem) {
        Meridiem.AM -> if (hour == 12) 0 else hour
        Meridiem.PM -> if (hour == 12) 12 else hour + 12
    }

    private fun timeOf(part: DayPart): LocalTime = when (part) {
        DayPart.MORNING -> dayParts.morning
        DayPart.AFTERNOON -> dayParts.afternoon
        DayPart.EVENING -> dayParts.evening
        DayPart.NIGHT -> dayParts.night
    }

    private fun roundUpToHalfHour(t: LocalDateTime): LocalDateTime {
        val remainder = t.minute % 30
        return if (remainder == 0) t else t.plusMinutes((30 - remainder).toLong())
    }

    // ---------------------------------------------------------------------------------------------
    // Title
    // ---------------------------------------------------------------------------------------------

    private fun buildTitle(tokens: List<Token>, consumed: BooleanArray, command: CommandResult): String {
        val words = tokens.indices.filterNot { consumed[it] }.map { tokens[it] }.toMutableList()
        while (words.isNotEmpty() && words.first().norm in DANGLING_START) words.removeAt(0)
        while (words.isNotEmpty() && words.last().norm in DANGLING_END) words.removeAt(words.lastIndex)
        val text = words.joinToString(" ") { it.original.trim(',', ';', ':') }
            .trimEnd('.', '!', '?', ',', ' ')
            .trim()
        if (text.isEmpty()) return command.defaultTitle
        return text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    private class Prefix(val words: List<String>, val ring: Boolean, val wake: Boolean, val defaultTitle: String)

    private companion object {
        const val MONTHS =
            "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|" +
                "sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
        const val WEEKDAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
        const val WEEKDAY_ABBREVIATIONS = "mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun"
        const val WEEKDAY_WORDS = "$WEEKDAYS|$WEEKDAY_ABBREVIATIONS"
        const val WEEKDAY_WORD_S = "(?:$WEEKDAYS|$WEEKDAY_ABBREVIATIONS)s?"
        const val ORDINALS = "first|second|third|fourth|fifth|last|1st|2nd|3rd|4th|5th"
        val ORDINAL_WORDS: Map<String, Int> = mapOf(
            "first" to 1, "1st" to 1, "second" to 2, "2nd" to 2, "third" to 3, "3rd" to 3,
            "fourth" to 4, "4th" to 4, "fifth" to 5, "5th" to 5, "last" to MonthlyBy.LAST,
        )
        val COMPOUND_TENS = setOf(20, 30, 40, 50)

        val WEEKDAY_NAMES: Map<String, DayOfWeek> = buildMap {
            DayOfWeek.entries.forEach { day ->
                val name = day.name.lowercase(Locale.ROOT)
                put(name, day)
                put(name.take(3), day)
            }
            put("tues", DayOfWeek.TUESDAY)
            put("thur", DayOfWeek.THURSDAY)
            put("thurs", DayOfWeek.THURSDAY)
        }

        val NUMBER_WORDS: Map<String, Int> = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
            "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
            "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
            "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40,
            "fifty" to 50,
        )

        fun monthOf(text: String): Int = when (text.take(3)) {
            "jan" -> 1
            "feb" -> 2
            "mar" -> 3
            "apr" -> 4
            "may" -> 5
            "jun" -> 6
            "jul" -> 7
            "aug" -> 8
            "sep" -> 9
            "oct" -> 10
            "nov" -> 11
            else -> 12
        }

        fun safeDate(year: Int, month: Int, day: Int): LocalDate? =
            runCatching { LocalDate.of(year, month, day) }.getOrNull()

        fun prefix(text: String, ring: Boolean, wake: Boolean = false, defaultTitle: String = "") =
            Prefix(text.split(' '), ring, wake, defaultTitle)

        /** Longest first so "remind me to" wins over "remind me". */
        val COMMAND_PREFIXES: List<Prefix> = listOf(
            prefix("set a reminder to", true), prefix("set a reminder for", true),
            prefix("set a reminder that", true), prefix("set a reminder about", true),
            prefix("set a reminder", true), prefix("set reminder to", true), prefix("set reminder for", true),
            prefix("set reminder", true), prefix("create a reminder to", true), prefix("create a reminder for", true),
            prefix("create a reminder", true), prefix("add a reminder to", true), prefix("add a reminder for", true),
            prefix("add a reminder", true), prefix("new reminder", true),
            prefix("remind me to", true), prefix("remind me about", true), prefix("remind me that", true),
            prefix("remind me of", true), prefix("remind me", true),
            prefix("set an alarm to", true, defaultTitle = "Alarm"),
            prefix("set an alarm for", true, defaultTitle = "Alarm"),
            prefix("set alarm for", true, defaultTitle = "Alarm"),
            prefix("set an alarm", true, defaultTitle = "Alarm"),
            prefix("set alarm", true, defaultTitle = "Alarm"),
            prefix("wake me up", true, wake = true, defaultTitle = "Wake up"),
            prefix("wake me", true, wake = true, defaultTitle = "Wake up"),
            prefix("call me to", true), prefix("call me about", true),
            prefix("add a task to", false), prefix("add a task", false), prefix("add task to", false),
            prefix("add task", false), prefix("create a task to", false), prefix("create a task", false),
            prefix("new task", false), prefix("add a todo", false), prefix("add todo", false),
            prefix("don't forget to", true), prefix("dont forget to", true), prefix("do not forget to", true),
            prefix("remember to", true), prefix("make sure to", false), prefix("i need to", false),
            prefix("i have to", false), prefix("i've got to", false), prefix("i gotta", false),
        ).sortedByDescending { it.words.size }

        val LEADING_FILLERS = setOf("please", "pls", "hey", "ok", "okay", "so", "um", "uh")
        val TRAILING_FILLERS = setOf("please", "pls", "thanks", "thx")
        val DANGLING_START = setOf("to", "that", "about", "for", "on", "at", "by", "and", "then", "-")
        val DANGLING_END =
            setOf("at", "on", "for", "by", "to", "in", "and", "the", "this", "next", "around", "about", "of", "-")
    }
}
