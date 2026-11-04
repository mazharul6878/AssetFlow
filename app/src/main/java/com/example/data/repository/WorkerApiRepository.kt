package com.example.data.repository

import com.example.data.network.CountingSubmissionDto
import com.example.data.network.CountingSubmissionResultDto
import com.example.data.network.DynamicSchemaDto
import com.example.data.network.FarAssetBaselineDto
import com.example.data.network.WorkerApiClient
import com.example.data.network.WorkerApiResult

data class DynamicAuditScreenData(
    val schema: DynamicSchemaDto?,
    val farAsset: FarAssetBaselineDto?
)

/**
 * Repository mediating between the UI/ViewModel and the Cloudflare Worker API.
 */
class WorkerApiRepository(
    private val apiClient: WorkerApiClient
) {
    private var cachedSchema: DynamicSchemaDto? = null

    /**
     * Loads the pinned schema and FAR baseline information for an asset.
     */
    suspend fun loadAuditScreenData(
        clientId: String,
        cycleId: String,
        assetKey: String
    ): WorkerApiResult<DynamicAuditScreenData> {
        val schemaResult = if (cachedSchema != null && cachedSchema?.clientId == clientId && cachedSchema?.fiscalYearId == cycleId) {
            WorkerApiResult.Success(cachedSchema)
        } else {
            val res = apiClient.getSchemaForCycle(clientId, cycleId)
            if (res is WorkerApiResult.Success && res.data != null) {
                cachedSchema = res.data
            }
            res
        }

        if (schemaResult is WorkerApiResult.Error) {
            return WorkerApiResult.Error(schemaResult.message, schemaResult.statusCode, cause = schemaResult.cause)
        }

        val schema = (schemaResult as WorkerApiResult.Success).data
        val farResult = apiClient.getFarAsset(clientId, cycleId, assetKey)
        val farAsset = (farResult as? WorkerApiResult.Success)?.data

        return WorkerApiResult.Success(DynamicAuditScreenData(schema, farAsset))
    }

    /**
     * Submits a counting verification record.
     */
    suspend fun submitCount(
        clientId: String,
        cycleId: String,
        submission: CountingSubmissionDto
    ): WorkerApiResult<CountingSubmissionResultDto> {
        return apiClient.submitCount(clientId, cycleId, submission)
    }

    fun clearCache() {
        cachedSchema = null
    }
}
