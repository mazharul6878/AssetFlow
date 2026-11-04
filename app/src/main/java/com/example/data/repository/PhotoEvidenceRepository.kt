package com.example.data.repository

import android.content.Context
import com.example.data.model.SyncState
import com.example.data.model.UserProfile
import com.example.data.offline.OfflineDatabaseGate
import com.example.data.offline.OfflinePhotoQueueEntity
import com.example.data.offline.OfflineSyncDao
import com.example.data.offline.OfflineSyncDatabase
import com.example.data.storage.PhotoStorageManager
import com.example.data.worker.PhotoUploadScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Repository coordinating photo evidence persistence, local hashing, standardized naming,
 * leading-zero preservation, and background WorkManager dispatch.
 * Fully guarded by [OfflineDatabaseGate].
 */
class PhotoEvidenceRepository(
    private val context: Context,
    private val dao: OfflineSyncDao = OfflineSyncDatabase.getInstance(context).offlineSyncDao()
) {

    suspend fun enqueueCapturedPhoto(
        recordKey: String,
        assetId: String,
        recordType: String = "FAR_COUNT",
        clientId: String,
        fiscalYearId: String,
        localFilePath: String,
        userProfile: UserProfile?,
        driveFolderId: String = "",
        photoCategory: String = "GENERAL",
        caption: String? = null
    ): Result<OfflinePhotoQueueEntity> = withContext(Dispatchers.IO) {
        runCatching {
            if (!OfflineDatabaseGate.isAvailable(context)) {
                throw IllegalStateException("Offline database is currently unavailable. Photo saved locally but queueing deferred.")
            }

            val localFile = File(localFilePath)
            if (!localFile.exists() || localFile.length() <= 0L) {
                throw IllegalArgumentException("Photo file '$localFilePath' does not exist or is empty.")
            }

            // Deduplication / Idempotency check: prevent duplicate queue entries on retry or re-save
            val existingList = dao.getPhotosForRecord(recordKey)
            val existing = existingList.firstOrNull {
                it.localFilePath == localFile.absolutePath || it.originalLocalFilename == localFile.name
            }
            if (existing != null && existing.uploadStatus != SyncState.FAILED) {
                val effectiveFolderId = driveFolderId.ifBlank { existing.driveFolderId }
                if (effectiveFolderId != existing.driveFolderId && effectiveFolderId.isNotBlank()) {
                    val updated = existing.copy(driveFolderId = effectiveFolderId)
                    dao.upsertPhotoQueue(updated)
                    return@runCatching updated
                }
                return@runCatching existing
            }

            // 1. Image Format Verification
            val formatInfo = PhotoStorageManager.detectImageFormat(localFile)

            // 2. Preserve Leading Zeros in Asset ID
            val safeAssetId = PhotoStorageManager.sanitizeAssetIdentifierPreservingLeadingZeros(
                assetId.ifBlank { recordKey }
            )

            // 3. Collision-Safe Nickname Resolution
            val rawNick = userProfile?.nickname.orEmpty().ifBlank { "auditor" }
            val currentUid = userProfile?.userId.orEmpty()
            val safeNick = PhotoStorageManager.resolveCollisionSafeNickname(rawNick, currentUid)

            // 4. Organization Date-Wise Subfolder Resolution
            val nowIso = java.time.Instant.now().toString()
            val dateFolderName = PhotoStorageManager.resolveDateFolderFromCaptureIso(nowIso)

            val photoId = "photo_${UUID.randomUUID().toString().replace("-", "").take(16)}"

            // 5. Standardized Collision-Safe Naming: <nickname>_<asset_id>_<photo_id>.<extension>
            val uniqueFilename = PhotoStorageManager.buildUniquePhotoFilename(
                nickname = safeNick,
                rawAssetId = safeAssetId,
                photoId = photoId,
                extension = formatInfo.extension
            )

            val naming = PhotoStorageManager.buildStandardizedPhotoName(
                rawNickname = safeNick,
                currentUid = currentUid,
                rawAssetId = safeAssetId,
                extension = formatInfo.extension
            )

            val entity = OfflinePhotoQueueEntity(
                photoId = photoId,
                recordKey = recordKey,
                assetOrObsId = safeAssetId, // Strictly preserves leading zeroes
                recordType = recordType,
                clientId = clientId,
                fiscalYearId = fiscalYearId,
                localFilePath = localFile.absolutePath,
                originalLocalFilename = localFile.name,
                finalDriveFilename = uniqueFilename,
                mimeType = formatInfo.mimeType,
                fileExtension = formatInfo.extension,
                nicknameSnapshot = safeNick,
                photoSequenceNumber = naming.sequenceNumber,
                schemaVersion = 1L,
                dateFolderName = dateFolderName,
                driveFolderId = driveFolderId,
                driveDateFolderId = "",
                driveFileId = "",
                driveWebViewLink = "",
                uploadStatus = SyncState.PENDING_SYNC,
                errorMessage = "",
                retryCount = 0,
                capturedBy = userProfile?.displayName?.ifBlank { safeNick } ?: safeNick,
                capturedByUserId = currentUid,
                capturedAtIso = nowIso,
                uploadedAtIso = "",
                queuedAtMillis = System.currentTimeMillis(),
                lastSyncedAtMillis = 0L
            )

            // 6. Persist to Room Queue
            dao.upsertPhotoQueue(entity)

            // 7. Trigger Background WorkManager Upload
            if (clientId.isNotBlank()) {
                PhotoUploadScheduler.schedulePhotoUpload(context, clientId, fiscalYearId)
            }

            entity
        }
    }

    suspend fun getPhotosForRecord(recordKey: String): List<OfflinePhotoQueueEntity> = withContext(Dispatchers.IO) {
        if (!OfflineDatabaseGate.isAvailable(context)) return@withContext emptyList()
        dao.getPhotosForRecord(recordKey)
    }

    suspend fun getPhotoById(photoId: String): OfflinePhotoQueueEntity? = withContext(Dispatchers.IO) {
        if (!OfflineDatabaseGate.isAvailable(context)) return@withContext null
        dao.getPhotoById(photoId)
    }

    suspend fun retryPhotoUpload(photoId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!OfflineDatabaseGate.isAvailable(context)) {
                throw IllegalStateException("Offline database is unavailable.")
            }
            val existing = dao.getPhotoById(photoId)
                ?: throw IllegalArgumentException("Photo with ID '$photoId' not found.")

            dao.updatePhotoUploadStatus(
                photoId = photoId,
                status = SyncState.PENDING_SYNC,
                driveFileId = existing.driveFileId,
                webLink = existing.driveWebViewLink,
                error = "",
                retries = 0,
                syncedAt = System.currentTimeMillis(),
                dateFolderId = existing.driveDateFolderId,
                dateFolderName = existing.dateFolderName,
                finalName = existing.finalDriveFilename,
                ext = existing.fileExtension,
                mime = existing.mimeType,
                seq = existing.photoSequenceNumber,
                uploadedIso = existing.uploadedAtIso
            )

            if (existing.clientId.isNotBlank()) {
                PhotoUploadScheduler.schedulePhotoUpload(context, existing.clientId, existing.fiscalYearId)
            }
        }
    }

    fun observeAllPhotos(): Flow<List<OfflinePhotoQueueEntity>> = dao.observeAllPhotoQueue()

    fun observePhotosForUser(uid: String): Flow<List<OfflinePhotoQueueEntity>> = dao.observePhotoQueueForUser(uid)

    companion object {
        fun computeFileSha256(file: File): String {
            if (!file.exists() || file.length() <= 0L) return ""
            return runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { inStream ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (inStream.read(buffer).also { read = it } != -1) {
                        digest.update(buffer, 0, read)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }.getOrDefault("")
        }
    }
}
