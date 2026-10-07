package dev.suyash.dot.feature.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.suyash.dot.core.data.TaskCommands
import dev.suyash.dot.core.data.TaskRepository
import dev.suyash.dot.core.data.di.ApplicationScope
import dev.suyash.dot.core.data.settings.SettingsRepository
import dev.suyash.dot.core.domain.events.TaskChange
import dev.suyash.dot.core.domain.events.TaskChangeObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Refreshes the tasks widget after changes, debounced so a burst of edits (e.g. a sync) causes one
 * redraw. Skips all work when no tasks widget is on the home screen.
 */
@Singleton
class WidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    settings: SettingsRepository,
) : TaskChangeObserver {

    private var pending: Job? = null

    init {
        // Hiding titles (or turning on App lock) must take effect on the home screen at once, not at
        // the next task edit.
        scope.launch {
            settings.settings.map { it.hidesWidgetTitles }.distinctUntilChanged().drop(1).collect { request() }
        }
    }

    override suspend fun onChanged(change: TaskChange) {
        request()
    }

    @Synchronized
    fun request() {
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MS)
            val manager = GlanceAppWidgetManager(context)
            if (manager.getGlanceIds(TasksWidget::class.java).isNotEmpty()) {
                TasksWidget().updateAll(context)
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 400L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun repository(): TaskRepository
    fun commands(): TaskCommands
    fun settings(): SettingsRepository
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetModule {
    @Binds
    @IntoSet
    abstract fun refresher(impl: WidgetRefresher): TaskChangeObserver
}
