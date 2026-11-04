package com.example.data.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager scheduler for background photo uploads.
 * Implements bounded unique work queue semantics (`APPEND_OR_REPLACE` or `REPLACE`),
 * ensuring active photo uploads are never abruptly cancelled and queue chains remain bounded.
 */
object PhotoUploadScheduler {

    fun getUniqueWorkName(clientId: String? = null): String {
        return if (!clientId.isNullOrBlank()) {
            "photo_upload_${clientId.trim()}"
        } else {
            PhotoUploadWorker.UNIQUE_WORK_NAME
        }
    }

    fun schedulePhotoUpload(
        context: Context,
        clientId: String? = null,
        cycleId: String? = null
    ): WorkSchedulingDecision {
        val uniqueWorkName = getUniqueWorkName(clientId)
        val workManager = WorkManager.getInstance(context.applicationContext)

        val existingWorkInfos = try {
            workManager.getWorkInfosForUniqueWork(uniqueWorkName).get(2, TimeUnit.SECONDS) ?: emptyList()
        } catch (_: Exception) {
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
            if (!clientId.isNullOrBlank()) putString("clientId", clientId.trim())
            if (!cycleId.isNullOrBlank()) putString("cycleId", cycleId.trim())
        }.build()

        val request = OneTimeWorkRequestBuilder<PhotoUploadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag("photo_upload")
            .build()

        return when {
            hasEnqueuedOrBlocked -> {
                // Already queued or waiting to run, coalesce
                WorkSchedulingDecision.COALESCED_ALREADY_QUEUED
            }
            hasRunning -> {
                // Running worker: chain exactly one follow-up request
                workManager.enqueueUniqueWork(
                    uniqueWorkName,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request
                )
                WorkSchedulingDecision.ENQUEUED_FOLLOWUP
            }
            else -> {
                // Fresh queue
                workManager.enqueueUniqueWork(
                    uniqueWorkName,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
                WorkSchedulingDecision.ENQUEUED_FRESH
            }
        }
    }
}
