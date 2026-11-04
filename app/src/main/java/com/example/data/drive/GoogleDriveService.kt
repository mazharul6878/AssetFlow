package com.example.data.drive

import com.example.data.excel.ExcelWorkbookEngine
import com.example.data.storage.PhotoStorageManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class DriveFolderProvisionResult(
  val rootFolderId: String,
  val photosFolderId: String,
  val reportsFolderId: String,
  val backupsFolderId: String
)

data class ClientDriveProvisionResult(
  val rootAssetFlowFolderId: String,
  val clientRootFolderId: String,
  val imageEvidenceFolderId: String,
  val masterInputFileFolderId: String,
  val dailyInputSheetsFolderId: String
)

data class DriveFileMetadata(
  val fileId: String,
  val name: String,
  val webViewLink: String,
  val mimeType: String,
  val versionNumber: Long = 1L,
  val headRevisionId: String = "",
  val md5Checksum: String = "",
  val modifiedTimeIso: String = "",
  val appProperties: Map<String, String> = emptyMap()
)

data class DriveRevisionMetadata(
  val fileId: String,
  val name: String,
  val headRevisionId: String,
  val md5Checksum: String,
  val modifiedTimeIso: String,
  val appProperties: Map<String, String>
)

data class StandardizedPhotoUploadResult(
  val fileMetadata: DriveFileMetadata,
  val finalDriveFilename: String,
  val fileExtension: String,
  val mimeType: String,
  val dateFolderName: String,
  val parentPhotosFolderId: String,
  val dateFolderId: String,
  val sequenceNumber: Int,
  val captureDateFolder: String = dateFolderName,
  val finalFilename: String = finalDriveFilename,
  val parentFolderId: String = parentPhotosFolderId,
  val driveRelativePath: String = "Image Evidence/$dateFolderName/$finalDriveFilename"
)

class DriveApiException(
  val statusCode: Int,
  val authorizationInvalid: Boolean,
  message: String
) : IllegalStateException(message)

/**
 * Production Google Drive REST v3 client (`https://www.googleapis.com/drive/v3/files` and
 * `https://www.googleapis.com/upload/drive/v3/files`) supporting:
 * 1. Per-client folder hierarchy provisioning (`AssetFlow/[Client Name]/Image Evidence/`, `Master Input File/`, `Daily Input Sheets/`)
 * 2. Revision & MD5 checksum inspection (`getFileRevisionMetadata`) for concurrency and conflict detection
 * 3. Non-destructive in-place Master Excel Workbook `PATCH` updates (`patchMasterWorkbookInPlace`) with `appProperties` crash-recovery metadata
 * 4. Standardized date-wise photo uploads (`AssetFlow/[Client Name]/Image Evidence/{YYYY-MM-DD}/{nickname}_{assetId}.{ext}`)
 * 5. Daily Input Sheet archival (`AssetFlow/[Client Name]/Daily Input Sheets/[Auditor Name]/[Date]/`)
 * 6. Exclusive client folder trash/deletion when explicitly confirmed by Admin (`trashOrDeleteExclusiveClientFolder`)
 */
class GoogleDriveService {

  private val uploadLock = Mutex()
  private val dateFolderCacheMutex = Mutex()
  private val dateFolderIdCache = mutableMapOf<String, String>()

  @Volatile
  var accessToken: String = ""
    private set

  @Volatile
  var connectedAccountEmail: String = ""
    private set

  fun setCredentials(token: String, email: String) {
    accessToken = token.trim()
    connectedAccountEmail = email.trim()
  }

  fun clearCredentials() {
    accessToken = ""
    connectedAccountEmail = ""
    dateFolderIdCache.clear()
  }

  fun disconnectDrive() {
    clearCredentials()
  }

  val isConfigured: Boolean
    get() = accessToken.isNotBlank()

  /**
   * Tests the validity of the current Drive access token by calling the lightweight
   * 'about' endpoint (https://www.googleapis.com/drive/v3/about?fields=user).
   * Returns Result.success(true) if the token is valid, or Result.failure with details if unauthorized or network fails.
   */
  suspend fun testDriveConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
    runCatching {
      requireToken()
      val url = URL("https://www.googleapis.com/drive/v3/about?fields=user")
      val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Authorization", "Bearer $accessToken")
        connectTimeout = 10_000
        readTimeout = 10_000
      }
      val code = conn.responseCode
      if (code in 200..299) {
        true
      } else {
        val err = readStream(conn.errorStream)
        throw driveApiException(code, "Drive connection test failed ($code): $err")
      }
    }
  }

  /**
   * Provisions the per-client Google Drive folder hierarchy:
   * - AssetFlow/
   *   - [Client Name]/
   *     - Image Evidence/
   *     - Master Input File/
   *     - Daily Input Sheets/
   */
  suspend fun provisionClientDriveFolderStructure(
    clientName: String
  ): Result<ClientDriveProvisionResult> = withContext(Dispatchers.IO) {
    runCatching {
      requireToken()
      val cleanClientName = clientName.trim().ifEmpty { "Default Client" }
      val rootAssetFlowId = findOrCreateFolder("AssetFlow", parentId = null)
      val clientRootId = findOrCreateFolder(cleanClientName, parentId = rootAssetFlowId)
      val imageEvidenceId = findOrCreateFolder("Image Evidence", parentId = clientRootId)
      val masterInputId = findOrCreateFolder("Master Input File", parentId = clientRootId)
      val dailySheetsId = findOrCreateFolder("Daily Input Sheets", parentId = clientRootId)

      ClientDriveProvisionResult(
        rootAssetFlowFolderId = rootAssetFlowId,
        clientRootFolderId = clientRootId,
        imageEvidenceFolderId = imageEvidenceId,
        masterInputFileFolderId = masterInputId,
        dailyInputSheetsFolderId = dailySheetsId
      )
    }
  }

  /**
   * Legacy folder structure provisioning kept for backward compatibility.
   */
  suspend fun provisionAuditFolderStructure(
    projectRootFolderName: String = "AssetFlow — Fixed Asset Verification"
  ): Result<DriveFolderProvisionResult> = withContext(Dispatchers.IO) {
    runCatching {
      requireToken()
      val rootId = findOrCreateFolder(projectRootFolderName, parentId = null)
      val photosId = findOrCreateFolder("Image Evidence", parentId = rootId)
      val reportsId = findOrCreateFolder("Daily Input Sheets", parentId = rootId)
      val backupsId = findOrCreateFolder("Master Input File", parentId = rootId)
      DriveFolderProvisionResult(
        rootFolderId = rootId,
        photosFolderId = photosId,
        reportsFolderId = reportsId,
        backupsFolderId = backupsId
      )
    }
  }

  /**
   * Fetches the current headRevisionId, md5Checksum, modifiedTime, and custom appProperties
   * for a Google Drive file so the Master Workbook sync engine can verify concurrency and crash recovery.
   */
  suspend fun getFileRevisionMetadata(
    fileId: String
  ): Result<DriveRevisionMetadata> = withContext(Dispatchers.IO) {
    runCatching {
      requireToken()
      val cleanId = fileId.trim()
      require(cleanId.isNotEmpty()) { "Google Drive file ID cannot be blank." }
      val url = URL(
        "https://www.googleapis.com/drive/v3/files/$cleanId?fields=id,name,headRevisionId,md5Checksum,modifiedTime,appProperties"
      )
      val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Authorization", "Bearer $accessToken")
        connectTimeout = 20_000
        readTimeout = 20_000
      }
      val code = conn.responseCode
      val body = readStream(if (code in 200..299) conn.inputStream else conn.errorStream)
      if (code !in 200..299) {
        throw driveApiException(code, "Failed to inspect Drive file metadata ($code): $body")
      }
      val json = JSONObject(body)
      val appPropsObj = json.optJSONObject("appProperties")
      val appProps = mutableMapOf<String, String>()
      if (appPropsObj != null) {
        appPropsObj.keys().forEach { k -> appProps[k] = appPropsObj.optString(k, "") }
      }
      DriveRevisionMetadata(
        fileId = json.optString("id", cleanId),
        name = json.optString("name", ""),
        headRevisionId = json.optString("headRevisionId", ""),
        md5Checksum = json.optString("md5Checksum", ""),
        modifiedTimeIso = json.optString("modifiedTime", ""),
        appProperties = appProps
      )
    }
  }

  /**
   * Resolves or creates the date-wise photo subfolder `Image Evidence/{YYYY-MM-DD}/`
   * under the client's `Image Evidence` folder.
   */
  suspend fun getOrCreateDatePhotoSubfolder(
    photosParentFolderId: String?,
    dateFolderName: String
  ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
    runCatching {
      requireToken()
      val cleanDate = dateFolderName.trim().take(10)
      require(cleanDate.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) {
        "Invalid date folder format '$cleanDate'. Expected YYYY-MM-DD."
      }
      val effectiveParentId = if (!photosParentFolderId.isNullOrBlank()) {
        photosParentFolderId.trim()
      } else {
        findOrCreateFolder("Image Evidence", parentId = null)
      }

      val cacheKey = "${effectiveParentId}_$cleanDate"
      dateFolderCacheMutex.withLock {
        dateFolderIdCache[cacheKey]?.let { cachedId ->
          return@runCatching Pair(effectiveParentId, cachedId)
        }
      }

      val dateFolderId = findOrCreateFolder(cleanDate, parentId = effectiveParentId)
      dateFolderCacheMutex.withLock {
        dateFolderIdCache[cacheKey] = dateFolderId
      }
      Pair(effectiveParentId, dateFolderId)
    }
  }

  /**
   * Standardized photo upload pipeline:
   * - Validates local image file exists and is non-empty
   * - Preserves leading zeros in `assetIdentifier`
   * - Resolves date subfolder `Image Evidence/{YYYY-MM-DD}/`
   * - Checks existing filenames in that date subfolder to apply `_2`, `_3` sequence suffixes
   * - Performs idempotent upload using `photoId` so retries never create duplicate files
   */
  suspend fun uploadStandardizedAssetPhoto(
    photoId: String,
    localFilePath: String,
    nickname: String,
    assetIdentifier: String,
    capturedAtIso: String,
    photosParentFolderId: String?,
    currentUid: String = "",
    existingNicknameOwners: Map<String, Set<String>> = emptyMap(),
    existingNamesInDateFolderHint: Set<String> = emptySet()
  ): Result<StandardizedPhotoUploadResult> = withContext(Dispatchers.IO) {
    runCatching {
      requireToken()
      val file = File(localFilePath)
      val (isValid, validationError) = PhotoStorageManager.validatePhotoFileBeforeUpload(file)
      require(isValid) { validationError }

      val formatInfo = PhotoStorageManager.detectImageFormat(file, null, file.name)
      val dateFolderName = PhotoStorageManager.resolveDateFolderFromCaptureIso(capturedAtIso)
      val (parentFolderId, dateFolderId) = getOrCreateDatePhotoSubfolder(
        photosParentFolderId = photosParentFolderId,
        dateFolderName = dateFolderName
      ).getOrThrow()

      val remoteNames = runCatching { listFileNamesInFolder(dateFolderId) }.getOrDefault(emptySet())
      val combinedExistingNames = existingNamesInDateFolderHint + remoteNames

      val naming = PhotoStorageManager.buildStandardizedPhotoName(
        rawNickname = nickname,
        currentUid = currentUid,
        rawAssetId = assetIdentifier,
        extension = formatInfo.extension,
        existingNicknamesToUids = existingNicknameOwners,
        existingFilenamesInDateFolder = combinedExistingNames
      )

      val fileBytes = file.readBytes()
      val uploadedMeta = uploadLock.withLock {
        val existingByKey = findFileByIdempotencyKey(
          idempotencyKey = photoId,
          parentId = dateFolderId
        )
        uploadOrUpdateBinaryFile(
          fileName = naming.finalFilename,
          mimeType = formatInfo.mimeType,
          bytes = fileBytes,
          parentId = dateFolderId,
          existingFileId = existingByKey ?: findFileByName(naming.finalFilename, dateFolderId),
          versionNumber = 1L,
          idempotencyKey = photoId
        )
      }

      StandardizedPhotoUploadResult(
        fileMetadata = uploadedMeta,
        finalDriveFilename = naming.finalFilename,
        fileExtension = formatInfo.extension,
        mimeType = formatInfo.mimeType,
        dateFolderName = dateFolderName,
        parentPhotosFolderId = parentFolderId,
        dateFolderId = dateFolderId,
        sequenceNumber = naming.sequenceNumber
      )
    }
  }

  /**
   * Executes a small diagnostic test upload (`{nickname}_TEST-ASSET.jpg`) to verify Admin Google Drive
   * connectivity and date-folder creation.
   */
  suspend fun performTestDriveUpload(
    parentFolderId: String?,
    adminNickname: String = "admin"
  ): Result<StandardizedPhotoUploadResult> = withContext(Dispatchers.IO) {
    runCatching {
      requireToken()
      val nowIso = java.time.LocalDate.now().toString() + "T12:00:00Z"
      val dateFolderName = PhotoStorageManager.resolveDateFolderFromCaptureIso(nowIso)
      val (resolvedParentId, dateFolderId) = getOrCreateDatePhotoSubfolder(
        photosParentFolderId = parentFolderId,
        dateFolderName = dateFolderName
      ).getOrThrow()

      val cleanNick = adminNickname.trim().lowercase().replace(Regex("[^a-z0-9_\\-]"), "").ifEmpty { "admin" }
      val testFileName = "${cleanNick}_TEST-ASSET.jpg"
      val minimalJpegBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x01, 0x00, 0x48, 0x00, 0x48, 0x00, 0x00,
        0xFF.toByte(), 0xD9.toByte()
      )

      val uploadedMeta = uploadLock.withLock {
        val existingId = findFileByIdempotencyKey("ADMIN_TEST_UPLOAD", dateFolderId)
          ?: findFileByName(testFileName, dateFolderId)
        uploadOrUpdateBinaryFile(
          fileName = testFileName,
          mimeType = "image/jpeg",
          bytes = minimalJpegBytes,
          parentId = dateFolderId,
          existingFileId = existingId,
          versionNumber = 1L,
          idempotencyKey = "ADMIN_TEST_UPLOAD"
        )
      }

      StandardizedPhotoUploadResult(
        fileMetadata = uploadedMeta,
        finalDriveFilename = testFileName,
        fileExtension = "jpg",
        mimeType = "image/jpeg",
        dateFolderName = dateFolderName,
        parentPhotosFolderId = resolvedParentId,
        dateFolderId = dateFolderId,
        sequenceNumber = 1
      )
    }
  }

  /**
   * Uploads an initial Master Input Sheet or Asset Register `.xlsx` file to the client's
   * Google Drive folder and returns its `DriveFileMetadata` (including `headRevisionId` and `md5Checksum`).
   */
  suspend fun uploadClientWorkbookToDrive(
    workbookBytes: ByteArray,
    fileName: String,
    parentFolderId: String?,
    existingFileId: String? = null,
    idempotencyKey: String = ""
  ): Result<DriveFileMetadata> = withContext(Dispatchers.IO) {
    uploadLock.withLock {
      runCatching {
        requireToken()
        require(workbookBytes.size > 256) { "Workbook payload is empty or too small." }
        val sheets = ExcelWorkbookEngine.extractAllWorksheetsMap(workbookBytes)
        require(sheets.isNotEmpty()) { "Uploaded workbook contains no readable worksheets." }

        uploadOrUpdateBinaryFile(
          fileName = fileName,
          mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          bytes = workbookBytes,
          parentId = parentFolderId?.takeIf { it.isNotBlank() },
          existingFileId = existingFileId?.takeIf { it.isNotBlank() },
          versionNumber = 1L,
          idempotencyKey = idempotencyKey
        )
      }
    }
  }

  /**
   * Non-destructive in-place `PATCH` update of the persistent Master Input `.xlsx` workbook in Google Drive.
   * Writes `appProperties` (`lastAppliedSyncJobId`, `lastAppliedBatchId`, `lastAppliedVersion`) atomically
   * with the patched binary so crash recovery can detect whether a prior sync job already succeeded on Drive.
   */
  suspend fun patchMasterWorkbookInPlace(
    masterFileId: String,
    masterFileName: String,
    patchedWorkbookBytes: ByteArray,
    versionNumber: Long,
    syncJobId: String,
    appliedBatchId: String
  ): Result<DriveFileMetadata> = withContext(Dispatchers.IO) {
    uploadLock.withLock {
      runCatching {
        requireToken()
        val cleanFileId = masterFileId.trim()
        require(cleanFileId.isNotEmpty()) { "Master Input Workbook Google Drive fileId is required for in-place update." }
        require(patchedWorkbookBytes.size > 256) { "Patched Master Workbook bytes are invalid or empty." }

        val sheets = ExcelWorkbookEngine.extractAllWorksheetsMap(patchedWorkbookBytes)
        require(sheets.isNotEmpty()) { "Patched Master Workbook failed OpenXML verification." }

        uploadOrUpdateBinaryFile(
          fileName = masterFileName.ifBlank { "Master_Input_Sheet.xlsx" },
          mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          bytes = patchedWorkbookBytes,
          parentId = null,
          existingFileId = cleanFileId,
          versionNumber = versionNumber,
          idempotencyKey = syncJobId,
          extraAppProperties = mapOf(
            "lastAppliedSyncJobId" to syncJobId,
            "lastAppliedBatchId" to appliedBatchId,
            "lastAppliedVersion" to versionNumber.toString()
          )
        )
      }
    }
  }

  /**
   * Archives an Auditor's Daily Input Sheet `.xlsx` under:
   * `AssetFlow/[Client Name]/Daily Input Sheets/[Auditor Name]/[Date]/`
   */
  suspend fun uploadDailyInputSheetToDrive(
    dailySheetsRootFolderId: String?,
    auditorName: String,
    countingDate: String,
    fileName: String,
    workbookBytes: ByteArray,
    batchId: String
  ): Result<DriveFileMetadata> = withContext(Dispatchers.IO) {
    uploadLock.withLock {
      runCatching {
        requireToken()
        val rootId = dailySheetsRootFolderId?.takeIf { it.isNotBlank() }
          ?: findOrCreateFolder("Daily Input Sheets", parentId = null)
        val cleanAuditor = auditorName.trim().ifEmpty { "Auditor" }
        val cleanDate = countingDate.trim().take(10).ifEmpty { java.time.LocalDate.now().toString() }
        val auditorFolderId = findOrCreateFolder(cleanAuditor, parentId = rootId)
        val dateFolderId = findOrCreateFolder(cleanDate, parentId = auditorFolderId)
        val existingId = findFileByIdempotencyKey(batchId, dateFolderId)
          ?: findFileByName(fileName, dateFolderId)

        uploadOrUpdateBinaryFile(
          fileName = fileName,
          mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          bytes = workbookBytes,
          parentId = dateFolderId,
          existingFileId = existingId,
          versionNumber = 1L,
          idempotencyKey = batchId
        )
      }
    }
  }

  /**
   * Trashes or permanently deletes a client's exclusive Google Drive folder ONLY when Admin
   * explicitly opts into Drive cleanup during Permanent Client Deletion.
   */
  suspend fun trashOrDeleteExclusiveClientFolder(
    clientRootFolderId: String,
    permanentDelete: Boolean = false
  ): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      requireToken()
      val cleanId = clientRootFolderId.trim()
      require(cleanId.isNotEmpty()) { "Client Drive root folder ID is empty." }

      if (permanentDelete) {
        val url = URL("https://www.googleapis.com/drive/v3/files/$cleanId")
        val conn = (url.openConnection() as HttpURLConnection).apply {
          requestMethod = "DELETE"
          setRequestProperty("Authorization", "Bearer $accessToken")
          connectTimeout = 20_000
          readTimeout = 20_000
        }
        val code = conn.responseCode
        if (code !in 200..299 && code != 404) {
          val err = readStream(conn.errorStream)
          throw driveApiException(code, "Failed to delete client Drive folder ($code): $err")
        }
      } else {
        val url = URL("https://www.googleapis.com/drive/v3/files/$cleanId?fields=id,trashed")
        val payload = JSONObject().put("trashed", true).toString().toByteArray(Charsets.UTF_8)
        val conn = (url.openConnection() as HttpURLConnection).apply {
          requestMethod = "PATCH"
          doOutput = true
          setRequestProperty("Authorization", "Bearer $accessToken")
          setRequestProperty("Content-Type", "application/json; charset=UTF-8")
          connectTimeout = 20_000
          readTimeout = 20_000
        }
        conn.outputStream.use { it.write(payload) }
        val code = conn.responseCode
        if (code !in 200..299 && code != 404) {
          val err = readStream(conn.errorStream)
          throw driveApiException(code, "Failed to move client Drive folder to trash ($code): $err")
        }
      }
    }
  }

  suspend fun validateAndUploadMasterWorkbook(
    workbookBytes: ByteArray,
    masterFileName: String = "FA_Count_Verification_Sheet.xlsx",
    rootFolderId: String?,
    backupsFolderId: String?,
    existingMasterFileId: String?,
    versionNumber: Long,
    idempotencyKey: String = ""
  ): Result<DriveFileMetadata> = withContext(Dispatchers.IO) {
    uploadLock.withLock {
      runCatching {
        requireToken()
        require(workbookBytes.size > 512) {
          "Generated Excel workbook is empty or invalid (${workbookBytes.size} bytes)."
        }

        val extractedSheets = ExcelWorkbookEngine.extractAllWorksheetsMap(workbookBytes)
        require(extractedSheets.isNotEmpty()) {
          "Generated Excel workbook contains no readable worksheets."
        }

        val targetMasterId = existingMasterFileId?.takeIf { it.isNotBlank() }
          ?: idempotencyKey.takeIf { it.isNotBlank() }?.let { key ->
            findFileByIdempotencyKey(key, rootFolderId)
          }
          ?: findFileByName(masterFileName, rootFolderId)

        uploadOrUpdateBinaryFile(
          fileName = masterFileName,
          mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          bytes = workbookBytes,
          parentId = rootFolderId,
          existingFileId = targetMasterId,
          versionNumber = versionNumber,
          idempotencyKey = idempotencyKey
        )
      }
    }
  }

  suspend fun downloadFile(fileId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
    runCatching {
      requireToken()
      require(fileId.isNotBlank()) { "Google Drive file ID cannot be empty." }
      val url = URL("https://www.googleapis.com/drive/v3/files/${fileId.trim()}?alt=media")
      val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Authorization", "Bearer $accessToken")
        connectTimeout = 30_000
        readTimeout = 30_000
      }
      val code = conn.responseCode
      if (code !in 200..299) {
        val err = readStream(conn.errorStream)
        throw driveApiException(code, "Google Drive download failed ($code): $err")
      }
      conn.inputStream.use { it.readBytes() }
    }
  }

  private fun requireToken() {
    if (accessToken.isBlank()) {
      throw DriveApiException(
        statusCode = 401,
        authorizationInvalid = true,
        message = "Google Drive OAuth access token is not configured. Connect Google Drive in Control Panel first."
      )
    }
  }

  private fun findOrCreateFolder(folderName: String, parentId: String?): String {
    val existing = findFileByName(
      name = folderName,
      parentId = parentId,
      mimeTypeFilter = "application/vnd.google-apps.folder"
    )
    if (existing != null) return existing

    val metadata = JSONObject().apply {
      put("name", folderName)
      put("mimeType", "application/vnd.google-apps.folder")
      if (!parentId.isNullOrBlank()) {
        put("parents", JSONArray().put(parentId))
      }
    }
    val url = URL("https://www.googleapis.com/drive/v3/files?fields=id,name")
    val conn = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      doOutput = true
      setRequestProperty("Authorization", "Bearer $accessToken")
      setRequestProperty("Content-Type", "application/json; charset=UTF-8")
      connectTimeout = 20_000
      readTimeout = 20_000
    }
    conn.outputStream.use { it.write(metadata.toString().toByteArray(Charsets.UTF_8)) }
    val code = conn.responseCode
    val responseBody = readStream(if (code in 200..299) conn.inputStream else conn.errorStream)
    if (code !in 200..299) {
      throw driveApiException(code, "Drive folder creation failed ($code): $responseBody")
    }
    return JSONObject(responseBody).getString("id")
  }

  private fun listFileNamesInFolder(parentId: String): Set<String> {
    val qBuilder = StringBuilder("trashed = false and '${parentId.replace("'", "\\'")}' in parents")
    val encodedQ = URLEncoder.encode(qBuilder.toString(), "UTF-8")
    val url = URL("https://www.googleapis.com/drive/v3/files?q=$encodedQ&pageSize=200&fields=files(id,name)")
    val conn = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      setRequestProperty("Authorization", "Bearer $accessToken")
      connectTimeout = 15_000
      readTimeout = 15_000
    }
    val code = conn.responseCode
    if (code !in 200..299) {
      val err = readStream(conn.errorStream)
      throw driveApiException(code, "Drive folder listing failed ($code): $err")
    }
    val body = readStream(conn.inputStream)
    val files = JSONObject(body).optJSONArray("files") ?: return emptySet()
    val names = mutableSetOf<String>()
    for (i in 0 until files.length()) {
      val name = files.optJSONObject(i)?.optString("name").orEmpty()
      if (name.isNotBlank()) names.add(name)
    }
    return names
  }

  private fun findFileByIdempotencyKey(idempotencyKey: String, parentId: String?): String? {
    if (idempotencyKey.isBlank()) return null
    val escapedKey = idempotencyKey.replace("'", "\\'")
    val qBuilder = StringBuilder(
      "trashed = false and appProperties has { key='idempotencyKey' and value='$escapedKey' }"
    )
    if (!parentId.isNullOrBlank()) {
      qBuilder.append(" and '${parentId.replace("'", "\\'")}' in parents")
    }
    val encodedQ = URLEncoder.encode(qBuilder.toString(), "UTF-8")
    val url = URL("https://www.googleapis.com/drive/v3/files?q=$encodedQ&pageSize=1&fields=files(id,name)")
    val conn = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      setRequestProperty("Authorization", "Bearer $accessToken")
      connectTimeout = 15_000
      readTimeout = 15_000
    }
    val code = conn.responseCode
    if (code !in 200..299) {
      val err = readStream(conn.errorStream)
      throw driveApiException(code, "Drive idempotency lookup failed ($code): $err")
    }
    val body = readStream(conn.inputStream)
    val files = JSONObject(body).optJSONArray("files") ?: return null
    return if (files.length() > 0) files.getJSONObject(0).optString("id").takeIf { it.isNotBlank() } else null
  }

  private fun findFileByName(
    name: String,
    parentId: String?,
    mimeTypeFilter: String? = null
  ): String? {
    val escapedName = name.replace("'", "\\'")
    val qBuilder = StringBuilder("name = '$escapedName' and trashed = false")
    if (!parentId.isNullOrBlank()) {
      qBuilder.append(" and '${parentId.replace("'", "\\'")}' in parents")
    }
    if (!mimeTypeFilter.isNullOrBlank()) {
      qBuilder.append(" and mimeType = '$mimeTypeFilter'")
    }
    val encodedQ = URLEncoder.encode(qBuilder.toString(), "UTF-8")
    val url = URL("https://www.googleapis.com/drive/v3/files?q=$encodedQ&pageSize=1&fields=files(id,name)")
    val conn = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      setRequestProperty("Authorization", "Bearer $accessToken")
      connectTimeout = 15_000
      readTimeout = 15_000
    }
    val code = conn.responseCode
    if (code !in 200..299) {
      val err = readStream(conn.errorStream)
      throw driveApiException(code, "Drive file lookup failed ($code): $err")
    }
    val body = readStream(conn.inputStream)
    val files = JSONObject(body).optJSONArray("files") ?: return null
    return if (files.length() > 0) files.getJSONObject(0).optString("id").takeIf { it.isNotBlank() } else null
  }

  private fun uploadOrUpdateBinaryFile(
    fileName: String,
    mimeType: String,
    bytes: ByteArray,
    parentId: String?,
    existingFileId: String?,
    versionNumber: Long,
    idempotencyKey: String = "",
    extraAppProperties: Map<String, String> = emptyMap()
  ): DriveFileMetadata {
    val boundary = "===AuditDriveBoundary_${System.currentTimeMillis()}==="
    val isUpdate = !existingFileId.isNullOrBlank()
    val fieldsParam = "id,name,webViewLink,mimeType,headRevisionId,md5Checksum,modifiedTime,appProperties"
    val endpoint = if (isUpdate) {
      "https://www.googleapis.com/upload/drive/v3/files/${existingFileId!!.trim()}?uploadType=multipart&fields=$fieldsParam"
    } else {
      "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=$fieldsParam"
    }

    val metaJson = JSONObject().apply {
      put("name", fileName)
      if (!isUpdate && !parentId.isNullOrBlank()) {
        put("parents", JSONArray().put(parentId))
      }
      val props = JSONObject()
      if (idempotencyKey.isNotBlank()) {
        props.put("idempotencyKey", idempotencyKey)
      }
      extraAppProperties.forEach { (k, v) -> props.put(k, v) }
      if (props.length() > 0) {
        put("appProperties", props)
      }
    }

    val bodyStream = ByteArrayOutputStream()
    val lineEnd = "\r\n"
    bodyStream.write("--$boundary$lineEnd".toByteArray(Charsets.UTF_8))
    bodyStream.write("Content-Type: application/json; charset=UTF-8$lineEnd$lineEnd".toByteArray(Charsets.UTF_8))
    bodyStream.write(metaJson.toString().toByteArray(Charsets.UTF_8))
    bodyStream.write(lineEnd.toByteArray(Charsets.UTF_8))

    bodyStream.write("--$boundary$lineEnd".toByteArray(Charsets.UTF_8))
    bodyStream.write("Content-Type: $mimeType$lineEnd$lineEnd".toByteArray(Charsets.UTF_8))
    bodyStream.write(bytes)
    bodyStream.write(lineEnd.toByteArray(Charsets.UTF_8))
    bodyStream.write("--$boundary--$lineEnd".toByteArray(Charsets.UTF_8))

    val payloadBytes = bodyStream.toByteArray()
    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = if (isUpdate) "PATCH" else "POST"
      doOutput = true
      setRequestProperty("Authorization", "Bearer $accessToken")
      setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
      setRequestProperty("Content-Length", payloadBytes.size.toString())
      connectTimeout = 30_000
      readTimeout = 30_000
    }
    conn.outputStream.use { it.write(payloadBytes) }

    val code = conn.responseCode
    val resp = readStream(if (code in 200..299) conn.inputStream else conn.errorStream)
    if (code !in 200..299) {
      throw driveApiException(code, "Google Drive upload failed ($code): $resp")
    }
    val json = JSONObject(resp)
    val id = json.optString("id")
    val webLink = json.optString("webViewLink").ifBlank {
      "https://drive.google.com/file/d/$id/view"
    }
    val appPropsObj = json.optJSONObject("appProperties")
    val parsedProps = mutableMapOf<String, String>()
    if (appPropsObj != null) {
      appPropsObj.keys().forEach { k -> parsedProps[k] = appPropsObj.optString(k, "") }
    }
    return DriveFileMetadata(
      fileId = id,
      name = json.optString("name", fileName),
      webViewLink = webLink,
      mimeType = json.optString("mimeType", mimeType),
      versionNumber = versionNumber,
      headRevisionId = json.optString("headRevisionId", ""),
      md5Checksum = json.optString("md5Checksum", ""),
      modifiedTimeIso = json.optString("modifiedTime", ""),
      appProperties = parsedProps
    )
  }

  private fun driveApiException(code: Int, message: String): DriveApiException {
    val authInvalid = code == 401 || code == 403
    if (authInvalid) {
      accessToken = ""
    }
    return DriveApiException(
      statusCode = code,
      authorizationInvalid = authInvalid,
      message = message
    )
  }

  private fun readStream(stream: InputStream?): String {
    if (stream == null) return ""
    return try {
      stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (_: Exception) {
      ""
    }
  }
}
