package com.example.ui.screens.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.ApprovalLifecycleStatus
import com.example.data.model.DailyBatchSubmissionRecord
import com.example.data.model.MasterDriveSyncStatus
import com.example.data.model.SubmissionBatchStatus
import com.example.data.model.UserProfile
import com.example.ui.theme.AuditNavyPrimary
import com.example.ui.theme.StatusExceptionBg
import com.example.ui.theme.StatusExceptionFg
import com.example.ui.theme.StatusVerifiedBg
import com.example.ui.theme.StatusVerifiedFg
import com.example.ui.theme.StatusWarningBg
import com.example.ui.theme.StatusWarningFg

/**
 * Admin Workspace Tab 3 — Daily Sheet Review:
 * Displays separate Approval Status and Master Drive Synchronization Status badges,
 * with actions for:
 * 1. Review (inspect batch details)
 * 2. Send Remarks (inline dialog to request changes)
 * 3. Approve Only (records approval in Firebase without Drive sync)
 * 4. Approve & Publish / Retry Master Sync (non-destructive cell patch of persistent Master Input Workbook in Google Drive)
 * 5. Download Daily Sheet (.xlsx)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminReviewQueueTab(
  userProfile: UserProfile,
  canReview: Boolean,
  canPublishToMaster: Boolean,
  dailyBatches: List<DailyBatchSubmissionRecord>,
  onReviewBatch: (DailyBatchSubmissionRecord) -> Unit,
  onSendRemarks: (DailyBatchSubmissionRecord, String) -> Unit,
  onApproveBatchOnly: (DailyBatchSubmissionRecord) -> Unit = {},
  onApproveAndPublish: (DailyBatchSubmissionRecord) -> Unit,
  onDownloadDailySheetExcel: (DailyBatchSubmissionRecord) -> Unit = {},
  onRefresh: () -> Unit = {}
) {
  var selectedStatusFilter by remember { mutableStateOf<String?>(null) }
  var batchForRemarksDialog by remember { mutableStateOf<DailyBatchSubmissionRecord?>(null) }
  var remarksInput by remember { mutableStateOf("") }
  var publicationDeniedMessage by remember { mutableStateOf<String?>(null) }

  val filteredBatches = remember(dailyBatches, selectedStatusFilter) {
    if (selectedStatusFilter == null) {
      dailyBatches
    } else {
      dailyBatches.filter {
        it.status == selectedStatusFilter ||
          it.approvalStatus == selectedStatusFilter ||
          it.masterSyncStatus == selectedStatusFilter
      }
    }
  }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("admin_review_queue_list"),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Daily Sheet Review & Master Workbook Sync",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = "Total Sheets: ${dailyBatches.size} | Awaiting Approval: ${dailyBatches.count { it.status == SubmissionBatchStatus.SUBMITTED }} | Synced to Master: ${dailyBatches.count { it.masterSyncStatus == MasterDriveSyncStatus.SYNCED || it.status == SubmissionBatchStatus.PUBLISHED }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            OutlinedButton(
              onClick = onRefresh,
              modifier = Modifier.testTag("refresh_review_queue_button")
            ) {
              Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text("Refresh")
            }
          }

          Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier.padding(10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Text(
                text = "Approval is recorded in Firebase first. Master Workbook synchronization patches ONLY mapped destination cells in place in Google Drive while preserving all Excel formulas and formatting.",
                style = MaterialTheme.typography.labelSmall
              )
            }
          }

          FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            FilterChip(
              selected = selectedStatusFilter == null,
              onClick = { selectedStatusFilter = null },
              label = { Text("All (${dailyBatches.size})") }
            )
            FilterChip(
              selected = selectedStatusFilter == SubmissionBatchStatus.SUBMITTED,
              onClick = { selectedStatusFilter = SubmissionBatchStatus.SUBMITTED },
              label = { Text("Awaiting (${dailyBatches.count { it.status == SubmissionBatchStatus.SUBMITTED }})") }
            )
            FilterChip(
              selected = selectedStatusFilter == SubmissionBatchStatus.CHANGES_REQUESTED,
              onClick = { selectedStatusFilter = SubmissionBatchStatus.CHANGES_REQUESTED },
              label = { Text("Remarks Sent") }
            )
            FilterChip(
              selected = selectedStatusFilter == MasterDriveSyncStatus.SYNC_FAILED,
              onClick = { selectedStatusFilter = MasterDriveSyncStatus.SYNC_FAILED },
              label = { Text("Sync Failed") }
            )
            FilterChip(
              selected = selectedStatusFilter == SubmissionBatchStatus.PUBLISHED,
              onClick = { selectedStatusFilter = SubmissionBatchStatus.PUBLISHED },
              label = { Text("Published") }
            )
          }
        }
      }
    }

    if (filteredBatches.isEmpty()) {
      item {
        OutlinedCard(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp)
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              Icons.AutoMirrored.Filled.FactCheck,
              contentDescription = null,
              modifier = Modifier.size(40.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
              text = "No daily sheets matching filter",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold
            )
            Text(
              text = "Auditor daily submissions for this client and fiscal year will appear here.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }
    }

    items(filteredBatches, key = { it.batchId.ifBlank { it.id } }) { batch ->
      BatchReviewItemCard(
        batch = batch,
        canReview = canReview,
        canPublish = canPublishToMaster,
        onActionReview = { onReviewBatch(batch) },
        onActionSendRemarks = {
          batchForRemarksDialog = batch
          remarksInput = batch.adminRemarks
        },
        onActionApproveOnly = { onApproveBatchOnly(batch) },
        onActionApproveAndPublish = {
          if (canPublishToMaster) {
            onApproveAndPublish(batch)
          } else {
            publicationDeniedMessage = "Publication Permission Required: Updating the persistent Master Input Workbook in Google Drive requires Master Publication permission."
          }
        },
        onActionDownloadExcel = { onDownloadDailySheetExcel(batch) }
      )
    }
  }

  batchForRemarksDialog?.let { batch ->
    AlertDialog(
      onDismissRequest = { batchForRemarksDialog = null },
      title = { Text("Send Remarks to Auditor") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "Batch: ${batch.batchName} (${batch.targetDate}) by ${batch.auditorDisplayName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          OutlinedTextField(
            value = remarksInput,
            onValueChange = { remarksInput = it },
            label = { Text("Reviewer Remarks *") },
            placeholder = { Text("e.g. Please re-verify physical count quantity and upload photo.") },
            modifier = Modifier
              .fillMaxWidth()
              .height(120.dp)
              .testTag("admin_remarks_text_input"),
            maxLines = 5
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (remarksInput.isNotBlank()) {
              onSendRemarks(batch, remarksInput.trim())
              batchForRemarksDialog = null
              remarksInput = ""
            }
          },
          enabled = remarksInput.isNotBlank(),
          modifier = Modifier.testTag("submit_remarks_button")
        ) {
          Icon(Icons.AutoMirrored.Filled.Comment, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text("Send Remarks")
        }
      },
      dismissButton = {
        TextButton(onClick = { batchForRemarksDialog = null }) {
          Text("Cancel")
        }
      }
    )
  }

  publicationDeniedMessage?.let { msg ->
    AlertDialog(
      onDismissRequest = { publicationDeniedMessage = null },
      title = { Text("Master Publication Denied") },
      text = { Text(msg) },
      confirmButton = {
        Button(onClick = { publicationDeniedMessage = null }) {
          Text("Understood")
        }
      }
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchReviewItemCard(
  batch: DailyBatchSubmissionRecord,
  canReview: Boolean,
  canPublish: Boolean,
  onActionReview: () -> Unit,
  onActionSendRemarks: () -> Unit,
  onActionApproveOnly: () -> Unit,
  onActionApproveAndPublish: () -> Unit,
  onActionDownloadExcel: () -> Unit
) {
  OutlinedCard(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("batch_card_${batch.batchId}"),
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = batch.batchName.ifBlank { "Daily Sheet — ${batch.countingDate}" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Date: ${batch.targetDate.ifBlank { batch.countingDate }} | Auditor: ${batch.auditorDisplayName} (${batch.auditorNickname})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        OutlinedButton(
          onClick = onActionDownloadExcel,
          modifier = Modifier.testTag("download_batch_excel_${batch.batchId}")
        ) {
          Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Excel", style = MaterialTheme.typography.labelSmall)
        }
      }

      // Separate Approval Status & Master Sync Status badges
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        val approvalLabel = batch.approvalStatus.ifBlank { batch.status }
        val (appBg, appFg) = when (approvalLabel) {
          ApprovalLifecycleStatus.APPROVED, SubmissionBatchStatus.PUBLISHED, SubmissionBatchStatus.APPROVED_AWAITING_MASTER_UPDATE ->
            StatusVerifiedBg to StatusVerifiedFg
          ApprovalLifecycleStatus.CHANGES_REQUESTED, SubmissionBatchStatus.CHANGES_REQUESTED ->
            StatusWarningBg to StatusWarningFg
          ApprovalLifecycleStatus.REJECTED, SubmissionBatchStatus.REJECTED ->
            StatusExceptionBg to StatusExceptionFg
          else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Surface(shape = RoundedCornerShape(8.dp), color = appBg) {
          Text(
            text = "Approval: $approvalLabel",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = appFg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
          )
        }

        val syncLabel = batch.masterSyncStatus.ifBlank {
          if (batch.status == SubmissionBatchStatus.PUBLISHED) MasterDriveSyncStatus.SYNCED
          else MasterDriveSyncStatus.NOT_APPLICABLE
        }
        val (syncBg, syncFg) = when (syncLabel) {
          MasterDriveSyncStatus.SYNCED -> StatusVerifiedBg to StatusVerifiedFg
          MasterDriveSyncStatus.SYNC_FAILED -> StatusExceptionBg to StatusExceptionFg
          MasterDriveSyncStatus.PENDING_DRIVE_SYNC, MasterDriveSyncStatus.SYNCING_TO_DRIVE -> StatusWarningBg to StatusWarningFg
          else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Surface(shape = RoundedCornerShape(8.dp), color = syncBg) {
          Text(
            text = "Master Drive Sync: $syncLabel",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = syncFg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        Column {
          Text("Counted Assets", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text("${batch.countedAssetCount.takeIf { it > 0 } ?: batch.recordCount}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Column {
          Text("Unregistered (Found)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text("${batch.foundNotInFarCount}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Column {
          Text("Total Items", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text("${batch.totalRecordCount.takeIf { it > 0 } ?: batch.recordCount}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
      }

      if (batch.adminRemarks.isNotBlank()) {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(10.dp)) {
            Text(
              text = "Admin Remarks (${batch.reviewedByName}):",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = batch.adminRemarks,
              style = MaterialTheme.typography.bodySmall
            )
          }
        }
      }

      if (batch.masterSyncErrorMessage.isNotBlank()) {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = StatusExceptionBg,
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = "Master Sync Notice: ${batch.masterSyncErrorMessage}",
            style = MaterialTheme.typography.bodySmall,
            color = StatusExceptionFg,
            modifier = Modifier.padding(10.dp)
          )
        }
      }

      HorizontalDivider()

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        OutlinedButton(
          onClick = onActionReview,
          enabled = canReview,
          modifier = Modifier
            .weight(1f)
            .testTag("action_review_batch_${batch.batchId}")
        ) {
          Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("1. Review")
        }

        OutlinedButton(
          onClick = onActionSendRemarks,
          enabled = canReview,
          modifier = Modifier
            .weight(1.1f)
            .testTag("action_send_remarks_${batch.batchId}")
        ) {
          Icon(Icons.AutoMirrored.Filled.Comment, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("2. Remarks")
        }

        if (batch.approvalStatus != ApprovalLifecycleStatus.APPROVED && batch.status != SubmissionBatchStatus.PUBLISHED) {
          OutlinedButton(
            onClick = onActionApproveOnly,
            enabled = canReview,
            modifier = Modifier
              .weight(1.1f)
              .testTag("action_approve_only_${batch.batchId}")
          ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Approve")
          }
        }

        val isSynced = batch.status == SubmissionBatchStatus.PUBLISHED && batch.masterSyncStatus == MasterDriveSyncStatus.SYNCED
        Button(
          onClick = onActionApproveAndPublish,
          enabled = canPublish && !isSynced,
          colors = ButtonDefaults.buttonColors(containerColor = AuditNavyPrimary),
          modifier = Modifier
            .weight(1.6f)
            .testTag("action_approve_publish_${batch.batchId}")
        ) {
          Icon(
            if (isSynced) Icons.Default.CheckCircle else Icons.Default.CloudUpload,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
          )
          Spacer(Modifier.width(4.dp))
          Text(
            when {
              isSynced -> "Synced to Master"
              batch.masterSyncStatus == MasterDriveSyncStatus.SYNC_FAILED -> "Retry Master Sync"
              batch.approvalStatus == ApprovalLifecycleStatus.APPROVED -> "Sync Master Sheet"
              else -> "3. Approve & Sync"
            }
          )
        }
      }
    }
  }
}
