package com.example.ui.screens
import com.example.data.model.ExportJobRecord

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AssetPhotoRecord
import com.example.data.model.ProjectSettings
import com.example.data.model.SyncState
import com.example.data.model.UserProfile
import com.example.data.model.UserSubmissionRecord
import com.example.data.schema.AutomationRuleType
import com.example.data.schema.DetectedWorkbookPreview
import com.example.data.schema.DynamicSchemaEngine
import com.example.data.schema.FieldBehaviorType
import com.example.data.schema.FieldInputType
import com.example.data.schema.SchemaConfigMode
import com.example.data.schema.SchemaFieldConfig
import com.example.data.schema.SubmissionLifecycleStatus
import com.example.data.schema.SupplementaryImportPreview
import com.example.data.schema.WorkbookSchemaVersion
import com.example.ui.theme.AuditGoldLight
import com.example.ui.theme.AuditNavyPrimary
import com.example.ui.theme.StatusExceptionBg
import com.example.ui.theme.StatusExceptionFg
import com.example.ui.theme.StatusVerifiedBg
import com.example.ui.theme.StatusVerifiedFg
import com.example.ui.theme.StatusWarningBg
import com.example.ui.theme.StatusWarningFg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================================
// PART A: ADMIN MASTER WORKBOOK CONFIGURATION TAB
// ============================================================================

@Composable
fun AdminMasterWorkbookConfigTab(
  isEffectiveAdmin: Boolean,
  detectedPreview: DetectedWorkbookPreview?,
  draftFields: List<SchemaFieldConfig>,
  schemaVersions: List<WorkbookSchemaVersion>,
  supplementaryPreview: SupplementaryImportPreview?,
  onInspectWorkbook: (bytes: ByteArray, fileName: String) -> Unit,
  onSelectConfigMode: (mode: String, sheetName: String) -> Unit,
  onUpdateFieldConfig: (SchemaFieldConfig) -> Unit,
  onImportSupplementary: (bytes: ByteArray, fileName: String, targetFieldId: String, valHeading: String) -> Unit,
  onApplySupplementary: () -> Unit,
  onPublishSchema: (changeSummary: String) -> Unit,
  onRollbackSchema: (versionNumber: Long) -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var showSupplementaryDialogForField by remember { mutableStateOf<SchemaFieldConfig?>(null) }
  var publishSummaryInput by remember { mutableStateOf("Updated Master Configuration") }
  var showPublishConfirmDialog by remember { mutableStateOf(false) }

  val masterWorkbookPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri ->
    if (uri != null) {
      scope.launch {
        val bytes = withContext(Dispatchers.IO) {
          context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
        if (bytes != null) {
          val name = uri.lastPathSegment ?: "Master_Workbook.xlsx"
          onInspectWorkbook(bytes, name)
        }
      }
    }
  }

  val supplementaryFilePicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri ->
    val field = showSupplementaryDialogForField
    if (uri != null && field != null) {
      scope.launch {
        val bytes = withContext(Dispatchers.IO) {
          context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
        if (bytes != null) {
          val name = uri.lastPathSegment ?: "Supplementary.csv"
          onImportSupplementary(bytes, name, field.fieldId, field.originalExcelHeading)
        }
      }
    }
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    // 1. Upload & Inspection Card
    item {
      ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text(
                text = "1. Official Master Excel Upload",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = "Upload or replace the official Master Excel workbook to inspect worksheets and headings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
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
              modifier = Modifier.testTag("admin_upload_master_excel_button"),
              colors = ButtonDefaults.buttonColors(containerColor = AuditNavyPrimary)
            ) {
              Icon(Icons.Default.FileUpload, contentDescription = null, tint = AuditGoldLight)
              Spacer(Modifier.width(6.dp))
              Text("Upload Master Excel", color = Color.White)
            }
          }

          if (detectedPreview != null) {
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = MaterialTheme.colorScheme.surfaceVariant,
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                  text = "Detected Workbook: ${detectedPreview.fileName} (${detectedPreview.worksheets.size} worksheets)",
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold
                )
                detectedPreview.worksheets.forEach { ws ->
                  Text(
                    text = "• Sheet '${ws.sheetName}': ${ws.headings.size} columns, ${ws.totalDataRows} data rows",
                    style = MaterialTheme.typography.bodySmall
                  )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                  text = "How would you like to configure this workbook?",
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Button(
                    onClick = {
                      onSelectConfigMode(SchemaConfigMode.DEFAULT_SETTINGS, detectedPreview.primaryCountingSheetName)
                    },
                    modifier = Modifier.testTag("admin_config_keep_default_button")
                  ) {
                    Text("Keep Default Settings")
                  }
                  OutlinedButton(
                    onClick = {
                      onSelectConfigMode(SchemaConfigMode.CUSTOM_SETTINGS, detectedPreview.primaryCountingSheetName)
                    },
                    modifier = Modifier.testTag("admin_config_customise_button")
                  ) {
                    Text("Customise Settings")
                  }
                }
              }
            }
          }
        }
      }
    }

    // 2. Supplementary Preview Alert
    if (supplementaryPreview != null) {
      item {
        Card(
          colors = CardDefaults.cardColors(containerColor = StatusWarningBg),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              text = "Supplementary File Import Preview (${supplementaryPreview.targetFieldId})",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = StatusWarningFg
            )
            Text(
              text = "Matched by Asset ID: ${supplementaryPreview.matchedValuesByAssetId.size} records. " +
                "Unmatched in master: ${supplementaryPreview.unmatchedAssetIdsInMaster.size}. " +
                "Duplicates: ${supplementaryPreview.duplicateKeysInSupplementary.size}.",
              style = MaterialTheme.typography.bodySmall,
              color = StatusWarningFg
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(
                onClick = onApplySupplementary,
                enabled = supplementaryPreview.isReadyToApply,
                modifier = Modifier.testTag("apply_supplementary_button")
              ) {
                Text("Apply Supplementary Values")
              }
            }
          }
        }
      }
    }

    // 3. Heading-by-Heading Wizard
    if (draftFields.isNotEmpty()) {
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "2. Field Configuration Wizard (${draftFields.size} fields)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
          )
          Button(
            onClick = { showPublishConfirmDialog = true },
            enabled = isEffectiveAdmin,
            modifier = Modifier.testTag("publish_schema_button")
          ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Publish Configuration")
          }
        }
      }

      items(draftFields, key = { it.fieldId }) { field ->
        FieldConfigCard(
          field = field,
          onUpdateField = onUpdateFieldConfig,
          onOpenSupplementary = { showSupplementaryDialogForField = field }
        )
      }
    }

    // 4. Schema Version History & Rollback
    item {
      ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = "3. Schema Version History & Rollback",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Strict versioning: existing drafts and submissions retain their original schemaVersion. Rollback restores previous configurations safely.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          if (schemaVersions.isEmpty()) {
            Text("No custom schema versions published yet (System Default v1 active).", style = MaterialTheme.typography.bodySmall)
          } else {
            schemaVersions.forEach { ver ->
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text("Version ${ver.versionNumber} (${ver.configMode})", fontWeight = FontWeight.Bold)
                    Text("Published by ${ver.publishedByName} at ${ver.publishedAtIso.take(19)}", style = MaterialTheme.typography.bodySmall)
                    Text(ver.changeSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                  OutlinedButton(
                    onClick = { onRollbackSchema(ver.versionNumber) },
                    enabled = isEffectiveAdmin,
                    modifier = Modifier.testTag("rollback_schema_v${ver.versionNumber}")
                  ) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Rollback")
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  // Publish Dialog
  if (showPublishConfirmDialog) {
    AlertDialog(
      onDismissRequest = { showPublishConfirmDialog = false },
      title = { Text("Publish Master Excel Configuration") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("This publishes a new schema version for all active user counting forms. All Fixed master values and Automatic formulas will be validated before publishing.")
          OutlinedTextField(
            value = publishSummaryInput,
            onValueChange = { publishSummaryInput = it },
            label = { Text("Change Summary / Release Notes") },
            modifier = Modifier.fillMaxWidth()
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            showPublishConfirmDialog = false
            onPublishSchema(publishSummaryInput.trim().ifBlank { "Published Master Excel Configuration" })
          },
          modifier = Modifier.testTag("confirm_publish_schema_button")
        ) {
          Text("Confirm & Publish")
        }
      },
      dismissButton = {
        TextButton(onClick = { showPublishConfirmDialog = false }) { Text("Cancel") }
      }
    )
  }

  // Supplementary Upload Dialog
  showSupplementaryDialogForField?.let { field ->
    AlertDialog(
      onDismissRequest = { showSupplementaryDialogForField = null },
      title = { Text("Resolve Missing Master Data: ${field.displayName}") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            DynamicSchemaEngine.MISSING_FIXED_DATA_MESSAGE,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
          )
          Text(
            "Upload a supplementary CSV or Excel file containing 'Asset ID' and the replacement column heading.",
            style = MaterialTheme.typography.bodySmall
          )
          Button(
            onClick = {
              supplementaryFilePicker.launch(arrayOf("text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "*/*"))
            },
            modifier = Modifier.testTag("pick_supplementary_file_button")
          ) {
            Icon(Icons.Default.FileUpload, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Select CSV / Excel File")
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showSupplementaryDialogForField = null }) { Text("Close") }
      }
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldConfigCard(
  field: SchemaFieldConfig,
  onUpdateField: (SchemaFieldConfig) -> Unit,
  onOpenSupplementary: () -> Unit
) {
  var isExpanded by remember { mutableStateOf(false) }
  var newOptionInput by remember { mutableStateOf("") }

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = if (field.sourceHasMissingValues) StatusWarningBg.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
    ),
    border = if (field.sourceHasMissingValues) CardDefaults.outlinedCardBorder().copy(width = 1.dp) else null
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = field.displayName.ifBlank { field.originalExcelHeading },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Original Heading: '${field.originalExcelHeading}' • ID: ${field.fieldId}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          AssistChip(
            onClick = { isExpanded = !isExpanded },
            label = { Text(field.behavior) }
          )
          IconButton(onClick = { isExpanded = !isExpanded }) {
            Icon(Icons.Default.Tune, contentDescription = "Configure Field")
          }
        }
      }

      // Missing Fixed Data Warning
      if (field.behavior == FieldBehaviorType.FIXED_MASTER_DATA && field.sourceHasMissingValues) {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = StatusWarningBg,
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              DynamicSchemaEngine.MISSING_FIXED_DATA_MESSAGE,
              style = MaterialTheme.typography.bodySmall,
              color = StatusWarningFg
            )
            OutlinedButton(
              onClick = onOpenSupplementary,
              modifier = Modifier.testTag("resolve_fixed_field_${field.fieldId}")
            ) {
              Text("Upload Supplementary File")
            }
          }
        }
      }

      // Unresolved Automatic Warning
      if (field.behavior == FieldBehaviorType.AUTOMATIC_SYSTEM &&
        (field.automationRule.isBlank() || field.automationRule == AutomationRuleType.NONE || field.automationRule == AutomationRuleType.UNRESOLVED)
      ) {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = StatusExceptionBg,
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = DynamicSchemaEngine.UNRESOLVED_AUTOMATIC_FIELD_MESSAGE,
            style = MaterialTheme.typography.bodySmall,
            color = StatusExceptionFg,
            modifier = Modifier.padding(10.dp)
          )
        }
      }

      if (isExpanded) {
        HorizontalDivider()

        OutlinedTextField(
          value = field.displayName,
          onValueChange = { onUpdateField(field.copy(displayName = it)) },
          label = { Text("Display Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )

        // Behavior Selection
        Text("Field Behaviour:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          FieldBehaviorType.ALL_BEHAVIORS.forEach { beh ->
            FilterChip(
              selected = field.behavior == beh,
              onClick = { onUpdateField(field.copy(behavior = beh)) },
              label = { Text(beh) }
            )
          }
        }

        // Input Type Selection (for User Input)
        if (field.behavior == FieldBehaviorType.USER_INPUT) {
          Text("Input Type:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldInputType.ALL_INPUT_TYPES.take(7).forEach { itype ->
              FilterChip(
                selected = field.inputType == itype,
                onClick = { onUpdateField(field.copy(inputType = itype)) },
                label = { Text(itype) }
              )
            }
          }
        }

        // Automation Rule Selection (for Automatic)
        if (field.behavior == FieldBehaviorType.AUTOMATIC_SYSTEM) {
          Text("Automation Rule:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
              AutomationRuleType.QTY_DIFF_FORMULA,
              AutomationRuleType.VERIFICATION_RESULT_FORMULA,
              AutomationRuleType.AUTHENTICATED_USER_NAME,
              AutomationRuleType.CURRENT_COUNT_DATE,
              AutomationRuleType.CURRENT_TIMESTAMP,
              AutomationRuleType.SUPPLEMENTARY_FILE_LOOKUP
            ).forEach { rule ->
              FilterChip(
                selected = field.automationRule == rule,
                onClick = { onUpdateField(field.copy(automationRule = rule)) },
                label = { Text(rule.take(24)) }
              )
            }
          }
        }

        // Dropdown Option Manager
        if (field.behavior == FieldBehaviorType.DROPDOWN) {
          Text("Dropdown Options (${field.dropdownOptions.size}):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
          field.dropdownOptions.forEachIndexed { idx, opt ->
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text("• $opt", modifier = Modifier.weight(1f))
              Row {
                if (idx > 0) {
                  IconButton(onClick = { onUpdateField(DynamicSchemaEngine.reorderDropdownOptions(field, idx, idx - 1)) }) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up", modifier = Modifier.size(16.dp))
                  }
                }
                if (idx < field.dropdownOptions.size - 1) {
                  IconButton(onClick = { onUpdateField(DynamicSchemaEngine.reorderDropdownOptions(field, idx, idx + 1)) }) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down", modifier = Modifier.size(16.dp))
                  }
                }
                IconButton(onClick = { onUpdateField(DynamicSchemaEngine.deleteDropdownOption(field, opt)) }) {
                  Icon(Icons.Default.Delete, contentDescription = "Delete Option", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                }
              }
            }
          }

          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = newOptionInput,
              onValueChange = { newOptionInput = it },
              label = { Text("Add Dropdown Option") },
              singleLine = true,
              modifier = Modifier.weight(1f)
            )
            Button(
              onClick = {
                if (newOptionInput.isNotBlank()) {
                  onUpdateField(DynamicSchemaEngine.addDropdownOption(field, newOptionInput))
                  newOptionInput = ""
                }
              }
            ) {
              Icon(Icons.Default.Add, contentDescription = null)
            }
          }

          Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
              checked = field.allowOtherOption,
              onCheckedChange = { onUpdateField(field.copy(allowOtherOption = it)) }
            )
            Spacer(Modifier.width(8.dp))
            Text("Allow 'Other' Option with Custom Text Entry", style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }
  }
}

// ============================================================================
// PART A: ADMIN SUBMISSION APPROVAL TAB (LAYER C)
// ============================================================================

@Composable
fun AdminSubmissionApprovalTab(
  isEffectiveAdmin: Boolean,
  canPublishToMaster: Boolean,
  canSyncDrive: Boolean,
  submissions: List<UserSubmissionRecord>,
  onRefreshSubmissions: () -> Unit,
  onApprove: (UserSubmissionRecord, reviewNotes: String) -> Unit,
  onReject: (UserSubmissionRecord, rejectionReason: String) -> Unit,
  onRequestChanges: (UserSubmissionRecord, notes: String) -> Unit,
  onPublishApprovedToMaster: (simulateFailure: Boolean) -> Unit
) {
  var filterStatus by remember { mutableStateOf("All") }
  var activeReviewSubmission by remember { mutableStateOf<UserSubmissionRecord?>(null) }
  var reviewDialogMode by remember { mutableStateOf<String?>(null) } // "APPROVE", "REJECT", "CHANGES"
  var reviewInputNotes by remember { mutableStateOf("") }

  val filteredSubmissions = remember(submissions, filterStatus) {
    if (filterStatus == "All") submissions
    else submissions.filter { it.submissionStatus == filterStatus }
  }

  val pendingApprovalCount = remember(submissions) {
    submissions.count {
      it.submissionStatus == SubmissionLifecycleStatus.APPROVED_AWAITING_MASTER_UPDATE ||
        it.submissionStatus == SubmissionLifecycleStatus.SYNC_FAILED ||
        it.submissionStatus == SubmissionLifecycleStatus.MASTER_UPDATE_IN_PROGRESS
    }
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text(
                text = "Controlled Master Workbook Publishing Queue",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = "$pendingApprovalCount submission(s) ready to update the Master Excel Workbook.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              IconButton(onClick = onRefreshSubmissions) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh Queue")
              }
              Button(
                onClick = { onPublishApprovedToMaster(false) },
                enabled = canPublishToMaster && canSyncDrive && pendingApprovalCount > 0,
                modifier = Modifier.testTag("publish_approved_to_master_button")
              ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Publish Approved")
              }
            }
          }

          // Filter chips
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("All", "Pending Review", "Approved — Awaiting Master Update", "Published", "Changes Requested", "Rejected", "Sync Failed").forEach { st ->
              FilterChip(
                selected = filterStatus == st,
                onClick = { filterStatus = st },
                label = { Text(st.take(18)) }
              )
            }
          }
        }
      }
    }

    if (filteredSubmissions.isEmpty()) {
      item {
        Card(modifier = Modifier.fillMaxWidth()) {
          Text("No submissions matching '$filterStatus'.", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
      }
    } else {
      items(filteredSubmissions, key = { it.submissionId }) { sub ->
        SubmissionItemCard(
          sub = sub,
          isEffectiveAdmin = isEffectiveAdmin,
          onOpenApprove = {
            activeReviewSubmission = sub
            reviewDialogMode = "APPROVE"
            reviewInputNotes = "Approved by Admin"
          },
          onOpenReject = {
            activeReviewSubmission = sub
            reviewDialogMode = "REJECT"
            reviewInputNotes = "Physical tag unreadable — re-verify serial"
          },
          onOpenChanges = {
            activeReviewSubmission = sub
            reviewDialogMode = "CHANGES"
            reviewInputNotes = "Please capture a clearer photo of the nameplate."
          }
        )
      }
    }
  }

  // Review Dialog
  activeReviewSubmission?.let { sub ->
    reviewDialogMode?.let { mode ->
      AlertDialog(
        onDismissRequest = {
          activeReviewSubmission = null
          reviewDialogMode = null
        },
        title = {
          Text(
            when (mode) {
              "APPROVE" -> "Approve Submission (${sub.assetId})"
              "REJECT" -> "Reject Submission (${sub.assetId})"
              else -> "Request Changes (${sub.assetId})"
            }
          )
        },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Auditor: ${sub.submitterName} (UID: ${sub.submitterUid.take(8)}...) • Schema v${sub.schemaVersionNumber}")
            OutlinedTextField(
              value = reviewInputNotes,
              onValueChange = { reviewInputNotes = it },
              label = { Text(if (mode == "REJECT") "Rejection Reason *" else "Review Notes") },
              modifier = Modifier.fillMaxWidth()
            )
          }
        },
        confirmButton = {
          Button(
            onClick = {
              when (mode) {
                "APPROVE" -> onApprove(sub, reviewInputNotes.trim())
                "REJECT" -> onReject(sub, reviewInputNotes.trim().ifBlank { "Rejected by Admin" })
                "CHANGES" -> onRequestChanges(sub, reviewInputNotes.trim().ifBlank { "Changes Requested" })
              }
              activeReviewSubmission = null
              reviewDialogMode = null
            },
            modifier = Modifier.testTag("confirm_review_action_button")
          ) {
            Text("Confirm")
          }
        },
        dismissButton = {
          TextButton(onClick = {
            activeReviewSubmission = null
            reviewDialogMode = null
          }) { Text("Cancel") }
        }
      )
    }
  }
}

@Composable
private fun SubmissionItemCard(
  sub: UserSubmissionRecord,
  isEffectiveAdmin: Boolean,
  onOpenApprove: () -> Unit,
  onOpenReject: () -> Unit,
  onOpenChanges: () -> Unit
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text("Asset ID: ${sub.assetId} (Row ${sub.sourceRow})", fontWeight = FontWeight.Bold)
          Text("Submitter: ${sub.submitterName} (${sub.nicknameSnapshot})", style = MaterialTheme.typography.bodySmall)
        }
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = when (sub.submissionStatus) {
            "Published" -> StatusVerifiedBg
            "Approved — Awaiting Master Update" -> AuditGoldLight.copy(alpha = 0.25f)
            "Rejected" -> StatusExceptionBg
            "Changes Requested" -> StatusWarningBg
            else -> MaterialTheme.colorScheme.surfaceVariant
          }
        ) {
          Text(
            text = sub.submissionStatus,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = when (sub.submissionStatus) {
              "Published" -> StatusVerifiedFg
              "Rejected" -> StatusExceptionFg
              "Changes Requested" -> StatusWarningFg
              else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
          )
        }
      }

      if (sub.reviewNotes.isNotBlank()) {
        Text("Reviewer Notes: ${sub.reviewNotes}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
      }
      if (sub.rejectionReason.isNotBlank()) {
        Text("Rejection Reason: ${sub.rejectionReason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }
      if (sub.syncErrorMessage.isNotBlank()) {
        Text("Sync Error: ${sub.syncErrorMessage}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }

      if (isEffectiveAdmin && (sub.submissionStatus == "Pending Review" || sub.submissionStatus == "Changes Requested" || sub.submissionStatus == "Sync Failed")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = onOpenApprove, modifier = Modifier.testTag("approve_submission_${sub.submissionId}")) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Approve")
          }
          OutlinedButton(onClick = onOpenChanges, modifier = Modifier.testTag("changes_submission_${sub.submissionId}")) {
            Text("Request Changes")
          }
          OutlinedButton(
            onClick = onOpenReject,
            modifier = Modifier.testTag("reject_submission_${sub.submissionId}"),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
          ) {
            Text("Reject")
          }
        }
      }
    }
  }
}

// ============================================================================
// PART B: ADMIN GOOGLE DRIVE & PHOTO MANAGEMENT TABS
// ============================================================================

@Composable
fun AdminGoogleDriveConnectionTab(
  canSyncDrive: Boolean,
  projectSettings: ProjectSettings,
  userProfile: UserProfile,
  assetPhotos: List<AssetPhotoRecord>,
  exportJobs: List<ExportJobRecord>,
  queuedOfflinePhotos: List<com.example.data.offline.OfflinePhotoQueueEntity>,
  isDriveAuthorizationReady: Boolean,
  driveErrorMessage: String,
  driveSessionAccountEmail: String,
  onAuthorizeDrive: () -> Unit,
  onDisconnectDrive: () -> Unit,
  onTestUpload: () -> Unit
) {
  val pendingPhotoIds = (
    assetPhotos.filter { it.uploadStatus != SyncState.SYNCED && it.uploadStatus != SyncState.UPLOADED }
      .map { it.photoId.ifBlank { it.id } } +
      queuedOfflinePhotos.filter { it.uploadStatus != SyncState.SYNCED && it.uploadStatus != SyncState.UPLOADED }
        .map { it.photoId }
    ).filter(String::isNotBlank).toSet()
  val driveJobs = exportJobs.filter { it.jobType == "MASTER_WORKBOOK_DRIVE_SYNC" }
  val pendingJobs = driveJobs.filter {
    it.status.equals("Pending", true) || it.status.equals("Queued", true) || it.status.equals("Running", true)
  }
  val lastSuccessfulSync = sequenceOf(
    projectSettings.lastMasterUpdateAtIso.takeIf { projectSettings.lastMasterUpdateStatus.startsWith("Synced") },
    assetPhotos.map { it.uploadedAtIso }.filter(String::isNotBlank).maxOrNull(),
    driveJobs.filter { it.status.equals("Completed", true) }
      .map { it.completedAtIso }.filter(String::isNotBlank).maxOrNull()
  ).filterNotNull().maxOrNull().orEmpty().ifBlank { "Never" }
  val driveErrors = (listOf(driveErrorMessage, projectSettings.lastMasterUpdateError) +
    assetPhotos.map { it.errorMessage } + queuedOfflinePhotos.map { it.errorMessage } +
    driveJobs.map { it.errorMessage }).filter(String::isNotBlank).distinct().take(3)

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    item {
      ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(
            text = "Google Drive Connection Status",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
          )

          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              val displayedAccount = if (isDriveAuthorizationReady) driveSessionAccountEmail else projectSettings.connectedDriveEmail
              Text("Connected Account: ${displayedAccount.ifBlank { "Not Connected" }}", fontWeight = FontWeight.SemiBold)
              Text("Status: ${projectSettings.driveConnectionStatus}", style = MaterialTheme.typography.bodySmall)
              Text(
                "Authorization: ${if (isDriveAuthorizationReady) "Active" else if (projectSettings.connectedDriveEmail.isNotBlank()) "Reconnect required" else "Not authorized"}",
                style = MaterialTheme.typography.bodySmall
              )
              Text("Last successful Drive sync: $lastSuccessfulSync", style = MaterialTheme.typography.bodySmall)
              Text("Pending operations: ${pendingPhotoIds.size} photo(s), ${pendingJobs.size} workbook/export job(s)", style = MaterialTheme.typography.bodySmall)
              Text("Destination Parent Folder: Fixed Asset Photos/", style = MaterialTheme.typography.bodySmall)
              Text("Organization Shared Date Folder: Fixed Asset Photos/{YYYY-MM-DD}/", style = MaterialTheme.typography.bodySmall)
              Text("Admin Nickname for Test: ${userProfile.effectiveNickname()}", style = MaterialTheme.typography.bodySmall)
              driveErrors.forEach { error ->
                Text("Drive error: $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
              }
            }
          }

          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
              onClick = onAuthorizeDrive,
              enabled = canSyncDrive,
              modifier = Modifier.testTag("admin_drive_authorize_button")
            ) {
              Icon(Icons.Default.CloudSync, contentDescription = null)
              Spacer(Modifier.width(6.dp))
              Text(if (projectSettings.connectedDriveEmail.isBlank()) "Connect Google Drive" else "Switch Google Account")
            }
            OutlinedButton(
              onClick = onTestUpload,
              enabled = canSyncDrive && isDriveAuthorizationReady,
              modifier = Modifier.testTag("admin_drive_test_upload_button")
            ) {
              Icon(Icons.Default.FileUpload, contentDescription = null)
              Spacer(Modifier.width(6.dp))
              Text("Test Drive Upload")
            }
            if (projectSettings.connectedDriveEmail.isNotBlank()) {
              OutlinedButton(
                onClick = onDisconnectDrive,
                enabled = canSyncDrive,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
              ) {
                Text("Disconnect")
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun AdminPhotoManagementTab(
  assetPhotos: List<AssetPhotoRecord>,
  onRetryUpload: (photoId: String) -> Unit
) {
  var selectedDateFolder by remember { mutableStateOf("All") }
  var searchQuery by remember { mutableStateOf("") }
  var statusFilter by remember { mutableStateOf("All") }

  val dateFolders = remember(assetPhotos) {
    listOf("All") + assetPhotos.map { it.dateFolderName }.filter { it.isNotBlank() }.distinct().sortedDescending()
  }

  val filteredPhotos = remember(assetPhotos, selectedDateFolder, searchQuery, statusFilter) {
    assetPhotos.filter { photo ->
      val matchesFolder = selectedDateFolder == "All" || photo.dateFolderName == selectedDateFolder
      val matchesStatus = statusFilter == "All" || photo.uploadStatus == statusFilter
      val q = searchQuery.trim()
      val matchesSearch = q.isEmpty() ||
        photo.assetOrObsId.contains(q, ignoreCase = true) ||
        photo.finalDriveFilename.contains(q, ignoreCase = true) ||
        photo.nicknameSnapshot.contains(q, ignoreCase = true)
      matchesFolder && matchesStatus && matchesSearch
    }
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Photo Evidence Management (${assetPhotos.size} total)", fontWeight = FontWeight.Bold)

          OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search by Asset ID or Filename") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )

          // Date folder filters
          Text("Date Folders:", style = MaterialTheme.typography.labelSmall)
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            dateFolders.take(6).forEach { df ->
              FilterChip(
                selected = selectedDateFolder == df,
                onClick = { selectedDateFolder = df },
                label = { Text(df) }
              )
            }
          }
        }
      }
    }

    if (filteredPhotos.isEmpty()) {
      item {
        Card(modifier = Modifier.fillMaxWidth()) {
          Text("No photos matching filters.", modifier = Modifier.padding(16.dp))
        }
      }
    } else {
      items(filteredPhotos, key = { it.photoId.ifBlank { it.id } }) { p ->
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = p.finalDriveFilename.ifBlank { p.originalLocalFilename.ifBlank { p.photoId } },
                fontWeight = FontWeight.Bold
              )
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (p.uploadStatus == SyncState.UPLOADED || p.uploadStatus == SyncState.SYNCED) StatusVerifiedBg else StatusWarningBg
              ) {
                Text(
                  p.uploadStatus,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  style = MaterialTheme.typography.labelSmall,
                  color = if (p.uploadStatus == SyncState.UPLOADED || p.uploadStatus == SyncState.SYNCED) StatusVerifiedFg else StatusWarningFg
                )
              }
            }
            Text("Asset ID: ${p.assetOrObsId} • Folder: Fixed Asset Photos/${p.dateFolderName}/", style = MaterialTheme.typography.bodySmall)
            Text("Nickname: ${p.nicknameSnapshot} • Captured by ${p.capturedBy} at ${p.capturedAtIso.take(19)}", style = MaterialTheme.typography.bodySmall)
            if (p.driveFileId.isNotBlank()) {
              Text("Google Drive File ID: ${p.driveFileId}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
            if (p.errorMessage.isNotBlank()) {
              Text("Error: ${p.errorMessage}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
              OutlinedButton(onClick = { onRetryUpload(p.photoId) }) { Text("Retry Upload") }
            }
          }
        }
      }
    }
  }
}
