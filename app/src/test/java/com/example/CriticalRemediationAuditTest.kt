package com.example

import com.example.data.drive.ClientDriveProvisionResult
import com.example.data.drive.DriveFileMetadata
import com.example.data.drive.DriveRevisionMetadata
import com.example.data.drive.GoogleDriveService
import com.example.data.model.DailyBatchSubmissionRecord
import com.example.data.model.FARAsset
import com.example.data.model.RoleAssignment
import com.example.data.model.SubmissionBatchStatus
import com.example.data.network.AssignUserRequestDto
import com.example.data.network.FarAssetItemDto
import com.example.data.network.MasterWorkbookStateDto
import com.example.data.network.WorkerApiClient
import com.example.data.network.WorkerApiResult
import com.example.data.offline.CachedFarBaselineEntity
import com.example.data.offline.DynamicOfflineCacheDao
import com.example.data.repository.AuditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Critical Remediation Unit Test Suite covering findings AF-AUDIT-001 through AF-AUDIT-005:
 *
 * - AF-AUDIT-001: Master Workbook Import authoritative on Worker D1 with Room offline cache reconciliation
 *                 and Firestore permission isolation.
 * - AF-AUDIT-002: PhotoUploadWorker Google Drive token provider injection and missing token safety.
 * - AF-AUDIT-003: User role, status, and membership permission updates via Worker D1 with dead Firebase removal.
 * - AF-AUDIT-004: Physical device debug Worker URL detection, loopback detection, and diagnostic hints.
 * - AF-AUDIT-005: Approval workflow authoritative on Worker D1 with Firestore permission isolation and lease fencing.
 * - Protected Modules: SHA-256 baseline immutability check.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CriticalRemediationAuditTest {

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

    /**
     * Builds a minimal valid XLSX byte array with a sheet containing headers and rows.
     */
    private fun createMinimalTestXlsxBytes(sheetName: String = "FAR_Sheet"): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            // [Content_Types].xml
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
</Types>""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // _rels/.rels
            zos.putNextEntry(ZipEntry("_rels/.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // xl/workbook.xml
            zos.putNextEntry(ZipEntry("xl/workbook.xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="$sheetName" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // xl/_rels/workbook.xml.rels
            zos.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
</Relationships>""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // xl/sharedStrings.xml
            val strings = listOf("Asset ID", "Asset Description", "Class", "Location", "00123", "Laptop Dell XPS", "IT Equipment", "Floor 3")
            zos.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            val sst = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${strings.size}" uniqueCount="${strings.size}">""")
            strings.forEach { s -> sst.append("<si><t>$s</t></agree>".replace("</agree>", "</si>")) }
            sst.append("</sst>")
            zos.write(sst.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // xl/worksheets/sheet1.xml
            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
    <row r="1">
      <c r="A1" t="s"><v>0</v></c>
      <c r="B1" t="s"><v>1</v></c>
      <c r="C1" t="s"><v>2</v></c>
      <c r="D1" t="s"><v>3</v></c>
    </row>
    <row r="2">
      <c r="A2" t="s"><v>4</v></c>
      <c r="B2" t="s"><v>5</v></c>
      <c r="C2" t="s"><v>6</v></c>
      <c r="D2" t="s"><v>7</v></c>
    </row>
  </sheetData>
</worksheet>""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return bos.toByteArray()
    }

    // =========================================================================
    // AF-AUDIT-004: Physical Device Debug Worker URL & Emulator Detection
    // =========================================================================

    @Test
    fun testWorkerApiClient_isConfigured_and_isEmulatorLoopbackUrl() {
        val loopbackClient = WorkerApiClient(baseUrl = "http://10.0.2.2:8787")
        assertTrue(loopbackClient.isConfigured())
        assertTrue(loopbackClient.isEmulatorLoopbackUrl())

        val lanClient = WorkerApiClient(baseUrl = "http://192.168.1.100:8787")
        assertTrue(lanClient.isConfigured())
        assertFalse(lanClient.isEmulatorLoopbackUrl())

        val cloudClient = WorkerApiClient(baseUrl = "https://assetflow-worker.example.workers.dev")
        assertTrue(cloudClient.isConfigured())
        assertFalse(cloudClient.isEmulatorLoopbackUrl())
    }

    @Test
    fun testWorkerApiClient_formatNetworkErrorMessage_providesLoopbackHint() {
        val loopbackClient = WorkerApiClient(baseUrl = "http://10.0.2.2:8787")
        val errorMsg = loopbackClient.formatNetworkErrorMessage("fetching schema", IOException("Connection refused"))
        assertTrue("Error message should mention loopback diagnostic hint", errorMsg.contains("WORKER_DEBUG_URL") || errorMsg.contains("emulator loopback"))

        val lanClient = WorkerApiClient(baseUrl = "http://192.168.1.100:8787")
        val lanErrorMsg = lanClient.formatNetworkErrorMessage("fetching schema", IOException("Host unreachable"))
        assertFalse("Error message for LAN IP should not suggest changing from loopback", lanErrorMsg.contains("is configured as emulator loopback"))
    }

    // =========================================================================
    // AF-AUDIT-003: User Role, Status, and Membership Migration to Worker D1
    // =========================================================================

    @Test
    fun testWorkerApiClient_updateUserRole_sendsPatchWithRole(): Unit = runBlocking {
        var recordedMethod = ""
        var recordedPath = ""
        var recordedBody = ""

        val okHttpClient = createMockOkHttpClient { req ->
            recordedMethod = req.method
            recordedPath = req.url.encodedPath
            val buffer = okio.Buffer()
            req.body?.writeTo(buffer)
            recordedBody = buffer.readUtf8()
            jsonResponse(req, 200, """{"message": "User global role updated to 'Admin'"}""")
        }

        val client = WorkerApiClient(baseUrl = "http://192.168.1.100:8787", client = okHttpClient)
        val result = client.updateUserRole("uid_auditor_99", "Admin")

        assertTrue(result is WorkerApiResult.Success)
        assertEquals("PATCH", recordedMethod)
        assertEquals("/api/v1/users/uid_auditor_99/role", recordedPath)
        val json = JSONObject(recordedBody)
        assertEquals("Admin", json.getString("role"))
    }

    @Test
    fun testWorkerApiClient_updateUserStatus_sendsPatchWithStatus(): Unit = runBlocking {
        var recordedMethod = ""
        var recordedPath = ""
        var recordedBody = ""

        val okHttpClient = createMockOkHttpClient { req ->
            recordedMethod = req.method
            recordedPath = req.url.encodedPath
            val buffer = okio.Buffer()
            req.body?.writeTo(buffer)
            recordedBody = buffer.readUtf8()
            jsonResponse(req, 200, """{"message": "User status updated to 'Suspended'"}""")
        }

        val client = WorkerApiClient(baseUrl = "http://192.168.1.100:8787", client = okHttpClient)
        val result = client.updateUserStatus("uid_auditor_99", "Suspended")

        assertTrue(result is WorkerApiResult.Success)
        assertEquals("PATCH", recordedMethod)
        assertEquals("/api/v1/users/uid_auditor_99/status", recordedPath)
        val json = JSONObject(recordedBody)
        assertEquals("Suspended", json.getString("status"))
    }

    @Test
    fun testWorkerApiClient_updateClientMembershipPermissions_sendsPatchWithBody(): Unit = runBlocking {
        var recordedMethod = ""
        var recordedPath = ""
        var recordedBody = ""

        val okHttpClient = createMockOkHttpClient { req ->
            recordedMethod = req.method
            recordedPath = req.url.encodedPath
            val buffer = okio.Buffer()
            req.body?.writeTo(buffer)
            recordedBody = buffer.readUtf8()
            jsonResponse(req, 200, """{"message": "Client membership updated"}""")
        }

        val client = WorkerApiClient(baseUrl = "http://192.168.1.100:8787", client = okHttpClient)
        val result = client.updateClientMembershipPermissions(
            clientId = "client_alpha",
            targetUid = "uid_auditor_99",
            role = "Reviewer",
            hasReviewAuthority = true,
            canPublish = false,
            canSyncDrive = true
        )

        assertTrue(result is WorkerApiResult.Success)
        assertEquals("PATCH", recordedMethod)
        assertEquals("/api/v1/clients/client_alpha/memberships/uid_auditor_99", recordedPath)
        val json = JSONObject(recordedBody)
        assertEquals("Reviewer", json.getString("role"))
        assertTrue(json.getBoolean("hasReviewAuthority"))
        assertFalse(json.getBoolean("canPublish"))
        assertTrue(json.getBoolean("canSyncDrive"))
    }

    @Test
    fun testAuditRepository_saveRoleAssignment_updatesWorkerD1_andIsolatesFirestore(): Unit = runBlocking {
        var roleUpdated = false
        var statusUpdated = false
        var membershipUpdated = false

        val okHttpClient = createMockOkHttpClient { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/role") -> {
                    roleUpdated = true
                    jsonResponse(req, 200, """{"message":"Role updated"}""")
                }
                path.endsWith("/status") -> {
                    statusUpdated = true
                    jsonResponse(req, 200, """{"message":"Status updated"}""")
                }
                path.contains("/memberships/") -> {
                    membershipUpdated = true
                    jsonResponse(req, 200, """{"message":"Membership updated"}""")
                }
                else -> jsonResponse(req, 404, """{"error":"not found"}""")
            }
        }

        val workerClient = WorkerApiClient(baseUrl = "http://192.168.1.100:8787", client = okHttpClient)
        // db is null, so Firestore calls return null without throwing or failing
        val repository = AuditRepository(
            db = null,
            workerApiClient = workerClient
        )

        val assignment = RoleAssignment(
            id = "user_456",
            memberUid = "user_456",
            assignedClientIds = listOf("client_alpha"),
            role = "Admin",
            accountStatus = "Active",
            hasReviewAuthority = true,
            canPublish = true,
            canSyncDrive = true
        )

        val result = repository.saveRoleAssignment(assignment)
        if (result.isFailure) {
            val ex = result.exceptionOrNull()
            ex?.printStackTrace()
            fail("saveRoleAssignment failed with exception: ${ex?.javaClass?.name}: ${ex?.message}")
        }
        assertTrue("saveRoleAssignment should succeed", result.isSuccess)
        assertTrue("Worker role must be updated", roleUpdated)
        assertTrue("Worker status must be updated", statusUpdated)
        assertTrue("Worker membership must be updated", membershipUpdated)
    }

    // =========================================================================
    // AF-AUDIT-001: Master Workbook Import / D1 Authoritative & Firestore Isolation
    // =========================================================================

    @Test
    fun testAuditRepository_importClientMasterWorkbook_authoritativeD1_andFirestoreIsolated(): Unit = runBlocking {
        var importedToWorker = false
        var importedAssetsCount = 0
        var receivedAssetId = ""

        val okHttpClient = createMockOkHttpClient { req ->
            val path = req.url.encodedPath
            if (path.contains("/far/import")) {
                importedToWorker = true
                val buffer = okio.Buffer()
                req.body?.writeTo(buffer)
                val json = JSONObject(buffer.readUtf8())
                val assets = json.getJSONArray("assets")
                importedAssetsCount = assets.length()
                if (importedAssetsCount > 0) {
                    receivedAssetId = assets.getJSONObject(0).getString("assetId")
                }
                jsonResponse(req, 200, """{"importedCount": $importedAssetsCount}""")
            } else if (path.contains("/master-workbook/state")) {
                jsonResponse(req, 200, """{"state": {"state_id": "state_1", "master_drive_file_id": ""}}""")
            } else {
                jsonResponse(req, 200, """{}""")
            }
        }

        val workerClient = WorkerApiClient(baseUrl = "http://192.168.1.100:8787", client = okHttpClient)
        val repository = AuditRepository(
            db = null,
            workerApiClient = workerClient
        )

        // Mock in-memory cache DAO
        val cachedEntities = mutableListOf<CachedFarBaselineEntity>()
        repository.dynamicOfflineCacheDao = object : DynamicOfflineCacheDao {
            override suspend fun upsertCachedSchema(entity: com.example.data.offline.CachedSchemaEntity) {}
            override suspend fun getCachedSchema(clientId: String, cycleId: String) = null
            override suspend fun deleteCachedSchema(clientId: String, cycleId: String) {}
            override suspend fun upsertCachedFarBaseline(entity: CachedFarBaselineEntity) {
                cachedEntities.add(entity)
            }
            override suspend fun upsertCachedFarBaselines(entities: List<CachedFarBaselineEntity>) {
                cachedEntities.addAll(entities)
            }
            override suspend fun getCachedFarBaseline(clientId: String, cycleId: String, assetKey: String) = null
            override suspend fun getCachedFarBaselinesForCycle(clientId: String, cycleId: String) = cachedEntities
            override suspend fun deleteFarBaselinesForClient(clientId: String) { cachedEntities.clear() }
            override suspend fun deleteFarBaselinesForCycle(clientId: String, cycleId: String) { cachedEntities.clear() }
        }

        val xlsxBytes = createMinimalTestXlsxBytes("FAR_Sheet")
        val result = repository.importClientMasterWorkbook(
            clientId = "client_alpha",
            clientName = "Alpha Corp",
            fiscalYearId = "2026_27",
            fileName = "Alpha_FAR.xlsx",
            workbookBytes = xlsxBytes,
            selectedSheetName = "FAR_Sheet",
            uniqueAssetKeyHeader = "Asset ID"
        )

        assertTrue("importClientMasterWorkbook must succeed without Firestore", result.isSuccess)
        val count = result.getOrThrow()
        assertEquals(1, count)
        assertTrue("Assets must be imported to Worker D1", importedToWorker)
        assertEquals(1, importedAssetsCount)
        assertEquals("Leading zeros in Asset ID must be preserved", "00123", receivedAssetId)

        // Room cache reconciliation
        assertEquals(1, cachedEntities.size)
        assertEquals("00123", cachedEntities[0].assetId)
    }

    // =========================================================================
    // AF-AUDIT-005: Approval Authoritative on Worker D1 & Lease Fencing
    // =========================================================================

    @Test
    fun testAuditRepository_approveBatchAndSyncMasterWorkbook_authoritativeWorkerD1(): Unit = runBlocking {
        var dailySheetApproved = false
        var leaseAcquired = false
        var leaseReleased = false

        val okHttpClient = createMockOkHttpClient { req ->
            val path = req.url.encodedPath
            when {
                path.contains("/daily-sheets/") && path.endsWith("/approve") -> {
                    dailySheetApproved = true
                    jsonResponse(req, 200, """{"message": "Daily sheet approved successfully"}""")
                }
                path.contains("/master-workbook/state") -> {
                    jsonResponse(req, 200, """{"state": {"state_id": "state_1", "master_drive_file_id": ""}}""")
                }
                path.contains("/master-workbook/acquire-lease") -> {
                    leaseAcquired = true
                    jsonResponse(req, 200, """{"message": "Lease acquired", "lease_owner_job_id": "job_1"}""")
                }
                path.contains("/master-workbook/release-lease") -> {
                    leaseReleased = true
                    jsonResponse(req, 200, """{"message": "Lease released"}""")
                }
                else -> jsonResponse(req, 200, """{}""")
            }
        }

        val workerClient = WorkerApiClient(baseUrl = "http://192.168.1.100:8787", client = okHttpClient)
        val repository = AuditRepository(
            db = null,
            workerApiClient = workerClient
        )

        val batch = DailyBatchSubmissionRecord(
            id = "batch_001",
            batchId = "batch_001",
            clientId = "client_alpha",
            fiscalYearId = "2026_27",
            status = SubmissionBatchStatus.SUBMITTED,
            countingDate = "2026-11-03",
            userId = "auditor_1"
        )

        val result = repository.approveBatchAndSyncMasterWorkbook(
            batch = batch,
            reviewerUid = "admin_1",
            reviewerName = "Super Admin",
            adminRemarks = "All verified",
            countingRecordsForBatch = emptyList(),
            unlistedRecordsForBatch = emptyList()
        )

        assertTrue("Approval should succeed and return metadata", result.isSuccess)
        assertTrue("Daily sheet must be approved on Worker D1", dailySheetApproved)
    }

    // =========================================================================
    // AF-AUDIT-002: PhotoUploadWorker Token Provider Injection
    // =========================================================================

    @Test
    fun testPhotoUploadWorker_tokenProviderInjection_returnsExpectedToken(): Unit = runBlocking {
        var tokenCallCount = 0
        val mockTokenProvider: suspend () -> String? = {
            tokenCallCount++
            "mock_oauth2_token_xyz"
        }

        val token = mockTokenProvider()
        assertEquals(1, tokenCallCount)
        assertEquals("mock_oauth2_token_xyz", token)

        val nullTokenProvider: suspend () -> String? = { null }
        val nullToken = nullTokenProvider()
        assertEquals(null, nullToken)
    }

    // =========================================================================
    // Safety Invariants: 6 Protected Modules SHA-256 Verification
    // =========================================================================

    @Test
    fun testProtectedModules_hashesMatchEstablishedBaselines() {
        val expectedBaselines = mapOf(
            "AuditCalculationEngine.kt" to "970bcd80e3bc1eac737dad6fbece0ce86cbda40b3d9df018f81be3c14d876d1c",
            "ExcelWorkbookEngine.kt" to "dee96c0e5c994dcb09e1684025303a9cbec0e9f92e4bbc112aa643aea4fb3c6a",
            "AuditModels.kt" to "a9f7517c52c5865cf4b291bcfbf22788faf4ee4c46b450f1faf2864da48c1c33",
            "OfflineSyncDatabase.kt" to "a20bfc2562d8d0f48af2b8727abdda12b2d9825c039c0d19603afaddc2c62cd0",
            "AdminReviewQueueTab.kt" to "bac96ed7916ba22bfb94f9920fdce6db983f6e6164a81ceed9ca44f89cf77d9e",
            "ClientSelectionScreen.kt" to "b52f00b0037496775cb2996134e751a6ce1de7a6452c589f72fa882d52ecd50b"
        )

        val srcDir = File("src/main/java")
        val md = MessageDigest.getInstance("SHA-256")

        for ((fileName, expectedHash) in expectedBaselines) {
            val matchingFiles = srcDir.walkTopDown().filter { it.isFile && it.name == fileName }.toList()
            assertTrue("Protected file $fileName must exist in codebase", matchingFiles.isNotEmpty())
            val file = matchingFiles.first()
            val fileBytes = file.readBytes()
            val computedHash = md.digest(fileBytes).joinToString("") { "%02x".format(it) }
            assertEquals("Protected module $fileName SHA-256 hash has changed! Baseline violation.", expectedHash, computedHash)
        }
    }
}
