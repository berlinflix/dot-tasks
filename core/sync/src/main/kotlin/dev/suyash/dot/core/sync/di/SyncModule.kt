package dev.suyash.dot.core.sync.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.suyash.dot.core.domain.events.TaskChangeObserver
import dev.suyash.dot.core.sync.SyncScheduler
import dev.suyash.dot.core.sync.remote.FirestoreRemoteStore
import dev.suyash.dot.core.sync.remote.RemoteStore

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds
    abstract fun remoteStore(impl: FirestoreRemoteStore): RemoteStore

    /** Local edits schedule a push. */
    @Binds
    @IntoSet
    abstract fun syncOnChange(impl: SyncScheduler): TaskChangeObserver
}
