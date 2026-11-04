package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AssignmentInd
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.AdminNavTab
import com.example.data.excel.ExcelWorkbookEngine
import com.example.data.model.AccessRequestRecord
import com.example.data.model.ActivityLogRecord
import com.example.data.model.AssetPhotoRecord
import com.example.data.model.AssetWithCount
import com.example.data.model.ClientAssignmentRecord
import com.example.data.model.ClientRecord
import com.example.data.model.DailyBatchSubmissionRecord
import com.example.data.model.DeletionAuditEvent
import com.example.data.model.DeletionImpactSummary
import com.example.data.model.DeletionJobRecord
import com.example.data.model.ExcelColumnMappingConfig
import com.example.data.model.ExportJobRecord
import com.example.data.model.FoundNotInFarRecord
import com.example.data.model.MasterWorkbookSyncMetadata
import com.example.data.model.ProjectSettings
import com.example.data.model.RoleAssignment
import com.example.data.model.SubmissionBatchStatus
import com.example.data.model.SyncState
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.data.model.UserSubmissionRecord
import com.example.data.offline.OfflinePhotoQueueEntity
import com.example.data.offline.OfflineQueuedRecordEntity
import com.example.data.schema.DetectedWorkbookPreview
import com.example.data.schema.SchemaFieldConfig
import com.example.data.schema.SupplementaryImportPreview
import com.example.data.schema.WorkbookSchemaVersion
import com.example.ui.screens.admin.AdminBatchDetailTab
import com.example.ui.screens.admin.AdminReviewQueueTab
import com.example.ui.viewmodel.AdminDashboardViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminControlPanelScreen(
  userProfile: UserProfile,
  isEffectiveOwner: Boolean = false,
  isEffectiveAdmin: Boolean,
  hasReviewAuthority: Boolean = false,
  canPublishToMaster: Boolean = false,
  canSyncDrive: Boolean = false,
  selectedTab: AdminNavTab,
  onNavigateToAdminTab: (AdminNavTab) -> Unit = {},
  activeClient: ClientRecord? = null,
  activeFiscalYearId: String = "2026_27",
  clients: List<ClientRecord> = emptyList(),
  clientAssignments: List<ClientAssignmentRecord> = emptyList(),
  accessRequests: List<AccessRequestRecord> = emptyList(),
  assetRegisterValidation: ExcelWorkbookEngine.ImportValidationSummary? = null,
  masterWorkbookSyncMeta: MasterWorkbookSyncMetadata? = null,
  deletionImpactPreview: DeletionImpactSummary? = null,
  deletionJobs: List<DeletionJobRecord> = emptyList(),
  deletionAuditEvents: List<DeletionAuditEvent> = emptyList(),
  assetsWithCounts: List<AssetWithCount>,
  foundNotInFar: List<FoundNotInFarRecord>,
  projectSettings: ProjectSettings,
  roleAssignments: List<RoleAssignment>,
  assetPhotos: List<AssetPhotoRecord>,
  activityLogs: List<ActivityLogRecord>,
  exportJobs: List<ExportJobRecord>,
  queuedOfflineRecords: List<OfflineQueuedRecordEntity>,
  queuedOfflinePhotos: List<OfflinePhotoQueueEntity>,
  isOfflineSimulationEnabled: Boolean,
  onToggleOfflineSimulation: (Boolean) -> Unit,
  onSyncAllOfflineNow: () -> Unit,
  onRetryPhotoUpload: (String) -> Unit,
  onSaveProjectSettings: (ProjectSettings, String) -> Unit,
  onAuthorizeGoogleDrive: () -> Unit,
  onProvisionDriveFolders: () -> Unit,
  onSyncMasterWorkbookToDrive: () -> Unit,
  onDownloadMasterInputSheet: () -> Unit = {},
  onSaveRoleAssignment: (RoleAssignment) -> Unit,
  detectedPreview: DetectedWorkbookPreview? = null,
  draftFields: List<SchemaFieldConfig> = emptyList(),
  schemaVersions: List<WorkbookSchemaVersion> = emptyList(),
  supplementaryPreview: SupplementaryImportPreview? = null,
  teamSubmissions: List<UserSubmissionRecord> = emptyList(),
  dailyBatches: List<DailyBatchSubmissionRecord> = emptyList(),
  columnMapping: ExcelColumnMappingConfig = ExcelColumnMappingConfig(),
  detectedDropdownOptions: Map<String, List<String>> = emptyMap(),
  onImportAssetRegisterToFirebase: (ByteArray, String, String, Boolean) -> Unit = { _, _, _, _ -> },
  onUploadMasterInputWorkbook: (ByteArray, String, String, Int, String) -> Unit = { _, _, _, _, _ -> },
  onInspectWorkbook: (ByteArray, String) -> Unit = { _, _ -> },
  onSelectConfigMode: (String, String) -> Unit = { _, _ -> },
  onUpdateFieldConfig: (SchemaFieldConfig) -> Unit = {},
  onImportSupplementary: (ByteArray, String, String, String) -> Unit = { _, _, _, _ -> },
  onApplySupplementary: () -> Unit = {},
  onPublishSchema: (String) -> Unit = {},
  onRollbackSchema: (Long) -> Unit = {},
  onRefreshSubmissions: () -> Unit = {},
  onApproveSubmission: (UserSubmissionRecord, String) -> Unit = { _, _ -> },
  onRejectSubmission: (UserSubmissionRecord, String) -> Unit = { _, _ -> },
  onRequestChangesOnSubmission: (UserSubmissionRecord, String) -> Unit = { _, _ -> },
  onPublishApprovedToMaster: (Boolean) -> Unit = {},
  onDisconnectGoogleDrive: () -> Unit = {},
  onTestDriveUpload: () -> Unit = {},
  onSendBatchRemarks: (DailyBatchSubmissionRecord, String) -> Unit = { _, _ -> },
  onApproveBatchOnly: (DailyBatchSubmissionRecord) -> Unit = {},
  onApproveAndPublishBatch: (DailyBatchSubmissionRecord) -> Unit = {},
  onDownloadDailyBatchExcel: (DailyBatchSubmissionRecord) -> Unit = {},
  onSaveColumnMapping: (ExcelColumnMappingConfig) -> Unit = {},
  onUpdateUserPermissions: (String, Boolean, Boolean, Boolean) -> Unit = { _, _, _, _ -> },
  onSelectClientWorkspace: (clientId: String, fiscalYearId: String) -> Unit = { _, _ -> },
  onCreateClient: (String, String, String, String, String, String, String, Boolean) -> Unit = { _, _, _, _, _, _, _, _ -> },
  onArchiveOrRestoreClient: (clientId: String, archive: Boolean) -> Unit = { _, _ -> },
  onAssignClientToAuditor: (clientId: String, clientName: String, fiscalYearIds: List<String>, auditorUid: String, auditorEmail: String, auditorName: String) -> Unit = { _, _, _, _, _, _ -> },
  onUnassignClientFromAuditor: (assignmentId: String, clientId: String, auditorUid: String) -> Unit = { _, _, _ -> },
  onReviewAccessRequest: (request: AccessRequestRecord, approve: Boolean, targetClientId: String, targetClientName: String, note: String) -> Unit = { _, _, _, _, _ -> },
  onPreviewClientDeletion: (clientId: String) -> Unit = {},
  onExecutePermanentClientDeletion: (clientId: String, confirmationText: String, includeDriveFolder: Boolean) -> Unit = { _, _, _ -> },
  onPreviewUserDeletion: (targetUid: String) -> Unit = {},
  onExecutePermanentUserDeletion: (targetUid: String, confirmationText: String) -> Unit = { _, _ -> },
  onClearDeletionPreview: () -> Unit = {},
  onSignOut: () -> Unit = {},
  isDriveAuthorizationReady: Boolean = false,
  driveErrorMessage: String = "",
  driveSessionAccountEmail: String = "",
  dashboardViewModel: AdminDashboardViewModel? = null
) {
  var selectedBatchForDetail by remember { mutableStateOf<DailyBatchSubmissionRecord?>(null) }
  var selectedReviewSection by rememberSaveable { mutableIntStateOf(0) }
  var selectedControlSubTab by rememberSaveable { mutableIntStateOf(0) }

  Column(modifier = Modifier.fillMaxSize()) {
    // Top Role, Client Scope & Offline Status Banner
    Surface(
      color = if (isEffectiveAdmin) {
        MaterialTheme.colorScheme.primaryContainer
      } else {
        MaterialTheme.colorScheme.errorContainer
      },
      modifier = Modifier.fillMaxWidth()
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.weight(1f)
        ) {
          Icon(
            Icons.Default.AdminPanelSettings,
            contentDescription = null
          )
          Column {
            Text(
              text = buildString {
                append(if (isEffectiveOwner) "Owner" else "Admin")
                append(" Workspace — ")
                append(activeClient?.clientName ?: "No Client Selected")
                append(" (FY ${activeFiscalYearId.replace("_", "–")})")
              },
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "Offline Queue: ${queuedOfflineRecords.count { it.syncStatus != SyncState.SYNCED }} record(s), ${queuedOfflinePhotos.count { it.uploadStatus != SyncState.SYNCED }} photo(s) pending",
              style = MaterialTheme.typography.bodySmall
            )
          }
        }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Text("Offline", style = MaterialTheme.typography.labelSmall)
          Switch(
            checked = isOfflineSimulationEnabled,
            onCheckedChange = onToggleOfflineSimulation,
            modifier = Modifier.testTag("offline_mode_switch")
          )
        }
      }
    }

    when (selectedTab) {
      AdminNavTab.HOME -> {
        AdminHomeOverviewTab(
          activeClient = activeClient,
          activeFiscalYearId = activeFiscalYearId,
          roleAssignments = roleAssignments,
          assetsWithCounts = assetsWithCounts,
          dailyBatches = dailyBatches,
          activityLogs = activityLogs,
          dashboardViewModel = dashboardViewModel,
          onNavigateToAdminTab = onNavigateToAdminTab
        )
      }

      AdminNavTab.RESOURCES -> {
        ResourcesScreen(
          isEffectiveAdmin = isEffectiveAdmin,
          activeClient = activeClient,
          activeFiscalYearId = activeFiscalYearId,
          assetRegisterValidation = assetRegisterValidation,
          masterWorkbookSyncMeta = masterWorkbookSyncMeta,
          detectedPreview = detectedPreview,
          draftFields = draftFields,
          schemaVersions = schemaVersions,
          supplementaryPreview = supplementaryPreview,
          columnMapping = columnMapping,
          detectedDropdownOptions = detectedDropdownOptions,
          onImportAssetRegisterToFirebase = onImportAssetRegisterToFirebase,
          onUploadMasterInputWorkbook = onUploadMasterInputWorkbook,
          onDownloadMasterInputWorkbook = onDownloadMasterInputSheet,
          onInspectWorkbook = onInspectWorkbook,
          onSelectConfigMode = onSelectConfigMode,
          onUpdateFieldConfig = onUpdateFieldConfig,
          onImportSupplementary = onImportSupplementary,
          onApplySupplementary = onApplySupplementary,
          onPublishSchema = onPublishSchema,
          onRollbackSchema = onRollbackSchema,
          onSaveColumnMapping = onSaveColumnMapping
        )
      }

      AdminNavTab.REVIEW -> {
        if (selectedBatchForDetail != null) {
          AdminBatchDetailTab(
            batch = selectedBatchForDetail,
            allAssets = assetsWithCounts,
            foundNotInFar = foundNotInFar,
            onBackToQueue = { selectedBatchForDetail = null }
          )
        } else {
          Column(modifier = Modifier.fillMaxSize()) {
            val reviewSections = listOf("Daily Sheet Review", "Dynamic Submissions")
            ScrollableTabRow(selectedTabIndex = selectedReviewSection) {
              reviewSections.forEachIndexed { index, label ->
                Tab(
                  selected = selectedReviewSection == index,
                  onClick = { selectedReviewSection = index },
                  text = { Text(label) },
                  modifier = Modifier.testTag("review_section_$index")
                )
              }
            }
            when (selectedReviewSection) {
              0 -> AdminReviewQueueTab(
                userProfile = userProfile,
                canReview = hasReviewAuthority,
                canPublishToMaster = canPublishToMaster,
                dailyBatches = dailyBatches,
                onReviewBatch = { batch -> selectedBatchForDetail = batch },
                onSendRemarks = onSendBatchRemarks,
                onApproveBatchOnly = onApproveBatchOnly,
                onApproveAndPublish = onApproveAndPublishBatch,
                onDownloadDailySheetExcel = onDownloadDailyBatchExcel,
                onRefresh = onRefreshSubmissions
              )

              else -> AdminSubmissionApprovalTab(
                isEffectiveAdmin = hasReviewAuthority,
                canPublishToMaster = canPublishToMaster,
                canSyncDrive = canSyncDrive,
                submissions = teamSubmissions,
                onRefreshSubmissions = onRefreshSubmissions,
                onApprove = onApproveSubmission,
                onReject = onRejectSubmission,
                onRequestChanges = onRequestChangesOnSubmission,
                onPublishApprovedToMaster = onPublishApprovedToMaster
              )
            }
          }
        }
      }

      AdminNavTab.CONTROL_PANEL -> {
        val controlSubTabs = listOf(
          "1. Client Management",
          "2. User & Role Management",
          "3. Client Assignment",
          "4. Access Requests (${accessRequests.count { it.status == "Pending" }})",
          "5. Google Drive & Settings"
        )
        Column(modifier = Modifier.fillMaxSize()) {
          ScrollableTabRow(selectedTabIndex = selectedControlSubTab) {
            controlSubTabs.forEachIndexed { idx, label ->
              Tab(
                selected = selectedControlSubTab == idx,
                onClick = { selectedControlSubTab = idx },
                text = { Text(label) },
                modifier = Modifier.testTag("control_panel_subtab_$idx")
              )
            }
          }

          when (selectedControlSubTab) {
            0 -> AdminClientManagementSubTab(
              isEffectiveAdmin = isEffectiveAdmin,
              activeClient = activeClient,
              clients = clients,
              deletionImpactPreview = deletionImpactPreview,
              onSelectClientWorkspace = onSelectClientWorkspace,
              onCreateClient = onCreateClient,
              onArchiveOrRestoreClient = onArchiveOrRestoreClient,
              onPreviewClientDeletion = onPreviewClientDeletion,
              onExecutePermanentClientDeletion = onExecutePermanentClientDeletion,
              onClearDeletionPreview = onClearDeletionPreview
            )

            1 -> AdminUserManagementTab(
              isEffectiveAdmin = isEffectiveAdmin,
              isEffectiveOwner = isEffectiveOwner,
              currentUserId = userProfile.userId,
              roleAssignments = roleAssignments,
              deletionImpactPreview = deletionImpactPreview,
              onSaveRoleAssignment = onSaveRoleAssignment,
              onUpdateUserPermissions = onUpdateUserPermissions,
              onPreviewUserDeletion = onPreviewUserDeletion,
              onExecutePermanentUserDeletion = onExecutePermanentUserDeletion,
              onClearDeletionPreview = onClearDeletionPreview
            )

            2 -> AdminClientAssignmentSubTab(
              isEffectiveAdmin = isEffectiveAdmin,
              clients = clients,
              roleAssignments = roleAssignments,
              clientAssignments = clientAssignments,
              onAssignClientToAuditor = onAssignClientToAuditor,
              onUnassignClientFromAuditor = onUnassignClientFromAuditor
            )

            3 -> AdminAccessRequestsSubTab(
              isEffectiveAdmin = isEffectiveAdmin,
              clients = clients,
              accessRequests = accessRequests,
              onReviewAccessRequest = onReviewAccessRequest
            )

            else -> AdminDriveAndSettingsSubTab(
              isEffectiveAdmin = isEffectiveAdmin,
              canSyncDrive = canSyncDrive,
              activeClient = activeClient,
              projectSettings = projectSettings,
              userProfile = userProfile,
              assetPhotos = assetPhotos,
              exportJobs = exportJobs,
              queuedOfflineRecords = queuedOfflineRecords,
              queuedOfflinePhotos = queuedOfflinePhotos,
              deletionJobs = deletionJobs,
              deletionAuditEvents = deletionAuditEvents,
              isDriveAuthorizationReady = isDriveAuthorizationReady,
              driveErrorMessage = driveErrorMessage,
              driveSessionAccountEmail = driveSessionAccountEmail,
              onAuthorizeDrive = onAuthorizeGoogleDrive,
              onDisconnectDrive = onDisconnectGoogleDrive,
              onTestUpload = onTestDriveUpload,
              onProvisionFolders = onProvisionDriveFolders,
              onSyncMasterWorkbook = onSyncMasterWorkbookToDrive,
              onSyncAllOfflineNow = onSyncAllOfflineNow,
              onRetryPhotoUpload = onRetryPhotoUpload,
              onSignOut = onSignOut
            )
          }
        }
      }
    }
  }
}

// ============================================================================
// TAB 1: ADMIN HOME OVERVIEW (DASHBOARD, RECENT ACTIVITY, QUICK ACCESS LINKS)
// ============================================================================

@Composable
private fun AdminHomeOverviewTab(
  activeClient: ClientRecord?,
  activeFiscalYearId: String,
  roleAssignments: List<RoleAssignment>,
  assetsWithCounts: List<AssetWithCount>,
  dailyBatches: List<DailyBatchSubmissionRecord>,
  activityLogs: List<ActivityLogRecord>,
  dashboardViewModel: AdminDashboardViewModel?,
  onNavigateToAdminTab: (AdminNavTab) -> Unit
) {
  val totalAuditors = roleAssignments.count { it.role == UserRole.AUDITOR || it.role == UserRole.LEAD_AUDITOR }
  val totalAssets = assetsWithCounts.size
  val pendingSheets = dailyBatches.count { it.status == SubmissionBatchStatus.SUBMITTED || it.status == SubmissionBatchStatus.UNDER_REVIEW }
  val approvedSheets = dailyBatches.count {
    it.status == SubmissionBatchStatus.PUBLISHED ||
      it.status == SubmissionBatchStatus.APPROVED_AWAITING_MASTER_UPDATE ||
      it.approvalStatus == "Approved"
  }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("admin_home_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    item {
      ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(
            text = "Dashboard Overview — ${activeClient?.clientName ?: "Select a Client"}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Fiscal Year: ${activeFiscalYearId.replace("_", "–")} • Register File: ${activeClient?.assetRegisterFileName?.ifBlank { "Not uploaded" } ?: "N/A"} • Master Sheet: ${activeClient?.masterWorkbookFileName?.ifBlank { "Not uploaded" } ?: "N/A"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            MetricSummaryBox("Auditors", totalAuditors.toString(), Modifier.weight(1f))
            MetricSummaryBox("Assets", totalAssets.toString(), Modifier.weight(1f))
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            MetricSummaryBox("Pending Sheets", pendingSheets.toString(), Modifier.weight(1f))
            MetricSummaryBox("Approved Sheets", approvedSheets.toString(), Modifier.weight(1f))
          }
        }
      }
    }

    if (dashboardViewModel != null) {
      item {
        AdminDashboardScreen(viewModel = dashboardViewModel)
      }
    }

    // Quick Access Links
    item {
      ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = "Quick Access Links",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            OutlinedButton(
              onClick = { onNavigateToAdminTab(AdminNavTab.RESOURCES) },
              modifier = Modifier
                .weight(1f)
                .testTag("quick_link_resources")
            ) {
              Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text("Resources & Sheets")
            }
            OutlinedButton(
              onClick = { onNavigateToAdminTab(AdminNavTab.REVIEW) },
              modifier = Modifier
                .weight(1f)
                .testTag("quick_link_review")
            ) {
              Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text("Review Queue ($pendingSheets)")
            }
          }
          OutlinedButton(
            onClick = { onNavigateToAdminTab(AdminNavTab.CONTROL_PANEL) },
            modifier = Modifier
              .fillMaxWidth()
              .testTag("quick_link_control_panel")
          ) {
            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Open Control Panel (Clients, Users, Assignments & Drive)")
          }
        }
      }
    }

    // Recent Activity Log
    item {
      Text(
        text = "Recent Activity Log (${activityLogs.size})",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
      )
    }

    if (activityLogs.isEmpty()) {
      item {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
          Text(
            text = "No activity logs recorded yet.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall
          )
        }
      }
    } else {
      items(activityLogs.take(15), key = { it.id }) { log ->
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(log.actionType, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
              Text(log.timestampIso.take(19), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(log.summary, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }
  }
}

@Composable
private fun MetricSummaryBox(label: String, value: String, modifier: Modifier = Modifier) {
  Card(
    modifier = modifier,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
      Text(label, style = MaterialTheme.typography.labelMedium)
    }
  }
}

// ============================================================================
// CONTROL PANEL SUB-TAB 1: CLIENT MANAGEMENT & PERMANENT CLIENT DELETION
// ============================================================================

@Composable
private fun AdminClientManagementSubTab(
  isEffectiveAdmin: Boolean,
  activeClient: ClientRecord?,
  clients: List<ClientRecord>,
  deletionImpactPreview: DeletionImpactSummary?,
  onSelectClientWorkspace: (String, String) -> Unit,
  onCreateClient: (String, String, String, String, String, String, String, Boolean) -> Unit,
  onArchiveOrRestoreClient: (String, Boolean) -> Unit,
  onPreviewClientDeletion: (String) -> Unit,
  onExecutePermanentClientDeletion: (String, String, Boolean) -> Unit,
  onClearDeletionPreview: () -> Unit
) {
  var showCreateDialog by remember { mutableStateOf(false) }
  var clientToDelete by remember { mutableStateOf<ClientRecord?>(null) }
  var deleteConfirmationText by remember { mutableStateOf("") }
  var includeDriveFolderDeletion by remember { mutableStateOf(false) }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("admin_client_management_subtab"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Client Management (${clients.size})",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "Create new clients, switch active client workspaces, archive inactive clients, or permanently delete a client's data.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          Button(
            onClick = { showCreateDialog = true },
            enabled = isEffectiveAdmin,
            modifier = Modifier.testTag("control_panel_create_client_button")
          ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("New Client")
          }
        }
      }
    }

    items(clients, key = { it.clientId }) { client ->
      val isCurrent = activeClient?.clientId == client.clientId
      val isArchived = client.status.equals("Archived", ignoreCase = true)

      OutlinedCard(
        modifier = Modifier
          .fillMaxWidth()
          .testTag("client_card_${client.clientId}"),
        shape = RoundedCornerShape(14.dp)
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "${client.clientName} (${client.clientCode.ifBlank { client.clientId }})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = "Status: ${client.status} • Active FY: ${client.activeFiscalYearId.replace("_", "–")} • Assets in Firebase: ${client.assetRegisterRowCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              if (client.masterWorkbookFileName.isNotBlank()) {
                Text(
                  text = "Persistent Master Workbook: ${client.masterWorkbookFileName} (${client.masterWorkbookLastSyncStatus})",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.primary
                )
              }
            }
            if (isCurrent) {
              AssistChip(
                onClick = {},
                label = { Text("Active Workspace") }
              )
            }
          }

          HorizontalDivider()

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            if (!isCurrent && !isArchived) {
              Button(
                onClick = { onSelectClientWorkspace(client.clientId, client.activeFiscalYearId) },
                modifier = Modifier.weight(1f)
              ) {
                Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Open Workspace")
              }
            }

            OutlinedButton(
              onClick = { onArchiveOrRestoreClient(client.clientId, !isArchived) },
              enabled = isEffectiveAdmin,
              modifier = Modifier.weight(1f)
            ) {
              Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text(if (isArchived) "Restore Client" else "Archive Client")
            }

            OutlinedButton(
              onClick = {
                clientToDelete = client
                deleteConfirmationText = ""
                includeDriveFolderDeletion = false
                onPreviewClientDeletion(client.clientId)
              },
              enabled = isEffectiveAdmin,
              colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
              modifier = Modifier
                .weight(1f)
                .testTag("delete_client_button_${client.clientId}")
            ) {
              Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text("Delete Client")
            }
          }
        }
      }
    }
  }

  if (showCreateDialog) {
    CreateClientDialog(
      onDismiss = { showCreateDialog = false },
      onConfirm = { name, code, industry, contactPerson, contactEmail, fyLabel, uniqueKeyHeader, provisionDrive ->
        showCreateDialog = false
        onCreateClient(name, code, industry, contactPerson, contactEmail, fyLabel, uniqueKeyHeader, provisionDrive)
      }
    )
  }

  // Permanent Client Deletion Confirmation & Impact Preview Dialog
  clientToDelete?.let { targetClient ->
    val targetCodeOrName = targetClient.clientCode.ifBlank { targetClient.clientName }
    AlertDialog(
      onDismissRequest = {
        clientToDelete = null
        onClearDeletionPreview()
      },
      title = {
        Text("Permanent Client Deletion", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = "You are about to permanently delete client '${targetClient.clientName}' (${targetClient.clientCode}) and all of its business records.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
          )

          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("Target-Scoped Impact Preview:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
              if (deletionImpactPreview != null && deletionImpactPreview.targetId == targetClient.clientId) {
                Text("• Asset Register rows (far_assets): ${deletionImpactPreview.farAssetsCount}", style = MaterialTheme.typography.bodySmall)
                Text("• Counting records (asset_counts): ${deletionImpactPreview.assetCountsCount}", style = MaterialTheme.typography.bodySmall)
                Text("• Daily Input Sheets (daily_batches): ${deletionImpactPreview.dailyBatchesCount}", style = MaterialTheme.typography.bodySmall)
                Text("• Photo metadata records (asset_photos): ${deletionImpactPreview.assetPhotosCount}", style = MaterialTheme.typography.bodySmall)
                Text("• Unlisted observations (unlisted_assets): ${deletionImpactPreview.unlistedAssetsCount}", style = MaterialTheme.typography.bodySmall)
                Text("• Schema configurations: ${deletionImpactPreview.schemaConfigsCount}", style = MaterialTheme.typography.bodySmall)
                Text("• Client assignments & access requests: ${deletionImpactPreview.assignmentsCount + deletionImpactPreview.accessRequestsCount}", style = MaterialTheme.typography.bodySmall)
              } else {
                Text("Calculating target client record counts...", style = MaterialTheme.typography.bodySmall)
              }
              Text("• Other clients and user accounts will NOT be affected.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
          }

          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
          ) {
            Checkbox(
              checked = includeDriveFolderDeletion,
              onCheckedChange = { includeDriveFolderDeletion = it },
              modifier = Modifier.testTag("delete_client_drive_checkbox")
            )
            Spacer(Modifier.width(6.dp))
            Text(
              text = "Also trash exclusive Google Drive folder (AssetFlow/${targetClient.clientName})",
              style = MaterialTheme.typography.bodySmall
            )
          }

          OutlinedTextField(
            value = deleteConfirmationText,
            onValueChange = { deleteConfirmationText = it },
            label = { Text("Type '${targetClient.clientName}' or '$targetCodeOrName' to confirm *") },
            singleLine = true,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("confirm_delete_client_input")
          )
        }
      },
      confirmButton = {
        val confirmValid = deleteConfirmationText.trim().equals(targetClient.clientName, ignoreCase = true) ||
          deleteConfirmationText.trim().equals(targetCodeOrName, ignoreCase = true) ||
          deleteConfirmationText.trim().equals("DELETE", ignoreCase = true)
        Button(
          onClick = {
            onExecutePermanentClientDeletion(
              targetClient.clientId,
              deleteConfirmationText.trim(),
              includeDriveFolderDeletion
            )
            clientToDelete = null
          },
          enabled = confirmValid,
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          modifier = Modifier.testTag("confirm_permanent_delete_client_button")
        ) {
          Text("Permanently Delete Client")
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            clientToDelete = null
            onClearDeletionPreview()
          }
        ) {
          Text("Cancel")
        }
      }
    )
  }
}

// ============================================================================
// CONTROL PANEL SUB-TAB 3: CLIENT ASSIGNMENT (AUDITOR WORKSPACE ACCESS)
// ============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminClientAssignmentSubTab(
  isEffectiveAdmin: Boolean,
  clients: List<ClientRecord>,
  roleAssignments: List<RoleAssignment>,
  clientAssignments: List<ClientAssignmentRecord>,
  onAssignClientToAuditor: (String, String, List<String>, String, String, String) -> Unit,
  onUnassignClientFromAuditor: (String, String, String) -> Unit
) {
  val activeClients = remember(clients) { clients.filter { it.status == "Active" } }
  val auditors = remember(roleAssignments) {
    roleAssignments.filter { it.role != UserRole.OWNER && it.role != UserRole.ADMIN }
  }

  var selectedClient by remember(activeClients) { mutableStateOf(activeClients.firstOrNull()) }
  var selectedAuditor by remember(auditors) { mutableStateOf(auditors.firstOrNull()) }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("admin_client_assignment_subtab"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.AssignmentInd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
              text = "Assign Clients to Auditors",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
          }
          Text(
            text = "Auditors can only view and count assets for clients explicitly assigned to them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          Text("1. Choose Client:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            activeClients.forEach { c ->
              FilterChip(
                selected = selectedClient?.clientId == c.clientId,
                onClick = { selectedClient = c },
                label = { Text(c.clientName) }
              )
            }
          }

          Text("2. Choose Auditor:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
          if (auditors.isEmpty()) {
            Text("No Auditor accounts registered yet.", style = MaterialTheme.typography.bodySmall)
          } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              auditors.forEach { aud ->
                val uid = aud.memberUid.ifBlank { aud.id }
                FilterChip(
                  selected = selectedAuditor?.memberUid == uid,
                  onClick = { selectedAuditor = aud },
                  label = { Text("${aud.fullName.ifBlank { aud.displayName }} (${aud.email})") }
                )
              }
            }
          }

          Button(
            onClick = {
              val c = selectedClient
              val a = selectedAuditor
              if (c != null && a != null) {
                onAssignClientToAuditor(
                  c.clientId,
                  c.clientName,
                  c.fiscalYearIds.ifEmpty { listOf(c.activeFiscalYearId) },
                  a.memberUid.ifBlank { a.id },
                  a.email,
                  a.fullName.ifBlank { a.displayName }
                )
              }
            },
            enabled = isEffectiveAdmin && selectedClient != null && selectedAuditor != null,
            modifier = Modifier
              .align(Alignment.End)
              .testTag("assign_client_to_auditor_button")
          ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Assign Client to Auditor")
          }
        }
      }
    }

    item {
      Text(
        text = "Current Client Assignments (${clientAssignments.count { it.status == "Assigned" }})",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
      )
    }

    items(clientAssignments.filter { it.status == "Assigned" }, key = { it.assignmentId }) { item ->
      OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "${item.clientName} → ${item.auditorName}",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "Auditor Email: ${item.auditorEmail} • Fiscal Years: ${item.fiscalYearIds.joinToString { it.replace("_", "–") }}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          OutlinedButton(
            onClick = { onUnassignClientFromAuditor(item.assignmentId, item.clientId, item.auditorUid) },
            enabled = isEffectiveAdmin,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
          ) {
            Text("Unassign")
          }
        }
      }
    }
  }
}

// ============================================================================
// CONTROL PANEL SUB-TAB 4: ACCESS REQUESTS (AUDITOR → ADMIN APPROVAL FLOW)
// ============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminAccessRequestsSubTab(
  isEffectiveAdmin: Boolean,
  clients: List<ClientRecord>,
  accessRequests: List<AccessRequestRecord>,
  onReviewAccessRequest: (AccessRequestRecord, Boolean, String, String, String) -> Unit
) {
  val activeClients = remember(clients) { clients.filter { it.status == "Active" } }
  var selectedAssignClientByReqId by remember { mutableStateOf<Map<String, ClientRecord>>(emptyMap()) }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("admin_access_requests_subtab"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.LockOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
              text = "Auditor Client Access Requests",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
          }
          Text(
            text = "Approving a request automatically assigns the selected client workspace to the requesting Auditor.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }

    if (accessRequests.isEmpty()) {
      item {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
          Text(
            text = "No access requests submitted by Auditors.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall
          )
        }
      }
    } else {
      items(accessRequests, key = { it.requestId }) { req ->
        val chosenClient = selectedAssignClientByReqId[req.requestId]
          ?: activeClients.firstOrNull { it.clientId == req.clientId }
          ?: activeClients.firstOrNull()

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "${req.requesterName} (${req.requesterEmail})",
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold
                )
                Text(
                  text = "Requested Client: ${req.clientName.ifBlank { "Any Client" }} • Status: ${req.status}",
                  style = MaterialTheme.typography.bodySmall
                )
                if (req.reason.isNotBlank()) {
                  Text(
                    text = "Reason: ${req.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
            }

            if (req.status == "Pending" && isEffectiveAdmin) {
              if (activeClients.isNotEmpty()) {
                Text("Assign to Client on Approval:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  activeClients.forEach { c ->
                    FilterChip(
                      selected = chosenClient?.clientId == c.clientId,
                      onClick = {
                        selectedAssignClientByReqId = selectedAssignClientByReqId + (req.requestId to c)
                      },
                      label = { Text(c.clientName) }
                    )
                  }
                }
              }

              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                  onClick = {
                    onReviewAccessRequest(
                      req,
                      true,
                      chosenClient?.clientId ?: req.clientId,
                      chosenClient?.clientName ?: req.clientName,
                      "Approved and assigned by Admin"
                    )
                  },
                  enabled = chosenClient != null || req.clientId.isNotBlank(),
                  modifier = Modifier.testTag("approve_access_request_${req.requestId}")
                ) {
                  Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(4.dp))
                  Text("Approve & Assign")
                }

                OutlinedButton(
                  onClick = {
                    onReviewAccessRequest(req, false, req.clientId, req.clientName, "Rejected by Admin")
                  },
                  colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                  modifier = Modifier.testTag("reject_access_request_${req.requestId}")
                ) {
                  Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(4.dp))
                  Text("Reject")
                }
              }
            }
          }
        }
      }
    }
  }
}

// ============================================================================
// CONTROL PANEL SUB-TAB 5: GOOGLE DRIVE, OFFLINE SYNC, DELETION LOGS & LOGOUT
// ============================================================================

@Composable
private fun AdminDriveAndSettingsSubTab(
  isEffectiveAdmin: Boolean,
  canSyncDrive: Boolean,
  activeClient: ClientRecord?,
  projectSettings: ProjectSettings,
  userProfile: UserProfile,
  assetPhotos: List<AssetPhotoRecord>,
  exportJobs: List<ExportJobRecord>,
  queuedOfflineRecords: List<OfflineQueuedRecordEntity>,
  queuedOfflinePhotos: List<OfflinePhotoQueueEntity>,
  deletionJobs: List<DeletionJobRecord>,
  deletionAuditEvents: List<DeletionAuditEvent>,
  isDriveAuthorizationReady: Boolean,
  driveErrorMessage: String,
  driveSessionAccountEmail: String,
  onAuthorizeDrive: () -> Unit,
  onDisconnectDrive: () -> Unit,
  onTestUpload: () -> Unit,
  onProvisionFolders: () -> Unit,
  onSyncMasterWorkbook: () -> Unit,
  onSyncAllOfflineNow: () -> Unit,
  onRetryPhotoUpload: (String) -> Unit,
  onSignOut: () -> Unit
) {
  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("admin_drive_and_settings_subtab"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    // 1. Per-Client Google Drive Folder Structure Card
    item {
      ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
              text = "Google Drive Structure (Per Client)",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
          }
          val clientLabel = activeClient?.clientName ?: "[Client Name]"
          Text(
            text = "• AssetFlow / $clientLabel / Image Evidence / [YYYY-MM-DD]\n" +
              "• AssetFlow / $clientLabel / Master Input File (Persistent)\n" +
              "• AssetFlow / $clientLabel / Daily Input Sheets / [Auditor Name] / [Date]",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          val displayedEmail = if (isDriveAuthorizationReady) driveSessionAccountEmail else projectSettings.connectedDriveEmail
          Text(
            text = "Connected Account: ${displayedEmail.ifBlank { "Not Connected" }} • Status: ${if (isDriveAuthorizationReady) "Authorized" else "Disconnected"}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
          )
          if (driveErrorMessage.isNotBlank()) {
            Text(driveErrorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
          }

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              onClick = onAuthorizeDrive,
              enabled = canSyncDrive,
              modifier = Modifier.testTag("admin_drive_authorize_button")
            ) {
              Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text(if (displayedEmail.isBlank()) "Connect Google Drive" else "Switch Account")
            }

            OutlinedButton(
              onClick = onProvisionFolders,
              enabled = canSyncDrive && isDriveAuthorizationReady,
              modifier = Modifier.testTag("provision_client_drive_folders_button")
            ) {
              Text("Provision Folders")
            }
          }

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
              onClick = onSyncMasterWorkbook,
              enabled = canSyncDrive && isDriveAuthorizationReady
            ) {
              Text("Sync Master Sheet")
            }
            OutlinedButton(
              onClick = onTestUpload,
              enabled = canSyncDrive && isDriveAuthorizationReady
            ) {
              Text("Test Photo Upload")
            }
            if (displayedEmail.isNotBlank()) {
              TextButton(
                onClick = onDisconnectDrive,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
              ) {
                Text("Disconnect")
              }
            }
          }
        }
      }
    }

    // 2. Offline Queue & Photo Upload Retry Card
    item {
      val pendingRecs = queuedOfflineRecords.count { it.syncStatus != SyncState.SYNCED }
      val pendingPhotos = queuedOfflinePhotos.filter { it.uploadStatus != SyncState.SYNCED }
      OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Offline Synchronization Queue", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
              Text(
                "Pending Records: $pendingRecs • Local-Only Photos Pending Drive Upload: ${pendingPhotos.size} • Synced Drive Photos: ${assetPhotos.count { it.uploadStatus == SyncState.SYNCED }}",
                style = MaterialTheme.typography.bodySmall
              )
            }
            OutlinedButton(onClick = onSyncAllOfflineNow) {
              Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text("Sync Now")
            }
          }

          pendingPhotos.forEach { photo ->
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "Local Photo Pending Drive Upload: ${photo.targetDriveFileName} (${photo.uploadStatus})",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
              )
              TextButton(onClick = { onRetryPhotoUpload(photo.photoId) }) {
                Text("Retry")
              }
            }
          }
        }
      }
    }

    // 3. Permanent Deletion Audit Trail Summary
    if (deletionJobs.isNotEmpty() || deletionAuditEvents.isNotEmpty()) {
      item {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Text("Permanent Deletion Audit Events (${deletionAuditEvents.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            deletionAuditEvents.take(5).forEach { evt ->
              Text(
                text = "• [${evt.timestampIso.take(19)}] ${evt.targetType} (#${evt.targetIdHash}) — ${evt.status} by ${evt.actorEmail}",
                style = MaterialTheme.typography.bodySmall
              )
            }
          }
        }
      }
    }

    // 4. Admin Logout Button
    item {
      Spacer(Modifier.height(8.dp))
      Button(
        onClick = onSignOut,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = Modifier
          .fillMaxWidth()
          .height(48.dp)
          .testTag("admin_logout_button"),
        shape = RoundedCornerShape(12.dp)
      ) {
        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Admin Logout (End Admin Session)", fontWeight = FontWeight.Bold)
      }
    }
  }
}
