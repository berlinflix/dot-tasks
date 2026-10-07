package dev.suyash.dot.feature.tasks

import android.content.Context
import android.content.Intent
import androidx.activity.compose.ReportDrawnWhen
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import dev.suyash.dot.core.domain.view.SortOrder
import dev.suyash.dot.core.domain.view.TaskGroup
import dev.suyash.dot.core.ui.DateTimePickerDialog
import dev.suyash.dot.core.ui.SnoozeSheetContent
import dev.suyash.dot.core.ui.TimeFormats
import dev.suyash.dot.feature.tasks.ui.QuickAddBar
import dev.suyash.dot.feature.tasks.ui.TaskEditorActions
import dev.suyash.dot.feature.tasks.ui.TaskEditorSheet
import dev.suyash.dot.feature.tasks.ui.TaskRow
import dev.suyash.dot.feature.tasks.ui.dragReorderItem
import dev.suyash.dot.feature.tasks.ui.rememberDragReorderState
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Home: views (Today, Upcoming, Starred) and lists, sectioned open tasks with nested subtasks, a
 * collapsible "Completed" section, search, and the quick-add bar.
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
    // Startup is "fully drawn" once the tasks are on screen (Android vitals and benchmarks measure to here).
    ReportDrawnWhen { !state.loading }
    val context = LocalContext.current
    val formats = remember(context) { TimeFormats.from(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var snoozingId by rememberSaveable { mutableStateOf<String?>(null) }
    var customSnoozeId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<ListDialog?>(null) }

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
                        val what = if (event.tasks.size > 1) "Task and ${event.tasks.size - 1} subtasks deleted" else "Task deleted"
                        val result = snackbar.showSnackbar(what, actionLabel = "Undo", withDismissAction = true, duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(event.tasks)
                    }
                    is TasksEvent.Completed -> {
                        val text = if (event.task.repeat != null) "Completed · next one is scheduled" else "Completed"
                        val result = snackbar.showSnackbar(text, actionLabel = "Undo", duration = SnackbarDuration.Short)
                        if (result == SnackbarResult.ActionPerformed) viewModel.setDone(event.task, false)
                    }
                    is TasksEvent.Snoozed -> snackbar.showSnackbar("Snoozed until ${formats.dayAndTime(event.until, viewModel.now())}")
                    is TasksEvent.Created -> {
                        val parts = listOfNotNull(
                            event.at?.let { formats.dayAndTime(it, viewModel.now()) },
                            event.repeat?.describe(event.at?.toLocalDate()),
                        )
                        if (parts.isNotEmpty()) snackbar.showSnackbar("Added · " + parts.joinToString(" · "))
                    }
                    is TasksEvent.CompletedCleared -> snackbar.showSnackbar(
                        if (event.count == 1) "Deleted 1 completed task" else "Deleted ${event.count} completed tasks",
                    )
                    is TasksEvent.Moved -> snackbar.showSnackbar("Moved to ${event.listTitle}")
                    TasksEvent.Duplicated -> snackbar.showSnackbar("Copied")
                }
            }
        }
    }

    // Minute precision (the state re-emits every minute): rows whose task didn't change keep an equal
    // `now` and skip recomposition instead of all redrawing on every update.
    val now = remember(state) { viewModel.now().truncatedTo(ChronoUnit.MINUTES) }
    val listState = rememberLazyListState()
    val drag = rememberDragReorderState(listState)
    // Each view starts at the top.
    LaunchedEffect(state.selection) { listState.scrollToItem(0) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (state.query != null) {
                SearchBar(query = state.query.orEmpty(), onQuery = viewModel::search, onClose = viewModel::closeSearch)
            } else {
                Header(
                    state = state,
                    onSelect = viewModel::select,
                    onSearch = viewModel::openSearch,
                    onSort = viewModel::setSort,
                    onDialog = { dialog = it },
                    onOpenSettings = onOpenSettings,
                )
            }
        },
        bottomBar = {
            if (state.query == null) {
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
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (state.query != null) {
                if (!state.query.isNullOrBlank() && state.results.isEmpty()) {
                    item(key = "no-results") { Hint("No tasks match “${state.query}”") }
                }
                items(state.results, key = { it.id.value }, contentType = { "task" }) { task ->
                    TaskRow(
                        task = task,
                        now = now,
                        onToggleDone = { viewModel.setDone(task, !task.isDone) },
                        onToggleStar = { viewModel.setStarred(task, !task.isStarred) },
                        onOpen = { editingId = task.id.value },
                        onSnoozeRequest = { snoozingId = task.id.value },
                        listName = state.listTitle(task.listId),
                        modifier = Modifier.animateItem(),
                    )
                }
                return@LazyColumn
            }

            item(key = "banner") { banner() }
            if (!state.loading && state.sections.isEmpty() && state.completed.isEmpty()) {
                item(key = "empty") { EmptyState(state.selection) }
            }
            for (section in state.sections) {
                if (section.title != null) {
                    item(key = "header-${section.key}", contentType = "header") {
                        SectionLabel(
                            section.title.orEmpty(),
                            color = if (section.overdue) DotTheme.colors.accent else DotTheme.colors.muted,
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp).animateItem(),
                        )
                    }
                }
                val groups = if (state.canReorder) {
                    drag.order?.let { keys -> keys.mapNotNull { key -> section.groups.firstOrNull { it.task.id.value == key } } } ?: section.groups
                } else {
                    section.groups
                }
                items(groups, key = { it.task.id.value }, contentType = { "group" }) { group ->
                    val key = group.task.id.value
                    val itemModifier = if (state.canReorder) {
                        Modifier.dragReorderItem(
                            state = drag,
                            key = key,
                            keys = { section.groups.map { it.task.id.value } },
                            onDrop = { _, index -> viewModel.reorder(group.task, index) },
                        )
                    } else {
                        Modifier
                    }
                    TaskGroupItem(
                        group = group,
                        now = now,
                        listName = if (state.showsListNames) state.listTitle(group.task.listId) else null,
                        onToggleDone = { viewModel.setDone(it, !it.isDone) },
                        onToggleStar = { viewModel.setStarred(it, !it.isStarred) },
                        onOpen = { editingId = it.id.value },
                        onSnooze = { snoozingId = it.id.value },
                        modifier = (if (drag.draggingKey == key) Modifier else Modifier.animateItem()).then(itemModifier),
                    )
                }
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
                subtasks = state.subtasksOf(task.id),
                lists = state.lists,
                parent = task.parentId?.let(viewModel::taskById),
                now = now,
                defaultMode = state.settings.defaultRingMode,
                actions = TaskEditorActions(
                    rename = { viewModel.rename(task, it) },
                    setNotes = { viewModel.setNotes(task, it) },
                    setSchedule = { date, time -> viewModel.setSchedule(task, date, time) },
                    setRingMode = { viewModel.setRingMode(task, it) },
                    setRepeat = { viewModel.setRepeat(task, it) },
                    toggleStar = { viewModel.setStarred(task, !task.isStarred) },
                    toggleDone = { viewModel.setDone(task, !task.isDone) },
                    moveToList = { viewModel.moveToList(task, it) },
                    addSubtask = { viewModel.addSubtask(task, it) },
                    toggleSubtask = { viewModel.setDone(it, !it.isDone) },
                    openTask = { editingId = it.id.value },
                    duplicate = { viewModel.duplicate(task) },
                    share = { shareTask(context, task, formats, viewModel.now()) },
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

    when (dialog) {
        ListDialog.NEW -> NameDialog(title = "New list", initial = "", onDismiss = { dialog = null }, onConfirm = {
            dialog = null
            viewModel.createList(it)
        })
        ListDialog.RENAME -> state.selectedList?.let { list ->
            NameDialog(title = "Rename list", initial = list.title, onDismiss = { dialog = null }, onConfirm = {
                dialog = null
                viewModel.renameList(list.id, it)
            })
        } ?: run { dialog = null }
        ListDialog.DELETE -> state.selectedList?.let { list ->
            ConfirmDialog(
                title = "Delete “${list.title}”?",
                text = "All ${state.openCount + state.completed.size} tasks in this list will be deleted. This can't be undone.",
                confirm = "Delete",
                onDismiss = { dialog = null },
                onConfirm = {
                    dialog = null
                    viewModel.deleteList(list.id)
                },
            )
        } ?: run { dialog = null }
        ListDialog.CLEAR_COMPLETED -> ConfirmDialog(
            title = "Delete all completed tasks?",
            text = "${state.completed.size} completed tasks will be deleted from this list.",
            confirm = "Delete",
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                viewModel.deleteCompleted()
            },
        )
        null -> Unit
    }
}

private enum class ListDialog { NEW, RENAME, DELETE, CLEAR_COMPLETED }

/** A task with its open subtasks underneath; dragged as one. */
@Composable
private fun TaskGroupItem(
    group: TaskGroup,
    now: ZonedDateTime,
    listName: String?,
    onToggleDone: (Task) -> Unit,
    onToggleStar: (Task) -> Unit,
    onOpen: (Task) -> Unit,
    onSnooze: (Task) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        TaskRow(
            task = group.task,
            now = now,
            onToggleDone = { onToggleDone(group.task) },
            onToggleStar = { onToggleStar(group.task) },
            onOpen = { onOpen(group.task) },
            onSnoozeRequest = { onSnooze(group.task) },
            listName = listName,
            subtaskProgress = (group.doneSubtasks to group.totalSubtasks).takeIf { group.totalSubtasks > 0 },
        )
        group.subtasks.forEach { sub ->
            TaskRow(
                task = sub,
                now = now,
                onToggleDone = { onToggleDone(sub) },
                onToggleStar = { onToggleStar(sub) },
                onOpen = { onOpen(sub) },
                onSnoozeRequest = { onSnooze(sub) },
                indent = true,
            )
        }
    }
}

@Composable
private fun Header(
    state: TasksUiState,
    onSelect: (ListSelection) -> Unit,
    onSearch: () -> Unit,
    onSort: (SortOrder) -> Unit,
    onDialog: (ListDialog) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val title = when (val selection = state.selection) {
        ListSelection.Today -> "Today"
        ListSelection.Upcoming -> "Upcoming"
        ListSelection.Starred -> "Starred"
        is ListSelection.InList -> state.listTitle(selection.id)
    }
    Column(Modifier.statusBarsPadding().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                DotMatrixText(text = title.uppercase().take(12), dotSize = 5.dp, unlitColor = Color.Transparent)
                Spacer(Modifier.height(6.dp))
                SectionLabel("${state.openCount} open")
            }
            IconButton(onClick = onSearch) { Icon(DotIcons.Search, contentDescription = "Search") }
            if (state.selection is ListSelection.InList) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(DotIcons.More, contentDescription = "List options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        Text(
                            "Sort by",
                            style = MaterialTheme.typography.labelMedium,
                            color = DotTheme.colors.muted,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        listOf(SortOrder.MY_ORDER to "My order", SortOrder.DATE to "Date", SortOrder.STARRED_RECENTLY to "Starred recently")
                            .forEach { (sort, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    leadingIcon = if (state.sort == sort) ({ Icon(DotIcons.Check, contentDescription = null) }) else null,
                                    onClick = { menu = false; onSort(sort) },
                                )
                            }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Rename list") }, onClick = { menu = false; onDialog(ListDialog.RENAME) })
                        if (state.completed.isNotEmpty()) {
                            DropdownMenuItem(text = { Text("Delete all completed tasks") }, onClick = { menu = false; onDialog(ListDialog.CLEAR_COMPLETED) })
                        }
                        val deletable = (state.selection as ListSelection.InList).id != TaskCommands.DEFAULT_LIST_ID
                        if (deletable) {
                            DropdownMenuItem(text = { Text("Delete list", color = DotTheme.colors.accent) }, onClick = { menu = false; onDialog(ListDialog.DELETE) })
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
            item(key = "today") {
                DotPill(text = "Today", selected = state.selection == ListSelection.Today, onClick = { onSelect(ListSelection.Today) }, leadingIcon = DotIcons.Sun)
            }
            item(key = "upcoming") {
                DotPill(
                    text = "Upcoming",
                    selected = state.selection == ListSelection.Upcoming,
                    onClick = { onSelect(ListSelection.Upcoming) },
                    leadingIcon = DotIcons.Calendar,
                )
            }
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
                DotPill(text = "New list", selected = false, onClick = { onDialog(ListDialog.NEW) }, leadingIcon = DotIcons.Plus)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier.statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(DotIcons.Back, contentDescription = "Close search") }
        TextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Search tasks") },
            singleLine = true,
            leadingIcon = { Icon(DotIcons.Search, contentDescription = null, tint = DotTheme.colors.muted) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) { Icon(DotIcons.Close, contentDescription = "Clear") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, capitalization = KeyboardCapitalization.None),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
    }
}

@Composable
private fun EmptyState(selection: ListSelection) {
    val (headline, hint) = when (selection) {
        ListSelection.Today -> "ALL DONE" to "Nothing due today."
        ListSelection.Upcoming -> "NO PLANS" to "Tasks with a date show up here."
        ListSelection.Starred -> "NO STARS" to "Star important tasks to see them here."
        is ListSelection.InList -> "ALL CLEAR" to null
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 96.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DotMatrixText(text = headline, dotSize = 5.dp, color = DotTheme.colors.muted)
        Spacer(Modifier.height(16.dp))
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = DotTheme.colors.muted)
        } else {
            Text("Type below or tap the mic:", style = MaterialTheme.typography.bodyMedium, color = DotTheme.colors.muted)
            Text("“remind me to call mom tomorrow 8 am”", style = MaterialTheme.typography.bodyMedium, color = DotTheme.colors.muted)
            Text("“water plants every sunday”", style = MaterialTheme.typography.bodyMedium, color = DotTheme.colors.muted)
        }
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = DotTheme.colors.muted,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
    )
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(100) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = DotTheme.colors.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shares a task as plain text through the system share sheet (the user picks where it goes). */
private fun shareTask(context: Context, task: Task, formats: TimeFormats, now: ZonedDateTime) {
    val text = buildString {
        append(task.title)
        val at = task.reminder?.at?.atZone(ZoneId.systemDefault())
        val whenText = at?.let { formats.dayAndTime(it, now) } ?: task.dueDate?.let { formats.day(it, now.toLocalDate()) }
        if (whenText != null) append("\n").append(whenText)
        task.repeat?.let { append("\n").append(it.describe(task.dueDate ?: at?.toLocalDate())) }
        if (task.notes.isNotBlank()) append("\n\n").append(task.notes)
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}
