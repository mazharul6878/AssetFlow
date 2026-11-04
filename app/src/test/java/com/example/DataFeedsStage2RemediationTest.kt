package com.example

import com.example.data.model.DailyBatchSubmissionRecord
import com.example.data.model.FARAsset
import com.example.data.model.SubmissionBatchStatus
import com.example.data.network.DailySheetDetailDto
import com.example.data.network.DailySheetItemDto
import com.example.data.network.DailySheetRecordDto
import com.example.data.network.FarAssetItemDto
import com.example.data.network.FarSearchResultDto
import com.example.data.network.WorkerApiClient
import com.example.data.network.WorkerApiResult
import com.example.data.offline.CachedFarBaselineEntity
import com.example.data.offline.CachedSchemaEntity
import com.example.data.offline.DynamicOfflineCacheDao
import com.example.data.remote.FirebaseFunctionsService
import com.example.data.repository.AuditRepository
import com.example.data.repository.DailySheetsDataFeedState
import com.example.data.repository.FarDataFeedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Stage 2 Verification & Remediation Test Suite.
 * Validates Workstream A (FAR Asset Data Feed AF-002),
 * Workstream B (Daily Sheets Data Feed AF-002), and
 * Workstream C (AF-004 Required Read API Bridges).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataFeedsStage2RemediationTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * In-memory implementation of DynamicOfflineCacheDao for faithful Room DAO simulation.
     */
    private class InMemoryDynamicOfflineCacheDao : DynamicOfflineCacheDao {
        val schemas = mutableMapOf<String, CachedSchemaEntity>()
        val farBaselines = mutableMapOf<String, CachedFarBaselineEntity>()

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
            for (e in entities) {
                farBaselines[e.cacheKey] = e
            }
        }
        override suspend fun getCachedFarBaseline(clientId: String, cycleId: String, assetKey: String): CachedFarBaselineEntity? {
            return farBaselines["$clientId:$cycleId:$assetKey"]
        }
        override suspend fun getCachedFarBaselinesForCycle(clientId: String, cycleId: String): List<CachedFarBaselineEntity> {
            return farBaselines.values.filter { it.clientId == clientId && it.auditCycleId == cycleId }
        }
        override suspend fun deleteFarBaselinesForClient(clientId: String) {
            farBaselines.entries.removeAll { it.value.clientId == clientId }
        }
        override suspend fun deleteFarBaselinesForCycle(clientId: String, cycleId: String) {
            farBaselines.entries.removeAll { it.value.clientId == clientId && it.value.auditCycleId == cycleId }
        }
    }

    private fun createRepository(workerClient: WorkerApiClient, dao: DynamicOfflineCacheDao? = null): AuditRepository {
        val repo = AuditRepository(
            db = null,
            offlineSyncDao = null,
            functionsService = object : FirebaseFunctionsService(null) {},
            workerApiClient = workerClient
        )
        repo.dynamicOfflineCacheDao = dao
        return repo
    }

    // -------------------------------------------------------------------------
    // Test 1: Empty Firestore does not block FAR Worker fetching (AF-002 fix)
    // -------------------------------------------------------------------------
    @Test
    fun test1_EmptyFirestoreDoesNotBlockFarWorkerFetching() = runBlocking {
        val mockFarDto = FarSearchResultDto(
            assets = listOf(
                FarAssetItemDto(
                    assetKey = "FAR_KEY_1",
                    assetId = "00100",
                    rawRowJson = JSONObject(mapOf("Description" to "Office Desk", "Location" to "Floor 2")).toString()
                )
            ),
            nextCursor = null
        )

        val workerClient = object : WorkerApiClient() {
            override suspend fun searchFarAssets(
                clientId: String,
                fiscalYearId: String,
                query: String,
                cursor: String?,
                limit: Int,
                bearerTokenOverride: String?
            ): WorkerApiResult<FarSearchResultDto> {
                return WorkerApiResult.Success(mockFarDto)
            }
        }

        val repository = createRepository(workerClient)
        val initialFeed = repository.farDataFeedState.value
        assertEquals(FarDataFeedState.Idle, initialFeed)

        val flow = repository.observeFarAssets("client_test", "2026_27")
        val emitted = flow.first { it.isNotEmpty() }

        assertEquals(1, emitted.size)
        assertEquals("FAR_KEY_1", emitted[0].id)
        assertEquals("00100", emitted[0].assetId)
        assertEquals("Office Desk", emitted[0].assetDescription)
        assertEquals("Floor 2", emitted[0].location)

        val state = repository.farDataFeedState.value
        assertTrue(state is FarDataFeedState.Success)
        assertEquals(1, (state as FarDataFeedState.Success).count)
        assertFalse(state.isOfflineCache)
    }

    // -------------------------------------------------------------------------
    // Test 2: FAR records loaded from Worker D1 with parsed row json
    // -------------------------------------------------------------------------
    @Test
    fun test2_FarRecordsLoadedFromWorkerD1() = runBlocking {
        val rawJson = JSONObject().apply {
            put("Asset Description", "Dell PowerEdge Server R750")
            put("Location", "Server Room B")
            put("Department", "Information Technology")
            put("Asset Class", "IT Equipment")
            put("Cost Tk", 450000.0)
            put("FAR Qty", 2)
        }.toString()

        val mockFarDto = FarSearchResultDto(
            assets = listOf(
                FarAssetItemDto(
                    assetKey = "SRV_001",
                    assetId = "000543",
                    rawRowJson = rawJson,
                    importBatchId = "BATCH_IMPORT_99"
                )
            )
        )

        val workerClient = object : WorkerApiClient() {
            override suspend fun searchFarAssets(
                clientId: String,
                fiscalYearId: String,
                query: String,
                cursor: String?,
                limit: Int,
                bearerTokenOverride: String?
            ): WorkerApiResult<FarSearchResultDto> = WorkerApiResult.Success(mockFarDto)
        }

        val dao = InMemoryDynamicOfflineCacheDao()
        val repository = createRepository(workerClient, dao)

        val result = repository.searchFarAssets("client_corp", "2026_27", limit = 10)
        assertTrue(result is WorkerApiResult.Success)

        val cachedAssets = repository.cachedFarAssets.value
        assertEquals(1, cachedAssets.size)
        val asset = cachedAssets[0]
        assertEquals("SRV_001", asset.id)
        assertEquals("000543", asset.assetId)
        assertEquals("Dell PowerEdge Server R750", asset.assetDescription)
        assertEquals("Server Room B", asset.location)
        assertEquals("Information Technology", asset.department)
        assertEquals("IT Equipment", asset.assetClass)
        assertEquals(450000.0, asset.costTk, 0.001)
        assertEquals(2L, asset.farQty)

        // Verify Room offline cache was updated
        val roomBaselines = dao.getCachedFarBaselinesForCycle("client_corp", "2026_27")
        assertEquals(1, roomBaselines.size)
        assertEquals("000543", roomBaselines[0].assetId)
        assertEquals("Dell PowerEdge Server R750", roomBaselines[0].assetDescription)
    }

    // -------------------------------------------------------------------------
    // Test 3: Daily Sheets loaded from Worker D1
    // -------------------------------------------------------------------------
    @Test
    fun test3_DailySheetsLoadedFromWorkerD1() = runBlocking {
        val mockSheets = listOf(
            DailySheetItemDto(
                batchId = "BATCH_OCT_24",
                clientId = "client_alpha",
                fiscalYearId = "2026_27",
                auditorUid = "auditor_uid_99",
                auditorName = "Alice Auditor",
                batchDate = "2026-10-24",
                batchStatus = "APPROVED",
                totalItems = 28,
                createdAt = "2026-10-24T10:00:00Z",
                updatedAt = "2026-10-24T18:00:00Z",
                reviewedByUid = "admin_uid_1",
                reviewedAt = "2026-10-24T18:30:00Z"
            ),
            DailySheetItemDto(
                batchId = "BATCH_OCT_25",
                clientId = "client_alpha",
                fiscalYearId = "2026_27",
                auditorUid = "auditor_uid_99",
                auditorName = "Alice Auditor",
                batchDate = "2026-10-25",
                batchStatus = "OPEN",
                totalItems = 12,
                createdAt = "2026-10-25T09:00:00Z",
                updatedAt = "2026-10-25T17:00:00Z"
            )
        )

        val workerClient = object : WorkerApiClient() {
            override suspend fun listDailySheets(
                clientId: String,
                status: String?,
                mine: Boolean,
                bearerTokenOverride: String?
            ): WorkerApiResult<List<DailySheetItemDto>> = WorkerApiResult.Success(mockSheets)
        }

        val repository = createRepository(workerClient)
        val res = repository.fetchDailyBatchesFromWorker("client_alpha")
        assertTrue(res is WorkerApiResult.Success)

        val batches = (res as WorkerApiResult.Success).data
        assertEquals(2, batches.size)

        val approvedBatch = batches[0]
        assertEquals("BATCH_OCT_24", approvedBatch.batchId)
        assertEquals(SubmissionBatchStatus.PUBLISHED, approvedBatch.status)
        assertEquals("APPROVED", approvedBatch.approvalStatus)
        assertEquals(28L, approvedBatch.recordCount)
        assertEquals("admin_uid_1", approvedBatch.reviewedByUid)

        val openBatch = batches[1]
        assertEquals("BATCH_OCT_25", openBatch.batchId)
        assertEquals(SubmissionBatchStatus.SUBMITTED, openBatch.status)
        assertEquals("PENDING_REVIEW", openBatch.approvalStatus)
        assertEquals(12L, openBatch.recordCount)

        val state = repository.dailySheetsDataFeedState.value
        assertTrue(state is DailySheetsDataFeedState.Success)
        assertEquals(2, (state as DailySheetsDataFeedState.Success).count)
    }

    // -------------------------------------------------------------------------
    // Test 4: Pagination works without duplicate or missing records
    // -------------------------------------------------------------------------
    @Test
    fun test4_PaginationWorksWithoutDuplicateOrMissingRecords() = runBlocking {
        val page1 = FarSearchResultDto(
            assets = listOf(
                FarAssetItemDto(assetKey = "KEY_01", assetId = "001"),
                FarAssetItemDto(assetKey = "KEY_02", assetId = "002")
            ),
            nextCursor = "KEY_02"
        )
        val page2 = FarSearchResultDto(
            assets = listOf(
                FarAssetItemDto(assetKey = "KEY_03", assetId = "003"),
                FarAssetItemDto(assetKey = "KEY_04", assetId = "004")
            ),
            nextCursor = null
        )

        val workerClient = object : WorkerApiClient() {
            override suspend fun searchFarAssets(
                clientId: String,
                fiscalYearId: String,
                query: String,
                cursor: String?,
                limit: Int,
                bearerTokenOverride: String?
            ): WorkerApiResult<FarSearchResultDto> {
                return if (cursor == null) WorkerApiResult.Success(page1)
                else if (cursor == "KEY_02") WorkerApiResult.Success(page2)
                else WorkerApiResult.Success(FarSearchResultDto(emptyList()))
            }
        }

        val repository = createRepository(workerClient)

        // Fetch page 1
        val res1 = repository.searchFarAssets("client_pag", "2026_27", cursor = null, limit = 2)
        assertTrue(res1 is WorkerApiResult.Success)
        assertEquals(2, repository.cachedFarAssets.value.size)
        assertEquals(listOf("KEY_01", "KEY_02"), repository.cachedFarAssets.value.map { it.id })

        // Fetch page 2 using cursor
        val nextCursor = (res1 as WorkerApiResult.Success).data.nextCursor
        assertEquals("KEY_02", nextCursor)
        val res2 = repository.searchFarAssets("client_pag", "2026_27", cursor = nextCursor, limit = 2)
        assertTrue(res2 is WorkerApiResult.Success)

        // All 4 distinct records present without duplicates
        val allAssets = repository.cachedFarAssets.value
        assertEquals(4, allAssets.size)
        assertEquals(listOf("KEY_01", "KEY_02", "KEY_03", "KEY_04"), allAssets.map { it.id })
    }

    // -------------------------------------------------------------------------
    // Test 5: Leading-zero Asset IDs remain unchanged
    // -------------------------------------------------------------------------
    @Test
    fun test5_LeadingZeroAssetIdsRemainUnchanged() = runBlocking {
        val testAssetIds = listOf("004092", "000001", "07890", "000456789")

        val workerClient = object : WorkerApiClient() {
            override suspend fun searchFarAssets(
                clientId: String,
                fiscalYearId: String,
                query: String,
                cursor: String?,
                limit: Int,
                bearerTokenOverride: String?
            ): WorkerApiResult<FarSearchResultDto> {
                return WorkerApiResult.Success(
                    FarSearchResultDto(
                        assets = testAssetIds.map { id ->
                            FarAssetItemDto(
                                assetKey = "KEY_$id",
                                assetId = id,
                                rawRowJson = JSONObject(mapOf("Asset ID" to id)).toString()
                            )
                        }
                    )
                )
            }
        }

        val dao = InMemoryDynamicOfflineCacheDao()
        val repository = createRepository(workerClient, dao)

        repository.searchFarAssets("client_zeros", "2026_27")

        val memoryAssets = repository.cachedFarAssets.value
        assertEquals(4, memoryAssets.size)
        for (i in testAssetIds.indices) {
            assertEquals("Asset ID leading zeros must be preserved in FARAsset", testAssetIds[i], memoryAssets[i].assetId)
        }

        val cachedRoom = dao.getCachedFarBaselinesForCycle("client_zeros", "2026_27")
        assertEquals(4, cachedRoom.size)
        for (i in testAssetIds.indices) {
            assertEquals("Asset ID leading zeros must be preserved in Room cache", testAssetIds[i], cachedRoom[i].assetId)
        }
    }

    // -------------------------------------------------------------------------
    // Test 6: Client switching refreshes correct data without cross-tenant leak
    // -------------------------------------------------------------------------
    @Test
    fun test6_ClientSwitchingRefreshesCorrectData() = runBlocking {
        val clientAAssets = listOf(FarAssetItemDto(assetKey = "A1", assetId = "AST_A1"))
        val clientBAssets = listOf(FarAssetItemDto(assetKey = "B1", assetId = "AST_B1"))

        val clientASheets = listOf(
            DailySheetItemDto(
                batchId = "BATCH_A", clientId = "client_A", fiscalYearId = "2026_27",
                auditorUid = "aud_1", auditorName = "Auditor A", batchDate = "2026-11-01",
                batchStatus = "OPEN", totalItems = 5, createdAt = "", updatedAt = ""
            )
        )
        val clientBSheets = listOf(
            DailySheetItemDto(
                batchId = "BATCH_B", clientId = "client_B", fiscalYearId = "2026_27",
                auditorUid = "aud_2", auditorName = "Auditor B", batchDate = "2026-11-01",
                batchStatus = "APPROVED", totalItems = 10, createdAt = "", updatedAt = ""
            )
        )

        val workerClient = object : WorkerApiClient() {
            override suspend fun searchFarAssets(
                clientId: String, fiscalYearId: String, query: String, cursor: String?, limit: Int, bearerTokenOverride: String?
            ): WorkerApiResult<FarSearchResultDto> {
                return if (clientId == "client_A") WorkerApiResult.Success(FarSearchResultDto(clientAAssets))
                else WorkerApiResult.Success(FarSearchResultDto(clientBAssets))
            }

            override suspend fun listDailySheets(
                clientId: String, status: String?, mine: Boolean, bearerTokenOverride: String?
            ): WorkerApiResult<List<DailySheetItemDto>> {
                return if (clientId == "client_A") WorkerApiResult.Success(clientASheets)
                else WorkerApiResult.Success(clientBSheets)
            }
        }

        val repository = createRepository(workerClient)

        // Observe Client A
        val flowFarA = repository.observeFarAssets("client_A", "2026_27")
        val flowBatchesA = repository.observeDailyBatches("client_A", "2026_27")

        val farA = flowFarA.first { it.isNotEmpty() }
        assertEquals(1, farA.size)
        assertEquals("A1", farA[0].id)

        val batchesA = flowBatchesA.first { it.isNotEmpty() }
        assertEquals(1, batchesA.size)
        assertEquals("BATCH_A", batchesA[0].batchId)

        // Switch to Client B
        val flowFarB = repository.observeFarAssets("client_B", "2026_27")
        val flowBatchesB = repository.observeDailyBatches("client_B", "2026_27")

        val farB = flowFarB.first { it.isNotEmpty() }
        assertEquals(1, farB.size)
        assertEquals("B1", farB[0].id)

        val batchesB = flowBatchesB.first { it.isNotEmpty() }
        assertEquals(1, batchesB.size)
        assertEquals("BATCH_B", batchesB[0].batchId)

        // Re-verify Client A flow still does not leak Client B data
        val recheckA = repository.observeFarAssets("client_A", "2026_27").first()
        assertEquals(1, recheckA.size)
        assertEquals("A1", recheckA[0].id)
    }

    // -------------------------------------------------------------------------
    // Test 7: Fiscal Year switching refreshes correct data
    // -------------------------------------------------------------------------
    @Test
    fun test7_FiscalYearSwitchingRefreshesCorrectData() = runBlocking {
        val fy25Assets = listOf(FarAssetItemDto(assetKey = "FY25_1", assetId = "OLD_01"))
        val fy26Assets = listOf(FarAssetItemDto(assetKey = "FY26_1", assetId = "NEW_01"))

        val workerClient = object : WorkerApiClient() {
            override suspend fun searchFarAssets(
                clientId: String, fiscalYearId: String, query: String, cursor: String?, limit: Int, bearerTokenOverride: String?
            ): WorkerApiResult<FarSearchResultDto> {
                return if (fiscalYearId == "2025_26") WorkerApiResult.Success(FarSearchResultDto(fy25Assets))
                else WorkerApiResult.Success(FarSearchResultDto(fy26Assets))
            }
        }

        val repository = createRepository(workerClient)

        val flow25 = repository.observeFarAssets("client_x", "2025_26")
        val emitted25 = flow25.first { it.isNotEmpty() }
        assertEquals("FY25_1", emitted25[0].id)

        val flow26 = repository.observeFarAssets("client_x", "2026_27")
        val emitted26 = flow26.first { it.isNotEmpty() }
        assertEquals("FY26_1", emitted26[0].id)
    }

    // -------------------------------------------------------------------------
    // Test 8: Network failure is distinct from empty dataset
    // -------------------------------------------------------------------------
    @Test
    fun test8_NetworkFailureIsDistinctFromEmptyDataset() = runBlocking {
        val workerClient = object : WorkerApiClient() {
            override suspend fun searchFarAssets(
                clientId: String, fiscalYearId: String, query: String, cursor: String?, limit: Int, bearerTokenOverride: String?
            ): WorkerApiResult<FarSearchResultDto> {
                return WorkerApiResult.Error("Internal Server Error", statusCode = 500)
            }

            override suspend fun listDailySheets(
                clientId: String, status: String?, mine: Boolean, bearerTokenOverride: String?
            ): WorkerApiResult<List<DailySheetItemDto>> {
                return WorkerApiResult.Error("Network Timeout", statusCode = null, cause = IOException("Timeout"))
            }
        }

        val repository = createRepository(workerClient)

        // 1. FAR Asset network failure
        val farRes = repository.searchFarAssets("client_err", "2026_27")
        assertTrue(farRes is WorkerApiResult.Error)
        val farState = repository.farDataFeedState.value
        assertTrue("FAR state must be Error, not Empty", farState is FarDataFeedState.Error)
        assertEquals(500, (farState as FarDataFeedState.Error).statusCode)
        assertFalse(farState.hasOfflineCache)

        // 2. Daily Sheets network failure
        val sheetRes = repository.fetchDailyBatchesFromWorker("client_err")
        assertTrue(sheetRes is WorkerApiResult.Error)
        val sheetState = repository.dailySheetsDataFeedState.value
        assertTrue("Daily sheets state must be Error, not Empty", sheetState is DailySheetsDataFeedState.Error)
        assertTrue((sheetState as DailySheetsDataFeedState.Error).message.contains("Timeout"))
    }

    // -------------------------------------------------------------------------
    // Test 9: Offline mode uses Room cached records when network fails
    // -------------------------------------------------------------------------
    @Test
    fun test9_OfflineModeUsesRoomCachedRecordsWhenNetworkFails() = runBlocking {
        val dao = InMemoryDynamicOfflineCacheDao()
        // Pre-cache an offline baseline
        dao.upsertCachedFarBaseline(
            CachedFarBaselineEntity(
                cacheKey = "client_offline:2026_27:KEY_OFFLINE",
                clientId = "client_offline",
                auditCycleId = "2026_27",
                assetKey = "KEY_OFFLINE",
                assetId = "009876",
                assetDescription = "Offline Stored Generator",
                location = "Basement",
                department = "Maintenance",
                baselineDataJson = "{}",
                rawRowJson = JSONObject(mapOf("Description" to "Offline Stored Generator", "Cost" to 120000)).toString()
            )
        )

        // Worker fails with network error
        val failingWorker = object : WorkerApiClient() {
            override suspend fun searchFarAssets(
                clientId: String, fiscalYearId: String, query: String, cursor: String?, limit: Int, bearerTokenOverride: String?
            ): WorkerApiResult<FarSearchResultDto> {
                return WorkerApiResult.Error("No internet connection", cause = IOException("Offline"))
            }
        }

        val repository = createRepository(failingWorker, dao)

        repository.searchFarAssets("client_offline", "2026_27")

        // Assets should be loaded from Room offline cache!
        val cachedAssets = repository.cachedFarAssets.value
        assertEquals(1, cachedAssets.size)
        assertEquals("KEY_OFFLINE", cachedAssets[0].id)
        assertEquals("009876", cachedAssets[0].assetId)
        assertEquals("Offline Stored Generator", cachedAssets[0].assetDescription)

        val state = repository.farDataFeedState.value
        assertTrue("State must indicate success with offline flag", state is FarDataFeedState.Success)
        val successState = state as FarDataFeedState.Success
        assertEquals(1, successState.count)
        assertTrue("isOfflineCache must be true", successState.isOfflineCache)
    }

    // -------------------------------------------------------------------------
    // Test 10: Unauthorized client data cannot be retrieved (403 error)
    // -------------------------------------------------------------------------
    @Test
    fun test10_UnauthorizedClientDataCannotBeRetrieved_403Error() = runBlocking {
        val forbiddenWorker = object : WorkerApiClient() {
            override suspend fun searchFarAssets(
                clientId: String, fiscalYearId: String, query: String, cursor: String?, limit: Int, bearerTokenOverride: String?
            ): WorkerApiResult<FarSearchResultDto> {
                return WorkerApiResult.Error("Forbidden: User is not assigned to client_forbidden", statusCode = 403)
            }

            override suspend fun listDailySheets(
                clientId: String, status: String?, mine: Boolean, bearerTokenOverride: String?
            ): WorkerApiResult<List<DailySheetItemDto>> {
                return WorkerApiResult.Error("Forbidden: Access denied", statusCode = 403)
            }
        }

        val repository = createRepository(forbiddenWorker)

        val farRes = repository.searchFarAssets("client_forbidden", "2026_27")
        assertTrue(farRes is WorkerApiResult.Error)
        assertEquals(403, (farRes as WorkerApiResult.Error).statusCode)

        val farState = repository.farDataFeedState.value
        assertTrue(farState is FarDataFeedState.Error)
        assertEquals(403, (farState as FarDataFeedState.Error).statusCode)

        val sheetRes = repository.fetchDailyBatchesFromWorker("client_forbidden")
        assertTrue(sheetRes is WorkerApiResult.Error)
        assertEquals(403, (sheetRes as WorkerApiResult.Error).statusCode)

        val sheetState = repository.dailySheetsDataFeedState.value
        assertTrue(sheetState is DailySheetsDataFeedState.Error)
        assertEquals(403, (sheetState as DailySheetsDataFeedState.Error).statusCode)
    }

    // -------------------------------------------------------------------------
    // Test 11: Get Daily Sheet Detail returns batch, submission and records
    // -------------------------------------------------------------------------
    @Test
    fun test11_GetDailySheetDetailReturnsBatchSubmissionAndRecords() = runBlocking {
        val mockDetail = DailySheetDetailDto(
            batch = DailySheetItemDto(
                batchId = "BATCH_DETAIL_1",
                clientId = "client_alpha",
                fiscalYearId = "2026_27",
                auditorUid = "aud_01",
                auditorName = "John Doe",
                batchDate = "2026-11-02",
                batchStatus = "SUBMITTED",
                totalItems = 2,
                createdAt = "2026-11-02T10:00:00Z",
                updatedAt = "2026-11-02T18:00:00Z"
            ),
            records = listOf(
                DailySheetRecordDto(
                    recordId = "REC_01",
                    clientId = "client_alpha",
                    fiscalYearId = "2026_27",
                    assetKey = "AST_KEY_01",
                    schemaVersionId = "SCHEMA_V1",
                    auditorUid = "aud_01",
                    auditorName = "John Doe",
                    recordStatus = "SUBMITTED",
                    batchId = "BATCH_DETAIL_1",
                    countDate = "2026-11-02",
                    valuesJson = JSONObject(mapOf("Condition" to "Good", "Found?" to "Yes")).toString(),
                    photoCount = 2,
                    hasPhotoEvidence = true
                ),
                DailySheetRecordDto(
                    recordId = "REC_02",
                    clientId = "client_alpha",
                    fiscalYearId = "2026_27",
                    assetKey = "AST_KEY_02",
                    schemaVersionId = "SCHEMA_V1",
                    auditorUid = "aud_01",
                    auditorName = "John Doe",
                    recordStatus = "SUBMITTED",
                    batchId = "BATCH_DETAIL_1",
                    countDate = "2026-11-02",
                    valuesJson = JSONObject(mapOf("Condition" to "Fair", "Found?" to "Yes")).toString(),
                    photoCount = 0,
                    hasPhotoEvidence = false
                )
            )
        )

        val workerClient = object : WorkerApiClient() {
            override suspend fun getDailySheetDetail(
                clientId: String,
                batchId: String,
                bearerTokenOverride: String?
            ): WorkerApiResult<DailySheetDetailDto> {
                return WorkerApiResult.Success(mockDetail)
            }
        }

        val repository = createRepository(workerClient)
        val res = repository.getDailySheetDetail("client_alpha", "BATCH_DETAIL_1")

        assertTrue(res is WorkerApiResult.Success)
        val detail = (res as WorkerApiResult.Success).data
        assertEquals("BATCH_DETAIL_1", detail.batch.batchId)
        assertEquals(2, detail.records.size)
        assertEquals("AST_KEY_01", detail.records[0].assetKey)
        assertTrue(detail.records[0].hasPhotoEvidence)
        assertEquals(2, detail.records[0].photoCount)
        assertEquals("AST_KEY_02", detail.records[1].assetKey)
        assertFalse(detail.records[1].hasPhotoEvidence)
    }
}
