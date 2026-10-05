package dev.suyash.dot.feature.reminders.di

import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.suyash.dot.core.data.TaskCommands
import dev.suyash.dot.core.data.TaskRepository
import dev.suyash.dot.core.data.settings.SettingsRepository
import dev.suyash.dot.core.domain.events.TaskChangeObserver
import dev.suyash.dot.feature.reminders.AttentionStateReader
import dev.suyash.dot.feature.reminders.alarm.AlarmReminderScheduler
import dev.suyash.dot.feature.reminders.notify.ReminderNotifier
import java.time.Clock

/**
 * Receivers resolve dependencies through this entry point *on demand* instead of field injection,
 * because they may run before the user unlocks after a reboot, when the encrypted database (and
 * anything depending on it) must not be touched.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun repository(): TaskRepository
    fun commands(): TaskCommands
    fun scheduler(): AlarmReminderScheduler
    fun notifier(): ReminderNotifier
    fun settings(): SettingsRepository
    fun attention(): AttentionStateReader
    fun clock(): Clock
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ReminderModule {
    /** Re-arms the alarm (and clears stale notifications) after every committed change. */
    @Binds
    @IntoSet
    abstract fun scheduler(impl: AlarmReminderScheduler): TaskChangeObserver
}
