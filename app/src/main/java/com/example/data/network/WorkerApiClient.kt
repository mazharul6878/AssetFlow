package com.example.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Type-safe HTTP Client communicating with the Cloudflare Worker API.
 */
open class WorkerApiClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val tokenProvider: WorkerTokenProvider = FirebaseAuthTokenProvider(),
    private val client: OkHttpClient = createDefaultOkHttpClient(tokenProvider)
) {
    init {
        if (!com.example.BuildConfig.DEBUG) {
            if (baseUrl.isBlank()) {
                throw IllegalStateException("Production Worker API URL is not configured. Supply -PWORKER_PROD_URL during build.")
            }
            if (baseUrl.startsWith("http://10.0.2.2") || baseUrl.startsWith("http://localhost") || baseUrl.startsWith("http://127.0.0.1")) {
                throw IllegalStateException("Development/local endpoint ($baseUrl) is strictly forbidden in release builds.")
            }
            if (baseUrl.startsWith("http://")) {
                throw SecurityException("Cleartext HTTP traffic is rejected in release builds: $baseUrl. Production API requires HTTPS.")
            }
            if (tokenProvider is LocalMockTokenProvider) {
                throw SecurityException("LocalMockTokenProvider is strictly prohibited in release builds.")
            }
        }
    }

    companion object {
        val DEFAULT_BASE_URL: String
            get() = try {
                val configured = com.example.BuildConfig.WORKER_BASE_URL
                if (configured.isNotBlank()) {
                    configured
                } else if (com.example.BuildConfig.DEBUG) {
                    "http://10.0.2.2:8787"
                } else {
                    ""
                }
            } catch (_: Throwable) {
                if (com.example.BuildConfig.DEBUG) "http://10.0.2.2:8787" else ""
            }
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun createDefaultOkHttpClient(tokenProvider: WorkerTokenProvider): OkHttpClient {
            return OkHttpClient.Builder()
                .addInterceptor(WorkerAuthInterceptor(tokenProvider))
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }

    open fun isConfigured(): Boolean =
        baseUrl.isNotBlank() && (!isEmulatorLoopbackUrl() || com.example.BuildConfig.DEBUG)

    open fun isEmulatorLoopbackUrl(): Boolean =
        baseUrl.contains("10.0.2.2") || baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")

    fun formatNetworkErrorMessage(action: String, e: IOException): String {
        val loopbackHint = if (isEmulatorLoopbackUrl()) {
            " (Host is configured as emulator loopback '$baseUrl'. On a physical Android device, set WORKER_DEBUG_URL to your host PC's LAN IP, e.g. http://192.168.1.X:8787)"
        } else ""
        return "Network error while $action: ${e.message}$loopbackHint"
    }

    /**
     * Retrieves the exact dynamic schema pinned to a specific Client and Audit Cycle.
     */
    open suspend fun getSchemaForCycle(
        clientId: String,
        cycleId: String,
        bearerTokenOverride: String? = null
    ): WorkerApiResult<DynamicSchemaDto?> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/audit-cycles/$cycleId/schema"
        val requestBuilder = Request.Builder().url(url).get()
        if (!bearerTokenOverride.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerTokenOverride")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    if (root.isNull("schema")) {
                        WorkerApiResult.Success(null)
                    } else {
                        val schemaObj = root.getJSONObject("schema")
                        WorkerApiResult.Success(DynamicSchemaDto.fromJson(schemaObj))
                    }
                } else {
                    WorkerApiResult.Error(
                        message = "Failed to fetch schema (HTTP ${response.code}): $bodyStr",
                        statusCode = response.code
                    )
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while connecting to Worker: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while parsing schema response: ${e.message}", cause = e)
        }
    }

    /**
     * Publishes a new dynamic schema version for a client.
     * Maps to POST /api/v1/clients/:clientId/schemas/publish.
     */
    open suspend fun publishSchema(
        clientId: String,
        schema: DynamicSchemaDto,
        bearerTokenOverride: String? = null
    ): WorkerApiResult<String> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/schemas/publish"
        val body = schema.toJson().toString().toRequestBody(JSON_MEDIA_TYPE)
        val requestBuilder = Request.Builder().url(url).post(body)
        if (!bearerTokenOverride.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerTokenOverride")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    WorkerApiResult.Success(root.optString("message", "Schema version published successfully"))
                } else {
                    WorkerApiResult.Error(
                        message = "Failed to publish schema (HTTP ${response.code}): $bodyStr",
                        statusCode = response.code
                    )
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while publishing schema: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while publishing schema: ${e.message}", cause = e)
        }
    }

    /**
     * Analyzes uploaded Excel workbook headers and sample rows to classify columns.
     * Maps to POST /api/v1/clients/:clientId/schemas/analyze-headers.
     */
    open suspend fun analyzeHeaders(
        clientId: String,
        headers: List<String>,
        rows: List<List<String?>>,
        bearerTokenOverride: String? = null
    ): WorkerApiResult<List<HeaderClassificationResultDto>> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/schemas/analyze-headers"
        val reqDto = AnalyzeHeadersRequestDto(headers, rows)
        val body = reqDto.toJson().toString().toRequestBody(JSON_MEDIA_TYPE)
        val requestBuilder = Request.Builder().url(url).post(body)
        if (!bearerTokenOverride.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerTokenOverride")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    val resultsArr = root.optJSONArray("results") ?: JSONArray()
                    val list = mutableListOf<HeaderClassificationResultDto>()
                    for (i in 0 until resultsArr.length()) {
                        list.add(HeaderClassificationResultDto.fromJson(resultsArr.getJSONObject(i)))
                    }
                    WorkerApiResult.Success(list)
                } else {
                    WorkerApiResult.Error(
                        message = "Failed to analyze headers (HTTP ${response.code}): $bodyStr",
                        statusCode = response.code
                    )
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while analyzing headers: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while analyzing headers: ${e.message}", cause = e)
        }
    }

    /**
     * Retrieves the currently active published dynamic schema for a client and fiscal year.
     * Maps to GET /api/v1/clients/:clientId/schemas/active?fiscalYear=:fiscalYear.
     */
    open suspend fun getActiveSchema(
        clientId: String,
        fiscalYear: String = "2026_27",
        bearerTokenOverride: String? = null
    ): WorkerApiResult<DynamicSchemaDto?> = withContext(Dispatchers.IO) {
        val encodedFy = URLEncoder.encode(fiscalYear, "UTF-8")
        val url = "$baseUrl/api/v1/clients/$clientId/schemas/active?fiscalYear=$encodedFy"
        val requestBuilder = Request.Builder().url(url).get()
        if (!bearerTokenOverride.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerTokenOverride")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    if (root.isNull("schema")) {
                        WorkerApiResult.Success(null)
                    } else {
                        val schemaObj = root.getJSONObject("schema")
                        WorkerApiResult.Success(DynamicSchemaDto.fromJson(schemaObj))
                    }
                } else {
                    WorkerApiResult.Error(
                        message = "Failed to fetch active schema (HTTP ${response.code}): $bodyStr",
                        statusCode = response.code
                    )
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while fetching active schema: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while parsing active schema response: ${e.message}", cause = e)
        }
    }

    /**
     * Retrieves the imported FAR baseline record for an asset.
     */
    suspend fun getFarAsset(
        clientId: String,
        cycleId: String,
        assetKey: String
    ): WorkerApiResult<FarAssetBaselineDto?> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/audit-cycles/$cycleId/assets/$assetKey"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    val assetObj = root.getJSONObject("asset")
                    WorkerApiResult.Success(FarAssetBaselineDto.fromJson(assetObj))
                } else if (response.code == 404) {
                    WorkerApiResult.Success(null)
                } else {
                    WorkerApiResult.Error(
                        message = "Failed to fetch FAR asset (HTTP ${response.code}): $bodyStr",
                        statusCode = response.code
                    )
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while fetching FAR asset: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while parsing FAR asset: ${e.message}", cause = e)
        }
    }

    /**
     * Submits a counting verification record with an Idempotency-Key.
     * Optionally accepts [bearerTokenOverride] to support token-refresh retry flows.
     */
    suspend fun submitCount(
        clientId: String,
        cycleId: String,
        submission: CountingSubmissionDto,
        bearerTokenOverride: String? = null
    ): WorkerApiResult<CountingSubmissionResultDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/audit-cycles/$cycleId/counts"
        val jsonPayload = submission.toJson().toString()
        val body = jsonPayload.toRequestBody(JSON_MEDIA_TYPE)

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Idempotency-Key", submission.idempotencyKey)
            .post(body)

        if (!bearerTokenOverride.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerTokenOverride")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    WorkerApiResult.Success(CountingSubmissionResultDto.fromJson(root))
                } else {
                    val retryAfter = response.header("Retry-After")?.toLongOrNull()
                    WorkerApiResult.Error(
                        message = "Count submission failed (HTTP ${response.code}): $bodyStr",
                        statusCode = response.code,
                        retryAfterSeconds = retryAfter
                    )
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while submitting counting record: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error during counting submission: ${e.message}", cause = e)
        }
    }

    /**
     * Registers photo evidence metadata with Cloudflare Worker D1.
     * Note: Never uploads binary data here — only metadata including Drive IDs and SHA-256.
     */
    open suspend fun registerPhotoEvidence(
        clientId: String,
        cycleId: String,
        payload: PhotoEvidenceMetadataDto,
        bearerTokenOverride: String? = null
    ): WorkerApiResult<PhotoEvidenceResultDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/audit-cycles/$cycleId/photos"
        val jsonPayload = payload.toJson().toString()
        val body = jsonPayload.toRequestBody(JSON_MEDIA_TYPE)

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Idempotency-Key", payload.photoId)
            .post(body)

        if (!bearerTokenOverride.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerTokenOverride")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    WorkerApiResult.Success(PhotoEvidenceResultDto.fromJson(root))
                } else {
                    val retryAfter = response.header("Retry-After")?.toLongOrNull()
                    WorkerApiResult.Error(
                        message = "Evidence metadata registration failed (HTTP ${response.code}): $bodyStr",
                        statusCode = response.code,
                        retryAfterSeconds = retryAfter
                    )
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while registering evidence metadata: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error during evidence registration: ${e.message}", cause = e)
        }
    }

    /**
     * Executes initial one-time System Owner bootstrap on the Cloudflare Worker backend.
     * Maps to POST /api/v1/system/bootstrap.
     */
    open suspend fun bootstrapInitialOwner(
        fullName: String? = null,
        nickname: String? = null,
        bearerTokenOverride: String? = null
    ): WorkerApiResult<SystemBootstrapResultDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/system/bootstrap"
        val payload = JSONObject().apply {
            if (!fullName.isNullOrBlank()) put("fullName", fullName)
            if (!nickname.isNullOrBlank()) put("nickname", nickname)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val requestBuilder = Request.Builder().url(url).post(body)
        if (!bearerTokenOverride.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerTokenOverride")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    WorkerApiResult.Success(SystemBootstrapResultDto.fromJson(root))
                } else {
                    WorkerApiResult.Error(
                        message = "System bootstrap failed (HTTP ${response.code}): $bodyStr",
                        statusCode = response.code
                    )
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error during system bootstrap: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error during system bootstrap: ${e.message}", cause = e)
        }
    }

    /**
     * Resolves authenticated user session, global role, and tenant memberships from Control DB.
     * Maps to GET /api/v1/system/session.
     */
    open suspend fun getUserSession(
        bearerTokenOverride: String? = null
    ): WorkerApiResult<UserSessionResultDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/system/session"
        val requestBuilder = Request.Builder().url(url).get()
        if (!bearerTokenOverride.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerTokenOverride")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful || response.code == 404) {
                    val root = JSONObject(bodyStr)
                    WorkerApiResult.Success(UserSessionResultDto.fromJson(root))
                } else {
                    WorkerApiResult.Error(
                        message = "Failed to resolve user session (HTTP ${response.code}): $bodyStr",
                        statusCode = response.code
                    )
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error resolving user session: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error resolving user session: ${e.message}", cause = e)
        }
    }

    /**
     * Creates a new client tenant workspace with shard assignment, creator membership, and initial fiscal year.
     * Maps to POST /api/v1/clients.
     */
    open suspend fun createClient(
        request: CreateClientRequestDto,
        bearerTokenOverride: String? = null
    ): WorkerApiResult<CreateClientResponseDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients"
        val body = request.toJson().toString().toRequestBody(JSON_MEDIA_TYPE)
        val requestBuilder = Request.Builder().url(url).post(body)
        if (!bearerTokenOverride.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerTokenOverride")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    WorkerApiResult.Success(CreateClientResponseDto.fromJson(root))
                } else {
                    WorkerApiResult.Error(
                        message = "Failed to create client (HTTP ${response.code}): $bodyStr",
                        statusCode = response.code
                    )
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while creating client: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while creating client: ${e.message}", cause = e)
        }
    }

    /**
     * Lists accessible clients for the authenticated user (all active clients for Owner; assigned for Admin/Auditor).
     * Maps to GET /api/v1/clients.
     */
    open suspend fun listClients(
        bearerTokenOverride: String? = null
    ): WorkerApiResult<ListClientsResponseDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients"
        val requestBuilder = Request.Builder().url(url).get()
        if (!bearerTokenOverride.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerTokenOverride")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    WorkerApiResult.Success(ListClientsResponseDto.fromJson(root))
                } else {
                    WorkerApiResult.Error(
                        message = "Failed to list clients (HTTP ${response.code}): $bodyStr",
                        statusCode = response.code
                    )
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while listing clients: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while listing clients: ${e.message}", cause = e)
        }
    }

    /**
     * Lists fiscal years for a specific client tenant.
     * Maps to GET /api/v1/clients/:clientId/fiscal-years.
     */
    open suspend fun listFiscalYears(
        clientId: String,
        bearerTokenOverride: String? = null
    ): WorkerApiResult<ListFiscalYearsResponseDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/fiscal-years"
        val requestBuilder = Request.Builder().url(url).get()
        if (!bearerTokenOverride.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerTokenOverride")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    WorkerApiResult.Success(ListFiscalYearsResponseDto.fromJson(root))
                } else {
                    WorkerApiResult.Error(
                        message = "Failed to list fiscal years (HTTP ${response.code}): $bodyStr",
                        statusCode = response.code
                    )
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while listing fiscal years: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while listing fiscal years: ${e.message}", cause = e)
        }
    }

    /**
     * Creates a new fiscal year for a client.
     * Maps to POST /api/v1/clients/:clientId/fiscal-years.
     */
    open suspend fun createFiscalYear(
        clientId: String,
        fiscalYearId: String,
        label: String,
        bearerTokenOverride: String? = null
    ): WorkerApiResult<FiscalYearItemDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/fiscal-years"
        val payload = JSONObject().apply {
            put("fiscalYearId", fiscalYearId)
            put("label", label)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val requestBuilder = Request.Builder().url(url).post(body)
        if (!bearerTokenOverride.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerTokenOverride")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    val fyObj = root.optJSONObject("fiscalYear") ?: root
                    WorkerApiResult.Success(FiscalYearItemDto.fromJson(fyObj))
                } else {
                    WorkerApiResult.Error(
                        message = "Failed to create fiscal year (HTTP ${response.code}): $bodyStr",
                        statusCode = response.code
                    )
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while creating fiscal year: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while creating fiscal year: ${e.message}", cause = e)
        }
    }

    /**
     * Lists users across the system (Owner / Admin only).
     */
    open suspend fun listUsers(
        query: String? = null,
        role: String? = null,
        status: String? = null
    ): WorkerApiResult<List<UserItemDto>> = withContext(Dispatchers.IO) {
        val qParams = mutableListOf<String>()
        if (!query.isNullOrBlank()) qParams.add("q=$query")
        if (!role.isNullOrBlank()) qParams.add("role=$role")
        if (!status.isNullOrBlank()) qParams.add("status=$status")
        val queryString = if (qParams.isNotEmpty()) "?" + qParams.joinToString("&") else ""
        val url = "$baseUrl/api/v1/users$queryString"
        val request = Request.Builder().url(url).get().build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    val arr = root.optJSONArray("users") ?: org.json.JSONArray()
                    val list = mutableListOf<UserItemDto>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        list.add(UserItemDto.fromJson(obj))
                    }
                    WorkerApiResult.Success(list)
                } else {
                    WorkerApiResult.Error("Failed to list users (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while listing users: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while listing users: ${e.message}", cause = e)
        }
    }

    /**
     * Lists all client memberships for a tenant client.
     */
    open suspend fun listClientMemberships(
        clientId: String
    ): WorkerApiResult<List<ClientMembershipItemDto>> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/memberships"
        val request = Request.Builder().url(url).get().build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    val arr = root.optJSONArray("memberships") ?: org.json.JSONArray()
                    val list = mutableListOf<ClientMembershipItemDto>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        list.add(ClientMembershipItemDto.fromJson(obj))
                    }
                    WorkerApiResult.Success(list)
                } else {
                    WorkerApiResult.Error("Failed to list memberships (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while listing memberships: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while listing memberships: ${e.message}", cause = e)
        }
    }

    /**
     * Assigns a user to a client tenant.
     */
    open suspend fun assignUserToClient(
        clientId: String,
        req: AssignUserRequestDto
    ): WorkerApiResult<String> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/memberships"
        val body = req.toJson().toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(body).build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    WorkerApiResult.Success("User assigned successfully")
                } else {
                    WorkerApiResult.Error("Failed to assign user (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while assigning user: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while assigning user: ${e.message}", cause = e)
        }
    }

    /**
     * Removes a user from a client tenant.
     */
    open suspend fun removeUserFromClient(
        clientId: String,
        userUid: String
    ): WorkerApiResult<String> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/memberships/$userUid"
        val request = Request.Builder().url(url).delete().build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    WorkerApiResult.Success("User removed successfully")
                } else {
                    WorkerApiResult.Error("Failed to remove user (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while removing user: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while removing user: ${e.message}", cause = e)
        }
    }

    /**
     * Submits an access request for a Client.
     */
    open suspend fun submitAccessRequest(
        clientId: String,
        fiscalYearId: String,
        reason: String
    ): WorkerApiResult<AccessRequestItemDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/access-requests"
        val payload = JSONObject().apply {
            put("clientId", clientId)
            put("fiscalYearId", fiscalYearId)
            put("reason", reason)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(body).build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    val reqObj = root.optJSONObject("accessRequest") ?: root
                    WorkerApiResult.Success(AccessRequestItemDto.fromJson(reqObj))
                } else {
                    WorkerApiResult.Error("Failed to submit access request (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while submitting access request: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while submitting access request: ${e.message}", cause = e)
        }
    }

    /**
     * Lists access requests for a Client (Admin/Owner).
     */
    open suspend fun listAccessRequestsForClient(
        clientId: String
    ): WorkerApiResult<List<AccessRequestItemDto>> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/access-requests"
        val request = Request.Builder().url(url).get().build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    val arr = root.optJSONArray("accessRequests") ?: org.json.JSONArray()
                    val list = mutableListOf<AccessRequestItemDto>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        list.add(AccessRequestItemDto.fromJson(obj))
                    }
                    WorkerApiResult.Success(list)
                } else {
                    WorkerApiResult.Error("Failed to list access requests (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while listing access requests: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while listing access requests: ${e.message}", cause = e)
        }
    }

    /**
     * Decides an access request (Approve / Reject).
     */
    open suspend fun decideAccessRequest(
        clientId: String,
        requestId: String,
        decision: String
    ): WorkerApiResult<AccessRequestItemDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/access-requests/$requestId/decide"
        val payload = JSONObject().apply {
            put("decision", decision)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(body).build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    val reqObj = root.optJSONObject("accessRequest") ?: root
                    WorkerApiResult.Success(AccessRequestItemDto.fromJson(reqObj))
                } else {
                    WorkerApiResult.Error("Failed to decide access request (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while deciding access request: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while deciding access request: ${e.message}", cause = e)
        }
    }

    /**
     * Imports a batch of FAR assets into Cloudflare D1 via Worker.
     * Enforces chunking into <= 250 items to stay safely below Worker payload limits.
     */
    open suspend fun importFarAssets(
        clientId: String,
        fiscalYearId: String,
        importBatchId: String,
        assets: List<FarAssetItemDto>
    ): WorkerApiResult<Int> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/far/import"
        var totalInserted = 0

        for (chunk in assets.chunked(250)) {
            val payload = JSONObject().apply {
                put("fiscalYearId", fiscalYearId)
                put("importBatchId", importBatchId)
                val arr = JSONArray()
                for (a in chunk) {
                    val item = JSONObject().apply {
                        put("assetKey", a.assetKey)
                        put("assetId", a.assetId)
                        put("sourceRow", a.sourceRow)
                        put("sourceWorksheet", a.sourceWorksheet)
                        put("sourceWorksheetIndex", a.sourceWorksheetIndex)
                        put("rawRowJson", a.rawRowJson)
                        put("searchText", a.searchText)
                    }
                    arr.put(item)
                }
                put("assets", arr)
            }

            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(url).post(body).build()

            try {
                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val root = JSONObject(bodyStr)
                        totalInserted += root.optInt("importedCount", chunk.size)
                    } else {
                        return@withContext WorkerApiResult.Error("FAR import chunk failed (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                    }
                }
            } catch (e: IOException) {
                return@withContext WorkerApiResult.Error("Network error during FAR import: ${e.message}", cause = e)
            } catch (e: Exception) {
                return@withContext WorkerApiResult.Error("Unexpected error during FAR import: ${e.message}", cause = e)
            }
        }

        WorkerApiResult.Success(totalInserted)
    }

    /**
     * Searches FAR assets with pagination and search query.
     */
    open suspend fun searchFarAssets(
        clientId: String,
        fiscalYearId: String,
        query: String = "",
        cursor: String? = null,
        limit: Int = 50,
        bearerTokenOverride: String? = null
    ): WorkerApiResult<FarSearchResultDto> = withContext(Dispatchers.IO) {
        val encQuery = URLEncoder.encode(query, "UTF-8")
        val encCursor = if (!cursor.isNullOrBlank()) "&cursor=" + URLEncoder.encode(cursor, "UTF-8") else ""
        val url = "$baseUrl/api/v1/clients/$clientId/far/assets?fiscalYearId=$fiscalYearId&query=$encQuery&limit=$limit$encCursor"
        val requestBuilder = Request.Builder().url(url).get()
        if (!bearerTokenOverride.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerTokenOverride")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    WorkerApiResult.Success(FarSearchResultDto.fromJson(root))
                } else {
                    WorkerApiResult.Error("Failed to search FAR assets (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while searching FAR assets: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while searching FAR assets: ${e.message}", cause = e)
        }
    }

    /**
     * Submits a daily sheet batch to Cloudflare Worker D1.
     */
    open suspend fun submitDailySheet(
        clientId: String,
        fiscalYearId: String,
        batchId: String,
        batchDate: String,
        items: List<Map<String, Any?>>
    ): WorkerApiResult<String> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/daily-sheets"
        val payload = JSONObject().apply {
            put("fiscalYearId", fiscalYearId)
            put("batchId", batchId)
            put("batchDate", batchDate)
            val arr = JSONArray()
            for (it in items) {
                arr.put(JSONObject(it))
            }
            put("items", arr)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .header("Idempotency-Key", batchId)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    WorkerApiResult.Success(batchId)
                } else {
                    WorkerApiResult.Error("Failed to submit daily sheet (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: Exception) {
            WorkerApiResult.Error("Error submitting daily sheet: ${e.message}", cause = e)
        }
    }

    /**
     * Lists daily sheets for a Client.
     */
    open suspend fun listDailySheets(
        clientId: String,
        status: String? = null,
        mine: Boolean = false,
        bearerTokenOverride: String? = null
    ): WorkerApiResult<List<DailySheetItemDto>> = withContext(Dispatchers.IO) {
        var url = "$baseUrl/api/v1/clients/$clientId/daily-sheets?mine=$mine"
        if (!status.isNullOrBlank()) {
            url += "&status=" + URLEncoder.encode(status, "UTF-8")
        }
        val requestBuilder = Request.Builder().url(url).get()
        if (!bearerTokenOverride.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerTokenOverride")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    val arr = root.optJSONArray("dailySheets") ?: JSONArray()
                    val list = mutableListOf<DailySheetItemDto>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        list.add(DailySheetItemDto.fromJson(obj))
                    }
                    WorkerApiResult.Success(list)
                } else {
                    WorkerApiResult.Error("Failed to list daily sheets (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: Exception) {
            WorkerApiResult.Error("Error listing daily sheets: ${e.message}", cause = e)
        }
    }

    /**
     * Retrieves daily sheet details including batch, submission, and counting records.
     * Maps to GET /api/v1/clients/:clientId/daily-sheets/:batchId.
     */
    open suspend fun getDailySheetDetail(
        clientId: String,
        batchId: String,
        bearerTokenOverride: String? = null
    ): WorkerApiResult<DailySheetDetailDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/daily-sheets/$batchId"
        val requestBuilder = Request.Builder().url(url).get()
        if (!bearerTokenOverride.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerTokenOverride")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    WorkerApiResult.Success(DailySheetDetailDto.fromJson(root))
                } else {
                    WorkerApiResult.Error("Failed to fetch daily sheet detail (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error("Network error while fetching daily sheet detail: ${e.message}", cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while fetching daily sheet detail: ${e.message}", cause = e)
        }
    }

    /**
     * Reviews and returns a daily sheet with remarks.
     */
    open suspend fun returnDailySheet(
        clientId: String,
        batchId: String,
        remarks: String
    ): WorkerApiResult<String> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/daily-sheets/$batchId/review"
        val payload = JSONObject().apply {
            put("remarks", remarks)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(body).build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    WorkerApiResult.Success("Returned with remarks")
                } else {
                    WorkerApiResult.Error("Failed to return daily sheet (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: Exception) {
            WorkerApiResult.Error("Error returning daily sheet: ${e.message}", cause = e)
        }
    }

    /**
     * Approves a daily sheet for master sync.
     */
    open suspend fun approveDailySheet(
        clientId: String,
        batchId: String,
        remarks: String = ""
    ): WorkerApiResult<String> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/daily-sheets/$batchId/approve"
        val payload = JSONObject().apply {
            if (remarks.isNotBlank()) put("remarks", remarks)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(body).build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    WorkerApiResult.Success("Approved")
                } else {
                    WorkerApiResult.Error("Failed to approve daily sheet (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: Exception) {
            WorkerApiResult.Error("Error approving daily sheet: ${e.message}", cause = e)
        }
    }


    /**
     * Gets master workbook sync state from Cloudflare Worker D1.
     */
    open suspend fun getMasterWorkbookState(
        clientId: String,
        fiscalYearId: String
    ): WorkerApiResult<MasterWorkbookStateDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/master-workbook/state?fiscalYearId=$fiscalYearId"
        val request = Request.Builder().url(url).get().build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    val stateObj = root.optJSONObject("state") ?: root
                    WorkerApiResult.Success(MasterWorkbookStateDto.fromJson(stateObj))
                } else {
                    WorkerApiResult.Error("Failed to get master workbook state (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: Exception) {
            WorkerApiResult.Error("Error getting master workbook state: ${e.message}", cause = e)
        }
    }

    /**
     * Acquires single-writer concurrency lease lock for Master Workbook sync.
     */
    open suspend fun acquireMasterSyncLease(
        clientId: String,
        fiscalYearId: String,
        jobId: String,
        batchId: String? = null,
        leaseDurationMs: Long = 60000
    ): WorkerApiResult<Boolean> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/master-workbook/acquire-lease"
        val payload = JSONObject().apply {
            put("fiscalYearId", fiscalYearId)
            put("jobId", jobId)
            if (!batchId.isNullOrBlank()) put("batchId", batchId)
            put("leaseDurationMs", leaseDurationMs)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(body).build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    WorkerApiResult.Success(true)
                } else {
                    WorkerApiResult.Error("Failed to acquire sync lease (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: Exception) {
            WorkerApiResult.Error("Error acquiring sync lease: ${e.message}", cause = e)
        }
    }

    /**
     * Releases single-writer concurrency lease lock for Master Workbook sync.
     */
    open suspend fun releaseMasterSyncLease(
        clientId: String,
        fiscalYearId: String,
        jobId: String,
        status: String,
        errorMessage: String? = null,
        fileChecksum: String? = null,
        driveFileId: String? = null,
        submissionId: String? = null
    ): WorkerApiResult<Boolean> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/master-workbook/release-lease"
        val payload = JSONObject().apply {
            put("fiscalYearId", fiscalYearId)
            put("jobId", jobId)
            put("status", status)
            if (!errorMessage.isNullOrBlank()) put("errorMessage", errorMessage)
            if (!fileChecksum.isNullOrBlank()) put("fileChecksum", fileChecksum)
            if (!driveFileId.isNullOrBlank()) put("driveFileId", driveFileId)
            if (!submissionId.isNullOrBlank()) put("submissionId", submissionId)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(body).build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    WorkerApiResult.Success(true)
                } else {
                    WorkerApiResult.Error("Failed to release sync lease (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: Exception) {
            WorkerApiResult.Error("Error releasing sync lease: ${e.message}", cause = e)
        }
    }

    /**
     * Permanently deletes a client and its shard data (System Owner only).
     */
    open suspend fun deleteClient(
        clientId: String,
        confirmClientCode: String,
        reason: String? = null
    ): WorkerApiResult<Unit> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/delete"
        val payload = JSONObject().apply {
            put("confirmClientCode", confirmClientCode)
            if (!reason.isNullOrBlank()) put("reason", reason)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(body).build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    WorkerApiResult.Success(Unit)
                } else {
                    WorkerApiResult.Error("Failed to permanently delete client (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: Exception) {
            WorkerApiResult.Error("Error permanently deleting client: ${e.message}", cause = e)
        }
    }

    /**
     * Permanently deletes a user account and purges user data (System Owner only).
     */
    open suspend fun deleteUser(
        targetUid: String,
        confirmEmailOrName: String,
        reason: String? = null
    ): WorkerApiResult<Unit> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/users/$targetUid/delete"
        val payload = JSONObject().apply {
            put("confirmEmailOrName", confirmEmailOrName)
            if (!reason.isNullOrBlank()) put("reason", reason)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(body).build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    WorkerApiResult.Success(Unit)
                } else {
                    WorkerApiResult.Error("Failed to permanently delete user (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: Exception) {
            WorkerApiResult.Error("Error permanently deleting user: ${e.message}", cause = e)
        }
    }

    /**
     * Updates a user's global role on Cloudflare Worker D1 (System Owner / Admin).
     * Maps to PATCH /api/v1/users/:uid/role
     */
    open suspend fun updateUserRole(
        targetUid: String,
        role: String
    ): WorkerApiResult<String> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/users/$targetUid/role"
        val payload = JSONObject().apply {
            put("role", role)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).patch(body).build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val msg = runCatching { JSONObject(bodyStr).optString("message", "User global role updated successfully") }.getOrDefault("User global role updated successfully")
                    WorkerApiResult.Success(msg)
                } else {
                    WorkerApiResult.Error("Failed to update user role (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error(formatNetworkErrorMessage("updating user role", e), cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Error updating user role: ${e.message}", cause = e)
        }
    }

    /**
     * Updates a user's account status ('Active' or 'Suspended') on Cloudflare Worker D1 (System Owner / Admin).
     * Maps to PATCH /api/v1/users/:uid/status
     */
    open suspend fun updateUserStatus(
        targetUid: String,
        status: String
    ): WorkerApiResult<String> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/users/$targetUid/status"
        val payload = JSONObject().apply {
            put("status", status)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).patch(body).build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val msg = runCatching { JSONObject(bodyStr).optString("message", "User status updated successfully") }.getOrDefault("User status updated successfully")
                    WorkerApiResult.Success(msg)
                } else {
                    WorkerApiResult.Error("Failed to update user status (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error(formatNetworkErrorMessage("updating user status", e), cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Error updating user status: ${e.message}", cause = e)
        }
    }

    /**
     * Updates a user's permissions and role within a client tenant on Cloudflare Worker D1 (Admin / Owner).
     * Maps to PATCH /api/v1/clients/:clientId/memberships/:uid
     */
    open suspend fun updateClientMembershipPermissions(
        clientId: String,
        targetUid: String,
        role: String = "Auditor",
        hasReviewAuthority: Boolean = false,
        canPublish: Boolean = false,
        canSyncDrive: Boolean = false
    ): WorkerApiResult<String> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/memberships/$targetUid"
        val payload = JSONObject().apply {
            put("role", role)
            put("hasReviewAuthority", hasReviewAuthority)
            put("canPublish", canPublish)
            put("canSyncDrive", canSyncDrive)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).patch(body).build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val msg = runCatching { JSONObject(bodyStr).optString("message", "Client membership updated successfully") }.getOrDefault("Client membership updated successfully")
                    WorkerApiResult.Success(msg)
                } else {
                    WorkerApiResult.Error("Failed to update client membership permissions (HTTP ${response.code}): $bodyStr", statusCode = response.code)
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error(formatNetworkErrorMessage("updating client membership permissions", e), cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Error updating client membership permissions: ${e.message}", cause = e)
        }
    }

    /**
     * Submits an unlisted asset ("Found Not In FAR") record to the client's D1 shard.
     * Maps to POST /api/v1/clients/:clientId/found-not-in-far.
     */
    open suspend fun submitFoundNotInFar(
        clientId: String,
        payload: SubmitFoundNotInFarRequestDto,
        bearerTokenOverride: String? = null
    ): WorkerApiResult<FoundNotInFarItemDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/clients/$clientId/found-not-in-far"
        val body = payload.toJson().toString().toRequestBody(JSON_MEDIA_TYPE)
        val requestBuilder = Request.Builder().url(url).post(body)
        if (!bearerTokenOverride.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerTokenOverride")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    val recObj = root.optJSONObject("record") ?: root
                    WorkerApiResult.Success(FoundNotInFarItemDto.fromJson(recObj))
                } else {
                    WorkerApiResult.Error(
                        message = "Failed to submit unlisted asset (HTTP ${response.code}): $bodyStr",
                        statusCode = response.code
                    )
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error(formatNetworkErrorMessage("submitting unlisted asset", e), cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while submitting unlisted asset: ${e.message}", cause = e)
        }
    }

    /**
     * Lists unlisted assets ("Found Not In FAR") for a client.
     * Maps to GET /api/v1/clients/:clientId/found-not-in-far.
     */
    open suspend fun listFoundNotInFar(
        clientId: String,
        fiscalYearId: String? = null,
        bearerTokenOverride: String? = null
    ): WorkerApiResult<List<FoundNotInFarItemDto>> = withContext(Dispatchers.IO) {
        val queryParam = if (!fiscalYearId.isNullOrBlank()) {
            "?fiscalYearId=" + java.net.URLEncoder.encode(fiscalYearId, "UTF-8")
        } else ""
        val url = "$baseUrl/api/v1/clients/$clientId/found-not-in-far$queryParam"
        val requestBuilder = Request.Builder().url(url).get()
        if (!bearerTokenOverride.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerTokenOverride")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = JSONObject(bodyStr)
                    val recordsArr = root.optJSONArray("records") ?: JSONArray()
                    val list = mutableListOf<FoundNotInFarItemDto>()
                    for (i in 0 until recordsArr.length()) {
                        val obj = recordsArr.optJSONObject(i) ?: continue
                        list.add(FoundNotInFarItemDto.fromJson(obj))
                    }
                    WorkerApiResult.Success(list)
                } else {
                    WorkerApiResult.Error(
                        message = "Failed to list unlisted assets (HTTP ${response.code}): $bodyStr",
                        statusCode = response.code
                    )
                }
            }
        } catch (e: IOException) {
            WorkerApiResult.Error(formatNetworkErrorMessage("listing unlisted assets", e), cause = e)
        } catch (e: Exception) {
            WorkerApiResult.Error("Unexpected error while listing unlisted assets: ${e.message}", cause = e)
        }
    }
}
