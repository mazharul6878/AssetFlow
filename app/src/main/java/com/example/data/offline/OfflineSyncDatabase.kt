package com.example.data.offline

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.CountingRecord
import com.example.data.model.FoundNotInFarRecord
import com.example.data.model.SyncState
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * Local SQLite offline queue entity for physical counting and manual unregistered asset records.
 * Scoped by `userId`, `clientId`, and `fiscalYearId`.
 */
@Entity(tableName = "offline_queued_records")
data class OfflineQueuedRecordEntity(
  @PrimaryKey val recordKey: String,
  val recordType: String = "FAR_COUNT", // "FAR_COUNT" or "UNREGISTERED_OBS"
  val assetId: String = "",
  val sourceRow: Long = 0L,
  val clientId: String = "",
  val fiscalYearId: String = "",
  val payloadJson: String = "",
  val markAsReviewed: Boolean = false,
  val explicitDateEdit: Boolean = false,
  val baseUpdatedAtMillis: Long = 0L,
  val syncStatus: String = SyncState.PENDING_SYNC,
  val errorMessage: String = "",
  val retryCount: Int = 0,
  val userId: String = "",
  val userDisplayName: String = "",
  val queuedAtMillis: Long = System.currentTimeMillis(),
  val lastSyncedAtMillis: Long = 0L
)

/**
 * Local SQLite queue for asset photo uploads to Google Drive (`AssetFlow/[Client Name]/Image Evidence/[YYYY-MM-DD]/`).
 * Zero Firebase Storage usage: photos are stored in app-private storage (`context.filesDir/audit_photos/`)
 * until uploaded to Google Drive.
 */
@Entity(tableName = "offline_photo_queue")
data class OfflinePhotoQueueEntity(
  @PrimaryKey val photoId: String,
  val recordKey: String,
  val assetOrObsId: String,
  val recordType: String = "FAR_COUNT",
  val clientId: String = "",
  val fiscalYearId: String = "",
  val localFilePath: String,
  val originalLocalFilename: String = "",
  val finalDriveFilename: String = "",
  val mimeType: String = "image/jpeg",
  val fileExtension: String = "jpg",
  val nicknameSnapshot: String = "",
  val photoSequenceNumber: Int = 0,
  val schemaVersion: Long = 1L,
  val dateFolderName: String = "",
  val driveFolderId: String = "",
  val driveDateFolderId: String = "",
  val driveFileId: String = "",
  val driveWebViewLink: String = "",
  val uploadStatus: String = SyncState.PENDING_SYNC,
  val errorMessage: String = "",
  val retryCount: Int = 0,
  val capturedBy: String = "",
  val capturedByUserId: String = "",
  val capturedAtIso: String = "",
  val uploadedAtIso: String = "",
  val queuedAtMillis: Long = System.currentTimeMillis(),
  val lastSyncedAtMillis: Long = 0L,
  val leaseOwnerToken: String = "",
  val leaseExpiresAtMillis: Long = 0L
) {
  val targetDriveFileName: String
    get() = finalDriveFilename.ifBlank { originalLocalFilename.ifBlank { photoId } }
}

@Dao
interface OfflineSyncDao {
  @Query("SELECT * FROM offline_queued_records ORDER BY queuedAtMillis DESC")
  fun observeAllQueuedRecords(): Flow<List<OfflineQueuedRecordEntity>>

  @Query("SELECT * FROM offline_queued_records WHERE userId = :uid ORDER BY queuedAtMillis DESC")
  fun observeQueuedRecordsForUser(uid: String): Flow<List<OfflineQueuedRecordEntity>>

  @Query("SELECT * FROM offline_queued_records WHERE syncStatus IN ('Pending Sync', 'Failed') ORDER BY queuedAtMillis ASC")
  suspend fun getPendingOrFailedRecords(): List<OfflineQueuedRecordEntity>

  @Query("SELECT * FROM offline_queued_records WHERE userId = :uid AND syncStatus IN ('Pending Sync', 'Failed') ORDER BY queuedAtMillis ASC")
  suspend fun getPendingOrFailedRecordsForUser(uid: String): List<OfflineQueuedRecordEntity>

  @Query("SELECT * FROM offline_queued_records WHERE recordKey = :key LIMIT 1")
  suspend fun getQueuedRecordByKey(key: String): OfflineQueuedRecordEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertQueuedRecord(entity: OfflineQueuedRecordEntity)

  @Query(
    "UPDATE offline_queued_records SET syncStatus = :status, errorMessage = :error, retryCount = :retries, lastSyncedAtMillis = :syncedAt WHERE recordKey = :recordKey"
  )
  suspend fun updateRecordSyncStatus(
    recordKey: String,
    status: String,
    error: String,
    retries: Int,
    syncedAt: Long
  )

  @Query("DELETE FROM offline_queued_records WHERE recordKey = :recordKey")
  suspend fun deleteQueuedRecord(recordKey: String)

  @Query("DELETE FROM offline_queued_records WHERE clientId = :clientId")
  suspend fun deleteQueuedRecordsForClient(clientId: String)

  @Query("DELETE FROM offline_queued_records WHERE userId = :uid")
  suspend fun deleteQueuedRecordsForUser(uid: String)

  // --- Photo Queue ---
  @Query("SELECT * FROM offline_photo_queue ORDER BY queuedAtMillis DESC")
  fun observeAllPhotoQueue(): Flow<List<OfflinePhotoQueueEntity>>

  @Query("SELECT * FROM offline_photo_queue WHERE capturedByUserId = :uid ORDER BY queuedAtMillis DESC")
  fun observePhotoQueueForUser(uid: String): Flow<List<OfflinePhotoQueueEntity>>

  @Query("SELECT * FROM offline_photo_queue WHERE recordKey = :recordKey ORDER BY queuedAtMillis ASC")
  suspend fun getPhotosForRecord(recordKey: String): List<OfflinePhotoQueueEntity>

  @Query("SELECT * FROM offline_photo_queue WHERE photoId = :photoId LIMIT 1")
  suspend fun getPhotoById(photoId: String): OfflinePhotoQueueEntity?

  @Query("SELECT * FROM offline_photo_queue WHERE uploadStatus IN ('Pending Sync', 'Failed') ORDER BY queuedAtMillis ASC")
  suspend fun getPendingOrFailedPhotos(): List<OfflinePhotoQueueEntity>

  @Query("SELECT * FROM offline_photo_queue WHERE capturedByUserId = :uid AND uploadStatus IN ('Pending Sync', 'Failed') ORDER BY queuedAtMillis ASC")
  suspend fun getPendingOrFailedPhotosForUser(uid: String): List<OfflinePhotoQueueEntity>

  @Query("SELECT * FROM offline_photo_queue WHERE clientId = :clientId")
  suspend fun getPhotosForClient(clientId: String): List<OfflinePhotoQueueEntity>

  @Query("SELECT * FROM offline_photo_queue WHERE capturedByUserId = :uid")
  suspend fun getPhotosForUser(uid: String): List<OfflinePhotoQueueEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertPhotoQueue(entity: OfflinePhotoQueueEntity)

  @Query(
    "UPDATE offline_photo_queue SET uploadStatus = :status, driveFileId = :driveFileId, driveWebViewLink = :webLink, errorMessage = :error, retryCount = :retries, lastSyncedAtMillis = :syncedAt, driveDateFolderId = :dateFolderId, dateFolderName = :dateFolderName, finalDriveFilename = :finalName, fileExtension = :ext, mimeType = :mime, photoSequenceNumber = :seq, uploadedAtIso = :uploadedIso WHERE photoId = :photoId"
  )
  suspend fun updatePhotoUploadStatus(
    photoId: String,
    status: String,
    driveFileId: String,
    webLink: String,
    error: String,
    retries: Int,
    syncedAt: Long,
    dateFolderId: String = "",
    dateFolderName: String = "",
    finalName: String = "",
    ext: String = "jpg",
    mime: String = "image/jpeg",
    seq: Int = 0,
    uploadedIso: String = ""
  )

  @Query(
    "UPDATE offline_photo_queue SET uploadStatus = :newStatus, leaseOwnerToken = :ownerToken, leaseExpiresAtMillis = :expiresAtMillis, lastSyncedAtMillis = :nowMillis WHERE photoId = :photoId AND uploadStatus IN ('Pending Sync', 'Saved Locally', 'Metadata Sync Pending', 'Waiting for Internet', 'Failed', 'Upload Failed — Retry Available', 'Uploading') AND (leaseOwnerToken = '' OR leaseExpiresAtMillis <= :nowMillis OR leaseOwnerToken = :ownerToken)"
  )
  suspend fun claimPhotoLease(
    photoId: String,
    ownerToken: String,
    newStatus: String,
    expiresAtMillis: Long,
    nowMillis: Long
  ): Int

  @Query(
    "UPDATE offline_photo_queue SET leaseExpiresAtMillis = :newExpiresAtMillis WHERE photoId = :photoId AND leaseOwnerToken = :ownerToken"
  )
  suspend fun renewPhotoLease(
    photoId: String,
    ownerToken: String,
    newExpiresAtMillis: Long
  ): Int

  @Query(
    "UPDATE offline_photo_queue SET uploadStatus = :status, driveFileId = :driveFileId, driveWebViewLink = :webLink, errorMessage = :error, retryCount = :retries, lastSyncedAtMillis = :syncedAt, driveDateFolderId = :dateFolderId, dateFolderName = :dateFolderName, finalDriveFilename = :finalName, fileExtension = :ext, mimeType = :mime, photoSequenceNumber = :seq, uploadedAtIso = :uploadedIso, leaseExpiresAtMillis = :newExpiresAtMillis WHERE photoId = :photoId AND leaseOwnerToken = :ownerToken"
  )
  suspend fun updatePhotoIntermediateStatusWithLease(
    photoId: String,
    ownerToken: String,
    status: String,
    driveFileId: String,
    webLink: String,
    error: String,
    retries: Int,
    syncedAt: Long,
    dateFolderId: String = "",
    dateFolderName: String = "",
    finalName: String = "",
    ext: String = "jpg",
    mime: String = "image/jpeg",
    seq: Int = 0,
    uploadedIso: String = "",
    newExpiresAtMillis: Long
  ): Int

  @Query(
    "UPDATE offline_photo_queue SET uploadStatus = :status, driveFileId = :driveFileId, driveWebViewLink = :webLink, errorMessage = :error, retryCount = :retries, lastSyncedAtMillis = :syncedAt, driveDateFolderId = :dateFolderId, dateFolderName = :dateFolderName, finalDriveFilename = :finalName, fileExtension = :ext, mimeType = :mime, photoSequenceNumber = :seq, uploadedAtIso = :uploadedIso, leaseOwnerToken = '', leaseExpiresAtMillis = 0 WHERE photoId = :photoId AND leaseOwnerToken = :ownerToken"
  )
  suspend fun updatePhotoStatusWithLease(
    photoId: String,
    ownerToken: String,
    status: String,
    driveFileId: String,
    webLink: String,
    error: String,
    retries: Int,
    syncedAt: Long,
    dateFolderId: String = "",
    dateFolderName: String = "",
    finalName: String = "",
    ext: String = "jpg",
    mime: String = "image/jpeg",
    seq: Int = 0,
    uploadedIso: String = ""
  ): Int

  @Query(
    "UPDATE offline_photo_queue SET uploadStatus = :newStatus, errorMessage = :error, leaseOwnerToken = '', leaseExpiresAtMillis = 0, lastSyncedAtMillis = :nowMillis WHERE photoId = :photoId AND uploadStatus IN ('Uploading', 'Metadata Sync Pending') AND leaseExpiresAtMillis <= :nowMillis"
  )
  suspend fun recoverExpiredPhotoLease(
    photoId: String,
    newStatus: String,
    error: String,
    nowMillis: Long
  ): Int

  @Query("DELETE FROM offline_photo_queue WHERE clientId = :clientId")
  suspend fun deleteQueuedPhotosForClient(clientId: String)

  @Query("DELETE FROM offline_photo_queue WHERE capturedByUserId = :uid")
  suspend fun deleteQueuedPhotosForUser(uid: String)
}

@Database(
  entities = [
    OfflineQueuedRecordEntity::class,
    OfflinePhotoQueueEntity::class
  ],
  version = 3,
  exportSchema = true
)
abstract class OfflineSyncDatabase : RoomDatabase() {
  abstract fun offlineSyncDao(): OfflineSyncDao

  companion object {
    val MIGRATION_2_3 = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE offline_photo_queue ADD COLUMN leaseOwnerToken TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE offline_photo_queue ADD COLUMN leaseExpiresAtMillis INTEGER NOT NULL DEFAULT 0")
      }
    }

    @Volatile
    private var INSTANCE: OfflineSyncDatabase? = null

    fun getInstance(context: Context): OfflineSyncDatabase {
      return INSTANCE ?: synchronized(this) {
        val instance = Room.databaseBuilder(
          context.applicationContext,
          OfflineSyncDatabase::class.java,
          "audit_offline_sync_db"
        )
          .addMigrations(MIGRATION_2_3)
          .build()
        INSTANCE = instance
        instance
      }
    }
  }
}

/**
 * JSON serializer/deserializer for offline queue payloads so Room entities remain compact and schema-safe.
 */
object OfflinePayloadSerializer {
  fun serializeCountingRecord(rec: CountingRecord): String {
    return JSONObject().apply {
      put("id", rec.id)
      put("assetKey", rec.assetKey)
      put("assetId", rec.assetId)
      put("sourceRow", rec.sourceRow)
      put("clientId", rec.clientId)
      put("fiscalYearId", rec.fiscalYearId)
      put("found", rec.found)
      put("countDate", rec.countDate)
      put("countedBy", rec.countedBy)
      put("countedByUserId", rec.countedByUserId)
      put("buildingSite", rec.buildingSite)
      put("buildingSiteOther", rec.buildingSiteOther)
      put("floor", rec.floor)
      put("floorOther", rec.floorOther)
      put("roomLocation", rec.roomLocation)
      put("roomLocationOther", rec.roomLocationOther)
      put("department", rec.department)
      put("departmentOther", rec.departmentOther)
      put("userCustodian", rec.userCustodian)
      put("tagStatus", rec.tagStatus)
      put("tagNumberAcn", rec.tagNumberAcn)
      put("brandModel", rec.brandModel)
      put("physicalQtyText", rec.physicalQtyText)
      put("condition", rec.condition)
      put("inUse", rec.inUse)
      put("photoRef", rec.photoRef)
      put("remarks", rec.remarks)
      put("qtyDiffText", rec.qtyDiffText)
      put("result", rec.result)
      put("countingStatus", rec.countingStatus)
      put("applicableExceptions", rec.applicableExceptions)
      put("reviewedBy", rec.reviewedBy)
      put("reviewedByUserId", rec.reviewedByUserId)
      put("reviewerRequiredFlag", rec.reviewerRequiredFlag)
      put("reviewedAt", rec.reviewedAt)
      put("reviewStatus", rec.reviewStatus)
      put("clientExplanation", rec.clientExplanation)
      put("isDraft", rec.isDraft)
      put("auditTrailSummary", rec.auditTrailSummary)
      put("dynamicFieldValuesJson", rec.dynamicFieldValuesJson)
      put("userId", rec.userId)
    }.toString()
  }

  fun deserializeCountingRecord(jsonStr: String): CountingRecord {
    val obj = JSONObject(jsonStr)
    return CountingRecord(
      id = obj.optString("id"),
      assetKey = obj.optString("assetKey"),
      assetId = obj.optString("assetId"),
      sourceRow = obj.optLong("sourceRow", 1L),
      clientId = obj.optString("clientId"),
      fiscalYearId = obj.optString("fiscalYearId"),
      found = obj.optString("found"),
      countDate = obj.optString("countDate"),
      countedBy = obj.optString("countedBy"),
      countedByUserId = obj.optString("countedByUserId"),
      buildingSite = obj.optString("buildingSite"),
      buildingSiteOther = obj.optString("buildingSiteOther"),
      floor = obj.optString("floor"),
      floorOther = obj.optString("floorOther"),
      roomLocation = obj.optString("roomLocation"),
      roomLocationOther = obj.optString("roomLocationOther"),
      department = obj.optString("department"),
      departmentOther = obj.optString("departmentOther"),
      userCustodian = obj.optString("userCustodian"),
      tagStatus = obj.optString("tagStatus"),
      tagNumberAcn = obj.optString("tagNumberAcn"),
      brandModel = obj.optString("brandModel"),
      physicalQtyText = obj.optString("physicalQtyText"),
      condition = obj.optString("condition"),
      inUse = obj.optString("inUse"),
      photoRef = obj.optString("photoRef"),
      remarks = obj.optString("remarks"),
      qtyDiffText = obj.optString("qtyDiffText"),
      result = obj.optString("result", "Pending"),
      countingStatus = obj.optString("countingStatus", "Not Counted"),
      applicableExceptions = obj.optString("applicableExceptions"),
      reviewedBy = obj.optString("reviewedBy"),
      reviewedByUserId = obj.optString("reviewedByUserId"),
      reviewerRequiredFlag = obj.optBoolean("reviewerRequiredFlag", false),
      reviewedAt = obj.optString("reviewedAt"),
      reviewStatus = obj.optString("reviewStatus", "Not Required"),
      clientExplanation = obj.optString("clientExplanation"),
      isDraft = obj.optBoolean("isDraft", false),
      auditTrailSummary = obj.optString("auditTrailSummary"),
      dynamicFieldValuesJson = obj.optString("dynamicFieldValuesJson", "{}"),
      userId = obj.optString("userId")
    )
  }

  fun serializeFoundNotInFar(rec: FoundNotInFarRecord): String {
    return JSONObject().apply {
      put("id", rec.id)
      put("tempId", rec.tempId)
      put("assetIdIfKnown", rec.assetIdIfKnown)
      put("clientId", rec.clientId)
      put("fiscalYearId", rec.fiscalYearId)
      put("description", rec.description)
      put("assetClass", rec.assetClass)
      put("buildingSite", rec.buildingSite)
      put("buildingSiteOther", rec.buildingSiteOther)
      put("floor", rec.floor)
      put("floorOther", rec.floorOther)
      put("roomLocation", rec.roomLocation)
      put("roomLocationOther", rec.roomLocationOther)
      put("department", rec.department)
      put("departmentOther", rec.departmentOther)
      put("userCustodian", rec.userCustodian)
      put("tagStatus", rec.tagStatus)
      put("tagNumberAcn", rec.tagNumberAcn)
      put("brandModel", rec.brandModel)
      put("physicalQtyText", rec.physicalQtyText)
      put("physicalQty", rec.physicalQty)
      put("found", rec.found)
      put("condition", rec.condition)
      put("inUse", rec.inUse)
      put("countDate", rec.countDate)
      put("countedBy", rec.countedBy)
      put("countedByUserId", rec.countedByUserId)
      put("reviewedBy", rec.reviewedBy)
      put("reviewedByUserId", rec.reviewedByUserId)
      put("reviewedAt", rec.reviewedAt)
      put("photoRef", rec.photoRef)
      put("remarks", rec.remarks)
      put("clientExplanation", rec.clientExplanation)
      put("recordStatus", rec.recordStatus)
      put("classificationStatus", rec.classificationStatus)
      put("isDraft", rec.isDraft)
      put("auditTrailSummary", rec.auditTrailSummary)
      put("userId", rec.userId)
    }.toString()
  }

  fun deserializeFoundNotInFar(jsonStr: String): FoundNotInFarRecord {
    val obj = JSONObject(jsonStr)
    return FoundNotInFarRecord(
      id = obj.optString("id"),
      tempId = obj.optString("tempId"),
      assetIdIfKnown = obj.optString("assetIdIfKnown"),
      clientId = obj.optString("clientId"),
      fiscalYearId = obj.optString("fiscalYearId"),
      description = obj.optString("description"),
      assetClass = obj.optString("assetClass"),
      buildingSite = obj.optString("buildingSite"),
      buildingSiteOther = obj.optString("buildingSiteOther"),
      floor = obj.optString("floor"),
      floorOther = obj.optString("floorOther"),
      roomLocation = obj.optString("roomLocation"),
      roomLocationOther = obj.optString("roomLocationOther"),
      department = obj.optString("department"),
      departmentOther = obj.optString("departmentOther"),
      userCustodian = obj.optString("userCustodian"),
      tagStatus = obj.optString("tagStatus"),
      tagNumberAcn = obj.optString("tagNumberAcn"),
      brandModel = obj.optString("brandModel"),
      physicalQtyText = obj.optString("physicalQtyText"),
      physicalQty = obj.optLong("physicalQty", 0L),
      found = obj.optString("found", "Yes"),
      condition = obj.optString("condition"),
      inUse = obj.optString("inUse"),
      countDate = obj.optString("countDate"),
      countedBy = obj.optString("countedBy"),
      countedByUserId = obj.optString("countedByUserId"),
      reviewedBy = obj.optString("reviewedBy"),
      reviewedByUserId = obj.optString("reviewedByUserId"),
      reviewedAt = obj.optString("reviewedAt"),
      photoRef = obj.optString("photoRef"),
      remarks = obj.optString("remarks"),
      clientExplanation = obj.optString("clientExplanation"),
      recordStatus = obj.optString("recordStatus", "Pending Review"),
      classificationStatus = obj.optString("classificationStatus", "Unregistered Asset — Pending FAR Verification"),
      isDraft = obj.optBoolean("isDraft", false),
      auditTrailSummary = obj.optString("auditTrailSummary"),
      userId = obj.optString("userId")
    )
  }
}
