package com.example.data.offline

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Lifecycle states for offline dynamic submissions according to Section 9.
 */
object DynamicSyncState {
    const val SAVED_LOCALLY = "Saved Locally"
    const val WAITING_FOR_INTERNET = "Waiting for Internet"
    const val PENDING_SYNC = "Pending Sync"
    const val SYNCING = "Syncing"
    const val SYNCED = "Synced"
    const val AUTHENTICATION_REQUIRED = "Authentication Required"
    const val PERMISSION_BLOCKED = "Permission Blocked"
    const val VALIDATION_FAILED = "Validation Failed"
    const val CONFLICT_REQUIRES_REVIEW = "Conflict Requires Review"
    const val SYNC_FAILED_RETRY_AVAILABLE = "Sync Failed — Retry Available"

    fun isActionRequired(status: String): Boolean {
        return status in listOf(
            AUTHENTICATION_REQUIRED,
            PERMISSION_BLOCKED,
            VALIDATION_FAILED,
            CONFLICT_REQUIRES_REVIEW
        )
    }

    fun isPending(status: String): Boolean {
        return status in listOf(
            SAVED_LOCALLY,
            WAITING_FOR_INTERNET,
            PENDING_SYNC,
            SYNCING,
            SYNC_FAILED_RETRY_AVAILABLE
        )
    }
}

/**
 * Complete, durable dynamic counting submission record.
 * Preserves all lifecycle metadata, immutable field IDs, text Asset ID, and idempotency key.
 */
data class DynamicSubmissionRecord(
    val localSubmissionId: String = UUID.randomUUID().toString(),
    val clientId: String,
    val auditCycleId: String,
    val assetKey: String,
    val assetId: String, // Strictly preserved as TEXT with leading zeroes
    val schemaVersionId: String,
    val countDate: String,
    val batchId: String? = null,
    val fieldValues: Map<String, Any?>, // Keyed strictly by immutable fieldId
    val auditorUid: String,
    val auditorDisplayName: String = "",
    val idempotencyKey: String = UUID.randomUUID().toString(), // Generated once, never regenerated on retry
    val createdAtMillis: Long = System.currentTimeMillis(),
    val syncStatus: String = DynamicSyncState.SAVED_LOCALLY,
    val retryCount: Int = 0,
    val lastAttemptMillis: Long? = null,
    val lastErrorCode: Int? = null,
    val lastErrorMessage: String? = null,
    val serverRecordId: String? = null,
    val isIdempotentReplay: Boolean = false,
    val leaseOwnerId: String? = null,
    val leaseExpiresAtMillis: Long? = null,
    val syncStartedAtMillis: Long? = null
) {
    /**
     * Unique key for database persistence: "$clientId:$auditCycleId:$assetKey"
     */
    val recordKey: String
        get() = "$clientId:$auditCycleId:$assetKey"

    fun toJson(): String {
        val root = JSONObject()
        root.put("localSubmissionId", localSubmissionId)
        root.put("clientId", clientId)
        root.put("auditCycleId", auditCycleId)
        root.put("assetKey", assetKey)
        root.put("assetId", assetId)
        root.put("schemaVersionId", schemaVersionId)
        root.put("countDate", countDate)
        if (batchId != null) root.put("batchId", batchId)
        root.put("auditorUid", auditorUid)
        root.put("auditorDisplayName", auditorDisplayName)
        root.put("idempotencyKey", idempotencyKey)
        root.put("createdAtMillis", createdAtMillis)
        root.put("syncStatus", syncStatus)
        root.put("retryCount", retryCount)
        if (lastAttemptMillis != null) root.put("lastAttemptMillis", lastAttemptMillis)
        if (lastErrorCode != null) root.put("lastErrorCode", lastErrorCode)
        if (lastErrorMessage != null) root.put("lastErrorMessage", lastErrorMessage)
        if (serverRecordId != null) root.put("serverRecordId", serverRecordId)
        root.put("isIdempotentReplay", isIdempotentReplay)
        if (leaseOwnerId != null) root.put("leaseOwnerId", leaseOwnerId)
        if (leaseExpiresAtMillis != null) root.put("leaseExpiresAtMillis", leaseExpiresAtMillis)
        if (syncStartedAtMillis != null) root.put("syncStartedAtMillis", syncStartedAtMillis)

        // Serialize field values: preserve multi-select as JSONArray
        val valuesObj = JSONObject()
        for ((fieldId, value) in fieldValues) {
            when (value) {
                null -> valuesObj.put(fieldId, JSONObject.NULL)
                is List<*> -> {
                    val arr = JSONArray()
                    for (item in value) {
                        arr.put(item)
                    }
                    valuesObj.put(fieldId, arr)
                }
                is JSONArray -> valuesObj.put(fieldId, value)
                is Number -> valuesObj.put(fieldId, value)
                is Boolean -> valuesObj.put(fieldId, value)
                else -> valuesObj.put(fieldId, value.toString())
            }
        }
        root.put("fieldValues", valuesObj)

        return root.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): DynamicSubmissionRecord {
            val root = JSONObject(jsonStr)
            val valuesMap = mutableMapOf<String, Any?>()
            val valuesObj = root.optJSONObject("fieldValues")
            if (valuesObj != null) {
                val keys = valuesObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (valuesObj.isNull(k)) {
                        valuesMap[k] = null
                    } else {
                        val v = valuesObj.get(k)
                        if (v is JSONArray) {
                            val list = mutableListOf<String>()
                            for (i in 0 until v.length()) {
                                list.add(v.getString(i))
                            }
                            valuesMap[k] = list
                        } else {
                            valuesMap[k] = v
                        }
                    }
                }
            }

            return DynamicSubmissionRecord(
                localSubmissionId = root.optString("localSubmissionId", UUID.randomUUID().toString()),
                clientId = root.optString("clientId", ""),
                auditCycleId = root.optString("auditCycleId", ""),
                assetKey = root.optString("assetKey", ""),
                assetId = root.optString("assetId", ""),
                schemaVersionId = root.optString("schemaVersionId", ""),
                countDate = root.optString("countDate", ""),
                batchId = if (root.has("batchId") && !root.isNull("batchId")) root.getString("batchId") else null,
                fieldValues = valuesMap,
                auditorUid = root.optString("auditorUid", ""),
                auditorDisplayName = root.optString("auditorDisplayName", ""),
                idempotencyKey = root.optString("idempotencyKey", UUID.randomUUID().toString()),
                createdAtMillis = root.optLong("createdAtMillis", System.currentTimeMillis()),
                syncStatus = root.optString("syncStatus", DynamicSyncState.SAVED_LOCALLY),
                retryCount = root.optInt("retryCount", 0),
                lastAttemptMillis = if (root.has("lastAttemptMillis") && !root.isNull("lastAttemptMillis")) root.getLong("lastAttemptMillis") else null,
                lastErrorCode = if (root.has("lastErrorCode") && !root.isNull("lastErrorCode")) root.getInt("lastErrorCode") else null,
                lastErrorMessage = if (root.has("lastErrorMessage") && !root.isNull("lastErrorMessage")) root.getString("lastErrorMessage") else null,
                serverRecordId = if (root.has("serverRecordId") && !root.isNull("serverRecordId")) root.getString("serverRecordId") else null,
                isIdempotentReplay = root.optBoolean("isIdempotentReplay", false),
                leaseOwnerId = if (root.has("leaseOwnerId") && !root.isNull("leaseOwnerId")) root.getString("leaseOwnerId") else null,
                leaseExpiresAtMillis = if (root.has("leaseExpiresAtMillis") && !root.isNull("leaseExpiresAtMillis")) root.getLong("leaseExpiresAtMillis") else null,
                syncStartedAtMillis = if (root.has("syncStartedAtMillis") && !root.isNull("syncStartedAtMillis")) root.getLong("syncStartedAtMillis") else null
            )
        }
    }
}
