package dev.suyash.dot.feature.tasks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.suyash.dot.core.designsystem.component.DotPill
import dev.suyash.dot.core.designsystem.component.SectionLabel
import dev.suyash.dot.core.designsystem.theme.DotTheme
import dev.suyash.dot.core.domain.repeat.Frequency
import dev.suyash.dot.core.domain.repeat.MonthlyBy
import dev.suyash.dot.core.domain.repeat.RepeatEnd
import dev.suyash.dot.core.domain.repeat.RepeatRule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

/**
 * Choose how a task repeats: quick choices based on its date, or a custom rule (every N
 * days/weeks/months/years, weekdays, day of month or "2nd Tuesday", and when it ends).
 */
@Composable
internal fun RepeatDialog(
    current: RepeatRule?,
    date: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (RepeatRule?) -> Unit,
) {
    val presets = remember(date) {
        listOf(
            RepeatRule(Frequency.DAILY),
            RepeatRule(Frequency.WEEKLY, weekdays = RepeatRule.WEEKDAYS),
            RepeatRule(Frequency.WEEKLY, weekdays = setOf(date.dayOfWeek)),
            RepeatRule(Frequency.MONTHLY, monthlyBy = MonthlyBy.DayOfMonth(date.dayOfMonth)),
            RepeatRule(Frequency.YEARLY),
        )
    }
    var custom by remember { mutableStateOf(current != null && current.anchoredTo(date) !in presets) }

    if (custom) {
        CustomRepeatDialog(initial = current?.anchoredTo(date) ?: presets[2], date = date, onDismiss = onDismiss, onConfirm = onConfirm)
        return
    }
    val selected = current?.anchoredTo(date)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repeat") },
        text = {
            Column {
                ChoiceLine("Doesn't repeat", selected == null) { onConfirm(null) }
                presets.forEach { rule ->
                    ChoiceLine(rule.describe(date), selected == rule) { onConfirm(rule) }
                }
                ChoiceLine("Custom…", false) { custom = true }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CustomRepeatDialog(initial: RepeatRule, date: LocalDate, onDismiss: () -> Unit, onConfirm: (RepeatRule?) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    var interval by remember { mutableStateOf(initial.interval.toString()) }
    var frequency by remember { mutableStateOf(initial.frequency) }
    var weekdays by remember { mutableStateOf(initial.weekdays.ifEmpty { setOf(date.dayOfWeek) }) }
    val nth = remember(date) { MonthlyBy.NthWeekday(nthOf(date), date.dayOfWeek) }
    var monthlyByWeekday by remember { mutableStateOf(initial.monthlyBy is MonthlyBy.NthWeekday) }
    var endKind by remember { mutableStateOf(initial.end::class) }
    var endDate by remember { mutableStateOf((initial.end as? RepeatEnd.OnDate)?.date ?: date.plusMonths(3)) }
    var endCount by remember { mutableStateOf(((initial.end as? RepeatEnd.AfterCount)?.remaining ?: 10).toString()) }
    var pickingEndDate by remember { mutableStateOf(false) }

    val parsedInterval = interval.toIntOrNull()?.takeIf { it in 1..RepeatRule.MAX_INTERVAL }
    val parsedCount = endCount.toIntOrNull()?.takeIf { it in 1..RepeatRule.MAX_COUNT }
    val rule: RepeatRule? = parsedInterval?.let { n ->
        val end = when (endKind) {
            RepeatEnd.OnDate::class -> RepeatEnd.OnDate(endDate)
            RepeatEnd.AfterCount::class -> parsedCount?.let { RepeatEnd.AfterCount(it) } ?: return@let null
            else -> RepeatEnd.Never
        }
        RepeatRule(
            frequency = frequency,
            interval = n,
            weekdays = if (frequency == Frequency.WEEKLY) weekdays.ifEmpty { setOf(date.dayOfWeek) } else emptySet(),
            monthlyBy = when {
                frequency != Frequency.MONTHLY -> null
                monthlyByWeekday -> nth
                else -> MonthlyBy.DayOfMonth(date.dayOfMonth)
            },
            end = end,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom repeat") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Every", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { interval = it.filter(Char::isDigit).take(3) },
                        singleLine = true,
                        isError = parsedInterval == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(72.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Frequency.entries.forEach { f ->
                        DotPill(text = unitName(f, parsedInterval ?: 1), selected = frequency == f, onClick = { frequency = f })
                    }
                }
                when (frequency) {
                    Frequency.WEEKLY -> {
                        Spacer(Modifier.height(16.dp))
                        SectionLabel("On")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            DayOfWeek.entries.forEach { day ->
                                DayToggle(day, day in weekdays) {
                                    weekdays = if (day in weekdays) weekdays - day else weekdays + day
                                }
                            }
                        }
                    }
                    Frequency.MONTHLY -> {
                        Spacer(Modifier.height(12.dp))
                        ChoiceLine("On day ${date.dayOfMonth}", !monthlyByWeekday) { monthlyByWeekday = false }
                        ChoiceLine(RepeatRule(Frequency.MONTHLY, monthlyBy = nth).describe(date).removePrefix("Monthly "), monthlyByWeekday) {
                            monthlyByWeekday = true
                        }
                    }
                    else -> Unit
                }
                Spacer(Modifier.height(16.dp))
                SectionLabel("Ends")
                ChoiceLine("Never", endKind == RepeatEnd.Never::class) { endKind = RepeatEnd.Never::class }
                ChoiceLine(
                    "On " + endDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)),
                    endKind == RepeatEnd.OnDate::class,
                ) {
                    endKind = RepeatEnd.OnDate::class
                    pickingEndDate = true
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = endKind == RepeatEnd.AfterCount::class, onClick = { endKind = RepeatEnd.AfterCount::class })
                    Text("After", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = endCount,
                        onValueChange = {
                            endCount = it.filter(Char::isDigit).take(4)
                            endKind = RepeatEnd.AfterCount::class
                        },
                        singleLine = true,
                        isError = endKind == RepeatEnd.AfterCount::class && parsedCount == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(80.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("times", style = MaterialTheme.typography.bodyLarge)
                }
                if (rule != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(rule.describe(date), style = MaterialTheme.typography.bodySmall, color = DotTheme.colors.muted)
                }
            }
        },
        confirmButton = { TextButton(onClick = { rule?.let(onConfirm) }, enabled = rule != null) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickingEndDate) {
        PickDateDialog(initial = endDate, onDismiss = { pickingEndDate = false }) {
            endDate = if (it.isBefore(date)) date else it
            pickingEndDate = false
        }
    }
}

@Composable
private fun ChoiceLine(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DayToggle(day: DayOfWeek, selected: Boolean, onClick: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val colors = DotTheme.colors
    val name = day.getDisplayName(TextStyle.FULL, locale)
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.background)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.onBackground else colors.hairline, CircleShape)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics {
                contentDescription = name
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            day.getDisplayName(TextStyle.NARROW, locale),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground,
        )
    }
}

private fun unitName(frequency: Frequency, count: Int): String {
    val one = when (frequency) {
        Frequency.DAILY -> "day"
        Frequency.WEEKLY -> "week"
        Frequency.MONTHLY -> "month"
        Frequency.YEARLY -> "year"
    }
    return if (count == 1) one else one + "s"
}

/** Which occurrence of its weekday [date] is in its month (the last one counts as "last"). */
private fun nthOf(date: LocalDate): Int =
    if (date.plusWeeks(1).month != date.month) MonthlyBy.LAST else (date.dayOfMonth - 1) / 7 + 1
