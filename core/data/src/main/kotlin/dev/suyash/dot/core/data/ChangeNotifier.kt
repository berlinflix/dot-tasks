package dev.suyash.dot.core.data

import android.util.Log
import dev.suyash.dot.core.domain.events.TaskChange
import dev.suyash.dot.core.domain.events.TaskChangeObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Fans committed changes out to every [TaskChangeObserver] (alarms, widgets, sync). */
@Singleton
class ChangeNotifier @Inject constructor(
    private val observers: Set<@JvmSuppressWildcards TaskChangeObserver>,
) {
    /**
     * Observers make system calls (AlarmManager, widgets, WorkManager) and write a small file, so they
     * never run on the caller's thread, which is the main thread for edits made in the UI.
     */
    suspend fun notify(change: TaskChange) = withContext(Dispatchers.IO) {
        for (observer in observers) {
            try {
                observer.onChanged(change)
            } catch (e: Exception) {
                // An observer failing (e.g. widget host gone) must never fail the user's write.
                Log.w("ChangeNotifier", "Observer ${observer.javaClass.simpleName} failed", e)
            }
        }
    }
}
