package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.CountingRecord
import com.example.data.model.SyncState
import com.example.data.model.VerificationResultStatus
import com.example.data.offline.OfflinePayloadSerializer
import com.example.data.offline.OfflinePhotoQueueEntity
import com.example.data.offline.OfflineQueuedRecordEntity
import com.example.data.offline.OfflineSyncDatabase
import com.example.data.storage.PhotoStorageManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("AssetFlow", appName)
  }

  @Test
  fun `camera photo capture persistence and multi-photo management works`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val bitmap1 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.BLUE)
    }
    val bitmap2 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.GREEN)
    }
    val bitmapReplacement = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.RED)
    }

    // 1. Capture first photo from camera and verify non-empty file exists on disk
    val path1 = PhotoStorageManager.saveCameraBitmap(context, "FA-10001", bitmap1).getOrThrow()
    assertTrue(File(path1).exists() && File(path1).length() > 0L)

    // 2. Capture second photo and append to photoRef
    val path2 = PhotoStorageManager.saveCameraBitmap(context, "FA-10001", bitmap2).getOrThrow()
    assertTrue(File(path2).exists() && File(path2).length() > 0L)

    var photoRef = PhotoStorageManager.appendPhoto("", path1)
    photoRef = PhotoStorageManager.appendPhoto(photoRef, path2)
    val parsedList = PhotoStorageManager.parsePhotoList(photoRef)
    assertEquals(2, parsedList.size)
    assertEquals(path1, parsedList[0])
    assertEquals(path2, parsedList[1])

    // 3. Replace first photo before saving
    val pathReplacement = PhotoStorageManager.saveCameraBitmap(context, "FA-10001", bitmapReplacement).getOrThrow()
    photoRef = PhotoStorageManager.replacePhotoAt(photoRef, 0, pathReplacement)
    val afterReplace = PhotoStorageManager.parsePhotoList(photoRef)
    assertEquals(2, afterReplace.size)
    assertEquals(pathReplacement, afterReplace[0])
    assertEquals(path2, afterReplace[1])

    // 4. Remove second photo before saving
    photoRef = PhotoStorageManager.removePhotoAt(photoRef, 1)
    val afterRemove = PhotoStorageManager.parsePhotoList(photoRef)
    assertEquals(1, afterRemove.size)
    assertEquals(pathReplacement, afterRemove[0])
    assertFalse(afterRemove.contains(path2))
  }

  // Acceptance Test #3: Offline records and queued photos remain available in durable SQLite after app restart
  @Test
  fun `acceptance03_offlineRecordsAndPhotoQueueRemainAvailableAfterDatabaseRestart`(): Unit = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbFile = File(context.filesDir, "test_restart_offline_sync.db")
    if (dbFile.exists()) dbFile.delete()

    val offlineRecord = CountingRecord(
      id = "FA-2001_ROW_2",
      assetKey = "FA-2001_ROW_2",
      assetId = "FA-2001",
      sourceRow = 2L,
      found = "Yes",
      countDate = "2026-10-28",
      countedBy = "M. Islam",
      countedByUserId = "uid-auditor-1",
      buildingSite = "Chattogram Regional Office",
      floor = "2nd Floor",
      roomLocation = "Room 204",
      department = "Operations",
      tagStatus = "Tag OK",
      tagNumberAcn = "00712A",
      physicalQtyText = "4",
      condition = "Good",
      inUse = "Yes",
      result = VerificationResultStatus.VERIFIED,
      countingStatus = VerificationResultStatus.COUNTED,
      reviewStatus = VerificationResultStatus.PENDING_REVIEW,
      isQueuedOffline = true,
      userId = "ws-owner-1"
    )

    // Session 1: Open file-backed Room database, insert queued record and photo, then close DB (simulating app restart)
    val dbSession1 = Room.databaseBuilder(
      context,
      OfflineSyncDatabase::class.java,
      dbFile.absolutePath
    ).allowMainThreadQueries().build()

    dbSession1.offlineSyncDao().upsertQueuedRecord(
      OfflineQueuedRecordEntity(
        recordKey = offlineRecord.assetKey,
        recordType = "FAR_COUNT",
        assetId = offlineRecord.assetId,
        sourceRow = offlineRecord.sourceRow,
        payloadJson = OfflinePayloadSerializer.serializeCountingRecord(offlineRecord),
        markAsReviewed = false,
        explicitDateEdit = false,
        baseUpdatedAtMillis = 1000L,
        syncStatus = SyncState.PENDING_SYNC,
        userId = "uid-auditor-1",
        userDisplayName = "M. Islam"
      )
    )
    dbSession1.offlineSyncDao().upsertPhotoQueue(
      OfflinePhotoQueueEntity(
        photoId = "PHOTO-FA-2001-1",
        recordKey = offlineRecord.assetKey,
        assetOrObsId = offlineRecord.assetId,
        recordType = "FAR_COUNT",
        localFilePath = "/data/user/0/audit_photos/fa_2001.jpg",
        uploadStatus = SyncState.PENDING_SYNC,
        capturedBy = "M. Islam",
        capturedByUserId = "uid-auditor-1",
        capturedAtIso = "2026-10-28T10:00:00Z"
      )
    )
    dbSession1.close()

    // Session 2: Reopen file-backed Room database from disk after simulated restart and verify data is intact
    val dbSession2 = Room.databaseBuilder(
      context,
      OfflineSyncDatabase::class.java,
      dbFile.absolutePath
    ).allowMainThreadQueries().build()

    val restoredRecords = dbSession2.offlineSyncDao().getPendingOrFailedRecords()
    assertEquals(1, restoredRecords.size)
    assertEquals(SyncState.PENDING_SYNC, restoredRecords.first().syncStatus)
    val deserialized = OfflinePayloadSerializer.deserializeCountingRecord(restoredRecords.first().payloadJson)
    assertEquals("FA-2001", deserialized.assetId)
    assertEquals("Chattogram Regional Office", deserialized.buildingSite)
    assertEquals("00712A", deserialized.tagNumberAcn)

    val restoredPhotos = dbSession2.offlineSyncDao().getPendingOrFailedPhotos()
    assertEquals(1, restoredPhotos.size)
    assertEquals("PHOTO-FA-2001-1", restoredPhotos.first().photoId)
    assertEquals(SyncState.PENDING_SYNC, restoredPhotos.first().uploadStatus)

    dbSession2.close()
    dbFile.delete()
    Unit
  }
}
