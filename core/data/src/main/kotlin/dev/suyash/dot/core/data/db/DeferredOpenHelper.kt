package dev.suyash.dot.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Opens the SQLCipher database only when Room first touches it, which is always on a background
 * thread. Reading the key ([key]: Android Keystore, StrongBox where available) and loading the native
 * library therefore never happen on the main thread, although the database object itself is often
 * created there (injected into a ViewModel).
 */
internal class DeferredOpenHelperFactory(private val key: () -> ByteArray) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        DeferredOpenHelper(configuration, key)
}

private class DeferredOpenHelper(
    private val configuration: SupportSQLiteOpenHelper.Configuration,
    private val key: () -> ByteArray,
) : SupportSQLiteOpenHelper {

    private var opened: SupportSQLiteOpenHelper? = null

    /** Room sets this while it is being built, long before the first query. */
    private var writeAheadLogging: Boolean? = null

    private val delegate: SupportSQLiteOpenHelper
        @Synchronized get() = opened ?: run {
            System.loadLibrary("sqlcipher")
            // SQLCipher zeroes the key bytes once it has used them.
            SupportOpenHelperFactory(key()).create(configuration).also { helper ->
                writeAheadLogging?.let(helper::setWriteAheadLoggingEnabled)
                opened = helper
            }
        }

    override val databaseName: String? get() = configuration.name

    @Synchronized
    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        val helper = opened
        if (helper != null) helper.setWriteAheadLoggingEnabled(enabled) else writeAheadLogging = enabled
    }

    override val writableDatabase: SupportSQLiteDatabase get() = delegate.writableDatabase

    override val readableDatabase: SupportSQLiteDatabase get() = delegate.readableDatabase

    @Synchronized
    override fun close() {
        opened?.close()
    }
}
