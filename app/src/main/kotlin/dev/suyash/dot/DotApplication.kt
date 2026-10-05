package dev.suyash.dot

import android.app.Application
import android.os.StrictMode
import android.os.UserManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import dev.suyash.dot.core.data.di.ApplicationScope
import dev.suyash.dot.core.sync.AccountSession
import dev.suyash.dot.core.sync.LiveSync
import dev.suyash.dot.feature.reminders.alarm.AlarmReminderScheduler
import dev.suyash.dot.feature.reminders.notify.ReminderNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class DotApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var notifier: ReminderNotifier

    // Everything below is Lazy: building it opens the encrypted database, which must not happen before
    // the first unlock after a reboot (alarms can start this process while the phone is still locked).
    @Inject
    lateinit var scheduler: Lazy<AlarmReminderScheduler>

    @Inject
    lateinit var accountSession: Lazy<AccountSession>

    @Inject
    lateinit var liveSync: Lazy<LiveSync>

    @Inject
    lateinit var workerFactory: Lazy<HiltWorkerFactory>

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory.get()).build()

    override fun onCreate() {
        if (BuildConfig.DEBUG) enableStrictMode()
        super.onCreate()
        // Channels must exist before the first reminder fires. Safe before unlock (no database access).
        notifier.ensureChannels()
        if (!getSystemService(UserManager::class.java).isUserUnlocked) return

        AppCheckSetup.install()
        appScope.launch {
            // Alarms are wiped when the app is force-stopped; re-arm on every process start (idempotent).
            scheduler.get().reconcile()
            // Unlock the signed-in account's keys (from this phone's Keystore) and resume sync.
            accountSession.get().restore()
        }
        // Live sync only while the app is visible.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = liveSync.get().start()
            override fun onStop(owner: LifecycleOwner) = liveSync.get().stop()
        })
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectUnsafeIntentLaunch()
                .detectContentUriWithoutPermission()
                .penaltyLog()
                .build(),
        )
    }
}
