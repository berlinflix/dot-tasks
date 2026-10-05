package dev.suyash.dot.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

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

        /** Opens the SQLCipher-encrypted database. [passphrase] is consumed (zeroed) by SQLCipher. */
        fun encrypted(context: Context, passphrase: ByteArray): DotDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, DotDatabase::class.java, FILE_NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }

        /** Deletes the database and its WAL/SHM files (used when its key was lost). */
        fun deleteFiles(context: Context) {
            context.deleteDatabase(FILE_NAME)
        }
    }
}
