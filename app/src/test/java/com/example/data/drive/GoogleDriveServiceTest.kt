package com.example.data.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveServiceTest {

  @Test
  fun setAndClearCredentials_updatesConfiguredStateAndConnectedAccountEmail() {
    val service = GoogleDriveService()
    assertFalse(service.isConfigured)
    assertEquals("", service.connectedAccountEmail)

    service.setCredentials(token = "test-oauth-token-123", email = "mislamabed@gmail.com")
    assertTrue(service.isConfigured)
    assertEquals("test-oauth-token-123", service.accessToken)
    assertEquals("mislamabed@gmail.com", service.connectedAccountEmail)

    service.clearCredentials()
    assertFalse(service.isConfigured)
    assertEquals("", service.accessToken)
    assertEquals("", service.connectedAccountEmail)
  }

  @Test
  fun standardizedPhotoUploadResult_computesExpectedDriveRelativePath() {
    val meta = DriveFileMetadata(
      fileId = "file-123",
      name = "abed_00042.jpg",
      webViewLink = "https://drive.google.com/file/d/file-123/view",
      mimeType = "image/jpeg"
    )
    val result = StandardizedPhotoUploadResult(
      fileMetadata = meta,
      finalDriveFilename = "abed_00042.jpg",
      fileExtension = "jpg",
      mimeType = "image/jpeg",
      dateFolderName = "2026-10-29",
      parentPhotosFolderId = "folder-photos",
      dateFolderId = "folder-date",
      sequenceNumber = 0
    )
    assertEquals("Image Evidence/2026-10-29/abed_00042.jpg", result.driveRelativePath)
    assertEquals("abed_00042.jpg", result.finalFilename)
    assertEquals("2026-10-29", result.captureDateFolder)
  }
}
