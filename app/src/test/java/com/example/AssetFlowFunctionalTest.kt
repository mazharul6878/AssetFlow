package com.example

import com.example.data.excel.ExcelWorkbookEngine
import com.example.data.model.AssetWithCount
import com.example.data.model.CountingRecord
import com.example.data.model.FARAsset
import com.example.data.model.FoundNotInFarRecord
import com.example.data.model.MANUAL_ENTRY_REMARKS
import com.example.data.model.VerificationResultStatus
import com.example.data.repository.AuditRepository
import com.example.data.schema.DetectedColumnHeading
import com.example.data.schema.DetectedWorksheetStructure
import com.example.data.schema.DynamicSchemaEngine
import com.example.domain.AuditCalculationEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AssetFlowFunctionalTest {

  @Test
  fun testLeadingZeroPreservationInSearch() {
    val assets = listOf(
      AssetWithCount(FARAsset(assetId = "001", sourceRow = 1), null),
      AssetWithCount(FARAsset(assetId = "1", sourceRow = 2), null),
      AssetWithCount(FARAsset(assetId = "00078", sourceRow = 3), null)
    )

    val result1 = AuditCalculationEngine.searchFarAssets(assets, "001")
    assertEquals(1, result1.exactMatches.size)
    assertEquals("001", result1.exactMatches[0].farAsset.assetId)

    val result2 = AuditCalculationEngine.searchFarAssets(assets, "1")
    assertEquals(1, result2.exactMatches.size)
    assertEquals("1", result2.exactMatches[0].farAsset.assetId)

    val result3 = AuditCalculationEngine.searchFarAssets(assets, "78")
    assertTrue(result3.exactMatches.isEmpty())
    assertTrue(result3.isNotFoundInFar)
  }

  @Test
  fun testAutomaticManualEntryRemarks() {
    val draft = FoundNotInFarRecord(
      tempId = "OBS-001",
      description = "Unregistered Chair",
      remarks = "Slightly scratched"
    )

    val outcome = AuditCalculationEngine.evaluateManualObservation(draft)
    val formattedRemarks = outcome.preparedRecord.remarks

    assertTrue(formattedRemarks.contains(MANUAL_ENTRY_REMARKS))
    assertTrue(formattedRemarks.contains("Slightly scratched"))
    assertEquals("$MANUAL_ENTRY_REMARKS — Slightly scratched", formattedRemarks)

    val draftEmpty = FoundNotInFarRecord(tempId = "OBS-002", description = "Unregistered Table", remarks = "")
    val outcomeEmpty = AuditCalculationEngine.evaluateManualObservation(draftEmpty)
    assertEquals(MANUAL_ENTRY_REMARKS, outcomeEmpty.preparedRecord.remarks)
  }

  @Test
  fun testFullNameAttribution() {
    val farAsset = FARAsset(assetId = "FA-101", sourceRow = 1, farQty = 1, farQtyPresent = true)
    val record = CountingRecord(found = "Yes", physicalQtyText = "1")

    val outcome = AuditCalculationEngine.evaluateRecord(
      farAsset = farAsset,
      record = record,
      authenticatedCounterFullName = "John Doe Full Name",
      authenticatedCounterUserId = "user123"
    )

    assertEquals("John Doe Full Name", outcome.countedBy)
    assertEquals("user123", outcome.countedByUserId)

    val existing = record.copy(countedBy = "Historical Name", countedByUserId = "old_user")
    val outcomePreserved = AuditCalculationEngine.evaluateRecord(
      farAsset = farAsset,
      record = existing,
      authenticatedCounterFullName = "New Auditor Name",
      authenticatedCounterUserId = "new_user",
      existingRecord = existing
    )

    assertEquals("Historical Name", outcomePreserved.countedBy)
    assertEquals("old_user", outcomePreserved.countedByUserId)
  }

  @Test
  fun testCategoricalDropdownDetection() {
    val worksheet = DetectedWorksheetStructure(
      sheetName = "Count Sheet",
      headerRowIndex = 0,
      totalDataRows = 5,
      headings = listOf(
        DetectedColumnHeading(0, "Department", 5, 5, listOf("Finance", "HR", "Finance", "IT", "HR")),
        DetectedColumnHeading(1, "Asset ID", 5, 5, listOf("FA-01", "FA-02", "FA-03", "FA-04", "FA-05"))
      )
    )

    val mapping = com.example.data.model.ExcelColumnMappingConfig(
      departmentColumn = "Department"
    )

    val detected = DynamicSchemaEngine.detectCategoricalOptionsFromWorksheet(worksheet, mapping)

    assertTrue(detected.containsKey("department"))
    val deptOptions = detected["department"]!!
    assertEquals(3, deptOptions.size)
    assertTrue(deptOptions.contains("Finance"))
    assertTrue(deptOptions.contains("HR"))
    assertTrue(deptOptions.contains("IT"))
    assertFalse(detected.containsKey("asset_id"))
  }

  @Test
  fun testMultiClientScopedAssetKeyAndInPlaceMasterWorkbookPatching() {
    val scopedKey = FARAsset.buildScopedAssetKey("client_apex", "2026_27", "FA-10001", 2L)
    assertTrue(scopedKey.contains("client_apex"))
    assertTrue(scopedKey.contains("2026_27"))
    assertTrue(scopedKey.contains("FA-10001"))

    val masterBytes = ExcelWorkbookEngine.createDefaultWorkbookBytes("BATCH-TEST")
    val parsedBefore = ExcelWorkbookEngine.parseFarWorkbook(masterBytes, "Master.xlsx")
    assertEquals("Count Sheet", parsedBefore.selectedWorksheetName)
    assertTrue(parsedBefore.assets.any { it.assetId == "FA-10001" })

    val approvedRecord = CountingRecord(
      assetId = "FA-10001",
      sourceRow = 2L,
      found = "Yes",
      countDate = "2026-10-29",
      countedBy = "M. Islam (Lead Auditor)",
      buildingSite = "Dhaka HQ Tower",
      floor = "3rd Floor",
      roomLocation = "Server & NOC Room 301",
      department = "Information Technology",
      userCustodian = "A. Rahman",
      tagStatus = "Tag OK",
      tagNumberAcn = "ACN-10001",
      brandModel = "Dell Latitude 5540",
      physicalQtyText = "1",
      condition = "Good",
      inUse = "Yes",
      photoRef = "abed_FA-10001.jpg",
      remarks = "Verified in place",
      qtyDiffText = "0",
      result = VerificationResultStatus.VERIFIED,
      reviewedBy = "M. Islam (Owner)"
    )

    val patched = ExcelWorkbookEngine.patchMasterWorkbookInPlace(
      originalWorkbookBytes = masterBytes,
      targetSheetName = "Count Sheet",
      matchingKeyHeader = "Asset ID",
      approvedRecords = listOf(approvedRecord),
      reviewerName = "M. Islam (Owner)"
    )

    assertEquals(1, patched.updatedAssetCount)
    assertTrue(patched.updatedCellCount > 0)
    assertTrue(patched.patchedBytes.isNotEmpty())

    // Verify all 6 required sheets remain intact after in-place cell patching
    val sheetsAfter = ExcelWorkbookEngine.extractAllWorksheetsMap(patched.patchedBytes)
    assertTrue(sheetsAfter.keys.containsAll(ExcelWorkbookEngine.REQUIRED_WORKBOOK_SHEETS))
    val countRowsAfter = sheetsAfter["Count Sheet"].orEmpty()
    val fa10001Row = countRowsAfter.first { it.getOrNull(1) == "FA-10001" }
    assertEquals("Yes", fa10001Row[9])
    assertEquals("2026-10-29", fa10001Row[10])
    assertEquals("Verified", fa10001Row[26])
    assertEquals("M. Islam (Owner)", fa10001Row[27])
  }

  @Test
  fun testDesignatedSystemOwnerConstant() {
    assertEquals("mislamabed@gmail.com", AuditRepository.DESIGNATED_OWNER_EMAIL)
  }
}
