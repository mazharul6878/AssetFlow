package com.example.data.worker

import android.content.Context
import androidx.work.WorkManager
import com.example.data.offline.OfflineDatabaseGate
import com.example.data.offline.OfflineSyncDao
import com.example.data.offline.OfflineSyncDatabase

/**
 * Recovers pending photo uploads across app process restarts, crashes, and device reboots.
 * Ensures the Room `offline_photo_queue` remains the durable source of truth.
 */
object PhotoUploadRecovery {

    suspend fun recoverPendingPhotos(
        context: Context,
        dao: OfflineSyncDao? = null,
        workManager: WorkManager? = null
    ): List<String> {
        if (!OfflineDatabaseGate.isAvailable(context)) {
            return emptyList()
        }
        val resolvedDao = dao ?: OfflineSyncDatabase.getInstance(context).offlineSyncDao()
        val pendingPhotos = resolvedDao.getPendingOrFailedPhotos()

        val clientsToSchedule = pendingPhotos
            .map { it.clientId.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        for (clientId in clientsToSchedule) {
            PhotoUploadScheduler.schedulePhotoUpload(
                context = context,
                clientId = clientId
            )
        }
        return clientsToSchedule
    }
}
