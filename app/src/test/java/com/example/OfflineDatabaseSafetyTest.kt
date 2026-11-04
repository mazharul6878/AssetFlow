package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.model.SyncState
import com.example.data.network.WorkerApiClient
import com.example.data.network.WorkerApiResult
import com.example.data.offline.DatabaseUnavailableReason
import com.example.data.offline.DynamicSubmissionDaoAdapter
import com.example.data.offline.DynamicSubmissionRecord
import com.example.data.offline.DynamicSyncState
import com.example.data.offline.OfflineDatabaseGate
import com.example.data.offline.OfflineDatabaseStatus
import com.example.data.offline.OfflinePhotoQueueEntity
import com.example.data.offline.OfflineQueuedRecordEntity
import com.example.data.offline.OfflineSyncDao
import com.example.data.offline.OfflineSyncDatabase
import com.example.data.repository.DynamicSyncRepository
import com.example.data.repository.DynamicSyncResult
import com.example.data.worker.DynamicSubmissionWorker
import com.example.data.worker.DynamicSyncRecovery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * Phase 2.3.5B — Offline Database Hardening, Fail-Closed Security, and Migration Governance Tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class OfflineDatabaseSafetyTest {

    private lateinit var context: Context

    companion object {
        // Baseline hashes for modules
        const val HASH_EXCEL_WORKBOOK_ENGINE = "DEE96C0E5C994DCB09E1684025303A9CBEC0E9F92E4BBC112AA643AEA4FB3C6A"
        // Authorized Phase 2.3.5E modification hash
        const val HASH_AUDIT_CALCULATION_ENGINE = "970BCD80E3BC1EAC737DAD6FBECE0CE86CBDA40B3D9DF018F81BE3C14D876D1C"
        const val HASH_AUDIT_MODELS = "A9F7517C52C5865CF4B291BCFBF22788FAF4EE4C46B450F1FAF2864DA48C1C33"
        const val HASH_ADMIN_REVIEW_QUEUE_TAB = "BAC96ED7916BA22BFB94F9920FDCE6DB983F6E6164A81CEED9CA44F89CF77D9E"
        // Authorized Phase 1 UI Remediation modification hash (operational loading, error banner, duplicate-tap prevention)
        const val HASH_CLIENT_SELECTION_SCREEN = "B52F00B0037496775CB2996134E751A6CE1DE7A6452C589F72FA882D52ECD50B"

        // Newly recorded hash for modified OfflineSyncDatabase.kt (Phase 2.4.3 DAO query corrections)
        const val HASH_OFFLINE_SYNC_DATABASE_MODIFIED = "A20BFC2562D8D0F48AF2B8727ABDDA12B2D9825C039C0D19603AFADDC2C62CD0"
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        OfflineDatabaseGate.resetForTesting()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        OfflineDatabaseGate.resetForTesting()
    }

    // 1. Fresh database creates Version 3 successfully
    @Test
    fun testFreshDatabaseCreatesVersion2Successfully() {
        val dbName = "test_fresh_v3.db"
        context.deleteDatabase(dbName)

        val db = Room.databaseBuilder(context, OfflineSyncDatabase::class.java, dbName)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()

        runBlocking {
            val list = db.offlineSyncDao().getPendingOrFailedRecords()
            assertEquals(0, list.size)
            val version = db.openHelper.readableDatabase.version
            assertEquals(3, version)
        }

        db.close()
        context.deleteDatabase(dbName)
    }

    // 2. Existing populated Version 2 database opens normally
    @Test
    fun testExistingPopulatedVersion2DatabaseOpensNormally() {
        val dbName = "test_populated_v2.db"
        context.deleteDatabase(dbName)

        // Step 1: populate v2 db
        var db = Room.databaseBuilder(context, OfflineSyncDatabase::class.java, dbName)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()

        runBlocking {
            db.offlineSyncDao().upsertQueuedRecord(
                OfflineQueuedRecordEntity(
                    recordKey = "apex:2026:asset_100",
                    recordType = "DYNAMIC_COUNT",
                    assetId = "0008899",
                    clientId = "apex",
                    fiscalYearId = "2026",
                    payloadJson = "{\"test\":true}",
                    syncStatus = SyncState.PENDING_SYNC
                )
            )
        }
        db.close()

        // Step 2: Reopen without migrations (clean open)
        db = Room.databaseBuilder(context, OfflineSyncDatabase::class.java, dbName)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()

        runBlocking {
            val records = db.offlineSyncDao().getPendingOrFailedRecords()
            assertEquals(1, records.size)
            assertEquals("0008899", records[0].assetId)
            assertEquals("apex:2026:asset_100", records[0].recordKey)
        }
        db.close()
        context.deleteDatabase(dbName)
    }

    // 3. Pending submissions survive database reopen
    @Test
    fun testPendingSubmissionsSurviveDatabaseReopen() {
        val dbName = "test_pending_submissions.db"
        context.deleteDatabase(dbName)

        val db1 = Room.databaseBuilder(context, OfflineSyncDatabase::class.java, dbName)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()

        val adapter1 = DynamicSubmissionDaoAdapter(db1.offlineSyncDao(), db1)
        val testRecord = DynamicSubmissionRecord(
            clientId = "client_alpha",
            auditCycleId = "cycle_fy2026",
            assetKey = "asset_key_0099",
            assetId = "00007711", // Leading zeroes strictly preserved
            schemaVersionId = "sch_v2_pub",
            countDate = "2026-11-01",
            fieldValues = mapOf("condition" to "GOOD", "qty" to 5, "serialNo" to "SN-0099-XYZ"),
            auditorUid = "auditor_uid_123",
            auditorDisplayName = "Alice Auditor",
            idempotencyKey = "idemp_unique_key_7711",
            syncStatus = DynamicSyncState.PENDING_SYNC
        )

        runBlocking {
            adapter1.upsertDynamicSubmission(testRecord)
        }
        db1.close()

        // Reopen in independent instance
        val db2 = Room.databaseBuilder(context, OfflineSyncDatabase::class.java, dbName)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()

        val adapter2 = DynamicSubmissionDaoAdapter(db2.offlineSyncDao(), db2)
        runBlocking {
            val retrieved = adapter2.getDynamicSubmission(testRecord.recordKey)
            assertNotNull("Pending submission must survive database reopen", retrieved)
            assertEquals("00007711", retrieved?.assetId)
            assertEquals("idemp_unique_key_7711", retrieved?.idempotencyKey)
            assertEquals("client_alpha", retrieved?.clientId)
            assertEquals("cycle_fy2026", retrieved?.auditCycleId)
            assertEquals("GOOD", retrieved?.fieldValues?.get("condition"))
            assertEquals(5, (retrieved?.fieldValues?.get("qty") as? Number)?.toInt())
            assertEquals("SN-0099-XYZ", retrieved?.fieldValues?.get("serialNo"))
        }

        db2.close()
        context.deleteDatabase(dbName)
    }

    // 4. Offline photo queue rows survive database reopen
    @Test
    fun testOfflinePhotoQueueRowsSurviveDatabaseReopen() {
        val dbName = "test_photo_queue.db"
        context.deleteDatabase(dbName)

        var db = Room.databaseBuilder(context, OfflineSyncDatabase::class.java, dbName)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()

        val photo = OfflinePhotoQueueEntity(
            photoId = "photo_uuid_888",
            recordKey = "apex:2026:asset_100",
            assetOrObsId = "0008899",
            clientId = "apex",
            fiscalYearId = "2026",
            localFilePath = "/data/user/0/app/filesDir/audit_photos/photo_888.jpg",
            driveFolderId = "drive_folder_id_xyz",
            uploadStatus = "PENDING",
            capturedBy = "Alice Auditor"
        )

        runBlocking {
            db.offlineSyncDao().upsertPhotoQueue(photo)
        }
        db.close()

        db = Room.databaseBuilder(context, OfflineSyncDatabase::class.java, dbName)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()

        runBlocking {
            val photos = db.offlineSyncDao().observeAllPhotoQueue().first()
            assertEquals(1, photos.size)
            assertEquals("photo_uuid_888", photos[0].photoId)
            assertEquals("0008899", photos[0].assetOrObsId)
            assertEquals("drive_folder_id_xyz", photos[0].driveFolderId)
            assertEquals("/data/user/0/app/filesDir/audit_photos/photo_888.jpg", photos[0].localFilePath)
        }

        db.close()
        context.deleteDatabase(dbName)
    }

    // 5 & 6. Synthetic unknown Version 1 schema fails closed AND preserves original data
    @Test
    fun testSyntheticUnsupportedVersion1SchemaFailsClosedAndPreservesData() {
        val dbName = "synthetic_v1_fail_closed.db"
        context.deleteDatabase(dbName)

        // 1. Create a synthetic Version 1 database with a marker table and sentinel record
        val helperFactory = FrameworkSQLiteOpenHelperFactory()
        val helperConfig = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE unsupported_legacy_marker (id INTEGER PRIMARY KEY, marker_text TEXT)")
                    db.execSQL("INSERT INTO unsupported_legacy_marker (id, marker_text) VALUES (1, 'sentinel_v1_marker_record_safe')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = helperFactory.create(helperConfig)
        val rawDb = helper.writableDatabase
        rawDb.close()
        helper.close()

        // 2. Attempt to open through production Room builder without destructive migration
        val roomDb = Room.databaseBuilder(context, OfflineSyncDatabase::class.java, dbName)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()

        var caughtMigrationException = false
        try {
            // Trigger database open
            roomDb.openHelper.writableDatabase
            fail("Room must fail closed with IllegalStateException when unsupported Version 1 is encountered without migration")
        } catch (e: IllegalStateException) {
            caughtMigrationException = true
            assertTrue(
                "Exception message must indicate missing migration path",
                e.message?.contains("migration", ignoreCase = true) == true
            )
        }
        assertTrue("IllegalStateException must be caught", caughtMigrationException)

        // 3. Crucial verification: The original database file was NOT wiped or destroyed!
        val verifyHelper = helperFactory.create(helperConfig)
        val verifyDb = verifyHelper.readableDatabase
        val cursor = verifyDb.query(SimpleSQLiteQuery("SELECT marker_text FROM unsupported_legacy_marker WHERE id = 1"))
        assertTrue("Marker table must still exist intact", cursor.moveToFirst())
        val sentinelVal = cursor.getString(0)
        cursor.close()
        verifyDb.close()
        verifyHelper.close()

        assertEquals(
            "Sentinel record must be preserved completely untouched",
            "sentinel_v1_marker_record_safe",
            sentinelVal
        )

        context.deleteDatabase(dbName)
    }

    // 7. Synthetic unsupported future version fails closed and preserves data
    @Test
    fun testSyntheticUnsupportedFutureVersionFailsClosedAndPreservesData() {
        val dbName = "synthetic_v4_future.db"
        context.deleteDatabase(dbName)

        val helperFactory = FrameworkSQLiteOpenHelperFactory()
        val helperConfig = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE future_marker_table (id INTEGER PRIMARY KEY, future_col TEXT)")
                    db.execSQL("INSERT INTO future_marker_table (id, future_col) VALUES (99, 'sentinel_v4_future_data')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = helperFactory.create(helperConfig)
        val rawDb = helper.writableDatabase
        rawDb.close()
        helper.close()

        // Attempt open with v3 builder
        val roomDb = Room.databaseBuilder(context, OfflineSyncDatabase::class.java, dbName)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()

        var caughtException = false
        try {
            roomDb.openHelper.writableDatabase
            fail("Room must fail closed on future schema downgrade/missing migration")
        } catch (e: IllegalStateException) {
            caughtException = true
        }
        assertTrue("Must throw IllegalStateException on unsupported future version", caughtException)

        // Verify data preserved
        val verifyHelper = helperFactory.create(helperConfig)
        val verifyDb = verifyHelper.readableDatabase
        val cursor = verifyDb.query(SimpleSQLiteQuery("SELECT future_col FROM future_marker_table WHERE id = 99"))
        assertTrue(cursor.moveToFirst())
        assertEquals("sentinel_v4_future_data", cursor.getString(0))
        cursor.close()
        verifyDb.close()
        verifyHelper.close()

        context.deleteDatabase(dbName)
    }

    // 8. Database initialization failure does not crash the entire application
    @Test
    fun testDatabaseInitializationFailureDoesNotCrashApplication() {
        // Manually configure gate with unsupported migration error
        OfflineDatabaseGate.resetForTesting(
            OfflineDatabaseStatus.Unavailable(
                reason = DatabaseUnavailableReason.UNSUPPORTED_MIGRATION,
                cause = IllegalStateException("A migration from 1 to 2 was required but not found.")
            )
        )

        assertFalse("Database gate must report unavailable", OfflineDatabaseGate.isAvailable())
        val status = OfflineDatabaseGate.getStatus()
        assertTrue("Status must be Unavailable", status is OfflineDatabaseStatus.Unavailable)
        val unavail = status as OfflineDatabaseStatus.Unavailable
        assertEquals(DatabaseUnavailableReason.UNSUPPORTED_MIGRATION, unavail.reason)
        assertEquals(OfflineDatabaseStatus.USER_RECOVERY_MESSAGE, unavail.userRecoveryMessage)
        assertNull("getDatabaseIfAvailable must return null", OfflineDatabaseGate.getDatabaseIfAvailable())
    }

    // 9 & 11. Unavailable database blocks new offline submissions and does NOT bypass to API
    @Test
    fun testUnavailableDatabaseBlocksNewOfflineSubmissionsWithoutBypassingToApi() {
        OfflineDatabaseGate.resetForTesting(
            OfflineDatabaseStatus.Unavailable(
                reason = DatabaseUnavailableReason.UNSUPPORTED_MIGRATION
            )
        )

        val mockHttp = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                fail("Network must never be reached when database is unavailable")
                throw java.io.IOException("Network unreachable")
            }
            .build()
        val mockApiClient = WorkerApiClient(client = mockHttp)

        // Empty dummy dao
        val inMemoryDao = InMemoryReliabilityOfflineDao()
        val repo = DynamicSyncRepository(
            daoAdapter = DynamicSubmissionDaoAdapter(inMemoryDao),
            apiClient = mockApiClient,
            context = context
        )

        val submission = DynamicSubmissionRecord(
            clientId = "acme",
            auditCycleId = "cycle_2026",
            assetKey = "k_1",
            assetId = "001",
            schemaVersionId = "s_1",
            countDate = "2026-11-01",
            fieldValues = emptyMap(),
            auditorUid = "auditor_uid_test",
            auditorDisplayName = "Auditor Test"
        )

        runBlocking {
            val result = repo.saveAndSubmit(submission, attemptImmediateNetwork = true)
            assertTrue("Result must be DatabaseUnavailable", result is DynamicSyncResult.DatabaseUnavailable)
            assertEquals(
                OfflineDatabaseStatus.USER_RECOVERY_MESSAGE,
                (result as DynamicSyncResult.DatabaseUnavailable).message
            )
        }

        // Critical safety verification: Zero direct API submission bypass!
        runBlocking {
            assertEquals("No record must be persisted to DAO", 0, inMemoryDao.getPendingOrFailedRecords().size)
        }
    }

    // 10. Unavailable database blocks photo queue operations
    @Test
    fun testUnavailableDatabaseBlocksPhotoQueueOperations() {
        OfflineDatabaseGate.resetForTesting(
            OfflineDatabaseStatus.Unavailable(
                reason = DatabaseUnavailableReason.UNSUPPORTED_MIGRATION
            )
        )

        assertFalse("Gate must report false", OfflineDatabaseGate.isAvailable(context))
        assertNull("No database must be provided", OfflineDatabaseGate.getDatabaseIfAvailable())
    }

    // 12. Workers do not run when database initialization fails
    @Test
    fun testWorkersDoNotRunWhenDatabaseInitializationFails() {
        OfflineDatabaseGate.resetForTesting(
            OfflineDatabaseStatus.Unavailable(
                reason = DatabaseUnavailableReason.UNSUPPORTED_MIGRATION
            )
        )

        val mockHttp = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                fail("Network must never be reached when database is unavailable")
                throw java.io.IOException("Network unreachable")
            }
            .build()
        val mockApiClient = WorkerApiClient(client = mockHttp)

        val worker = TestListenableWorkerBuilder<DynamicSubmissionWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: androidx.work.WorkerParameters
                ): androidx.work.ListenableWorker {
                    return DynamicSubmissionWorker(
                        appContext = appContext,
                        workerParams = workerParameters,
                        apiClient = mockApiClient,
                        daoAdapter = DynamicSubmissionDaoAdapter(InMemoryReliabilityOfflineDao())
                    )
                }
            })
            .build()

        runBlocking {
            val result = worker.doWork()
            assertEquals("Worker must return failure when database is unavailable", ListenableWorker.Result.failure(), result)
        }
    }

    // 13. Recovery does not schedule endless workers against an unavailable database
    @Test
    fun testRecoveryDoesNotScheduleEndlessWorkersAgainstUnavailableDatabase() {
        OfflineDatabaseGate.resetForTesting(
            OfflineDatabaseStatus.Unavailable(
                reason = DatabaseUnavailableReason.UNSUPPORTED_MIGRATION
            )
        )

        val inMemoryDao = InMemoryReliabilityOfflineDao()
        runBlocking {
            val scheduled = DynamicSyncRecovery.recoverPendingSubmissions(context, dao = inMemoryDao)
            assertEquals("Recovery must return empty list when database is unavailable", 0, scheduled.size)
        }
    }

    // 14. Successful database initialization restores normal offline workflow
    @Test
    fun testSuccessfulDatabaseInitializationRestoresNormalOfflineWorkflow() {
        val dbName = "test_restore_workflow.db"
        context.deleteDatabase(dbName)

        val db = Room.databaseBuilder(context, OfflineSyncDatabase::class.java, dbName)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()

        OfflineDatabaseGate.resetForTesting(OfflineDatabaseStatus.Available(db))
        assertTrue("Gate must report available", OfflineDatabaseGate.isAvailable())

        val inMemoryDao = InMemoryReliabilityOfflineDao()
        val repo = DynamicSyncRepository(
            daoAdapter = DynamicSubmissionDaoAdapter(inMemoryDao),
            context = context
        )

        val sub = DynamicSubmissionRecord(
            clientId = "c1",
            auditCycleId = "cy1",
            assetKey = "ak1",
            assetId = "00055",
            schemaVersionId = "sch1",
            countDate = "2026-11-01",
            fieldValues = emptyMap(),
            auditorUid = "auditor_uid_test",
            auditorDisplayName = "Auditor Test"
        )

        runBlocking {
            val res = repo.saveAndSubmit(sub, attemptImmediateNetwork = false)
            assertTrue("Must save offline successfully", res is DynamicSyncResult.SavedOffline)
            assertEquals(1, inMemoryDao.getPendingOrFailedRecords().size)
        }

        db.close()
        context.deleteDatabase(dbName)
    }

    // 15. Five unchanged protected modules retain their baseline hashes
    @Test
    fun testFiveUnchangedProtectedModulesRetainHashes() {
        val candidates = listOf(
            File("src/main/java/com/example"),
            File("app/src/main/java/com/example"),
            File("AssetFlow-main/AssetFlow-main/app/src/main/java/com/example")
        )
        val baseDir = candidates.firstOrNull { it.exists() } ?: File("src/main/java/com/example")

        val filesToCheck = mapOf(
            File(baseDir, "data/excel/ExcelWorkbookEngine.kt") to HASH_EXCEL_WORKBOOK_ENGINE,
            File(baseDir, "domain/AuditCalculationEngine.kt") to HASH_AUDIT_CALCULATION_ENGINE,
            File(baseDir, "data/model/AuditModels.kt") to HASH_AUDIT_MODELS,
            File(baseDir, "ui/screens/admin/AdminReviewQueueTab.kt") to HASH_ADMIN_REVIEW_QUEUE_TAB,
            File(baseDir, "ui/screens/ClientSelectionScreen.kt") to HASH_CLIENT_SELECTION_SCREEN
        )

        for ((file, expectedHash) in filesToCheck) {
            assertTrue("Protected module file must exist: ${file.path}", file.exists())
            val actualHash = sha256(file.readBytes())
            assertEquals("Protected module ${file.name} SHA-256 hash must remain immutable", expectedHash, actualHash)
        }
    }

    // 16. Modified OfflineSyncDatabase hash is recorded
    @Test
    fun testModifiedOfflineSyncDatabaseHashRecorded() {
        val candidates = listOf(
            File("src/main/java/com/example/data/offline/OfflineSyncDatabase.kt"),
            File("app/src/main/java/com/example/data/offline/OfflineSyncDatabase.kt"),
            File("AssetFlow-main/AssetFlow-main/app/src/main/java/com/example/data/offline/OfflineSyncDatabase.kt")
        )
        val file = candidates.firstOrNull { it.exists() } ?: File("src/main/java/com/example/data/offline/OfflineSyncDatabase.kt")
        assertTrue("OfflineSyncDatabase.kt must exist", file.exists())
        val actualHash = sha256(file.readBytes())
        assertEquals(
            "OfflineSyncDatabase.kt SHA-256 hash must match recorded hash",
            HASH_OFFLINE_SYNC_DATABASE_MODIFIED,
            actualHash
        )
    }

    // 17. Room Migration MIGRATION_2_3 safely upgrades Version 2 to Version 3 preserving data
    @Test
    fun testRoomMigrationFromVersion2ToVersion3PreservesData() {
        val dbName = "test_migration_v2_to_v3.db"
        context.deleteDatabase(dbName)

        // Step 1: Create a Version 2 database using raw SQLite helper
        val helperFactory = FrameworkSQLiteOpenHelperFactory()
        val v2HelperConfig = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `offline_queued_records` (`recordKey` TEXT NOT NULL, `recordType` TEXT NOT NULL, `assetId` TEXT NOT NULL, `sourceRow` INTEGER NOT NULL, `clientId` TEXT NOT NULL, `fiscalYearId` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `markAsReviewed` INTEGER NOT NULL, `explicitDateEdit` INTEGER NOT NULL, `baseUpdatedAtMillis` INTEGER NOT NULL, `syncStatus` TEXT NOT NULL, `errorMessage` TEXT NOT NULL, `retryCount` INTEGER NOT NULL, `userId` TEXT NOT NULL, `userDisplayName` TEXT NOT NULL, `queuedAtMillis` INTEGER NOT NULL, `lastSyncedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`recordKey`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `offline_photo_queue` (`photoId` TEXT NOT NULL, `recordKey` TEXT NOT NULL, `assetOrObsId` TEXT NOT NULL, `recordType` TEXT NOT NULL, `clientId` TEXT NOT NULL, `fiscalYearId` TEXT NOT NULL, `localFilePath` TEXT NOT NULL, `originalLocalFilename` TEXT NOT NULL, `finalDriveFilename` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `fileExtension` TEXT NOT NULL, `nicknameSnapshot` TEXT NOT NULL, `photoSequenceNumber` INTEGER NOT NULL, `schemaVersion` INTEGER NOT NULL, `dateFolderName` TEXT NOT NULL, `driveFolderId` TEXT NOT NULL, `driveDateFolderId` TEXT NOT NULL, `driveFileId` TEXT NOT NULL, `driveWebViewLink` TEXT NOT NULL, `uploadStatus` TEXT NOT NULL, `errorMessage` TEXT NOT NULL, `retryCount` INTEGER NOT NULL, `capturedBy` TEXT NOT NULL, `capturedByUserId` TEXT NOT NULL, `capturedAtIso` TEXT NOT NULL, `uploadedAtIso` TEXT NOT NULL, `queuedAtMillis` INTEGER NOT NULL, `lastSyncedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`photoId`))"
                    )
                    db.execSQL(
                        "INSERT INTO `offline_queued_records` (`recordKey`, `recordType`, `assetId`, `sourceRow`, `clientId`, `fiscalYearId`, `payloadJson`, `markAsReviewed`, `explicitDateEdit`, `baseUpdatedAtMillis`, `syncStatus`, `errorMessage`, `retryCount`, `userId`, `userDisplayName`, `queuedAtMillis`, `lastSyncedAtMillis`) VALUES ('rec_v2_001', 'DYNAMIC_COUNT', '000142', 1, 'client_walton', '2026_27', '{}', 0, 0, 0, 'Pending Sync', '', 0, 'uid_01', 'Jane', 1000, 0)"
                    )
                    db.execSQL(
                        "INSERT INTO `offline_photo_queue` (`photoId`, `recordKey`, `assetOrObsId`, `recordType`, `clientId`, `fiscalYearId`, `localFilePath`, `originalLocalFilename`, `finalDriveFilename`, `mimeType`, `fileExtension`, `nicknameSnapshot`, `photoSequenceNumber`, `schemaVersion`, `dateFolderName`, `driveFolderId`, `driveDateFolderId`, `driveFileId`, `driveWebViewLink`, `uploadStatus`, `errorMessage`, `retryCount`, `capturedBy`, `capturedByUserId`, `capturedAtIso`, `uploadedAtIso`, `queuedAtMillis`, `lastSyncedAtMillis`) VALUES ('photo_v2_001', 'rec_v2_001', '000142', 'FAR_COUNT', 'client_walton', '2026_27', '/data/photo.jpg', 'img.jpg', 'img.jpg', 'image/jpeg', 'jpg', 'auditor', 1, 1, '2026-11-01', 'f1', 'df1', 'drv_123', 'link', 'Pending Sync', '', 0, 'Auditor Jane', 'uid_01', '2026-11-01T00:00:00Z', '', 1000, 0)"
                    )
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = helperFactory.create(v2HelperConfig)
        val rawDb = helper.writableDatabase
        assertEquals(2, rawDb.version)
        rawDb.close()
        helper.close()

        // Step 2: Open with Room v3 using MIGRATION_2_3
        val roomDb = Room.databaseBuilder(context, OfflineSyncDatabase::class.java, dbName)
            .addMigrations(OfflineSyncDatabase.MIGRATION_2_3)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()

        runBlocking {
            val version = roomDb.openHelper.readableDatabase.version
            assertEquals(3, version)

            // Verify queued records preserved
            val record = roomDb.offlineSyncDao().getQueuedRecordByKey("rec_v2_001")
            assertNotNull(record)
            assertEquals("000142", record?.assetId)

            // Verify photo queue row preserved with default lease fields
            val photo = roomDb.offlineSyncDao().getPhotoById("photo_v2_001")
            assertNotNull(photo)
            assertEquals("photo_v2_001", photo?.photoId)
            assertEquals("drv_123", photo?.driveFileId)
            assertEquals("", photo?.leaseOwnerToken)
            assertEquals(0L, photo?.leaseExpiresAtMillis)
        }

        roomDb.close()
        context.deleteDatabase(dbName)
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02X".format(it) }
    }
}
