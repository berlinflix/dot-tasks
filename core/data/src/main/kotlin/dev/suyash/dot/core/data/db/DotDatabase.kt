package dev.suyash.dot.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TaskListEntity::class, TaskEntity::class, OutboxEntity::class, SyncMetaEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class DotDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun taskListDao(): TaskListDao
    abstract fun outboxDao(): OutboxDao
    abstract fun syncMetaDao(): SyncMetaDao

    companion object {
        const val FILE_NAME = "dot.db"

        /**
         * The SQLCipher-encrypted database. [key] returns the passphrase (consumed and zeroed by SQLCipher)
         * and is only called when the first query opens the file, on a background thread.
         */
        fun encrypted(context: Context, key: () -> ByteArray): DotDatabase =
            Room.databaseBuilder(context, DotDatabase::class.java, FILE_NAME)
                .openHelperFactory(DeferredOpenHelperFactory(key))
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()

        /** Deletes the database and its WAL/SHM files (used when its key was lost). */
        fun deleteFiles(context: Context) {
            context.deleteDatabase(FILE_NAME)
        }
    }
}
