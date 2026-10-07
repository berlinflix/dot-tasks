package dev.suyash.dot.core.data.di

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import dev.suyash.dot.core.crypto.DatabaseKeyProvider
import dev.suyash.dot.core.crypto.KeystoreWrapper
import dev.suyash.dot.core.data.db.DotDatabase
import dev.suyash.dot.core.domain.events.TaskChangeObserver
import dev.suyash.dot.core.domain.sync.HlcClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Qualifier
import javax.inject.Singleton

/** App-lifetime coroutine scope for work that must outlive a screen (e.g. finishing a write). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * A [Clock] that always reflects the *current* system zone. `Clock.systemDefaultZone()` captures the
 * zone once, which would break "8:00 stays 8:00" after the user changes time zone.
 */
object SystemZoneClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = system(zone)
    override fun instant(): Instant = Instant.now()
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun clock(): Clock = SystemZoneClock

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun hlcClock(@ApplicationContext context: Context): HlcClock = HlcClock(node = deviceId(context))

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): DotDatabase {
        KeystoreWrapper.strongBoxSupported =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        // This provider often runs on the main thread (ViewModel injection): the key is read from the
        // Keystore only when Room first opens the file, on a background thread.
        return DotDatabase.encrypted(context) {
            val keys = DatabaseKeyProvider(
                keyFile = File(context.noBackupFilesDir, "dot_db.key"),
                wrapper = KeystoreWrapper(alias = "dot.db.kek"),
            )
            when (val result = keys.obtainKey()) {
                is DatabaseKeyProvider.Result.Ready -> result.passphrase
                is DatabaseKeyProvider.Result.Lost -> {
                    Log.w("DataModule", "Database key was lost; recreating the local database", result.cause)
                    DotDatabase.deleteFiles(context)
                    result.passphrase
                }
            }
        }
    }

    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    /** Random per-install id used only as the HLC node (never leaves the device except inside ciphertext). */
    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("dot_device", Context.MODE_PRIVATE)
        prefs.getString("node_id", null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString("node_id", id).apply()
        return id
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ObserverBindings {
    /** Feature modules contribute observers with @IntoSet; the set may legitimately be empty. */
    @Multibinds
    abstract fun taskChangeObservers(): Set<TaskChangeObserver>
}
