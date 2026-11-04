package com.example.data.excel

import com.example.data.model.AssetWithCount
import com.example.data.model.AuditDropdownOptions
import com.example.data.model.CountingRecord
import com.example.data.model.ExceptionRecord
import com.example.data.model.FARAsset
import com.example.data.model.FoundNotInFarRecord
import com.example.data.model.MasterDataConfig
import com.example.data.model.VerificationResultStatus
import com.example.domain.AuditCalculationEngine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.StringWriter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

data class WorkbookImportResult(
  val sheetNames: List<String>,
  val countSheetHeaders: List<String>,
  val farAssets: List<FARAsset>,
  val initialCounts: List<CountingRecord>,
  val foundNotInFar: List<FoundNotInFarRecord>,
  val landAucClearingRows: List<FARAsset>,
  val skippedGlRows: List<String>,
  val extractedMasterData: MasterDataConfig,
  val howToFillNotes: List<String>
)

data class AssetRegisterValidationIssue(
  val rowNumber: Long,
  val matchingKey: String,
  val issueType: String, // MISSING_KEY | DUPLICATE_KEY | NON_COUNTABLE_GL_ROW
  val message: String
)

data class AssetRegisterImportResult(
  val sourceWorkbookName: String,
  val targetWorksheetName: String,
  val headers: List<String>,
  val matchingKeyHeader: String,
  val importedAssets: List<FARAsset>,
  val duplicateKeyValues: List<String>,
  val missingKeyRowNumbers: List<Long>,
  val validationIssues: List<AssetRegisterValidationIssue>,
  val extractedMasterData: MasterDataConfig
) {
  val hasBlockingValidationErrors: Boolean
    get() = duplicateKeyValues.isNotEmpty() || missingKeyRowNumbers.isNotEmpty()
}

data class ApprovedMasterRowPatch(
  val matchingKeyValue: String,
  val sourceRowHint: Long = 0L,
  val destinationCellsByHeader: Map<String, String>
)

data class InPlaceWorkbookPatchResult(
  val patchedWorkbookBytes: ByteArray,
  val targetWorksheetName: String,
  val updatedAssetCount: Int,
  val updatedCellCount: Int,
  val matchedKeys: List<String>
) {
  val patchedBytes: ByteArray
    get() = patchedWorkbookBytes
}

data class ParsedFarWorkbook(
  val assets: List<FARAsset>,
  val selectedWorksheetName: String,
  val headerRowIndex: Int,
  val detectedHeaders: List<String>
)

/**
 * Pure Kotlin OpenXML (.xlsx) parser, generator, and non-destructive in-place cell patcher.
 */
object ExcelWorkbookEngine {

  data class ImportValidationSummary(
    val sourceFileName: String = "",
    val totalRowsScanned: Int = 0,
    val importedRowsCount: Int = 0,
    val updatedRowsCount: Int = 0,
    val duplicateAssetKeys: List<String> = emptyList(),
    val skippedMissingKeyRows: List<Int> = emptyList(),
    val validationErrors: List<String> = emptyList()
  )

  fun parseFarWorkbook(
    bytes: ByteArray,
    sourceWorkbookName: String,
    preferredSheetName: String = ""
  ): ParsedFarWorkbook {
    val imported = importClientAssetRegisterFromStream(
      inputStream = ByteArrayInputStream(bytes),
      clientId = "",
      fiscalYearId = "",
      sourceWorkbookName = sourceWorkbookName,
      targetWorksheetName = preferredSheetName,
      allowDuplicateKeysWithRowDisambiguation = true
    )
    return ParsedFarWorkbook(
      assets = imported.importedAssets,
      selectedWorksheetName = imported.targetWorksheetName,
      headerRowIndex = 1,
      detectedHeaders = imported.headers
    )
  }

  fun patchMasterWorkbookInPlace(
    originalWorkbookBytes: ByteArray,
    targetSheetName: String = "Count Sheet",
    headerRowIndex: Int = 1,
    matchingKeyHeader: String = "Asset ID",
    approvedRecords: List<CountingRecord>,
    approvedUnlistedRecords: List<FoundNotInFarRecord> = emptyList(),
    reviewerName: String = ""
  ): InPlaceWorkbookPatchResult {
    if (approvedRecords.isEmpty()) {
      return InPlaceWorkbookPatchResult(
        patchedWorkbookBytes = originalWorkbookBytes,
        targetWorksheetName = targetSheetName,
        updatedAssetCount = 0,
        updatedCellCount = 0,
        matchedKeys = emptyList()
      )
    }
    val patches = approvedRecords.map { rec ->
      ApprovedMasterRowPatch(
        matchingKeyValue = rec.assetId,
        sourceRowHint = rec.sourceRow,
        destinationCellsByHeader = linkedMapOf(
          "Found?" to rec.found,
          "Count Date" to rec.countDate,
          "Counted By" to rec.countedBy,
          "Building / Site" to rec.resolvedBuildingSite(),
          "Floor" to rec.resolvedFloor(),
          "Room / Location" to rec.resolvedRoomLocation(),
          "Department" to rec.resolvedDepartment(),
          "User / Custodian" to rec.userCustodian,
          "Tag Status" to rec.tagStatus,
          "Tag Number (ACN)" to rec.tagNumberAcn,
          "Brand / Model" to rec.brandModel,
          "Physical Qty" to rec.physicalQtyText,
          "Condition" to rec.condition,
          "In Use?" to rec.inUse,
          "Photo Ref" to rec.photoRef,
          "Remarks" to rec.remarks,
          "Qty Diff" to rec.qtyDiffText,
          "Result" to rec.result.ifBlank { VerificationResultStatus.PENDING },
          "Reviewed By" to reviewerName.ifBlank { rec.reviewedBy },
          "Client Explanation / Follow-up" to rec.clientExplanation
        )
      )
    }
    return applyApprovedMappingsToExistingWorkbookInPlace(
      originalMasterBytes = originalWorkbookBytes,
      targetWorksheetName = targetSheetName,
      matchingKeyHeader = matchingKeyHeader,
      approvedRowPatches = patches
    ).getOrElse {
      InPlaceWorkbookPatchResult(
        patchedWorkbookBytes = originalWorkbookBytes,
        targetWorksheetName = targetSheetName,
        updatedAssetCount = 0,
        updatedCellCount = 0,
        matchedKeys = emptyList()
      )
    }
  }

  fun generateFullWorkbook(
    assetsWithCounts: List<AssetWithCount>,
    foundNotInFar: List<FoundNotInFarRecord>,
    masterData: MasterDataConfig,
    settings: com.example.data.model.ProjectSettings = com.example.data.model.ProjectSettings()
  ): ByteArray {
    val out = ByteArrayOutputStream()
    exportWorkbookToStream(
      outputStream = out,
      assetsWithCounts = assetsWithCounts,
      exceptions = emptyList(),
      foundNotInFar = foundNotInFar,
      masterData = masterData,
      includeSampleGlControlRow = false
    )
    return out.toByteArray()
  }

  fun generateDailyBatchSheetBytes(
    countingDate: String,
    auditorName: String,
    assetsWithCounts: List<AssetWithCount>,
    foundNotInFar: List<FoundNotInFarRecord> = emptyList(),
    columnMapping: com.example.data.model.ExcelColumnMappingConfig = com.example.data.model.ExcelColumnMappingConfig()
  ): ByteArray = generateDailySheetWorkbookBytes(
    clientName = columnMapping.clientId.ifBlank { "Client" },
    fiscalYearLabel = columnMapping.fiscalYearId.ifBlank { "2026-27" },
    auditorName = auditorName,
    countingDate = countingDate,
    assetsWithCounts = assetsWithCounts
  )

  val REQUIRED_WORKBOOK_SHEETS = listOf(
    "Summary",
    "How to Fill",
    "Count Sheet",
    "Found Not in FAR",
    "Land, AUC & Clearing",
    "Lists"
  )

  /**
   * Generates the canonical 6-sheet FA_Count_Verification_Sheet.xlsx workbook bytes
   * with the exact 29 Count Sheet headers, Lists dropdown options, Land/AUC/Clearing rows,
   * and realistic multi-row FAR records for immediate verification and unit testing.
   */
  fun createDefaultWorkbookBytes(batchId: String = "BATCH-FA-2026"): ByteArray {
    val sampleFarAssets = listOf(
      FARAsset(
        id = FARAsset.buildAssetKey("FA-10001", 2),
        sl = 1,
        assetId = "FA-10001",
        sourceRow = 2,
        assetDescription = "Dell Latitude 5540 Core i7 Business Laptop",
        assetClass = "IT Equipment",
        capitalizedOn = "2023-03-15",
        costTk = 145000.0,
        nbvTk = 96667.0,
        farQty = 1,
        farQtyPresent = true,
        method = "Straight Line",
        sheetOrigin = "Count Sheet",
        importBatchId = batchId
      ),
      FARAsset(
        id = FARAsset.buildAssetKey("FA-10002", 3),
        sl = 2,
        assetId = "FA-10002",
        sourceRow = 3,
        assetDescription = "Cisco Catalyst 9200L 48-Port PoE+ Network Switch",
        assetClass = "IT Equipment",
        capitalizedOn = "2022-07-01",
        costTk = 380000.0,
        nbvTk = 215000.0,
        farQty = 2,
        farQtyPresent = true,
        method = "Straight Line",
        sheetOrigin = "Count Sheet",
        importBatchId = batchId
      ),
      FARAsset(
        id = FARAsset.buildAssetKey("FA-10003", 4),
        sl = 3,
        assetId = "FA-10003",
        sourceRow = 4,
        assetDescription = "Ergonomic Executive Mesh Workstation Chair (Batch A)",
        assetClass = "Furniture & Fixtures",
        capitalizedOn = "2021-11-10",
        costTk = 250000.0,
        nbvTk = 135000.0,
        farQty = 10,
        farQtyPresent = true,
        method = "Straight Line",
        sheetOrigin = "Count Sheet",
        importBatchId = batchId
      ),
      FARAsset(
        id = FARAsset.buildAssetKey("FA-10003", 5),
        sl = 4,
        assetId = "FA-10003",
        sourceRow = 5,
        assetDescription = "Ergonomic Executive Mesh Workstation Chair (Batch B - Annex)",
        assetClass = "Furniture & Fixtures",
        capitalizedOn = "2022-02-18",
        costTk = 125000.0,
        nbvTk = 78000.0,
        farQty = 5,
        farQtyPresent = true,
        method = "Straight Line",
        sheetOrigin = "Count Sheet",
        importBatchId = batchId
      ),
      FARAsset(
        id = FARAsset.buildAssetKey("FA-10004", 6),
        sl = 5,
        assetId = "FA-10004",
        sourceRow = 6,
        assetDescription = "Daikin 2.0 Ton Inverter Split Air Conditioner",
        assetClass = "Office Equipment",
        capitalizedOn = "2020-05-20",
        costTk = 190000.0,
        nbvTk = 68000.0,
        farQty = 2,
        farQtyPresent = true,
        method = "Reducing Balance",
        sheetOrigin = "Count Sheet",
        importBatchId = batchId
      ),
      FARAsset(
        id = FARAsset.buildAssetKey("FA-10005", 7),
        sl = 6,
        assetId = "FA-10005",
        sourceRow = 7,
        assetDescription = "Cummins 250 kVA Diesel Standby Generator Set",
        assetClass = "Plant & Machinery",
        capitalizedOn = "2019-09-01",
        costTk = 2850000.0,
        nbvTk = 1425000.0,
        farQty = 1,
        farQtyPresent = true,
        method = "Reducing Balance",
        sheetOrigin = "Count Sheet",
        importBatchId = batchId
      ),
      FARAsset(
        id = FARAsset.buildAssetKey("FA-10006", 8),
        sl = 7,
        assetId = "FA-10006",
        sourceRow = 8,
        assetDescription = "Canon imageRUNNER ADVANCE DX C3826i Multifunction Printer",
        assetClass = "Office Equipment",
        capitalizedOn = "2023-08-12",
        costTk = 420000.0,
        nbvTk = 350000.0,
        farQty = 1,
        farQtyPresent = true,
        method = "Straight Line",
        sheetOrigin = "Count Sheet",
        importBatchId = batchId
      ),
      FARAsset(
        id = FARAsset.buildAssetKey("FA-10007", 9),
        sl = 8,
        assetId = "FA-10007",
        sourceRow = 9,
        assetDescription = "Toyota Hilux Double Cab Field Pickup Vehicle",
        assetClass = "Motor Vehicles",
        capitalizedOn = "2021-04-05",
        costTk = 5400000.0,
        nbvTk = 2700000.0,
        farQty = 1,
        farQtyPresent = true,
        method = "Reducing Balance",
        sheetOrigin = "Count Sheet",
        importBatchId = batchId
      )
    )

    val defaultMasterData = MasterDataConfig(
      id = "default_config",
      auditors = listOf(
        "M. Islam (Lead Auditor)",
        "T. Rahman (Senior Auditor)",
        "S. Ahmed (Audit Associate)",
        "N. Karim (Field Verifier)"
      ).joinToString("\n"),
      sites = listOf(
        "Dhaka HQ Tower",
        "Chattogram Regional Office",
        "Gazipur Central Plant",
        "Narayanganj Warehouse"
      ).joinToString("\n"),
      floors = listOf(
        "Basement-1",
        "Ground Floor",
        "1st Floor",
        "2nd Floor",
        "3rd Floor",
        "4th Floor"
      ).joinToString("\n"),
      rooms = listOf(
        "Dhaka HQ Tower|1st Floor|Main Reception & Lobby",
        "Dhaka HQ Tower|2nd Floor|Finance & Accounts Hall",
        "Dhaka HQ Tower|3rd Floor|Server & NOC Room 301",
        "Dhaka HQ Tower|3rd Floor|IT Support Bay 304",
        "Dhaka HQ Tower|4th Floor|Executive Boardroom",
        "Gazipur Central Plant|Ground Floor|Generator & Utility House",
        "Gazipur Central Plant|1st Floor|Production Control Room",
        "Chattogram Regional Office|2nd Floor|Operations Wing",
        "Narayanganj Warehouse|Ground Floor|Dispatch & Fleet Yard"
      ).joinToString("\n"),
      departments = listOf(
        "Finance & Accounts",
        "Information Technology",
        "Operations & Logistics",
        "Human Resources & Admin",
        "Plant Engineering",
        "Fleet Management"
      ).joinToString("\n")
    )

    val out = ByteArrayOutputStream()
    exportWorkbookToStream(
      outputStream = out,
      assetsWithCounts = sampleFarAssets.map { AssetWithCount(it, null) },
      exceptions = emptyList(),
      foundNotInFar = emptyList(),
      masterData = defaultMasterData,
      includeSampleGlControlRow = true
    )
    return out.toByteArray()
  }

  fun extractAllWorksheetsMap(bytes: ByteArray): Map<String, List<List<String>>> =
    extractAllWorksheetsMap(ByteArrayInputStream(bytes))

  fun extractAllWorksheetsMap(inputStream: InputStream): Map<String, List<List<String>>> {
    val entries = mutableMapOf<String, ByteArray>()
    ZipInputStream(inputStream).use { zis ->
      var entry: ZipEntry? = zis.nextEntry
      while (entry != null) {
        if (!entry.isDirectory) {
          val buffer = ByteArrayOutputStream()
          val data = ByteArray(4096)
          var n: Int
          while (zis.read(data).also { n = it } != -1) {
            buffer.write(data, 0, n)
          }
          entries[entry.name] = buffer.toByteArray()
        }
        zis.closeEntry()
        entry = zis.nextEntry
      }
    }

    val sharedStrings = parseSharedStrings(entries["xl/sharedStrings.xml"])
    val sheetNameToPath = parseWorkbookSheets(
      workbookXml = entries["xl/workbook.xml"],
      relsXml = entries["xl/_rels/workbook.xml.rels"]
    )

    val sheetRowsMap = LinkedHashMap<String, List<List<String>>>()
    for ((sheetName, path) in sheetNameToPath) {
      val normalizedPath = if (path.startsWith("xl/")) path else "xl/$path"
      val xmlBytes = entries[normalizedPath] ?: entries[path.removePrefix("/")]
      if (xmlBytes != null) {
        sheetRowsMap[sheetName] = parseWorksheetRows(xmlBytes, sharedStrings)
      }
    }
    return sheetRowsMap
  }

  /**
   * Parses a client's Asset Register .xlsx workbook and produces structured Firebase `FARAsset` records
   * scoped to `(clientId, fiscalYearId)` while preserving `rawRowByHeaderJson` and validating
   * duplicate or missing matching keys.
   */
  fun importClientAssetRegisterFromStream(
    inputStream: InputStream,
    clientId: String,
    fiscalYearId: String,
    sourceWorkbookName: String = "Client_Asset_Register.xlsx",
    targetWorksheetName: String = "",
    matchingKeyHeader: String = "Asset ID",
    batchId: String = "BATCH-${System.currentTimeMillis()}",
    userId: String = "",
    allowDuplicateKeysWithRowDisambiguation: Boolean = false
  ): AssetRegisterImportResult {
    val sheetRowsMap = extractAllWorksheetsMap(inputStream)
    require(sheetRowsMap.isNotEmpty()) { "Workbook '$sourceWorkbookName' contains no readable worksheets." }

    val selectedSheetName = when {
      targetWorksheetName.isNotBlank() && sheetRowsMap.containsKey(targetWorksheetName) -> targetWorksheetName
      sheetRowsMap.keys.any { it.equals("Count Sheet", ignoreCase = true) } ->
        sheetRowsMap.keys.first { it.equals("Count Sheet", ignoreCase = true) }
      else -> sheetRowsMap.entries.maxByOrNull { it.value.size }?.key ?: sheetRowsMap.keys.first()
    }

    val rows = sheetRowsMap[selectedSheetName].orEmpty()
    require(rows.isNotEmpty()) { "Worksheet '$selectedSheetName' in '$sourceWorkbookName' is empty." }

    val headerRowIndex = rows.indexOfFirst { row ->
      row.any { it.trim().equals(matchingKeyHeader, ignoreCase = true) } ||
        row.any { it.trim().equals("Asset ID", ignoreCase = true) } ||
        row.count { it.trim().isNotEmpty() } >= 2
    }.let { if (it >= 0) it else 0 }

    val rawHeaders = rows.getOrNull(headerRowIndex).orEmpty().mapIndexed { idx, h ->
      h.trim().ifEmpty { "Column_${columnIndexToLetter(idx)}" }
    }

    val keyColIdx = rawHeaders.indexOfFirst { it.equals(matchingKeyHeader, ignoreCase = true) }
      .let { if (it >= 0) it else rawHeaders.indexOfFirst { h -> h.contains("asset", true) && (h.contains("id", true) || h.contains("tag", true) || h.contains("no", true) || h.contains("code", true)) } }
      .let { if (it >= 0) it else if (rawHeaders.size > 1) 1 else 0 }

    val resolvedKeyHeader = rawHeaders.getOrNull(keyColIdx) ?: matchingKeyHeader

    val slColIdx = rawHeaders.indexOfFirst { it.equals("SL", true) || it.equals("S/L", true) || it.equals("Serial", true) }
    val descColIdx = rawHeaders.indexOfFirst { it.contains("description", true) || it.contains("name", true) }
      .let { if (it >= 0) it else 2 }
    val classColIdx = rawHeaders.indexOfFirst { it.contains("class", true) || it.contains("category", true) }
      .let { if (it >= 0) it else 3 }
    val capColIdx = rawHeaders.indexOfFirst { it.contains("capitalized", true) || it.contains("acq", true) || it.contains("date", true) }
      .let { if (it >= 0) it else 4 }
    val costColIdx = rawHeaders.indexOfFirst { it.contains("cost", true) }
      .let { if (it >= 0) it else 5 }
    val nbvColIdx = rawHeaders.indexOfFirst { it.contains("nbv", true) || it.contains("book value", true) || it.contains("wdv", true) }
      .let { if (it >= 0) it else 6 }
    val qtyColIdx = rawHeaders.indexOfFirst { it.contains("far qty", true) || it.equals("qty", true) || it.contains("quantity", true) }
      .let { if (it >= 0) it else 7 }
    val methodColIdx = rawHeaders.indexOfFirst { it.contains("method", true) }
      .let { if (it >= 0) it else 8 }
    val deptColIdx = rawHeaders.indexOfFirst { it.contains("department", true) || it.contains("dept", true) }
    val locColIdx = rawHeaders.indexOfFirst { it.contains("location", true) || it.contains("building", true) || it.contains("site", true) || it.contains("room", true) }

    val seenKeys = mutableMapOf<String, Long>()
    val duplicateKeys = mutableListOf<String>()
    val missingKeyRows = mutableListOf<Long>()
    val issues = mutableListOf<AssetRegisterValidationIssue>()
    val assets = mutableListOf<FARAsset>()

    for (rIdx in (headerRowIndex + 1) until rows.size) {
      val row = rows[rIdx]
      if (row.all { it.trim().isEmpty() }) continue
      val sourceRowNumber = (rIdx + 1).toLong()
      val keyVal = row.getOrNull(keyColIdx)?.trim().orEmpty()
      val descVal = row.getOrNull(descColIdx)?.trim().orEmpty()

      if (AuditCalculationEngine.isNonCountableGlOrDepreciationRow(keyVal, descVal)) {
        issues.add(
          AssetRegisterValidationIssue(
            rowNumber = sourceRowNumber,
            matchingKey = keyVal,
            issueType = "NON_COUNTABLE_GL_ROW",
            message = "Skipped non-countable G/L or depreciation row $sourceRowNumber"
          )
        )
        continue
      }

      if (keyVal.isEmpty()) {
        missingKeyRows.add(sourceRowNumber)
        issues.add(
          AssetRegisterValidationIssue(
            rowNumber = sourceRowNumber,
            matchingKey = "",
            issueType = "MISSING_KEY",
            message = "Row $sourceRowNumber is missing required key '$resolvedKeyHeader'."
          )
        )
        continue
      }

      val prevRow = seenKeys[keyVal]
      if (prevRow != null) {
        duplicateKeys.add(keyVal)
        issues.add(
          AssetRegisterValidationIssue(
            rowNumber = sourceRowNumber,
            matchingKey = keyVal,
            issueType = "DUPLICATE_KEY",
            message = "Duplicate key '$keyVal' on row $sourceRowNumber (first seen on row $prevRow)."
          )
        )
        if (!allowDuplicateKeysWithRowDisambiguation) {
          continue
        }
      } else {
        seenKeys[keyVal] = sourceRowNumber
      }

      val rawRowJson = JSONObject()
      rawHeaders.forEachIndexed { cIdx, hName ->
        val cellVal = row.getOrNull(cIdx)?.trim().orEmpty()
        if (cellVal.isNotEmpty()) {
          rawRowJson.put(hName, cellVal)
        }
      }

      val slVal = if (slColIdx >= 0) row.getOrNull(slColIdx)?.trim()?.toLongOrNull() ?: (assets.size + 1).toLong() else (assets.size + 1).toLong()
      val costVal = row.getOrNull(costColIdx)?.trim()?.replace(",", "")?.toDoubleOrNull() ?: 0.0
      val nbvVal = row.getOrNull(nbvColIdx)?.trim()?.replace(",", "")?.toDoubleOrNull() ?: 0.0
      val qtyRaw = row.getOrNull(qtyColIdx)?.trim().orEmpty()
      val qtyParsed = qtyRaw.replace(",", "").toDoubleOrNull()?.toLong()
      val deptVal = if (deptColIdx >= 0) row.getOrNull(deptColIdx)?.trim().orEmpty() else ""
      val locVal = if (locColIdx >= 0) row.getOrNull(locColIdx)?.trim().orEmpty() else ""

      val scopedDocId = if (clientId.isNotBlank() && fiscalYearId.isNotBlank()) {
        FARAsset.buildScopedAssetKey(clientId, fiscalYearId, keyVal, sourceRowNumber)
      } else {
        FARAsset.buildAssetKey(keyVal, sourceRowNumber)
      }

      assets.add(
        FARAsset(
          id = scopedDocId,
          sl = slVal,
          assetId = keyVal,
          sourceRow = sourceRowNumber,
          assetDescription = descVal,
          assetClass = row.getOrNull(classColIdx)?.trim().orEmpty(),
          capitalizedOn = row.getOrNull(capColIdx)?.trim().orEmpty(),
          costTk = costVal,
          nbvTk = nbvVal,
          farQty = qtyParsed ?: 1L,
          farQtyPresent = qtyRaw.isNotEmpty() && qtyParsed != null,
          method = row.getOrNull(methodColIdx)?.trim().orEmpty(),
          sheetOrigin = selectedSheetName,
          importBatchId = batchId,
          clientId = clientId,
          fiscalYearId = fiscalYearId,
          department = deptVal,
          location = locVal,
          matchingKeyValue = keyVal,
          sourceWorkbookName = sourceWorkbookName,
          sourceWorksheetName = selectedSheetName,
          rawRowByHeaderJson = rawRowJson.toString(),
          userId = userId
        )
      )
    }

    val depts = assets.mapNotNull { it.department.takeIf(String::isNotBlank) }.distinct()
    val locs = assets.mapNotNull { it.location.takeIf(String::isNotBlank) }.distinct()
    val extractedMasterData = MasterDataConfig(
      id = if (clientId.isNotBlank()) "${clientId}_master_data" else "default_config",
      sites = locs.joinToString("\n"),
      departments = depts.joinToString("\n"),
      clientId = clientId,
      fiscalYearId = fiscalYearId,
      userId = userId
    )

    return AssetRegisterImportResult(
      sourceWorkbookName = sourceWorkbookName,
      targetWorksheetName = selectedSheetName,
      headers = rawHeaders,
      matchingKeyHeader = resolvedKeyHeader,
      importedAssets = assets,
      duplicateKeyValues = duplicateKeys.distinct(),
      missingKeyRowNumbers = missingKeyRows,
      validationIssues = issues,
      extractedMasterData = extractedMasterData
    )
  }

  /**
   * Non-destructive in-place OpenXML (.xlsx) cell patcher for the persistent Master Input Workbook on Google Drive.
   *
   * Rules enforced:
   * - Never regenerates the workbook from scratch.
   * - Copies all non-target ZIP entries (xl/styles.xml, other worksheets, drawings, themes, macros) byte-for-byte.
   * - Locates the target worksheet XML and parses its DOM.
   * - Matches the header row to resolve exact column letters for `matchingKeyHeader` and each mapped destination header.
   * - For each `ApprovedMasterRowPatch`, finds the exact row where `matchingKeyHeader == patch.matchingKeyValue`.
   *   - If 0 rows match -> throws IllegalStateException("ASSET_KEY_NOT_FOUND: ...")
   *   - If >1 rows match (and sourceRowHint does not disambiguate) -> throws IllegalStateException("DUPLICATE_MASTER_ASSET_KEY: ...")
   * - Modifies ONLY the mapped destination `<c>` elements in that row, preserving existing style `s="..."` attributes,
   *   `<cols>`, `<mergeCells>`, and all unmapped columns.
   * - Refuses to overwrite any `<c>` containing an `<f>` formula child unless its header is in `allowFormulaOverwriteHeaders`.
   */
  fun applyApprovedMappingsToExistingWorkbookInPlace(
    originalMasterBytes: ByteArray,
    targetWorksheetName: String = "Count Sheet",
    matchingKeyHeader: String = "Asset ID",
    approvedRowPatches: List<ApprovedMasterRowPatch>,
    allowFormulaOverwriteHeaders: Set<String> = emptySet()
  ): Result<InPlaceWorkbookPatchResult> = runCatching {
    require(originalMasterBytes.isNotEmpty()) { "Master workbook bytes cannot be empty." }
    require(approvedRowPatches.isNotEmpty()) { "No approved row patches provided for Master Workbook update." }

    // 1. Read all ZIP entries into memory preserving exact order and bytes
    val zipEntries = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(originalMasterBytes)).use { zis ->
      var entry: ZipEntry? = zis.nextEntry
      while (entry != null) {
        if (!entry.isDirectory) {
          val buffer = ByteArrayOutputStream()
          val chunk = ByteArray(4096)
          var read: Int
          while (zis.read(chunk).also { read = it } != -1) {
            buffer.write(chunk, 0, read)
          }
          zipEntries[entry.name] = buffer.toByteArray()
        }
        zis.closeEntry()
        entry = zis.nextEntry
      }
    }

    val workbookXmlBytes = zipEntries["xl/workbook.xml"]
      ?: throw IllegalStateException("WORKBOOK_CORRUPT: Missing xl/workbook.xml in Master Workbook.")
    val relsXmlBytes = zipEntries["xl/_rels/workbook.xml.rels"]
      ?: throw IllegalStateException("WORKBOOK_CORRUPT: Missing xl/_rels/workbook.xml.rels in Master Workbook.")
    val sharedStrings = parseSharedStrings(zipEntries["xl/sharedStrings.xml"])

    val sheetNameToPath = parseWorkbookSheets(workbookXmlBytes, relsXmlBytes)
    val matchedSheetEntry = sheetNameToPath.entries.firstOrNull {
      it.key.equals(targetWorksheetName, ignoreCase = true)
    } ?: sheetNameToPath.entries.firstOrNull {
      it.key.equals("Count Sheet", ignoreCase = true)
    } ?: throw IllegalStateException(
      "WORKSHEET_NOT_FOUND: Target worksheet '$targetWorksheetName' not found in Master Workbook (available: ${sheetNameToPath.keys.joinToString(", ")})."
    )

    val resolvedSheetName = matchedSheetEntry.key
    val rawSheetPath = matchedSheetEntry.value
    val normalizedSheetPath = if (rawSheetPath.startsWith("xl/")) rawSheetPath else "xl/${rawSheetPath.removePrefix("/")}"
    val targetSheetBytes = zipEntries[normalizedSheetPath] ?: zipEntries[rawSheetPath.removePrefix("/")]
      ?: throw IllegalStateException("WORKSHEET_ENTRY_MISSING: ZIP entry '$normalizedSheetPath' not found.")
    val actualZipKey = if (zipEntries.containsKey(normalizedSheetPath)) normalizedSheetPath else rawSheetPath.removePrefix("/")

    val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
    val sheetDoc = factory.newDocumentBuilder().parse(ByteArrayInputStream(targetSheetBytes))
    sheetDoc.documentElement.normalize()

    val sheetDataList = sheetDoc.getElementsByTagName("sheetData")
    require(sheetDataList.length > 0) { "WORKSHEET_INVALID: Missing <sheetData> in '$resolvedSheetName'." }
    val sheetDataEl = sheetDataList.item(0) as Element

    val rowNodes = sheetDataEl.getElementsByTagName("row")

    // 2. Locate header row and build column letter -> header text map
    var headerRowEl: Element? = null
    val headerByColLetter = LinkedHashMap<String, String>()
    for (rIdx in 0 until rowNodes.length) {
      val rowEl = rowNodes.item(rIdx) as? Element ?: continue
      val candidateMap = extractRowCellsMap(rowEl, sharedStrings)
      if (candidateMap.values.any { it.equals(matchingKeyHeader, ignoreCase = true) } ||
        candidateMap.values.any { it.equals("Asset ID", ignoreCase = true) }) {
        headerRowEl = rowEl
        candidateMap.forEach { (colLet, text) ->
          if (text.isNotBlank()) headerByColLetter[colLet] = text.trim()
        }
        break
      }
    }
    if (headerRowEl == null && rowNodes.length > 0) {
      val firstRow = rowNodes.item(0) as Element
      headerRowEl = firstRow
      extractRowCellsMap(firstRow, sharedStrings).forEach { (colLet, text) ->
        if (text.isNotBlank()) headerByColLetter[colLet] = text.trim()
      }
    }
    require(headerByColLetter.isNotEmpty()) {
      "HEADER_ROW_NOT_FOUND: Could not locate header row in worksheet '$resolvedSheetName'."
    }

    val keyColumnLetter = headerByColLetter.entries.firstOrNull {
      it.value.equals(matchingKeyHeader, ignoreCase = true)
    }?.key ?: headerByColLetter.entries.firstOrNull {
      it.value.equals("Asset ID", ignoreCase = true)
    }?.key ?: throw IllegalStateException(
      "KEY_HEADER_NOT_FOUND: Matching key header '$matchingKeyHeader' not found in worksheet '$resolvedSheetName' (headers: ${headerByColLetter.values.joinToString(", ")})."
    )

    val headerRowNumber = headerRowEl?.getAttribute("r")?.toLongOrNull() ?: 1L

    // Verify all requested destination headers exist in the Master Workbook
    val requestedDestHeaders = approvedRowPatches.flatMap { it.destinationCellsByHeader.keys }.distinct()
    val destColLetterByHeader = LinkedHashMap<String, String>()
    for (destHeader in requestedDestHeaders) {
      val matchCol = headerByColLetter.entries.firstOrNull {
        it.value.equals(destHeader.trim(), ignoreCase = true)
      }?.key ?: throw IllegalStateException(
        "DESTINATION_HEADER_MISSING: Mapped destination header '$destHeader' does not exist in Master Workbook worksheet '$resolvedSheetName'."
      )
      destColLetterByHeader[destHeader] = matchCol
    }

    // 3. Index data rows by matching key value
    val rowsByMatchingKey = LinkedHashMap<String, MutableList<Element>>()
    for (rIdx in 0 until rowNodes.length) {
      val rowEl = rowNodes.item(rIdx) as? Element ?: continue
      val rowNum = rowEl.getAttribute("r").toLongOrNull() ?: (rIdx + 1).toLong()
      if (rowNum <= headerRowNumber) continue
      val rowCells = extractRowCellsMap(rowEl, sharedStrings)
      val rowKeyVal = rowCells[keyColumnLetter]?.trim().orEmpty()
      if (rowKeyVal.isNotEmpty()) {
        rowsByMatchingKey.getOrPut(rowKeyVal) { mutableListOf() }.add(rowEl)
      }
    }

    var updatedAssetCount = 0
    var updatedCellCount = 0
    val matchedKeys = mutableListOf<String>()
    val normalizedFormulaAllowSet = allowFormulaOverwriteHeaders.map { it.trim().lowercase(Locale.US) }.toSet()

    // 4. Apply each approved patch in-place
    for (patch in approvedRowPatches) {
      val cleanKey = patch.matchingKeyValue.trim()
      val matchingRows = rowsByMatchingKey[cleanKey].orEmpty()
      if (matchingRows.isEmpty()) {
        throw IllegalStateException(
          "ASSET_KEY_NOT_FOUND: Approved asset '$cleanKey' was not found in Master Workbook column '$keyColumnLetter' ($matchingKeyHeader)."
        )
      }
      val targetRowEl = if (matchingRows.size == 1) {
        matchingRows.first()
      } else {
        val hinted = if (patch.sourceRowHint > 0L) {
          matchingRows.firstOrNull { it.getAttribute("r").toLongOrNull() == patch.sourceRowHint }
        } else null
        hinted ?: throw IllegalStateException(
          "DUPLICATE_MASTER_ASSET_KEY: Asset key '$cleanKey' matched ${matchingRows.size} rows in Master Workbook and could not be uniquely disambiguated."
        )
      }

      val rowNum = targetRowEl.getAttribute("r").toLongOrNull() ?: 2L
      for ((destHeader, newValue) in patch.destinationCellsByHeader) {
        val targetColLetter = destColLetterByHeader[destHeader] ?: continue
        val cellRef = "$targetColLetter$rowNum"
        val existingCell = findCellInRow(targetRowEl, targetColLetter)

        if (existingCell != null) {
          val hasFormula = existingCell.getElementsByTagName("f").length > 0
          if (hasFormula && destHeader.trim().lowercase(Locale.US) !in normalizedFormulaAllowSet) {
            // Skip overwriting formula cell unless explicitly allowed; workbook formulas recalculate on open
            continue
          }
          updateCellElementInPlace(sheetDoc, existingCell, newValue)
        } else {
          val createdCell = createCellElementInRowOrder(sheetDoc, targetRowEl, targetColLetter, cellRef, newValue)
          updateCellElementInPlace(sheetDoc, createdCell, newValue)
        }
        updatedCellCount++
      }
      updatedAssetCount++
      matchedKeys.add(cleanKey)
    }

    // 5. Serialize modified target worksheet DOM back to XML bytes
    zipEntries[actualZipKey] = serializeDocumentToXmlBytes(sheetDoc)

    // 6. Ensure <calcPr fullCalcOnLoad="1"/> on xl/workbook.xml so Excel recalculates dependent formulas on open
    runCatching {
      val wbDoc = factory.newDocumentBuilder().parse(ByteArrayInputStream(workbookXmlBytes))
      val calcPrNodes = wbDoc.getElementsByTagName("calcPr")
      if (calcPrNodes.length > 0) {
        (calcPrNodes.item(0) as? Element)?.setAttribute("fullCalcOnLoad", "1")
      } else {
        val calcEl = wbDoc.createElement("calcPr")
        calcEl.setAttribute("fullCalcOnLoad", "1")
        wbDoc.documentElement.appendChild(calcEl)
      }
      zipEntries["xl/workbook.xml"] = serializeDocumentToXmlBytes(wbDoc)
    }

    // 7. Re-pack ZIP archive preserving all other entries untouched
    val outBytes = ByteArrayOutputStream()
    ZipOutputStream(outBytes).use { zos ->
      for ((entryName, bytes) in zipEntries) {
        zos.putNextEntry(ZipEntry(entryName))
        zos.write(bytes)
        zos.closeEntry()
      }
    }

    InPlaceWorkbookPatchResult(
      patchedWorkbookBytes = outBytes.toByteArray(),
      targetWorksheetName = resolvedSheetName,
      updatedAssetCount = updatedAssetCount,
      updatedCellCount = updatedCellCount,
      matchedKeys = matchedKeys
    )
  }

  /**
   * Generates a standalone Daily Input Sheet `.xlsx` file for a specific Auditor and Date
   * to support viewing/downloading in Admin Review and archival under
   * `AssetFlow/[Client Name]/Daily Input Sheets/[Auditor Name]/[Date]/`.
   */
  fun generateDailySheetWorkbookBytes(
    clientName: String,
    fiscalYearLabel: String,
    auditorName: String,
    countingDate: String,
    assetsWithCounts: List<AssetWithCount>
  ): ByteArray {
    val summaryRows = listOf(
      listOf("AssetFlow — Daily Input Sheet", ""),
      listOf("Client", clientName),
      listOf("Fiscal Year", fiscalYearLabel),
      listOf("Auditor", auditorName),
      listOf("Counting Date", countingDate),
      listOf("Total Records", assetsWithCounts.size.toString())
    )
    val countRows = mutableListOf<List<String>>()
    countRows.add(AuditDropdownOptions.EXACT_29_HEADERS)
    for (item in assetsWithCounts.sortedBy { it.farAsset.sourceRow }) {
      val far = item.farAsset
      val cnt = item.countingRecord
      countRows.add(
        listOf(
          far.sl.toString(),
          far.assetId,
          far.assetDescription,
          far.assetClass,
          far.capitalizedOn,
          String.format(Locale.US, "%.2f", far.costTk),
          String.format(Locale.US, "%.2f", far.nbvTk),
          if (far.farQtyPresent) far.farQty.toString() else "",
          far.method,
          cnt?.found.orEmpty(),
          cnt?.countDate.orEmpty(),
          cnt?.countedBy.orEmpty(),
          cnt?.resolvedBuildingSite().orEmpty(),
          cnt?.resolvedFloor().orEmpty(),
          cnt?.resolvedRoomLocation().orEmpty(),
          cnt?.resolvedDepartment().orEmpty(),
          cnt?.userCustodian.orEmpty(),
          cnt?.tagStatus.orEmpty(),
          cnt?.tagNumberAcn.orEmpty(),
          cnt?.brandModel.orEmpty(),
          cnt?.physicalQtyText.orEmpty(),
          cnt?.condition.orEmpty(),
          cnt?.inUse.orEmpty(),
          cnt?.photoRef.orEmpty(),
          cnt?.remarks.orEmpty(),
          cnt?.qtyDiffText.orEmpty(),
          cnt?.result?.ifBlank { VerificationResultStatus.PENDING } ?: VerificationResultStatus.PENDING,
          cnt?.reviewedBy.orEmpty(),
          cnt?.clientExplanation.orEmpty()
        )
      )
    }
    val out = ByteArrayOutputStream()
    writeXlsxArchive(
      out,
      linkedMapOf(
        "Summary" to summaryRows,
        "Count Sheet" to countRows
      )
    )
    return out.toByteArray()
  }

  private fun extractRowCellsMap(rowEl: Element, sharedStrings: List<String>): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    val cNodes = rowEl.getElementsByTagName("c")
    var fallbackCol = 0
    for (cIdx in 0 until cNodes.length) {
      val cEl = cNodes.item(cIdx) as? Element ?: continue
      val ref = cEl.getAttribute("r")
      val colLetters = ref.takeWhile { it.isLetter() }.uppercase(Locale.US)
        .ifEmpty { columnIndexToLetter(fallbackCol) }
      fallbackCol = columnLetterToIndex(colLetters) + 1
      val cellType = cEl.getAttribute("t")
      val vNodes = cEl.getElementsByTagName("v")
      val isNodes = cEl.getElementsByTagName("is")
      val textVal = when {
        cellType == "s" && vNodes.length > 0 -> {
          val sstIdx = vNodes.item(0).textContent.trim().toIntOrNull() ?: -1
          sharedStrings.getOrElse(sstIdx) { "" }
        }
        cellType == "inlineStr" && isNodes.length > 0 -> isNodes.item(0).textContent
        vNodes.length > 0 -> vNodes.item(0).textContent
        isNodes.length > 0 -> isNodes.item(0).textContent
        else -> ""
      }
      result[colLetters] = textVal
    }
    return result
  }

  private fun findCellInRow(rowEl: Element, targetColLetter: String): Element? {
    val cNodes = rowEl.getElementsByTagName("c")
    for (i in 0 until cNodes.length) {
      val cEl = cNodes.item(i) as? Element ?: continue
      val ref = cEl.getAttribute("r")
      val colLet = ref.takeWhile { it.isLetter() }.uppercase(Locale.US)
      if (colLet == targetColLetter.uppercase(Locale.US)) {
        return cEl
      }
    }
    return null
  }

  private fun createCellElementInRowOrder(
    doc: Document,
    rowEl: Element,
    targetColLetter: String,
    cellRef: String,
    value: String
  ): Element {
    val newCell = doc.createElement("c")
    newCell.setAttribute("r", cellRef)
    val targetIdx = columnLetterToIndex(targetColLetter)
    val cNodes = rowEl.getElementsByTagName("c")
    var insertBeforeNode: Node? = null
    for (i in 0 until cNodes.length) {
      val cEl = cNodes.item(i) as? Element ?: continue
      val colLet = cEl.getAttribute("r").takeWhile { it.isLetter() }
      if (colLet.isNotEmpty() && columnLetterToIndex(colLet) > targetIdx) {
        insertBeforeNode = cEl
        break
      }
    }
    if (insertBeforeNode != null) {
      rowEl.insertBefore(newCell, insertBeforeNode)
    } else {
      rowEl.appendChild(newCell)
    }
    return newCell
  }

  private fun updateCellElementInPlace(doc: Document, cellEl: Element, newValue: String) {
    // Preserve style attribute 's' and reference 'r'; remove old value/inlineStr/formula children
    val childNodes = cellEl.childNodes
    val toRemove = mutableListOf<Node>()
    for (i in 0 until childNodes.length) {
      toRemove.add(childNodes.item(i))
    }
    toRemove.forEach { cellEl.removeChild(it) }

    val trimmed = newValue.trim()
    val isPureNumber = trimmed.matches(Regex("^-?(?:0|[1-9]\\d*)(?:\\.\\d+)?$"))
    if (isPureNumber) {
      cellEl.removeAttribute("t")
      val vEl = doc.createElement("v")
      vEl.textContent = trimmed
      cellEl.appendChild(vEl)
    } else {
      cellEl.setAttribute("t", "inlineStr")
      val isEl = doc.createElement("is")
      val tEl = doc.createElement("t")
      if (newValue.startsWith(" ") || newValue.endsWith(" ") || newValue.contains("\n")) {
        tEl.setAttribute("xml:space", "preserve")
      }
      tEl.textContent = newValue
      isEl.appendChild(tEl)
      cellEl.appendChild(isEl)
    }
  }

  private fun serializeDocumentToXmlBytes(doc: Document): ByteArray {
    val transformer = TransformerFactory.newInstance().newTransformer().apply {
      setOutputProperty(OutputKeys.ENCODING, "UTF-8")
      setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
      setOutputProperty(OutputKeys.INDENT, "no")
    }
    val writer = StringWriter()
    transformer.transform(DOMSource(doc), StreamResult(writer))
    return writer.toString().toByteArray(Charsets.UTF_8)
  }

  /**
   * Parses an .xlsx InputStream and extracts all 6 sheets, 29 Count Sheet columns,
   * Found Not in FAR records, Land/AUC/Clearing rows, and Lists master data.
   */
  fun importWorkbookFromStream(
    inputStream: InputStream,
    batchId: String = "BATCH-${System.currentTimeMillis()}",
    userId: String = ""
  ): WorkbookImportResult {
    val sheetRowsMap = extractAllWorksheetsMap(inputStream)

    // 1. Read "How to Fill"
    val howToFillRows = sheetRowsMap["How to Fill"].orEmpty()
    val howToFillNotes = howToFillRows.mapNotNull { row ->
      row.filter { it.isNotBlank() }.joinToString(" — ").takeIf { it.isNotBlank() }
    }

    // 2. Read "Count Sheet"
    val countSheetRows = sheetRowsMap["Count Sheet"].orEmpty()
    val headerRowIndex = countSheetRows.indexOfFirst { row ->
      row.any { it.trim().equals("Asset ID", ignoreCase = true) }
    }.let { if (it >= 0) it else 0 }

    val headers = if (countSheetRows.isNotEmpty() && headerRowIndex < countSheetRows.size) {
      countSheetRows[headerRowIndex].map { it.trim() }.filter { it.isNotEmpty() }
    } else {
      AuditDropdownOptions.EXACT_29_HEADERS
    }

    val farAssets = mutableListOf<FARAsset>()
    val initialCounts = mutableListOf<CountingRecord>()
    val skippedGlRows = mutableListOf<String>()

    for (rowIdx in (headerRowIndex + 1) until countSheetRows.size) {
      val row = countSheetRows[rowIdx]
      val sourceRowNumber = (rowIdx + 1).toLong()
      val slStr = row.getOrNull(0)?.trim().orEmpty()
      val assetId = row.getOrNull(1)?.trim().orEmpty()
      val description = row.getOrNull(2)?.trim().orEmpty()
      if (assetId.isEmpty() && description.isEmpty()) continue

      if (AuditCalculationEngine.isNonCountableGlOrDepreciationRow(assetId, description)) {
        skippedGlRows.add("Row $sourceRowNumber: $assetId $description")
        continue
      }

      val effectiveAssetId = assetId.ifEmpty { "UNASSIGNED-ROW-$sourceRowNumber" }
      val assetClass = row.getOrNull(3)?.trim().orEmpty()
      val capitalizedOn = row.getOrNull(4)?.trim().orEmpty()
      val costTk = row.getOrNull(5)?.trim()?.replace(",", "")?.toDoubleOrNull() ?: 0.0
      val nbvTk = row.getOrNull(6)?.trim()?.replace(",", "")?.toDoubleOrNull() ?: 0.0
      val farQtyRaw = row.getOrNull(7)?.trim().orEmpty()
      val farQtyParsed = farQtyRaw.replace(",", "").toDoubleOrNull()?.toLong()
      val farQtyPresent = farQtyRaw.isNotEmpty() && farQtyParsed != null
      val farQty = farQtyParsed ?: 0L
      val method = row.getOrNull(8)?.trim().orEmpty()

      val rawRowJson = JSONObject()
      headers.forEachIndexed { idx, hdr ->
        val cellVal = row.getOrNull(idx)?.trim().orEmpty()
        if (cellVal.isNotEmpty()) rawRowJson.put(hdr, cellVal)
      }

      val assetKey = FARAsset.buildAssetKey(effectiveAssetId, sourceRowNumber)
      val farAsset = FARAsset(
        id = assetKey,
        sl = slStr.toLongOrNull() ?: (farAssets.size + 1).toLong(),
        assetId = effectiveAssetId,
        sourceRow = sourceRowNumber,
        assetDescription = description,
        assetClass = assetClass,
        capitalizedOn = capitalizedOn,
        costTk = costTk,
        nbvTk = nbvTk,
        farQty = farQty,
        farQtyPresent = farQtyPresent,
        method = method,
        sheetOrigin = "Count Sheet",
        importBatchId = batchId,
        matchingKeyValue = effectiveAssetId,
        rawRowByHeaderJson = rawRowJson.toString(),
        userId = userId
      )
      farAssets.add(farAsset)

      val found = row.getOrNull(9)?.trim().orEmpty()
      val countDate = row.getOrNull(10)?.trim().orEmpty()
      val countedBy = row.getOrNull(11)?.trim().orEmpty()
      val buildingSite = row.getOrNull(12)?.trim().orEmpty()
      val floor = row.getOrNull(13)?.trim().orEmpty()
      val roomLocation = row.getOrNull(14)?.trim().orEmpty()
      val department = row.getOrNull(15)?.trim().orEmpty()
      val userCustodian = row.getOrNull(16)?.trim().orEmpty()
      val tagStatus = row.getOrNull(17)?.trim().orEmpty()
      val tagNumberAcn = row.getOrNull(18)?.trim().orEmpty()
      val brandModel = row.getOrNull(19)?.trim().orEmpty()
      val physicalQtyText = row.getOrNull(20)?.trim().orEmpty()
      val condition = row.getOrNull(21)?.trim().orEmpty()
      val inUse = row.getOrNull(22)?.trim().orEmpty()
      val photoRef = row.getOrNull(23)?.trim().orEmpty()
      val remarks = row.getOrNull(24)?.trim().orEmpty()
      val reviewedBy = row.getOrNull(27)?.trim().orEmpty()
      val clientExplanation = row.getOrNull(28)?.trim().orEmpty()

      val hasAnyCountInput = listOf(
        found, countDate, countedBy, buildingSite, floor, roomLocation,
        department, userCustodian, tagStatus, tagNumberAcn, brandModel,
        physicalQtyText, condition, inUse, photoRef, remarks, clientExplanation
      ).any { it.isNotEmpty() }

      if (hasAnyCountInput) {
        val draftRecord = CountingRecord(
          id = assetKey,
          assetKey = assetKey,
          assetId = effectiveAssetId,
          sourceRow = sourceRowNumber,
          found = found,
          countDate = countDate.ifBlank { AuditCalculationEngine.currentLocalDateString() },
          countedBy = countedBy,
          buildingSite = buildingSite,
          floor = floor,
          roomLocation = roomLocation,
          department = department,
          userCustodian = userCustodian,
          tagStatus = tagStatus,
          tagNumberAcn = tagNumberAcn,
          brandModel = brandModel,
          physicalQtyText = physicalQtyText,
          condition = condition,
          inUse = inUse,
          photoRef = photoRef,
          remarks = remarks,
          reviewedBy = reviewedBy,
          clientExplanation = clientExplanation,
          userId = userId
        )
        val calc = AuditCalculationEngine.evaluateRecord(
          farAsset = farAsset,
          record = draftRecord
        )
        initialCounts.add(
          draftRecord.copy(
            qtyDiffText = calc.qtyDiffText,
            result = calc.primaryResult,
            applicableExceptions = calc.applicableExceptions.joinToString(" | "),
            reviewerRequiredFlag = calc.reviewerRequiredFlag,
            reviewStatus = calc.reviewStatus
          )
        )
      }
    }

    // 3. Read "Found Not in FAR"
    val foundNotInFarRows = sheetRowsMap["Found Not in FAR"].orEmpty()
    val foundNotInFarList = mutableListOf<FoundNotInFarRecord>()
    for (rowIdx in 1 until foundNotInFarRows.size) {
      val row = foundNotInFarRows[rowIdx]
      val tempId = row.getOrNull(0)?.trim().orEmpty()
      val desc = row.getOrNull(1)?.trim().orEmpty()
      if (tempId.isEmpty() && desc.isEmpty()) continue
      val recId = tempId.ifEmpty { "FNIF-${rowIdx}" }.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
      val rawQtyStr = row.getOrNull(11)?.trim().orEmpty()
      foundNotInFarList.add(
        FoundNotInFarRecord(
          id = recId,
          tempId = tempId.ifEmpty { "FNIF-$rowIdx" },
          description = desc,
          assetClass = row.getOrNull(2)?.trim().orEmpty(),
          buildingSite = row.getOrNull(3)?.trim().orEmpty(),
          floor = row.getOrNull(4)?.trim().orEmpty(),
          roomLocation = row.getOrNull(5)?.trim().orEmpty(),
          department = row.getOrNull(6)?.trim().orEmpty(),
          userCustodian = row.getOrNull(7)?.trim().orEmpty(),
          tagStatus = row.getOrNull(8)?.trim().orEmpty(),
          tagNumberAcn = row.getOrNull(9)?.trim().orEmpty(),
          brandModel = row.getOrNull(10)?.trim().orEmpty(),
          physicalQtyText = rawQtyStr,
          physicalQty = rawQtyStr.toLongOrNull() ?: 0L,
          condition = row.getOrNull(12)?.trim().orEmpty(),
          inUse = row.getOrNull(13)?.trim().orEmpty(),
          countDate = row.getOrNull(14)?.trim().orEmpty(),
          countedBy = row.getOrNull(15)?.trim().orEmpty(),
          photoRef = row.getOrNull(16)?.trim().orEmpty(),
          remarks = row.getOrNull(17)?.trim().orEmpty(),
          clientExplanation = row.getOrNull(18)?.trim().orEmpty(),
          userId = userId
        )
      )
    }

    // 4. Read "Land, AUC & Clearing"
    val landRows = sheetRowsMap["Land, AUC & Clearing"].orEmpty()
    val landAssets = mutableListOf<FARAsset>()
    for (rowIdx in 1 until landRows.size) {
      val row = landRows[rowIdx]
      val assetId = row.getOrNull(1)?.trim().orEmpty()
      val desc = row.getOrNull(2)?.trim().orEmpty()
      if (assetId.isEmpty() && desc.isEmpty()) continue
      if (AuditCalculationEngine.isNonCountableGlOrDepreciationRow(assetId, desc)) {
        skippedGlRows.add("Land/AUC Row ${rowIdx + 1}: $assetId $desc")
        continue
      }
      val sourceRow = (1000 + rowIdx).toLong()
      val key = FARAsset.buildAssetKey(assetId.ifEmpty { "LAND-$rowIdx" }, sourceRow)
      landAssets.add(
        FARAsset(
          id = key,
          sl = row.getOrNull(0)?.trim()?.toLongOrNull() ?: rowIdx.toLong(),
          assetId = assetId.ifEmpty { "LAND-$rowIdx" },
          sourceRow = sourceRow,
          assetDescription = desc,
          assetClass = row.getOrNull(3)?.trim().orEmpty().ifEmpty { "Land & AUC" },
          capitalizedOn = row.getOrNull(4)?.trim().orEmpty(),
          costTk = row.getOrNull(5)?.trim()?.replace(",", "")?.toDoubleOrNull() ?: 0.0,
          nbvTk = row.getOrNull(6)?.trim()?.replace(",", "")?.toDoubleOrNull() ?: 0.0,
          farQty = row.getOrNull(7)?.trim()?.toLongOrNull() ?: 1L,
          farQtyPresent = true,
          method = row.getOrNull(8)?.trim().orEmpty().ifEmpty { "Non-Depreciable / AUC" },
          sheetOrigin = "Land, AUC & Clearing",
          importBatchId = batchId,
          userId = userId
        )
      )
    }

    // 5. Read "Lists" sheet for MasterDataConfig
    val listsRows = sheetRowsMap["Lists"].orEmpty()
    val auditors = mutableListOf<String>()
    val sites = mutableListOf<String>()
    val floors = mutableListOf<String>()
    val rooms = mutableListOf<String>()
    val departments = mutableListOf<String>()

    if (listsRows.size > 1) {
      val listHeaders = listsRows.first().map { it.trim().lowercase(Locale.US) }
      val auditorCol = listHeaders.indexOfFirst { it.contains("counted by") || it.contains("auditor") }
      val siteCol = listHeaders.indexOfFirst { it.contains("building") || it.contains("site") }
      val floorCol = listHeaders.indexOfFirst { it.contains("floor") }
      val roomCol = listHeaders.indexOfFirst { it.contains("room") || it.contains("location") }
      val deptCol = listHeaders.indexOfFirst { it.contains("department") }

      for (r in 1 until listsRows.size) {
        val row = listsRows[r]
        if (auditorCol >= 0) row.getOrNull(auditorCol)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Other", true) }?.let { auditors.add(it) }
        if (siteCol >= 0) row.getOrNull(siteCol)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Other", true) }?.let { sites.add(it) }
        if (floorCol >= 0) row.getOrNull(floorCol)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Other", true) }?.let { floors.add(it) }
        if (roomCol >= 0) row.getOrNull(roomCol)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Other", true) }?.let { rooms.add(it) }
        if (deptCol >= 0) row.getOrNull(deptCol)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Other", true) }?.let { departments.add(it) }
      }
    }

    val extractedMasterData = MasterDataConfig(
      id = "default_config",
      auditors = auditors.distinct().joinToString("\n"),
      sites = sites.distinct().joinToString("\n"),
      floors = floors.distinct().joinToString("\n"),
      rooms = rooms.distinct().joinToString("\n"),
      departments = departments.distinct().joinToString("\n"),
      userId = userId
    )

    return WorkbookImportResult(
      sheetNames = sheetRowsMap.keys.toList(),
      countSheetHeaders = headers,
      farAssets = farAssets,
      initialCounts = initialCounts,
      foundNotInFar = foundNotInFarList,
      landAucClearingRows = landAssets,
      skippedGlRows = skippedGlRows,
      extractedMasterData = extractedMasterData,
      howToFillNotes = howToFillNotes
    )
  }

  /**
   * Exports all 6 canonical sheets + dedicated Exceptions sheet to a valid .xlsx OutputStream,
   * strictly preserving all original FAR columns (1..9) and writing all physical count results (10..29).
   */
  fun exportWorkbookToStream(
    outputStream: OutputStream,
    assetsWithCounts: List<AssetWithCount>,
    exceptions: List<ExceptionRecord>,
    foundNotInFar: List<FoundNotInFarRecord>,
    masterData: MasterDataConfig,
    includeSampleGlControlRow: Boolean = false
  ) {
    val countSheetAssets = assetsWithCounts.filter { it.farAsset.sheetOrigin != "Land, AUC & Clearing" }
    val landAssets = assetsWithCounts.filter { it.farAsset.sheetOrigin == "Land, AUC & Clearing" }

    val totalAssets = countSheetAssets.size
    val pendingAssets = countSheetAssets.count { it.effectiveResult == VerificationResultStatus.PENDING }
    val countedAssets = totalAssets - pendingAssets
    val verifiedClean = countSheetAssets.count { it.effectiveResult == VerificationResultStatus.VERIFIED }
    val notFoundCount = countSheetAssets.count { it.effectiveResult == VerificationResultStatus.NOT_FOUND }
    val notAccessibleCount = countSheetAssets.count { it.effectiveResult == VerificationResultStatus.NOT_ACCESSIBLE }
    val pendingVerifCount = countSheetAssets.count { it.effectiveResult == VerificationResultStatus.PENDING_VERIFICATION }
    val qtyShortCount = countSheetAssets.count { it.effectiveResult == VerificationResultStatus.QTY_SHORT }
    val qtyExcessCount = countSheetAssets.count { it.effectiveResult == VerificationResultStatus.QTY_EXCESS }
    val condIssueCount = countSheetAssets.count { it.effectiveResult == VerificationResultStatus.VERIFIED_CONDITION_ISSUE }
    val tagIssueCount = countSheetAssets.count { it.effectiveResult == VerificationResultStatus.VERIFIED_TAG_ISSUE }
    val totalExceptions = countSheetAssets.count {
      it.effectiveResult !in setOf(VerificationResultStatus.PENDING, VerificationResultStatus.VERIFIED) ||
        (it.countingRecord?.exceptionList()?.isNotEmpty() == true)
    }
    val verificationPct = if (totalAssets > 0) {
      String.format(Locale.US, "%.1f%%", (countedAssets.toDouble() / totalAssets.toDouble()) * 100.0)
    } else {
      "0.0%"
    }
    val totalCostTk = countSheetAssets.sumOf { it.farAsset.costTk }
    val totalNbvTk = countSheetAssets.sumOf { it.farAsset.nbvTk }

    // Sheet 1: Summary
    val summaryRows = listOf(
      listOf("Fixed Asset Physical Counting & Verification — Executive Summary", ""),
      listOf("Metric", "Value"),
      listOf("Total Countable FAR Assets", totalAssets.toString()),
      listOf("Counted Assets", countedAssets.toString()),
      listOf("Pending Assets", pendingAssets.toString()),
      listOf("Verification Completion %", verificationPct),
      listOf("Verified (No Exceptions)", verifiedClean.toString()),
      listOf("Assets with Exceptions / Pending Verification", totalExceptions.toString()),
      listOf("Result: Not Found", notFoundCount.toString()),
      listOf("Result: Not Accessible", notAccessibleCount.toString()),
      listOf("Result: Pending Verification", pendingVerifCount.toString()),
      listOf("Result: Qty Short", qtyShortCount.toString()),
      listOf("Result: Qty Excess", qtyExcessCount.toString()),
      listOf("Result: Verified - Condition Issue", condIssueCount.toString()),
      listOf("Result: Verified - Tag Issue", tagIssueCount.toString()),
      listOf("Newly Found Assets (Found Not in FAR)", foundNotInFar.size.toString()),
      listOf("Total FAR Cost (Tk)", String.format(Locale.US, "%.2f", totalCostTk)),
      listOf("Total FAR NBV (Tk)", String.format(Locale.US, "%.2f", totalNbvTk))
    )

    // Sheet 2: How to Fill
    val howToFillRows = listOf(
      listOf("Step / Field", "Audit Instruction & Verification Rule"),
      listOf("1-9. Fixed FAR Columns", "SL, Asset ID, Asset Description (FAR), Asset Class, Capitalized On, Cost (Tk), NBV (Tk), FAR Qty, Method are fixed from the FAR and must never be overwritten."),
      listOf("10. Found?", "Select Yes, No, or Not Accessible from the Lists worksheet."),
      listOf("11. Count Date", "Auto-recorded local date (YYYY-MM-DD) when the asset is counted; preserved across edits."),
      listOf("12. Counted By", "Select from the authorised auditor master list."),
      listOf("13-16. Location & Dept", "Select Building / Site, Floor, Room / Location, and Department from dropdowns, or select 'Other' and specify the required custom value."),
      listOf("18-19. Tag Verification", "Select Tag Status and enter Tag Number (ACN) preserving leading zeros and alphanumeric characters."),
      listOf("21. Physical Qty", "Enter non-negative whole numbers only. Blank is not zero. Zero is a valid quantity."),
      listOf("26. Qty Diff", "Auto-calculated as Physical Qty - FAR Qty. Blank if Physical Qty is blank."),
      listOf("27. Result Priority", "1: Pending -> 2: Not Found -> 3: Not Accessible -> 4: Pending Verification -> 5: Qty Short -> 6: Qty Excess -> 7: Verified - Condition Issue -> 8: Verified - Tag Issue -> 9: Verified."),
      listOf("28. Reviewed By", "Auto-filled from authenticated reviewer identity; blank and flagged if reviewer identity is unavailable.")
    )

    // Sheet 3: Count Sheet (Exact 29 Headers)
    val countRows = mutableListOf<List<String>>()
    countRows.add(AuditDropdownOptions.EXACT_29_HEADERS)
    for (item in countSheetAssets.sortedBy { it.farAsset.sourceRow }) {
      val far = item.farAsset
      val cnt = item.countingRecord
      countRows.add(
        listOf(
          far.sl.toString(),
          far.assetId,
          far.assetDescription,
          far.assetClass,
          far.capitalizedOn,
          String.format(Locale.US, "%.2f", far.costTk),
          String.format(Locale.US, "%.2f", far.nbvTk),
          if (far.farQtyPresent) far.farQty.toString() else "",
          far.method,
          cnt?.found.orEmpty(),
          cnt?.countDate.orEmpty(),
          cnt?.countedBy.orEmpty(),
          cnt?.resolvedBuildingSite().orEmpty(),
          cnt?.resolvedFloor().orEmpty(),
          cnt?.resolvedRoomLocation().orEmpty(),
          cnt?.resolvedDepartment().orEmpty(),
          cnt?.userCustodian.orEmpty(),
          cnt?.tagStatus.orEmpty(),
          cnt?.tagNumberAcn.orEmpty(),
          cnt?.brandModel.orEmpty(),
          cnt?.physicalQtyText.orEmpty(),
          cnt?.condition.orEmpty(),
          cnt?.inUse.orEmpty(),
          cnt?.photoRef.orEmpty(),
          cnt?.remarks.orEmpty(),
          cnt?.qtyDiffText.orEmpty(),
          cnt?.result?.ifBlank { VerificationResultStatus.PENDING } ?: VerificationResultStatus.PENDING,
          cnt?.reviewedBy.orEmpty(),
          cnt?.clientExplanation.orEmpty()
        )
      )
    }
    if (includeSampleGlControlRow) {
      countRows.add(
        listOf(
          "",
          "GL-DEP-CTRL",
          "Accumulated Depreciation G/L Control Total (Do Not Count Physically)",
          "G/L Reconciliation",
          "",
          "0.00",
          "0.00",
          "0",
          "Control",
          "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", ""
        )
      )
    }

    // Sheet 4: Found Not in FAR
    val fnifRows = mutableListOf<List<String>>()
    fnifRows.add(
      listOf(
        "Temp ID", "Asset Description", "Asset Class", "Building / Site", "Floor",
        "Room / Location", "Department", "User / Custodian", "Tag Status",
        "Tag Number (ACN)", "Brand / Model", "Physical Qty", "Condition",
        "In Use?", "Count Date", "Counted By", "Photo Ref", "Remarks",
        "Client Explanation / Follow-up"
      )
    )
    for (rec in foundNotInFar) {
      val exportedQty = rec.physicalQtyText.ifBlank {
        if (rec.physicalQty > 0L) rec.physicalQty.toString() else ""
      }
      fnifRows.add(
        listOf(
          rec.tempId,
          rec.description,
          rec.assetClass,
          rec.resolvedBuildingSite(),
          rec.resolvedFloor(),
          rec.resolvedRoomLocation(),
          rec.resolvedDepartment(),
          rec.userCustodian,
          rec.tagStatus,
          rec.tagNumberAcn,
          rec.brandModel,
          exportedQty,
          rec.condition,
          rec.inUse,
          rec.countDate,
          rec.countedBy,
          rec.photoRef,
          AuditCalculationEngine.formatManualEntryRemarks(rec.remarks),
          rec.clientExplanation
        )
      )
    }

    // Sheet 5: Land, AUC & Clearing
    val landSheetRows = mutableListOf<List<String>>()
    landSheetRows.add(
      listOf(
        "SL", "Asset ID", "Description", "Category (Land / AUC / Clearing)",
        "Capitalized / Opening Date", "Cost (Tk)", "NBV (Tk)", "Qty", "Verification Basis"
      )
    )
    if (landAssets.isEmpty()) {
      landSheetRows.add(
        listOf(
          "1", "LAND-001", "Gazipur Industrial Plot #14 (Deed #8841)", "Land",
          "2016-01-10", "45000000.00", "45000000.00", "1", "Title Deed & Mutation Verification"
        )
      )
      landSheetRows.add(
        listOf(
          "2", "AUC-2026-02", "Narayanganj Warehouse Cold-Storage Expansion", "AUC",
          "2026-06-01", "8200000.00", "8200000.00", "1", "CWIP Project Certificate & Site Inspection"
        )
      )
    } else {
      for (item in landAssets) {
        val far = item.farAsset
        landSheetRows.add(
          listOf(
            far.sl.toString(), far.assetId, far.assetDescription, far.assetClass,
            far.capitalizedOn, String.format(Locale.US, "%.2f", far.costTk),
            String.format(Locale.US, "%.2f", far.nbvTk), far.farQty.toString(), far.method
          )
        )
      }
    }

    // Sheet 6: Lists
    val listsRows = buildListsWorksheetRows(masterData)

    // Sheet 7: Exceptions
    val exceptionRows = mutableListOf<List<String>>()
    exceptionRows.add(
      listOf(
        "Asset Key", "Asset ID", "Source Row", "Exception Type",
        "Description", "Status", "Follow-up Notes", "Resolution History"
      )
    )
    for (ex in exceptions) {
      exceptionRows.add(
        listOf(
          ex.assetKey, ex.assetId, ex.sourceRow.toString(), ex.exceptionType,
          ex.description, ex.status, ex.followUpNotes, ex.resolutionHistory
        )
      )
    }

    val sheets = linkedMapOf(
      "Summary" to summaryRows,
      "How to Fill" to howToFillRows,
      "Count Sheet" to countRows,
      "Found Not in FAR" to fnifRows,
      "Land, AUC & Clearing" to landSheetRows,
      "Lists" to listsRows,
      "Exceptions" to exceptionRows
    )

    writeXlsxArchive(outputStream, sheets)
  }

  private fun buildListsWorksheetRows(masterData: MasterDataConfig): List<List<String>> {
    val foundCol = AuditDropdownOptions.FOUND_OPTIONS
    val tagCol = AuditDropdownOptions.TAG_STATUS_OPTIONS
    val condCol = AuditDropdownOptions.CONDITION_OPTIONS
    val inUseCol = AuditDropdownOptions.IN_USE_OPTIONS
    val auditorsCol = masterData.auditorList()
    val sitesCol = masterData.siteList()
    val floorsCol = masterData.floorList()
    val roomsCol = masterData.rooms.lines().map { it.trim() }.filter { it.isNotEmpty() } + "Other"
    val deptCol = masterData.departmentList()

    val columns = listOf(
      foundCol,
      auditorsCol,
      sitesCol,
      floorsCol,
      roomsCol,
      deptCol,
      tagCol,
      condCol,
      inUseCol
    )
    val maxRows = columns.maxOfOrNull { it.size } ?: 0

    val rows = mutableListOf<List<String>>()
    rows.add(
      listOf(
        "Found?",
        "Counted By",
        "Building / Site",
        "Floor",
        "Room / Location",
        "Department",
        "Tag Status",
        "Condition",
        "In Use?"
      )
    )
    for (r in 0 until maxRows) {
      rows.add(columns.map { col -> col.getOrElse(r) { "" } })
    }
    return rows
  }

  // --- Low-Level OpenXML (.xlsx) Reader & Writer ---

  private fun parseSharedStrings(xmlBytes: ByteArray?): List<String> {
    if (xmlBytes == null) return emptyList()
    return try {
      val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
      val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
      val siNodes = doc.getElementsByTagName("si")
      val list = ArrayList<String>(siNodes.length)
      for (i in 0 until siNodes.length) {
        val si = siNodes.item(i) as? Element
        if (si != null) {
          val tNodes = si.getElementsByTagName("t")
          val sb = StringBuilder()
          for (j in 0 until tNodes.length) {
            sb.append(tNodes.item(j).textContent)
          }
          list.add(sb.toString())
        } else {
          list.add("")
        }
      }
      list
    } catch (_: Exception) {
      emptyList()
    }
  }

  private fun parseWorkbookSheets(workbookXml: ByteArray?, relsXml: ByteArray?): Map<String, String> {
    if (workbookXml == null) return emptyMap()
    return try {
      val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
      val relMap = mutableMapOf<String, String>()
      if (relsXml != null) {
        val relDoc = factory.newDocumentBuilder().parse(ByteArrayInputStream(relsXml))
        val relNodes = relDoc.getElementsByTagName("Relationship")
        for (i in 0 until relNodes.length) {
          val el = relNodes.item(i) as? Element ?: continue
          val id = el.getAttribute("Id")
          val target = el.getAttribute("Target")
          if (id.isNotEmpty() && target.isNotEmpty()) {
            relMap[id] = target
          }
        }
      }

      val wbDoc = factory.newDocumentBuilder().parse(ByteArrayInputStream(workbookXml))
      val sheetNodes = wbDoc.getElementsByTagName("sheet")
      val result = LinkedHashMap<String, String>()
      for (i in 0 until sheetNodes.length) {
        val el = sheetNodes.item(i) as? Element ?: continue
        val name = el.getAttribute("name")
        val rId = el.getAttribute("r:id").ifEmpty { el.getAttribute("id") }
        val target = relMap[rId] ?: "worksheets/sheet${i + 1}.xml"
        result[name] = target
      }
      result
    } catch (_: Exception) {
      emptyMap()
    }
  }

  private fun parseWorksheetRows(xmlBytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
    return try {
      val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
      val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
      val rowNodes = doc.getElementsByTagName("row")
      val rows = ArrayList<List<String>>(rowNodes.length)

      for (i in 0 until rowNodes.length) {
        val rowEl = rowNodes.item(i) as? Element ?: continue
        val cNodes = rowEl.getElementsByTagName("c")
        val cellsByCol = mutableMapOf<Int, String>()
        var maxCol = -1
        var nextSequentialCol = 0

        for (j in 0 until cNodes.length) {
          val cEl = cNodes.item(j) as? Element ?: continue
          val ref = cEl.getAttribute("r")
          val colIndex = if (ref.isNotEmpty()) {
            columnLetterToIndex(ref.takeWhile { it.isLetter() })
          } else {
            nextSequentialCol
          }
          nextSequentialCol = colIndex + 1
          if (colIndex > maxCol) maxCol = colIndex

          val cellType = cEl.getAttribute("t")
          val vNodes = cEl.getElementsByTagName("v")
          val isNodes = cEl.getElementsByTagName("is")

          val value = when {
            cellType == "s" && vNodes.length > 0 -> {
              val idx = vNodes.item(0).textContent.trim().toIntOrNull() ?: -1
              sharedStrings.getOrElse(idx) { "" }
            }
            cellType == "inlineStr" && isNodes.length > 0 -> {
              isNodes.item(0).textContent
            }
            vNodes.length > 0 -> vNodes.item(0).textContent
            isNodes.length > 0 -> isNodes.item(0).textContent
            else -> ""
          }
          cellsByCol[colIndex] = value
        }

        if (maxCol >= 0) {
          val rowList = ArrayList<String>(maxCol + 1)
          for (col in 0..maxCol) {
            rowList.add(cellsByCol[col] ?: "")
          }
          rows.add(rowList)
        } else {
          rows.add(emptyList())
        }
      }
      rows
    } catch (_: Exception) {
      emptyList()
    }
  }

  private fun writeXlsxArchive(
    outputStream: OutputStream,
    sheets: LinkedHashMap<String, List<List<String>>>
  ) {
    ZipOutputStream(outputStream).use { zos ->
      val contentTypesXml = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        sheets.keys.forEachIndexed { idx, _ ->
          append("""<Override PartName="/xl/worksheets/sheet${idx + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("""</Types>""")
      }
      writeZipEntry(zos, "[Content_Types].xml", contentTypesXml)

      val rootRelsXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>
      """.trimIndent()
      writeZipEntry(zos, "_rels/.rels", rootRelsXml)

      val wbRelsXml = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        sheets.keys.forEachIndexed { idx, _ ->
          append("""<Relationship Id="rId${idx + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${idx + 1}.xml"/>""")
        }
        append("""</Relationships>""")
      }
      writeZipEntry(zos, "xl/_rels/workbook.xml.rels", wbRelsXml)

      val workbookXml = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        append("""<sheets>""")
        sheets.keys.forEachIndexed { idx, sheetName ->
          append("""<sheet name="${escapeXml(sheetName)}" sheetId="${idx + 1}" r:id="rId${idx + 1}"/>""")
        }
        append("""</sheets>""")
        append("""<calcPr fullCalcOnLoad="1"/>""")
        append("""</workbook>""")
      }
      writeZipEntry(zos, "xl/workbook.xml", workbookXml)

      sheets.entries.forEachIndexed { idx, (_, rows) ->
        val sheetXml = buildWorksheetXml(rows)
        writeZipEntry(zos, "xl/worksheets/sheet${idx + 1}.xml", sheetXml)
      }
    }
  }

  private fun buildWorksheetXml(rows: List<List<String>>): String {
    val sb = StringBuilder()
    sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
    sb.append("""<sheetData>""")
    rows.forEachIndexed { rowIdx, row ->
      val rNum = rowIdx + 1
      sb.append("""<row r="$rNum">""")
      row.forEachIndexed { colIdx, cellValue ->
        val cellRef = "${columnIndexToLetter(colIdx)}$rNum"
        sb.append("""<c r="$cellRef" t="inlineStr"><is><t>${escapeXml(cellValue)}</t></is></c>""")
      }
      sb.append("""</row>""")
    }
    sb.append("""</sheetData>""")
    sb.append("""</worksheet>""")
    return sb.toString()
  }

  private fun writeZipEntry(zos: ZipOutputStream, name: String, content: String) {
    val entry = ZipEntry(name)
    zos.putNextEntry(entry)
    zos.write(content.toByteArray(Charsets.UTF_8))
    zos.closeEntry()
  }

  fun columnIndexToLetter(index: Int): String {
    var n = index
    val sb = StringBuilder()
    while (n >= 0) {
      sb.insert(0, ('A'.code + (n % 26)).toChar())
      n = (n / 26) - 1
    }
    return sb.toString()
  }

  fun columnLetterToIndex(col: String): Int {
    var result = 0
    for (ch in col.uppercase(Locale.US)) {
      if (ch in 'A'..'Z') {
        result = result * 26 + (ch.code - 'A'.code + 1)
      }
    }
    return (result - 1).coerceAtLeast(0)
  }

  private fun escapeXml(input: String): String {
    val sb = StringBuilder(input.length + 16)
    for (ch in input) {
      when (ch) {
        '&' -> sb.append("&amp;")
        '<' -> sb.append("&lt;")
        '>' -> sb.append("&gt;")
        '"' -> sb.append("&quot;")
        '\'' -> sb.append("&apos;")
        else -> if (ch.code >= 0x20 || ch == '\n' || ch == '\r' || ch == '\t') {
          sb.append(ch)
        }
      }
    }
    return sb.toString()
  }
}
