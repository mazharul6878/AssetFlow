package com.example.data.repository

import com.example.data.network.DynamicSchemaDto
import com.example.data.network.FarAssetBaselineDto
import com.example.data.network.WorkerApiClient
import com.example.data.network.WorkerApiResult
import com.example.data.offline.CachedFarBaselineEntity
import com.example.data.offline.CachedSchemaEntity
import com.example.data.offline.DynamicOfflineCacheDao
import org.json.JSONObject

/**
 * Offline-first repository caching published schemas and FAR asset baselines.
 * Ensures the auditor can view, search, and audit assets 100% offline.
 */
class DynamicOfflineCacheRepository(
    private val apiClient: WorkerApiClient,
    private val cacheDao: DynamicOfflineCacheDao
) {

    /**
     * Retrieves the schema for an Audit Cycle.
     * Follows offline-first policy:
     * 1. Returns cached schema if available and not forced to refresh.
     * 2. Attempts network refresh if forced or if no cache exists.
     * 3. Falls back safely to cache if network request fails.
     */
    suspend fun getSchemaForCycle(
        clientId: String,
        cycleId: String,
        forceRefresh: Boolean = false
    ): WorkerApiResult<DynamicSchemaDto?> {
        val cached = cacheDao.getCachedSchema(clientId, cycleId)

        if (cached != null && !forceRefresh) {
            return try {
                val dto = DynamicSchemaDto.fromJson(JSONObject(cached.schemaJson))
                WorkerApiResult.Success(dto)
            } catch (e: Exception) {
                // If cached JSON corrupted, fallback to network
                fetchAndCacheSchema(clientId, cycleId)
            }
        }

        val networkResult = fetchAndCacheSchema(clientId, cycleId)
        return if (networkResult is WorkerApiResult.Success) {
            networkResult
        } else {
            // Offline fallback: if network failed but we have a cache, return cache!
            if (cached != null) {
                try {
                    val dto = DynamicSchemaDto.fromJson(JSONObject(cached.schemaJson))
                    WorkerApiResult.Success(dto)
                } catch (e: Exception) {
                    networkResult
                }
            } else {
                networkResult
            }
        }
    }

    private suspend fun fetchAndCacheSchema(
        clientId: String,
        cycleId: String
    ): WorkerApiResult<DynamicSchemaDto?> {
        val res = apiClient.getSchemaForCycle(clientId, cycleId)
        if (res is WorkerApiResult.Success && res.data != null) {
            val schema = res.data
            val entity = CachedSchemaEntity(
                cacheKey = "$clientId:$cycleId",
                clientId = clientId,
                auditCycleId = cycleId,
                schemaVersionId = schema.schemaVersionId,
                versionNumber = schema.versionNumber,
                status = schema.status,
                sourceWorkbookName = schema.sourceWorkbookName,
                uniqueAssetKeyHeader = schema.uniqueAssetKeyHeader,
                schemaJson = schema.toJson().toString(),
                cachedAtMillis = System.currentTimeMillis()
            )
            cacheDao.upsertCachedSchema(entity)
        }
        return res
    }

    /**
     * Evicts the cached schema for a specific client and audit cycle, forcing the next
     * access to refresh the published schema version from the Worker/D1 backend.
     */
    suspend fun invalidateSchemaCache(clientId: String, cycleId: String) {
        cacheDao.deleteCachedSchema(clientId, cycleId)
    }

    /**
     * Retrieves the FAR baseline for an asset.
     * Caches downloaded FAR assets locally.
     */
    suspend fun getFarAsset(
        clientId: String,
        cycleId: String,
        assetKey: String,
        forceRefresh: Boolean = false
    ): WorkerApiResult<FarAssetBaselineDto?> {
        val cached = cacheDao.getCachedFarBaseline(clientId, cycleId, assetKey)

        if (cached != null && !forceRefresh) {
            return try {
                val dto = FarAssetBaselineDto.fromJson(JSONObject(cached.baselineDataJson))
                WorkerApiResult.Success(dto)
            } catch (e: Exception) {
                fetchAndCacheFarAsset(clientId, cycleId, assetKey)
            }
        }

        val networkResult = fetchAndCacheFarAsset(clientId, cycleId, assetKey)
        return if (networkResult is WorkerApiResult.Success) {
            networkResult
        } else {
            if (cached != null) {
                try {
                    val dto = FarAssetBaselineDto.fromJson(JSONObject(cached.baselineDataJson))
                    WorkerApiResult.Success(dto)
                } catch (e: Exception) {
                    networkResult
                }
            } else {
                networkResult
            }
        }
    }

    private suspend fun fetchAndCacheFarAsset(
        clientId: String,
        cycleId: String,
        assetKey: String
    ): WorkerApiResult<FarAssetBaselineDto?> {
        val res = apiClient.getFarAsset(clientId, cycleId, assetKey)
        if (res is WorkerApiResult.Success && res.data != null) {
            val asset = res.data
            val entity = CachedFarBaselineEntity(
                cacheKey = "$clientId:$cycleId:$assetKey",
                clientId = clientId,
                auditCycleId = cycleId,
                assetKey = asset.assetKey,
                assetId = asset.assetId,
                assetDescription = asset.assetDescription,
                location = asset.location,
                department = asset.department,
                baselineDataJson = asset.toJson().toString(),
                rawRowJson = JSONObject(asset.rawRowData as Map<*, *>).toString(),
                cachedAtMillis = System.currentTimeMillis()
            )
            cacheDao.upsertCachedFarBaseline(entity)
        }
        return res
    }

    /**
     * Pre-populates the FAR baseline cache for a cycle (e.g. on bulk download).
     */
    suspend fun cacheFarBaselines(
        clientId: String,
        cycleId: String,
        assets: List<FarAssetBaselineDto>
    ) {
        val entities = assets.map { asset ->
            CachedFarBaselineEntity(
                cacheKey = "$clientId:$cycleId:${asset.assetKey}",
                clientId = clientId,
                auditCycleId = cycleId,
                assetKey = asset.assetKey,
                assetId = asset.assetId,
                assetDescription = asset.assetDescription,
                location = asset.location,
                department = asset.department,
                baselineDataJson = asset.toJson().toString(),
                rawRowJson = JSONObject(asset.rawRowData as Map<*, *>).toString(),
                cachedAtMillis = System.currentTimeMillis()
            )
        }
        cacheDao.upsertCachedFarBaselines(entities)
    }
}
