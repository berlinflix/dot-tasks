package dev.suyash.dot.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.suyash.dot.core.data.settings.SettingsRepository
import dev.suyash.dot.core.data.settings.UserSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repository: SettingsRepository) : ViewModel() {

    val settings: StateFlow<UserSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    fun update(transform: (UserSettings) -> UserSettings) {
        viewModelScope.launch { repository.update(transform) }
    }
}
