package com.example

import com.example.data.network.CountingSubmissionDto
import com.example.data.network.DynamicFieldDto
import com.example.data.network.DynamicFieldOptionDto
import com.example.data.network.DynamicSchemaDto
import com.example.data.network.FarAssetBaselineDto
import com.example.data.network.LocalMockTokenProvider
import com.example.data.network.WorkerApiClient
import com.example.data.network.WorkerApiResult
import com.example.data.repository.DynamicAuditScreenData
import com.example.data.repository.WorkerApiRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local Vertical Slice Test Suite for Android Dynamic Schema API Integration.
 * Validates dynamic schema parsing, multi-field counts (8, 10, 15, 24), leading-zero preservation,
 * JSON array serialization for multi-select, and error/idempotency behavior.
 */
class DynamicSchemaVerticalSliceTest {

    @Test
    fun testUninitializedClientReturnsNullSchema() {
        val mockJson = JSONObject().apply {
            put("message", "No active published schema for client 'client_uninit' in cycle '2026_27'")
            put("schema", JSONObject.NULL)
        }

        val client = createMockWorkerClient(
            mockResponse = mockJson.toString(),
            statusCode = 200
        )

        runBlocking {
            val result = client.getSchemaForCycle("client_uninit", "2026_27")
            assertTrue("Expected Success result", result is WorkerApiResult.Success)
            val schema = (result as WorkerApiResult.Success).data
            assertNull("Uninitialized client must return null schema", schema)
        }
    }

    @Test
    fun testLeadingZeroAssetIdPreservedAsText() {
        val leadingZeroAssetId = "0001872"
        val mockAssetJson = JSONObject().apply {
            val assetObj = JSONObject().apply {
                put("asset_key", "client_walton_2026_27_0001872_R5")
                put("asset_id", leadingZeroAssetId)
                put("client_id", "client_walton")
                put("fiscal_year_id", "2026_27")
                put("source_row", 5)
                put("raw_row_json", JSONObject().apply {
                    put("Asset ID", leadingZeroAssetId)
                    put("Description", "Heavy Hydraulic Press")
                    put("Location", "Plant 3, Bay 12")
                }.toString())
            }
            put("asset", assetObj)
        }

        val client = createMockWorkerClient(
            mockResponse = mockAssetJson.toString(),
            statusCode = 200
        )

        runBlocking {
            val result = client.getFarAsset("client_walton", "2026_27", "client_walton_2026_27_0001872_R5")
            assertTrue("Expected Success", result is WorkerApiResult.Success)
            val asset = (result as WorkerApiResult.Success).data
            assertNotNull(asset)
            assertEquals("0001872", asset?.assetId)
            assertTrue("Must preserve leading zeros", asset?.assetId?.startsWith("000") == true)
            assertEquals("Plant 3, Bay 12", asset?.rawRowData?.get("Location"))
        }
    }

    @Test
    fun testMultiSelectSerializesToJsonArrayNeverCommaString() {
        val submission = CountingSubmissionDto(
            assetKey = "client_walton_2026_27_0001872_R5",
            schemaVersionId = "sch_wlt_fy26_v1",
            countDate = "2026-11-01",
            idempotencyKey = "idemp_test_uuid_1234",
            values = mapOf(
                "fld_cond_01" to "Good",
                "fld_flags_02" to listOf("TAG_VERIFIED", "PHOTO_CAPTURED", "LOCATION_CONFIRMED")
            )
        )

        val json = submission.toJson()
        val valuesObj = json.getJSONObject("values")
        val flagsVal = valuesObj.get("fld_flags_02")

        assertTrue("Multi-select value must be a JSONArray", flagsVal is JSONArray)
        assertFalse("Multi-select value must NOT be a comma-separated String", flagsVal is String)

        val flagsArray = flagsVal as JSONArray
        assertEquals(3, flagsArray.length())
        assertEquals("TAG_VERIFIED", flagsArray.getString(0))
        assertEquals("PHOTO_CAPTURED", flagsArray.getString(1))
        assertEquals("LOCATION_CONFIRMED", flagsArray.getString(2))
    }

    @Test
    fun testDynamicSchemasWithArbitraryFieldCounts() {
        fun buildMockSchemaJson(fieldCount: Int): String {
            val fields = JSONArray()
            for (i in 1..fieldCount) {
                fields.put(JSONObject().apply {
                    put("fieldId", "fld_$i")
                    put("fieldKey", "key_$i")
                    put("displayLabel", "Field Label $i")
                    put("inputMode", if (i <= 3) "FIXED_IMPORTED" else if (i % 2 == 0) "DROPDOWN" else "MANUAL")
                    put("dataType", if (i == 1) "INTEGER" else "TEXT")
                    put("isRequired", i > 3)
                    put("isVisible", true)
                    put("sortOrder", i)
                })
            }
            return JSONObject().apply {
                put("schema", JSONObject().apply {
                    put("schemaVersionId", "sch_test_${fieldCount}_v1")
                    put("clientId", "client_test")
                    put("fiscalYearId", "2026_27")
                    put("versionNumber", 1)
                    put("status", "PUBLISHED")
                    put("fields", fields)
                })
            }.toString()
        }

        // 1. Test 8-Field Schema
        val schema8 = DynamicSchemaDto.fromJson(JSONObject(buildMockSchemaJson(8)).getJSONObject("schema"))
        assertEquals(8, schema8.fields.size)
        assertEquals("FIXED_IMPORTED", schema8.fields[0].inputMode)

        // 2. Test 10-Field Schema
        val schema10 = DynamicSchemaDto.fromJson(JSONObject(buildMockSchemaJson(10)).getJSONObject("schema"))
        assertEquals(10, schema10.fields.size)

        // 3. Test 15-Field Schema (Commercial standard)
        val schema15 = DynamicSchemaDto.fromJson(JSONObject(buildMockSchemaJson(15)).getJSONObject("schema"))
        assertEquals(15, schema15.fields.size)

        // 4. Test 24-Field Schema (Complex facility)
        val schema24 = DynamicSchemaDto.fromJson(JSONObject(buildMockSchemaJson(24)).getJSONObject("schema"))
        assertEquals(24, schema24.fields.size)
    }

    @Test
    fun testIdempotentSubmissionReplayHandling() {
        val successJson = JSONObject().apply {
            put("recordId", "rec_99210")
            put("status", "SUBMITTED")
            put("isIdempotentReplay", false)
        }.toString()

        val replayJson = JSONObject().apply {
            put("recordId", "rec_99210")
            put("status", "SUBMITTED")
            put("isIdempotentReplay", true)
        }.toString()

        val client = createMockWorkerClient(successJson, 201)
        val submission = CountingSubmissionDto(
            assetKey = "key_01",
            schemaVersionId = "sch_01",
            countDate = "2026-11-01",
            idempotencyKey = "idem_key_replay",
            values = mapOf("fld_01" to "Good")
        )

        runBlocking {
            val res1 = client.submitCount("cl_01", "2026_27", submission)
            assertTrue(res1 is WorkerApiResult.Success)
            val data1 = (res1 as WorkerApiResult.Success).data
            assertEquals("rec_99210", data1.recordId)
            assertFalse(data1.isIdempotentReplay)
        }

        // Test Replay Response
        val replayClient = createMockWorkerClient(replayJson, 200)
        runBlocking {
            val res2 = replayClient.submitCount("cl_01", "2026_27", submission)
            assertTrue(res2 is WorkerApiResult.Success)
            val data2 = (res2 as WorkerApiResult.Success).data
            assertEquals("rec_99210", data2.recordId)
            assertTrue("Replay must be flagged as idempotent replay", data2.isIdempotentReplay)
        }
    }

    @Test
    fun testApiErrorPreservesEnteredValues() {
        val errorJson = JSONObject().apply {
            put("error", "Conflict: Asset already verified by another auditor")
            put("code", "CONFLICT")
        }.toString()

        val client = createMockWorkerClient(errorJson, 409)
        val submission = CountingSubmissionDto(
            assetKey = "key_01",
            schemaVersionId = "sch_01",
            countDate = "2026-11-01",
            idempotencyKey = "idem_conflict",
            values = mapOf("fld_qty" to 5, "fld_notes" to "Auditor entered notes")
        )

        runBlocking {
            val res = client.submitCount("cl_01", "2026_27", submission)
            assertTrue(res is WorkerApiResult.Error)
            val err = res as WorkerApiResult.Error
            assertEquals(409, err.statusCode)
            assertTrue(err.message.contains("Conflict"))

            // Verify entered values in submission DTO are intact and not mutated
            assertEquals(5, submission.values["fld_qty"])
            assertEquals("Auditor entered notes", submission.values["fld_notes"])
        }
    }

    private fun createMockWorkerClient(
        mockResponse: String,
        statusCode: Int
    ): WorkerApiClient {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message(if (statusCode in 200..299) "OK" else "Error")
                    .body(mockResponse.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        return WorkerApiClient(
            baseUrl = "http://localhost:8787",
            tokenProvider = LocalMockTokenProvider(),
            client = okHttpClient
        )
    }
}
