package com.example.ui.admin

import com.example.data.offline.OfflinePhotoQueueEntity
import com.example.ui.screens.admin.EvidenceFormatting
import com.example.ui.screens.admin.EvidenceItemUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying AdminEvidenceViewer formatting, mapping, leading zero preservation,
 * and state resolution without modifying protected modules.
 */
class AdminEvidenceViewerTest {

  @Test
  fun syncStatusLabel_formatsKnownAndCustomStatuses() {
    assertEquals("Uploaded to Drive", EvidenceFormatting.formatSyncStatusLabel("Uploaded"))
    assertEquals("Uploading...", EvidenceFormatting.formatSyncStatusLabel("Uploading"))
    assertEquals("Upload Failed — Retry Available", EvidenceFormatting.formatSyncStatusLabel("Failed"))
    assertEquals("Upload Failed — Retry Available", EvidenceFormatting.formatSyncStatusLabel("Upload Failed — Retry Available"))
    assertEquals("Saved Locally — Pending Upload", EvidenceFormatting.formatSyncStatusLabel("Saved Locally"))
    assertEquals("Saved Locally — Pending Upload", EvidenceFormatting.formatSyncStatusLabel("Pending Sync"))
    assertEquals("Custom State", EvidenceFormatting.formatSyncStatusLabel("Custom State"))
  }

  @Test
  fun truncateHash_handlesBlankAndLongHashes() {
    assertEquals("Pending Hash", EvidenceFormatting.truncateHash(""))
    assertEquals("Pending Hash", EvidenceFormatting.truncateHash("   "))
    val fullHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    assertEquals("e3b0c44298fc...", EvidenceFormatting.truncateHash(fullHash, 12))
    assertEquals("short", EvidenceFormatting.truncateHash("short", 10))
  }

  @Test
  fun formatTimestamp_formatsIsoTimestampCorrectly() {
    assertEquals("Not uploaded", EvidenceFormatting.formatTimestamp(""))
    val formatted = EvidenceFormatting.formatTimestamp("2026-11-01T12:00:00Z")
    assertTrue("Formatted timestamp must contain 2026-11-01", formatted.contains("2026-11-01"))
  }

  @Test
  fun mapEntityToUiModel_preservesLeadingZerosAndResolvesProperties() {
    val entity = OfflinePhotoQueueEntity(
      photoId = "photo_12345",
      recordKey = "rec_001",
      assetOrObsId = "0001872", // Strict leading zeroes preservation
      recordType = "FAR_COUNT",
      clientId = "client_walton",
      fiscalYearId = "fy_2026",
      localFilePath = "/data/photos/photo_12345.jpg",
      originalLocalFilename = "raw_photo.jpg",
      finalDriveFilename = "abed_0001872_photo12345.jpg",
      driveFileId = "drive_file_999",
      driveWebViewLink = "https://drive.google.com/file/d/drive_file_999/view",
      uploadStatus = "Uploaded",
      errorMessage = "",
      retryCount = 0,
      capturedAtIso = "2026-11-01T10:00:00Z",
      uploadedAtIso = "2026-11-01T10:05:00Z"
    )

    val uiModel = EvidenceFormatting.mapEntityToUiModel(entity, sha256 = "dummy_sha256_hash")

    assertEquals("photo_12345", uiModel.photoId)
    assertEquals("0001872", uiModel.assetId) // Verifies leading zeroes preserved
    assertEquals("abed_0001872_photo12345.jpg", uiModel.filename)
    assertEquals("Uploaded", uiModel.syncStatus)
    assertTrue("Should indicate uploaded", uiModel.isUploaded)
    assertFalse("Should not indicate failed", uiModel.isFailed)
    assertTrue("Should indicate drive link available", uiModel.hasDriveLink)
    assertEquals("https://drive.google.com/file/d/drive_file_999/view", uiModel.driveWebViewLink)
    assertEquals("dummy_sha256_hash", uiModel.sha256Hash)
  }

  @Test
  fun mapEntityToUiModel_handlesFailedStatusAndMissingDriveLink() {
    val entity = OfflinePhotoQueueEntity(
      photoId = "photo_failed",
      recordKey = "rec_002",
      assetOrObsId = "0000042",
      recordType = "FAR_COUNT",
      clientId = "client_walton",
      fiscalYearId = "fy_2026",
      localFilePath = "/data/photos/photo_failed.jpg",
      originalLocalFilename = "raw_photo.jpg",
      finalDriveFilename = "abed_0000042_photo_failed.jpg",
      driveFileId = "",
      driveWebViewLink = "",
      uploadStatus = "Upload Failed — Retry Available",
      errorMessage = "Network connection timed out",
      retryCount = 2,
      capturedAtIso = "2026-11-01T10:00:00Z",
      uploadedAtIso = ""
    )

    val uiModel = EvidenceFormatting.mapEntityToUiModel(entity, sha256 = "")

    assertFalse("Should not have drive link", uiModel.hasDriveLink)
    assertFalse("Should not indicate uploaded", uiModel.isUploaded)
    assertTrue("Should indicate failed", uiModel.isFailed)
    assertEquals("Upload Failed — Retry Available", uiModel.syncStatus)
    assertEquals("Network connection timed out", uiModel.errorMessage)
    assertEquals(2, uiModel.retryCount)
    assertEquals("0000042", uiModel.assetId)
  }
}
