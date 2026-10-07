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
import dev.suyash.dot.core.domain.repeat.RepeatRule
import dev.suyash.dot.core.domain.time.DayParts
import dev.suyash.dot.core.domain.view.SortOrder
import dev.suyash.dot.core.domain.view.TaskSection
import dev.suyash.dot.core.domain.view.TaskViews
import dev.suyash.dot.core.ui.TimeFormats
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale
import javax.inject.Inject

sealed interface ListSelection {
    /** Overdue and due today, across lists. */
    data object Today : ListSelection

    /** Everything scheduled from today on, across lists. */
    data object Upcoming : ListSelection

    data object Starred : ListSelection

    data class InList(val id: ListId) : ListSelection
}

data class TasksUiState(
    val loading: Boolean = true,
    val lists: ImmutableList<TaskList> = persistentListOf(),
    val selection: ListSelection = ListSelection.InList(TaskCommands.DEFAULT_LIST_ID),
    val sort: SortOrder = SortOrder.MY_ORDER,
    val sections: ImmutableList<TaskSection> = persistentListOf(),
    val completed: ImmutableList<Task> = persistentListOf(),
    val openCount: Int = 0,
    /** Every task the screen knows about (for the editor's subtasks and lookups). */
    val tasks: ImmutableMap<TaskId, Task> = persistentMapOf(),
    /** Non-null while searching. */
    val query: String? = null,
    val results: ImmutableList<Task> = persistentListOf(),
    val settings: UserSettings = UserSettings(),
) {
    val selectedList: TaskList?
        get() = (selection as? ListSelection.InList)?.let { sel -> lists.firstOrNull { it.id == sel.id } }

    /** Drag-to-reorder only makes sense in a list's own order. */
    val canReorder: Boolean get() = selection is ListSelection.InList && sort == SortOrder.MY_ORDER && query == null

    val showsListNames: Boolean get() = selection !is ListSelection.InList || query != null

    fun listTitle(id: ListId): String = lists.firstOrNull { it.id == id }?.title ?: TaskCommands.DEFAULT_LIST_TITLE

    fun subtasksOf(id: TaskId): List<Task> =
        tasks.values.filter { it.parentId == id }.sortedWith(compareBy<Task>({ it.isDone }, { it.position }, { it.id.value }))
}

/** One-off UI events (snackbars, mostly with undo). */
sealed interface TasksEvent {
    data class Deleted(val tasks: List<Task>) : TasksEvent
    data class Completed(val task: Task) : TasksEvent
    data class Snoozed(val task: Task, val until: ZonedDateTime) : TasksEvent
    data class Created(val title: String, val at: ZonedDateTime?, val repeat: RepeatRule?) : TasksEvent
    data class CompletedCleared(val count: Int) : TasksEvent
    data class Moved(val listTitle: String) : TasksEvent
    data object Duplicated : TasksEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val repository: TaskRepository,
    private val commands: TaskCommands,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val selection = MutableStateFlow<ListSelection>(ListSelection.InList(TaskCommands.DEFAULT_LIST_ID))
    private val query = MutableStateFlow<String?>(null)
    private val _events = MutableSharedFlow<TasksEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<TasksEvent> = _events

    /** Day headers ("Today", "Tomorrow", "Sat 10 Oct") need no Context. */
    private val dayLabels = TimeFormats(is24Hour = false, locale = Locale.getDefault())

    /** Re-evaluates "today" and relative labels every minute while the screen is visible. */
    private val minuteTicks: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    private val tasksInSelection = selection.flatMapLatest { sel ->
        when (sel) {
            is ListSelection.InList -> repository.observeTasks(sel.id)
            else -> repository.observeAll()
        }
    }

    private val searchResults = query.flatMapLatest { q ->
        if (q.isNullOrBlank()) flowOf(emptyList()) else repository.search(q)
    }

    private val inputs = combine(repository.observeLists(), selection, tasksInSelection, settingsRepository.settings, minuteTicks) {
            lists, sel, tasks, settings, _ ->
        Inputs(lists, sel, tasks, settings)
    }

    val state: StateFlow<TasksUiState> = combine(inputs, query, searchResults) { input, q, results ->
        val now = now()
        val today = now.toLocalDate()
        val zone = now.zone
        val label: (LocalDate) -> String = { dayLabels.day(it, today) }
        val sort = (input.selection as? ListSelection.InList)?.let { input.settings.sortOf(it.id.value) } ?: SortOrder.MY_ORDER
        val sections = when (val sel = input.selection) {
            ListSelection.Today -> TaskViews.today(input.tasks, today, zone)
            ListSelection.Upcoming -> TaskViews.upcoming(input.tasks, today, zone, label)
            ListSelection.Starred -> TaskViews.starred(input.tasks)
            is ListSelection.InList -> TaskViews.list(input.tasks, sort, today, zone, label)
        }
        TasksUiState(
            loading = false,
            lists = input.lists.toImmutableList(),
            selection = input.selection,
            sort = sort,
            sections = sections.toImmutableList(),
            completed = if (input.selection is ListSelection.InList) TaskViews.completed(input.tasks).toImmutableList() else persistentListOf(),
            openCount = sections.sumOf { it.groups.size },
            tasks = (input.tasks + results).associateBy { it.id }.toImmutableMap(),
            query = q,
            results = results.toImmutableList(),
            settings = input.settings,
        )
    }
        // Sorting, grouping and sectioning every task runs on each change and every minute: keep it off
        // the main thread so the list never drops frames.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState())

    private class Inputs(val lists: List<TaskList>, val selection: ListSelection, val tasks: List<Task>, val settings: UserSettings)

    init {
        viewModelScope.launch { commands.ensureDefaultList() }
    }

    fun now(): ZonedDateTime = ZonedDateTime.now(clock)

    fun select(target: ListSelection) {
        selection.value = target
    }

    fun setSort(sort: SortOrder) {
        val list = (selection.value as? ListSelection.InList)?.id ?: return
        viewModelScope.launch {
            settingsRepository.update { it.copy(listSorts = it.listSorts + (list.value to sort)) }
        }
    }

    fun openSearch() {
        if (query.value == null) query.value = ""
    }

    fun search(text: String) {
        query.value = text.take(100)
    }

    fun closeSearch() {
        query.value = null
    }

    /** Parses free text ("call mom tomorrow 6pm", "gym every monday 7am") exactly like voice input does. */
    suspend fun parse(text: String): ParsedUtterance = withContext(Dispatchers.Default) { parser().parse(text, now()) }

    fun quickAdd(text: String) {
        val sel = selection.value
        viewModelScope.launch {
            val parsed = parse(text)
            val title = parsed.title.ifBlank { text.trim() }
            if (title.isBlank()) return@launch
            val listId = (sel as? ListSelection.InList)?.id ?: TaskCommands.DEFAULT_LIST_ID
            val zone = clock.zone
            val at = parsed.at?.atZone(zone)
            commands.createTask(
                TaskDraft(
                    listId = listId,
                    title = title,
                    // Added from "Today" without a date: it's for today.
                    dueDate = at?.toLocalDate() ?: LocalDate.now(clock).takeIf { sel == ListSelection.Today },
                    reminder = at?.let {
                        Reminder(
                            at = it.toInstant(),
                            zone = zone,
                            mode = if (parsed.ringRequested) RingMode.RING else state.value.settings.defaultRingMode,
                        )
                    },
                    starred = sel == ListSelection.Starred,
                    repeat = parsed.repeat,
                ),
            )
            _events.tryEmit(TasksEvent.Created(title, at, parsed.repeat))
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

    /** Date with an optional time (= reminder); null date clears both. */
    fun setSchedule(task: Task, date: LocalDate?, time: LocalTime?) = viewModelScope.launch {
        commands.setSchedule(task.id, date, time, state.value.settings.defaultRingMode, clock.zone)
    }

    fun setRingMode(task: Task, mode: RingMode) = viewModelScope.launch { commands.setRingMode(task.id, mode) }

    fun setRepeat(task: Task, rule: RepeatRule?) = viewModelScope.launch { commands.setRepeat(task.id, rule) }

    fun snooze(task: Task, until: ZonedDateTime) = viewModelScope.launch {
        commands.snooze(task.id, until, state.value.settings.defaultRingMode)
        _events.tryEmit(TasksEvent.Snoozed(task, until))
    }

    fun delete(task: Task) = viewModelScope.launch {
        val deleted = commands.delete(task.id)
        if (deleted.isNotEmpty()) _events.tryEmit(TasksEvent.Deleted(deleted))
    }

    fun undoDelete(tasks: List<Task>) = viewModelScope.launch { commands.restore(tasks) }

    fun addSubtask(parent: Task, title: String) = viewModelScope.launch {
        if (title.isNotBlank()) commands.addSubtask(parent.id, title)
    }

    fun moveToList(task: Task, listId: ListId) = viewModelScope.launch {
        commands.moveToList(task.id, listId)
        _events.tryEmit(TasksEvent.Moved(state.value.listTitle(listId)))
    }

    fun duplicate(task: Task) = viewModelScope.launch {
        if (commands.duplicate(task.id) != null) _events.tryEmit(TasksEvent.Duplicated)
    }

    /** Drag-and-drop in "My order": [toIndex] is the new index among the open tasks shown. */
    fun reorder(task: Task, toIndex: Int) = viewModelScope.launch {
        val shown = state.value.sections.flatMap { it.groups }.map { it.task }.filter { it.id != task.id }
        val index = toIndex.coerceIn(0, shown.size)
        commands.reorder(task.id, before = shown.getOrNull(index - 1)?.position, after = shown.getOrNull(index)?.position)
    }

    fun deleteCompleted() {
        val list = (selection.value as? ListSelection.InList)?.id ?: return
        viewModelScope.launch {
            val count = commands.deleteCompleted(list)
            if (count > 0) _events.tryEmit(TasksEvent.CompletedCleared(count))
        }
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

    fun taskById(id: TaskId): Task? = state.value.tasks[id]

    /** Reused while the day-part settings and date order stay the same (the live preview parses often). */
    private var cachedParser: ReminderUtteranceParser? = null
    private var cachedParserKey: Pair<DayParts, DateOrder>? = null

    @Synchronized
    private fun parser(): ReminderUtteranceParser {
        val country = Locale.getDefault().country
        val order = if (country in MONTH_FIRST_COUNTRIES) DateOrder.MONTH_DAY else DateOrder.DAY_MONTH
        val key = state.value.settings.dayParts to order
        cachedParser?.takeIf { cachedParserKey == key }?.let { return it }
        return ReminderUtteranceParser(key.first, key.second).also {
            cachedParser = it
            cachedParserKey = key
        }
    }

    private companion object {
        val MONTH_FIRST_COUNTRIES = setOf("US", "PH", "FM", "MH", "PW", "BZ")
    }
}
