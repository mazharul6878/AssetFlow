package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.example.data.excel.ExcelWorkbookEngine
import com.example.data.model.CountingRecord
import com.example.data.model.DailyBatchSubmissionRecord
import com.example.data.model.FARAsset
import com.example.data.model.FoundNotInFarRecord
import com.example.data.model.ProjectSettings
import com.example.data.model.RoleAssignment
import com.example.data.model.SubmissionBatchStatus
import com.example.data.model.UserRole
import com.example.data.model.VerificationResultStatus
import com.example.data.offline.OfflineSyncDatabase
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

@Ignore("Requires active Firebase Auth & Firestore Emulators on ports 9099 and 8085")
class AuditRepositoryRuleTest : FirestoreEmulatorTestBase() {

  private lateinit var roomDb: OfflineSyncDatabase

  @Before
  fun setUpRoom() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    roomDb = Room.inMemoryDatabaseBuilder(
      context,
      OfflineSyncDatabase::class.java
    ).allowMainThreadQueries().build()
  }

  @After
  fun tearDownRoom() {
    if (::roomDb.isInitialized) {
      roomDb.close()
    }
  }

  @Test
  fun multiClient_importAndSaveCountingRecord_succeedsAndEmitsUpdates() = runBlocking {
    val uid = signInTestUser(AuditRepository.DESIGNATED_OWNER_EMAIL)
    val repo = AuditRepository(firestore, roomDb.offlineSyncDao())

    withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.bootstrapAuthenticatedMember(
        uid = uid,
        email = AuditRepository.DESIGNATED_OWNER_EMAIL,
        displayName = "Designated Owner",
        requestedRole = UserRole.ADMIN,
        preferredNickname = "owner"
      ).getOrThrow()
    }

    val client = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.createOrUpdateClient(
        clientName = "Apex Textiles Ltd",
        clientCode = "APEX",
        industry = "Manufacturing",
        contactPerson = "R. Karim",
        contactEmail = "karim@apextextiles.com",
        initialFiscalYearLabel = "2026-27"
      ).getOrThrow()
    }

    val defaultBytes = ExcelWorkbookEngine.createDefaultWorkbookBytes("BATCH-APEX")
    val importedCount = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.importClientMasterWorkbook(
        clientId = client.clientId,
        clientName = client.clientName,
        fiscalYearId = client.activeFiscalYearId,
        fileName = "Apex_Asset_Register.xlsx",
        workbookBytes = defaultBytes
      ).getOrThrow()
    }
    assertTrue(importedCount > 0)

    val uniqueAssetId = "FA-${UUID.randomUUID().toString().take(8)}"
    val scopedKey = FARAsset.buildScopedAssetKey(
      client.clientId,
      client.activeFiscalYearId,
      uniqueAssetId,
      2L
    )
    val countRecord = CountingRecord(
      id = scopedKey,
      assetKey = scopedKey,
      assetId = uniqueAssetId,
      sourceRow = 2L,
      clientId = client.clientId,
      fiscalYearId = client.activeFiscalYearId,
      found = "Yes",
      countDate = "2026-10-20",
      countedBy = "Alice Admin",
      countedByUserId = uid,
      buildingSite = "Dhaka HQ Tower",
      floor = "3rd Floor",
      roomLocation = "Server Room 301",
      department = "IT",
      tagStatus = "Tag OK",
      tagNumberAcn = "004512A",
      physicalQtyText = "2",
      condition = "Good",
      inUse = "Yes",
      result = VerificationResultStatus.VERIFIED,
      countingStatus = VerificationResultStatus.COUNTED,
      reviewStatus = VerificationResultStatus.PENDING_REVIEW,
      userId = uid
    )

    val savedCount = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.saveCountingRecord(countRecord, queueOfflineIfFailed = false).getOrThrow()
    }
    assertEquals(VerificationResultStatus.VERIFIED, savedCount.result)
    assertEquals(client.clientId, savedCount.clientId)

    val emittedRecords = withTimeout(FLOW_TIMEOUT_MS) {
      repo.observeCountingRecords(client.clientId, client.activeFiscalYearId)
        .first { list -> list.any { it.assetKey == scopedKey } }
    }
    assertEquals(1, emittedRecords.count { it.assetKey == scopedKey })
  }

  @Test
  fun saveFoundNotInFar_manualObservation_allowsBlankOptionalFieldsAndDrafts() = runBlocking {
    val uid = signInTestUser(ALICE_EMAIL)
    val repo = AuditRepository(firestore, roomDb.offlineSyncDao())

    val draftObs = FoundNotInFarRecord(
      tempId = "OBS-1001",
      clientId = "client_apex",
      fiscalYearId = "2026_27",
      assetIdIfKnown = "",
      description = "Industrial Air Compressor",
      countedBy = "M. Islam",
      countedByUserId = uid,
      recordStatus = VerificationResultStatus.DRAFT,
      isDraft = true,
      userId = uid
    )
    val savedDraftObs = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.saveFoundNotInFarRecord(draftObs).getOrThrow()
    }
    assertEquals(VerificationResultStatus.DRAFT, savedDraftObs.recordStatus)
    assertEquals("M. Islam", savedDraftObs.countedBy)
  }

  @Test
  fun auditorCannotWriteAdminProjectSettings_enforcesRules() = runBlocking {
    val bobUid = signInTestUser(BOB_EMAIL)
    val bobRepo = AuditRepository(firestore, roomDb.offlineSyncDao())
    withTimeout(DEFAULT_TIMEOUT_MS) {
      bobRepo.bootstrapAuthenticatedMember(
        uid = bobUid,
        email = BOB_EMAIL,
        displayName = "Bob Auditor",
        requestedRole = UserRole.AUDITOR,
        preferredNickname = "bob"
      ).getOrThrow()
    }

    val bobSettingsAttempt = withTimeout(DEFAULT_TIMEOUT_MS) {
      bobRepo.saveProjectSettings(
        settings = ProjectSettings(
          projectStartDate = "2026-11-01",
          originalPlannedCompletionDate = "2026-12-31"
        )
      )
    }
    assertTrue(bobSettingsAttempt.isFailure)
  }

  @Test
  fun observeFarAssets_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
    signInTestUser(ALICE_EMAIL)
    auth.signOut()
    val repo = AuditRepository(firestore, roomDb.offlineSyncDao())

    var caught: Throwable? = null
    try {
      withTimeout(FLOW_TIMEOUT_MS) {
        repo.observeFarAssets("client_test", "2026_27").first()
      }
    } catch (e: Throwable) {
      caught = e
    }
    assertNotNull(caught)
    val fsEx = caught?.unwrapFirestoreException()
    assertNotNull("Expected FirebaseFirestoreException, got $caught", fsEx)
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
  }

  private companion object {
    const val ALICE_EMAIL = "alice@test.com"
    const val BOB_EMAIL = "bob@test.com"
    const val DEFAULT_TIMEOUT_MS = 15000L
    const val FLOW_TIMEOUT_MS = 15000L
  }
}
