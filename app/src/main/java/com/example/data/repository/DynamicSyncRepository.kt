package com.example.data.repository

import android.content.Context
import com.example.data.network.CountingSubmissionDto
import com.example.data.network.DynamicSchemaDto
import com.example.data.network.WorkerApiClient
import com.example.data.network.WorkerApiResult
import com.example.data.offline.DynamicSubmissionDaoAdapter
import com.example.data.offline.DynamicSubmissionRecord
import com.example.data.offline.DynamicSyncState
import com.example.data.worker.DynamicSyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Result of saving and submitting a dynamic count record.
 */
sealed class DynamicSyncResult {
    data class SyncedOnline(val recordId: String, val isReplay: Boolean) : DynamicSyncResult()
    data class SavedOffline(val status: String, val submissionId: String) : DynamicSyncResult()
    data class SyncFailedRetryable(val message: String, val statusCode: Int?) : DynamicSyncResult()
    data class ActionRequired(val status: String, val message: String, val statusCode: Int?) : DynamicSyncResult()
    data class DatabaseUnavailable(val message: String) : DynamicSyncResult()
}

/**
 * Queue statistics for the current Client and Audit Cycle.
 */
data class DynamicQueueStats(
    val totalPending: Int,
    val totalSynced: Int,
    val totalActionRequired: Int,
    val lastSyncTimestamp: Long
)

/**
 * High-level repository orchestrating offline-first dynamic count verification:
 * 1. Local validation against cached pinned schema.
 * 2. Idempotency key preservation (generated once, never regenerated on retry).
 * 3. Immediate local Room persistence before network calls.
 * 4. Background or immediate network synchronization.
 * 5. Full lifecycle status updates and multi-client isolation.
 */
class DynamicSyncRepository(
    private val daoAdapter: DynamicSubmissionDaoAdapter,
    private val apiClient: WorkerApiClient = WorkerApiClient(),
    private val offlineCacheRepository: DynamicOfflineCacheRepository? = null,
    private val context: Context? = null
) {
    /**
     * Retrieves an existing dynamic submission by its record key.
     */
    suspend fun getSubmission(recordKey: String): DynamicSubmissionRecord? {
        if (context != null && !com.example.data.offline.OfflineDatabaseGate.isAvailable(context)) {
            return null
        }
        return daoAdapter.getDynamicSubmission(recordKey)
    }

    /**
     * Executes the strict persistence-before-network workflow for a dynamic submission.
     */
    suspend fun saveAndSubmit(
        submission: DynamicSubmissionRecord,
        attemptImmediateNetwork: Boolean = true
    ): DynamicSyncResult {
        if (context != null && !com.example.data.offline.OfflineDatabaseGate.isAvailable(context)) {
            return DynamicSyncResult.DatabaseUnavailable(
                com.example.data.offline.OfflineDatabaseStatus.USER_RECOVERY_MESSAGE
            )
        }
        // Reuse existing idempotency key if this record was previously saved locally
        val existing = daoAdapter.getDynamicSubmission(submission.recordKey)
        val finalSubmission = if (existing != null) {
            submission.copy(
                idempotencyKey = existing.idempotencyKey,
                localSubmissionId = existing.localSubmissionId,
                createdAtMillis = existing.createdAtMillis
            )
        } else {
            submission
        }

        // 1. Immediately persist locally to Room before any network call
        daoAdapter.upsertDynamicSubmission(finalSubmission.copy(syncStatus = DynamicSyncState.SAVED_LOCALLY))

        // 2. If immediate network is not requested or not possible, schedule background sync
        if (!attemptImmediateNetwork) {
            daoAdapter.updateSyncStatus(finalSubmission.recordKey, DynamicSyncState.WAITING_FOR_INTERNET)
            if (context != null) {
                DynamicSyncScheduler.scheduleSync(context, clientId = finalSubmission.clientId, cycleId = finalSubmission.auditCycleId)
            }
            return DynamicSyncResult.SavedOffline(DynamicSyncState.WAITING_FOR_INTERNET, finalSubmission.localSubmissionId)
        }

        // 3. Attempt immediate online submission
        daoAdapter.updateSyncStatus(finalSubmission.recordKey, DynamicSyncState.SYNCING)

        val submissionDto = CountingSubmissionDto(
            assetKey = finalSubmission.assetKey,
            schemaVersionId = finalSubmission.schemaVersionId,
            countDate = finalSubmission.countDate,
            batchId = finalSubmission.batchId,
            idempotencyKey = finalSubmission.idempotencyKey,
            values = finalSubmission.fieldValues
        )

        val apiResult = apiClient.submitCount(
            clientId = finalSubmission.clientId,
            cycleId = finalSubmission.auditCycleId,
            submission = submissionDto
        )

        return when (apiResult) {
            is WorkerApiResult.Success -> {
                daoAdapter.updateSyncStatus(
                    recordKey = finalSubmission.recordKey,
                    status = DynamicSyncState.SYNCED,
                    serverRecordId = apiResult.data.recordId,
                    isIdempotentReplay = apiResult.data.isIdempotentReplay
                )
                DynamicSyncResult.SyncedOnline(apiResult.data.recordId, apiResult.data.isIdempotentReplay)
            }

            is WorkerApiResult.Error -> {
                val statusCode = apiResult.statusCode
                when (statusCode) {
                    401 -> {
                        daoAdapter.updateSyncStatus(
                            recordKey = finalSubmission.recordKey,
                            status = DynamicSyncState.AUTHENTICATION_REQUIRED,
                            errorCode = 401,
                            errorMessage = apiResult.message
                        )
                        DynamicSyncResult.ActionRequired(DynamicSyncState.AUTHENTICATION_REQUIRED, apiResult.message, 401)
                    }

                    403 -> {
                        daoAdapter.updateSyncStatus(
                            recordKey = finalSubmission.recordKey,
                            status = DynamicSyncState.PERMISSION_BLOCKED,
                            errorCode = 403,
                            errorMessage = apiResult.message
                        )
                        DynamicSyncResult.ActionRequired(DynamicSyncState.PERMISSION_BLOCKED, apiResult.message, 403)
                    }

                    400, 422 -> {
                        daoAdapter.updateSyncStatus(
                            recordKey = finalSubmission.recordKey,
                            status = DynamicSyncState.VALIDATION_FAILED,
                            errorCode = statusCode,
                            errorMessage = apiResult.message
                        )
                        DynamicSyncResult.ActionRequired(DynamicSyncState.VALIDATION_FAILED, apiResult.message, statusCode)
                    }

                    409 -> {
                        daoAdapter.updateSyncStatus(
                            recordKey = finalSubmission.recordKey,
                            status = DynamicSyncState.CONFLICT_REQUIRES_REVIEW,
                            errorCode = 409,
                            errorMessage = apiResult.message
                        )
                        DynamicSyncResult.ActionRequired(DynamicSyncState.CONFLICT_REQUIRES_REVIEW, apiResult.message, 409)
                    }

                    429 -> {
                        daoAdapter.updateSyncStatus(
                            recordKey = finalSubmission.recordKey,
                            status = DynamicSyncState.SYNC_FAILED_RETRY_AVAILABLE,
                            errorCode = 429,
                            errorMessage = apiResult.message
                        )
                        if (context != null) {
                            DynamicSyncScheduler.scheduleSync(context, clientId = finalSubmission.clientId, cycleId = finalSubmission.auditCycleId)
                        }
                        DynamicSyncResult.SyncFailedRetryable(apiResult.message, 429)
                    }

                    else -> {
                        // Transient server or network error: schedule WorkManager retry
                        daoAdapter.updateSyncStatus(
                            recordKey = finalSubmission.recordKey,
                            status = DynamicSyncState.WAITING_FOR_INTERNET,
                            errorCode = statusCode,
                            errorMessage = apiResult.message
                        )
                        if (context != null) {
                            DynamicSyncScheduler.scheduleSync(context, clientId = finalSubmission.clientId, cycleId = finalSubmission.auditCycleId)
                        }
                        DynamicSyncResult.SyncFailedRetryable(apiResult.message, statusCode)
                    }
                }
            }
        }
    }

    /**
     * Executes the strict persistence order from raw parameters:
     * 1. Validate inputs against published schema.
     * 2. Generate or reuse idempotency key.
     * 3. Serialize and persist to local Room storage before ANY network call.
     * 4. Confirm durable local persistence.
     * 5. If requested, attempt immediate network submission; else schedule WorkManager sync.
     */
    suspend fun saveAndSubmitCount(
        context: Context?,
        clientId: String,
        cycleId: String,
        assetKey: String,
        assetId: String, // Preserved as text with leading zeroes
        schema: DynamicSchemaDto,
        fieldValues: Map<String, Any?>,
        countDate: String,
        batchId: String? = null,
        auditorUid: String,
        auditorDisplayName: String = "",
        attemptImmediateNetwork: Boolean = true
    ): DynamicSubmissionRecord {
        // 1. Validate required fields locally
        val validationErrors = mutableListOf<String>()
        for (field in schema.fields) {
            if (field.isRequired && field.inputMode != "FIXED_IMPORTED") {
                val value = fieldValues[field.fieldId]
                if (value == null || (value is String && value.isBlank()) || (value is List<*> && value.isEmpty())) {
                    validationErrors.add(field.displayLabel.ifBlank { field.fieldKey })
                }
            }
        }

        val initialStatus = if (validationErrors.isNotEmpty()) {
            DynamicSyncState.VALIDATION_FAILED
        } else {
            DynamicSyncState.SAVED_LOCALLY
        }

        val existing = daoAdapter.getDynamicSubmission("$clientId:$cycleId:$assetKey")
        val idempotencyKey = existing?.idempotencyKey ?: UUID.randomUUID().toString()

        val submission = DynamicSubmissionRecord(
            localSubmissionId = existing?.localSubmissionId ?: UUID.randomUUID().toString(),
            clientId = clientId,
            auditCycleId = cycleId,
            assetKey = assetKey,
            assetId = assetId,
            schemaVersionId = schema.schemaVersionId,
            countDate = countDate,
            batchId = batchId,
            fieldValues = fieldValues,
            auditorUid = auditorUid,
            auditorDisplayName = auditorDisplayName,
            idempotencyKey = idempotencyKey,
            createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis(),
            syncStatus = initialStatus,
            lastErrorMessage = if (validationErrors.isNotEmpty()) {
                "Missing required fields: ${validationErrors.joinToString(", ")}"
            } else null
        )

        // 2. Durably persist to Room BEFORE any network call
        daoAdapter.upsertDynamicSubmission(submission)

        // If local validation fails, never attempt network call
        if (validationErrors.isNotEmpty()) {
            return submission
        }

        // 3. Schedule WorkManager sync if context is provided
        if (context != null) {
            DynamicSyncScheduler.scheduleSync(context, clientId = clientId, cycleId = cycleId)
        }

        // 4. Attempt immediate network submission if requested
        if (attemptImmediateNetwork) {
            daoAdapter.updateSyncStatus(submission.recordKey, DynamicSyncState.SYNCING)

            val submissionDto = CountingSubmissionDto(
                assetKey = assetKey,
                schemaVersionId = schema.schemaVersionId,
                countDate = countDate,
                batchId = batchId,
                idempotencyKey = idempotencyKey,
                values = fieldValues
            )

            val apiResult = apiClient.submitCount(
                clientId = clientId,
                cycleId = cycleId,
                submission = submissionDto
            )

            when (apiResult) {
                is WorkerApiResult.Success -> {
                    daoAdapter.updateSyncStatus(
                        recordKey = submission.recordKey,
                        status = DynamicSyncState.SYNCED,
                        serverRecordId = apiResult.data.recordId,
                        isIdempotentReplay = apiResult.data.isIdempotentReplay
                    )
                    return submission.copy(
                        syncStatus = DynamicSyncState.SYNCED,
                        serverRecordId = apiResult.data.recordId,
                        isIdempotentReplay = apiResult.data.isIdempotentReplay
                    )
                }

                is WorkerApiResult.Error -> {
                    val status = when (apiResult.statusCode) {
                        401 -> DynamicSyncState.AUTHENTICATION_REQUIRED
                        403 -> DynamicSyncState.PERMISSION_BLOCKED
                        400, 422 -> DynamicSyncState.VALIDATION_FAILED
                        409 -> DynamicSyncState.CONFLICT_REQUIRES_REVIEW
                        429 -> DynamicSyncState.SYNC_FAILED_RETRY_AVAILABLE
                        else -> DynamicSyncState.WAITING_FOR_INTERNET
                    }
                    daoAdapter.updateSyncStatus(
                        recordKey = submission.recordKey,
                        status = status,
                        errorCode = apiResult.statusCode,
                        errorMessage = apiResult.message
                    )
                    return submission.copy(
                        syncStatus = status,
                        lastErrorCode = apiResult.statusCode,
                        lastErrorMessage = apiResult.message
                    )
                }
            }
        }

        return submission
    }

    /**
     * Observes queue statistics for the given Client and Cycle.
     */
    fun observeQueueStats(clientId: String, cycleId: String): Flow<DynamicQueueStats> {
        return daoAdapter.observeAllDynamicSubmissions(clientId, cycleId).map { records ->
            var pending = 0
            var synced = 0
            var actionRequired = 0
            var maxTimestamp = 0L

            for (r in records) {
                if (r.createdAtMillis > maxTimestamp) maxTimestamp = r.createdAtMillis
                if (r.syncStatus == DynamicSyncState.SYNCED) {
                    synced++
                } else if (DynamicSyncState.isActionRequired(r.syncStatus)) {
                    actionRequired++
                } else {
                    pending++
                }
            }

            DynamicQueueStats(
                totalPending = pending,
                totalSynced = synced,
                totalActionRequired = actionRequired,
                lastSyncTimestamp = maxTimestamp
            )
        }
    }

    /**
     * Resets a failed or action-required record back to PENDING_SYNC for manual retry.
     * Starts a fresh bounded retry cycle (resetting retryCount to 0) while strictly preserving
     * the original idempotency key, auditor attribution, and payload values.
     */
    suspend fun retrySubmission(recordKey: String, context: Context?) {
        daoAdapter.resetRetryCount(recordKey)
        val rec = daoAdapter.getDynamicSubmission(recordKey)
        if (context != null && rec != null) {
            DynamicSyncScheduler.scheduleSync(context, clientId = rec.clientId, cycleId = rec.auditCycleId)
        }
    }
}
