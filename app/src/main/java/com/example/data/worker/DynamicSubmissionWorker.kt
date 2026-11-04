package com.example.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.network.CountingSubmissionDto
import com.example.data.network.WorkerApiClient
import com.example.data.network.WorkerApiResult
import com.example.data.network.WorkerTokenProvider
import com.example.data.offline.DynamicSubmissionDaoAdapter
import com.example.data.offline.DynamicSyncState
import com.example.data.offline.OfflineSyncDatabase

/**
 * Background synchronization worker managing durable at-least-once dynamic count submission
 * using the Cloudflare Worker API.
 * Uses idempotency keys to guarantee zero duplicate logical records on retries.
 */
class DynamicSubmissionWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val apiClient: WorkerApiClient = WorkerApiClient(),
    private val daoAdapter: DynamicSubmissionDaoAdapter = DynamicSubmissionDaoAdapter(
        OfflineSyncDatabase.getInstance(appContext).offlineSyncDao(),
        OfflineSyncDatabase.getInstance(appContext)
    ),
    private val tokenProvider: WorkerTokenProvider? = null
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val UNIQUE_WORK_NAME = "dynamic_submission_sync"
        const val MAX_RETRY_ATTEMPTS = 5
        const val STALE_SYNCING_THRESHOLD_MILLIS = 30_000L
    }

    override suspend fun doWork(): Result {
        if (!com.example.data.offline.OfflineDatabaseGate.isAvailable(applicationContext)) {
            return Result.failure()
        }
        val targetClientId = inputData.getString("clientId")
        val targetCycleId = inputData.getString("cycleId")
        val workerSessionId = id.toString()

        SyncCoordinator.registerWorkerStart(workerSessionId, targetClientId, targetCycleId)
        var hasRetryableFailure = false

        try {
            // 1. Recover any records left in SYNCING state from previous interrupted processes or crashes
            // Does not steal leases owned by actively running workers
            daoAdapter.recoverStaleSyncingRecords(
                clientId = targetClientId,
                cycleId = targetCycleId,
                currentWorkerSessionId = workerSessionId,
                staleThresholdMillis = STALE_SYNCING_THRESHOLD_MILLIS
            )

            val processedKeysThisRun = mutableSetOf<String>()

            // 2. Safe drain loop: continues processing pending submissions including any queued during sync
            while (true) {
                val loopStartGen = SyncCoordinator.getRequestGeneration(targetClientId, targetCycleId)

                if (isStopped) {
                    return Result.retry()
                }

                val pending = daoAdapter.getPendingSubmissions(targetClientId, targetCycleId)
                    .filter { it.recordKey !in processedKeysThisRun }

                if (pending.isEmpty()) {
                    // Check if new sync requests were enqueued during our drain check
                    if (SyncCoordinator.hasNewRequestsSince(targetClientId, targetCycleId, loopStartGen)) {
                        continue
                    }
                    break
                }

                for (sub in pending) {
                    if (isStopped) {
                        daoAdapter.updateSyncStatus(
                            recordKey = sub.recordKey,
                            status = DynamicSyncState.SYNC_FAILED_RETRY_AVAILABLE,
                            errorMessage = "Sync stopped by OS. Queued for background retry.",
                            workerSessionId = workerSessionId
                        )
                        return Result.retry()
                    }

                    // Check retry attempt limits: never silently delete records!
                    if (sub.retryCount >= MAX_RETRY_ATTEMPTS) {
                        daoAdapter.updateSyncStatus(
                            recordKey = sub.recordKey,
                            status = DynamicSyncState.SYNC_FAILED_RETRY_AVAILABLE,
                            errorMessage = "Maximum retry attempts ($MAX_RETRY_ATTEMPTS) reached. Preserved locally for manual retry.",
                            workerSessionId = workerSessionId
                        )
                        processedKeysThisRun.add(sub.recordKey)
                        continue
                    }

                    // Atomically claim submission with unique worker session lease
                    val claimed = daoAdapter.claimForSync(sub.recordKey, workerSessionId)
                    if (!claimed) {
                        // Already held by another active worker or already processed
                        processedKeysThisRun.add(sub.recordKey)
                        continue
                    }

                    val submissionDto = CountingSubmissionDto(
                        assetKey = sub.assetKey,
                        schemaVersionId = sub.schemaVersionId,
                        countDate = sub.countDate,
                        batchId = sub.batchId,
                        idempotencyKey = sub.idempotencyKey, // Generated once at local creation; NEVER regenerated
                        values = sub.fieldValues
                    )

                    val result = apiClient.submitCount(
                        clientId = sub.clientId,
                        cycleId = sub.auditCycleId,
                        submission = submissionDto
                    )

                when (result) {
                    is WorkerApiResult.Success -> {
                        val serverRes = result.data
                        daoAdapter.updateSyncStatus(
                            recordKey = sub.recordKey,
                            status = DynamicSyncState.SYNCED,
                            serverRecordId = serverRes.recordId,
                            isIdempotentReplay = serverRes.isIdempotentReplay,
                            workerSessionId = workerSessionId
                        )
                        processedKeysThisRun.add(sub.recordKey)
                    }

                    is WorkerApiResult.Error -> {
                        when (result.statusCode) {
                            401 -> {
                                // Request refreshed Firebase token using legitimate SDK refresh mechanism
                                val refreshedToken = tokenProvider?.getIdToken(forceRefresh = true)
                                var refreshSucceeded = false

                                if (!refreshedToken.isNullOrBlank()) {
                                    // Retry once with the SAME submission and SAME idempotency key
                                    val retryRes = apiClient.submitCount(
                                        clientId = sub.clientId,
                                        cycleId = sub.auditCycleId,
                                        submission = submissionDto,
                                        bearerTokenOverride = refreshedToken
                                    )

                                    if (retryRes is WorkerApiResult.Success) {
                                        daoAdapter.updateSyncStatus(
                                            recordKey = sub.recordKey,
                                            status = DynamicSyncState.SYNCED,
                                            serverRecordId = retryRes.data.recordId,
                                            isIdempotentReplay = retryRes.data.isIdempotentReplay,
                                            workerSessionId = workerSessionId
                                        )
                                        refreshSucceeded = true
                                        processedKeysThisRun.add(sub.recordKey)
                                    }
                                }

                                if (!refreshSucceeded) {
                                    // Refreshed token failed or unavailable: mark AUTHENTICATION_REQUIRED
                                    daoAdapter.updateSyncStatus(
                                        recordKey = sub.recordKey,
                                        status = DynamicSyncState.AUTHENTICATION_REQUIRED,
                                        errorCode = 401,
                                        errorMessage = "Firebase session expired. Please re-authenticate.",
                                        workerSessionId = workerSessionId
                                    )
                                    processedKeysThisRun.add(sub.recordKey)
                                }
                            }

                            403 -> {
                                daoAdapter.updateSyncStatus(
                                    recordKey = sub.recordKey,
                                    status = DynamicSyncState.PERMISSION_BLOCKED,
                                    errorCode = 403,
                                    errorMessage = result.message,
                                    workerSessionId = workerSessionId
                                )
                                processedKeysThisRun.add(sub.recordKey)
                            }

                            400, 422 -> {
                                daoAdapter.updateSyncStatus(
                                    recordKey = sub.recordKey,
                                    status = DynamicSyncState.VALIDATION_FAILED,
                                    errorCode = result.statusCode,
                                    errorMessage = result.message,
                                    workerSessionId = workerSessionId
                                )
                                processedKeysThisRun.add(sub.recordKey)
                            }

                            404 -> {
                                daoAdapter.updateSyncStatus(
                                    recordKey = sub.recordKey,
                                    status = DynamicSyncState.SYNC_FAILED_RETRY_AVAILABLE,
                                    errorCode = 404,
                                    errorMessage = result.message,
                                    workerSessionId = workerSessionId
                                )
                                hasRetryableFailure = true
                                processedKeysThisRun.add(sub.recordKey)
                            }

                            409 -> {
                                daoAdapter.updateSyncStatus(
                                    recordKey = sub.recordKey,
                                    status = DynamicSyncState.CONFLICT_REQUIRES_REVIEW,
                                    errorCode = 409,
                                    errorMessage = result.message,
                                    workerSessionId = workerSessionId
                                )
                                processedKeysThisRun.add(sub.recordKey)
                            }

                            429 -> {
                                val retryAfter = result.retryAfterSeconds ?: 30L
                                daoAdapter.updateSyncStatus(
                                    recordKey = sub.recordKey,
                                    status = DynamicSyncState.SYNC_FAILED_RETRY_AVAILABLE,
                                    errorCode = 429,
                                    errorMessage = "Rate limited. Retry after $retryAfter seconds: ${result.message}",
                                    workerSessionId = workerSessionId
                                )
                                hasRetryableFailure = true
                                processedKeysThisRun.add(sub.recordKey)
                            }

                            in 500..599, null -> {
                                daoAdapter.updateSyncStatus(
                                    recordKey = sub.recordKey,
                                    status = DynamicSyncState.SYNC_FAILED_RETRY_AVAILABLE,
                                    errorCode = result.statusCode,
                                    errorMessage = result.message,
                                    workerSessionId = workerSessionId
                                )
                                hasRetryableFailure = true
                                processedKeysThisRun.add(sub.recordKey)
                            }

                            else -> {
                                daoAdapter.updateSyncStatus(
                                    recordKey = sub.recordKey,
                                    status = DynamicSyncState.SYNC_FAILED_RETRY_AVAILABLE,
                                    errorCode = result.statusCode,
                                    errorMessage = result.message,
                                    workerSessionId = workerSessionId
                                )
                                hasRetryableFailure = true
                                processedKeysThisRun.add(sub.recordKey)
                            }
                        }
                    }
                }
            }
        }

        } finally {
            SyncCoordinator.registerWorkerExit(workerSessionId, targetClientId, targetCycleId)
        }

        return if (hasRetryableFailure) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
