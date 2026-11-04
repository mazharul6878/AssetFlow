package com.example

import android.content.Context
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.offline.DynamicSubmissionDaoAdapter
import com.example.data.offline.DynamicSubmissionRecord
import com.example.data.offline.DynamicSyncState
import com.example.data.offline.OfflineQueuedRecordEntity
import com.example.data.offline.OfflineSyncDao
import com.example.data.worker.DynamicSubmissionWorker
import com.example.data.worker.DynamicSyncScheduler
import com.example.data.worker.SyncCoordinator
import com.example.data.worker.WorkSchedulingDecision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DynamicWorkManagerIntegrationTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SyncCoordinator.resetForTesting()

        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        SyncCoordinator.resetForTesting()
    }

    // =========================================================================
    // 1. Multiple rapid scheduling calls for one Client/Cycle
    // =========================================================================
    @Test
    fun testMultipleRapidSchedulingCallsAreCoalescedWithoutUnboundedChaining() {
        val clientId = "client_rapid"
        val cycleId = "cycle_2026"
        val uniqueName = DynamicSyncScheduler.getUniqueWorkName(clientId, cycleId)

        // First schedule call: no active work exists, enqueues fresh work with REPLACE
        val decision1 = DynamicSyncScheduler.scheduleSync(context, clientId, cycleId)
        assertEquals(WorkSchedulingDecision.ENQUEUED_FRESH, decision1)

        // Multiple rapid scheduling calls while the first is ENQUEUED (not yet running)
        val decision2 = DynamicSyncScheduler.scheduleSync(context, clientId, cycleId)
        val decision3 = DynamicSyncScheduler.scheduleSync(context, clientId, cycleId)
        val decision4 = DynamicSyncScheduler.scheduleSync(context, clientId, cycleId)

        // Subsequent calls must be coalesced and not append redundant nodes
        assertEquals(WorkSchedulingDecision.COALESCED_ALREADY_QUEUED, decision2)
        assertEquals(WorkSchedulingDecision.COALESCED_ALREADY_QUEUED, decision3)
        assertEquals(WorkSchedulingDecision.COALESCED_ALREADY_QUEUED, decision4)

        // Assert actual WorkInfo chain length in WorkManager
        val workInfos = workManager.getWorkInfosForUniqueWork(uniqueName).get()
        assertEquals("Chain must contain exactly 1 worker when coalesced before start", 1, workInfos.size)
        assertTrue(workInfos[0].state == WorkInfo.State.ENQUEUED || workInfos[0].state == WorkInfo.State.RUNNING)
        assertEquals(3L, SyncCoordinator.getCoalescedCount(clientId, cycleId))
    }

    // =========================================================================
    // 2. Submission arriving while Worker is active
    // =========================================================================
    @Test
    fun testSubmissionArrivingWhileWorkerActiveEnqueuesAtMostOneFollowUp() {
        val clientId = "client_active"
        val cycleId = "cycle_2026"
        val uniqueName = DynamicSyncScheduler.getUniqueWorkName(clientId, cycleId)

        // Schedule initial work
        DynamicSyncScheduler.scheduleSync(context, clientId, cycleId)
        val initialInfos = workManager.getWorkInfosForUniqueWork(uniqueName).get()
        val initialWorkerId = initialInfos[0].id

        // Put the worker into RUNNING state
        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)
        testDriver?.setAllConstraintsMet(initialWorkerId)

        // Simulate new submission arrival while active
        val decision1 = DynamicSyncScheduler.scheduleSync(context, clientId, cycleId)
        // If the worker was still running, exactly one follow-up is enqueued or coalesced
        assertTrue(
            decision1 == WorkSchedulingDecision.ENQUEUED_FOLLOWUP ||
            decision1 == WorkSchedulingDecision.COALESCED_ALREADY_QUEUED ||
            decision1 == WorkSchedulingDecision.ENQUEUED_FRESH
        )

        // Query WorkInfo chain
        val workInfos = workManager.getWorkInfosForUniqueWork(uniqueName).get()
        assertTrue("WorkInfo chain length must be bounded at <= 2", workInfos.size <= 2)
    }

    // =========================================================================
    // 3. Submission arriving after last empty queue check but before completion
    // =========================================================================
    @Test
    fun testSubmissionArrivingAtCompletionBoundaryIncrementsCoordinatorGeneration() {
        val clientId = "client_boundary"
        val cycleId = "cycle_2026"

        val initialGen = SyncCoordinator.getRequestGeneration(clientId, cycleId)
        assertEquals(0L, initialGen)

        // Worker begins draining at generation 1
        SyncCoordinator.notifySyncRequested(clientId, cycleId)
        val drainStartGen = SyncCoordinator.getRequestGeneration(clientId, cycleId)
        assertEquals(1L, drainStartGen)

        // New submission arrives right at exit boundary
        SyncCoordinator.notifySyncRequested(clientId, cycleId)

        // Worker drain loop detects new request since drainStartGen
        assertTrue(SyncCoordinator.hasNewRequestsSince(clientId, cycleId, drainStartGen))
    }

    // =========================================================================
    // 4. Repeated Sync Now while Worker is running
    // =========================================================================
    @Test
    fun testRepeatedSyncNowWhileRunningIsStrictlyBounded() {
        val clientId = "client_repeated"
        val cycleId = "cycle_2026"
        val uniqueName = DynamicSyncScheduler.getUniqueWorkName(clientId, cycleId)

        // Trigger 10 repeated calls
        val decisions = (1..10).map {
            DynamicSyncScheduler.scheduleSync(context, clientId, cycleId)
        }

        val workInfos = workManager.getWorkInfosForUniqueWork(uniqueName).get()
        assertTrue("WorkInfo chain length must never exceed 2", workInfos.size <= 2)
        assertTrue(decisions.count { it == WorkSchedulingDecision.COALESCED_ALREADY_QUEUED } >= 8)
    }

    // =========================================================================
    // 5. Worker failure followed by a new submission
    // =========================================================================
    @Test
    fun testWorkerFailureFollowedByNewSubmissionUsesReplaceToRestartCleanChain() {
        val clientId = "client_fail"
        val cycleId = "cycle_2026"
        val uniqueName = DynamicSyncScheduler.getUniqueWorkName(clientId, cycleId)

        // Schedule initial work
        DynamicSyncScheduler.scheduleSync(context, clientId, cycleId)
        val workInfos1 = workManager.getWorkInfosForUniqueWork(uniqueName).get()
        val workerId1 = workInfos1[0].id

        // Cancel the worker to simulate an unrecoverable failure/terminal state
        workManager.cancelWorkById(workerId1).result.get()

        val afterCancelInfos = workManager.getWorkInfosForUniqueWork(uniqueName).get()
        assertEquals(WorkInfo.State.CANCELLED, afterCancelInfos[0].state)

        // New submission arrives: scheduleSync must detect terminal state and use REPLACE
        val decision = DynamicSyncScheduler.scheduleSync(context, clientId, cycleId)
        assertEquals(WorkSchedulingDecision.ENQUEUED_FRESH, decision)

        val newWorkInfos = workManager.getWorkInfosForUniqueWork(uniqueName).get()
        assertEquals(1, newWorkInfos.size)
        assertTrue(
            "New work must be active and not blocked by the cancelled parent",
            newWorkInfos[0].state == WorkInfo.State.ENQUEUED || newWorkInfos[0].state == WorkInfo.State.RUNNING
        )
    }

    // =========================================================================
    // 6. Worker cancellation followed by a new scheduling request
    // =========================================================================
    @Test
    fun testWorkerCancellationFollowedByNewRequestStartsFreshWithoutPermanentBlock() {
        val clientId = "client_cancel"
        val cycleId = "cycle_2026"
        val uniqueName = DynamicSyncScheduler.getUniqueWorkName(clientId, cycleId)

        DynamicSyncScheduler.scheduleSync(context, clientId, cycleId)
        DynamicSyncScheduler.cancelAllSync(context, clientId, cycleId)

        val cancelledInfos = workManager.getWorkInfosForUniqueWork(uniqueName).get()
        assertTrue(cancelledInfos.all { it.state == WorkInfo.State.CANCELLED })

        // Next schedule must succeed cleanly
        val decision = DynamicSyncScheduler.scheduleSync(context, clientId, cycleId)
        assertEquals(WorkSchedulingDecision.ENQUEUED_FRESH, decision)

        val activeInfos = workManager.getWorkInfosForUniqueWork(uniqueName).get()
        assertEquals(1, activeInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, activeInfos[0].state)
    }

    // =========================================================================
    // 7. Different Clients scheduling work independently
    // =========================================================================
    @Test
    fun testDifferentClientsScheduleWorkIndependently() {
        val clientA = "client_alpha"
        val clientB = "client_beta"
        val cycle = "cycle_2026"

        val decisionA = DynamicSyncScheduler.scheduleSync(context, clientA, cycle)
        val decisionB = DynamicSyncScheduler.scheduleSync(context, clientB, cycle)

        assertEquals(WorkSchedulingDecision.ENQUEUED_FRESH, decisionA)
        assertEquals(WorkSchedulingDecision.ENQUEUED_FRESH, decisionB)

        val nameA = DynamicSyncScheduler.getUniqueWorkName(clientA, cycle)
        val nameB = DynamicSyncScheduler.getUniqueWorkName(clientB, cycle)

        val infosA = workManager.getWorkInfosForUniqueWork(nameA).get()
        val infosB = workManager.getWorkInfosForUniqueWork(nameB).get()

        assertEquals(1, infosA.size)
        assertEquals(1, infosB.size)
        assertFalse(infosA[0].id == infosB[0].id)
    }

    // =========================================================================
    // 8. Different Audit Cycles scheduling work independently
    // =========================================================================
    @Test
    fun testDifferentAuditCyclesScheduleWorkIndependently() {
        val client = "client_multi_cycle"
        val cycle1 = "cycle_2025"
        val cycle2 = "cycle_2026"

        val decision1 = DynamicSyncScheduler.scheduleSync(context, client, cycle1)
        val decision2 = DynamicSyncScheduler.scheduleSync(context, client, cycle2)

        assertEquals(WorkSchedulingDecision.ENQUEUED_FRESH, decision1)
        assertEquals(WorkSchedulingDecision.ENQUEUED_FRESH, decision2)

        val name1 = DynamicSyncScheduler.getUniqueWorkName(client, cycle1)
        val name2 = DynamicSyncScheduler.getUniqueWorkName(client, cycle2)

        val infos1 = workManager.getWorkInfosForUniqueWork(name1).get()
        val infos2 = workManager.getWorkInfosForUniqueWork(name2).get()

        assertEquals(1, infos1.size)
        assertEquals(1, infos2.size)
        assertFalse(infos1[0].id == infos2[0].id)
    }

    // =========================================================================
    // 9. Actual WorkInfo chain length after 20 repeated submissions
    // =========================================================================
    @Test
    fun testWorkInfoChainLengthRemainsBoundedAfter20Submissions() {
        val client = "client_stress"
        val cycle = "cycle_2026"
        val uniqueName = DynamicSyncScheduler.getUniqueWorkName(client, cycle)

        for (i in 1..20) {
            DynamicSyncScheduler.scheduleSync(context, client, cycle)
        }

        val workInfos = workManager.getWorkInfosForUniqueWork(uniqueName).get()
        assertTrue("WorkInfo chain length must be bounded <= 2, actual: ${workInfos.size}", workInfos.size <= 2)
        assertEquals(19L, SyncCoordinator.getCoalescedCount(client, cycle))
    }

    // =========================================================================
    // 10. Concurrency protection: competing workers cannot process same record
    // =========================================================================
    @Test
    fun testConcurrencyProtectionPreventsDuplicateClaim() {
        val mockDao = InMemoryWorkManagerDao()
        val adapter = DynamicSubmissionDaoAdapter(mockDao)

        val record = DynamicSubmissionRecord(
            clientId = "client_concur",
            auditCycleId = "cycle_2026",
            assetKey = "key_concur_1",
            assetId = "000123",
            schemaVersionId = "sch_v1",
            countDate = "2026-11-01",
            fieldValues = mapOf("fld" to "Val"),
            auditorUid = "aud_1"
        )

        runBlocking {
            adapter.upsertDynamicSubmission(record)

            val claim1 = adapter.claimForSync(record.recordKey, "worker_1", 60_000L)
            val claim2 = adapter.claimForSync(record.recordKey, "worker_2", 60_000L)

            assertTrue("First worker must claim successfully", claim1)
            assertFalse("Second worker must be rejected from active lease", claim2)

            val stored = adapter.getDynamicSubmission(record.recordKey)
            assertEquals("worker_1", stored?.leaseOwnerId)
            assertEquals(DynamicSyncState.SYNCING, stored?.syncStatus)
        }
    }

    private class InMemoryWorkManagerDao : OfflineSyncDao {
        private val records = ConcurrentHashMap<String, OfflineQueuedRecordEntity>()

        override fun observeAllQueuedRecords(): Flow<List<OfflineQueuedRecordEntity>> = flowOf(records.values.toList())
        override fun observeQueuedRecordsForUser(uid: String): Flow<List<OfflineQueuedRecordEntity>> = flowOf(records.values.filter { it.userId == uid })
        override suspend fun getPendingOrFailedRecords(): List<OfflineQueuedRecordEntity> = records.values.filter { it.syncStatus in listOf("Pending Sync", "Failed") }
        override suspend fun getPendingOrFailedRecordsForUser(uid: String): List<OfflineQueuedRecordEntity> = records.values.filter { it.userId == uid && it.syncStatus in listOf("Pending Sync", "Failed") }
        override suspend fun getQueuedRecordByKey(key: String): OfflineQueuedRecordEntity? = records[key]
        override suspend fun upsertQueuedRecord(entity: OfflineQueuedRecordEntity) { records[entity.recordKey] = entity }
        override suspend fun updateRecordSyncStatus(recordKey: String, status: String, error: String, retries: Int, syncedAt: Long) {
            records[recordKey]?.let { records[recordKey] = it.copy(syncStatus = status, errorMessage = error, retryCount = retries, lastSyncedAtMillis = syncedAt) }
        }
        override suspend fun deleteQueuedRecord(recordKey: String) { records.remove(recordKey) }
        override suspend fun deleteQueuedRecordsForClient(clientId: String) { records.values.filter { it.clientId == clientId }.forEach { records.remove(it.recordKey) } }
        override suspend fun deleteQueuedRecordsForUser(uid: String) { records.values.filter { it.userId == uid }.forEach { records.remove(it.recordKey) } }
        override fun observeAllPhotoQueue() = flowOf(emptyList<com.example.data.offline.OfflinePhotoQueueEntity>())
        override fun observePhotoQueueForUser(uid: String) = flowOf(emptyList<com.example.data.offline.OfflinePhotoQueueEntity>())
        override suspend fun getPhotosForRecord(recordKey: String) = emptyList<com.example.data.offline.OfflinePhotoQueueEntity>()
        override suspend fun getPhotoById(photoId: String) = null
        override suspend fun getPendingOrFailedPhotos() = emptyList<com.example.data.offline.OfflinePhotoQueueEntity>()
        override suspend fun getPendingOrFailedPhotosForUser(uid: String) = emptyList<com.example.data.offline.OfflinePhotoQueueEntity>()
        override suspend fun getPhotosForClient(clientId: String) = emptyList<com.example.data.offline.OfflinePhotoQueueEntity>()
        override suspend fun getPhotosForUser(uid: String) = emptyList<com.example.data.offline.OfflinePhotoQueueEntity>()
        override suspend fun upsertPhotoQueue(entity: com.example.data.offline.OfflinePhotoQueueEntity) {}
        override suspend fun updatePhotoUploadStatus(photoId: String, status: String, driveFileId: String, webLink: String, error: String, retries: Int, syncedAt: Long, dateFolderId: String, dateFolderName: String, finalName: String, ext: String, mime: String, seq: Int, uploadedIso: String) {}
        override suspend fun claimPhotoLease(photoId: String, ownerToken: String, newStatus: String, expiresAtMillis: Long, nowMillis: Long): Int = 1
        override suspend fun renewPhotoLease(photoId: String, ownerToken: String, newExpiresAtMillis: Long): Int = 1
        override suspend fun updatePhotoIntermediateStatusWithLease(photoId: String, ownerToken: String, status: String, driveFileId: String, webLink: String, error: String, retries: Int, syncedAt: Long, dateFolderId: String, dateFolderName: String, finalName: String, ext: String, mime: String, seq: Int, uploadedIso: String, newExpiresAtMillis: Long): Int = 1
        override suspend fun updatePhotoStatusWithLease(photoId: String, ownerToken: String, status: String, driveFileId: String, webLink: String, error: String, retries: Int, syncedAt: Long, dateFolderId: String, dateFolderName: String, finalName: String, ext: String, mime: String, seq: Int, uploadedIso: String): Int = 1
        override suspend fun recoverExpiredPhotoLease(photoId: String, newStatus: String, error: String, nowMillis: Long): Int = 0
        override suspend fun deleteQueuedPhotosForClient(clientId: String) {}
        override suspend fun deleteQueuedPhotosForUser(uid: String) {}
    }
}
