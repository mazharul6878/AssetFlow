package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.excel.ExcelWorkbookEngine
import com.example.data.model.ClientRecord
import com.example.data.model.ExcelColumnMappingConfig
import com.example.data.model.MasterWorkbookSyncMetadata
import com.example.data.schema.DetectedWorkbookPreview
import com.example.data.schema.SchemaFieldConfig
import com.example.data.schema.SupplementaryImportPreview
import com.example.data.schema.WorkbookSchemaVersion
import com.example.ui.theme.AuditNavyPrimary
import com.example.ui.theme.StatusVerifiedBg
import com.example.ui.theme.StatusVerifiedFg
import com.example.ui.theme.StatusWarningBg
import com.example.ui.theme.StatusWarningFg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Admin Workspace Tab 2 — Resources:
 * 1. Asset Register (Upload / Manage — Parsed & stored in Firebase `far_assets` per Client & Fiscal Year)
 * 2. Master Input Sheet (Upload / Manage — Persistent `.xlsx` workbook in Google Drive, patched in-place on approval)
 * 3. Input Form Designer & Field Mapping (Map source Asset Register columns & destination Master Input columns)
 */
@Composable
fun ResourcesScreen(
  isEffectiveAdmin: Boolean,
  activeClient: ClientRecord? = null,
  activeFiscalYearId: String = "2026_27",
  assetRegisterValidation: ExcelWorkbookEngine.ImportValidationSummary? = null,
  masterWorkbookSyncMeta: MasterWorkbookSyncMetadata? = null,
  detectedPreview: DetectedWorkbookPreview?,
  draftFields: List<SchemaFieldConfig>,
  schemaVersions: List<WorkbookSchemaVersion>,
  supplementaryPreview: SupplementaryImportPreview?,
  columnMapping: ExcelColumnMappingConfig,
  detectedDropdownOptions: Map<String, List<String>>,
  onImportAssetRegisterToFirebase: (bytes: ByteArray, fileName: String, uniqueKeyHeader: String, replaceExisting: Boolean) -> Unit = { _, _, _, _ -> },
  onUploadMasterInputWorkbook: (bytes: ByteArray, fileName: String, targetSheetName: String, headerRowIndex: Int, uniqueKeyHeader: String) -> Unit = { _, _, _, _, _ -> },
  onDownloadMasterInputWorkbook: () -> Unit = {},
  onInspectWorkbook: (ByteArray, String) -> Unit,
  onSelectConfigMode: (String, String) -> Unit,
  onUpdateFieldConfig: (SchemaFieldConfig) -> Unit,
  onImportSupplementary: (ByteArray, String, String, String) -> Unit,
  onApplySupplementary: () -> Unit,
  onPublishSchema: (String) -> Unit,
  onRollbackSchema: (Long) -> Unit,
  onSaveColumnMapping: (ExcelColumnMappingConfig) -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var selectedSubSection by remember { mutableIntStateOf(0) }
  var uniqueKeyHeaderInput by remember(activeClient, columnMapping) {
    mutableStateOf(
      activeClient?.uniqueAssetKeyHeader?.ifBlank { columnMapping.uniqueAssetKeyHeader }
        ?: columnMapping.uniqueAssetKeyHeader.ifBlank { "Identification No." }
    )
  }
  var replaceExistingRegister by remember { mutableStateOf(false) }
  var masterTargetSheetInput by remember(masterWorkbookSyncMeta, columnMapping) {
    mutableStateOf(
      masterWorkbookSyncMeta?.targetSheetName?.ifBlank { columnMapping.targetSheetName }
        ?: columnMapping.targetSheetName.ifBlank { "1. Need to Count" }
    )
  }
  var masterHeaderRowInput by remember(masterWorkbookSyncMeta, columnMapping) {
    mutableStateOf((masterWorkbookSyncMeta?.headerRowIndex ?: columnMapping.headerRowIndex).toString())
  }

  val assetRegisterPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri ->
    if (uri != null) {
      scope.launch {
        val bytes = withContext(Dispatchers.IO) {
          context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
        if (bytes != null) {
          val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "Asset_Register.xlsx"
          onImportAssetRegisterToFirebase(
            bytes,
            fileName,
            uniqueKeyHeaderInput.trim().ifBlank { "Identification No." },
            replaceExistingRegister
          )
        }
      }
    }
  }

  val masterWorkbookPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri ->
    if (uri != null) {
      scope.launch {
        val bytes = withContext(Dispatchers.IO) {
          context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
        if (bytes != null) {
          val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "Master_Input_Workbook.xlsx"
          onUploadMasterInputWorkbook(
            bytes,
            fileName,
            masterTargetSheetInput.trim(),
            masterHeaderRowInput.toIntOrNull() ?: 1,
            uniqueKeyHeaderInput.trim().ifBlank { "Identification No." }
          )
        }
      }
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    // Sub-navigation chips for the 3 Resources pillars
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      FilterChip(
        selected = selectedSubSection == 0,
        onClick = { selectedSubSection = 0 },
        leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp)) },
        label = { Text("1. Asset Register & Master Sheet") },
        modifier = Modifier.testTag("resources_tab_workbooks")
      )
      FilterChip(
        selected = selectedSubSection == 1,
        onClick = { selectedSubSection = 1 },
        leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) },
        label = { Text("2. Input Form Designer") },
        modifier = Modifier.testTag("resources_tab_form_designer")
      )
      FilterChip(
        selected = selectedSubSection == 2,
        onClick = { selectedSubSection = 2 },
        leadingIcon = { Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp)) },
        label = { Text("3. Column Mapping") },
        modifier = Modifier.testTag("resources_tab_column_mapping")
      )
    }

    when (selectedSubSection) {
      0 -> {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          // CARD A: ASSET REGISTER (FIREBASE REQUIRED)
          item {
            ElevatedCard(
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(16.dp)
            ) {
              Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = "Asset Register (Firebase Structured Store)",
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold
                    )
                    Text(
                      text = "Import the client's original Asset Register Excel workbook. Structured asset rows and source headers are stored in Firebase for fast Auditor search and form prepopulation.",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                }

                if (activeClient != null && activeClient.assetRegisterFileName.isNotBlank()) {
                  Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StatusVerifiedBg,
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                      Text(
                        text = "Active Register: ${activeClient.assetRegisterFileName} (${activeClient.assetRegisterRowCount} assets in Firebase)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = StatusVerifiedFg
                      )
                      Text(
                        text = "Unique Matching Key: '${activeClient.uniqueAssetKeyHeader}' • Fiscal Year: ${activeFiscalYearId.replace("_", "–")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusVerifiedFg
                      )
                    }
                  }
                }

                OutlinedTextField(
                  value = uniqueKeyHeaderInput,
                  onValueChange = { uniqueKeyHeaderInput = it },
                  label = { Text("Unique Asset Key Column Header *") },
                  supportingText = { Text("e.g. Identification No., Asset Tag, Asset ID") },
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth()
                )

                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text("Replace Existing Uncounted Assets on Re-Import", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text("If off, merges/updates by unique asset key without removing existing rows", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                  Switch(
                    checked = replaceExistingRegister,
                    onCheckedChange = { replaceExistingRegister = it }
                  )
                }

                Button(
                  onClick = {
                    assetRegisterPicker.launch(
                      arrayOf(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/octet-stream",
                        "*/*"
                      )
                    )
                  },
                  enabled = isEffectiveAdmin,
                  colors = ButtonDefaults.buttonColors(containerColor = AuditNavyPrimary),
                  modifier = Modifier
                    .fillMaxWidth()
                    .testTag("upload_asset_register_excel_button")
                ) {
                  Icon(Icons.Default.FileUpload, contentDescription = null, tint = Color.White)
                  Spacer(Modifier.width(8.dp))
                  Text("Import Asset Register Excel (.xlsx) to Firebase", color = Color.White, fontWeight = FontWeight.Bold)
                }

                if (assetRegisterValidation != null) {
                  Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (assetRegisterValidation.duplicateAssetKeys.isNotEmpty() || assetRegisterValidation.skippedMissingKeyRows.isNotEmpty()) {
                      StatusWarningBg
                    } else {
                      StatusVerifiedBg
                    },
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                      Text(
                        text = "Import Validation Summary: ${assetRegisterValidation.importedRowsCount} / ${assetRegisterValidation.totalRowsScanned} rows imported",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (assetRegisterValidation.duplicateAssetKeys.isNotEmpty()) StatusWarningFg else StatusVerifiedFg
                      )
                      if (assetRegisterValidation.duplicateAssetKeys.isNotEmpty()) {
                        Text(
                          text = "Duplicate Asset Keys skipped (${assetRegisterValidation.duplicateAssetKeys.size}): ${assetRegisterValidation.duplicateAssetKeys.take(5).joinToString()}",
                          style = MaterialTheme.typography.bodySmall,
                          color = StatusWarningFg
                        )
                      }
                      if (assetRegisterValidation.skippedMissingKeyRows.isNotEmpty()) {
                        Text(
                          text = "Missing Asset Key rows skipped: ${assetRegisterValidation.skippedMissingKeyRows.size} row(s)",
                          style = MaterialTheme.typography.bodySmall,
                          color = StatusWarningFg
                        )
                      }
                    }
                  }
                }
              }
            }
          }

          // CARD B: PERSISTENT MASTER INPUT WORKBOOK (GOOGLE DRIVE PRIMARY)
          item {
            ElevatedCard(
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(16.dp)
            ) {
              Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = "Master Input Sheet (Persistent Workbook in Google Drive)",
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold
                    )
                    Text(
                      text = "Upload the client's persistent Master Input Excel workbook. Stored in Google Drive and updated non-destructively in place (only mapped destination cells) after Admin approval.",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                }

                if (masterWorkbookSyncMeta != null && masterWorkbookSyncMeta.driveFileName.isNotBlank()) {
                  Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                      Text(
                        text = "Persistent Workbook: ${masterWorkbookSyncMeta.driveFileName}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                      )
                      Text(
                        text = "Target Sheet: '${masterWorkbookSyncMeta.targetSheetName}' • Header Row: ${masterWorkbookSyncMeta.headerRowIndex} • Columns: ${masterWorkbookSyncMeta.detectedHeaders.size}",
                        style = MaterialTheme.typography.bodySmall
                      )
                      Text(
                        text = "Master Sync Status: ${masterWorkbookSyncMeta.lastSyncStatus} ${if (masterWorkbookSyncMeta.lastSyncTimestampIso.isNotBlank()) "• Last Synced: ${masterWorkbookSyncMeta.lastSyncTimestampIso.take(19)}" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                      )
                      if (masterWorkbookSyncMeta.lastSyncError.isNotBlank()) {
                        Text(
                          text = "Sync Notice: ${masterWorkbookSyncMeta.lastSyncError}",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.error
                        )
                      }
                    }
                  }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  OutlinedTextField(
                    value = masterTargetSheetInput,
                    onValueChange = { masterTargetSheetInput = it },
                    label = { Text("Target Worksheet Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1.6f)
                  )
                  OutlinedTextField(
                    value = masterHeaderRowInput,
                    onValueChange = { masterHeaderRowInput = it },
                    label = { Text("Header Row") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                  )
                }

                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Button(
                    onClick = {
                      masterWorkbookPicker.launch(
                        arrayOf(
                          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                          "application/octet-stream",
                          "*/*"
                        )
                      )
                    },
                    enabled = isEffectiveAdmin,
                    modifier = Modifier
                      .weight(1.4f)
                      .testTag("upload_persistent_master_workbook_button")
                  ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Upload Master Sheet")
                  }

                  OutlinedButton(
                    onClick = onDownloadMasterInputWorkbook,
                    modifier = Modifier
                      .weight(1f)
                      .testTag("download_persistent_master_workbook_button")
                  ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Download Master")
                  }
                }
              }
            }
          }
        }
      }

      1 -> {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          item {
            AdminMasterWorkbookConfigTab(
              isEffectiveAdmin = isEffectiveAdmin,
              detectedPreview = detectedPreview,
              draftFields = draftFields,
              schemaVersions = schemaVersions,
              supplementaryPreview = supplementaryPreview,
              onInspectWorkbook = onInspectWorkbook,
              onSelectConfigMode = onSelectConfigMode,
              onUpdateFieldConfig = onUpdateFieldConfig,
              onImportSupplementary = onImportSupplementary,
              onApplySupplementary = onApplySupplementary,
              onPublishSchema = onPublishSchema,
              onRollbackSchema = onRollbackSchema
            )
          }
          item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
          }
          item {
            AdminFieldConfigTab(
              draftFields = draftFields,
              detectedDropdownOptions = detectedDropdownOptions,
              onUpdateFieldConfig = onUpdateFieldConfig,
              onPublishSchema = onPublishSchema
            )
          }
        }
      }

      2 -> {
        AdminColumnMappingTab(
          columnMapping = columnMapping,
          onSaveMapping = onSaveColumnMapping
        )
      }
    }
  }
}
