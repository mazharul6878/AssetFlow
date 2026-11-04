package com.example

import com.example.data.excel.ExcelWorkbookEngine
import com.example.data.model.AssetWithCount
import com.example.data.model.AuditDropdownOptions
import com.example.data.model.CountingRecord
import com.example.data.model.FARAsset
import com.example.data.model.FoundNotInFarRecord
import com.example.data.model.MasterDataConfig
import com.example.data.model.UserSubmissionRecord
import com.example.data.model.VerificationResultStatus
import com.example.data.schema.DynamicSchemaEngine
import com.example.data.schema.FieldBehaviorType
import com.example.data.schema.SchemaFieldConfig
import com.example.domain.AuditCalculationEngine
import com.example.domain.TagStatusNormalizer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive Verification Test Suite covering:
 * - 17 Core Asset Verification & Calculation Scenarios
 * - Firebase Asset Register Import & Duplicate Key Detection
 * - Non-Destructive In-Place Master Workbook Cell Patching & Formula Protection
 * - Dynamic Schema & Raw Register Header Lookup
 * - Standardized Photo Filename & Nickname Collision Rules
 */
class ExampleUnitTest {

  private val sampleFar = FARAsset(
    id = FARAsset.buildAssetKey("FA-1001", 2),
    sl = 1,
    assetId = "FA-1001",
    sourceRow = 2,
    assetDescription = "Dell Latitude 5540 Laptop",
    assetClass = "IT Equipment",
    capitalizedOn = "2024-01-15",
    costTk = 145000.0,
    nbvTk = 96667.0,
    farQty = 5,
    farQtyPresent = true,
    method = "Straight Line"
  )

  private val validCountBase = CountingRecord(
    id = sampleFar.assetKey,
    assetKey = sampleFar.assetKey,
    assetId = sampleFar.assetId,
    sourceRow = sampleFar.sourceRow,
    found = "Yes",
    countDate = "2026-10-28",
    countedBy = "M. Islam (Lead Auditor)",
    countedByUserId = "uid-counter-1",
    buildingSite = "Dhaka HQ Tower",
    floor = "3rd Floor",
    roomLocation = "Server & NOC Room 301",
    department = "Information Technology",
    userCustodian = "R. Karim",
    tagStatus = "Tag OK",
    tagNumberAcn = "000981A",
    brandModel = "Dell Latitude 5540",
    physicalQtyText = "5",
    condition = "Good",
    inUse = "Yes",
    photoRef = "file:///data/user/0/audit_photos/fa_1001.jpg",
    remarks = "All 5 units verified in rack",
    clientExplanation = ""
  )

  @Test
  fun scenario01_foundMatchingQuantityNoExceptions_returnsVerified() {
    val outcome = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = validCountBase,
      authenticatedReviewerFullName = "T. Rahman (Senior Reviewer)",
      authenticatedReviewerUserId = "uid-reviewer-2",
      markAsReviewed = true
    )
    assertEquals("0", outcome.qtyDiffText)
    assertEquals(VerificationResultStatus.VERIFIED, outcome.primaryResult)
    assertEquals(VerificationResultStatus.COUNTED, outcome.countingStatus)
    assertEquals(VerificationResultStatus.REVIEWED, outcome.reviewStatus)
    assertTrue(outcome.applicableExceptions.isEmpty())
    assertEquals("T. Rahman (Senior Reviewer)", outcome.reviewedBy)
    assertEquals("uid-reviewer-2", outcome.reviewedByUserId)
    assertFalse(outcome.reviewerRequiredFlag)
  }

  @Test
  fun scenario02_assetNotFound_returnsNotFound() {
    val outcome = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = CountingRecord(found = "No", countDate = "2026-10-28", countedBy = "M. Islam")
    )
    assertEquals(VerificationResultStatus.NOT_FOUND, outcome.primaryResult)
    assertEquals(VerificationResultStatus.COUNTED, outcome.countingStatus)
    assertEquals(VerificationResultStatus.PENDING_REVIEW, outcome.reviewStatus)
    assertTrue(outcome.applicableExceptions.contains(VerificationResultStatus.NOT_FOUND))
  }

  @Test
  fun scenario03_assetNotAccessible_returnsNotAccessibleNeverVerified() {
    val outcome = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = CountingRecord(found = "Not Accessible", countDate = "2026-10-28", countedBy = "M. Islam")
    )
    assertEquals(VerificationResultStatus.NOT_ACCESSIBLE, outcome.primaryResult)
    assertNotEquals(VerificationResultStatus.VERIFIED, outcome.primaryResult)
  }

  @Test
  fun scenario04_quantityShort_returnsQtyShortAndPreservesAllExceptions() {
    val outcome = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = validCountBase.copy(
        physicalQtyText = "3",
        tagStatus = "Damaged / Illegible Tag"
      )
    )
    assertEquals("-2", outcome.qtyDiffText)
    assertEquals(VerificationResultStatus.QTY_SHORT, outcome.primaryResult)
    assertTrue(outcome.applicableExceptions.any { it.startsWith(VerificationResultStatus.QTY_SHORT) })
    assertTrue(outcome.applicableExceptions.any { it.contains("Tag Issue") })
  }

  @Test
  fun scenario05_quantityExcess_returnsQtyExcess() {
    val outcome = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = validCountBase.copy(physicalQtyText = "7")
    )
    assertEquals("2", outcome.qtyDiffText)
    assertEquals(VerificationResultStatus.QTY_EXCESS, outcome.primaryResult)
    assertTrue(outcome.applicableExceptions.any { it.startsWith(VerificationResultStatus.QTY_EXCESS) })
  }

  @Test
  fun scenario06_damagedOrNotWorkingAsset_returnsVerifiedConditionIssue() {
    for (badCond in listOf("Damaged", "Obsolete", "Under Repair", "Scrap")) {
      val outcome = AuditCalculationEngine.evaluateRecord(
        farAsset = sampleFar,
        record = validCountBase.copy(condition = badCond)
      )
      assertEquals(VerificationResultStatus.VERIFIED_CONDITION_ISSUE, outcome.primaryResult)
      assertTrue(outcome.applicableExceptions.any { it.contains("Condition Issue ($badCond)") })
    }
  }

  @Test
  fun scenario07_missingOrUnreadableTag_returnsVerifiedTagIssue() {
    val nonOkTags = AuditDropdownOptions.TAG_STATUS_OPTIONS.filter { !TagStatusNormalizer.isPositive(it) }
    for (tag in nonOkTags) {
      val outcome = AuditCalculationEngine.evaluateRecord(
        farAsset = sampleFar,
        record = validCountBase.copy(tagStatus = tag)
      )
      assertEquals(VerificationResultStatus.VERIFIED_TAG_ISSUE, outcome.primaryResult)
      assertTrue(outcome.applicableExceptions.any { it.contains("Tag Issue ($tag)") })
    }
  }

  @Test
  fun scenario08_otherSelectedWithoutCustomValue_returnsPendingVerificationAndValidationError() {
    val outcome = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = validCountBase.copy(
        buildingSite = AuditDropdownOptions.OTHER_OPTION,
        buildingSiteOther = "   "
      )
    )
    assertEquals(VerificationResultStatus.PENDING_VERIFICATION, outcome.primaryResult)
    assertTrue(outcome.validationErrors.any { it.contains("Building / Site") })
  }

  @Test
  fun scenario09_missingRequiredVerificationFields_returnsPendingVerification() {
    val onlyFoundYes = CountingRecord(found = "Yes")
    val outcome = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = onlyFoundYes
    )
    assertEquals(VerificationResultStatus.PENDING_VERIFICATION, outcome.primaryResult)
    assertNotEquals(VerificationResultStatus.VERIFIED, outcome.primaryResult)
  }

  @Test
  fun scenario10_duplicateAssetIdsAcrossSourceRows_haveDistinctKeysAndPreserveRecords() {
    val keyRow4 = FARAsset.buildAssetKey("FA-10003", 4)
    val keyRow5 = FARAsset.buildAssetKey("FA-10003", 5)
    assertNotEquals(keyRow4, keyRow5)

    val wbBytes = ExcelWorkbookEngine.createDefaultWorkbookBytes()
    val imported = ExcelWorkbookEngine.importWorkbookFromStream(ByteArrayInputStream(wbBytes))
    val dupAssets = imported.farAssets.filter { it.assetId == "FA-10003" }
    assertEquals(2, dupAssets.size)
    assertEquals(10L, dupAssets[0].farQty)
    assertEquals(5L, dupAssets[1].farQty)
  }

  @Test
  fun scenario11_blankQuantityVersusZeroQuantity_handledDistinctly() {
    val blankOutcome = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = validCountBase.copy(physicalQtyText = "")
    )
    assertEquals("", blankOutcome.qtyDiffText)
    assertEquals(VerificationResultStatus.PENDING_VERIFICATION, blankOutcome.primaryResult)

    val zeroOutcome = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = validCountBase.copy(physicalQtyText = "0")
    )
    assertEquals("-5", zeroOutcome.qtyDiffText)
    assertEquals(VerificationResultStatus.QTY_SHORT, zeroOutcome.primaryResult)
  }

  @Test
  fun scenario12_reopenAndUpdateCountingRecord_preservesHistoricalCountDateAndSupportsExplicitEdit() {
    val initialDate = "2026-10-15"
    val resolvedOnReopen = AuditCalculationEngine.resolveCountDate(
      existingSavedCountDate = initialDate,
      userEnteredDate = "2026-10-28",
      explicitDateEdit = false
    )
    assertEquals("2026-10-15", resolvedOnReopen)

    val resolvedOnExplicitEdit = AuditCalculationEngine.resolveCountDate(
      existingSavedCountDate = initialDate,
      userEnteredDate = "2026-10-28",
      explicitDateEdit = true
    )
    assertEquals("2026-10-28", resolvedOnExplicitEdit)
  }

  @Test
  fun scenario13_excelExportAndReimport_preservesOriginalFarValuesAndCountResults() {
    val countedRecord = validCountBase.copy(
      tagNumberAcn = "000981A",
      qtyDiffText = "0",
      result = VerificationResultStatus.VERIFIED,
      reviewedBy = "T. Rahman"
    )
    val manualObs = FoundNotInFarRecord(
      id = "OBS-20261028-ABC123",
      tempId = "OBS-20261028-ABC123",
      assetIdIfKnown = "",
      description = "Unlabelled Wooden Desk",
      assetClass = "Furniture & Fixtures",
      buildingSite = "Dhaka HQ Tower",
      floor = "3rd Floor",
      roomLocation = "Room 304",
      department = "Finance & Accounts",
      physicalQtyText = "",
      found = "Yes",
      condition = "Good",
      inUse = "Yes",
      countDate = "2026-10-28",
      countedBy = "M. Islam",
      countedByUserId = "uid-1",
      photoRef = "file:///data/user/0/audit_photos/obs1.jpg",
      remarks = "No asset tag present",
      recordStatus = VerificationResultStatus.UNREGISTERED_OBSERVATION
    )

    val out = ByteArrayOutputStream()
    ExcelWorkbookEngine.exportWorkbookToStream(
      outputStream = out,
      assetsWithCounts = listOf(AssetWithCount(sampleFar, countedRecord)),
      exceptions = emptyList(),
      foundNotInFar = listOf(manualObs),
      masterData = MasterDataConfig()
    )
    val exportedBytes = out.toByteArray()

    val reimported = ExcelWorkbookEngine.importWorkbookFromStream(ByteArrayInputStream(exportedBytes))
    assertEquals(29, reimported.countSheetHeaders.size)
    assertEquals(AuditDropdownOptions.EXACT_29_HEADERS, reimported.countSheetHeaders)
    assertEquals(1, reimported.farAssets.size)
    val reFar = reimported.farAssets.first()
    assertEquals("FA-1001", reFar.assetId)
    assertEquals("Dell Latitude 5540 Laptop", reFar.assetDescription)

    val reCount = reimported.initialCounts.first()
    assertEquals("Yes", reCount.found)
    assertEquals("000981A", reCount.tagNumberAcn)
    assertEquals("5", reCount.physicalQtyText)
    assertEquals(VerificationResultStatus.VERIFIED, reCount.result)
    assertEquals("T. Rahman", reCount.reviewedBy)
  }

  @Test
  fun scenario14_clientAssetRegisterImport_parsesRawHeadersAndDetectsDuplicates() {
    val wbBytes = ExcelWorkbookEngine.createDefaultWorkbookBytes()
    val result = ExcelWorkbookEngine.importClientAssetRegisterFromStream(
      inputStream = ByteArrayInputStream(wbBytes),
      sourceWorkbookName = "Walton_Register.xlsx",
      clientId = "walton_client_1",
      fiscalYearId = "2026_27",
      matchingKeyHeader = "Asset ID"
    )
    assertTrue(result.importedAssets.isNotEmpty())
    assertEquals("walton_client_1", result.importedAssets.first().clientId)
    assertEquals("2026_27", result.importedAssets.first().fiscalYearId)
    assertTrue(result.importedAssets.first().rawRowByHeaderJson.contains("Asset Description (FAR)"))
    assertTrue(result.duplicateKeyValues.contains("FA-10003"))
  }

  @Test
  fun scenario15_nonDestructiveMasterWorkbookPatching_updatesOnlyMappedCellsInPlace() {
    val originalMasterBytes = ExcelWorkbookEngine.createDefaultWorkbookBytes()
    val approvedCount = CountingRecord(
      assetKey = FARAsset.buildAssetKey("FA-10001", 2),
      assetId = "FA-10001",
      sourceRow = 2,
      found = "Yes",
      physicalQtyText = "1",
      condition = "Good",
      remarks = "Patched via non-destructive engine",
      qtyDiffText = "0",
      result = "Verified"
    )

    val patchResult = ExcelWorkbookEngine.patchMasterWorkbookInPlace(
      originalWorkbookBytes = originalMasterBytes,
      targetSheetName = "Count Sheet",
      matchingKeyHeader = "Asset ID",
      approvedRecords = listOf(approvedCount),
      reviewerName = "Admin Reviewer"
    )

    assertEquals(1, patchResult.updatedAssetCount)
    assertTrue(patchResult.matchedKeys.contains("FA-10001"))

    val reimported = ExcelWorkbookEngine.importWorkbookFromStream(ByteArrayInputStream(patchResult.patchedWorkbookBytes))
    val updatedRow = reimported.initialCounts.find { it.assetId == "FA-10001" }
    assertNotNull(updatedRow)
    assertEquals("Yes", updatedRow?.found)
    assertEquals("1", updatedRow?.physicalQtyText)
    assertEquals("Patched via non-destructive engine", updatedRow?.remarks)
    // Verify original FAR fields on the same row were untouched
    val preservedFar = reimported.farAssets.find { it.assetId == "FA-10001" }
    assertNotNull(preservedFar)
    assertEquals("Dell Latitude 5540 Core i7 Business Laptop", preservedFar?.assetDescription)
  }

  @Test
  fun scenario16_dailySheetExcelExport_generatesValidXlsxBytes() {
    val bytes = ExcelWorkbookEngine.generateDailySheetWorkbookBytes(
      clientName = "Walton Hi-Tech Industries",
      fiscalYearLabel = "2026-27",
      auditorName = "M. Islam",
      countingDate = "2026-10-28",
      assetsWithCounts = listOf(AssetWithCount(sampleFar, validCountBase))
    )
    assertTrue(bytes.isNotEmpty())
  }

  @Test
  fun partA_01_default29FieldsGeneration_matchesExactHeadersAndFixedProtection() {
    val fields = DynamicSchemaEngine.buildDefault29FieldConfigs()
    assertEquals(29, fields.size)
    for (i in 0 until 9) {
      assertEquals(FieldBehaviorType.FIXED_MASTER_DATA, fields[i].behavior)
      assertTrue(fields[i].isReadOnlyFar)
    }
    val assetIdField = fields.find { it.fieldId == "asset_id" }
    assertNotNull(assetIdField)
    assertEquals("Asset ID", assetIdField?.displayName)
  }

  @Test
  fun partA_02_resolveFixedFieldValue_readsArbitraryRegisterHeaderFromRawJson() {
    val assetWithRawJson = sampleFar.copy(
      rawRowByHeaderJson = """{"Custom Serial Header":"SN-998877","Department Code":"DEPT-IT-01"}"""
    )
    val customField = SchemaFieldConfig(
      fieldId = "custom_serial",
      originalHeading = "Custom Serial Header",
      sourceRegisterHeader = "Custom Serial Header",
      displayName = "Serial Number",
      columnIndex = 29,
      columnLetter = "AD",
      fieldOrder = 30,
      behavior = FieldBehaviorType.FIXED_MASTER_DATA
    )
    val resolved = DynamicSchemaEngine.resolveFixedFieldValue(customField, assetWithRawJson)
    assertEquals("SN-998877", resolved)
  }

  @Test
  fun partB_01_standardizedPhotoNaming_preservesLeadingZerosAndExactFormat() {
    val (filename1, _) = com.example.data.storage.PhotoStorageManager.buildStandardizedPhotoFilename(
      nickname = "abed",
      assetIdentifier = "0001872",
      extension = "jpg"
    )
    assertEquals("abed_0001872.jpg", filename1)

    val (filename2, _) = com.example.data.storage.PhotoStorageManager.buildStandardizedPhotoFilename(
      nickname = "abed",
      assetIdentifier = "0001872",
      extension = "jpg",
      existingFileNamesInDateFolder = setOf("abed_0001872.jpg")
    )
    assertEquals("abed_0001872_1.jpg", filename2)
  }

  @Test
  fun partB_06_nicknameValidationRules() {
    assertTrue(com.example.data.model.UserProfile.isValidNickname("abed"))
    assertTrue(com.example.data.model.UserProfile.isValidNickname("m_islam"))
    assertTrue(com.example.data.model.UserProfile.isValidNickname("auditor1"))
    assertFalse(com.example.data.model.UserProfile.isValidNickname("a"))
    assertFalse(com.example.data.model.UserProfile.isValidNickname(""))
    assertFalse(com.example.data.model.UserProfile.isValidNickname("///"))
    assertFalse(com.example.data.model.UserProfile.isValidNickname("___"))
  }
}
