package com.example.data.worker

import android.content.Context
import androidx.work.WorkManager
import com.example.data.offline.DynamicSubmissionRecord
import com.example.data.offline.DynamicSyncState
import com.example.data.offline.OfflineSyncDao
import com.example.data.offline.OfflineSyncDatabase

/**
 * Recovers pending submissions across app process restarts, crashes, device reboots,
 * and the persistence-before-enqueue failure window.
 * Ensures the Room offline queue remains the durable source of truth.
 */
object DynamicSyncRecovery {

    /**
     * Inspects the Room offline queue for any pending submissions that lack active WorkManager execution.
     * Recovers submissions stranded by process termination, unexpected app kills, or device reboots.
     * Returns the list of (clientId, cycleId) pairs for which sync was scheduled or coalesced.
     */
    suspend fun recoverPendingSubmissions(
        context: Context,
        dao: OfflineSyncDao? = null,
        workManager: WorkManager? = null
    ): List<Pair<String, String>> {
        if (!com.example.data.offline.OfflineDatabaseGate.isAvailable(context)) {
            return emptyList()
        }
        val resolvedDao = dao ?: OfflineSyncDatabase.getInstance(context).offlineSyncDao()
        val resolvedWorkManager = workManager ?: WorkManager.getInstance(context.applicationContext)
        val pendingRecords = resolvedDao.getPendingOrFailedRecords()
        val distinctScopes = pendingRecords
            .filter { it.recordType == "DYNAMIC_COUNT" && it.payloadJson.isNotBlank() }
            .mapNotNull { entity ->
                try {
                    val record = DynamicSubmissionRecord.fromJson(entity.payloadJson)
                    if (DynamicSyncState.isPending(record.syncStatus)) {
                        record.clientId to record.auditCycleId
                    } else null
                } catch (e: Exception) {
                    if (entity.clientId.isNotBlank() && entity.fiscalYearId.isNotBlank()) {
                        entity.clientId to entity.fiscalYearId
                    } else null
                }
            }
            .distinct()

        val scheduledScopes = mutableListOf<Pair<String, String>>()
        for ((clientId, cycleId) in distinctScopes) {
            DynamicSyncScheduler.scheduleSync(
                context = context,
                clientId = clientId,
                cycleId = cycleId
            )
            scheduledScopes.add(clientId to cycleId)
        }
        return scheduledScopes
    }
}
