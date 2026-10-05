package dev.suyash.dot.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.suyash.dot.core.data.TaskCommands
import dev.suyash.dot.core.designsystem.component.DotPill
import dev.suyash.dot.core.designsystem.component.SectionLabel
import dev.suyash.dot.core.designsystem.dotmatrix.DotMatrixText
import dev.suyash.dot.core.designsystem.icon.DotIcons
import dev.suyash.dot.core.designsystem.theme.DotTheme
import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.model.TaskId
import dev.suyash.dot.core.ui.DateTimePickerDialog
import dev.suyash.dot.core.ui.SnoozeSheetContent
import dev.suyash.dot.core.ui.TimeFormats
import dev.suyash.dot.feature.tasks.ui.QuickAddBar
import dev.suyash.dot.feature.tasks.ui.TaskEditorActions
import dev.suyash.dot.feature.tasks.ui.TaskEditorSheet
import dev.suyash.dot.feature.tasks.ui.TaskRow
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * Home: list tabs, open tasks, a collapsible "Completed" section, and the quick-add bar.
 *
 * @param banner slot for app-level notices (e.g. "reminders need permission").
 * @param openTaskId a task to open on arrival (from a notification tap), consumed via [onOpenTaskHandled].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksRoute(
    onOpenVoice: () -> Unit,
    onOpenSettings: () -> Unit,
    openTaskId: String?,
    onOpenTaskHandled: () -> Unit,
    banner: @Composable () -> Unit = {},
    viewModel: TasksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val formats = remember(context) { TimeFormats.from(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var snoozingId by rememberSaveable { mutableStateOf<String?>(null) }
    var customSnoozeId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var newListDialog by rememberSaveable { mutableStateOf(false) }
    var renameListDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(openTaskId) {
        if (openTaskId != null) {
            editingId = openTaskId
            onOpenTaskHandled()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            scope.launch {
                when (event) {
                    is TasksEvent.Deleted -> {
                        val result = snackbar.showSnackbar("Task deleted", actionLabel = "Undo", withDismissAction = true)
                        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(event.task)
                    }
                    is TasksEvent.Completed -> {
                        val result = snackbar.showSnackbar("Completed", actionLabel = "Undo")
                        if (result == SnackbarResult.ActionPerformed) viewModel.setDone(event.task, false)
                    }
                    is TasksEvent.Snoozed -> snackbar.showSnackbar("Snoozed until ${formats.dayAndTime(event.until, viewModel.now())}")
                    is TasksEvent.Created -> event.at?.let { snackbar.showSnackbar("Reminder set · ${formats.dayAndTime(it, viewModel.now())}") }
                }
            }
        }
    }

    val now = remember(state) { viewModel.now() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Header(
                state = state,
                onSelect = viewModel::select,
                onNewList = { newListDialog = true },
                onRenameList = { renameListDialog = true },
                onDeleteList = { state.selectedList?.let { viewModel.deleteList(it.id) } },
                onOpenSettings = onOpenSettings,
            )
        },
        bottomBar = {
            QuickAddBar(
                now = viewModel::now,
                parse = viewModel::parse,
                onSubmit = viewModel::quickAdd,
                onMic = onOpenVoice,
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = 12.dp, top = 8.dp),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "banner") { banner() }
            if (!state.loading && state.open.isEmpty() && state.completed.isEmpty()) {
                item(key = "empty") { EmptyState() }
            }
            items(state.open, key = { it.id.value }, contentType = { "task" }) { task ->
                TaskRow(
                    task = task,
                    now = now,
                    onToggleDone = { viewModel.setDone(task, !task.isDone) },
                    onToggleStar = { viewModel.setStarred(task, !task.isStarred) },
                    onOpen = { editingId = task.id.value },
                    onSnoozeRequest = { snoozingId = task.id.value },
                    modifier = Modifier.animateItem(),
                )
            }
            if (state.completed.isNotEmpty()) {
                item(key = "completed-header") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showCompleted = !showCompleted }
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                            .animateItem(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionLabel("Completed · ${state.completed.size}", modifier = Modifier.weight(1f))
                        Icon(DotIcons.ChevronDown, contentDescription = if (showCompleted) "Collapse" else "Expand", tint = DotTheme.colors.muted)
                    }
                }
                if (showCompleted) {
                    items(state.completed, key = { it.id.value }, contentType = { "task" }) { task ->
                        TaskRow(
                            task = task,
                            now = now,
                            onToggleDone = { viewModel.setDone(task, false) },
                            onToggleStar = { viewModel.setStarred(task, !task.isStarred) },
                            onOpen = { editingId = task.id.value },
                            onSnoozeRequest = {},
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    editingId?.let { id ->
        val task = viewModel.taskById(TaskId(id))
        if (task == null) {
            if (!state.loading) editingId = null
        } else {
            TaskEditorSheet(
                task = task,
                now = now,
                dayParts = state.settings.dayParts,
                defaultMode = state.settings.defaultRingMode,
                actions = TaskEditorActions(
                    rename = { viewModel.rename(task, it) },
                    setNotes = { viewModel.setNotes(task, it) },
                    setReminder = { at, mode -> viewModel.setReminder(task, at, mode) },
                    setRingMode = { viewModel.setRingMode(task, it) },
                    toggleStar = { viewModel.setStarred(task, !task.isStarred) },
                    toggleDone = { viewModel.setDone(task, !task.isDone) },
                    delete = { viewModel.delete(task) },
                ),
                onDismiss = { editingId = null },
            )
        }
    }

    snoozingId?.let { id ->
        val task: Task? = viewModel.taskById(TaskId(id))
        if (task == null) {
            snoozingId = null
        } else {
            ModalBottomSheet(onDismissRequest = { snoozingId = null }) {
                SnoozeSheetContent(
                    now = viewModel.now(),
                    dayParts = state.settings.dayParts,
                    includeQuick = false,
                    onPick = { until ->
                        snoozingId = null
                        viewModel.snooze(task, until)
                    },
                    onCustom = {
                        snoozingId = null
                        customSnoozeId = id
                    },
                )
            }
        }
    }

    customSnoozeId?.let { id ->
        val task = viewModel.taskById(TaskId(id))
        if (task == null) {
            customSnoozeId = null
        } else {
            DateTimePickerDialog(
                initial = viewModel.now().plusHours(1).withMinute(0),
                onDismiss = { customSnoozeId = null },
                onConfirm = { until: ZonedDateTime ->
                    customSnoozeId = null
                    viewModel.snooze(task, until)
                },
            )
        }
    }

    if (newListDialog) {
        NameDialog(title = "New list", initial = "", onDismiss = { newListDialog = false }, onConfirm = {
            newListDialog = false
            viewModel.createList(it)
        })
    }
    if (renameListDialog) {
        val list = state.selectedList
        if (list == null) {
            renameListDialog = false
        } else {
            NameDialog(title = "Rename list", initial = list.title, onDismiss = { renameListDialog = false }, onConfirm = {
                renameListDialog = false
                viewModel.renameList(list.id, it)
            })
        }
    }
}

@Composable
private fun Header(
    state: TasksUiState,
    onSelect: (ListSelection) -> Unit,
    onNewList: () -> Unit,
    onRenameList: () -> Unit,
    onDeleteList: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val title = when (val selection = state.selection) {
        ListSelection.Starred -> "Starred"
        is ListSelection.InList -> state.lists.firstOrNull { it.id == selection.id }?.title ?: TaskCommands.DEFAULT_LIST_TITLE
    }
    Column(Modifier.statusBarsPadding().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                DotMatrixText(text = title.uppercase().take(12), dotSize = 5.dp, unlitColor = Color.Transparent)
                Spacer(Modifier.height(6.dp))
                SectionLabel("${state.open.size} open")
            }
            if (state.selection is ListSelection.InList) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(DotIcons.More, contentDescription = "List options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename list") }, onClick = { menu = false; onRenameList() })
                        val deletable = (state.selection as ListSelection.InList).id != TaskCommands.DEFAULT_LIST_ID
                        if (deletable) {
                            DropdownMenuItem(text = { Text("Delete list", color = DotTheme.colors.accent) }, onClick = { menu = false; onDeleteList() })
                        }
                    }
                }
            }
            IconButton(onClick = onOpenSettings) { Icon(DotIcons.Settings, contentDescription = "Settings") }
        }
        Spacer(Modifier.height(16.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "starred") {
                DotPill(
                    text = "Starred",
                    selected = state.selection == ListSelection.Starred,
                    onClick = { onSelect(ListSelection.Starred) },
                    leadingIcon = DotIcons.StarFilled,
                )
            }
            items(state.lists, key = { it.id.value }) { list ->
                DotPill(
                    text = list.title,
                    selected = state.selection == ListSelection.InList(list.id),
                    onClick = { onSelect(ListSelection.InList(list.id)) },
                )
            }
            item(key = "new") {
                DotPill(text = "New list", selected = false, onClick = onNewList, leadingIcon = DotIcons.Plus)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 96.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DotMatrixText(text = "ALL CLEAR", dotSize = 5.dp, color = DotTheme.colors.muted)
        Spacer(Modifier.height(16.dp))
        Text("Type below or tap the mic:", style = MaterialTheme.typography.bodyMedium, color = DotTheme.colors.muted)
        Text("“remind me to call mom tomorrow 8 am”", style = MaterialTheme.typography.bodyMedium, color = DotTheme.colors.muted)
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it.take(100) }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
