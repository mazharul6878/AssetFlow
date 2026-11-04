package com.example

import com.example.data.model.ClientRecord
import com.example.data.model.FiscalYearRecord
import com.example.data.network.DynamicFieldDto
import com.example.data.network.DynamicFieldOptionDto
import com.example.data.network.DynamicSchemaDto
import com.example.data.network.HeaderClassificationResultDto
import com.example.data.network.LocalMockTokenProvider
import com.example.data.network.WorkerApiClient
import com.example.data.network.WorkerApiResult
import com.example.data.network.toDynamicSchemaDto
import com.example.data.network.toWorkbookSchemaVersion
import com.example.data.offline.CachedFarBaselineEntity
import com.example.data.offline.CachedSchemaEntity
import com.example.data.offline.DynamicOfflineCacheDao
import com.example.data.repository.AuditRepository
import com.example.data.repository.DynamicOfflineCacheRepository
import com.example.data.schema.AutomationRuleType
import com.example.data.schema.DynamicSchemaEngine
import com.example.data.schema.FieldBehaviorType
import com.example.data.schema.FieldInputType
import com.example.data.schema.SchemaConfigMode
import com.example.data.schema.SchemaFieldConfig
import com.example.data.schema.WorkbookSchemaVersion
import com.example.ui.viewmodel.AuditViewModel
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 1 Verification & Remediation Test Suite.
 * Validates Workstream A (Dynamic Schema Integration AF-001 / AF-004) and
 * Workstream B (AF-023 Owner Create Client Workflow, Screen Unmount Prevention & Multi-Client FYs).
 */
class DynamicSchemaStage1RemediationTest {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @org.junit.Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @org.junit.After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun createMockClient(handler: (requestUrl: String, method: String, body: String) -> Pair<Int, String>): WorkerApiClient {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val bodyStr = req.body?.let {
                    val buffer = okio.Buffer()
                    it.writeTo(buffer)
                    buffer.readUtf8()
                } ?: ""
                val (statusCode, respBody) = handler(req.url.toString(), req.method, bodyStr)
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message(if (statusCode in 200..299) "OK" else "Error")
                    .body(respBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        return WorkerApiClient(
            baseUrl = "http://localhost:8787",
            tokenProvider = LocalMockTokenProvider(),
            client = okHttpClient
        )
    }

    // =========================================================================
    // WORKSTREAM A — DYNAMIC SCHEMA INTEGRATION TESTS
    // =========================================================================

    @Test
    fun testPublishSchemaWorkerApiClientSuccess() = runBlocking {
        var recordedUrl = ""
        var recordedMethod = ""
        var recordedBody = ""

        val client = createMockClient { url, method, body ->
            recordedUrl = url
            recordedMethod = method
            recordedBody = body
            200 to JSONObject().put("message", "Schema version published successfully").toString()
        }

        val schemaDto = DynamicSchemaDto(
            schemaVersionId = "sch_walton_v1",
            clientId = "client_walton",
            fiscalYearId = "2026_27",
            versionNumber = 1,
            status = "PUBLISHED",
            sourceWorkbookName = "Walton_Assets.xlsx",
            uniqueAssetKeyHeader = "Asset ID",
            fields = listOf(
                DynamicFieldDto(
                    fieldId = "fld_asset_id",
                    fieldKey = "asset_id",
                    displayLabel = "Asset ID",
                    originalHeader = "Asset ID",
                    inputMode = "FIXED_IMPORTED",
                    dataType = "TEXT",
                    isRequired = true,
                    isVisible = true,
                    sortOrder = 0,
                    sourceType = "EXCEL_POPULATED"
                ),
                DynamicFieldDto(
                    fieldId = "fld_status",
                    fieldKey = "status",
                    displayLabel = "Condition Status",
                    originalHeader = "Status",
                    inputMode = "DROPDOWN",
                    dataType = "SINGLE_SELECT",
                    isRequired = true,
                    isVisible = true,
                    sortOrder = 1,
                    sourceType = "CUSTOM",
                    options = listOf(
                        DynamicFieldOptionDto("opt_1", "Good", "Good", 1, true),
                        DynamicFieldOptionDto("opt_2", "Damaged", "Damaged", 2, true)
                    )
                )
            )
        )

        val result = client.publishSchema("client_walton", schemaDto)
        assertTrue("Result should be Success", result is WorkerApiResult.Success)
        assertEquals("Schema version published successfully", (result as WorkerApiResult.Success).data)
        assertTrue(recordedUrl.endsWith("/api/v1/clients/client_walton/schemas/publish"))
        assertEquals("POST", recordedMethod)

        val sentJson = JSONObject(recordedBody)
        assertEquals("sch_walton_v1", sentJson.getString("schemaVersionId"))
        assertEquals("client_walton", sentJson.getString("clientId"))
        assertEquals(2, sentJson.getJSONArray("fields").length())
    }

    @Test
    fun testAnalyzeHeadersWorkerApiClientSuccess() = runBlocking {
        var recordedUrl = ""
        var recordedMethod = ""

        val mockResponseJson = JSONObject().apply {
            val resultsArr = JSONArray()
            resultsArr.put(JSONObject().apply {
                put("headerText", "Asset ID")
                put("columnIndex", 0)
                put("columnLetter", "A")
                put("nonBlankCount", 10)
                put("totalRows", 10)
                put("sampleValues", JSONArray(listOf("W-001", "W-002")))
                put("inferredDataType", "TEXT")
                put("classification", "FIXED_IMPORTED")
                put("isSparse", false)
            })
            resultsArr.put(JSONObject().apply {
                put("headerText", "Notes")
                put("columnIndex", 1)
                put("columnLetter", "B")
                put("nonBlankCount", 0)
                put("totalRows", 10)
                put("sampleValues", JSONArray())
                put("inferredDataType", "TEXT")
                put("classification", "EMPTY_HEADER_PENDING_CONFIGURATION")
                put("isSparse", false)
            })
            put("results", resultsArr)
        }.toString()

        val client = createMockClient { url, method, _ ->
            recordedUrl = url
            recordedMethod = method
            200 to mockResponseJson
        }

        val headers = listOf("Asset ID", "Notes")
        val rows = listOf(listOf("W-001", null), listOf("W-002", null))

        val result = client.analyzeHeaders("client_walton", headers, rows)
        assertTrue("Result should be Success", result is WorkerApiResult.Success)
        val list = (result as WorkerApiResult.Success).data
        assertEquals(2, list.size)
        assertEquals("Asset ID", list[0].headerText)
        assertEquals("FIXED_IMPORTED", list[0].classification)
        assertEquals("Notes", list[1].headerText)
        assertEquals("EMPTY_HEADER_PENDING_CONFIGURATION", list[1].classification)
        assertTrue(recordedUrl.endsWith("/api/v1/clients/client_walton/schemas/analyze-headers"))
        assertEquals("POST", recordedMethod)
    }

    @Test
    fun testBidirectionalSchemaConversionFidelity() {
        val originalConfigs = listOf(
            SchemaFieldConfig(
                fieldId = "fld_asset_id",
                displayName = "Asset ID",
                originalHeading = "Asset ID",
                columnIndex = 0,
                columnLetter = "A",
                fieldOrder = 0,
                behavior = FieldBehaviorType.FIXED_MASTER_DATA,
                inputType = FieldInputType.TEXT,
                isRequired = true
            ),
            SchemaFieldConfig(
                fieldId = "fld_notes",
                displayName = "Auditor Notes",
                originalHeading = "Notes",
                columnIndex = 1,
                columnLetter = "B",
                fieldOrder = 1,
                behavior = FieldBehaviorType.USER_INPUT,
                inputType = FieldInputType.TEXT,
                isRequired = false
            ),
            SchemaFieldConfig(
                fieldId = "fld_condition",
                displayName = "Condition",
                originalHeading = "Condition",
                columnIndex = 2,
                columnLetter = "C",
                fieldOrder = 2,
                behavior = FieldBehaviorType.DROPDOWN,
                dropdownOptions = listOf("Good", "Fair", "Damaged"),
                isRequired = true
            )
        )

        val version = WorkbookSchemaVersion(
            id = "schema_walton_v2",
            versionNumber = 2L,
            status = "PUBLISHED",
            clientId = "client_walton",
            fiscalYearId = "2026_27",
            sourceWorkbookName = "Master_Register.xlsx",
            targetWorksheetName = "Count Sheet",
            uniqueKeyFieldId = "Asset ID",
            fieldsJson = DynamicSchemaEngine.serializeFieldsJson(originalConfigs),
            changeSummary = "Added Condition dropdown",
            publishedByUid = "uid_admin_123"
        )

        // 1. Convert to Worker DynamicSchemaDto
        val dto = version.toDynamicSchemaDto()
        assertEquals("schema_walton_v2", dto.schemaVersionId)
        assertEquals("client_walton", dto.clientId)
        assertEquals("2026_27", dto.fiscalYearId)
        assertEquals(2, dto.versionNumber)
        assertEquals(3, dto.fields.size)

        // Verify D1 constraint compatibility
        assertEquals("EXCEL_POPULATED", dto.fields[0].sourceType)
        assertEquals("FIXED_IMPORTED", dto.fields[0].inputMode)

        assertEquals("DROPDOWN", dto.fields[2].inputMode)
        assertEquals(3, dto.fields[2].options.size)
        assertEquals("Good", dto.fields[2].options[0].optionLabel)

        // 2. Convert back to WorkbookSchemaVersion
        val restored = dto.toWorkbookSchemaVersion()
        assertEquals(version.id, restored.id)
        assertEquals(version.versionNumber, restored.versionNumber)
        assertEquals(version.clientId, restored.clientId)
        assertEquals(version.fiscalYearId, restored.fiscalYearId)

        val restoredFields = restored.parsedFields()
        assertEquals(3, restoredFields.size)
        assertEquals("fld_asset_id", restoredFields[0].fieldId)
        assertEquals(FieldBehaviorType.FIXED_MASTER_DATA, restoredFields[0].behavior)
        assertEquals(FieldBehaviorType.DROPDOWN, restoredFields[2].behavior)
        assertEquals(listOf("Good", "Fair", "Damaged"), restoredFields[2].dropdownOptions)
    }

    @Test
    fun testAdminPublishSchemaInvalidatesOfflineCache() = runBlocking {
        var evictedClientId = ""
        var evictedCycleId = ""

        val mockCacheDao = object : DynamicOfflineCacheDao {
            override suspend fun upsertCachedSchema(entity: CachedSchemaEntity) {}
            override suspend fun getCachedSchema(clientId: String, cycleId: String): CachedSchemaEntity? = null
            override suspend fun deleteCachedSchema(clientId: String, cycleId: String) {
                evictedClientId = clientId
                evictedCycleId = cycleId
            }
            override suspend fun upsertCachedFarBaseline(entity: CachedFarBaselineEntity) {}
            override suspend fun upsertCachedFarBaselines(entities: List<CachedFarBaselineEntity>) {}
            override suspend fun getCachedFarBaseline(clientId: String, cycleId: String, assetKey: String): CachedFarBaselineEntity? = null
            override suspend fun getCachedFarBaselinesForCycle(clientId: String, cycleId: String): List<CachedFarBaselineEntity> = emptyList()
            override suspend fun deleteFarBaselinesForClient(clientId: String) {}
            override suspend fun deleteFarBaselinesForCycle(clientId: String, cycleId: String) {}
        }

        val client = createMockClient { _, _, _ ->
            200 to JSONObject().put("message", "Schema version published successfully").toString()
        }

        val mockFunctions = object : com.example.data.remote.FirebaseFunctionsService(null) {}
        val repo = AuditRepository(db = null, functionsService = mockFunctions, workerApiClient = client)
        repo.dynamicOfflineCacheDao = mockCacheDao

        val ver = WorkbookSchemaVersion(
            id = "sch_test_v1",
            versionNumber = 1L,
            clientId = "client_walton",
            fiscalYearId = "2026_27",
            status = "PUBLISHED"
        )

        val saveRes = repo.saveSchemaVersion(ver)
        assertTrue(saveRes.isSuccess)
        assertEquals("client_walton", evictedClientId)
        assertEquals("2026_27", evictedCycleId)

        // Verify that cachedSchemas immediately reflects the published version
        val cached = repo.cachedSchemas.value
        assertEquals(1, cached.size)
        assertEquals("sch_test_v1", cached[0].id)
    }

    @Test
    fun testAdminPublishAndAuditorRetrieveEndToEnd() = runBlocking {
        var publishedSchemaJson: JSONObject? = null

        val client = createMockClient { url, method, body ->
            if (url.endsWith("/schemas/publish") && method == "POST") {
                publishedSchemaJson = JSONObject(body)
                200 to JSONObject().put("message", "Schema version published successfully").toString()
            } else if (url.contains("/audit-cycles/") && url.endsWith("/schema") && method == "GET") {
                200 to JSONObject().apply {
                    put("schema", publishedSchemaJson ?: JSONObject.NULL)
                }.toString()
            } else {
                404 to JSONObject().put("error", "Not found").toString()
            }
        }

        val inMemoryCache = mutableMapOf<String, CachedSchemaEntity>()
        val mockCacheDao = object : DynamicOfflineCacheDao {
            override suspend fun upsertCachedSchema(entity: CachedSchemaEntity) {
                inMemoryCache[entity.cacheKey] = entity
            }
            override suspend fun getCachedSchema(clientId: String, cycleId: String): CachedSchemaEntity? {
                return inMemoryCache["$clientId:$cycleId"]
            }
            override suspend fun deleteCachedSchema(clientId: String, cycleId: String) {
                inMemoryCache.remove("$clientId:$cycleId")
            }
            override suspend fun upsertCachedFarBaseline(entity: CachedFarBaselineEntity) {}
            override suspend fun upsertCachedFarBaselines(entities: List<CachedFarBaselineEntity>) {}
            override suspend fun getCachedFarBaseline(clientId: String, cycleId: String, assetKey: String): CachedFarBaselineEntity? = null
            override suspend fun getCachedFarBaselinesForCycle(clientId: String, cycleId: String): List<CachedFarBaselineEntity> = emptyList()
            override suspend fun deleteFarBaselinesForClient(clientId: String) {}
            override suspend fun deleteFarBaselinesForCycle(clientId: String, cycleId: String) {}
        }

        val mockFunctions = object : com.example.data.remote.FirebaseFunctionsService(null) {}
        val adminRepo = AuditRepository(db = null, functionsService = mockFunctions, workerApiClient = client)
        adminRepo.dynamicOfflineCacheDao = mockCacheDao
        val auditorCacheRepo = DynamicOfflineCacheRepository(client, mockCacheDao)

        // 1. Admin publishes Schema v1 with 3 fields
        val field1 = SchemaFieldConfig("fld_id", "Asset ID", "Asset ID", columnIndex = 0, columnLetter = "A", fieldOrder = 0, behavior = FieldBehaviorType.FIXED_MASTER_DATA)
        val field2 = SchemaFieldConfig("fld_desc", "Description", "Desc", columnIndex = 1, columnLetter = "B", fieldOrder = 1, behavior = FieldBehaviorType.FIXED_MASTER_DATA)
        val field3 = SchemaFieldConfig("fld_condition", "Condition", "Cond", columnIndex = 2, columnLetter = "C", fieldOrder = 2, behavior = FieldBehaviorType.DROPDOWN, dropdownOptions = listOf("Good", "Fair", "Damaged"))

        val v1 = WorkbookSchemaVersion(
            id = "sch_walton_v1",
            versionNumber = 1L,
            clientId = "client_walton",
            fiscalYearId = "2026_27",
            status = "PUBLISHED",
            fieldsJson = DynamicSchemaEngine.serializeFieldsJson(listOf(field1, field2, field3))
        )

        val publishRes = adminRepo.saveSchemaVersion(v1)
        assertTrue("Admin publish should succeed", publishRes.isSuccess)
        assertNotNull(publishedSchemaJson)

        // 2. Auditor retrieves published schema from Worker/D1
        val auditorRes = auditorCacheRepo.getSchemaForCycle("client_walton", "2026_27", forceRefresh = true)
        assertTrue("Auditor schema fetch should succeed", auditorRes is WorkerApiResult.Success)
        val retrievedSchema = (auditorRes as WorkerApiResult.Success).data
        assertNotNull(retrievedSchema)
        assertEquals("sch_walton_v1", retrievedSchema?.schemaVersionId)
        assertEquals(3, retrievedSchema?.fields?.size)
        assertEquals("Condition", retrievedSchema?.fields?.get(2)?.displayLabel)
        assertEquals(3, retrievedSchema?.fields?.get(2)?.options?.size)
    }

    // =========================================================================
    // WORKSTREAM B — AF-023 OWNER CREATE CLIENT WORKFLOW & MULTI-CLIENT TESTS
    // =========================================================================

    @Test
    fun testAF023_OwnerCreateClientDoesNotPrematurelySelectClientOrUnmountScreen() = runBlocking {
        val client = createMockClient { url, method, _ ->
            if (url.endsWith("/api/v1/clients") && method == "POST") {
                val createdJson = JSONObject().apply {
                    put("clientId", "client_apex_01")
                    put("clientName", "Apex Footwear")
                    put("clientCode", "APEX")
                    put("activeFiscalYearId", "2026_27")
                    put("status", "Active")
                }
                val fyJson = JSONObject().apply {
                    put("fiscalYearId", "2026_27")
                    put("clientId", "client_apex_01")
                    put("label", "2026–27")
                    put("isClosed", false)
                }
                201 to JSONObject().apply {
                    put("client", createdJson)
                    put("fiscalYear", fyJson)
                }.toString()
            } else if (url.contains("/fiscal-years")) {
                200 to JSONObject().put("fiscalYears", JSONArray()).toString()
            } else {
                200 to JSONObject().toString()
            }
        }

        val mockFunctions = object : com.example.data.remote.FirebaseFunctionsService(null) {}
        val repo = AuditRepository(db = null, functionsService = mockFunctions, workerApiClient = client)
        val viewModel = AuditViewModel(repo, currentUserId = "uid_owner")

        // 1. Initial State: activeClientId is empty string (User is on ClientSelectionScreen)
        assertEquals("", viewModel.activeClientId.value)
        assertEquals(null, viewModel.clientCreationSuccess.value)

        // 2. Create client through ViewModel
        viewModel.createClientWithDefaultFiscalYear(
            clientName = "Apex Footwear",
            clientCode = "APEX",
            fiscalYearLabel = "2026–27"
        )
        kotlinx.coroutines.delay(50)

        // 3. CRITICAL VERIFICATION:
        // After successful creation, _activeClientId must NOT be prematurely updated!
        // This prevents MainActivity from prematurely unmounting ClientSelectionScreen.
        assertEquals(
            "activeClientId must remain empty after client creation to preserve ClientSelectionScreen mounting",
            "",
            viewModel.activeClientId.value
        )
        assertEquals(
            "Client 'Apex Footwear' created successfully.",
            viewModel.clientCreationSuccess.value
        )

        // 4. ClientSelectionScreen displays success card and selectable client list.
        // The user explicitly taps 'Continue' to enter the workspace:
        viewModel.selectClientAndFiscalYear("client_apex_01", "2026_27")

        // 5. Now and only now should activeClientId be set:
        assertEquals("client_apex_01", viewModel.activeClientId.value)
        assertEquals("2026_27", viewModel.activeFiscalYearId.value)
    }

    @Test
    fun testObserveFiscalYearsLoadsFromWorkerAndMergesMultiClient() = runBlocking {
        val client = createMockClient { url, _, _ ->
            if (url.contains("/clients/client_alpha/fiscal-years")) {
                val fyArr = JSONArray().apply {
                    put(JSONObject().apply {
                        put("fiscalYearId", "2026_27")
                        put("clientId", "client_alpha")
                        put("label", "2026–27")
                        put("isClosed", false)
                    })
                }
                200 to JSONObject().put("fiscalYears", fyArr).toString()
            } else if (url.contains("/clients/client_beta/fiscal-years")) {
                val fyArr = JSONArray().apply {
                    put(JSONObject().apply {
                        put("fiscalYearId", "2027_28")
                        put("clientId", "client_beta")
                        put("label", "2027–28")
                        put("isClosed", false)
                    })
                }
                200 to JSONObject().put("fiscalYears", fyArr).toString()
            } else {
                200 to JSONObject().put("fiscalYears", JSONArray()).toString()
            }
        }

        val mockFunctions = object : com.example.data.remote.FirebaseFunctionsService(null) {}
        val repo = AuditRepository(db = null, functionsService = mockFunctions, workerApiClient = client)

        // Fetch FYs for Client Alpha
        val alphaRes = repo.fetchFiscalYearsFromWorker("client_alpha")
        assertTrue(alphaRes.isSuccess)
        assertEquals(1, repo.cachedFiscalYears.value.size)
        assertEquals("client_alpha", repo.cachedFiscalYears.value[0].clientId)

        // Fetch FYs for Client Beta — must merge, NOT overwrite Alpha's FYs!
        val betaRes = repo.fetchFiscalYearsFromWorker("client_beta")
        assertTrue(betaRes.isSuccess)
        val combined = repo.cachedFiscalYears.value
        assertEquals("Multi-client fiscal years must be retained across client switches", 2, combined.size)
        assertTrue(combined.any { it.clientId == "client_alpha" })
        assertTrue(combined.any { it.clientId == "client_beta" })
    }
}
