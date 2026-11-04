package com.example.data.offline

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.data.model.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Adapter interfacing [DynamicSubmissionRecord] with the existing [OfflineSyncDao] and [OfflineQueuedRecordEntity].
 * This avoids any schema modifications to the protected [OfflineSyncDatabase] file while providing
 * full lifecycle tracking, idempotency persistence, and database-level transactional lease management.
 */
class DynamicSubmissionDaoAdapter(
    private val dao: OfflineSyncDao,
    private val database: RoomDatabase? = null
) {
    companion object {
        // Fallback cross-instance mutex used only when running in lightweight tests without a real RoomDatabase
        private val globalFallbackMutex = Mutex()
    }

    /**
     * Executes the given suspending block inside a Room database transaction when [database] is present,
     * or within a synchronized coroutine mutex when running against a standalone DAO mock.
     */
    private suspend fun <T> runInDbTransaction(block: suspend () -> T): T {
        val db = database
        return if (db != null) {
            db.withTransaction { block() }
        } else {
            globalFallbackMutex.withLock { block() }
        }
    }

    /**
     * Persists a dynamic verification submission locally before any network call.
     * Maps [DynamicSyncState] to the underlying [SyncState] strings expected by [OfflineSyncDao].
     */
    suspend fun upsertDynamicSubmission(submission: DynamicSubmissionRecord) {
        val entitySyncStatus = when (submission.syncStatus) {
            DynamicSyncState.SYNCED -> SyncState.SYNCED
            DynamicSyncState.CONFLICT_REQUIRES_REVIEW -> SyncState.CONFLICT_DETECTED
            DynamicSyncState.SAVED_LOCALLY,
            DynamicSyncState.WAITING_FOR_INTERNET,
            DynamicSyncState.PENDING_SYNC,
            DynamicSyncState.SYNCING -> SyncState.PENDING_SYNC
            else -> SyncState.FAILED
        }

        val entity = OfflineQueuedRecordEntity(
            recordKey = submission.recordKey,
            recordType = "DYNAMIC_COUNT",
            assetId = submission.assetId,
            sourceRow = 0L,
            clientId = submission.clientId,
            fiscalYearId = submission.auditCycleId,
            payloadJson = submission.toJson(),
            markAsReviewed = false,
            explicitDateEdit = false,
            baseUpdatedAtMillis = submission.createdAtMillis,
            syncStatus = entitySyncStatus,
            errorMessage = submission.lastErrorMessage ?: "",
            retryCount = submission.retryCount,
            userId = submission.auditorUid,
            userDisplayName = submission.auditorDisplayName,
            queuedAtMillis = submission.createdAtMillis,
            lastSyncedAtMillis = if (submission.syncStatus == DynamicSyncState.SYNCED) System.currentTimeMillis() else 0L
        )
        dao.upsertQueuedRecord(entity)
    }

    /**
     * Retrieves a single dynamic submission by its record key.
     */
    suspend fun getDynamicSubmission(recordKey: String): DynamicSubmissionRecord? {
        val entity = dao.getQueuedRecordByKey(recordKey) ?: return null
        return if (entity.recordType == "DYNAMIC_COUNT" && entity.payloadJson.isNotBlank()) {
            DynamicSubmissionRecord.fromJson(entity.payloadJson)
        } else {
            null
        }
    }

    /**
     * Retrieves all pending submissions requiring synchronization.
     */
    suspend fun getPendingSubmissions(
        clientId: String? = null,
        cycleId: String? = null
    ): List<DynamicSubmissionRecord> {
        val allEntities = dao.getPendingOrFailedRecords()
        return allEntities
            .filter { it.recordType == "DYNAMIC_COUNT" && it.payloadJson.isNotBlank() }
            .map { DynamicSubmissionRecord.fromJson(it.payloadJson) }
            .filter { rec ->
                (clientId == null || rec.clientId == clientId) &&
                (cycleId == null || rec.auditCycleId == cycleId) &&
                DynamicSyncState.isPending(rec.syncStatus)
            }
    }

    /**
     * Attempts to atomically claim a submission for synchronization by a specific worker session.
     * Prevents concurrent workers or competing processes from processing the same submission.
     * Guaranteed safe across multiple adapter instances sharing the same database via [runInDbTransaction].
     * Returns true if successfully claimed, or false if already held by another valid unexpired worker.
     */
    suspend fun claimForSync(
        recordKey: String,
        workerSessionId: String,
        leaseDurationMillis: Long = 60_000L
    ): Boolean = runInDbTransaction {
        val existing = getDynamicSubmission(recordKey) ?: return@runInDbTransaction false
        val now = System.currentTimeMillis()

        // Never claim already synced records
        if (existing.syncStatus == DynamicSyncState.SYNCED) {
            return@runInDbTransaction false
        }

        // If currently in SYNCING state, check lease ownership and expiry
        if (existing.syncStatus == DynamicSyncState.SYNCING) {
            val owner = existing.leaseOwnerId
            val expiry = existing.leaseExpiresAtMillis

            // If owned by another worker whose lease has not yet expired, cannot claim
            if (owner != null && owner != workerSessionId && expiry != null && now <= expiry) {
                return@runInDbTransaction false
            }
        }

        // Claim record with new lease
        val updated = existing.copy(
            syncStatus = DynamicSyncState.SYNCING,
            leaseOwnerId = workerSessionId,
            syncStartedAtMillis = now,
            leaseExpiresAtMillis = now + leaseDurationMillis,
            lastAttemptMillis = now
        )
        upsertDynamicSubmission(updated)
        return@runInDbTransaction true
    }

    /**
     * Refreshes an active lease for a long-running sync operation (e.g. slow network or large upload).
     * Prevents the record from being considered stale or recovered mid-transfer.
     * Verifies that the caller owns the active lease before extending it.
     */
    suspend fun refreshLease(
        recordKey: String,
        workerSessionId: String,
        additionalMillis: Long = 60_000L
    ): Boolean = runInDbTransaction {
        val existing = getDynamicSubmission(recordKey) ?: return@runInDbTransaction false
        if (existing.leaseOwnerId != workerSessionId) {
            return@runInDbTransaction false
        }
        if (existing.syncStatus != DynamicSyncState.SYNCING) {
            return@runInDbTransaction false
        }

        val now = System.currentTimeMillis()
        val updated = existing.copy(
            leaseExpiresAtMillis = now + additionalMillis
        )
        upsertDynamicSubmission(updated)
        return@runInDbTransaction true
    }

    /**
     * Releases a lease held by a worker session.
     * Strictly verifies that the calling worker owns the lease before releasing it.
     */
    suspend fun releaseLease(recordKey: String, workerSessionId: String): Boolean = runInDbTransaction {
        val existing = getDynamicSubmission(recordKey) ?: return@runInDbTransaction false
        if (existing.leaseOwnerId != workerSessionId) {
            return@runInDbTransaction false
        }
        val updated = existing.copy(
            leaseOwnerId = null,
            leaseExpiresAtMillis = null
        )
        upsertDynamicSubmission(updated)
        return@runInDbTransaction true
    }

    /**
     * Recovers submissions left in SYNCING state following an ungraceful process termination,
     * application kill, or worker cancellation.
     * Only recovers records whose lease has genuinely expired or was never granted.
     * Never resets a record currently owned by a valid active worker session.
     * Preserves the exact original idempotency key, assetId, auditor attribution, and field values.
     */
    suspend fun recoverStaleSyncingRecords(
        clientId: String? = null,
        cycleId: String? = null,
        currentWorkerSessionId: String? = null,
        staleThresholdMillis: Long = 60_000L
    ): Int = runInDbTransaction {
        val now = System.currentTimeMillis()
        val allEntities = dao.getPendingOrFailedRecords()
        val syncingRecords = allEntities
            .filter { it.recordType == "DYNAMIC_COUNT" && it.payloadJson.isNotBlank() }
            .map { DynamicSubmissionRecord.fromJson(it.payloadJson) }
            .filter { rec ->
                (clientId == null || rec.clientId == clientId) &&
                (cycleId == null || rec.auditCycleId == cycleId) &&
                rec.syncStatus == DynamicSyncState.SYNCING &&
                rec.serverRecordId == null &&
                // Do not recover our own active lease
                (currentWorkerSessionId == null || rec.leaseOwnerId != currentWorkerSessionId) &&
                // Check if lease has genuinely expired
                (if (rec.leaseExpiresAtMillis != null) {
                    now > rec.leaseExpiresAtMillis
                } else {
                    rec.lastAttemptMillis == null || now - rec.lastAttemptMillis > staleThresholdMillis
                })
            }

        for (rec in syncingRecords) {
            val recovered = rec.copy(
                syncStatus = DynamicSyncState.SYNC_FAILED_RETRY_AVAILABLE,
                leaseOwnerId = null,
                leaseExpiresAtMillis = null,
                lastErrorMessage = "Sync lease expired or worker terminated ungracefully. Restored to retryable state."
            )
            upsertDynamicSubmission(recovered)
        }
        return@runInDbTransaction syncingRecords.size
    }

    /**
     * Resets the retry counter to 0 for a record on manual retry, beginning a fresh bounded retry cycle.
     * Preserves original idempotency key, auditor attribution, asset ID, and payload unchanged.
     */
    suspend fun resetRetryCount(recordKey: String): Unit = runInDbTransaction {
        val existing = getDynamicSubmission(recordKey) ?: return@runInDbTransaction
        val updated = existing.copy(
            syncStatus = DynamicSyncState.PENDING_SYNC,
            retryCount = 0,
            lastErrorCode = null,
            lastErrorMessage = null,
            leaseOwnerId = null,
            leaseExpiresAtMillis = null
        )
        upsertDynamicSubmission(updated)
    }

    /**
     * Observes all dynamic submissions as a reactive Flow.
     */
    fun observeAllDynamicSubmissions(
        clientId: String? = null,
        cycleId: String? = null
    ): Flow<List<DynamicSubmissionRecord>> {
        return dao.observeAllQueuedRecords().map { entities ->
            entities
                .filter { it.recordType == "DYNAMIC_COUNT" && it.payloadJson.isNotBlank() }
                .map { DynamicSubmissionRecord.fromJson(it.payloadJson) }
                .filter { rec ->
                    (clientId == null || rec.clientId == clientId) &&
                    (cycleId == null || rec.auditCycleId == cycleId)
                }
        }
    }

    /**
     * Updates synchronization status, error messages, and server record IDs.
     * If [workerSessionId] is provided, ensures only the active lease owner can modify the record.
     */
    suspend fun updateSyncStatus(
        recordKey: String,
        status: String,
        errorCode: Int? = null,
        errorMessage: String? = null,
        serverRecordId: String? = null,
        isIdempotentReplay: Boolean = false,
        workerSessionId: String? = null
    ): Boolean = runInDbTransaction {
        val existing = getDynamicSubmission(recordKey) ?: return@runInDbTransaction false

        // Stale worker protection: if workerSessionId is specified, verify it owns the current lease
        if (workerSessionId != null && existing.leaseOwnerId != null && existing.leaseOwnerId != workerSessionId) {
            return@runInDbTransaction false
        }

        // Never revert an already SYNCED record
        if (existing.syncStatus == DynamicSyncState.SYNCED && status != DynamicSyncState.SYNCED) {
            return@runInDbTransaction false
        }

        val updated = existing.copy(
            syncStatus = status,
            retryCount = if (status == DynamicSyncState.SYNC_FAILED_RETRY_AVAILABLE) existing.retryCount + 1 else existing.retryCount,
            lastAttemptMillis = System.currentTimeMillis(),
            lastErrorCode = errorCode,
            lastErrorMessage = errorMessage,
            serverRecordId = serverRecordId ?: existing.serverRecordId,
            isIdempotentReplay = isIdempotentReplay,
            leaseOwnerId = if (status == DynamicSyncState.SYNCING) existing.leaseOwnerId else null,
            leaseExpiresAtMillis = if (status == DynamicSyncState.SYNCING) existing.leaseExpiresAtMillis else null
        )
        upsertDynamicSubmission(updated)
        return@runInDbTransaction true
    }

    /**
     * Deletes a submission from the offline queue.
     */
    suspend fun deleteSubmission(recordKey: String) {
        dao.deleteQueuedRecord(recordKey)
    }

    /**
     * Deletes all submissions for a client (e.g. on client removal or wipe).
     */
    suspend fun deleteSubmissionsForClient(clientId: String) {
        dao.deleteQueuedRecordsForClient(clientId)
    }
}
