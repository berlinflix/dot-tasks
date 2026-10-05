package dev.suyash.dot.feature.account

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.suyash.dot.core.auth.SignInResult
import dev.suyash.dot.core.crypto.RecoveryKey
import dev.suyash.dot.core.data.sync.SyncStore
import dev.suyash.dot.core.sync.AccountSession
import dev.suyash.dot.core.sync.AccountState
import dev.suyash.dot.core.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val session: AccountSession,
    private val scheduler: SyncScheduler,
    syncStore: SyncStore,
) : ViewModel() {

    val state: StateFlow<AccountState> = session.state

    val pendingChanges: StateFlow<Int> =
        syncStore.observePendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** A rotated recovery key to show once. */
    private val _rotatedKey = MutableStateFlow<RecoveryKey?>(null)
    val rotatedKey: StateFlow<RecoveryKey?> = _rotatedKey.asStateFlow()

    fun signIn(activity: Activity) = viewModelScope.launch {
        _message.value = when (val result = session.signIn(activity)) {
            is SignInResult.Success, SignInResult.Cancelled -> null
            SignInResult.NoGoogleAccount -> "Add a Google account to this phone first (Settings → Passwords & accounts)."
            is SignInResult.Failed -> result.message
        }
    }

    fun submitRecoveryKey(text: String) = viewModelScope.launch { session.submitRecoveryKey(text) }

    fun recoveryKeySaved() = session.recoveryKeySaved()

    fun syncNow() = scheduler.syncNow()

    fun signOut() = viewModelScope.launch { session.signOut() }

    fun deleteAccount(activity: Activity) = viewModelScope.launch {
        _message.value = if (session.deleteAccount(activity)) "Your account and encrypted cloud data were deleted." else null
    }

    fun startOver() = viewModelScope.launch { session.startOver() }

    fun retry() = session.retry()

    fun rotateRecoveryKey() = viewModelScope.launch {
        _rotatedKey.value = session.rotateRecoveryKey()
        if (_rotatedKey.value == null) _message.value = "Couldn't create a new recovery key. Check your connection."
    }

    fun rotatedKeySaved() {
        _rotatedKey.value = null
    }

    fun dismissMessage() {
        _message.value = null
    }
}
