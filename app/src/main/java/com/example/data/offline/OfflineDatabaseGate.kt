package com.example.data.offline

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Categorization of offline database failure states.
 */
enum class DatabaseUnavailableReason {
    UNSUPPORTED_MIGRATION,
    CORRUPTED_DATABASE,
    STORAGE_OR_IO_ERROR,
    UNKNOWN_ERROR
}

/**
 * Discrete lifecycle and availability states for the offline Room database.
 */
sealed class OfflineDatabaseStatus {
    object Uninitialized : OfflineDatabaseStatus()

    data class Available(
        val database: OfflineSyncDatabase
    ) : OfflineDatabaseStatus()

    data class Unavailable(
        val reason: DatabaseUnavailableReason,
        val userRecoveryMessage: String = USER_RECOVERY_MESSAGE,
        val cause: Throwable? = null
    ) : OfflineDatabaseStatus()

    companion object {
        const val USER_RECOVERY_MESSAGE =
            "Your local audit database could not be opened safely. Existing offline data has been preserved. Do not clear app storage or uninstall the application. Contact the system administrator/support team for recovery."
    }
}

/**
 * Additive centralized gate and availability mechanism for [OfflineSyncDatabase].
 * Guarantees that:
 * 1. The database is validated on [Dispatchers.IO] before any consumer accesses tables.
 * 2. Unsupported Room migrations fail closed, leaving the original SQLite file untouched.
 * 3. An unavailable database halts background sync and blocks new offline submissions without crashing.
 * 4. The user is presented with a non-technical, reassuring data preservation message.
 */
object OfflineDatabaseGate {
    private const val TAG = "OfflineDatabaseGate"

    @Volatile
    private var currentStatus: OfflineDatabaseStatus = OfflineDatabaseStatus.Uninitialized

    private val mutex = Mutex()

    /**
     * Current cached status of the database gate.
     */
    fun getStatus(): OfflineDatabaseStatus = currentStatus

    /**
     * Returns true only when the database is verified open and fully operational.
     */
    fun isAvailable(): Boolean = currentStatus is OfflineDatabaseStatus.Available

    /**
     * Helper to check availability, attempting synchronous validation if uninitialized.
     */
    fun isAvailable(context: Context): Boolean {
        val s = currentStatus
        if (s is OfflineDatabaseStatus.Available) return true
        if (s is OfflineDatabaseStatus.Unavailable) return false

        // If uninitialized, execute thread-safe initialization
        return synchronized(this) {
            when (val check = currentStatus) {
                is OfflineDatabaseStatus.Available -> true
                is OfflineDatabaseStatus.Unavailable -> false
                is OfflineDatabaseStatus.Uninitialized -> {
                    val result = validateAndOpen(context)
                    currentStatus = result
                    result is OfflineDatabaseStatus.Available
                }
            }
        }
    }

    /**
     * Returns the validated database instance if available, or null if uninitialized/unavailable.
     */
    fun getDatabaseIfAvailable(): OfflineSyncDatabase? {
        return (currentStatus as? OfflineDatabaseStatus.Available)?.database
    }

    /**
     * Initializes and verifies the offline Room database on [Dispatchers.IO].
     * Never throws an uncaught exception out to the caller.
     */
    suspend fun initialize(context: Context): OfflineDatabaseStatus = withContext(Dispatchers.IO) {
        mutex.withLock {
            when (val s = currentStatus) {
                is OfflineDatabaseStatus.Available -> s
                is OfflineDatabaseStatus.Unavailable -> s
                is OfflineDatabaseStatus.Uninitialized -> {
                    val status = validateAndOpen(context)
                    currentStatus = status
                    status
                }
            }
        }
    }

    /**
     * Internal verification routine that attempts to force database open and validates schema.
     */
    private fun validateAndOpen(context: Context): OfflineDatabaseStatus {
        return try {
            val db = OfflineSyncDatabase.getInstance(context)

            // Force actual opening of the SQLite database and validation of Room identity hash
            val sdb = db.openHelper.writableDatabase
            val cursor = sdb.query(SimpleSQLiteQuery("SELECT count(*) FROM sqlite_master"))
            cursor.moveToFirst()
            cursor.close()

            Log.i(TAG, "OfflineSyncDatabase validated successfully at version ${db.openHelper.readableDatabase.version}")
            OfflineDatabaseStatus.Available(db)
        } catch (e: IllegalStateException) {
            // Room throws IllegalStateException when a required Migration is missing or schema hash does not match
            Log.e(TAG, "Unsupported database schema or missing Room migration. Preserving original file.", e)
            closeDatabaseSafely(context)
            OfflineDatabaseStatus.Unavailable(
                reason = DatabaseUnavailableReason.UNSUPPORTED_MIGRATION,
                cause = e
            )
        } catch (e: SQLiteDatabaseCorruptException) {
            Log.e(TAG, "Database file is corrupt. Preserving file for administrative recovery.", e)
            closeDatabaseSafely(context)
            OfflineDatabaseStatus.Unavailable(
                reason = DatabaseUnavailableReason.CORRUPTED_DATABASE,
                cause = e
            )
        } catch (e: Exception) {
            Log.e(TAG, "Database initialization failure: ${e.message}", e)
            closeDatabaseSafely(context)
            val reason = when (e) {
                is SQLiteCantOpenDatabaseException,
                is SQLiteDiskIOException,
                is IOException -> DatabaseUnavailableReason.STORAGE_OR_IO_ERROR
                else -> DatabaseUnavailableReason.UNKNOWN_ERROR
            }
            OfflineDatabaseStatus.Unavailable(
                reason = reason,
                cause = e
            )
        }
    }

    private fun closeDatabaseSafely(context: Context) {
        try {
            val db = OfflineSyncDatabase.getInstance(context)
            if (db.isOpen) {
                db.close()
            }
        } catch (ignored: Throwable) {
            // Safeguard against cascading close exceptions
        }
    }

    /**
     * Allows test fixtures to reset gate state.
     */
    fun resetForTesting(status: OfflineDatabaseStatus = OfflineDatabaseStatus.Uninitialized) {
        currentStatus = status
    }
}
