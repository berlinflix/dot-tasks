package dev.suyash.dot.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.suyash.dot.core.domain.events.ChangeOrigin
import dev.suyash.dot.core.domain.events.TaskChange
import dev.suyash.dot.core.domain.events.TaskChangeObserver
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules sync through WorkManager so it survives process death and waits for a network. */
@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) : TaskChangeObserver {

    private val workManager get() = WorkManager.getInstance(context)

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Local edits → push a few seconds later (bursts of edits coalesce into one sync). */
    override suspend fun onChanged(change: TaskChange) {
        if (change.origin == ChangeOrigin.LOCAL) syncSoon()
    }

    fun syncSoon() {
        workManager.enqueueUniqueWork(
            WORK_SOON,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .setInitialDelay(3, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    /** User-visible sync (sign-in, "Sync now", app open): expedited. */
    fun syncNow() {
        workManager.enqueueUniqueWork(
            WORK_NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
        schedulePeriodic()
    }

    /** Hourly catch-up so other devices' changes arrive even if the app isn't opened. */
    fun schedulePeriodic() {
        workManager.enqueueUniquePeriodicWork(
            WORK_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(network)
                .build(),
        )
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(WORK_SOON)
        workManager.cancelUniqueWork(WORK_NOW)
        workManager.cancelUniqueWork(WORK_PERIODIC)
    }

    private companion object {
        const val WORK_SOON = "dot.sync.soon"
        const val WORK_NOW = "dot.sync.now"
        const val WORK_PERIODIC = "dot.sync.periodic"
    }
}

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (engine.sync()) {
        SyncOutcome.DONE, SyncOutcome.NOT_READY -> Result.success()
        SyncOutcome.RETRY -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    private companion object {
        const val MAX_ATTEMPTS = 6
    }
}
