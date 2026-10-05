package dev.suyash.dot.feature.tasks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.suyash.dot.core.designsystem.component.DotPill
import dev.suyash.dot.core.designsystem.component.SectionLabel
import dev.suyash.dot.core.designsystem.icon.DotIcons
import dev.suyash.dot.core.designsystem.theme.DotTheme
import dev.suyash.dot.core.domain.model.ListId
import dev.suyash.dot.core.domain.model.RingMode
import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.model.TaskList
import dev.suyash.dot.core.domain.repeat.RepeatRule
import dev.suyash.dot.core.ui.TimeFormats
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** Callbacks the editor uses; all writes go through the ViewModel → TaskCommands. */
internal class TaskEditorActions(
    val rename: (String) -> Unit,
    val setNotes: (String) -> Unit,
    val setSchedule: (LocalDate?, LocalTime?) -> Unit,
    val setRingMode: (RingMode) -> Unit,
    val setRepeat: (RepeatRule?) -> Unit,
    val toggleStar: () -> Unit,
    val toggleDone: () -> Unit,
    val moveToList: (ListId) -> Unit,
    val addSubtask: (String) -> Unit,
    val toggleSubtask: (Task) -> Unit,
    val openTask: (Task) -> Unit,
    val duplicate: () -> Unit,
    val share: () -> Unit,
    val delete: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskEditorSheet(
    task: Task,
    subtasks: List<Task>,
    lists: List<TaskList>,
    parent: Task?,
    now: ZonedDateTime,
    defaultMode: RingMode,
    actions: TaskEditorActions,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val formats = remember(context) { TimeFormats.from(context) }
    val colors = DotTheme.colors
    var title by rememberSaveable(task.id.value) { mutableStateOf(task.title) }
    var notes by rememberSaveable(task.id.value) { mutableStateOf(task.notes) }
    var newSubtask by rememberSaveable(task.id.value) { mutableStateOf("") }
    var picking by rememberSaveable { mutableStateOf<Picker?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val zone = ZoneId.systemDefault()
    val reminderAt = task.reminder?.at?.atZone(zone)
    val date: LocalDate? = reminderAt?.toLocalDate() ?: task.dueDate
    val time: LocalTime? = reminderAt?.toLocalTime()
    val mode = task.reminder?.mode ?: defaultMode
    val today = now.toLocalDate()

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
            // Where it lives: its list (switchable), or its parent task for a subtask.
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (parent != null) {
                    DotPill(
                        text = "Subtask of “${parent.title.take(40)}”",
                        selected = false,
                        onClick = { commitText(); actions.openTask(parent) },
                        leadingIcon = DotIcons.Subtask,
                    )
                } else {
                    ListSwitcher(current = task.listId, lists = lists, onSelect = { commitText(); actions.moveToList(it) })
                }
            }

            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                DoneCircle(done = task.isDone, onClick = { commitText(); actions.toggleDone() })
                TextField(
                    value = title,
                    onValueChange = { title = it.take(500) },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                    ),
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
                placeholder = { Text("Add details") },
                leadingIcon = { Icon(DotIcons.Notes, contentDescription = null, tint = colors.muted) },
                colors = transparentFieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )

            // ---- Date & time ----
            Spacer(Modifier.height(16.dp))
            SectionLabel("Date & time", modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(10.dp))
            if (date == null) {
                val nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { DotPill(text = "Today", selected = false, onClick = { actions.setSchedule(today, null) }) }
                    item { DotPill(text = "Tomorrow", selected = false, onClick = { actions.setSchedule(today.plusDays(1), null) }) }
                    item {
                        DotPill(
                            text = "Next week",
                            subtitle = formats.day(nextMonday, today),
                            selected = false,
                            onClick = { actions.setSchedule(nextMonday, null) },
                        )
                    }
                    item { DotPill(text = "Pick date…", selected = false, onClick = { picking = Picker.DATE }, leadingIcon = DotIcons.Calendar) }
                }
            } else {
                Row(
                    Modifier.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DotPill(text = formats.day(date, today), selected = true, onClick = { picking = Picker.DATE }, leadingIcon = DotIcons.Calendar)
                    if (time != null && reminderAt != null) {
                        DotPill(
                            text = formats.time(reminderAt),
                            selected = true,
                            onClick = { picking = Picker.TIME },
                            leadingIcon = if (mode == RingMode.RING) DotIcons.Phone else DotIcons.Bell,
                        )
                    } else {
                        DotPill(text = "Add time", selected = false, onClick = { picking = Picker.TIME }, leadingIcon = DotIcons.Clock)
                    }
                    IconButton(onClick = { actions.setSchedule(null, null) }) {
                        Icon(DotIcons.Close, contentDescription = "Remove date", tint = colors.muted)
                    }
                }
                if (time != null) {
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
                }
            }

            // ---- Repeat (top-level tasks, like Google Tasks) ----
            if (parent == null) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    DotPill(
                        text = task.repeat?.describe(date) ?: "Doesn't repeat",
                        selected = task.repeat != null,
                        onClick = { picking = Picker.REPEAT },
                        leadingIcon = DotIcons.Repeat,
                    )
                    if (task.repeat != null) {
                        IconButton(onClick = { actions.setRepeat(null) }) {
                            Icon(DotIcons.Close, contentDescription = "Stop repeating", tint = colors.muted)
                        }
                    }
                }
            }

            // ---- Subtasks ----
            if (parent == null) {
                Spacer(Modifier.height(20.dp))
                val done = subtasks.count { it.isDone }
                SectionLabel(
                    if (subtasks.isEmpty()) "Subtasks" else "Subtasks · $done/${subtasks.size}",
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                subtasks.forEach { sub ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { commitText(); actions.openTask(sub) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DoneCircle(done = sub.isDone, onClick = { actions.toggleSubtask(sub) })
                        Text(
                            sub.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (sub.isDone) colors.muted else MaterialTheme.colorScheme.onSurface,
                            textDecoration = if (sub.isDone) TextDecoration.LineThrough else null,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(DotIcons.Plus, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
                    }
                    TextField(
                        value = newSubtask,
                        onValueChange = { newSubtask = it.take(500) },
                        placeholder = { Text("Add subtask") },
                        singleLine = true,
                        colors = transparentFieldColors(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (newSubtask.isNotBlank()) {
                                actions.addSubtask(newSubtask.trim())
                                newSubtask = ""
                            }
                        }),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ---- Actions ----
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { actions.delete(); onDismiss() }) {
                    Icon(DotIcons.Trash, contentDescription = null, tint = colors.accent)
                    Text("  Delete", color = colors.accent)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { commitText(); actions.duplicate() }) { Icon(DotIcons.Copy, contentDescription = "Duplicate") }
                IconButton(onClick = { commitText(); actions.share() }) { Icon(DotIcons.Share, contentDescription = "Share") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { commitText(); onDismiss() }) { Text("Done") }
            }
        }
    }

    when (picking) {
        Picker.DATE -> PickDateDialog(initial = date ?: today, onDismiss = { picking = null }) {
            picking = null
            actions.setSchedule(it, time)
        }
        Picker.TIME -> PickTimeDialog(
            initial = time ?: now.toLocalTime().plusHours(1).withMinute(0).withSecond(0).withNano(0),
            onDismiss = { picking = null },
        ) {
            picking = null
            actions.setSchedule(date ?: today, it)
        }
        Picker.REPEAT -> RepeatDialog(current = task.repeat, date = date ?: today, onDismiss = { picking = null }) {
            picking = null
            actions.setRepeat(it)
        }
        null -> Unit
    }
}

private enum class Picker { DATE, TIME, REPEAT }

@Composable
private fun ListSwitcher(current: ListId, lists: List<TaskList>, onSelect: (ListId) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val title = lists.firstOrNull { it.id == current }?.title ?: "My Tasks"
    Box {
        DotPill(text = title, selected = false, onClick = { open = true }, leadingIcon = DotIcons.ListIcon)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            lists.forEach { list ->
                DropdownMenuItem(
                    text = { Text(list.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = if (list.id == current) ({ Icon(DotIcons.Check, contentDescription = null) }) else null,
                    onClick = {
                        open = false
                        if (list.id != current) onSelect(list.id)
                    },
                )
            }
        }
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
