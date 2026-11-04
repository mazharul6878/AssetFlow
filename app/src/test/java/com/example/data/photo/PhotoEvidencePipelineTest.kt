package com.example.data.photo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.drive.GoogleDriveResumableUploader
import com.example.data.drive.ResumableSessionStore
import com.example.data.drive.ResumableUploadParams
import com.example.data.drive.ResumableUploadResult
import com.example.data.model.SyncState
import com.example.data.model.UserProfile
import com.example.data.network.PhotoEvidenceMetadataDto
import com.example.data.network.PhotoEvidenceResultDto
import com.example.data.network.WorkerApiClient
import com.example.data.network.WorkerApiResult
import com.example.data.offline.OfflineDatabaseGate
import com.example.data.offline.OfflinePhotoQueueEntity
import com.example.data.offline.OfflineSyncDao
import com.example.data.offline.OfflineSyncDatabase
import com.example.data.repository.PhotoEvidenceRepository
import com.example.data.storage.PhotoStorageManager
import com.example.data.worker.PhotoUploadWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoEvidencePipelineTest {

    private lateinit var context: Context
    private lateinit var db: OfflineSyncDatabase
    private lateinit var dao: OfflineSyncDao
    private lateinit var repository: PhotoEvidenceRepository
    private lateinit var testPhotoFile: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)

        db = Room.inMemoryDatabaseBuilder(context, OfflineSyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.offlineSyncDao()
        OfflineDatabaseGate.resetForTesting(com.example.data.offline.OfflineDatabaseStatus.Available(db))
        repository = PhotoEvidenceRepository(context, dao)

        val testDir = File(context.filesDir, "audit_photos").apply { mkdirs() }
        testPhotoFile = File(testDir, "cam_test_000142_${System.currentTimeMillis()}.jpg")
        val dummyBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01)
        testPhotoFile.writeBytes(dummyBytes)
    }

    @After
    fun tearDown() {
        com.example.data.offline.OfflineDatabaseGate.resetForTesting()
        db.close()
        if (::testPhotoFile.isInitialized && testPhotoFile.exists()) {
            testPhotoFile.delete()
        }
    }

    @Test
    fun test01_localCaptureAndSha256PreservingLeadingZeros() = runBlocking {
        val userProfile = UserProfile(
            userId = "uid_auditor_01",
            displayName = "Rahim Auditor",
            nickname = "rahim"
        )

        // Enqueue with leading-zero asset ID: "000142"
        val result = repository.enqueueCapturedPhoto(
            recordKey = "rec_001",
            assetId = "000142", // Strictly preserves leading zeroes
            recordType = "FAR_COUNT",
            clientId = "client_walton",
            fiscalYearId = "2026_27",
            localFilePath = testPhotoFile.absolutePath,
            userProfile = userProfile,
            driveFolderId = "drive_folder_walton_root"
        )

        assertTrue(result.isSuccess)
        val entity = result.getOrThrow()

        // 1. Asset ID preserves leading zero
        assertEquals("000142", entity.assetOrObsId)

        // 2. Standardized unique filename format: <nickname>_<asset_id>_<unique_photo_id>.<extension>
        assertTrue(entity.finalDriveFilename.startsWith("rahim_000142_"))
        assertTrue(entity.finalDriveFilename.endsWith(".jpg"))

        // 3. Status starts at Pending Sync (displayed as Saved Locally in UI)
        assertEquals(SyncState.PENDING_SYNC, entity.uploadStatus)

        // 4. SHA-256 calculation
        val sha256 = PhotoEvidenceRepository.computeFileSha256(testPhotoFile)
        assertTrue(sha256.isNotBlank())
        assertEquals(64, sha256.length)

        // 5. Verified stored in Room database
        val retrieved = dao.getPhotoById(entity.photoId)
        assertNotNull(retrieved)
        assertEquals("000142", retrieved?.assetOrObsId)
        assertTrue(retrieved?.finalDriveFilename?.startsWith("rahim_000142_") == true)
    }

    @Test
    fun test02_staleUploadingProcessRecovery() = runBlocking {
        // Insert a photo with status "Uploading" and lastSyncedAtMillis 2 minutes ago
        val staleEntity = OfflinePhotoQueueEntity(
            photoId = "photo_stale_01",
            recordKey = "rec_stale",
            assetOrObsId = "000500",
            recordType = "FAR_COUNT",
            clientId = "client_walton",
            fiscalYearId = "2026_27",
            localFilePath = testPhotoFile.absolutePath,
            finalDriveFilename = "rahim_000500.jpg",
            uploadStatus = "Uploading",
            queuedAtMillis = System.currentTimeMillis() - 120_000L,
            lastSyncedAtMillis = System.currentTimeMillis() - 120_000L
        )
        dao.upsertPhotoQueue(staleEntity)

        val mockUploader = object : GoogleDriveResumableUploader(
            sessionStore = ResumableSessionStore(context),
            tokenProvider = { "mock-token" }
        ) {
            override suspend fun uploadPhoto(params: ResumableUploadParams): ResumableUploadResult {
                return ResumableUploadResult(
                    fileId = "drive_recovered_ok",
                    fileName = params.standardizedFileName,
                    webViewLink = "https://drive.google.com/file/d/drive_recovered_ok/view",
                    mimeType = params.mimeType
                )
            }
        }

        val mockApiClient = object : WorkerApiClient() {
            override suspend fun registerPhotoEvidence(
                clientId: String,
                cycleId: String,
                payload: PhotoEvidenceMetadataDto,
                bearerTokenOverride: String?
            ): WorkerApiResult<PhotoEvidenceResultDto> {
                return WorkerApiResult.Success(PhotoEvidenceResultDto("Success", false, payload.photoId))
            }
        }

        val worker = TestListenableWorkerBuilder<PhotoUploadWorker>(context)
            .setInputData(Data.Builder().putString("clientId", "client_walton").build())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker {
                    return PhotoUploadWorker(
                        appContext = appContext,
                        workerParams = workerParameters,
                        dao = dao,
                        apiClient = mockApiClient,
                        tokenProvider = { "mock-token" },
                        resumableUploader = mockUploader
                    )
                }
            })
            .build()

        val workResult = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), workResult)

        val updated = dao.getPhotoById("photo_stale_01")
        assertEquals("Uploaded", updated?.uploadStatus)
        assertEquals("drive_recovered_ok", updated?.driveFileId)
    }

    @Test
    fun test03_missingLocalPhotoHandledGracefully() = runBlocking {
        val nonExistentEntity = OfflinePhotoQueueEntity(
            photoId = "photo_missing_file",
            recordKey = "rec_missing",
            assetOrObsId = "000999",
            clientId = "client_walton",
            fiscalYearId = "2026_27",
            localFilePath = "/data/user/0/com.example/files/audit_photos/non_existent.jpg",
            finalDriveFilename = "rahim_000999.jpg",
            uploadStatus = SyncState.PENDING_SYNC,
            queuedAtMillis = System.currentTimeMillis()
        )
        dao.upsertPhotoQueue(nonExistentEntity)

        val worker = TestListenableWorkerBuilder<PhotoUploadWorker>(context)
            .setInputData(Data.Builder().putString("clientId", "client_walton").build())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker {
                    return PhotoUploadWorker(
                        appContext = appContext,
                        workerParams = workerParameters,
                        dao = dao,
                        apiClient = WorkerApiClient()
                    )
                }
            })
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val updated = dao.getPhotoById("photo_missing_file")
        assertEquals("Upload Failed — Retry Available", updated?.uploadStatus)
        assertTrue(updated?.errorMessage?.contains("missing", ignoreCase = true) == true)
    }

    @Test
    fun test04_retryPhotoUploadResetsStatusAndRetries() = runBlocking {
        val failedEntity = OfflinePhotoQueueEntity(
            photoId = "photo_failed_retry",
            recordKey = "rec_failed",
            assetOrObsId = "000777",
            clientId = "client_walton",
            fiscalYearId = "2026_27",
            localFilePath = testPhotoFile.absolutePath,
            uploadStatus = "Upload Failed — Retry Available",
            errorMessage = "Connection dropped",
            retryCount = 4
        )
        dao.upsertPhotoQueue(failedEntity)

        // Retry via repository
        val retryRes = repository.retryPhotoUpload("photo_failed_retry")
        assertTrue(retryRes.isSuccess)

        val resetPhoto = dao.getPhotoById("photo_failed_retry")
        assertEquals(SyncState.PENDING_SYNC, resetPhoto?.uploadStatus)
        assertEquals(0, resetPhoto?.retryCount)
        assertEquals("", resetPhoto?.errorMessage)
    }

    @Test
    fun test05_fullWorkerPipelineDriveAndD1Registration() = runBlocking {
        val entity = OfflinePhotoQueueEntity(
            photoId = "photo_full_pipe",
            recordKey = "rec_full",
            assetOrObsId = "000142",
            recordType = "FAR_COUNT",
            clientId = "client_walton",
            fiscalYearId = "2026_27",
            localFilePath = testPhotoFile.absolutePath,
            finalDriveFilename = "rahim_000142.jpg",
            uploadStatus = SyncState.PENDING_SYNC,
            driveFolderId = "folder_root_123",
            queuedAtMillis = System.currentTimeMillis()
        )
        dao.upsertPhotoQueue(entity)

        var driveUploadCalled = false
        var d1RegisterCalled = false
        var registeredAssetId = ""

        val mockUploader = object : GoogleDriveResumableUploader(
            sessionStore = ResumableSessionStore(context),
            tokenProvider = { "mock-token" }
        ) {
            override suspend fun uploadPhoto(params: ResumableUploadParams): ResumableUploadResult {
                driveUploadCalled = true
                return ResumableUploadResult(
                    fileId = "drive_pipe_file_id",
                    fileName = params.standardizedFileName,
                    webViewLink = "https://drive.google.com/file/d/drive_pipe_file_id/view",
                    mimeType = params.mimeType
                )
            }
        }

        val mockApiClient = object : WorkerApiClient() {
            override suspend fun registerPhotoEvidence(
                clientId: String,
                cycleId: String,
                payload: PhotoEvidenceMetadataDto,
                bearerTokenOverride: String?
            ): WorkerApiResult<PhotoEvidenceResultDto> {
                d1RegisterCalled = true
                registeredAssetId = payload.assetId
                return WorkerApiResult.Success(PhotoEvidenceResultDto("Success", false, payload.photoId))
            }
        }

        val worker = TestListenableWorkerBuilder<PhotoUploadWorker>(context)
            .setInputData(Data.Builder().putString("clientId", "client_walton").build())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker {
                    return PhotoUploadWorker(
                        appContext = appContext,
                        workerParams = workerParameters,
                        dao = dao,
                        apiClient = mockApiClient,
                        tokenProvider = { "mock-token" },
                        resumableUploader = mockUploader
                    )
                }
            })
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        assertTrue(driveUploadCalled)
        assertTrue(d1RegisterCalled)
        assertEquals("000142", registeredAssetId) // Leading zeros preserved in D1 registration

        val finished = dao.getPhotoById("photo_full_pipe")
        assertEquals("Uploaded", finished?.uploadStatus)
        assertEquals("drive_pipe_file_id", finished?.driveFileId)
    }

    @Test
    fun test06_duplicateEvidenceDetectedOnDriveSurfacesErrorState() = runBlocking {
        val entity = OfflinePhotoQueueEntity(
            photoId = "photo_dup_test",
            recordKey = "rec_dup",
            assetOrObsId = "000142",
            clientId = "client_walton",
            fiscalYearId = "2026_27",
            localFilePath = testPhotoFile.absolutePath,
            finalDriveFilename = "rahim_000142_dup.jpg",
            uploadStatus = SyncState.PENDING_SYNC,
            queuedAtMillis = System.currentTimeMillis()
        )
        dao.upsertPhotoQueue(entity)

        val mockUploader = object : GoogleDriveResumableUploader(
            sessionStore = ResumableSessionStore(context),
            tokenProvider = { "mock-token" }
        ) {
            override suspend fun uploadPhoto(params: ResumableUploadParams): ResumableUploadResult {
                throw com.example.data.drive.DriveDuplicateEvidenceException(
                    "Duplicate evidence files detected on Google Drive: [f1, f2]",
                    listOf("f1", "f2")
                )
            }
        }

        val worker = TestListenableWorkerBuilder<PhotoUploadWorker>(context)
            .setInputData(Data.Builder().putString("clientId", "client_walton").build())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker {
                    return PhotoUploadWorker(
                        appContext = appContext,
                        workerParams = workerParameters,
                        dao = dao,
                        apiClient = WorkerApiClient(),
                        tokenProvider = { "mock-token" },
                        resumableUploader = mockUploader
                    )
                }
            })
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val updated = dao.getPhotoById("photo_dup_test")
        assertEquals("Upload Failed — Retry Available", updated?.uploadStatus)
        assertTrue(updated?.errorMessage?.contains("Duplicate evidence", ignoreCase = true) == true)
    }

    @Test
    fun test07_metadataOnlyRetryWhenDriveFileIdAlreadyExists() = runBlocking {
        // Photo already uploaded to Drive, but D1 registration pending
        val entity = OfflinePhotoQueueEntity(
            photoId = "photo_metadata_retry",
            recordKey = "rec_meta",
            assetOrObsId = "000888",
            clientId = "client_walton",
            fiscalYearId = "2026_27",
            localFilePath = testPhotoFile.absolutePath,
            finalDriveFilename = "rahim_000888_m1.jpg",
            driveFileId = "drive_existing_id_777",
            driveWebViewLink = "https://drive.google.com/file/d/drive_existing_id_777/view",
            uploadStatus = "Metadata Sync Pending",
            queuedAtMillis = System.currentTimeMillis()
        )
        dao.upsertPhotoQueue(entity)

        var driveUploadAttempted = false
        var d1RegisterAttempted = false

        val mockUploader = object : GoogleDriveResumableUploader(
            sessionStore = ResumableSessionStore(context),
            tokenProvider = { "mock-token" }
        ) {
            override suspend fun uploadPhoto(params: ResumableUploadParams): ResumableUploadResult {
                driveUploadAttempted = true
                throw IllegalStateException("Drive upload should be skipped when driveFileId exists!")
            }
        }

        val mockApiClient = object : WorkerApiClient() {
            override suspend fun registerPhotoEvidence(
                clientId: String,
                cycleId: String,
                payload: PhotoEvidenceMetadataDto,
                bearerTokenOverride: String?
            ): WorkerApiResult<PhotoEvidenceResultDto> {
                d1RegisterAttempted = true
                return WorkerApiResult.Success(PhotoEvidenceResultDto("Success", false, payload.photoId))
            }
        }

        val worker = TestListenableWorkerBuilder<PhotoUploadWorker>(context)
            .setInputData(Data.Builder().putString("clientId", "client_walton").build())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker {
                    return PhotoUploadWorker(
                        appContext = appContext,
                        workerParams = workerParameters,
                        dao = dao,
                        apiClient = mockApiClient,
                        tokenProvider = { "mock-token" },
                        resumableUploader = mockUploader
                    )
                }
            })
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(!driveUploadAttempted)
        assertTrue(d1RegisterAttempted)

        val updated = dao.getPhotoById("photo_metadata_retry")
        assertEquals("Uploaded", updated?.uploadStatus)
        assertEquals("drive_existing_id_777", updated?.driveFileId)
    }
}
