package dev.suyash.dot.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.suyash.dot.core.data.TaskCommands
import dev.suyash.dot.core.data.TaskDraft
import dev.suyash.dot.core.data.TaskRepository
import dev.suyash.dot.core.data.settings.SettingsRepository
import dev.suyash.dot.core.data.settings.UserSettings
import dev.suyash.dot.core.domain.model.ListId
import dev.suyash.dot.core.domain.model.Reminder
import dev.suyash.dot.core.domain.model.RingMode
import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.model.TaskId
import dev.suyash.dot.core.domain.model.TaskList
import dev.suyash.dot.core.domain.nlp.DateOrder
import dev.suyash.dot.core.domain.nlp.ParsedUtterance
import dev.suyash.dot.core.domain.nlp.ReminderUtteranceParser
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale
import javax.inject.Inject

sealed interface ListSelection {
    data object Starred : ListSelection
    data class InList(val id: ListId) : ListSelection
}

data class TasksUiState(
    val loading: Boolean = true,
    val lists: ImmutableList<TaskList> = persistentListOf(),
    val selection: ListSelection = ListSelection.InList(TaskCommands.DEFAULT_LIST_ID),
    val open: ImmutableList<Task> = persistentListOf(),
    val completed: ImmutableList<Task> = persistentListOf(),
    val settings: UserSettings = UserSettings(),
) {
    val selectedList: TaskList?
        get() = (selection as? ListSelection.InList)?.let { sel -> lists.firstOrNull { it.id == sel.id } }
}

/** One-off UI events (snackbars with undo). */
sealed interface TasksEvent {
    data class Deleted(val task: Task) : TasksEvent
    data class Completed(val task: Task) : TasksEvent
    data class Snoozed(val task: Task, val until: ZonedDateTime) : TasksEvent
    data class Created(val title: String, val at: ZonedDateTime?) : TasksEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val repository: TaskRepository,
    private val commands: TaskCommands,
    settingsRepository: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val selection = MutableStateFlow<ListSelection>(ListSelection.InList(TaskCommands.DEFAULT_LIST_ID))
    private val _events = MutableSharedFlow<TasksEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<TasksEvent> = _events

    private val tasksInSelection = selection.flatMapLatest { sel ->
        when (sel) {
            ListSelection.Starred -> repository.observeStarred()
            is ListSelection.InList -> repository.observeTasks(sel.id)
        }
    }

    val state: StateFlow<TasksUiState> = combine(
        repository.observeLists(),
        selection,
        tasksInSelection,
        settingsRepository.settings,
    ) { lists, sel, tasks, settings ->
        val (done, open) = tasks.partition { it.isDone }
        TasksUiState(
            loading = false,
            lists = lists.toImmutableList(),
            selection = sel,
            open = open.toImmutableList(),
            completed = done.sortedByDescending { it.completedAt }.toImmutableList(),
            settings = settings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState())

    init {
        viewModelScope.launch { commands.ensureDefaultList() }
    }

    fun now(): ZonedDateTime = ZonedDateTime.now(clock)

    fun select(target: ListSelection) {
        selection.value = target
    }

    /** Parses free text ("call mom tomorrow 6pm") exactly like voice input does. */
    fun parse(text: String): ParsedUtterance = parser().parse(text, now())

    fun quickAdd(text: String) {
        val parsed = parse(text)
        val title = parsed.title.ifBlank { text.trim() }
        if (title.isBlank()) return
        val listId = (selection.value as? ListSelection.InList)?.id ?: TaskCommands.DEFAULT_LIST_ID
        val zone = clock.zone
        val at = parsed.at?.atZone(zone)
        viewModelScope.launch {
            commands.createTask(
                TaskDraft(
                    listId = listId,
                    title = title,
                    dueDate = at?.toLocalDate(),
                    reminder = at?.let {
                        Reminder(
                            at = it.toInstant(),
                            zone = zone,
                            mode = if (parsed.ringRequested) RingMode.RING else state.value.settings.defaultRingMode,
                        )
                    },
                    starred = selection.value == ListSelection.Starred,
                ),
            )
            _events.tryEmit(TasksEvent.Created(title, at))
        }
    }

    fun setDone(task: Task, done: Boolean) = viewModelScope.launch {
        commands.setDone(task.id, done)
        if (done) _events.tryEmit(TasksEvent.Completed(task))
    }

    fun setStarred(task: Task, starred: Boolean) = viewModelScope.launch { commands.setStarred(task.id, starred) }

    fun rename(task: Task, title: String) = viewModelScope.launch {
        if (title.isNotBlank()) commands.rename(task.id, title)
    }

    fun setNotes(task: Task, notes: String) = viewModelScope.launch { commands.setNotes(task.id, notes) }

    fun setDueDate(task: Task, date: LocalDate?) = viewModelScope.launch { commands.setDueDate(task.id, date) }

    fun setReminder(task: Task, at: ZonedDateTime?, mode: RingMode) = viewModelScope.launch {
        val reminder = at?.let { Reminder(at = it.toInstant(), zone = it.zone, mode = mode) }
        commands.setReminder(task.id, reminder)
        val due = task.dueDate
        if (at != null && due != null && due.isBefore(at.toLocalDate())) {
            commands.setDueDate(task.id, at.toLocalDate())
        }
    }

    fun setRingMode(task: Task, mode: RingMode) = viewModelScope.launch { commands.setRingMode(task.id, mode) }

    fun snooze(task: Task, until: ZonedDateTime) = viewModelScope.launch {
        commands.snooze(task.id, until, state.value.settings.defaultRingMode)
        _events.tryEmit(TasksEvent.Snoozed(task, until))
    }

    fun delete(task: Task) = viewModelScope.launch {
        commands.delete(task.id)?.let { _events.tryEmit(TasksEvent.Deleted(it)) }
    }

    fun undoDelete(task: Task) = viewModelScope.launch { commands.restore(task.id) }

    /** Reorders within the visible open list: [from]/[to] are indices into [TasksUiState.open]. */
    fun move(task: Task, toIndex: Int) = viewModelScope.launch {
        val open = state.value.open.filter { it.id != task.id }
        val index = toIndex.coerceIn(0, open.size)
        val before = open.getOrNull(index - 1)?.position
        val after = open.getOrNull(index)?.position
        if (before != null && after != null && before >= after) return@launch
        commands.move(task.id, task.listId, before, after)
    }

    fun createList(title: String) = viewModelScope.launch {
        if (title.isBlank()) return@launch
        val id = commands.createList(title)
        selection.value = ListSelection.InList(id)
    }

    fun renameList(id: ListId, title: String) = viewModelScope.launch {
        if (title.isNotBlank()) commands.renameList(id, title)
    }

    fun deleteList(id: ListId) = viewModelScope.launch {
        if (id == TaskCommands.DEFAULT_LIST_ID) return@launch
        commands.deleteList(id)
        selection.value = ListSelection.InList(TaskCommands.DEFAULT_LIST_ID)
    }

    fun taskById(id: TaskId): Task? = (state.value.open + state.value.completed).firstOrNull { it.id == id }

    private fun parser(): ReminderUtteranceParser {
        val country = Locale.getDefault().country
        val order = if (country in MONTH_FIRST_COUNTRIES) DateOrder.MONTH_DAY else DateOrder.DAY_MONTH
        return ReminderUtteranceParser(state.value.settings.dayParts, order)
    }

    private companion object {
        val MONTH_FIRST_COUNTRIES = setOf("US", "PH", "FM", "MH", "PW", "BZ")
    }
}
