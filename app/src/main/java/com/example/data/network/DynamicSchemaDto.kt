package com.example.data.network

import com.example.data.schema.AutomationRuleType
import com.example.data.schema.DynamicSchemaEngine
import com.example.data.schema.FieldBehaviorType
import com.example.data.schema.FieldInputType
import com.example.data.schema.SchemaConfigMode
import com.example.data.schema.SchemaFieldConfig
import com.example.data.schema.WorkbookSchemaVersion
import org.json.JSONArray
import org.json.JSONObject

/**
 * Result wrapper for Cloudflare Worker API operations.
 */
sealed class WorkerApiResult<out T> {
    data class Success<out T>(val data: T) : WorkerApiResult<T>()
    data class Error(
        val message: String,
        val statusCode: Int? = null,
        val retryAfterSeconds: Long? = null,
        val cause: Throwable? = null
    ) : WorkerApiResult<Nothing>()
}

/**
 * Pinned or published dynamic schema version returned by the Cloudflare Worker API.
 */
data class DynamicSchemaDto(
    val schemaVersionId: String,
    val clientId: String,
    val fiscalYearId: String,
    val versionNumber: Int,
    val status: String,
    val sourceWorkbookName: String,
    val uniqueAssetKeyHeader: String,
    val fields: List<DynamicFieldDto>,
    val sourceWorksheetName: String = "Count Sheet",
    val sourceWorksheetIndex: Int = 0,
    val headerRowIndex: Int = 0,
    val changeSummary: String? = null,
    val createdByUid: String = "",
    val publishedByUid: String? = null,
    val publishedAt: String? = null,
    val previousVersionId: String? = null
) {
    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("schemaVersionId", schemaVersionId)
        root.put("clientId", clientId)
        root.put("fiscalYearId", fiscalYearId)
        root.put("versionNumber", versionNumber)
        root.put("status", status)
        root.put("sourceWorkbookName", sourceWorkbookName)
        root.put("sourceWorksheetName", sourceWorksheetName)
        root.put("sourceWorksheetIndex", sourceWorksheetIndex)
        root.put("headerRowIndex", headerRowIndex)
        root.put("uniqueAssetKeyHeader", uniqueAssetKeyHeader)
        if (changeSummary != null) root.put("changeSummary", changeSummary)
        root.put("createdByUid", createdByUid)
        if (publishedByUid != null) root.put("publishedByUid", publishedByUid)
        if (publishedAt != null) root.put("publishedAt", publishedAt)
        if (previousVersionId != null) root.put("previousVersionId", previousVersionId)
        val arr = JSONArray()
        fields.forEach { arr.put(it.toJson()) }
        root.put("fields", arr)
        return root
    }

    companion object {
        fun fromJson(json: JSONObject): DynamicSchemaDto {
            val fieldsArray = json.optJSONArray("fields") ?: JSONArray()
            val fieldList = mutableListOf<DynamicFieldDto>()
            for (i in 0 until fieldsArray.length()) {
                val fObj = fieldsArray.getJSONObject(i)
                fieldList.add(DynamicFieldDto.fromJson(fObj))
            }

            return DynamicSchemaDto(
                schemaVersionId = json.optString("schemaVersionId", json.optString("schema_version_id", "")),
                clientId = json.optString("clientId", json.optString("client_id", "")),
                fiscalYearId = json.optString("fiscalYearId", json.optString("fiscal_year_id", "")),
                versionNumber = json.optInt("versionNumber", json.optInt("version_number", 1)),
                status = json.optString("status", "PUBLISHED"),
                sourceWorkbookName = json.optString("sourceWorkbookName", json.optString("source_workbook_name", "")),
                uniqueAssetKeyHeader = json.optString("uniqueAssetKeyHeader", json.optString("unique_asset_key_header", "Asset ID")),
                fields = fieldList,
                sourceWorksheetName = json.optString("sourceWorksheetName", json.optString("source_worksheet_name", "Count Sheet")),
                sourceWorksheetIndex = json.optInt("sourceWorksheetIndex", json.optInt("source_worksheet_index", 0)),
                headerRowIndex = json.optInt("headerRowIndex", json.optInt("header_row_index", 0)),
                changeSummary = if (json.has("changeSummary") && !json.isNull("changeSummary")) json.getString("changeSummary") else if (json.has("change_summary") && !json.isNull("change_summary")) json.getString("change_summary") else null,
                createdByUid = json.optString("createdByUid", json.optString("created_by_uid", "")),
                publishedByUid = if (json.has("publishedByUid") && !json.isNull("publishedByUid")) json.getString("publishedByUid") else if (json.has("published_by_uid") && !json.isNull("published_by_uid")) json.getString("published_by_uid") else null,
                publishedAt = if (json.has("publishedAt") && !json.isNull("publishedAt")) json.getString("publishedAt") else if (json.has("published_at") && !json.isNull("published_at")) json.getString("published_at") else null,
                previousVersionId = if (json.has("previousVersionId") && !json.isNull("previousVersionId")) json.getString("previousVersionId") else if (json.has("previous_version_id") && !json.isNull("previous_version_id")) json.getString("previous_version_id") else null
            )
        }
    }
}

/**
 * Individual field definition in a Dynamic Schema.
 */
data class DynamicFieldDto(
    val fieldId: String,
    val fieldKey: String,
    val displayLabel: String,
    val originalHeader: String?,
    val inputMode: String, // FIXED_IMPORTED, MANUAL, AUTO, DROPDOWN, HIDDEN, INACTIVE
    val dataType: String,  // TEXT, MULTILINE_TEXT, INTEGER, DECIMAL, DATE, BOOLEAN, SINGLE_SELECT, MULTI_SELECT, PHOTO_EVIDENCE
    val isRequired: Boolean,
    val isVisible: Boolean,
    val sortOrder: Int,
    val autoSourceRule: String? = null,
    val allowOtherOption: Boolean = false,
    val isArchived: Boolean = false,
    val options: List<DynamicFieldOptionDto> = emptyList(),
    val sourceType: String = "EXCEL_POPULATED",
    val sourceWorksheetIndex: Int? = 0,
    val sourceColumnIndex: Int? = null,
    val sourceColumnLetter: String? = null,
    val validationRulesJson: String? = null,
    val promotedFromFar: Boolean = false,
    val destinationExcelHeader: String? = null,
    val semanticRole: String? = null
) {
    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("fieldId", fieldId)
        root.put("fieldKey", fieldKey)
        root.put("displayLabel", displayLabel)
        if (originalHeader != null) root.put("originalHeader", originalHeader)
        root.put("sourceType", sourceType)
        if (sourceWorksheetIndex != null) root.put("sourceWorksheetIndex", sourceWorksheetIndex)
        if (sourceColumnIndex != null) root.put("sourceColumnIndex", sourceColumnIndex)
        if (sourceColumnLetter != null) root.put("sourceColumnLetter", sourceColumnLetter)
        root.put("inputMode", inputMode)
        root.put("dataType", dataType)
        root.put("isRequired", isRequired)
        root.put("isVisible", isVisible)
        root.put("sortOrder", sortOrder)
        if (validationRulesJson != null) root.put("validationRulesJson", validationRulesJson)
        if (autoSourceRule != null) root.put("autoSourceRule", autoSourceRule)
        root.put("promotedFromFar", promotedFromFar)
        if (destinationExcelHeader != null) root.put("destinationExcelHeader", destinationExcelHeader)
        if (semanticRole != null) root.put("semanticRole", semanticRole)
        root.put("allowOtherOption", allowOtherOption)
        root.put("isArchived", isArchived)
        val arr = JSONArray()
        options.forEach { arr.put(it.toJson()) }
        root.put("options", arr)
        return root
    }

    companion object {
        fun fromJson(json: JSONObject): DynamicFieldDto {
            val optionsArray = json.optJSONArray("options") ?: JSONArray()
            val optionList = mutableListOf<DynamicFieldOptionDto>()
            for (i in 0 until optionsArray.length()) {
                val optObj = optionsArray.getJSONObject(i)
                optionList.add(DynamicFieldOptionDto.fromJson(optObj))
            }

            return DynamicFieldDto(
                fieldId = json.optString("fieldId", json.optString("field_id", "")),
                fieldKey = json.optString("fieldKey", json.optString("field_key", "")),
                displayLabel = json.optString("displayLabel", json.optString("display_label", "")),
                originalHeader = if (json.has("originalHeader") && !json.isNull("originalHeader")) json.getString("originalHeader") else if (json.has("original_header") && !json.isNull("original_header")) json.getString("original_header") else null,
                inputMode = json.optString("inputMode", json.optString("input_mode", "MANUAL")),
                dataType = json.optString("dataType", json.optString("data_type", "TEXT")),
                isRequired = if (json.has("isRequired")) json.optBoolean("isRequired") else (json.optInt("is_required", 0) == 1),
                isVisible = if (json.has("isVisible")) json.optBoolean("isVisible", true) else (json.optInt("is_visible", 1) == 1),
                sortOrder = json.optInt("sortOrder", json.optInt("sort_order", 0)),
                autoSourceRule = if (json.has("autoSourceRule") && !json.isNull("autoSourceRule")) json.getString("autoSourceRule") else if (json.has("auto_source_rule") && !json.isNull("auto_source_rule")) json.getString("auto_source_rule") else null,
                allowOtherOption = if (json.has("allowOtherOption")) json.optBoolean("allowOtherOption") else (json.optInt("allow_other_option", 0) == 1),
                isArchived = if (json.has("isArchived")) json.optBoolean("isArchived") else (json.optInt("is_archived", 0) == 1),
                options = optionList,
                sourceType = json.optString("sourceType", json.optString("source_type", "EXCEL_POPULATED")),
                sourceWorksheetIndex = if (json.has("sourceWorksheetIndex")) json.optInt("sourceWorksheetIndex") else if (json.has("source_worksheet_index")) json.optInt("source_worksheet_index") else 0,
                sourceColumnIndex = if (json.has("sourceColumnIndex") && !json.isNull("sourceColumnIndex")) json.getInt("sourceColumnIndex") else if (json.has("source_column_index") && !json.isNull("source_column_index")) json.getInt("source_column_index") else null,
                sourceColumnLetter = if (json.has("sourceColumnLetter") && !json.isNull("sourceColumnLetter")) json.getString("sourceColumnLetter") else if (json.has("source_column_letter") && !json.isNull("source_column_letter")) json.getString("source_column_letter") else null,
                validationRulesJson = if (json.has("validationRulesJson") && !json.isNull("validationRulesJson")) json.getString("validationRulesJson") else if (json.has("validation_rules_json") && !json.isNull("validation_rules_json")) json.getString("validation_rules_json") else null,
                promotedFromFar = if (json.has("promotedFromFar")) json.optBoolean("promotedFromFar") else (json.optInt("promoted_from_far", 0) == 1),
                destinationExcelHeader = if (json.has("destinationExcelHeader") && !json.isNull("destinationExcelHeader")) json.getString("destinationExcelHeader") else if (json.has("destination_excel_header") && !json.isNull("destination_excel_header")) json.getString("destination_excel_header") else null,
                semanticRole = if (json.has("semanticRole") && !json.isNull("semanticRole")) json.getString("semanticRole") else if (json.has("semantic_role") && !json.isNull("semantic_role")) json.getString("semantic_role") else null
            )
        }
    }
}

/**
 * Dropdown option definition.
 */
data class DynamicFieldOptionDto(
    val optionId: String,
    val optionLabel: String,
    val optionValue: String,
    val sortOrder: Int = 0,
    val isActive: Boolean = true
) {
    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("optionId", optionId)
        root.put("optionLabel", optionLabel)
        root.put("optionValue", optionValue)
        root.put("sortOrder", sortOrder)
        root.put("isActive", isActive)
        return root
    }

    companion object {
        fun fromJson(json: JSONObject): DynamicFieldOptionDto {
            return DynamicFieldOptionDto(
                optionId = json.optString("optionId", json.optString("option_id", "")),
                optionLabel = json.optString("optionLabel", json.optString("option_label", json.optString("label", ""))),
                optionValue = json.optString("optionValue", json.optString("option_value", json.optString("value", ""))),
                sortOrder = json.optInt("sortOrder", json.optInt("sort_order", 0)),
                isActive = if (json.has("isActive")) json.optBoolean("isActive", true) else (json.optInt("is_active", 1) == 1)
            )
        }
    }
}

/**
 * Request payload for analyzing uploaded Excel headers against Worker heuristics.
 * Maps to POST /api/v1/clients/:clientId/schemas/analyze-headers.
 */
data class AnalyzeHeadersRequestDto(
    val headers: List<String>,
    val rows: List<List<String?>>
) {
    fun toJson(): JSONObject {
        val root = JSONObject()
        val hArr = JSONArray()
        headers.forEach { hArr.put(it) }
        root.put("headers", hArr)

        val rArr = JSONArray()
        rows.forEach { row ->
            val rowArr = JSONArray()
            row.forEach { cell ->
                if (cell == null) rowArr.put(JSONObject.NULL) else rowArr.put(cell)
            }
            rArr.put(rowArr)
        }
        root.put("rows", rArr)
        return root
    }
}

/**
 * Classification result for a single Excel column returned by the Worker.
 */
data class HeaderClassificationResultDto(
    val headerText: String,
    val columnIndex: Int,
    val columnLetter: String,
    val nonBlankCount: Int,
    val totalRows: Int,
    val sampleValues: List<String>,
    val inferredDataType: String,
    val classification: String,
    val isSparse: Boolean
) {
    companion object {
        fun fromJson(json: JSONObject): HeaderClassificationResultDto {
            val sampleArr = json.optJSONArray("sampleValues") ?: JSONArray()
            val samples = mutableListOf<String>()
            for (i in 0 until sampleArr.length()) {
                samples.add(sampleArr.optString(i, ""))
            }
            return HeaderClassificationResultDto(
                headerText = json.optString("headerText", json.optString("header_text", "")),
                columnIndex = json.optInt("columnIndex", json.optInt("column_index", 0)),
                columnLetter = json.optString("columnLetter", json.optString("column_letter", "")),
                nonBlankCount = json.optInt("nonBlankCount", json.optInt("non_blank_count", 0)),
                totalRows = json.optInt("totalRows", json.optInt("total_rows", 0)),
                sampleValues = samples,
                inferredDataType = json.optString("inferredDataType", json.optString("inferred_data_type", "TEXT")),
                classification = json.optString("classification", "FIXED_IMPORTED"),
                isSparse = json.optBoolean("isSparse", json.optBoolean("is_sparse", false))
            )
        }
    }
}

/**
 * Two-way conversion extensions between Android Compose WorkbookSchemaVersion / SchemaFieldConfig
 * and Cloudflare Worker / D1 DynamicSchemaDto / DynamicFieldDto.
 */
fun WorkbookSchemaVersion.toDynamicSchemaDto(): DynamicSchemaDto {
    val parsed = parsedFields()
    val dFields = parsed.mapIndexed { idx, fld ->
        fld.toDynamicFieldDto(id, clientId, idx)
    }
    return DynamicSchemaDto(
        schemaVersionId = id,
        clientId = clientId,
        fiscalYearId = fiscalYearId,
        versionNumber = versionNumber.toInt(),
        status = status.ifBlank { "PUBLISHED" },
        sourceWorkbookName = sourceWorkbookName.ifBlank { "FA_Count_Verification_Sheet.xlsx" },
        uniqueAssetKeyHeader = uniqueKeyFieldId.ifBlank { "Asset ID" },
        fields = dFields,
        sourceWorksheetName = targetWorksheetName.ifBlank { "Count Sheet" },
        sourceWorksheetIndex = 0,
        headerRowIndex = 0,
        changeSummary = changeSummary.ifBlank { null },
        createdByUid = createdByUid.ifBlank { userId },
        publishedByUid = publishedByUid.ifBlank { null },
        publishedAt = publishedAtIso.ifBlank { null },
        previousVersionId = previousVersionId.ifBlank { null }
    )
}

fun SchemaFieldConfig.toDynamicFieldDto(
    schemaVersionId: String,
    clientId: String,
    index: Int = 0
): DynamicFieldDto {
    val mode = when (behavior) {
        FieldBehaviorType.FIXED_MASTER_DATA -> "FIXED_IMPORTED"
        FieldBehaviorType.AUTOMATIC_SYSTEM -> "AUTO"
        FieldBehaviorType.DROPDOWN -> "DROPDOWN"
        else -> "MANUAL"
    }
    val dType = when (inputType) {
        FieldInputType.INTEGER -> "INTEGER"
        FieldInputType.DECIMAL -> "DECIMAL"
        FieldInputType.DATE -> "DATE"
        FieldInputType.PHOTO_EVIDENCE -> "PHOTO_EVIDENCE"
        else -> if (behavior == FieldBehaviorType.DROPDOWN) {
            if (isMultiSelect) "MULTI_SELECT" else "SINGLE_SELECT"
        } else "TEXT"
    }
    val sType = when {
        behavior == FieldBehaviorType.FIXED_MASTER_DATA -> "EXCEL_POPULATED"
        behavior == FieldBehaviorType.AUTOMATIC_SYSTEM -> "SYSTEM"
        columnIndex >= 0 -> "EXCEL_EMPTY"
        else -> "CUSTOM"
    }
    val opts = dropdownOptions.mapIndexed { optIdx, optVal ->
        DynamicFieldOptionDto(
            optionId = "opt_${fieldId}_${optIdx + 1}",
            optionLabel = optVal,
            optionValue = optVal,
            sortOrder = optIdx + 1,
            isActive = true
        )
    }
    val key = fieldId.ifBlank {
        displayName.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_")
    }

    return DynamicFieldDto(
        fieldId = fieldId.ifBlank { "fld_${clientId}_$index" },
        fieldKey = key,
        displayLabel = displayName.ifBlank { originalHeading },
        originalHeader = originalHeading.ifBlank { null },
        inputMode = mode,
        dataType = dType,
        isRequired = isRequired,
        isVisible = isVisibleToUsers,
        sortOrder = fieldOrder,
        autoSourceRule = if (automationRule == AutomationRuleType.NONE) null else automationRule,
        allowOtherOption = allowOtherOption,
        isArchived = false,
        options = opts,
        sourceType = sType,
        sourceWorksheetIndex = 0,
        sourceColumnIndex = if (columnIndex >= 0) columnIndex else null,
        sourceColumnLetter = columnLetter.ifBlank { null },
        promotedFromFar = isReadOnlyFar,
        destinationExcelHeader = destinationMasterHeader.ifBlank { null }
    )
}

fun DynamicSchemaDto.toWorkbookSchemaVersion(): WorkbookSchemaVersion {
    val schemaFields = fields.map { it.toSchemaFieldConfig() }
    val fieldsJson = DynamicSchemaEngine.serializeFieldsJson(schemaFields)
    return WorkbookSchemaVersion(
        id = schemaVersionId,
        versionNumber = versionNumber.toLong(),
        status = status,
        configMode = SchemaConfigMode.CUSTOM_SETTINGS,
        sourceWorkbookName = sourceWorkbookName,
        targetWorksheetName = sourceWorksheetName,
        uniqueKeyFieldId = uniqueAssetKeyHeader,
        clientId = clientId,
        fiscalYearId = fiscalYearId,
        fieldsJson = fieldsJson,
        changeSummary = changeSummary.orEmpty(),
        createdByUid = createdByUid,
        publishedByUid = publishedByUid.orEmpty(),
        publishedAtIso = publishedAt.orEmpty(),
        previousVersionId = previousVersionId.orEmpty(),
        userId = publishedByUid ?: createdByUid
    )
}

fun DynamicFieldDto.toSchemaFieldConfig(): SchemaFieldConfig {
    val bType = when (inputMode) {
        "FIXED_IMPORTED" -> FieldBehaviorType.FIXED_MASTER_DATA
        "AUTO" -> FieldBehaviorType.AUTOMATIC_SYSTEM
        "DROPDOWN" -> FieldBehaviorType.DROPDOWN
        else -> FieldBehaviorType.USER_INPUT
    }
    val iType = when (dataType) {
        "INTEGER" -> FieldInputType.INTEGER
        "DECIMAL" -> FieldInputType.DECIMAL
        "DATE" -> FieldInputType.DATE
        "PHOTO_EVIDENCE" -> FieldInputType.PHOTO_EVIDENCE
        else -> FieldInputType.TEXT
    }
    val colIdx = sourceColumnIndex ?: 0
    val colLet = sourceColumnLetter ?: DynamicSchemaEngine.columnIndexToLetter(colIdx)

    return SchemaFieldConfig(
        fieldId = fieldId,
        displayName = displayLabel,
        originalHeading = originalHeader ?: displayLabel,
        sourceRegisterHeader = originalHeader ?: "",
        destinationMasterHeader = destinationExcelHeader ?: "",
        columnIndex = colIdx,
        columnLetter = colLet,
        fieldOrder = sortOrder,
        behavior = bType,
        inputType = iType,
        isRequired = isRequired,
        isVisibleToUsers = isVisible,
        isEditableByUsers = (inputMode != "FIXED_IMPORTED"),
        dropdownOptions = options.map { it.optionLabel },
        isMultiSelect = (dataType == "MULTI_SELECT"),
        allowOtherOption = allowOtherOption,
        automationRule = autoSourceRule ?: AutomationRuleType.NONE
    )
}

/**
 * Fixed Asset Register (FAR) baseline representation returned by the Worker.
 * Preserves all imported columns, original header text, and duplicate header identities.
 */
data class FarAssetBaselineDto(
    val assetKey: String,
    val assetId: String, // String strictly preserving leading zeros (e.g. "004092")
    val clientId: String,
    val fiscalYearId: String,
    val sourceRow: Long,
    val rawRowData: Map<String, String>, // Dynamic map preserving all imported columns
    val indexedColumns: Map<String, String> = emptyMap() // Indexed by "col_index:header" for duplicate header stability
) {
    val assetDescription: String
        get() = rawRowData["Asset Description"] ?: rawRowData["Description"] ?: rawRowData["Item Description"] ?: ""

    val location: String
        get() = rawRowData["Room / Location"] ?: rawRowData["Location"] ?: rawRowData["Building / Site"] ?: ""

    val department: String
        get() = rawRowData["Department"] ?: rawRowData["Cost Center"] ?: ""

    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("asset_key", assetKey)
        root.put("asset_id", assetId)
        root.put("client_id", clientId)
        root.put("fiscal_year_id", fiscalYearId)
        root.put("source_row", sourceRow)

        val rawObj = JSONObject()
        rawRowData.forEach { (k, v) -> rawObj.put(k, v) }
        root.put("raw_row_json", rawObj.toString())

        val indexedObj = JSONObject()
        indexedColumns.forEach { (k, v) -> indexedObj.put(k, v) }
        root.put("indexed_columns_json", indexedObj.toString())

        return root
    }

    companion object {
        fun fromJson(json: JSONObject): FarAssetBaselineDto {
            val rawMap = mutableMapOf<String, String>()
            val rawJsonStr = json.optString("raw_row_json", json.optString("rawRowJson", "{}"))
            try {
                val rawObj = JSONObject(rawJsonStr)
                val keys = rawObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    rawMap[k] = rawObj.optString(k, "")
                }
            } catch (_: Exception) {}

            val indexedMap = mutableMapOf<String, String>()
            val indexedJsonStr = json.optString("indexed_columns_json", "{}")
            try {
                val indexedObj = JSONObject(indexedJsonStr)
                val keys = indexedObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    indexedMap[k] = indexedObj.optString(k, "")
                }
            } catch (_: Exception) {}

            return FarAssetBaselineDto(
                assetKey = json.optString("asset_key", json.optString("assetKey", "")),
                assetId = json.optString("asset_id", json.optString("assetId", "")),
                clientId = json.optString("client_id", json.optString("clientId", "")),
                fiscalYearId = json.optString("fiscal_year_id", json.optString("fiscalYearId", "")),
                sourceRow = json.optLong("source_row", json.optLong("sourceRow", 0L)),
                rawRowData = rawMap,
                indexedColumns = indexedMap
            )
        }
    }
}

/**
 * Typed submission payload sent to the Worker.
 */
data class CountingSubmissionDto(
    val assetKey: String,
    val schemaVersionId: String,
    val countDate: String,
    val batchId: String? = null,
    val idempotencyKey: String,
    val values: Map<String, Any?> // Keyed by immutable field_id
) {
    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("assetKey", assetKey)
        root.put("schemaVersionId", schemaVersionId)
        root.put("countDate", countDate)
        if (batchId != null) root.put("batchId", batchId)
        root.put("idempotencyKey", idempotencyKey)

        val valuesObj = JSONObject()
        for ((fieldId, v) in values) {
            when (v) {
                null -> valuesObj.put(fieldId, JSONObject.NULL)
                is List<*> -> {
                    val arr = JSONArray()
                    v.forEach { item -> arr.put(item) }
                    valuesObj.put(fieldId, arr)
                }
                else -> valuesObj.put(fieldId, v)
            }
        }
        root.put("values", valuesObj)
        return root
    }
}

/**
 * Counting submission response.
 */
data class CountingSubmissionResultDto(
    val recordId: String,
    val status: String,
    val isIdempotentReplay: Boolean
) {
    companion object {
        fun fromJson(json: JSONObject): CountingSubmissionResultDto {
            return CountingSubmissionResultDto(
                recordId = json.optString("recordId", ""),
                status = json.optString("status", "SUBMITTED"),
                isIdempotentReplay = json.optBoolean("isIdempotentReplay", false)
            )
        }
    }
}
