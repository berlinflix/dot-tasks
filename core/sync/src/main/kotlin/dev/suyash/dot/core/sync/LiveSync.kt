package dev.suyash.dot.core.sync

import dev.suyash.dot.core.data.di.ApplicationScope
import dev.suyash.dot.core.data.sync.SyncStore
import dev.suyash.dot.core.sync.remote.RemoteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * While the app is in the foreground and the account is unlocked, listens for changes made on other
 * devices and pulls them right away. Stops listening in the background (WorkManager catches up).
 *
 * Dependencies are resolved lazily on a background coroutine, so starting/stopping never opens the
 * database on the main thread.
 */
@Singleton
class LiveSync @Inject constructor(
    private val session: Provider<AccountSession>,
    private val engine: Provider<SyncEngine>,
    private val remote: Provider<RemoteStore>,
    private val store: Provider<SyncStore>,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var job: Job? = null

    @OptIn(FlowPreview::class)
    @Synchronized
    fun start() {
        job?.cancel()
        job = scope.launch {
            session.get().state.collectLatest { state ->
                if (state !is AccountState.Ready) return@collectLatest
                engine.get().sync()
                remote.get().changes(state.user.uid, store.get().cursor())
                    .debounce(500)
                    .collect { engine.get().pullOnly() }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }
}
