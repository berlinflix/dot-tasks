package dev.suyash.dot.feature.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.suyash.dot.core.data.export.TaskExporter
import dev.suyash.dot.core.data.settings.SettingsRepository
import dev.suyash.dot.core.data.settings.UserSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val exporter: TaskExporter,
) : ViewModel() {

    val settings: StateFlow<UserSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages

    fun update(transform: (UserSettings) -> UserSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    /** Writes the JSON export to the file the user picked. */
    fun export(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val count = runCatching { resolver.openOutputStream(uri, "wt")?.use { exporter.exportJson(it) } }.getOrNull()
            _messages.tryEmit(if (count != null) "Exported $count tasks" else "Couldn't save the export")
        }
    }
}
