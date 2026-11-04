package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.offline.OfflinePhotoQueueEntity
import com.example.data.offline.OfflineSyncDatabase
import com.example.data.model.SyncState
import com.example.data.network.CountingSubmissionDto
import com.example.data.network.DynamicFieldDto
import com.example.data.network.DynamicSchemaDto
import com.example.data.network.FarAssetBaselineDto
import com.example.data.network.LocalMockTokenProvider
import com.example.data.network.WorkerApiClient
import com.example.data.network.WorkerApiResult
import com.example.data.network.WorkerTokenProvider
import com.example.data.offline.CachedFarBaselineEntity
import com.example.data.offline.CachedSchemaEntity
import com.example.data.offline.DynamicOfflineCacheDao
import com.example.data.offline.DynamicOfflineStore
import com.example.data.offline.DynamicSubmissionDaoAdapter
import com.example.data.offline.DynamicSubmissionRecord
import com.example.data.offline.DynamicSyncState
import com.example.data.offline.OfflineQueuedRecordEntity
import com.example.data.offline.OfflineSyncDao
import com.example.data.repository.DynamicOfflineCacheRepository
import com.example.data.repository.DynamicSyncRepository
import com.example.data.repository.DynamicSyncResult
import com.example.data.worker.DynamicSubmissionWorker
import com.example.data.worker.DynamicSyncRecovery
import com.example.data.worker.DynamicSyncScheduler
import com.example.data.worker.SyncCoordinator
import com.example.ui.components.DynamicFormState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DynamicReliabilityAndMigrationTest {

    private lateinit var context: Context
    private lateinit var mockDao: InMemoryReliabilityOfflineDao
    private lateinit var daoAdapter: DynamicSubmissionDaoAdapter

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        mockDao = InMemoryReliabilityOfflineDao()
        daoAdapter = DynamicSubmissionDaoAdapter(mockDao)
        SyncCoordinator.resetForTesting()

        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        SyncCoordinator.resetForTesting()
    }

    // =========================================================================
    // 1. WORKMANAGER KEEP RACE RESOLUTION & CONCURRENCY TESTS
    // =========================================================================

    @Test
    fun testSubmissionArrivingBetweenFinalEmptyCheckAndWorkerCompletionIsProcessed() {
        // Simulates the exact boundary race:
        // Worker W1 drains queue -> finds 0 pending items -> enters exit window.
        // During that exact boundary, a new submission is persisted in Room and scheduleSync is called.
        // Under APPEND_OR_REPLACE, WorkManager appends/coalesces a follow-up execution.
        val schema = createTestSchema()
        val workerClient = createSuccessWorkerClient("rec_boundary_999")

        runBlocking {
            // 1. Queue is initially empty
            assertEquals(0, daoAdapter.getPendingSubmissions("client_race", "cycle_2026").size)

            // 2. Schedule initial sync (simulating worker W1 starting)
            DynamicSyncScheduler.scheduleSync(context, "client_race", "cycle_2026")
            val workManager = WorkManager.getInstance(context)
            val workName = DynamicSyncScheduler.getUniqueWorkName("client_race", "cycle_2026")

            // 3. Persist new submission right at the completion boundary
            val submission = DynamicSubmissionRecord(
                clientId = "client_race",
                auditCycleId = "cycle_2026",
                assetKey = "race_asset_001",
                assetId = "0001923",
                schemaVersionId = schema.schemaVersionId,
                countDate = "2026-11-01",
                fieldValues = mapOf("fld_01" to "Verified Active"),
                auditorUid = "aud_boundary_test",
                syncStatus = DynamicSyncState.SAVED_LOCALLY
            )
            daoAdapter.upsertDynamicSubmission(submission)

            // Trigger scheduler as saveAndSubmit does
            DynamicSyncScheduler.scheduleSync(context, "client_race", "cycle_2026")

            // 4. Verify coordination state: new request generation was registered
            assertTrue(SyncCoordinator.getRequestGeneration("client_race", "cycle_2026") > 0L)

            // 5. Execute worker using TestListenableWorkerBuilder
            val inputData = androidx.work.Data.Builder()
                .putString("clientId", "client_race")
                .putString("cycleId", "cycle_2026")
                .build()

            val worker = TestListenableWorkerBuilder<DynamicSubmissionWorker>(context)
                .setInputData(inputData)
                .setWorkerFactory(object : androidx.work.WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: androidx.work.WorkerParameters
                    ): androidx.work.ListenableWorker {
                        return DynamicSubmissionWorker(
                            appContext = appContext,
                            workerParams = workerParameters,
                            apiClient = workerClient,
                            daoAdapter = daoAdapter
                        )
                    }
                })
                .build()

            val result = worker.doWork()
            assertEquals(androidx.work.ListenableWorker.Result.success(), result)

            // 6. Verify submission was not stranded and was successfully synchronized
            val persisted = daoAdapter.getDynamicSubmission(submission.recordKey)
            assertNotNull(persisted)
            assertEquals(DynamicSyncState.SYNCED, persisted?.syncStatus)
            assertEquals("rec_boundary_999", persisted?.serverRecordId)
        }
    }

    @Test
    fun testRepeatedSyncNowRequestsDoNotCreateConcurrentProcessingOfSameRecord() {
        val schema = createTestSchema()
        val submission = DynamicSubmissionRecord(
            clientId = "client_concurrent",
            auditCycleId = "cycle_2026",
            assetKey = "concur_01",
            assetId = "0007788",
            schemaVersionId = schema.schemaVersionId,
            countDate = "2026-11-01",
            fieldValues = mapOf("fld_01" to "Present"),
            auditorUid = "aud_1"
        )

        runBlocking {
            daoAdapter.upsertDynamicSubmission(submission)

            // Worker 1 claims the record
            val worker1Session = "session_worker_1"
            val claimedBy1 = daoAdapter.claimForSync(submission.recordKey, worker1Session, leaseDurationMillis = 60_000L)
            assertTrue("Worker 1 must successfully claim the record", claimedBy1)

            // Worker 2 attempts to claim the same record while Worker 1 lease is valid
            val worker2Session = "session_worker_2"
            val claimedBy2 = daoAdapter.claimForSync(submission.recordKey, worker2Session, leaseDurationMillis = 60_000L)
            assertFalse("Worker 2 must be blocked from claiming an actively leased record", claimedBy2)

            // Record remains safely owned by Worker 1
            val current = daoAdapter.getDynamicSubmission(submission.recordKey)
            assertEquals(worker1Session, current?.leaseOwnerId)
        }
    }

    // =========================================================================
    // 2. LEASE OWNERSHIP & STALE RECOVERY SAFETY TESTS
    // =========================================================================

    @Test
    fun testActiveLongRunningWorkerIsProtectedAgainstPrematureRecovery() {
        val record = DynamicSubmissionRecord(
            clientId = "client_lease",
            auditCycleId = "cycle_2026",
            assetKey = "lease_01",
            assetId = "0003344",
            schemaVersionId = "sch_v1",
            countDate = "2026-11-01",
            fieldValues = mapOf("fld_1" to "OK"),
            auditorUid = "aud_lease",
            syncStatus = DynamicSyncState.SYNCING,
            leaseOwnerId = "worker_active_session_99",
            leaseExpiresAtMillis = System.currentTimeMillis() + 120_000L, // 2 minutes remaining
            lastAttemptMillis = System.currentTimeMillis() - 70_000L // Started 70s ago
        )

        runBlocking {
            daoAdapter.upsertDynamicSubmission(record)

            // Attempt stale recovery from another worker session
            val recoveredCount = daoAdapter.recoverStaleSyncingRecords(
                clientId = "client_lease",
                cycleId = "cycle_2026",
                currentWorkerSessionId = "worker_new_session_100",
                staleThresholdMillis = 60_000L
            )

            assertEquals("Active long-running worker with valid lease must NOT be recovered", 0, recoveredCount)

            val fromDb = daoAdapter.getDynamicSubmission(record.recordKey)
            assertEquals(DynamicSyncState.SYNCING, fromDb?.syncStatus)
            assertEquals("worker_active_session_99", fromDb?.leaseOwnerId)
        }
    }

    @Test
    fun testExpiredWorkerLeaseIsSafelyRecoveredPreservingIdempotencyKey() {
        val originalIdempotencyKey = "idempotency_key_immutable_123"
        val record = DynamicSubmissionRecord(
            clientId = "client_lease",
            auditCycleId = "cycle_2026",
            assetKey = "lease_02",
            assetId = "0005566",
            schemaVersionId = "sch_v1",
            countDate = "2026-11-01",
            fieldValues = mapOf("fld_1" to "Operational"),
            auditorUid = "aud_lease",
            auditorDisplayName = "Auditor James",
            idempotencyKey = originalIdempotencyKey,
            syncStatus = DynamicSyncState.SYNCING,
            leaseOwnerId = "crashed_worker_session_old",
            leaseExpiresAtMillis = System.currentTimeMillis() - 5_000L // Expired 5 seconds ago
        )

        runBlocking {
            daoAdapter.upsertDynamicSubmission(record)

            val recoveredCount = daoAdapter.recoverStaleSyncingRecords(
                clientId = "client_lease",
                cycleId = "cycle_2026",
                currentWorkerSessionId = "worker_recovery_session_200",
                staleThresholdMillis = 60_000L
            )

            assertEquals(1, recoveredCount)

            val recovered = daoAdapter.getDynamicSubmission(record.recordKey)
            assertNotNull(recovered)
            assertEquals(DynamicSyncState.SYNC_FAILED_RETRY_AVAILABLE, recovered?.syncStatus)
            assertEquals("Original idempotency key must be strictly preserved", originalIdempotencyKey, recovered?.idempotencyKey)
            assertEquals("0005566", recovered?.assetId)
            assertEquals("Auditor James", recovered?.auditorDisplayName)
            assertNull("Lease owner must be cleared after recovery", recovered?.leaseOwnerId)
            assertNull("Lease expiry must be cleared after recovery", recovered?.leaseExpiresAtMillis)
        }
    }

    @Test
    fun testAlreadySyncedRecordsAreNeverResetByStaleRecovery() {
        val syncedRecord = DynamicSubmissionRecord(
            clientId = "client_synced",
            auditCycleId = "cycle_2026",
            assetKey = "synced_01",
            assetId = "0009900",
            schemaVersionId = "sch_v1",
            countDate = "2026-11-01",
            fieldValues = mapOf("fld_1" to "Passed"),
            auditorUid = "aud_1",
            syncStatus = DynamicSyncState.SYNCED,
            serverRecordId = "rec_srv_111",
            leaseOwnerId = "old_session",
            leaseExpiresAtMillis = System.currentTimeMillis() - 100_000L
        )

        runBlocking {
            daoAdapter.upsertDynamicSubmission(syncedRecord)

            daoAdapter.recoverStaleSyncingRecords("client_synced", "cycle_2026", "recovery_worker")

            val fromDb = daoAdapter.getDynamicSubmission(syncedRecord.recordKey)
            assertEquals(DynamicSyncState.SYNCED, fromDb?.syncStatus)
            assertEquals("rec_srv_111", fromDb?.serverRecordId)
        }
    }

    // =========================================================================
    // 3. ROOM DATABASE MIGRATION & DESTRUCTIVE AUDIT TESTS
    // =========================================================================

    @Test
    fun testVersion1PopulatedDatabaseMigratesToVersion2PreservingRawJsonAndLeadingZeroes() {
        val dbName = "migration_test_v1.db"
        context.deleteDatabase(dbName)
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        val helperFactory = FrameworkSQLiteOpenHelperFactory()
        val helperConfig = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Create Version 1 schema (without rawRowJson)
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS cached_schemas (" +
                        "cacheKey TEXT PRIMARY KEY NOT NULL, " +
                        "clientId TEXT NOT NULL, " +
                        "auditCycleId TEXT NOT NULL, " +
                        "schemaVersionId TEXT NOT NULL, " +
                        "versionNumber INTEGER NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "sourceWorkbookName TEXT NOT NULL, " +
                        "uniqueAssetKeyHeader TEXT NOT NULL, " +
                        "schemaJson TEXT NOT NULL, " +
                        "cachedAtMillis INTEGER NOT NULL)"
                    )

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS cached_far_baselines (" +
                        "cacheKey TEXT PRIMARY KEY NOT NULL, " +
                        "clientId TEXT NOT NULL, " +
                        "auditCycleId TEXT NOT NULL, " +
                        "assetKey TEXT NOT NULL, " +
                        "assetId TEXT NOT NULL, " +
                        "assetDescription TEXT NOT NULL, " +
                        "location TEXT NOT NULL, " +
                        "department TEXT NOT NULL, " +
                        "baselineDataJson TEXT NOT NULL, " +
                        "cachedAtMillis INTEGER NOT NULL)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = helperFactory.create(helperConfig)
        val v1Db = openHelper.writableDatabase

        // Insert Version 1 records with arbitrary column JSON and leading zeros
        val testSchemaJson = "{\"schemaVersionId\":\"sch_v1\",\"fields\":[{\"fieldId\":\"fld_1\",\"fieldKey\":\"status\",\"displayLabel\":\"Status\",\"inputMode\":\"SELECT\",\"isRequired\":true,\"sortOrder\":1}]}"
        val testArbitraryBaselineJson = "{\"Asset ID\":\"0000456\",\"Purchase Order\":\"PO-991122\",\"Arbitrary Spec\":\"440V\"}"

        v1Db.execSQL(
            "INSERT INTO cached_schemas VALUES (" +
            "'client_acme:cycle_2026', 'client_acme', 'cycle_2026', 'sch_v1', 1, 'PUBLISHED', 'Acme_2026.xlsx', 'Asset ID', '$testSchemaJson', 1700000000)"
        )

        v1Db.execSQL(
            "INSERT INTO cached_far_baselines VALUES (" +
            "'client_acme:cycle_2026:acme_001', 'client_acme', 'cycle_2026', 'acme_001', '0000456', 'Industrial Milling Machine', 'Building C', 'Machining', '$testArbitraryBaselineJson', 1700000000)"
        )

        // Close raw Version 1 SQLite database
        v1Db.close()
        openHelper.close()

        // Open the Version 1 database through the actual current Room database builder
        // Room executes the registered MIGRATION_1_2 and validates schema integrity
        val roomDb = Room.databaseBuilder(
            context,
            DynamicOfflineStore::class.java,
            dbName
        )
            .addMigrations(DynamicOfflineStore.MIGRATION_1_2)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()

        runBlocking {
            // Confirm Room schema validation succeeds and DAO reads back the migrated record
            val baseline = roomDb.cacheDao().getCachedFarBaseline("client_acme", "cycle_2026", "acme_001")
            assertNotNull("Room must successfully load migrated FAR baseline", baseline)
            assertEquals("0000456", baseline?.assetId) // Leading zeroes strictly preserved
            assertEquals("Industrial Milling Machine", baseline?.assetDescription)
            assertEquals("Building C", baseline?.location)
            assertEquals("Machining", baseline?.department)
            assertEquals("{}", baseline?.rawRowJson) // Populated with safe default
            assertEquals(testArbitraryBaselineJson, baseline?.baselineDataJson)

            val schema = roomDb.cacheDao().getCachedSchema("client_acme", "cycle_2026")
            assertNotNull("Room must successfully load migrated schema", schema)
            assertEquals("sch_v1", schema?.schemaVersionId)
            assertEquals(1, schema?.versionNumber)
        }

        roomDb.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun testDatabaseLevelAtomicLeaseClaimingAcrossIndependentAdapterInstances() {
        val realDb = Room.inMemoryDatabaseBuilder(
            context,
            OfflineSyncDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()

        val adapter1 = DynamicSubmissionDaoAdapter(realDb.offlineSyncDao(), realDb)
        val adapter2 = DynamicSubmissionDaoAdapter(realDb.offlineSyncDao(), realDb)

        val submission = DynamicSubmissionRecord(
            clientId = "client_atomic",
            auditCycleId = "cycle_2026",
            assetKey = "atomic_asset_1",
            assetId = "0009988",
            schemaVersionId = "sch_v1",
            countDate = "2026-11-01",
            fieldValues = mapOf("fld" to "Val"),
            auditorUid = "aud_atomic",
            syncStatus = DynamicSyncState.SAVED_LOCALLY
        )

        runBlocking {
            // Persist via adapter 1
            adapter1.upsertDynamicSubmission(submission)

            // Competing claims from two independent adapter instances concurrently
            val claim1Deferred = async(Dispatchers.IO) {
                adapter1.claimForSync(submission.recordKey, "worker_session_A", leaseDurationMillis = 60_000L)
            }
            val claim2Deferred = async(Dispatchers.IO) {
                adapter2.claimForSync(submission.recordKey, "worker_session_B", leaseDurationMillis = 60_000L)
            }

            val claim1 = claim1Deferred.await()
            val claim2 = claim2Deferred.await()

            // Exactly ONE adapter must succeed
            assertTrue("Exactly one adapter must succeed in claiming the lease", claim1 xor claim2)

            val winningSession = if (claim1) "worker_session_A" else "worker_session_B"
            val losingSession = if (claim1) "worker_session_B" else "worker_session_A"

            val stored = adapter1.getDynamicSubmission(submission.recordKey)
            assertEquals(winningSession, stored?.leaseOwnerId)
            assertEquals(DynamicSyncState.SYNCING, stored?.syncStatus)
            assertEquals(submission.idempotencyKey, stored?.idempotencyKey)

            // Stale worker (losingSession) attempting to refresh lease must be rejected
            val staleRefresh = adapter2.refreshLease(submission.recordKey, losingSession)
            assertFalse("Stale worker cannot refresh lease", staleRefresh)

            // Stale worker attempting to release lease must be rejected
            val staleRelease = adapter2.releaseLease(submission.recordKey, losingSession)
            assertFalse("Stale worker cannot release lease", staleRelease)

            // Stale worker attempting to update sync status must be rejected
            val staleStatusUpdate = adapter2.updateSyncStatus(
                recordKey = submission.recordKey,
                status = DynamicSyncState.SYNCED,
                serverRecordId = "hacked_id",
                workerSessionId = losingSession
            )
            assertFalse("Stale worker cannot update sync status", staleStatusUpdate)

            // Winning worker updating sync status succeeds
            val validStatusUpdate = adapter1.updateSyncStatus(
                recordKey = submission.recordKey,
                status = DynamicSyncState.SYNCED,
                serverRecordId = "valid_server_id",
                workerSessionId = winningSession
            )
            assertTrue("Valid lease owner successfully updates status", validStatusUpdate)

            val synced = adapter2.getDynamicSubmission(submission.recordKey)
            assertEquals(DynamicSyncState.SYNCED, synced?.syncStatus)
            assertEquals("valid_server_id", synced?.serverRecordId)
            assertEquals(submission.idempotencyKey, synced?.idempotencyKey)

            // Already SYNCED records cannot be reclaimed by any adapter
            val reclaimAttempt = adapter1.claimForSync(submission.recordKey, "worker_session_C")
            assertFalse("Already SYNCED records must never be reclaimed", reclaimAttempt)
        }

        realDb.close()
    }

    @Test
    fun testProcessDeathRecoverySchedulesPendingSubmissionsWithoutStranding() {
        val realDb = Room.inMemoryDatabaseBuilder(
            context,
            OfflineSyncDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()

        val adapter = DynamicSubmissionDaoAdapter(realDb.offlineSyncDao(), realDb)

        val submission1 = DynamicSubmissionRecord(
            clientId = "client_recovery",
            auditCycleId = "cycle_2026",
            assetKey = "recov_asset_1",
            assetId = "0005544",
            schemaVersionId = "sch_v1",
            countDate = "2026-11-01",
            fieldValues = mapOf("fld" to "V1"),
            auditorUid = "aud_1",
            syncStatus = DynamicSyncState.SAVED_LOCALLY
        )
        val submission2 = DynamicSubmissionRecord(
            clientId = "client_recovery",
            auditCycleId = "cycle_2026",
            assetKey = "recov_asset_2",
            assetId = "0005545",
            schemaVersionId = "sch_v1",
            countDate = "2026-11-01",
            fieldValues = mapOf("fld" to "V2"),
            auditorUid = "aud_1",
            syncStatus = DynamicSyncState.WAITING_FOR_INTERNET
        )

        runBlocking {
            // Simulate process death after Room persistence but BEFORE scheduleSync
            adapter.upsertDynamicSubmission(submission1)
            adapter.upsertDynamicSubmission(submission2)

            val uniqueName = DynamicSyncScheduler.getUniqueWorkName("client_recovery", "cycle_2026")
            val workManager = WorkManager.getInstance(context)

            // WorkManager has NO active work for this client/cycle yet
            val beforeRecovery = workManager.getWorkInfosForUniqueWork(uniqueName).get()
            assertTrue(beforeRecovery.isEmpty())

            // Trigger startup durable recovery
            val recoveredScopes = DynamicSyncRecovery.recoverPendingSubmissions(
                context = context,
                dao = realDb.offlineSyncDao(),
                workManager = workManager
            )

            assertEquals(1, recoveredScopes.size)
            assertEquals("client_recovery" to "cycle_2026", recoveredScopes[0])

            // WorkManager now has unique work scheduled!
            val afterRecovery = workManager.getWorkInfosForUniqueWork(uniqueName).get()
            assertEquals(1, afterRecovery.size)
            assertTrue(
                afterRecovery[0].state == WorkInfo.State.ENQUEUED || afterRecovery[0].state == WorkInfo.State.RUNNING
            )

            // Verify both submissions in Room have their original IDs and idempotency keys intact
            val r1 = adapter.getDynamicSubmission(submission1.recordKey)
            val r2 = adapter.getDynamicSubmission(submission2.recordKey)
            assertNotNull(r1)
            assertNotNull(r2)
            assertEquals("0005544", r1?.assetId)
            assertEquals(submission1.idempotencyKey, r1?.idempotencyKey)
            assertEquals(submission1.localSubmissionId, r1?.localSubmissionId)
            assertEquals(submission2.idempotencyKey, r2?.idempotencyKey)
        }

        realDb.close()
    }

    // =========================================================================
    // 4. FAR CACHE-MISS & ARBITRARY COLUMN SAFETY TESTS
    // =========================================================================

    @Test
    fun testIncompleteLegacyFallbackCannotMasqueradeAsCompleteDynamicBaseline() {
        // Schema requires an arbitrary imported column: "Purchase Order No"
        val schemaWithArbitraryImported = DynamicSchemaDto(
            schemaVersionId = "sch_complex",
            clientId = "client_test",
            fiscalYearId = "cycle_2026",
            versionNumber = 1,
            status = "PUBLISHED",
            sourceWorkbookName = "MasterSheet.xlsx",
            uniqueAssetKeyHeader = "Asset ID",
            fields = listOf(
                DynamicFieldDto(
                    fieldId = "fld_po",
                    fieldKey = "po_number",
                    displayLabel = "Purchase Order No",
                    originalHeader = "PO Number",
                    dataType = "TEXT",
                    inputMode = "FIXED_IMPORTED",
                    isRequired = true,
                    isVisible = true,
                    sortOrder = 1
                )
            )
        )

        // Legacy fallback baseline only has the 5 default fields; missing "PO Number"
        val partialBaseline = mapOf(
            "Asset ID" to "0001889",
            "Asset Description" to "Office Chair",
            "Location" to "HQ Floor 2",
            "Department" to "HR",
            "Asset Class" to "Furniture"
        )

        // Verify incomplete baseline detection
        val missingColumns = mutableListOf<String>()
        for (f in schemaWithArbitraryImported.fields) {
            val isImported = f.inputMode in listOf("FIXED_IMPORTED", "READ_ONLY", "FIXED_MASTER_DATA", "FIXED")
            if (isImported) {
                val col = f.originalHeader ?: f.fieldKey
                val hasCol = partialBaseline.containsKey(col) || partialBaseline.containsKey(f.displayLabel)
                if (!hasCol && f.isRequired) {
                    missingColumns.add(f.displayLabel.ifBlank { col })
                }
            }
        }

        assertEquals(1, missingColumns.size)
        assertEquals("Purchase Order No", missingColumns[0])

        val formState = DynamicFormState.IncompleteBaseline(
            message = "Missing required master columns: ${missingColumns.joinToString(", ")}",
            missingColumns = missingColumns
        )

        assertTrue("Form state must be IncompleteBaseline when arbitrary columns are missing", formState is DynamicFormState.IncompleteBaseline)
    }

    @Test
    fun testCompleteOfflineCacheWorksWithoutNetwork() {
        val schema = createTestSchema()
        val completeFarBaseline = FarAssetBaselineDto(
            assetKey = "asset_complete_01",
            assetId = "0008899",
            clientId = "client_test",
            fiscalYearId = "cycle_2026",
            sourceRow = 5L,
            rawRowData = mapOf(
                "Asset ID" to "0008899",
                "Asset Description" to "Generator Unit",
                "Physical Condition" to "Operational",
                "Vendor PO" to "PO-998811"
            )
        )

        val mockCacheDao = InMemoryReliabilityCacheDao()
        val cacheRepo = DynamicOfflineCacheRepository(
            apiClient = createFailingWorkerClient(0, "No network connection"),
            cacheDao = mockCacheDao
        )

        runBlocking {
            cacheRepo.cacheFarBaselines("client_test", "cycle_2026", listOf(completeFarBaseline))

            val res = cacheRepo.getFarAsset("client_test", "cycle_2026", "asset_complete_01")
            assertTrue("Complete cached baseline must load successfully offline", res is WorkerApiResult.Success)
            val loaded = (res as WorkerApiResult.Success).data
            assertNotNull(loaded)
            assertEquals("0008899", loaded?.assetId)
            assertEquals("PO-998811", loaded?.rawRowData?.get("Vendor PO"))
        }
    }

    // =========================================================================
    // 5. RETRY EXHAUSTION & MANUAL RETRY TESTS
    // =========================================================================

    @Test
    fun testExhaustedSubmissionPreservedAndManualRetryStartsFreshSequence() {
        val originalKey = UUID.randomUUID().toString()
        val exhausted = DynamicSubmissionRecord(
            clientId = "client_exhaust",
            auditCycleId = "cycle_2026",
            assetKey = "exh_01",
            assetId = "0004455",
            schemaVersionId = "sch_v1",
            countDate = "2026-11-01",
            fieldValues = mapOf("fld_1" to "Done"),
            auditorUid = "aud_exhaust_test",
            auditorDisplayName = "Auditor Mark",
            idempotencyKey = originalKey,
            retryCount = DynamicSubmissionWorker.MAX_RETRY_ATTEMPTS, // 5
            syncStatus = DynamicSyncState.SYNC_FAILED_RETRY_AVAILABLE,
            lastErrorMessage = "Maximum retry attempts reached"
        )

        runBlocking {
            daoAdapter.upsertDynamicSubmission(exhausted)

            // 1. Verify record is never deleted on exhaustion
            val stored = daoAdapter.getDynamicSubmission(exhausted.recordKey)
            assertNotNull("Exhausted submission must remain safely in Room", stored)
            assertEquals(5, stored?.retryCount)

            // 2. Perform manual retry
            val repository = DynamicSyncRepository(daoAdapter)
            repository.retrySubmission(exhausted.recordKey, context = null)

            // 3. Verify manual retry reset retryCount to 0, preserved idempotency key and auditor UID
            val resetRecord = daoAdapter.getDynamicSubmission(exhausted.recordKey)
            assertNotNull(resetRecord)
            assertEquals("Retry count must be reset to 0 on manual retry", 0, resetRecord?.retryCount)
            assertEquals(DynamicSyncState.PENDING_SYNC, resetRecord?.syncStatus)
            assertEquals("Idempotency key must remain strictly unchanged", originalKey, resetRecord?.idempotencyKey)
            assertEquals("aud_exhaust_test", resetRecord?.auditorUid)
            assertEquals("Auditor Mark", resetRecord?.auditorDisplayName)
            assertEquals("0004455", resetRecord?.assetId)
        }
    }

    // =========================================================================
    // 6. SECURITY & REGRESSION TESTS
    // =========================================================================

    @Test
    fun testReleaseConfigurationRejectsMockAuthentication() {
        // LocalMockTokenProvider enforces check against BuildConfig.DEBUG
        // When com.example.BuildConfig.DEBUG is false, it throws SecurityException
        if (!com.example.BuildConfig.DEBUG) {
            try {
                LocalMockTokenProvider()
                fail("LocalMockTokenProvider must throw SecurityException in release builds")
            } catch (e: SecurityException) {
                assertTrue(e.message?.contains("strictly prohibited in release builds") == true)
            }
        }
    }

    @Test
    fun testReleaseConfigurationRejectsCleartextHttpAndLocalhost() {
        if (!com.example.BuildConfig.DEBUG) {
            try {
                WorkerApiClient(baseUrl = "http://10.0.2.2:8787")
                fail("WorkerApiClient must reject local development URL in release builds")
            } catch (e: Exception) {
                assertTrue(e is IllegalStateException || e is SecurityException)
            }

            try {
                WorkerApiClient(baseUrl = "http://api.production.internal")
                fail("WorkerApiClient must reject cleartext HTTP in release builds")
            } catch (e: SecurityException) {
                assertTrue(e.message?.contains("Cleartext HTTP traffic is rejected") == true)
            }
        }
    }

    // =========================================================================
    // HELPERS & TEST FIXTURES
    // =========================================================================

    private fun createTestSchema(): DynamicSchemaDto {
        return DynamicSchemaDto(
            schemaVersionId = "sch_test_v1",
            clientId = "client_test",
            fiscalYearId = "cycle_2026",
            versionNumber = 1,
            status = "PUBLISHED",
            sourceWorkbookName = "InputSheet.xlsx",
            uniqueAssetKeyHeader = "Asset ID",
            fields = listOf(
                DynamicFieldDto(
                    fieldId = "fld_01",
                    fieldKey = "asset_condition",
                    displayLabel = "Asset Condition",
                    originalHeader = "Condition",
                    dataType = "TEXT",
                    inputMode = "INPUT_TEXT",
                    isRequired = true,
                    isVisible = true,
                    sortOrder = 1
                )
            )
        )
    }

    private fun createSuccessWorkerClient(recordId: String): WorkerApiClient {
        val json = JSONObject().apply {
            put("recordId", recordId)
            put("status", "SUBMITTED")
            put("isIdempotentReplay", false)
        }
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body(json.toString().toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

        return WorkerApiClient(client = mockHttp)
    }

    private fun createFailingWorkerClient(code: Int, errorMsg: String): WorkerApiClient {
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(errorMsg)
                    .body(JSONObject().put("error", errorMsg).toString().toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

        return WorkerApiClient(client = mockHttp)
    }
}

class InMemoryReliabilityOfflineDao : OfflineSyncDao {
    private val records = mutableMapOf<String, OfflineQueuedRecordEntity>()
    private val flow = MutableStateFlow<List<OfflineQueuedRecordEntity>>(emptyList())

    override fun observeAllQueuedRecords(): Flow<List<OfflineQueuedRecordEntity>> = flow

    override fun observeQueuedRecordsForUser(uid: String): Flow<List<OfflineQueuedRecordEntity>> = flow

    override suspend fun getPendingOrFailedRecords(): List<OfflineQueuedRecordEntity> {
        return records.values.filter { it.syncStatus in listOf("Pending Sync", "Failed", "Conflict Detected") }
    }

    override suspend fun getPendingOrFailedRecordsForUser(uid: String): List<OfflineQueuedRecordEntity> {
        return records.values.filter { it.userId == uid && it.syncStatus in listOf("Pending Sync", "Failed") }
    }

    override suspend fun getQueuedRecordByKey(key: String): OfflineQueuedRecordEntity? = records[key]

    override suspend fun upsertQueuedRecord(entity: OfflineQueuedRecordEntity) {
        records[entity.recordKey] = entity
        flow.value = records.values.toList()
    }

    override suspend fun updateRecordSyncStatus(
        recordKey: String,
        status: String,
        error: String,
        retries: Int,
        syncedAt: Long
    ) {
        val existing = records[recordKey] ?: return
        records[recordKey] = existing.copy(
            syncStatus = status,
            errorMessage = error,
            retryCount = retries,
            lastSyncedAtMillis = syncedAt
        )
        flow.value = records.values.toList()
    }

    override suspend fun deleteQueuedRecord(recordKey: String) {
        records.remove(recordKey)
        flow.value = records.values.toList()
    }

    override suspend fun deleteQueuedRecordsForClient(clientId: String) {
        records.values.removeAll { it.clientId == clientId }
        flow.value = records.values.toList()
    }

    override suspend fun deleteQueuedRecordsForUser(uid: String) {
        records.values.removeAll { it.userId == uid }
        flow.value = records.values.toList()
    }

    override fun observeAllPhotoQueue(): Flow<List<OfflinePhotoQueueEntity>> = flowOf(emptyList())
    override fun observePhotoQueueForUser(uid: String): Flow<List<OfflinePhotoQueueEntity>> = flowOf(emptyList())
    override suspend fun getPhotosForRecord(recordKey: String): List<OfflinePhotoQueueEntity> = emptyList()
    override suspend fun getPhotoById(photoId: String): OfflinePhotoQueueEntity? = null
    override suspend fun getPendingOrFailedPhotos(): List<OfflinePhotoQueueEntity> = emptyList()
    override suspend fun getPendingOrFailedPhotosForUser(uid: String): List<OfflinePhotoQueueEntity> = emptyList()
    override suspend fun getPhotosForClient(clientId: String): List<OfflinePhotoQueueEntity> = emptyList()
    override suspend fun getPhotosForUser(uid: String): List<OfflinePhotoQueueEntity> = emptyList()
    override suspend fun upsertPhotoQueue(entity: OfflinePhotoQueueEntity) {}
    override suspend fun updatePhotoUploadStatus(
        photoId: String, status: String, driveFileId: String, webLink: String, error: String,
        retries: Int, syncedAt: Long, dateFolderId: String, dateFolderName: String, finalName: String,
        ext: String, mime: String, seq: Int, uploadedIso: String
    ) {}
    override suspend fun claimPhotoLease(photoId: String, ownerToken: String, newStatus: String, expiresAtMillis: Long, nowMillis: Long): Int = 1
    override suspend fun renewPhotoLease(photoId: String, ownerToken: String, newExpiresAtMillis: Long): Int = 1
    override suspend fun updatePhotoIntermediateStatusWithLease(
        photoId: String, ownerToken: String, status: String, driveFileId: String, webLink: String, error: String,
        retries: Int, syncedAt: Long, dateFolderId: String, dateFolderName: String, finalName: String,
        ext: String, mime: String, seq: Int, uploadedIso: String, newExpiresAtMillis: Long
    ): Int = 1
    override suspend fun updatePhotoStatusWithLease(
        photoId: String, ownerToken: String, status: String, driveFileId: String, webLink: String, error: String,
        retries: Int, syncedAt: Long, dateFolderId: String, dateFolderName: String, finalName: String,
        ext: String, mime: String, seq: Int, uploadedIso: String
    ): Int = 1
    override suspend fun recoverExpiredPhotoLease(photoId: String, newStatus: String, error: String, nowMillis: Long): Int = 0
    override suspend fun deleteQueuedPhotosForClient(clientId: String) {}
    override suspend fun deleteQueuedPhotosForUser(uid: String) {}
}

class InMemoryReliabilityCacheDao : DynamicOfflineCacheDao {
    private val schemas = mutableMapOf<String, CachedSchemaEntity>()
    private val farBaselines = mutableMapOf<String, CachedFarBaselineEntity>()

    override suspend fun upsertCachedSchema(entity: CachedSchemaEntity) {
        schemas[entity.cacheKey] = entity
    }

    override suspend fun getCachedSchema(clientId: String, cycleId: String): CachedSchemaEntity? {
        return schemas["$clientId:$cycleId"]
    }

    override suspend fun deleteCachedSchema(clientId: String, cycleId: String) {
        schemas.remove("$clientId:$cycleId")
    }

    override suspend fun upsertCachedFarBaseline(entity: CachedFarBaselineEntity) {
        farBaselines[entity.cacheKey] = entity
    }

    override suspend fun upsertCachedFarBaselines(entities: List<CachedFarBaselineEntity>) {
        entities.forEach { farBaselines[it.cacheKey] = it }
    }

    override suspend fun getCachedFarBaseline(clientId: String, cycleId: String, assetKey: String): CachedFarBaselineEntity? {
        return farBaselines["$clientId:$cycleId:$assetKey"]
    }

    override suspend fun getCachedFarBaselinesForCycle(clientId: String, cycleId: String): List<CachedFarBaselineEntity> {
        return farBaselines.values.filter { it.clientId == clientId && it.auditCycleId == cycleId }
    }

    override suspend fun deleteFarBaselinesForClient(clientId: String) {
        farBaselines.entries.removeIf { it.value.clientId == clientId }
    }

    override suspend fun deleteFarBaselinesForCycle(clientId: String, cycleId: String) {
        farBaselines.entries.removeIf { it.value.clientId == clientId && it.value.auditCycleId == cycleId }
    }
}
