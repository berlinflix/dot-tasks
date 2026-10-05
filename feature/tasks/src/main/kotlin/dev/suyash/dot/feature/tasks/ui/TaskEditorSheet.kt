package dev.suyash.dot.feature.tasks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.suyash.dot.core.designsystem.component.DotPill
import dev.suyash.dot.core.designsystem.component.SectionLabel
import dev.suyash.dot.core.designsystem.icon.DotIcons
import dev.suyash.dot.core.designsystem.theme.DotTheme
import dev.suyash.dot.core.domain.model.RingMode
import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.time.DayParts
import dev.suyash.dot.core.domain.time.SnoozeCalculator
import dev.suyash.dot.core.ui.DateTimePickerDialog
import dev.suyash.dot.core.ui.TimeFormats
import dev.suyash.dot.core.ui.label
import java.time.ZoneId
import java.time.ZonedDateTime

/** Callbacks the editor uses; all writes go through the ViewModel → TaskCommands. */
internal class TaskEditorActions(
    val rename: (String) -> Unit,
    val setNotes: (String) -> Unit,
    val setReminder: (ZonedDateTime?, RingMode) -> Unit,
    val setRingMode: (RingMode) -> Unit,
    val toggleStar: () -> Unit,
    val toggleDone: () -> Unit,
    val delete: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskEditorSheet(
    task: Task,
    now: ZonedDateTime,
    dayParts: DayParts,
    defaultMode: RingMode,
    actions: TaskEditorActions,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val formats = remember(context) { TimeFormats.from(context) }
    val colors = DotTheme.colors
    var title by rememberSaveable(task.id.value) { mutableStateOf(task.title) }
    var notes by rememberSaveable(task.id.value) { mutableStateOf(task.notes) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val reminderAt = task.reminder?.at?.atZone(ZoneId.systemDefault())
    val mode = task.reminder?.mode ?: defaultMode

    fun commitText() {
        if (title.trim() != task.title && title.isNotBlank()) actions.rename(title)
        if (notes.trim() != task.notes) actions.setNotes(notes)
    }

    ModalBottomSheet(
        onDismissRequest = {
            commitText()
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(bottom = 24.dp),
        ) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                DoneCircle(done = task.isDone, onClick = { commitText(); actions.toggleDone() })
                TextField(
                    value = title,
                    onValueChange = { title = it.take(500) },
                    textStyle = MaterialTheme.typography.headlineSmall,
                    placeholder = { Text("Title", style = MaterialTheme.typography.headlineSmall) },
                    colors = transparentFieldColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = actions.toggleStar) {
                    Icon(
                        if (task.isStarred) DotIcons.StarFilled else DotIcons.Star,
                        contentDescription = if (task.isStarred) "Unstar" else "Star",
                    )
                }
            }
            TextField(
                value = notes,
                onValueChange = { notes = it.take(5_000) },
                placeholder = { Text("Add notes") },
                leadingIcon = { Icon(DotIcons.Notes, contentDescription = null, tint = colors.muted) },
                colors = transparentFieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel("Reminder", modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(10.dp))
            if (reminderAt != null) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DotPill(
                        text = formats.dayAndTime(reminderAt, now),
                        selected = true,
                        onClick = { showPicker = true },
                        leadingIcon = if (mode == RingMode.RING) DotIcons.Phone else DotIcons.Bell,
                    )
                    IconButton(onClick = { actions.setReminder(null, mode) }) {
                        Icon(DotIcons.Close, contentDescription = "Remove reminder", tint = colors.muted)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DotPill(text = "Ring like a call", selected = mode == RingMode.RING, onClick = { actions.setRingMode(RingMode.RING) }, leadingIcon = DotIcons.Phone)
                    DotPill(text = "Notification", selected = mode == RingMode.NOTIFY, onClick = { actions.setRingMode(RingMode.NOTIFY) }, leadingIcon = DotIcons.Bell)
                }
                Text(
                    if (mode == RingMode.RING) "Rings until you answer — never on silent or Do Not Disturb." else "A normal notification.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            } else {
                val calculator = remember(dayParts) { SnoozeCalculator(dayParts) }
                val options = remember(now, calculator) { calculator.options(now).take(4) }
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(options) { option ->
                        DotPill(
                            text = option.preset.label(),
                            subtitle = formats.chip(option.at, now),
                            selected = false,
                            onClick = { actions.setReminder(option.at, defaultMode) },
                        )
                    }
                    item {
                        DotPill(text = "Pick…", selected = false, onClick = { showPicker = true }, leadingIcon = DotIcons.Calendar)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { actions.delete(); onDismiss() }) {
                    Icon(DotIcons.Trash, contentDescription = null, tint = colors.accent)
                    Text("  Delete", color = colors.accent)
                }
                TextButton(onClick = { commitText(); onDismiss() }) { Text("Done") }
            }
        }
    }

    if (showPicker) {
        DateTimePickerDialog(
            initial = reminderAt ?: now.plusHours(1).withMinute(0),
            onDismiss = { showPicker = false },
            onConfirm = {
                showPicker = false
                actions.setReminder(it, mode)
            },
        )
    }
}

@Composable
private fun transparentFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)
