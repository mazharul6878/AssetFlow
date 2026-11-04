package com.example.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.drive.DriveAuthException
import com.example.data.drive.DriveDuplicateEvidenceException
import com.example.data.drive.DriveLocalFileException
import com.example.data.drive.DrivePermissionException
import com.example.data.drive.DriveTransientException
import com.example.data.drive.GoogleDriveResumableUploader
import com.example.data.drive.ResumableSessionStore
import com.example.data.drive.ResumableUploadParams
import com.example.data.model.SyncState
import com.example.data.network.PhotoEvidenceMetadataDto
import com.example.data.network.WorkerApiClient
import com.example.data.network.WorkerApiResult
import com.example.data.offline.OfflineDatabaseGate
import com.example.data.offline.OfflinePhotoQueueEntity
import com.example.data.offline.OfflineSyncDao
import com.example.data.offline.OfflineSyncDatabase
import com.example.data.repository.PhotoEvidenceRepository
import java.io.File

/**
 * Background worker executing chunked Google Drive Resumable Uploads for photos in [OfflineSyncDatabase],
 * followed by metadata registration with the Cloudflare Worker D1 backend.
 * Never uploads raw image binaries to Cloudflare D1 or SQLite.
 */
class PhotoUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val dao: OfflineSyncDao = OfflineSyncDatabase.getInstance(appContext).offlineSyncDao(),
    private val apiClient: WorkerApiClient = WorkerApiClient(),
    private val tokenProvider: suspend () -> String? = { null },
    private val resumableUploader: GoogleDriveResumableUploader = GoogleDriveResumableUploader(
        sessionStore = ResumableSessionStore.getInstance(appContext),
        tokenProvider = tokenProvider
    )
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val UNIQUE_WORK_NAME = "photo_evidence_upload"
        const val MAX_RETRY_ATTEMPTS = 5
        const val STALE_UPLOADING_THRESHOLD_MILLIS = 120_000L
    }

    override suspend fun doWork(): Result {
        if (!OfflineDatabaseGate.isAvailable(applicationContext)) {
            return Result.failure()
        }

        val targetClientId = inputData.getString("clientId")
        val targetCycleId = inputData.getString("cycleId")

        // 1. Recover any stale "Uploading" records left by dead processes
        recoverStaleUploadingRecords(targetClientId)

        val pendingPhotos = getPendingPhotos(targetClientId)
        if (pendingPhotos.isEmpty()) {
            return Result.success()
        }

        var hasRetryableFailure = false
        val workerToken = "worker_${java.util.UUID.randomUUID().toString().take(12)}"

        for (photo in pendingPhotos) {
            if (isStopped) {
                return Result.retry()
            }

            val now = System.currentTimeMillis()
            val leaseExpiry = now + STALE_UPLOADING_THRESHOLD_MILLIS

            // 1. Atomic Database-Backed Lease Claim
            val claimed = dao.claimPhotoLease(
                photoId = photo.photoId,
                ownerToken = workerToken,
                newStatus = "Uploading",
                expiresAtMillis = leaseExpiry,
                nowMillis = now
            )
            if (claimed == 0) {
                // Active upload lease held by concurrent worker; skip
                continue
            }

            if (photo.retryCount >= MAX_RETRY_ATTEMPTS) {
                dao.updatePhotoStatusWithLease(
                    photoId = photo.photoId,
                    ownerToken = workerToken,
                    status = "Upload Failed — Retry Available",
                    driveFileId = photo.driveFileId,
                    webLink = photo.driveWebViewLink,
                    error = "Maximum upload retry attempts ($MAX_RETRY_ATTEMPTS) exceeded. Tap to retry.",
                    retries = photo.retryCount,
                    syncedAt = System.currentTimeMillis()
                )
                continue
            }

            val localFile = File(photo.localFilePath)
            if (!localFile.exists() || localFile.length() <= 0L) {
                dao.updatePhotoStatusWithLease(
                    photoId = photo.photoId,
                    ownerToken = workerToken,
                    status = "Upload Failed — Retry Available",
                    driveFileId = photo.driveFileId,
                    webLink = photo.driveWebViewLink,
                    error = "Local photo file missing from device storage.",
                    retries = photo.retryCount + 1,
                    syncedAt = System.currentTimeMillis()
                )
                continue
            }

            var driveFileId = photo.driveFileId
            var driveWebLink = photo.driveWebViewLink

            // 2. Google Drive Resumable Upload (if not already uploaded)
            if (driveFileId.isBlank()) {
                // Heartbeat lease renewal before upload begins
                dao.renewPhotoLease(photo.photoId, workerToken, System.currentTimeMillis() + STALE_UPLOADING_THRESHOLD_MILLIS)

                try {
                    val uploadResult = resumableUploader.uploadPhoto(
                        ResumableUploadParams(
                            photoId = photo.photoId,
                            localFile = localFile,
                            standardizedFileName = photo.finalDriveFilename.ifBlank { photo.originalLocalFilename },
                            mimeType = photo.mimeType,
                            parentFolderId = photo.driveDateFolderId.ifBlank { photo.driveFolderId },
                            appProperties = mapOf(
                                "clientId" to photo.clientId,
                                "cycleId" to photo.fiscalYearId,
                                "assetId" to photo.assetOrObsId,
                                "recordKey" to photo.recordKey
                            )
                        )
                    )

                    driveFileId = uploadResult.fileId
                    driveWebLink = uploadResult.webViewLink

                    // Heartbeat lease renewal after Drive upload completes
                    dao.renewPhotoLease(photo.photoId, workerToken, System.currentTimeMillis() + STALE_UPLOADING_THRESHOLD_MILLIS)

                    // Transition to Metadata Sync Pending (retaining lease ownership across intermediate state)
                    val updated = dao.updatePhotoIntermediateStatusWithLease(
                        photoId = photo.photoId,
                        ownerToken = workerToken,
                        status = "Metadata Sync Pending",
                        driveFileId = driveFileId,
                        webLink = driveWebLink,
                        error = "",
                        retries = photo.retryCount,
                        syncedAt = System.currentTimeMillis(),
                        uploadedIso = java.time.Instant.now().toString(),
                        newExpiresAtMillis = System.currentTimeMillis() + STALE_UPLOADING_THRESHOLD_MILLIS
                    )
                    if (updated == 0) {
                        // Lost lease to newer worker during upload; abort further processing
                        continue
                    }
                } catch (e: DriveDuplicateEvidenceException) {
                    dao.updatePhotoStatusWithLease(
                        photoId = photo.photoId,
                        ownerToken = workerToken,
                        status = "Upload Failed — Retry Available",
                        driveFileId = "",
                        webLink = "",
                        error = e.message ?: "Duplicate evidence detected on Drive.",
                        retries = photo.retryCount + 1,
                        syncedAt = System.currentTimeMillis()
                    )
                    continue
                } catch (e: DriveAuthException) {
                    dao.updatePhotoStatusWithLease(
                        photoId = photo.photoId,
                        ownerToken = workerToken,
                        status = "Sign-in Required",
                        driveFileId = "",
                        webLink = "",
                        error = e.message ?: "Authentication required for Google Drive upload.",
                        retries = photo.retryCount,
                        syncedAt = System.currentTimeMillis()
                    )
                    continue
                } catch (e: DrivePermissionException) {
                    dao.updatePhotoStatusWithLease(
                        photoId = photo.photoId,
                        ownerToken = workerToken,
                        status = "Drive Access Required",
                        driveFileId = "",
                        webLink = "",
                        error = e.message ?: "Drive permission denied.",
                        retries = photo.retryCount,
                        syncedAt = System.currentTimeMillis()
                    )
                    continue
                } catch (e: DriveTransientException) {
                    hasRetryableFailure = true
                    dao.updatePhotoStatusWithLease(
                        photoId = photo.photoId,
                        ownerToken = workerToken,
                        status = "Waiting for Internet",
                        driveFileId = "",
                        webLink = "",
                        error = e.message ?: "Network error during upload.",
                        retries = photo.retryCount + 1,
                        syncedAt = System.currentTimeMillis()
                    )
                    continue
                } catch (e: Exception) {
                    hasRetryableFailure = true
                    dao.updatePhotoStatusWithLease(
                        photoId = photo.photoId,
                        ownerToken = workerToken,
                        status = "Upload Failed — Retry Available",
                        driveFileId = "",
                        webLink = "",
                        error = e.localizedMessage ?: "Unknown upload error.",
                        retries = photo.retryCount + 1,
                        syncedAt = System.currentTimeMillis()
                    )
                    continue
                }
            }

            // 3. Cloudflare Worker D1 Metadata Registration
            if (driveFileId.isNotBlank()) {
                // Heartbeat lease renewal before D1 call
                dao.renewPhotoLease(photo.photoId, workerToken, System.currentTimeMillis() + STALE_UPLOADING_THRESHOLD_MILLIS)

                val sha256 = PhotoEvidenceRepository.computeFileSha256(localFile)
                val cycleId = photo.fiscalYearId.ifBlank { targetCycleId ?: "2026_27" }
                val clientId = photo.clientId.ifBlank { targetClientId ?: "" }

                val resolvedDriveFolderId = if (photo.driveFolderId.isNotBlank() && photo.driveFolderId != "drive_root_default") {
                    photo.driveFolderId
                } else {
                    val clientListRes = apiClient.listClients()
                    if (clientListRes is WorkerApiResult.Success) {
                        val clientDto = clientListRes.data.clients.firstOrNull { it.clientId == clientId }
                        clientDto?.driveRootFolderId?.takeIf { it.isNotBlank() } ?: photo.driveFolderId.ifBlank { "drive_root_default" }
                    } else {
                        photo.driveFolderId.ifBlank { "drive_root_default" }
                    }
                }

                val metadataDto = PhotoEvidenceMetadataDto(
                    photoId = photo.photoId,
                    cycleId = cycleId,
                    assetKey = photo.recordKey.ifBlank { photo.assetOrObsId },
                    assetId = photo.assetOrObsId, // Strictly preserves leading zeros!
                    driveFileId = driveFileId,
                    driveWebViewLink = driveWebLink,
                    driveRootFolderId = resolvedDriveFolderId,
                    driveDateFolderId = photo.driveDateFolderId.takeIf { it.isNotBlank() },
                    mimeType = photo.mimeType,
                    fileExtension = photo.fileExtension,
                    fileSize = localFile.length(),
                    sha256 = sha256,
                    localFilename = photo.finalDriveFilename,
                    capturedAt = photo.capturedAtIso,
                    uploadedAt = java.time.Instant.now().toString()
                )

                when (val apiRes = apiClient.registerPhotoEvidence(clientId, cycleId, metadataDto)) {
                    is WorkerApiResult.Success -> {
                        dao.updatePhotoStatusWithLease(
                            photoId = photo.photoId,
                            ownerToken = workerToken,
                            status = "Uploaded",
                            driveFileId = driveFileId,
                            webLink = driveWebLink,
                            error = "",
                            retries = photo.retryCount,
                            syncedAt = System.currentTimeMillis(),
                            uploadedIso = java.time.Instant.now().toString()
                        )
                    }
                    is WorkerApiResult.Error -> {
                        if (apiRes.statusCode in 400..499 && apiRes.statusCode != 408 && apiRes.statusCode != 429) {
                            // Non-retryable client validation or permission error
                            dao.updatePhotoStatusWithLease(
                                photoId = photo.photoId,
                                ownerToken = workerToken,
                                status = "Upload Failed — Retry Available",
                                driveFileId = driveFileId,
                                webLink = driveWebLink,
                                error = apiRes.message,
                                retries = photo.retryCount + 1,
                                syncedAt = System.currentTimeMillis()
                            )
                        } else {
                            hasRetryableFailure = true
                            dao.updatePhotoStatusWithLease(
                                photoId = photo.photoId,
                                ownerToken = workerToken,
                                status = "Waiting for Internet",
                                driveFileId = driveFileId,
                                webLink = driveWebLink,
                                error = apiRes.message,
                                retries = photo.retryCount + 1,
                                syncedAt = System.currentTimeMillis()
                            )
                        }
                    }
                }
            }
        }

        return if (hasRetryableFailure) Result.retry() else Result.success()
    }

    private suspend fun recoverStaleUploadingRecords(clientId: String?) {
        val now = System.currentTimeMillis()
        val allPhotos = if (!clientId.isNullOrBlank()) dao.getPhotosForClient(clientId) else dao.getPendingOrFailedPhotos()
        for (p in allPhotos) {
            val isLeaseExpired = p.leaseExpiresAtMillis <= now || (p.leaseExpiresAtMillis == 0L && (now - p.lastSyncedAtMillis > STALE_UPLOADING_THRESHOLD_MILLIS))
            if ((p.uploadStatus == "Uploading" || p.uploadStatus == "Metadata Sync Pending") && isLeaseExpired) {
                dao.recoverExpiredPhotoLease(
                    photoId = p.photoId,
                    newStatus = SyncState.PENDING_SYNC,
                    error = "Recovered interrupted upload process.",
                    nowMillis = now
                )
            }
        }
    }

    private suspend fun getPendingPhotos(clientId: String?): List<OfflinePhotoQueueEntity> {
        val all = if (!clientId.isNullOrBlank()) dao.getPhotosForClient(clientId) else dao.getPendingOrFailedPhotos()
        val now = System.currentTimeMillis()
        return all.filter { p ->
            val isLeaseAvailableOrExpired = p.leaseOwnerToken.isBlank() || p.leaseExpiresAtMillis <= now || (p.leaseExpiresAtMillis == 0L && (now - p.lastSyncedAtMillis > STALE_UPLOADING_THRESHOLD_MILLIS))
            p.uploadStatus in setOf(
                SyncState.PENDING_SYNC,
                "Saved Locally",
                "Waiting for Internet",
                "Failed"
            ) || ((p.uploadStatus == "Uploading" || p.uploadStatus == "Metadata Sync Pending") && isLeaseAvailableOrExpired)
        }.sortedBy { it.queuedAtMillis }
    }
}
