package com.example.data.schema

import com.example.data.excel.ExcelWorkbookEngine
import com.example.data.model.AuditDropdownOptions
import com.example.data.model.ExcelColumnMappingConfig
import com.example.data.model.FARAsset
import com.example.data.model.MasterDataConfig
import com.example.domain.AuditCalculationEngine
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import java.io.InputStream
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

object SchemaConfigMode {
  const val DEFAULT_SETTINGS = "Default Settings"
  const val CUSTOM_SETTINGS = "Custom Settings"
}

object FieldBehaviorType {
  const val FIXED_MASTER_DATA = "Fixed (Read-Only)"
  const val USER_INPUT = "User Input (Editable)"
  const val DROPDOWN = "Dropdown (Option List)"
  const val AUTOMATIC_SYSTEM = "Automatic (System Generated)"

  val ALL = listOf(FIXED_MASTER_DATA, USER_INPUT, DROPDOWN, AUTOMATIC_SYSTEM)
  val ALL_BEHAVIORS = ALL
}

object FieldInputType {
  const val TEXT = "Text"
  const val INTEGER = "Whole Number"
  const val DECIMAL = "Decimal Number"
  const val DATE = "Date (YYYY-MM-DD)"
  const val PHOTO_EVIDENCE = "Photo Evidence"

  val ALL = listOf(TEXT, INTEGER, DECIMAL, DATE, PHOTO_EVIDENCE)
  val ALL_INPUT_TYPES = ALL
}

object AutomationRuleType {
  const val NONE = "None"
  const val UNRESOLVED = "None"
  const val SOURCE_WORKBOOK_COLUMN = "Source Workbook Column"
  const val SUPPLEMENTARY_FILE_LOOKUP = "Supplementary File Lookup"
  const val QTY_DIFF_FORMULA = "Qty Diff (Physical Qty - FAR Qty)"
  const val VERIFICATION_RESULT_FORMULA = "Verification Result Status"
  const val AUTHENTICATED_USER_NAME = "Logged-in Auditor Name"
  const val AUTHENTICATED_USER_UID = "Logged-in Auditor UID"
  const val CURRENT_COUNT_DATE = "Current Local Date (YYYY-MM-DD)"
  const val CURRENT_TIMESTAMP = "Current ISO Timestamp"
  const val REVIEWER_NAME_ON_REVIEW = "Reviewer Identity on Approval"

  val ALL = listOf(
    NONE,
    SOURCE_WORKBOOK_COLUMN,
    SUPPLEMENTARY_FILE_LOOKUP,
    QTY_DIFF_FORMULA,
    VERIFICATION_RESULT_FORMULA,
    AUTHENTICATED_USER_NAME,
    AUTHENTICATED_USER_UID,
    CURRENT_COUNT_DATE,
    CURRENT_TIMESTAMP,
    REVIEWER_NAME_ON_REVIEW
  )
}

object SubmissionLifecycleStatus {
  const val DRAFT = "Draft"
  const val PENDING_REVIEW = "Pending Review"
  const val CHANGES_REQUESTED = "Changes Requested"
  const val REJECTED = "Rejected"
  const val APPROVED_AWAITING_MASTER_UPDATE = "Approved — Awaiting Master Update"
  const val MASTER_UPDATE_IN_PROGRESS = "Master Update In Progress"
  const val SYNC_FAILED = "Sync Failed"
  const val PUBLISHED = "Published"

  val ALL = listOf(
    DRAFT,
    PENDING_REVIEW,
    CHANGES_REQUESTED,
    REJECTED,
    APPROVED_AWAITING_MASTER_UPDATE,
    MASTER_UPDATE_IN_PROGRESS,
    SYNC_FAILED,
    PUBLISHED
  )
}

typealias DetectedColumnHeading = DetectedColumnHeader

data class DetectedColumnHeader(
  val columnIndex: Int,
  val columnLetter: String,
  val headingText: String,
  val sampleValues: List<String>,
  val nonBlankCount: Int,
  val totalRowsCount: Int
) {
  constructor(
    columnIndex: Int,
    headingText: String,
    nonBlankCount: Int,
    totalRowsCount: Int,
    sampleValues: List<String>
  ) : this(
    columnIndex = columnIndex,
    columnLetter = DynamicSchemaEngine.columnIndexToLetter(columnIndex),
    headingText = headingText,
    sampleValues = sampleValues,
    nonBlankCount = nonBlankCount,
    totalRowsCount = totalRowsCount
  )

  val hasMissingValues: Boolean
    get() = totalRowsCount > 0 && nonBlankCount < totalRowsCount
}

data class DetectedWorksheetStructure(
  val sheetName: String,
  val headerRowIndex: Int,
  val totalDataRows: Int,
  val columns: List<DetectedColumnHeader>,
  val sampleRows: List<List<String>> = emptyList()
) {
  constructor(
    sheetName: String,
    headerRowIndex: Int,
    totalDataRows: Int,
    headings: List<DetectedColumnHeader>
  ) : this(
    sheetName = sheetName,
    headerRowIndex = headerRowIndex,
    totalDataRows = totalDataRows,
    columns = headings,
    sampleRows = emptyList()
  )

  val headings: List<DetectedColumnHeader>
    get() = columns
}

data class DetectedWorkbookPreview(
  val fileName: String,
  val worksheets: List<DetectedWorksheetStructure>,
  val primaryCountingSheetName: String
)

data class SchemaFieldConfig(
  val fieldId: String,
  val displayName: String,
  val originalHeading: String,
  val sourceRegisterHeader: String = "",
  val destinationMasterHeader: String = "",
  val columnIndex: Int,
  val columnLetter: String,
  val fieldOrder: Int,
  val behavior: String = FieldBehaviorType.USER_INPUT,
  val inputType: String = FieldInputType.TEXT,
  val isRequired: Boolean = false,
  val isVisibleToUsers: Boolean = true,
  val isEditableByUsers: Boolean = true,
  val dropdownOptions: List<String> = emptyList(),
  val historicalDropdownOptions: List<String> = emptyList(),
  val isMultiSelect: Boolean = false,
  val allowOtherOption: Boolean = false,
  val automationRule: String = AutomationRuleType.NONE,
  val allowFormulaOverwrite: Boolean = false,
  val minNumericValue: Double? = null,
  val maxNumericValue: Double? = null,
  val sourceHasMissingValues: Boolean = false,
  val supplementaryValuesByAssetId: Map<String, String> = emptyMap(),
  val label: String = displayName,
  val isMandatory: Boolean = isRequired,
  val isVisible: Boolean = isVisibleToUsers,
  val columnHeading: String = originalHeading,
  val sourceSheet: String = "Count Sheet",
  val controlType: String = behavior,
  val isReadOnlyFar: Boolean = !isEditableByUsers
) {
  val originalExcelHeading: String
    get() = originalHeading

  val effectiveSourceHeader: String
    get() = sourceRegisterHeader.ifBlank { originalHeading }

  val effectiveDestinationHeader: String
    get() = destinationMasterHeader.ifBlank { originalHeading }
}

data class SupplementaryImportPreview(
  val fileName: String,
  val targetFieldId: String,
  val keyColumnHeading: String,
  val valueColumnHeading: String,
  val matchedValuesByAssetId: Map<String, String>,
  val unmatchedKeysInSupplementary: List<String>,
  val duplicateKeysInSupplementary: List<String>,
  val overwriteConflictsCount: Int,
  val remainingMissingAssetIds: List<String>
) {
  val unmatchedAssetIdsInMaster: List<String>
    get() = remainingMissingAssetIds

  val isReadyToApply: Boolean
    get() = matchedValuesByAssetId.isNotEmpty()
}

data class SchemaPublishValidationResult(
  val isValidToPublish: Boolean,
  val missingFixedFieldNames: List<String>,
  val invalidDropdownFieldNames: List<String>,
  val invalidAutomationFieldNames: List<String>,
  val allErrors: List<String>
)

@IgnoreExtraProperties
data class WorkbookSchemaVersion(
  @DocumentId val id: String = "",
  val versionNumber: Long = 1L,
  val status: String = "PUBLISHED", // DRAFT, PUBLISHED, ARCHIVED
  val configMode: String = SchemaConfigMode.DEFAULT_SETTINGS,
  val sourceWorkbookName: String = "FA_Count_Verification_Sheet.xlsx",
  val targetWorksheetName: String = "Count Sheet",
  val uniqueKeyFieldId: String = "asset_id",
  val clientId: String = "",
  val fiscalYearId: String = "",
  val fieldsJson: String = "[]",
  val changeSummary: String = "",
  val createdByUid: String = "",
  val createdByName: String = "",
  val publishedByUid: String = "",
  val publishedByName: String = "",
  val createdAtIso: String = "",
  val publishedAtIso: String = "",
  val previousVersionId: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  @Exclude
  fun parsedFields(): List<SchemaFieldConfig> =
    DynamicSchemaEngine.deserializeFieldsJson(fieldsJson)
}

/**
 * Pure Kotlin engine for Layer A (Admin Master Excel Configuration & Schema Versioning),
 * Supplementary Data Import & Key-Based Auto-Population, and Dynamic Form Validation.
 */
object DynamicSchemaEngine {

  const val MISSING_FIXED_DATA_MESSAGE = "Missing Source Data — Import Supplementary File"
  const val UNRESOLVED_AUTOMATIC_FIELD_MESSAGE = "Unresolved Automatic Field"

  fun addDropdownOption(field: SchemaFieldConfig, newOption: String): SchemaFieldConfig {
    val clean = newOption.trim()
    if (clean.isEmpty() || field.dropdownOptions.any { it.equals(clean, ignoreCase = true) }) return field
    return field.copy(dropdownOptions = field.dropdownOptions + clean)
  }

  fun deleteDropdownOption(field: SchemaFieldConfig, optionToDelete: String): SchemaFieldConfig {
    val next = field.dropdownOptions.filterNot { it == optionToDelete }
    val hist = (field.historicalDropdownOptions + optionToDelete).distinct()
    return field.copy(dropdownOptions = next, historicalDropdownOptions = hist)
  }

  fun reorderDropdownOptions(field: SchemaFieldConfig, index: Int, direction: Int): SchemaFieldConfig {
    val targetIdx = index + direction
    if (index !in field.dropdownOptions.indices || targetIdx !in field.dropdownOptions.indices) return field
    val mutable = field.dropdownOptions.toMutableList()
    val tmp = mutable[index]
    mutable[index] = mutable[targetIdx]
    mutable[targetIdx] = tmp
    return field.copy(dropdownOptions = mutable)
  }

  fun inspectWorkbookStream(
    inputStream: InputStream,
    fileName: String = "Uploaded_Workbook.xlsx"
  ): DetectedWorkbookPreview {
    val sheetsMap = ExcelWorkbookEngine.extractAllWorksheetsMap(inputStream)
    val structures = mutableListOf<DetectedWorksheetStructure>()

    for ((sheetName, rows) in sheetsMap) {
      if (rows.isEmpty()) {
        structures.add(
          DetectedWorksheetStructure(
            sheetName = sheetName,
            headerRowIndex = 0,
            totalDataRows = 0,
            columns = emptyList(),
            sampleRows = emptyList()
          )
        )
        continue
      }

      val headerIdx = rows.indexOfFirst { r ->
        r.count { it.trim().isNotEmpty() } >= 2 &&
          r.any {
            val cell = it.trim().lowercase(Locale.US)
            cell.contains("asset") || cell.contains("sl") || cell.contains("id") ||
              cell.contains("description") || cell.contains("metric") || cell.contains("step")
          }
      }.let { if (it >= 0) it else 0 }

      val headerRow = rows.getOrNull(headerIdx).orEmpty()
      val dataRows = rows.drop(headerIdx + 1).filter { r -> r.any { it.trim().isNotEmpty() } }
      val maxCol = maxOf(
        headerRow.size,
        dataRows.maxOfOrNull { it.size } ?: 0
      )

      val detectedCols = mutableListOf<DetectedColumnHeader>()
      for (colIdx in 0 until maxCol) {
        val rawHeading = headerRow.getOrNull(colIdx)?.trim().orEmpty()
        if (rawHeading.isEmpty() && dataRows.all { it.getOrNull(colIdx)?.trim().isNullOrEmpty() }) {
          continue
        }
        val heading = rawHeading.ifEmpty { "Column ${columnIndexToLetter(colIdx)}" }
        val nonBlanks = dataRows.mapNotNull { r ->
          r.getOrNull(colIdx)?.trim()?.takeIf { it.isNotEmpty() }
        }
        detectedCols.add(
          DetectedColumnHeader(
            columnIndex = colIdx,
            columnLetter = columnIndexToLetter(colIdx),
            headingText = heading,
            sampleValues = nonBlanks.distinct().take(4),
            nonBlankCount = nonBlanks.size,
            totalRowsCount = dataRows.size
          )
        )
      }

      structures.add(
        DetectedWorksheetStructure(
          sheetName = sheetName,
          headerRowIndex = headerIdx,
          totalDataRows = dataRows.size,
          columns = detectedCols,
          sampleRows = dataRows.take(5)
        )
      )
    }

    val primary = structures.firstOrNull {
      it.sheetName.equals("Count Sheet", ignoreCase = true)
    }?.sheetName
      ?: structures.maxByOrNull { it.columns.size }?.sheetName
      ?: structures.firstOrNull()?.sheetName.orEmpty()

    return DetectedWorkbookPreview(
      fileName = fileName,
      worksheets = structures,
      primaryCountingSheetName = primary
    )
  }

  /**
   * Builds the canonical 29-column schema matching `FA_Count_Verification_Sheet.xlsx` ("Default Settings").
   */
  fun buildDefault29FieldConfigs(
    masterData: MasterDataConfig? = null,
    farAssets: List<FARAsset> = emptyList()
  ): List<SchemaFieldConfig> {
    val md = masterData ?: MasterDataConfig()
    val exactHeaders = AuditDropdownOptions.EXACT_29_HEADERS

    return exactHeaders.mapIndexed { idx, heading ->
      val colLetter = columnIndexToLetter(idx)
      val order = idx + 1
      when (idx) {
        0 -> SchemaFieldConfig(
          fieldId = "sl",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = heading,
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.FIXED_MASTER_DATA,
          inputType = FieldInputType.INTEGER,
          isRequired = true,
          isEditableByUsers = false,
          automationRule = AutomationRuleType.SOURCE_WORKBOOK_COLUMN
        )
        1 -> SchemaFieldConfig(
          fieldId = "asset_id",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = heading,
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.FIXED_MASTER_DATA,
          inputType = FieldInputType.TEXT,
          isRequired = true,
          isEditableByUsers = false,
          automationRule = AutomationRuleType.SOURCE_WORKBOOK_COLUMN
        )
        2 -> SchemaFieldConfig(
          fieldId = "asset_description",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = heading,
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.FIXED_MASTER_DATA,
          inputType = FieldInputType.TEXT,
          isRequired = true,
          isEditableByUsers = false,
          automationRule = AutomationRuleType.SOURCE_WORKBOOK_COLUMN
        )
        3 -> SchemaFieldConfig(
          fieldId = "asset_class",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = heading,
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.FIXED_MASTER_DATA,
          inputType = FieldInputType.TEXT,
          isRequired = true,
          isEditableByUsers = false,
          automationRule = AutomationRuleType.SOURCE_WORKBOOK_COLUMN
        )
        4 -> SchemaFieldConfig(
          fieldId = "capitalized_on",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = heading,
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.FIXED_MASTER_DATA,
          inputType = FieldInputType.DATE,
          isRequired = false,
          isEditableByUsers = false,
          automationRule = AutomationRuleType.SOURCE_WORKBOOK_COLUMN
        )
        5 -> SchemaFieldConfig(
          fieldId = "cost_tk",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = heading,
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.FIXED_MASTER_DATA,
          inputType = FieldInputType.DECIMAL,
          isRequired = false,
          isEditableByUsers = false,
          automationRule = AutomationRuleType.SOURCE_WORKBOOK_COLUMN
        )
        6 -> SchemaFieldConfig(
          fieldId = "nbv_tk",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = heading,
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.FIXED_MASTER_DATA,
          inputType = FieldInputType.DECIMAL,
          isRequired = false,
          isEditableByUsers = false,
          automationRule = AutomationRuleType.SOURCE_WORKBOOK_COLUMN
        )
        7 -> SchemaFieldConfig(
          fieldId = "far_qty",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = heading,
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.FIXED_MASTER_DATA,
          inputType = FieldInputType.INTEGER,
          isRequired = true,
          isEditableByUsers = false,
          automationRule = AutomationRuleType.SOURCE_WORKBOOK_COLUMN,
          sourceHasMissingValues = farAssets.any { !it.farQtyPresent }
        )
        8 -> SchemaFieldConfig(
          fieldId = "method",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = heading,
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.FIXED_MASTER_DATA,
          inputType = FieldInputType.TEXT,
          isRequired = false,
          isEditableByUsers = false,
          automationRule = AutomationRuleType.SOURCE_WORKBOOK_COLUMN
        )
        9 -> SchemaFieldConfig(
          fieldId = "found",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = "",
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.DROPDOWN,
          inputType = FieldInputType.TEXT,
          isRequired = true,
          isEditableByUsers = true,
          dropdownOptions = AuditDropdownOptions.FOUND_OPTIONS
        )
        10 -> SchemaFieldConfig(
          fieldId = "count_date",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = "",
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.AUTOMATIC_SYSTEM,
          inputType = FieldInputType.DATE,
          isRequired = true,
          isEditableByUsers = false,
          automationRule = AutomationRuleType.CURRENT_COUNT_DATE
        )
        11 -> SchemaFieldConfig(
          fieldId = "counted_by",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = "",
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.AUTOMATIC_SYSTEM,
          inputType = FieldInputType.TEXT,
          isRequired = true,
          isEditableByUsers = false,
          automationRule = AutomationRuleType.AUTHENTICATED_USER_NAME
        )
        12 -> SchemaFieldConfig(
          fieldId = "building_site",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = heading,
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.DROPDOWN,
          inputType = FieldInputType.TEXT,
          isRequired = false,
          isEditableByUsers = true,
          dropdownOptions = md.siteList().ifEmpty { listOf("Dhaka HQ Tower", "Chattogram Regional Office", "Gazipur Central Plant", "Other") },
          allowOtherOption = true
        )
        13 -> SchemaFieldConfig(
          fieldId = "floor",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = heading,
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.DROPDOWN,
          inputType = FieldInputType.TEXT,
          isRequired = false,
          isEditableByUsers = true,
          dropdownOptions = md.floorList().ifEmpty { listOf("Ground Floor", "1st Floor", "2nd Floor", "3rd Floor", "Other") },
          allowOtherOption = true
        )
        14 -> SchemaFieldConfig(
          fieldId = "room_location",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = heading,
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.DROPDOWN,
          inputType = FieldInputType.TEXT,
          isRequired = false,
          isEditableByUsers = true,
          dropdownOptions = md.roomListFor("", "").ifEmpty { listOf("Main Hall", "Server Room", "Other") },
          allowOtherOption = true
        )
        15 -> SchemaFieldConfig(
          fieldId = "department",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = heading,
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.DROPDOWN,
          inputType = FieldInputType.TEXT,
          isRequired = false,
          isEditableByUsers = true,
          dropdownOptions = md.departmentList().ifEmpty { listOf("Finance & Accounts", "Information Technology", "Operations", "Other") },
          allowOtherOption = true
        )
        16 -> SchemaFieldConfig(
          fieldId = "user_custodian",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = heading,
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.USER_INPUT,
          inputType = FieldInputType.TEXT,
          isRequired = false,
          isEditableByUsers = true
        )
        17 -> SchemaFieldConfig(
          fieldId = "tag_status",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = "",
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.DROPDOWN,
          inputType = FieldInputType.TEXT,
          isRequired = false,
          isEditableByUsers = true,
          dropdownOptions = AuditDropdownOptions.TAG_STATUS_OPTIONS
        )
        18 -> SchemaFieldConfig(
          fieldId = "tag_number_acn",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = heading,
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.USER_INPUT,
          inputType = FieldInputType.TEXT,
          isRequired = false,
          isEditableByUsers = true
        )
        19 -> SchemaFieldConfig(
          fieldId = "brand_model",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = heading,
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.USER_INPUT,
          inputType = FieldInputType.TEXT,
          isRequired = false,
          isEditableByUsers = true
        )
        20 -> SchemaFieldConfig(
          fieldId = "physical_qty",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = "",
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.USER_INPUT,
          inputType = FieldInputType.INTEGER,
          isRequired = false,
          isEditableByUsers = true,
          minNumericValue = 0.0
        )
        21 -> SchemaFieldConfig(
          fieldId = "condition",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = "",
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.DROPDOWN,
          inputType = FieldInputType.TEXT,
          isRequired = false,
          isEditableByUsers = true,
          dropdownOptions = AuditDropdownOptions.CONDITION_OPTIONS
        )
        22 -> SchemaFieldConfig(
          fieldId = "in_use",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = "",
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.DROPDOWN,
          inputType = FieldInputType.TEXT,
          isRequired = false,
          isEditableByUsers = true,
          dropdownOptions = AuditDropdownOptions.IN_USE_OPTIONS
        )
        23 -> SchemaFieldConfig(
          fieldId = "photo_ref",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = "",
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.USER_INPUT,
          inputType = FieldInputType.PHOTO_EVIDENCE,
          isRequired = false,
          isEditableByUsers = true
        )
        24 -> SchemaFieldConfig(
          fieldId = "remarks",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = "",
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.USER_INPUT,
          inputType = FieldInputType.TEXT,
          isRequired = false,
          isEditableByUsers = true
        )
        25 -> SchemaFieldConfig(
          fieldId = "qty_diff",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = "",
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.AUTOMATIC_SYSTEM,
          inputType = FieldInputType.INTEGER,
          isRequired = false,
          isEditableByUsers = false,
          automationRule = AutomationRuleType.QTY_DIFF_FORMULA
        )
        26 -> SchemaFieldConfig(
          fieldId = "result",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = "",
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.AUTOMATIC_SYSTEM,
          inputType = FieldInputType.TEXT,
          isRequired = false,
          isEditableByUsers = false,
          automationRule = AutomationRuleType.VERIFICATION_RESULT_FORMULA
        )
        27 -> SchemaFieldConfig(
          fieldId = "reviewed_by",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = "",
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.AUTOMATIC_SYSTEM,
          inputType = FieldInputType.TEXT,
          isRequired = false,
          isEditableByUsers = false,
          automationRule = AutomationRuleType.REVIEWER_NAME_ON_REVIEW
        )
        else -> SchemaFieldConfig(
          fieldId = "client_explanation",
          displayName = heading,
          originalHeading = heading,
          sourceRegisterHeader = "",
          destinationMasterHeader = heading,
          columnIndex = idx,
          columnLetter = colLetter,
          fieldOrder = order,
          behavior = FieldBehaviorType.USER_INPUT,
          inputType = FieldInputType.TEXT,
          isRequired = false,
          isEditableByUsers = true
        )
      }
    }
  }

  /**
   * Automatically builds a customizable field list from any detected worksheet structure.
   */
  fun buildCustomFieldConfigsFromWorksheet(
    worksheet: DetectedWorksheetStructure,
    masterData: MasterDataConfig? = null
  ): List<SchemaFieldConfig> {
    val defaultMap = buildDefault29FieldConfigs(masterData).associateBy {
      it.originalHeading.lowercase(Locale.US)
    }
    return worksheet.columns.mapIndexed { idx, col ->
      val matchedDefault = defaultMap[col.headingText.lowercase(Locale.US)]
      if (matchedDefault != null) {
        matchedDefault.copy(
          columnIndex = col.columnIndex,
          columnLetter = col.columnLetter,
          fieldOrder = idx + 1,
          sourceHasMissingValues = col.hasMissingValues
        )
      } else {
        val cleanId = col.headingText.lowercase(Locale.US)
          .replace(Regex("[^a-z0-9]+"), "_")
          .trim('_')
          .ifEmpty { "col_${col.columnIndex}" }
        SchemaFieldConfig(
          fieldId = cleanId,
          displayName = col.headingText,
          originalHeading = col.headingText,
          sourceRegisterHeader = col.headingText,
          destinationMasterHeader = col.headingText,
          columnIndex = col.columnIndex,
          columnLetter = col.columnLetter,
          fieldOrder = idx + 1,
          behavior = FieldBehaviorType.USER_INPUT,
          inputType = FieldInputType.TEXT,
          isRequired = false,
          isVisibleToUsers = true,
          isEditableByUsers = true,
          sourceHasMissingValues = col.hasMissingValues
        )
      }
    }
  }

  /**
   * Parses a supplementary Excel (.xlsx) or CSV stream and matches rows by `keyColumnHeading`
   * against existing `FARAsset.assetId` (preserving leading zeros as strings).
   */
  fun parseSupplementaryKeyValueStream(
    inputStream: InputStream,
    fileName: String,
    targetFieldId: String,
    keyColumnHeading: String = "Asset ID",
    valueColumnHeading: String,
    existingFarAssets: List<FARAsset>,
    existingFieldValues: Map<String, String> = emptyMap()
  ): Result<SupplementaryImportPreview> = runCatching {
    val rows: List<List<String>> = if (fileName.lowercase(Locale.US).endsWith(".csv")) {
      inputStream.bufferedReader().readLines()
        .filter { it.isNotBlank() }
        .map { line -> line.split(",").map { it.trim().removeSurrounding("\"") } }
    } else {
      val map = ExcelWorkbookEngine.extractAllWorksheetsMap(inputStream)
      map.values.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    require(rows.size >= 2) { "Supplementary file '$fileName' must contain a header row and at least 1 data row." }
    val headers = rows.first().map { it.trim() }
    val keyColIdx = headers.indexOfFirst { it.equals(keyColumnHeading, ignoreCase = true) }
      .let { if (it >= 0) it else 0 }
    val valColIdx = headers.indexOfFirst { it.equals(valueColumnHeading, ignoreCase = true) }
      .let { if (it >= 0) it else if (headers.size > 1) 1 else 0 }

    val validAssetIds = existingFarAssets.map { it.assetId.trim() }.toSet()
    val matched = mutableMapOf<String, String>()
    val unmatched = mutableListOf<String>()
    val duplicates = mutableListOf<String>()
    var overwriteCount = 0

    for (r in 1 until rows.size) {
      val row = rows[r]
      val rawKey = row.getOrNull(keyColIdx)?.trim().orEmpty()
      val rawVal = row.getOrNull(valColIdx)?.trim().orEmpty()
      if (rawKey.isEmpty()) continue

      if (matched.containsKey(rawKey)) {
        duplicates.add(rawKey)
      }

      if (validAssetIds.isEmpty() || rawKey in validAssetIds) {
        val prevVal = existingFieldValues[rawKey]
        if (!prevVal.isNullOrBlank() && prevVal != rawVal) {
          overwriteCount++
        }
        matched[rawKey] = rawVal
      } else {
        unmatched.add(rawKey)
      }
    }

    val remainingMissing = existingFarAssets
      .map { it.assetId.trim() }
      .distinct()
      .filter { assetId ->
        val suppVal = matched[assetId] ?: existingFieldValues[assetId]
        suppVal.isNullOrBlank()
      }

    SupplementaryImportPreview(
      fileName = fileName,
      targetFieldId = targetFieldId,
      keyColumnHeading = headers.getOrNull(keyColIdx) ?: keyColumnHeading,
      valueColumnHeading = headers.getOrNull(valColIdx) ?: valueColumnHeading,
      matchedValuesByAssetId = matched,
      unmatchedKeysInSupplementary = unmatched.distinct(),
      duplicateKeysInSupplementary = duplicates.distinct(),
      overwriteConflictsCount = overwriteCount,
      remainingMissingAssetIds = remainingMissing
    )
  }

  /**
   * Validates that a schema version is complete before Admin can publish it to users:
   * - Every required Fixed field that had missing source values must have supplementary data or be non-required.
   * - Every Dropdown field must have at least 1 valid option.
   * - Every Automatic field must have a valid `AutomationRuleType`.
   */
  fun validateSchemaForPublishing(
    fields: List<SchemaFieldConfig>,
    farAssets: List<FARAsset> = emptyList()
  ): SchemaPublishValidationResult {
    val missingFixed = mutableListOf<String>()
    val invalidDropdowns = mutableListOf<String>()
    val invalidAutomation = mutableListOf<String>()
    val allErrors = mutableListOf<String>()

    for (field in fields) {
      when (field.behavior) {
        FieldBehaviorType.FIXED_MASTER_DATA -> {
          if (field.isRequired && field.sourceHasMissingValues) {
            val unresolvedCount = farAssets.count { asset ->
              val baseMissing = when (field.fieldId) {
                "far_qty" -> !asset.farQtyPresent
                "asset_description" -> asset.assetDescription.isBlank()
                "asset_class" -> asset.assetClass.isBlank()
                else -> false
              }
              baseMissing && field.supplementaryValuesByAssetId[asset.assetId].isNullOrBlank()
            }
            if (unresolvedCount > 0 || (farAssets.isEmpty() && field.supplementaryValuesByAssetId.isEmpty())) {
              missingFixed.add(field.displayName)
              allErrors.add("Fixed field '${field.displayName}' has missing source values and requires supplementary data before publishing.")
            }
          }
        }
        FieldBehaviorType.DROPDOWN -> {
          val cleanOpts = field.dropdownOptions.map { it.trim() }.filter { it.isNotEmpty() }
          if (cleanOpts.isEmpty()) {
            invalidDropdowns.add(field.displayName)
            allErrors.add("Dropdown field '${field.displayName}' must have at least one option configured.")
          }
        }
        FieldBehaviorType.AUTOMATIC_SYSTEM -> {
          if (field.automationRule == AutomationRuleType.NONE || field.automationRule.isBlank()) {
            invalidAutomation.add(field.displayName)
            allErrors.add("Automatic field '${field.displayName}' must have a valid calculation or system rule.")
          }
        }
      }
    }

    return SchemaPublishValidationResult(
      isValidToPublish = allErrors.isEmpty(),
      missingFixedFieldNames = missingFixed,
      invalidDropdownFieldNames = invalidDropdowns,
      invalidAutomationFieldNames = invalidAutomation,
      allErrors = allErrors
    )
  }

  /**
   * Resolves the read-only display value for a Fixed field on a specific `FARAsset`,
   * merging the imported Asset Register raw row headers, typed FARAsset fields, and supplementary lookup values.
   */
  fun resolveFixedFieldValue(
    field: SchemaFieldConfig,
    farAsset: FARAsset
  ): String {
    val supp = field.supplementaryValuesByAssetId[farAsset.assetId]?.takeIf { it.isNotBlank() }
    if (supp != null) return supp

    // First check rawRowByHeaderJson if sourceRegisterHeader is mapped
    val sourceHdr = field.effectiveSourceHeader.trim()
    if (sourceHdr.isNotEmpty() && farAsset.rawRowByHeaderJson.isNotBlank() && farAsset.rawRowByHeaderJson != "{}") {
      val rawObj = runCatching { JSONObject(farAsset.rawRowByHeaderJson) }.getOrNull()
      if (rawObj != null) {
        val exact = rawObj.optString(sourceHdr, "")
        if (exact.isNotBlank()) return exact
        val caseInsensitiveKey = rawObj.keys().asSequence().firstOrNull { it.equals(sourceHdr, ignoreCase = true) }
        if (caseInsensitiveKey != null) {
          val ciVal = rawObj.optString(caseInsensitiveKey, "")
          if (ciVal.isNotBlank()) return ciVal
        }
      }
    }

    return when (field.fieldId) {
      "sl" -> farAsset.sl.toString()
      "asset_id" -> farAsset.assetId
      "asset_description" -> farAsset.assetDescription
      "asset_class" -> farAsset.assetClass
      "capitalized_on" -> farAsset.capitalizedOn
      "cost_tk" -> String.format(Locale.US, "%.2f", farAsset.costTk)
      "nbv_tk" -> String.format(Locale.US, "%.2f", farAsset.nbvTk)
      "far_qty" -> if (farAsset.farQtyPresent) farAsset.farQty.toString() else ""
      "method" -> farAsset.method
      "department" -> farAsset.department
      "building_site", "room_location" -> farAsset.location
      else -> ""
    }
  }

  fun serializeFieldsJson(fields: List<SchemaFieldConfig>): String {
    val arr = JSONArray()
    for (f in fields.sortedBy { it.fieldOrder }) {
      val obj = JSONObject()
      obj.put("fieldId", f.fieldId)
      obj.put("displayName", f.displayName)
      obj.put("originalHeading", f.originalHeading)
      obj.put("sourceRegisterHeader", f.sourceRegisterHeader)
      obj.put("destinationMasterHeader", f.destinationMasterHeader)
      obj.put("columnIndex", f.columnIndex)
      obj.put("columnLetter", f.columnLetter)
      obj.put("fieldOrder", f.fieldOrder)
      obj.put("behavior", f.behavior)
      obj.put("inputType", f.inputType)
      obj.put("isRequired", f.isRequired)
      obj.put("isVisibleToUsers", f.isVisibleToUsers)
      obj.put("isEditableByUsers", f.isEditableByUsers)
      obj.put("dropdownOptions", JSONArray(f.dropdownOptions))
      obj.put("historicalDropdownOptions", JSONArray(f.historicalDropdownOptions))
      obj.put("isMultiSelect", f.isMultiSelect)
      obj.put("allowOtherOption", f.allowOtherOption)
      obj.put("automationRule", f.automationRule)
      obj.put("allowFormulaOverwrite", f.allowFormulaOverwrite)
      if (f.minNumericValue != null) obj.put("minNumericValue", f.minNumericValue)
      if (f.maxNumericValue != null) obj.put("maxNumericValue", f.maxNumericValue)
      obj.put("sourceHasMissingValues", f.sourceHasMissingValues)
      val suppObj = JSONObject()
      f.supplementaryValuesByAssetId.forEach { (k, v) -> suppObj.put(k, v) }
      obj.put("supplementaryValuesByAssetId", suppObj)
      arr.put(obj)
    }
    return arr.toString()
  }

  fun deserializeFieldsJson(json: String): List<SchemaFieldConfig> {
    if (json.isBlank()) return emptyList()
    return runCatching {
      val arr = JSONArray(json)
      val list = mutableListOf<SchemaFieldConfig>()
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val optsArr = obj.optJSONArray("dropdownOptions") ?: JSONArray()
        val opts = (0 until optsArr.length()).mapNotNull { optsArr.optString(it).takeIf { s -> s.isNotEmpty() } }
        val histArr = obj.optJSONArray("historicalDropdownOptions") ?: JSONArray()
        val histOpts = (0 until histArr.length()).mapNotNull { histArr.optString(it).takeIf { s -> s.isNotEmpty() } }
        val suppObj = obj.optJSONObject("supplementaryValuesByAssetId") ?: JSONObject()
        val suppMap = mutableMapOf<String, String>()
        suppObj.keys().forEach { k -> suppMap[k] = suppObj.optString(k, "") }

        val origHeading = obj.optString("originalHeading", "")
        list.add(
          SchemaFieldConfig(
            fieldId = obj.optString("fieldId", "col_$i"),
            displayName = obj.optString("displayName", "Field ${i + 1}"),
            originalHeading = origHeading,
            sourceRegisterHeader = obj.optString("sourceRegisterHeader", origHeading),
            destinationMasterHeader = obj.optString("destinationMasterHeader", origHeading),
            columnIndex = obj.optInt("columnIndex", i),
            columnLetter = obj.optString("columnLetter", columnIndexToLetter(i)),
            fieldOrder = obj.optInt("fieldOrder", i + 1),
            behavior = obj.optString("behavior", FieldBehaviorType.USER_INPUT),
            inputType = obj.optString("inputType", FieldInputType.TEXT),
            isRequired = obj.optBoolean("isRequired", false),
            isVisibleToUsers = obj.optBoolean("isVisibleToUsers", true),
            isEditableByUsers = obj.optBoolean("isEditableByUsers", true),
            dropdownOptions = opts,
            historicalDropdownOptions = histOpts,
            isMultiSelect = obj.optBoolean("isMultiSelect", false),
            allowOtherOption = obj.optBoolean("allowOtherOption", false),
            automationRule = obj.optString("automationRule", AutomationRuleType.NONE),
            allowFormulaOverwrite = obj.optBoolean("allowFormulaOverwrite", false),
            minNumericValue = if (obj.has("minNumericValue")) obj.optDouble("minNumericValue") else null,
            maxNumericValue = if (obj.has("maxNumericValue")) obj.optDouble("maxNumericValue") else null,
            sourceHasMissingValues = obj.optBoolean("sourceHasMissingValues", false),
            supplementaryValuesByAssetId = suppMap
          )
        )
      }
      list.sortedBy { it.fieldOrder }
    }.getOrElse { emptyList() }
  }

  fun createDefaultSchemaVersion(
    versionNumber: Long = 1L,
    adminUid: String = "",
    adminName: String = "Admin",
    workspaceId: String = "",
    clientId: String = "",
    fiscalYearId: String = "",
    masterData: MasterDataConfig? = null,
    farAssets: List<FARAsset> = emptyList()
  ): WorkbookSchemaVersion {
    val fields = buildDefault29FieldConfigs(masterData, farAssets)
    val nowIso = AuditCalculationEngine.currentIsoTimestamp()
    return WorkbookSchemaVersion(
      id = if (clientId.isNotBlank() && fiscalYearId.isNotBlank()) {
        "${clientId}_${fiscalYearId}_schema_v$versionNumber"
      } else {
        "schema_v$versionNumber"
      },
      versionNumber = versionNumber,
      status = "PUBLISHED",
      configMode = SchemaConfigMode.DEFAULT_SETTINGS,
      sourceWorkbookName = "FA_Count_Verification_Sheet.xlsx",
      targetWorksheetName = "Count Sheet",
      uniqueKeyFieldId = "asset_id",
      clientId = clientId,
      fiscalYearId = fiscalYearId,
      fieldsJson = serializeFieldsJson(fields),
      changeSummary = "Initial 29-column FA_Count_Verification_Sheet schema",
      createdByUid = adminUid,
      createdByName = adminName,
      publishedByUid = adminUid,
      publishedByName = adminName,
      createdAtIso = nowIso,
      publishedAtIso = nowIso,
      previousVersionId = "",
      userId = workspaceId
    )
  }

  /**
   * Automatically scans categorical columns from an uploaded worksheet and extracts unique non-blank values
   * to pre-populate dropdown option lists.
   */
  fun detectCategoricalOptionsFromWorksheet(
    worksheet: DetectedWorksheetStructure,
    mapping: ExcelColumnMappingConfig? = null
  ): Map<String, List<String>> {
    val result = mutableMapOf<String, List<String>>()
    val categoricalKeywords = mapOf(
      "department" to "department",
      "building" to "building_site",
      "site" to "building_site",
      "floor" to "floor",
      "room" to "room_location",
      "location" to "room_location",
      "found" to "found",
      "tag status" to "tag_status",
      "condition" to "condition",
      "in use" to "in_use",
      "class" to "asset_class",
      "method" to "method"
    )
    val mappedTargets = if (mapping != null) {
      mapOf(
        mapping.foundColumn.trim().lowercase(Locale.US) to "found",
        mapping.buildingSiteColumn.trim().lowercase(Locale.US) to "building_site",
        mapping.floorColumn.trim().lowercase(Locale.US) to "floor",
        mapping.roomLocationColumn.trim().lowercase(Locale.US) to "room_location",
        mapping.departmentColumn.trim().lowercase(Locale.US) to "department",
        mapping.tagStatusColumn.trim().lowercase(Locale.US) to "tag_status",
        mapping.conditionColumn.trim().lowercase(Locale.US) to "condition",
        mapping.inUseColumn.trim().lowercase(Locale.US) to "in_use",
        mapping.assetClassColumn.trim().lowercase(Locale.US) to "asset_class",
        mapping.methodColumn.trim().lowercase(Locale.US) to "method"
      )
    } else emptyMap()

    for (col in worksheet.columns) {
      val headingLower = col.headingText.trim().lowercase(Locale.US)
      val colLetterLower = col.columnLetter.trim().lowercase(Locale.US)
      val matchedLogicalKey = mappedTargets[headingLower]
        ?: mappedTargets[colLetterLower]
        ?: categoricalKeywords.entries.firstOrNull { headingLower.contains(it.key) }?.value

      if (matchedLogicalKey != null) {
        val values = worksheet.sampleRows
          .mapNotNull { r -> r.getOrNull(col.columnIndex)?.trim()?.takeIf { it.isNotEmpty() } }
          .plus(col.sampleValues)
          .distinct()
          .sorted()
        if (values.isNotEmpty()) {
          result[col.columnLetter] = values
          result[matchedLogicalKey] = values
        }
      }
    }
    return result
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
}
