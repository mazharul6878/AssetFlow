package com.example.data.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Result of scheduling a unique sync work request.
 */
enum class WorkSchedulingDecision {
    ENQUEUED_FRESH,           // No active work, enqueued with REPLACE
    ENQUEUED_FOLLOWUP,        // Active worker running without follow-up, enqueued 1 follow-up with APPEND_OR_REPLACE
    COALESCED_ALREADY_QUEUED  // Follow-up or pending worker already enqueued, coalesced without appending new work
}

/**
 * WorkManager scheduler for background dynamic count synchronization.
 * Applies network constraints, exponential backoff, and singleton unique work coordination.
 * Enforces race-safe bounded queue semantics:
 * 1. An active in-flight worker is NEVER cancelled by repeated "Sync Now" taps.
 * 2. Repeated scheduling calls are genuinely COALESCED: if an enqueued follow-up already exists,
 *    no additional workers are chained onto WorkManager, strictly bounding the unique work chain length at <= 2.
 * 3. Failed or cancelled prerequisite work uses REPLACE to clear the dead chain and immediately restart sync.
 * 4. Distinct Client and Cycle queues remain fully isolated.
 */
object DynamicSyncScheduler {

    fun getUniqueWorkName(clientId: String? = null, cycleId: String? = null): String {
        return if (!clientId.isNullOrBlank() && !cycleId.isNullOrBlank()) {
            "dynamic_sync_${clientId}_${cycleId}"
        } else {
            DynamicSubmissionWorker.UNIQUE_WORK_NAME
        }
    }

    /**
     * Schedules synchronization of pending submissions using a bounded unique-work queue protocol.
     * 1. Increments [SyncCoordinator] request generation so active drain loops can re-check Room.
     * 2. Inspects existing WorkManager states for the unique work name:
     *    - If no active work (or terminal FAILED/CANCELLED/SUCCEEDED): enqueues fresh work with [ExistingWorkPolicy.REPLACE].
     *    - If a worker is RUNNING and no follow-up is enqueued: appends exactly one follow-up with [ExistingWorkPolicy.APPEND_OR_REPLACE].
     *    - If a follow-up is ALREADY ENQUEUED or BLOCKED: coalesces without appending, preventing unbounded chains.
     *    - If a worker is ENQUEUED (not yet running): coalesces without appending since it will drain Room on startup.
     */
    fun scheduleSync(
        context: Context,
        clientId: String? = null,
        cycleId: String? = null,
        immediate: Boolean = false
    ): WorkSchedulingDecision {
        val uniqueWorkName = getUniqueWorkName(clientId, cycleId)

        // Register sync request generation in coordinator
        SyncCoordinator.notifySyncRequested(clientId, cycleId)

        val workManager = WorkManager.getInstance(context.applicationContext)

        val existingWorkInfos = try {
            workManager.getWorkInfosForUniqueWork(uniqueWorkName).get(2, TimeUnit.SECONDS) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val hasRunning = existingWorkInfos.any { it.state == WorkInfo.State.RUNNING }
        val hasEnqueuedOrBlocked = existingWorkInfos.any {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = Data.Builder().apply {
            if (!clientId.isNullOrBlank()) putString("clientId", clientId)
            if (!cycleId.isNullOrBlank()) putString("cycleId", cycleId)
        }.build()

        val request = OneTimeWorkRequestBuilder<DynamicSubmissionWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

        return if (hasRunning) {
            if (hasEnqueuedOrBlocked) {
                // An active worker is running AND a follow-up worker is ALREADY enqueued/blocked.
                // Do NOT append an additional worker (which would create an unbounded chain).
                // The running worker or already-enqueued follow-up will drain Room using the coordinator counter.
                SyncCoordinator.notifyCoalesced(clientId, cycleId)
                WorkSchedulingDecision.COALESCED_ALREADY_QUEUED
            } else {
                // An active worker is running, but no follow-up is queued yet.
                // Enqueue exactly ONE follow-up worker using APPEND_OR_REPLACE so the running worker is not cancelled.
                workManager.enqueueUniqueWork(
                    uniqueWorkName,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request
                )
                WorkSchedulingDecision.ENQUEUED_FOLLOWUP
            }
        } else if (hasEnqueuedOrBlocked) {
            // A worker is already waiting in the queue (ENQUEUED or BLOCKED, not yet running).
            // When it runs, it will read all pending records from Room.
            // Do NOT append redundant workers.
            SyncCoordinator.notifyCoalesced(clientId, cycleId)
            WorkSchedulingDecision.COALESCED_ALREADY_QUEUED
        } else {
            // No active or enqueued work exists (or previous work is SUCCEEDED, FAILED, or CANCELLED).
            // Use ExistingWorkPolicy.REPLACE to start fresh and clear any dead/failed chains.
            workManager.enqueueUniqueWork(
                uniqueWorkName,
                ExistingWorkPolicy.REPLACE,
                request
            )
            WorkSchedulingDecision.ENQUEUED_FRESH
        }
    }

    /**
     * Bounded periodic sync check (e.g. hourly when connected).
     */
    fun schedulePeriodicSync(context: Context, repeatIntervalHours: Long = 1) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<DynamicSubmissionWorker>(
            repeatIntervalHours, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                "${DynamicSubmissionWorker.UNIQUE_WORK_NAME}_periodic",
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
    }

    /**
     * Cancels scheduled background sync tasks.
     */
    fun cancelAllSync(context: Context, clientId: String? = null, cycleId: String? = null) {
        val uniqueWorkName = getUniqueWorkName(clientId, cycleId)
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(uniqueWorkName)
    }
}
