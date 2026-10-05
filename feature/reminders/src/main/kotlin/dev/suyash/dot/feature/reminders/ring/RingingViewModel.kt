package dev.suyash.dot.feature.reminders.ring

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.suyash.dot.core.data.TaskCommands
import dev.suyash.dot.core.data.TaskRepository
import dev.suyash.dot.core.data.settings.SettingsRepository
import dev.suyash.dot.core.data.settings.UserSettings
import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.model.TaskId
import dev.suyash.dot.feature.reminders.alarm.ReminderIntents
import dev.suyash.dot.feature.reminders.notify.ReminderNotifier
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Inject

data class RingingUiState(
    val loading: Boolean = true,
    /** Tasks still waiting for an answer (not done, not snoozed/rescheduled). */
    val tasks: ImmutableList<Task> = persistentListOf(),
    val settings: UserSettings = UserSettings(),
    /** All reminders were answered elsewhere (notification, another device) — close the screen. */
    val finished: Boolean = false,
)

@HiltViewModel
class RingingViewModel @Inject constructor(
    savedState: SavedStateHandle,
    repository: TaskRepository,
    settingsRepository: SettingsRepository,
    private val commands: TaskCommands,
    private val notifier: ReminderNotifier,
    private val clock: Clock,
) : ViewModel() {

    private val ids: List<TaskId> =
        savedState.get<Array<String>>(ReminderIntents.EXTRA_TASK_IDS)?.map(::TaskId).orEmpty()

    private val acted = MutableStateFlow(false)

    private val tasksFlow = if (ids.isEmpty()) {
        flowOf(emptyList())
    } else {
        combine(ids.map { repository.observeTask(it) }) { tasks -> tasks.filterNotNull() }
    }

    val state: StateFlow<RingingUiState> =
        combine(tasksFlow, settingsRepository.settings, acted) { tasks, settings, didAct ->
            val waiting = tasks.filter { !it.isDone && it.reminder?.hasFired == true }
            RingingUiState(
                loading = false,
                tasks = waiting.toImmutableList(),
                settings = settings,
                finished = didAct || (ids.isNotEmpty() && waiting.isEmpty()),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RingingUiState())

    fun now(): ZonedDateTime = ZonedDateTime.now(clock)

    fun snooze(until: ZonedDateTime) = act { tasks ->
        tasks.forEach { commands.snooze(it.id, until) }
    }

    fun done() = act { tasks ->
        tasks.forEach { commands.setDone(it.id, true) }
    }

    /** Volume key: stop the sound, keep the reminders as quiet notifications. */
    fun silence() {
        val current = state.value
        notifier.silenceRinging(current.tasks, current.settings, now())
    }

    private fun act(block: suspend (List<Task>) -> Unit) {
        val tasks = state.value.tasks
        notifier.cancelRinging()
        notifier.cancel(tasks.map { it.id })
        viewModelScope.launch {
            block(tasks)
            acted.value = true
        }
    }
}
