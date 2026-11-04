package com.example

import com.example.data.model.FoundNotInFarRecord
import com.example.data.model.SyncState
import com.example.data.network.FoundNotInFarItemDto
import com.example.data.network.SubmitFoundNotInFarRequestDto
import com.example.data.network.WorkerApiClient
import com.example.data.network.WorkerApiResult
import com.example.data.offline.OfflinePayloadSerializer
import com.example.data.offline.OfflinePhotoQueueEntity
import com.example.data.offline.OfflineQueuedRecordEntity
import com.example.data.offline.OfflineSyncDao
import com.example.data.repository.AuditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * Post-Remediation Workflow Tests covering:
 * - Stage 5.2 / 5.3: Unlisted Asset ("Found Not In FAR") Worker D1 pipeline and offline Room queue fallback.
 * - Stage 5.2: Leading-zero preservation and JSON deserialization fidelity.
 * - Stage 5.1-5.7: Invariant verification of all 6 protected modules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PostRemediationWorkflowTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createMockOkHttpClient(handler: (Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                handler(chain.request())
            })
            .build()
    }

    private fun jsonResponse(request: Request, code: Int, json: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(json.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    private fun createMockOfflineSyncDao(): OfflineSyncDao {
        return object : OfflineSyncDao {
            val queuedRecords = mutableMapOf<String, OfflineQueuedRecordEntity>()

            override fun observeAllQueuedRecords(): Flow<List<OfflineQueuedRecordEntity>> =
                flowOf(queuedRecords.values.toList())

            override fun observeQueuedRecordsForUser(uid: String): Flow<List<OfflineQueuedRecordEntity>> =
                flowOf(queuedRecords.values.filter { it.userId == uid })

            override suspend fun getPendingOrFailedRecords(): List<OfflineQueuedRecordEntity> =
                queuedRecords.values.filter { it.syncStatus in listOf("Pending Sync", "Failed") }

            override suspend fun getPendingOrFailedRecordsForUser(uid: String): List<OfflineQueuedRecordEntity> =
                queuedRecords.values.filter { it.userId == uid && it.syncStatus in listOf("Pending Sync", "Failed") }

            override suspend fun getQueuedRecordByKey(key: String): OfflineQueuedRecordEntity? =
                queuedRecords[key]

            override suspend fun upsertQueuedRecord(entity: OfflineQueuedRecordEntity) {
                queuedRecords[entity.recordKey] = entity
            }

            override suspend fun updateRecordSyncStatus(
                recordKey: String,
                status: String,
                error: String,
                retries: Int,
                syncedAt: Long
            ) {
                queuedRecords[recordKey]?.let {
                    queuedRecords[recordKey] = it.copy(syncStatus = status, errorMessage = error, retryCount = retries, lastSyncedAtMillis = syncedAt)
                }
            }

            override suspend fun deleteQueuedRecord(recordKey: String) {
                queuedRecords.remove(recordKey)
            }

            override suspend fun deleteQueuedRecordsForClient(clientId: String) {
                queuedRecords.entries.removeIf { it.value.clientId == clientId }
            }

            override suspend fun deleteQueuedRecordsForUser(uid: String) {
                queuedRecords.entries.removeIf { it.value.userId == uid }
            }

            override fun observeAllPhotoQueue(): Flow<List<OfflinePhotoQueueEntity>> =
                flowOf(emptyList())

            override fun observePhotoQueueForUser(uid: String): Flow<List<OfflinePhotoQueueEntity>> =
                flowOf(emptyList())

            override suspend fun getPhotosForRecord(recordKey: String): List<OfflinePhotoQueueEntity> = emptyList()
            override suspend fun getPhotoById(photoId: String): OfflinePhotoQueueEntity? = null
            override suspend fun getPendingOrFailedPhotos(): List<OfflinePhotoQueueEntity> = emptyList()
            override suspend fun getPendingOrFailedPhotosForUser(uid: String): List<OfflinePhotoQueueEntity> = emptyList()
            override suspend fun getPhotosForClient(clientId: String): List<OfflinePhotoQueueEntity> = emptyList()
            override suspend fun getPhotosForUser(uid: String): List<OfflinePhotoQueueEntity> = emptyList()
            override suspend fun upsertPhotoQueue(entity: OfflinePhotoQueueEntity) {}
            override suspend fun updatePhotoUploadStatus(
                photoId: String, status: String, driveFileId: String, webLink: String, error: String, retries: Int, syncedAt: Long,
                dateFolderId: String, dateFolderName: String, finalName: String, ext: String, mime: String, seq: Int, uploadedIso: String
            ) {}
            override suspend fun claimPhotoLease(photoId: String, ownerToken: String, newStatus: String, expiresAtMillis: Long, nowMillis: Long): Int = 1
            override suspend fun renewPhotoLease(photoId: String, ownerToken: String, newExpiresAtMillis: Long): Int = 1
            override suspend fun updatePhotoIntermediateStatusWithLease(
                photoId: String, ownerToken: String, status: String, driveFileId: String, webLink: String, error: String, retries: Int, syncedAt: Long,
                dateFolderId: String, dateFolderName: String, finalName: String, ext: String, mime: String, seq: Int, uploadedIso: String, newExpiresAtMillis: Long
            ): Int = 1
            override suspend fun updatePhotoStatusWithLease(
                photoId: String, ownerToken: String, status: String, driveFileId: String, webLink: String, error: String, retries: Int, syncedAt: Long,
                dateFolderId: String, dateFolderName: String, finalName: String, ext: String, mime: String, seq: Int, uploadedIso: String
            ): Int = 1
            override suspend fun recoverExpiredPhotoLease(photoId: String, newStatus: String, error: String, nowMillis: Long): Int = 0
            override suspend fun deleteQueuedPhotosForClient(clientId: String) {}
            override suspend fun deleteQueuedPhotosForUser(uid: String) {}
        }
    }

    @Test
    fun testWorkerApiClient_submitFoundNotInFar_postsToEndpoint(): Unit = runBlocking {
        var recordedMethod = ""
        var recordedPath = ""
        var recordedBody = ""

        val okHttpClient = createMockOkHttpClient { req ->
            recordedMethod = req.method
            recordedPath = req.url.encodedPath
            val buffer = okio.Buffer()
            req.body?.writeTo(buffer)
            recordedBody = buffer.readUtf8()
            jsonResponse(
                req,
                201,
                """
                {
                    "message": "Unlisted asset recorded successfully",
                    "record": {
                        "temp_id": "UNLISTED_TEST_01",
                        "client_id": "client_alpha",
                        "fiscal_year_id": "2026_27",
                        "description": "Standing Desk 120cm",
                        "raw_values_json": "{\"tagNumberAcn\":\"000987\"}",
                        "auditor_uid": "auditor_1",
                        "auditor_name": "Auditor One",
                        "count_date": "2026-11-03",
                        "created_at": "2026-11-03T10:00:00Z"
                    }
                }
                """.trimIndent()
            )
        }

        val client = WorkerApiClient(baseUrl = "http://192.168.1.100:8787", client = okHttpClient)
        val payload = SubmitFoundNotInFarRequestDto(
            tempId = "UNLISTED_TEST_01",
            fiscalYearId = "2026_27",
            description = "Standing Desk 120cm",
            rawValuesJson = "{\"tagNumberAcn\":\"000987\"}",
            countDate = "2026-11-03"
        )

        val result = client.submitFoundNotInFar("client_alpha", payload)

        assertTrue(result is WorkerApiResult.Success)
        val data = (result as WorkerApiResult.Success).data
        assertEquals("POST", recordedMethod)
        assertEquals("/api/v1/clients/client_alpha/found-not-in-far", recordedPath)
        assertEquals("UNLISTED_TEST_01", data.tempId)
        assertEquals("client_alpha", data.clientId)
        assertEquals("Standing Desk 120cm", data.description)
    }

    @Test
    fun testWorkerApiClient_listFoundNotInFar_parsesList(): Unit = runBlocking {
        var recordedPath = ""
        var recordedQuery = ""

        val okHttpClient = createMockOkHttpClient { req ->
            recordedPath = req.url.encodedPath
            recordedQuery = req.url.query.orEmpty()
            jsonResponse(
                req,
                200,
                """
                {
                    "records": [
                        {
                            "temp_id": "UNLISTED_1",
                            "client_id": "client_alpha",
                            "fiscal_year_id": "2026_27",
                            "description": "Forklift A",
                            "raw_values_json": "{}",
                            "auditor_uid": "auditor_1",
                            "auditor_name": "Auditor",
                            "count_date": "2026-11-01"
                        },
                        {
                            "temp_id": "UNLISTED_2",
                            "client_id": "client_alpha",
                            "fiscal_year_id": "2026_27",
                            "description": "Pallet Jack B",
                            "raw_values_json": "{}",
                            "auditor_uid": "auditor_2",
                            "auditor_name": "Auditor",
                            "count_date": "2026-11-02"
                        }
                    ]
                }
                """.trimIndent()
            )
        }

        val client = WorkerApiClient(baseUrl = "http://192.168.1.100:8787", client = okHttpClient)
        val result = client.listFoundNotInFar("client_alpha", "2026_27")

        assertTrue(result is WorkerApiResult.Success)
        val list = (result as WorkerApiResult.Success).data
        assertEquals("/api/v1/clients/client_alpha/found-not-in-far", recordedPath)
        assertTrue(recordedQuery.contains("fiscalYearId=2026_27"))
        assertEquals(2, list.size)
        assertEquals("UNLISTED_1", list[0].tempId)
        assertEquals("Forklift A", list[0].description)
    }

    @Test
    fun testAuditRepository_saveFoundNotInFarRecord_authoritativeWorkerD1(): Unit = runBlocking {
        var workerCallCount = 0

        val okHttpClient = createMockOkHttpClient { req ->
            workerCallCount++
            jsonResponse(
                req,
                201,
                """
                {
                    "message": "Unlisted asset recorded successfully",
                    "record": {
                        "temp_id": "UNLISTED_ONLINE",
                        "client_id": "client_alpha",
                        "fiscal_year_id": "2026_27",
                        "description": "Welding Machine",
                        "raw_values_json": "{}",
                        "auditor_uid": "auditor_1",
                        "auditor_name": "Auditor",
                        "count_date": "2026-11-03"
                    }
                }
                """.trimIndent()
            )
        }

        val workerClient = WorkerApiClient(baseUrl = "http://192.168.1.100:8787", client = okHttpClient)
        val mockDao = createMockOfflineSyncDao()

        val repo = AuditRepository(
            db = null,
            offlineSyncDao = mockDao,
            workerApiClient = workerClient
        )

        val unlistedRecord = FoundNotInFarRecord(
            id = "UNLISTED_ONLINE",
            tempId = "UNLISTED_ONLINE",
            clientId = "client_alpha",
            fiscalYearId = "2026_27",
            description = "Welding Machine",
            countedBy = "Auditor",
            countedByUserId = "auditor_1",
            countDate = "2026-11-03"
        )

        val res = repo.saveFoundNotInFarRecord(unlistedRecord)

        assertTrue(res.isSuccess)
        assertEquals(1, workerCallCount)
        // Verify offline queue is empty (not queued because online submission succeeded)
        assertEquals(0, mockDao.getPendingOrFailedRecords().size)
        // Verify cached in-memory
        val cached = repo.cachedFoundNotInFar.value
        assertTrue(cached.any { it.tempId == "UNLISTED_ONLINE" })
    }

    @Test
    fun testAuditRepository_saveFoundNotInFarRecord_offlineRoomFallback(): Unit = runBlocking {
        val okHttpClient = createMockOkHttpClient { req ->
            jsonResponse(req, 500, """{"error": "Database error"}""")
        }

        val workerClient = WorkerApiClient(baseUrl = "http://192.168.1.100:8787", client = okHttpClient)
        val mockDao = createMockOfflineSyncDao()

        val repo = AuditRepository(
            db = null,
            offlineSyncDao = mockDao,
            workerApiClient = workerClient
        )

        val unlistedRecord = FoundNotInFarRecord(
            id = "UNLISTED_OFFLINE_01",
            tempId = "UNLISTED_OFFLINE_01",
            clientId = "client_alpha",
            fiscalYearId = "2026_27",
            assetIdIfKnown = "000456", // Leading zeros!
            description = "Hydraulic Press",
            department = "Fabrication",
            countedBy = "Auditor Field",
            countedByUserId = "auditor_99",
            countDate = "2026-11-03"
        )

        val res = repo.saveFoundNotInFarRecord(unlistedRecord)

        assertTrue(res.isSuccess)
        // Verify queued in Room database as UNREGISTERED_OBS with PENDING_SYNC status
        val pending = mockDao.getPendingOrFailedRecords()
        assertEquals(1, pending.size)
        val queued = pending[0]
        assertEquals("UNLISTED_OFFLINE_01", queued.recordKey)
        assertEquals("UNREGISTERED_OBS", queued.recordType)
        assertEquals("000456", queued.assetId) // Preserves leading zeros
        assertEquals(SyncState.PENDING_SYNC, queued.syncStatus)

        // Verify deserialization fidelity from the stored payload JSON
        val deserialized = OfflinePayloadSerializer.deserializeFoundNotInFar(queued.payloadJson)
        assertEquals("Hydraulic Press", deserialized.description)
        assertEquals("Fabrication", deserialized.department)
        assertEquals("000456", deserialized.assetIdIfKnown)
    }

    @Test
    fun testAuditRepository_observeFoundNotInFar_filtersByClientAndCycle(): Unit = runBlocking {
        val okHttpClient = createMockOkHttpClient { req ->
            jsonResponse(
                req,
                200,
                """
                {
                    "records": [
                        {
                            "temp_id": "REC_A1",
                            "client_id": "client_alpha",
                            "fiscal_year_id": "2026_27",
                            "description": "Desk 1",
                            "raw_values_json": "{\"assetIdIfKnown\":\"00123\"}",
                            "auditor_uid": "auditor_1",
                            "auditor_name": "Auditor",
                            "count_date": "2026-11-03"
                        }
                    ]
                }
                """.trimIndent()
            )
        }

        val workerClient = WorkerApiClient(baseUrl = "http://192.168.1.100:8787", client = okHttpClient)
        val repo = AuditRepository(db = null, workerApiClient = workerClient)

        // Pre-fetch
        val fetchRes = repo.fetchFoundNotInFarFromWorker("client_alpha", "2026_27")
        assertTrue(fetchRes.isSuccess)

        val list = repo.observeFoundNotInFar("client_alpha", "2026_27").first()
        assertEquals(1, list.size)
        assertEquals("REC_A1", list[0].tempId)
        assertEquals("00123", list[0].assetIdIfKnown) // Preserved!
    }

    @Test
    fun testProtectedModules_sha256BaselineIntegrity() {
        val expectedHashes = mapOf(
            "AuditCalculationEngine.kt" to "970bcd80e3bc1eac737dad6fbece0ce86cbda40b3d9df018f81be3c14d876d1c",
            "ExcelWorkbookEngine.kt" to "dee96c0e5c994dcb09e1684025303a9cbec0e9f92e4bbc112aa643aea4fb3c6a",
            "AuditModels.kt" to "a9f7517c52c5865cf4b291bcfbf22788faf4ee4c46b450f1faf2864da48c1c33",
            "OfflineSyncDatabase.kt" to "a20bfc2562d8d0f48af2b8727abdda12b2d9825c039c0d19603afaddc2c62cd0",
            "AdminReviewQueueTab.kt" to "bac96ed7916ba22bfb94f9920fdce6db983f6e6164a81ceed9ca44f89cf77d9e",
            "ClientSelectionScreen.kt" to "b52f00b0037496775cb2996134e751a6ce1de7a6452c589f72fa882d52ecd50b"
        )

        val root = File("src/main/java")
        val foundFiles = mutableMapOf<String, File>()
        root.walkTopDown().filter { it.isFile && expectedHashes.containsKey(it.name) }.forEach {
            foundFiles[it.name] = it
        }

        assertEquals("All 6 protected files must exist in source tree", 6, foundFiles.size)

        for ((fileName, expectedHash) in expectedHashes) {
            val file = foundFiles[fileName]
            assertNotNull("Protected file '$fileName' must exist", file)

            val bytes = file!!.readBytes()
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(bytes).joinToString("") { "%02x".format(it) }

            assertEquals(
                "Protected module '$fileName' has been modified! SHA-256 mismatch.",
                expectedHash.lowercase(),
                hash.lowercase()
            )
        }
    }
}
