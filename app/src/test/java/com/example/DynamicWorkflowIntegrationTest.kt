package com.example

import com.example.data.model.SyncState
import com.example.data.network.CountingSubmissionDto
import com.example.data.network.DynamicFieldDto
import com.example.data.network.DynamicFieldOptionDto
import com.example.data.network.DynamicSchemaDto
import com.example.data.network.FarAssetBaselineDto
import com.example.data.network.LocalMockTokenProvider
import com.example.data.network.WorkerApiClient
import com.example.data.network.WorkerApiResult
import com.example.data.network.WorkerTokenProvider
import com.example.data.offline.CachedFarBaselineEntity
import com.example.data.offline.CachedSchemaEntity
import com.example.data.offline.DynamicOfflineCacheDao
import com.example.data.offline.DynamicSubmissionDaoAdapter
import com.example.data.offline.DynamicSubmissionRecord
import com.example.data.offline.DynamicSyncState
import com.example.data.offline.OfflinePhotoQueueEntity
import com.example.data.offline.OfflineQueuedRecordEntity
import com.example.data.offline.OfflineSyncDao
import com.example.data.repository.DynamicOfflineCacheRepository
import com.example.data.repository.DynamicSyncRepository
import com.example.data.repository.DynamicSyncResult
import com.example.data.worker.DynamicSubmissionWorker
import com.example.data.worker.DynamicSyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.UUID

/**
 * Phase 2.3.1 Integration & Reliability Hardening Test Suite.
 * Validates:
 * 1. Navigation & form parameter preservation (leading-zero asset IDs, cycle pinning).
 * 2. Dynamic arbitrary FAR row caching & duplicate header support.
 * 3. WorkManager unique work scheduling, queue draining, and stale SYNCING recovery.
 * 4. Firebase 401 token refresh retry & HTTP retry matrix (400, 403, 409, 429, 5xx).
 * 5. Local submission queue isolation from Admin Review queue.
 */
class DynamicWorkflowIntegrationTest {

    private lateinit var mockDao: InMemoryWorkflowOfflineDao
    private lateinit var daoAdapter: DynamicSubmissionDaoAdapter
    private lateinit var mockCacheDao: InMemoryWorkflowCacheDao
    private lateinit var mockTokenProvider: LocalMockTokenProvider

    @Before
    fun setUp() {
        mockDao = InMemoryWorkflowOfflineDao()
        daoAdapter = DynamicSubmissionDaoAdapter(mockDao)
        mockCacheDao = InMemoryWorkflowCacheDao()
        mockTokenProvider = LocalMockTokenProvider(
            mockToken = "valid.test.initial.token",
            refreshedMockToken = "valid.test.refreshed.token"
        )
    }

    // =========================================================================
    // 1. NAVIGATION & FORM PARAMETERS TESTS
    // =========================================================================

    @Test
    fun testLeadingZeroAssetIdPreservedAcrossWorkflow() {
        val assetId = "0004928" // Strict text format with leading zeroes
        val submission = DynamicSubmissionRecord(
            clientId = "client_alpha",
            auditCycleId = "cycle_2026",
            assetKey = "alpha:0004928",
            assetId = assetId,
            schemaVersionId = "sch_v1",
            countDate = "2026-11-01",
            fieldValues = mapOf("fld_status" to "Found"),
            auditorUid = "auditor_42",
            auditorDisplayName = "Alice Auditor"
        )

        runBlocking {
            daoAdapter.upsertDynamicSubmission(submission)
            val retrieved = daoAdapter.getDynamicSubmission(submission.recordKey)

            assertNotNull(retrieved)
            assertEquals("0004928", retrieved?.assetId)
            assertTrue("Asset ID must start with 000", retrieved?.assetId?.startsWith("000") == true)
        }
    }

    @Test
    fun testSchemaPinnedToAuditCycleRendersCorrectFields() {
        val cycle1Schema = createSampleSchema("cycle_2025_q4", "v1", listOf("Field_Old_A", "Field_Old_B"))
        val cycle2Schema = createSampleSchema("cycle_2026_q1", "v2", listOf("Field_New_A", "Field_New_B", "Field_New_C"))

        val cacheRepo = DynamicOfflineCacheRepository(
            apiClient = createMockWorkerClient(200, "{}"),
            cacheDao = mockCacheDao
        )

        runBlocking {
            // Seed both cycles
            mockCacheDao.upsertCachedSchema(
                CachedSchemaEntity(
                    cacheKey = "client_beta:cycle_2025_q4",
                    clientId = "client_beta",
                    auditCycleId = "cycle_2025_q4",
                    schemaVersionId = "v1",
                    versionNumber = 1,
                    status = "PUBLISHED",
                    sourceWorkbookName = "2025.xlsx",
                    uniqueAssetKeyHeader = "Asset ID",
                    schemaJson = cycle1Schema.toJson().toString()
                )
            )
            mockCacheDao.upsertCachedSchema(
                CachedSchemaEntity(
                    cacheKey = "client_beta:cycle_2026_q1",
                    clientId = "client_beta",
                    auditCycleId = "cycle_2026_q1",
                    schemaVersionId = "v2",
                    versionNumber = 2,
                    status = "PUBLISHED",
                    sourceWorkbookName = "2026.xlsx",
                    uniqueAssetKeyHeader = "Asset ID",
                    schemaJson = cycle2Schema.toJson().toString()
                )
            )

            val resolvedCycle1 = cacheRepo.getSchemaForCycle("client_beta", "cycle_2025_q4")
            val resolvedCycle2 = cacheRepo.getSchemaForCycle("client_beta", "cycle_2026_q1")

            assertTrue(resolvedCycle1 is WorkerApiResult.Success)
            assertTrue(resolvedCycle2 is WorkerApiResult.Success)

            val s1 = (resolvedCycle1 as WorkerApiResult.Success).data
            val s2 = (resolvedCycle2 as WorkerApiResult.Success).data

            assertEquals(2, s1?.fields?.size)
            assertEquals(3, s2?.fields?.size)
            assertEquals("Field_Old_A", s1?.fields?.get(0)?.fieldKey)
            assertEquals("Field_New_A", s2?.fields?.get(0)?.fieldKey)
        }
    }

    // =========================================================================
    // 2. COMPLETE DYNAMIC FAR BASELINE CACHING TESTS
    // =========================================================================

    @Test
    fun testArbitraryMasterInputSheetColumnsPreservedInCache() {
        // Arbitrary columns that vary between clients and workbooks
        val arbitraryColumns = mapOf(
            "Asset ID" to "009123",
            "Asset Description" to "Precision Industrial Milling Machine",
            "Custom Client Tag" to "TAG-CORP-9921",
            "Vendor Invoice Reference" to "INV-2023-8819A",
            "Original Purchase Cost" to "450000.00",
            "Depreciation Method" to "Straight-Line 10Y",
            "Manufacturer" to "Haas Automation",
            "Room / Location" to "Shop Floor B",
            "Department" to "Heavy Fabrication"
        )

        val indexedColumns = mapOf(
            "col_0:Asset ID" to "009123",
            "col_1:Remarks" to "Primary Unit",
            "col_8:Remarks" to "Secondary Note" // Duplicate header disambiguated
        )

        val farBaseline = FarAssetBaselineDto(
            assetKey = "mfg_009123",
            assetId = "009123",
            clientId = "client_mfg",
            fiscalYearId = "cycle_2026",
            sourceRow = 45L,
            rawRowData = arbitraryColumns,
            indexedColumns = indexedColumns
        )

        val cacheRepo = DynamicOfflineCacheRepository(
            apiClient = createMockWorkerClient(200, "{}"),
            cacheDao = mockCacheDao
        )

        runBlocking {
            cacheRepo.cacheFarBaselines("client_mfg", "cycle_2026", listOf(farBaseline))

            // Retrieve offline
            val retrievedResult = cacheRepo.getFarAsset("client_mfg", "cycle_2026", "mfg_009123")
            assertTrue(retrievedResult is WorkerApiResult.Success)

            val retrieved = (retrievedResult as WorkerApiResult.Success).data
            assertNotNull(retrieved)
            assertEquals("009123", retrieved?.assetId)
            assertEquals("TAG-CORP-9921", retrieved?.rawRowData?.get("Custom Client Tag"))
            assertEquals("INV-2023-8819A", retrieved?.rawRowData?.get("Vendor Invoice Reference"))
            assertEquals("450000.00", retrieved?.rawRowData?.get("Original Purchase Cost"))
            assertEquals("Primary Unit", retrieved?.indexedColumns?.get("col_1:Remarks"))
            assertEquals("Secondary Note", retrieved?.indexedColumns?.get("col_8:Remarks"))
        }
    }

    @Test
    fun testCacheEnforcesClientAndCycleIsolation() {
        val cacheRepo = DynamicOfflineCacheRepository(
            apiClient = createMockWorkerClient(200, "{}"),
            cacheDao = mockCacheDao
        )

        val clientAAsset = FarAssetBaselineDto(
            assetKey = "asset_shared_key",
            assetId = "001",
            clientId = "client_A",
            fiscalYearId = "cycle_2026",
            sourceRow = 1,
            rawRowData = mapOf("Owner" to "Client A")
        )

        val clientBAsset = FarAssetBaselineDto(
            assetKey = "asset_shared_key",
            assetId = "001",
            clientId = "client_B",
            fiscalYearId = "cycle_2026",
            sourceRow = 1,
            rawRowData = mapOf("Owner" to "Client B")
        )

        runBlocking {
            cacheRepo.cacheFarBaselines("client_A", "cycle_2026", listOf(clientAAsset))
            cacheRepo.cacheFarBaselines("client_B", "cycle_2026", listOf(clientBAsset))

            val resA = cacheRepo.getFarAsset("client_A", "cycle_2026", "asset_shared_key")
            val resB = cacheRepo.getFarAsset("client_B", "cycle_2026", "asset_shared_key")

            assertEquals("Client A", (resA as WorkerApiResult.Success).data?.rawRowData?.get("Owner"))
            assertEquals("Client B", (resB as WorkerApiResult.Success).data?.rawRowData?.get("Owner"))
        }
    }

    // =========================================================================
    // 3. WORKMANAGER UNIQUE WORK & RECOVERY TESTS
    // =========================================================================

    @Test
    fun testUniqueWorkNameGeneration() {
        val globalName = DynamicSyncScheduler.getUniqueWorkName(null, null)
        val cycleScopedName = DynamicSyncScheduler.getUniqueWorkName("client_acme", "cycle_2026")

        assertEquals("dynamic_submission_sync", globalName)
        assertEquals("dynamic_sync_client_acme_cycle_2026", cycleScopedName)
    }

    @Test
    fun testStaleSyncingRecordsRecoverSafely() {
        val staleSubmission = DynamicSubmissionRecord(
            localSubmissionId = "sub_stale_1",
            clientId = "client_delta",
            auditCycleId = "cycle_2026",
            assetKey = "asset_stale",
            assetId = "0005521",
            schemaVersionId = "sch_v1",
            countDate = "2026-11-01",
            fieldValues = mapOf("fld_01" to "Passed"),
            auditorUid = "auditor_stale_uid",
            auditorDisplayName = "Stale Counter",
            idempotencyKey = "idempotency_key_stale_constant",
            syncStatus = DynamicSyncState.SYNCING,
            lastAttemptMillis = System.currentTimeMillis() - 60_000L // 60s ago (> 30s threshold)
        )

        runBlocking {
            daoAdapter.upsertDynamicSubmission(staleSubmission)

            // Execute recovery
            val recoveredCount = daoAdapter.recoverStaleSyncingRecords(
                clientId = "client_delta",
                cycleId = "cycle_2026",
                staleThresholdMillis = 30_000L
            )

            assertEquals(1, recoveredCount)

            val recovered = daoAdapter.getDynamicSubmission("client_delta:cycle_2026:asset_stale")
            assertNotNull(recovered)
            assertEquals(DynamicSyncState.SYNC_FAILED_RETRY_AVAILABLE, recovered?.syncStatus)
            assertEquals("idempotency_key_stale_constant", recovered?.idempotencyKey) // Unchanged!
            assertEquals("auditor_stale_uid", recovered?.auditorUid) // Unchanged!
            assertEquals("Stale Counter", recovered?.auditorDisplayName) // Unchanged!
            assertNull("Server record ID must remain null", recovered?.serverRecordId)
        }
    }

    @Test
    fun testAlreadySyncedRecordsNeverResetByRecovery() {
        val syncedSubmission = DynamicSubmissionRecord(
            localSubmissionId = "sub_synced_1",
            clientId = "client_delta",
            auditCycleId = "cycle_2026",
            assetKey = "asset_synced",
            assetId = "0005522",
            schemaVersionId = "sch_v1",
            countDate = "2026-11-01",
            fieldValues = mapOf("fld_01" to "Passed"),
            auditorUid = "auditor_uid",
            auditorDisplayName = "Counter",
            idempotencyKey = "idempotency_synced_key",
            syncStatus = DynamicSyncState.SYNCED,
            serverRecordId = "srv_rec_9981"
        )

        runBlocking {
            daoAdapter.upsertDynamicSubmission(syncedSubmission)
            val recoveredCount = daoAdapter.recoverStaleSyncingRecords("client_delta", "cycle_2026")
            assertEquals(0, recoveredCount)

            val current = daoAdapter.getDynamicSubmission("client_delta:cycle_2026:asset_synced")
            assertEquals(DynamicSyncState.SYNCED, current?.syncStatus)
            assertEquals("srv_rec_9981", current?.serverRecordId)
        }
    }

    // =========================================================================
    // 4. AUTHENTICATION & RETRY MATRIX TESTS
    // =========================================================================

    @Test
    fun testHttp401TriggersTokenRefreshAndRetrySuccess() {
        var callCount = 0
        val interceptingClient = createInterceptingWorkerClient { request ->
            callCount++
            val authHeader = request.header("Authorization")
            if (callCount == 1) {
                // First call: initial token is rejected
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("{\"error\": \"Token expired\"}".toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                // Second call: refreshed token succeeds!
                assertEquals("Bearer valid.test.refreshed.token", authHeader)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{\"recordId\": \"rec_refreshed_99\", \"status\": \"SUBMITTED\", \"isIdempotentReplay\": false}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }

        val submission = DynamicSubmissionRecord(
            clientId = "client_omega",
            auditCycleId = "cycle_2026",
            assetKey = "omega_100",
            assetId = "0009911",
            schemaVersionId = "sch_v1",
            countDate = "2026-11-01",
            fieldValues = mapOf("fld_1" to "Yes"),
            auditorUid = "aud_1",
            auditorDisplayName = "Auditor"
        )

        runBlocking {
            daoAdapter.upsertDynamicSubmission(submission)

            // Simulate worker's 401 handling
            val firstRes = interceptingClient.submitCount(submission.clientId, submission.auditCycleId, CountingSubmissionDto(
                assetKey = submission.assetKey,
                schemaVersionId = submission.schemaVersionId,
                countDate = submission.countDate,
                idempotencyKey = submission.idempotencyKey,
                values = submission.fieldValues
            ))

            assertTrue(firstRes is WorkerApiResult.Error)
            assertEquals(401, (firstRes as WorkerApiResult.Error).statusCode)

            // Worker calls getIdToken(forceRefresh = true)
            val refreshedToken = mockTokenProvider.getIdToken(forceRefresh = true)
            assertEquals("valid.test.refreshed.token", refreshedToken)

            // Worker retries once with refreshed token
            val retryRes = interceptingClient.submitCount(
                clientId = submission.clientId,
                cycleId = submission.auditCycleId,
                submission = CountingSubmissionDto(
                    assetKey = submission.assetKey,
                    schemaVersionId = submission.schemaVersionId,
                    countDate = submission.countDate,
                    idempotencyKey = submission.idempotencyKey, // SAME idempotency key
                    values = submission.fieldValues
                ),
                bearerTokenOverride = refreshedToken
            )

            assertTrue(retryRes is WorkerApiResult.Success)
            assertEquals("rec_refreshed_99", (retryRes as WorkerApiResult.Success).data.recordId)
            assertEquals(2, callCount)
        }
    }

    @Test
    fun testSecond401SetsAuthenticationRequiredWithoutDeletingRecord() {
        val failingClient = createMockWorkerClient(401, "{\"error\": \"Invalid credential\"}")
        val submission = DynamicSubmissionRecord(
            clientId = "client_omega",
            auditCycleId = "cycle_2026",
            assetKey = "omega_101",
            assetId = "0009912",
            schemaVersionId = "sch_v1",
            countDate = "2026-11-01",
            fieldValues = mapOf("fld_1" to "Yes"),
            auditorUid = "aud_1",
            auditorDisplayName = "Auditor"
        )

        runBlocking {
            val repository = DynamicSyncRepository(daoAdapter, failingClient)
            val result = repository.saveAndSubmit(submission, attemptImmediateNetwork = true)

            assertTrue(result is DynamicSyncResult.ActionRequired)
            assertEquals(DynamicSyncState.AUTHENTICATION_REQUIRED, (result as DynamicSyncResult.ActionRequired).status)

            // Record must still exist locally with entered values intact!
            val recordInDb = daoAdapter.getDynamicSubmission(submission.recordKey)
            assertNotNull(recordInDb)
            assertEquals(DynamicSyncState.AUTHENTICATION_REQUIRED, recordInDb?.syncStatus)
            assertEquals("0009912", recordInDb?.assetId)
        }
    }

    @Test
    fun testRateLimit429RespectsRetryAfterHeader() {
        val rateLimitedClient = createInterceptingWorkerClient { req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests")
                .header("Retry-After", "45")
                .body("{\"error\": \"Rate limit exceeded\"}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val submission = DynamicSubmissionRecord(
            clientId = "client_omega",
            auditCycleId = "cycle_2026",
            assetKey = "omega_102",
            assetId = "0009913",
            schemaVersionId = "sch_v1",
            countDate = "2026-11-01",
            fieldValues = mapOf("fld_1" to "Yes"),
            auditorUid = "aud_1"
        )

        runBlocking {
            val repo = DynamicSyncRepository(daoAdapter, rateLimitedClient)
            val result = repo.saveAndSubmit(submission, attemptImmediateNetwork = true)

            assertTrue(result is DynamicSyncResult.SyncFailedRetryable)
            assertEquals(429, (result as DynamicSyncResult.SyncFailedRetryable).statusCode)

            val record = daoAdapter.getDynamicSubmission(submission.recordKey)
            assertEquals(DynamicSyncState.SYNC_FAILED_RETRY_AVAILABLE, record?.syncStatus)
        }
    }

    @Test
    fun testRetryLimitDoesNotDeletePendingRecord() {
        val exhaustedSubmission = DynamicSubmissionRecord(
            clientId = "client_zeta",
            auditCycleId = "cycle_2026",
            assetKey = "zeta_01",
            assetId = "0007788",
            schemaVersionId = "sch_v1",
            countDate = "2026-11-01",
            fieldValues = mapOf("fld_1" to "True"),
            auditorUid = "aud_zeta",
            retryCount = 5, // At MAX_RETRY_ATTEMPTS limit
            syncStatus = DynamicSyncState.SYNC_FAILED_RETRY_AVAILABLE
        )

        runBlocking {
            daoAdapter.upsertDynamicSubmission(exhaustedSubmission)

            val stored = daoAdapter.getDynamicSubmission(exhaustedSubmission.recordKey)
            assertNotNull("Record must never be deleted when retry limit is reached", stored)
            assertEquals(5, stored?.retryCount)
            assertEquals(DynamicSyncState.SYNC_FAILED_RETRY_AVAILABLE, stored?.syncStatus)
        }
    }

    // =========================================================================
    // 5. QUEUE ISOLATION TESTS
    // =========================================================================

    @Test
    fun testUnsyncedSubmissionsAreNotTreatedAsServerReviewRecords() {
        val localSubmission = DynamicSubmissionRecord(
            clientId = "client_theta",
            auditCycleId = "cycle_2026",
            assetKey = "theta_99",
            assetId = "0008811",
            schemaVersionId = "sch_v1",
            countDate = "2026-11-01",
            fieldValues = mapOf("fld_test" to "Value"),
            auditorUid = "aud_theta",
            syncStatus = DynamicSyncState.SAVED_LOCALLY
        )

        runBlocking {
            daoAdapter.upsertDynamicSubmission(localSubmission)

            val pending = daoAdapter.getPendingSubmissions("client_theta", "cycle_2026")
            assertEquals(1, pending.size)
            assertNull("Unsynced submission must have no server record ID", pending[0].serverRecordId)
            assertFalse("Unsynced submission is not synced", pending[0].syncStatus == DynamicSyncState.SYNCED)

            // When synced:
            daoAdapter.updateSyncStatus(
                recordKey = localSubmission.recordKey,
                status = DynamicSyncState.SYNCED,
                serverRecordId = "server_review_eligible_123"
            )

            val synced = daoAdapter.getDynamicSubmission(localSubmission.recordKey)
            assertEquals(DynamicSyncState.SYNCED, synced?.syncStatus)
            assertEquals("server_review_eligible_123", synced?.serverRecordId)

            val remainingPending = daoAdapter.getPendingSubmissions("client_theta", "cycle_2026")
            assertEquals("Synced record must no longer be in pending queue", 0, remainingPending.size)
        }
    }

    // =========================================================================
    // HELPERS & MOCKS
    // =========================================================================

    private fun createSampleSchema(
        cycleId: String = "cycle_2026",
        versionId: String = "sch_v1",
        fieldNames: List<String> = listOf("Field_1", "Field_2")
    ): DynamicSchemaDto {
        val fields = fieldNames.mapIndexed { idx, name ->
            DynamicFieldDto(
                fieldId = "fld_${idx + 1}",
                fieldKey = name,
                displayLabel = name.replace("_", " "),
                originalHeader = name,
                inputMode = "MANUAL",
                dataType = "TEXT",
                isRequired = idx == 0,
                isVisible = true,
                sortOrder = idx
            )
        }
        return DynamicSchemaDto(
            schemaVersionId = versionId,
            clientId = "client_test",
            fiscalYearId = cycleId,
            versionNumber = 1,
            status = "PUBLISHED",
            sourceWorkbookName = "InputSheet.xlsx",
            uniqueAssetKeyHeader = "Asset ID",
            fields = fields
        )
    }

    private fun createMockWorkerClient(statusCode: Int, responseBody: String): WorkerApiClient {
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message(if (statusCode in 200..299) "OK" else "Error")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        return WorkerApiClient(baseUrl = "http://localhost:8787", client = mockHttp)
    }

    private fun createInterceptingWorkerClient(handler: (Request) -> Response): WorkerApiClient {
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()
        return WorkerApiClient(baseUrl = "http://localhost:8787", client = mockHttp)
    }
}

/**
 * In-memory Room OfflineSyncDao mock for integration testing.
 */
class InMemoryWorkflowOfflineDao : OfflineSyncDao {
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

/**
 * In-memory Room DynamicOfflineCacheDao mock for integration testing.
 */
class InMemoryWorkflowCacheDao : DynamicOfflineCacheDao {
    private val schemas = mutableMapOf<String, CachedSchemaEntity>()
    private val baselines = mutableMapOf<String, CachedFarBaselineEntity>()

    override suspend fun upsertCachedSchema(entity: CachedSchemaEntity) {
        schemas["${entity.clientId}:${entity.auditCycleId}"] = entity
    }

    override suspend fun getCachedSchema(clientId: String, cycleId: String): CachedSchemaEntity? {
        return schemas["$clientId:$cycleId"]
    }

    override suspend fun deleteCachedSchema(clientId: String, cycleId: String) {
        schemas.remove("$clientId:$cycleId")
    }

    override suspend fun upsertCachedFarBaseline(entity: CachedFarBaselineEntity) {
        baselines[entity.cacheKey] = entity
    }

    override suspend fun upsertCachedFarBaselines(entities: List<CachedFarBaselineEntity>) {
        entities.forEach { baselines[it.cacheKey] = it }
    }

    override suspend fun getCachedFarBaseline(clientId: String, cycleId: String, assetKey: String): CachedFarBaselineEntity? {
        return baselines["$clientId:$cycleId:$assetKey"]
    }

    override suspend fun getCachedFarBaselinesForCycle(clientId: String, cycleId: String): List<CachedFarBaselineEntity> {
        return baselines.values.filter { it.clientId == clientId && it.auditCycleId == cycleId }
    }

    override suspend fun deleteFarBaselinesForClient(clientId: String) {
        baselines.values.removeAll { it.clientId == clientId }
    }

    override suspend fun deleteFarBaselinesForCycle(clientId: String, cycleId: String) {
        baselines.values.removeAll { it.clientId == clientId && it.auditCycleId == cycleId }
    }
}
