package dev.suyash.dot.core.data

import android.util.Log
import dev.suyash.dot.core.domain.events.TaskChange
import dev.suyash.dot.core.domain.events.TaskChangeObserver
import javax.inject.Inject
import javax.inject.Singleton

/** Fans committed changes out to every [TaskChangeObserver] (alarms, widgets, sync). */
@Singleton
class ChangeNotifier @Inject constructor(
    private val observers: Set<@JvmSuppressWildcards TaskChangeObserver>,
) {
    suspend fun notify(change: TaskChange) {
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
