package dev.suyash.dot.feature.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.suyash.dot.core.data.TaskCommands
import dev.suyash.dot.core.data.TaskDraft
import dev.suyash.dot.core.data.settings.SettingsRepository
import dev.suyash.dot.core.domain.model.Reminder
import dev.suyash.dot.core.domain.model.RingMode
import dev.suyash.dot.core.domain.model.TaskId
import dev.suyash.dot.core.domain.model.TaskSource
import dev.suyash.dot.core.domain.nlp.DateOrder
import dev.suyash.dot.core.domain.nlp.ReminderUtteranceParser
import dev.suyash.dot.core.domain.time.DayParts
import dev.suyash.dot.feature.voice.speech.OnDeviceSpeechEngine
import dev.suyash.dot.feature.voice.speech.SpeechEvent
import dev.suyash.dot.feature.voice.speech.SpeechFailure
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZonedDateTime
import java.util.Locale
import javax.inject.Inject

sealed interface VoiceState {
    data object Starting : VoiceState

    data class Listening(val partial: String = "", val levels: ImmutableList<Float> = persistentListOf()) : VoiceState

    /** Saved. [at] null = no time was said (offer quick reminder chips). */
    data class Saved(
        val taskId: TaskId,
        val title: String,
        val at: ZonedDateTime?,
        val mode: RingMode,
        val heard: String,
        /** e.g. "Weekly on Mon" when a repeat was said. */
        val repeat: String? = null,
    ) : VoiceState

    data class Failed(val reason: SpeechFailure) : VoiceState

    /** On-device recognition unavailable (or the user chose to type). */
    data class Typing(val unavailable: Boolean, val initial: String = "") : VoiceState

    data object NeedsPermission : VoiceState
}

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val engine: OnDeviceSpeechEngine,
    private val commands: TaskCommands,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Starting)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var listenJob: Job? = null
    private var userTouched = false
    var dayParts: DayParts = DayParts()
        private set

    init {
        viewModelScope.launch { dayParts = settings.current().dayParts }
    }

    fun now(): ZonedDateTime = ZonedDateTime.now(clock)

    fun start() {
        if (!engine.isAvailable()) {
            _state.value = VoiceState.Typing(unavailable = true)
            return
        }
        listen(Locale.getDefault(), allowFallback = true)
    }

    private fun listen(locale: Locale, allowFallback: Boolean) {
        listenJob?.cancel()
        _state.value = VoiceState.Listening()
        listenJob = viewModelScope.launch {
            engine.listen(locale).collect { event ->
                when (event) {
                    SpeechEvent.Ready -> Unit
                    is SpeechEvent.Level -> _state.update { current ->
                        if (current is VoiceState.Listening) {
                            current.copy(levels = (current.levels + event.rmsDb).takeLast(LEVEL_HISTORY).toImmutableList())
                        } else {
                            current
                        }
                    }
                    is SpeechEvent.Partial -> _state.update { current ->
                        if (current is VoiceState.Listening) current.copy(partial = event.text) else current
                    }
                    is SpeechEvent.Final -> save(event.text)
                    is SpeechEvent.Failed -> {
                        if (event.reason == SpeechFailure.LANGUAGE_UNAVAILABLE && allowFallback && locale != Locale.US) {
                            // The phone's language has no offline model; try US English, then offer a download.
                            engine.requestModelDownload(locale)
                            listen(Locale.US, allowFallback = false)
                        } else {
                            _state.value = VoiceState.Failed(event.reason)
                        }
                    }
                }
            }
        }
    }

    fun permissionDenied() {
        _state.value = VoiceState.NeedsPermission
    }

    fun typeInstead() {
        listenJob?.cancel()
        _state.value = VoiceState.Typing(unavailable = false)
    }

    /** Opened to type (shortcut) or with text shared from another app: nothing is saved until confirmed. */
    fun startTyping(initial: String) {
        listenJob?.cancel()
        _state.value = VoiceState.Typing(unavailable = false, initial = initial)
    }

    fun submitTyped(text: String) {
        if (text.isNotBlank()) save(text)
    }

    fun retry() = start()

    /** Called when the user interacts with the confirmation card, so it doesn't auto-close. */
    fun touched() {
        userTouched = true
    }

    fun shouldAutoClose(): Boolean = !userTouched

    private fun save(heard: String) {
        listenJob?.cancel()
        viewModelScope.launch {
            val current = settings.current()
            val parser = ReminderUtteranceParser(current.dayParts, dateOrder())
            val now = now()
            val parsed = parser.parse(heard, now)
            val title = parsed.title.ifBlank { heard.trim().replaceFirstChar { it.titlecase(Locale.getDefault()) } }
            val at = parsed.at?.atZone(clock.zone)
            val mode = if (parsed.ringRequested) RingMode.RING else current.defaultRingMode
            val id = commands.createTask(
                TaskDraft(
                    listId = TaskCommands.DEFAULT_LIST_ID,
                    title = title,
                    dueDate = at?.toLocalDate(),
                    reminder = at?.let { Reminder(at = it.toInstant(), zone = it.zone, mode = mode) },
                    source = TaskSource.VOICE,
                    repeat = parsed.repeat,
                ),
            )
            _state.value = VoiceState.Saved(
                taskId = id,
                title = title,
                at = at,
                mode = mode,
                heard = heard,
                repeat = parsed.repeat?.describe(at?.toLocalDate()),
            )
        }
    }

    fun undo() {
        val saved = _state.value as? VoiceState.Saved ?: return
        viewModelScope.launch { commands.delete(saved.taskId) }
    }

    /** "No time was said" → one-tap reminder. */
    fun remindAt(at: ZonedDateTime) {
        val saved = _state.value as? VoiceState.Saved ?: return
        viewModelScope.launch {
            commands.setReminder(saved.taskId, Reminder(at = at.toInstant(), zone = at.zone, mode = saved.mode))
            commands.setDueDate(saved.taskId, at.toLocalDate())
            _state.value = saved.copy(at = at)
        }
    }

    fun toggleMode() {
        val saved = _state.value as? VoiceState.Saved ?: return
        val mode = if (saved.mode == RingMode.RING) RingMode.NOTIFY else RingMode.RING
        viewModelScope.launch {
            commands.setRingMode(saved.taskId, mode)
            _state.value = saved.copy(mode = mode)
        }
    }

    private fun dateOrder(): DateOrder =
        if (Locale.getDefault().country in setOf("US", "PH", "FM", "MH", "PW", "BZ")) DateOrder.MONTH_DAY else DateOrder.DAY_MONTH

    override fun onCleared() {
        listenJob?.cancel()
    }

    private companion object {
        const val LEVEL_HISTORY = 28
    }
}
