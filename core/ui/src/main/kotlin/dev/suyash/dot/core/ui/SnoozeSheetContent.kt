package dev.suyash.dot.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.suyash.dot.core.designsystem.component.SectionLabel
import dev.suyash.dot.core.designsystem.icon.DotIcons
import dev.suyash.dot.core.designsystem.theme.DotTheme
import dev.suyash.dot.core.domain.time.DayParts
import dev.suyash.dot.core.domain.time.SnoozeCalculator
import dev.suyash.dot.core.domain.time.SnoozeOption
import dev.suyash.dot.core.domain.time.SnoozePreset
import java.time.ZonedDateTime

fun SnoozePreset.label(): String = when (this) {
    SnoozePreset.PLUS_10_MINUTES -> "In 10 minutes"
    SnoozePreset.PLUS_1_HOUR -> "In 1 hour"
    SnoozePreset.LATER_TODAY -> "Later today"
    SnoozePreset.THIS_EVENING -> "This evening"
    SnoozePreset.THIS_MORNING -> "This morning"
    SnoozePreset.TOMORROW -> "Tomorrow"
    SnoozePreset.THIS_WEEKEND -> "This weekend"
    SnoozePreset.NEXT_WEEKEND -> "Next weekend"
    SnoozePreset.NEXT_WEEK -> "Next week"
}

/**
 * The snooze chooser: every option shows the exact time it resolves to, so "next weekend" is never
 * ambiguous. Used in the task list, the ringing screen and the notification's "Later…" action.
 */
@Composable
fun SnoozeSheetContent(
    now: ZonedDateTime,
    dayParts: DayParts,
    onPick: (ZonedDateTime) -> Unit,
    onCustom: () -> Unit,
    modifier: Modifier = Modifier,
    includeQuick: Boolean = true,
) {
    val context = LocalContext.current
    val formats = remember(context) { TimeFormats.from(context) }
    val calculator = remember(dayParts) { SnoozeCalculator(dayParts) }
    val options: List<SnoozeOption> = remember(now, calculator, includeQuick) {
        (if (includeQuick) calculator.quickOptions(now) else emptyList()) + calculator.options(now)
    }

    Column(modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        SectionLabel("Snooze until", modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        options.forEach { option ->
            SnoozeRow(
                title = option.preset.label(),
                subtitle = formats.chip(option.at, now),
                onClick = { onPick(option.at) },
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), color = DotTheme.colors.hairline)
        SnoozeRow(title = "Pick date & time", subtitle = null, onClick = onCustom, showCalendar = true)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SnoozeRow(title: String, subtitle: String?, onClick: () -> Unit, showCalendar: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = if (showCalendar) DotIcons.Calendar else DotIcons.Clock,
            contentDescription = null,
            tint = DotTheme.colors.muted,
            modifier = Modifier.size(22.dp),
        )
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = DotTheme.colors.muted)
        }
    }
}
