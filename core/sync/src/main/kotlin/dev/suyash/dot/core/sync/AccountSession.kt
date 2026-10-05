package dev.suyash.dot.core.sync

import android.app.Activity
import android.util.Log
import dev.suyash.dot.core.auth.AccountUser
import dev.suyash.dot.core.auth.AuthRepository
import dev.suyash.dot.core.auth.SignInResult
import dev.suyash.dot.core.crypto.AccountCrypto
import dev.suyash.dot.core.crypto.AccountKeys
import dev.suyash.dot.core.crypto.BlockStoreKeyBackup
import dev.suyash.dot.core.crypto.Keyring
import dev.suyash.dot.core.crypto.LocalAccountKeyStore
import dev.suyash.dot.core.crypto.RecoveryKey
import dev.suyash.dot.core.data.di.ApplicationScope
import dev.suyash.dot.core.data.sync.SyncStore
import dev.suyash.dot.core.sync.remote.RemoteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AccountState {
    /** Not signed in: tasks live only on this phone (still encrypted at rest). */
    data object SignedOut : AccountState

    data class Working(val message: String) : AccountState

    /** First sign-in: show the new recovery key once and ask the user to save it. */
    data class ShowRecoveryKey(val user: AccountUser, val recoveryKey: RecoveryKey, val cloudBackup: Boolean) : AccountState

    /** Keys exist in the cloud but this phone can't unlock them automatically. */
    data class NeedsRecoveryKey(val user: AccountUser, val wrongKey: Boolean = false) : AccountState

    data class Ready(val user: AccountUser) : AccountState

    data class Error(val message: String, val user: AccountUser?) : AccountState
}

/**
 * Owns the signed-in account and its encryption keys.
 *
 * On sign-in the account key is found, in order:
 *  1. on this phone (Keystore-wrapped),
 *  2. in Block Store (end-to-end encrypted Google backup → seamless reinstall/new phone),
 *  3. by asking for the recovery key.
 * A brand-new account generates keys and shows the recovery key once.
 */
@Singleton
class AccountSession @Inject constructor(
    private val auth: AuthRepository,
    private val remote: RemoteStore,
    private val localKeys: LocalAccountKeyStore,
    private val blockStore: BlockStoreKeyBackup,
    private val syncStore: SyncStore,
    private val scheduler: SyncScheduler,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AccountState>(AccountState.SignedOut)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    @Volatile
    private var keys: AccountKeys? = null
    private var pendingKeyring: Keyring? = null
    private val mutex = Mutex()

    /** Keys for encrypting/decrypting records, or null if sync isn't ready. */
    fun keysOrNull(): AccountKeys? = keys

    /** Restores the session at app start (no UI, no network needed if keys are on this phone). */
    fun restore() {
        val user = auth.current() ?: return
        scope.launch {
            mutex.withLock {
                val local = withContext(Dispatchers.IO) { localKeys.load(user.uid) }
                if (local != null) {
                    keys = local
                    _state.value = AccountState.Ready(user)
                    scheduler.syncSoon()
                } else {
                    resolveKeys(user)
                }
            }
        }
    }

    suspend fun signIn(activity: Activity): SignInResult {
        _state.value = AccountState.Working("Signing in…")
        val result = auth.signIn(activity)
        when (result) {
            is SignInResult.Success -> mutex.withLock { resolveKeys(result.user) }
            else -> _state.value = AccountState.SignedOut
        }
        return result
    }

    private suspend fun resolveKeys(user: AccountUser) {
        _state.value = AccountState.Working("Unlocking your encrypted tasks…")
        try {
            withContext(Dispatchers.IO) { localKeys.load(user.uid) }?.let { return becomeReady(user, it) }

            val keyring = remote.keyring(user.uid)
            if (keyring == null) {
                createNewAccount(user)
                return
            }
            val fromBackup = blockStore.load(user.uid)
            if (fromBackup != null && AccountCrypto.matches(fromBackup, keyring)) {
                val restored = AccountKeys(user.uid, fromBackup, AccountCrypto.decryptKeyset(keyring.encryptedDataKeyset, fromBackup, user.uid))
                withContext(Dispatchers.IO) { localKeys.save(restored, keyring.encryptedDataKeyset) }
                becomeReady(user, restored)
                return
            }
            pendingKeyring = keyring
            _state.value = AccountState.NeedsRecoveryKey(user)
        } catch (e: Exception) {
            Log.w(TAG, "Could not unlock account: ${e.javaClass.simpleName}")
            _state.value = AccountState.Error("Couldn't reach your encrypted backup. Check your connection and try again.", user)
        }
    }

    private suspend fun createNewAccount(user: AccountUser) {
        val created = withContext(Dispatchers.Default) { AccountCrypto.createAccount(user.uid) }
        if (!remote.createKeyring(user.uid, created.keyring)) {
            // Another device created the account at the same moment — use its keys instead.
            resolveKeys(user)
            return
        }
        val accountKeys = AccountKeys(
            user.uid,
            created.masterKey,
            AccountCrypto.decryptKeyset(created.keyring.encryptedDataKeyset, created.masterKey, user.uid),
        )
        withContext(Dispatchers.IO) { localKeys.save(accountKeys, created.keyring.encryptedDataKeyset) }
        val cloudBackup = runCatching { blockStore.save(user.uid, created.masterKey) }.getOrDefault(false)
        keys = accountKeys
        // Anything created before signing in is uploaded to the new account.
        syncStore.enqueueEverything(System.currentTimeMillis())
        _state.value = AccountState.ShowRecoveryKey(user, created.recoveryKey, cloudBackup)
    }

    /** The user typed their recovery key. */
    suspend fun submitRecoveryKey(text: String): Boolean = mutex.withLock {
        val current = _state.value as? AccountState.NeedsRecoveryKey ?: return@withLock false
        val keyring = pendingKeyring ?: remote.keyring(current.user.uid) ?: return@withLock false
        val recoveryKey = RecoveryKey.parse(text)
        val masterKey = recoveryKey?.let {
            runCatching { AccountCrypto.unwrapMasterKey(keyring, it, current.user.uid) }.getOrNull()
        }
        if (masterKey == null) {
            _state.value = current.copy(wrongKey = true)
            return@withLock false
        }
        val restored = AccountKeys(current.user.uid, masterKey, AccountCrypto.decryptKeyset(keyring.encryptedDataKeyset, masterKey, current.user.uid))
        withContext(Dispatchers.IO) { localKeys.save(restored, keyring.encryptedDataKeyset) }
        runCatching { blockStore.save(current.user.uid, masterKey) }
        pendingKeyring = null
        becomeReady(current.user, restored)
        true
    }

    /** The user confirmed they saved the recovery key shown after creating the account. */
    fun recoveryKeySaved() {
        val current = _state.value as? AccountState.ShowRecoveryKey ?: return
        _state.value = AccountState.Ready(current.user)
        scheduler.syncNow()
    }

    /**
     * Replaces the recovery key (e.g. if the old one may have been seen by someone). Returns the new key
     * to show once. The account key and data are unchanged.
     */
    suspend fun rotateRecoveryKey(): RecoveryKey? = mutex.withLock {
        val user = (_state.value as? AccountState.Ready)?.user ?: return@withLock null
        val accountKeys = keys ?: return@withLock null
        val keyring = remote.keyring(user.uid) ?: return@withLock null
        val (newKey, newKeyring) = AccountCrypto.rotateRecoveryKey(accountKeys.masterKey, keyring, user.uid)
        remote.replaceKeyring(user.uid, newKeyring)
        newKey
    }

    /** Signs out and removes this account's tasks and keys from the phone (the encrypted cloud copy stays). */
    suspend fun signOut() = mutex.withLock {
        val uid = auth.current()?.uid
        scheduler.cancelAll()
        auth.signOut()
        keys = null
        pendingKeyring = null
        if (uid != null) withContext(Dispatchers.IO) { localKeys.delete(uid) }
        syncStore.wipe()
        _state.value = AccountState.SignedOut
    }

    /** Deletes the cloud copy, the Block Store key, local data and the account itself. */
    suspend fun deleteAccount(activity: Activity): Boolean = mutex.withLock {
        val user = auth.current() ?: return@withLock false
        _state.value = AccountState.Working("Deleting your account…")
        return@withLock try {
            scheduler.cancelAll()
            remote.deleteEverything(user.uid)
            blockStore.delete(user.uid)
            withContext(Dispatchers.IO) { localKeys.delete(user.uid) }
            syncStore.wipe()
            keys = null
            val deleted = auth.deleteAccount(activity)
            auth.signOut()
            _state.value = AccountState.SignedOut
            deleted
        } catch (e: Exception) {
            Log.w(TAG, "Account deletion failed: ${e.javaClass.simpleName}")
            _state.value = AccountState.Error("Deletion didn't finish. Check your connection and try again.", user)
            false
        }
    }

    /** "Lost my recovery key": wipe the unreadable cloud copy and start a fresh encrypted account. */
    suspend fun startOver() = mutex.withLock {
        val user = (state.value as? AccountState.NeedsRecoveryKey)?.user ?: return@withLock
        _state.value = AccountState.Working("Starting over…")
        remote.deleteEverything(user.uid)
        blockStore.delete(user.uid)
        createNewAccount(user)
    }

    fun retry() {
        val user = auth.current() ?: run { _state.value = AccountState.SignedOut; return }
        scope.launch { mutex.withLock { resolveKeys(user) } }
    }

    private fun becomeReady(user: AccountUser, accountKeys: AccountKeys) {
        keys = accountKeys
        _state.value = AccountState.Ready(user)
        scheduler.syncNow()
    }

    private companion object {
        const val TAG = "AccountSession"
    }
}
