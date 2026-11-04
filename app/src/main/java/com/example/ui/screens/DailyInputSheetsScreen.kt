package com.example.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.data.model.DailyBatchSubmissionRecord
import com.example.data.model.SubmissionBatchStatus
import com.example.ui.theme.NovaBorderLight
import com.example.ui.theme.NovaPrimary
import com.example.ui.theme.NovaSurfaceWhite
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextNavy
import com.example.ui.theme.StatusExceptionBg
import com.example.ui.theme.StatusExceptionFg
import com.example.ui.theme.StatusPendingBg
import com.example.ui.theme.StatusPendingFg
import com.example.ui.theme.StatusVerifiedBg
import com.example.ui.theme.StatusVerifiedFg
import com.example.ui.theme.StatusWarningBg
import com.example.ui.theme.StatusWarningFg

/**
 * End User Panel: Daily Input Sheets (Section 4.C.4, 4.C.7 Method B, 4.C.8)
 * - Consolidated Input Sheet for each counting date
 * - Display current submission and review status
 * - Provide access to version and Admin feedback
 * - Request Admin Review for eligible pending batches
 * - Download Daily Input Sheet Excel & Upload corrected Excel (Method B)
 */
@Composable
fun DailyInputSheetsScreen(
  dailyBatches: List<DailyBatchSubmissionRecord>,
  userDisplayName: String,
  onRequestReview: (batchId: String, countingDate: String, recordCount: Long, notes: String) -> Unit,
  onDownloadExcel: (DailyBatchSubmissionRecord) -> Unit,
  onUploadCorrectedExcel: (batchId: String) -> Unit,
  onSubmitBatch: (DailyBatchSubmissionRecord) -> Unit
) {
  var reviewRequestBatch by remember { mutableStateOf<DailyBatchSubmissionRecord?>(null) }
  var reviewRequestNotes by remember { mutableStateOf("") }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("daily_input_sheets_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    item {
      Column {
        Text(
          text = "Daily Input Sheets",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          color = NovaTextNavy
        )
        Text(
          text = "Consolidated count batches for $userDisplayName. Each counting date forms a distinct verifiable batch.",
          style = MaterialTheme.typography.bodyMedium,
          color = NovaTextMuted
        )
      }
    }

    if (dailyBatches.isEmpty()) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = NovaSurfaceWhite),
          border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(NovaBorderLight))
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
          ) {
            Icon(
              imageVector = Icons.Default.CalendarMonth,
              contentDescription = null,
              modifier = Modifier.size(48.dp),
              tint = NovaPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
              text = "No Daily Input Sheets Yet",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              color = NovaTextNavy
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "Counts performed in 'Find Asset' will be grouped here by date and submitted for Admin review.",
              style = MaterialTheme.typography.bodyMedium,
              color = NovaTextMuted,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
          }
        }
      }
    } else {
      items(dailyBatches, key = { it.batchId.ifBlank { it.id } }) { batch ->
        DailyBatchCard(
          batch = batch,
          onRequestReviewClick = {
            reviewRequestBatch = batch
            reviewRequestNotes = ""
          },
          onDownloadExcelClick = { onDownloadExcel(batch) },
          onUploadCorrectedExcelClick = { onUploadCorrectedExcel(batch.batchId) },
          onSubmitBatchClick = { onSubmitBatch(batch) }
        )
      }
    }
  }

  // Request Admin Review Dialog
  reviewRequestBatch?.let { batch ->
    AlertDialog(
      onDismissRequest = { reviewRequestBatch = null },
      title = {
        Text("Request Admin Review", fontWeight = FontWeight.Bold)
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = "Request review for Date ${batch.countingDate} (v${batch.versionNumber}, ${batch.recordCount} records). This notifies Admin & Reviewers.",
            style = MaterialTheme.typography.bodyMedium,
            color = NovaTextNavy
          )
          OutlinedTextField(
            value = reviewRequestNotes,
            onValueChange = { reviewRequestNotes = it },
            label = { Text("Notes for Reviewer (Optional)") },
            placeholder = { Text("e.g. Floor 2 counts complete, ready for verification") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            onRequestReview(batch.batchId, batch.countingDate, batch.recordCount, reviewRequestNotes)
            reviewRequestBatch = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = NovaPrimary)
        ) {
          Text("Send Request")
        }
      },
      dismissButton = {
        TextButton(onClick = { reviewRequestBatch = null }) {
          Text("Cancel")
        }
      }
    )
  }
}

@Composable
private fun DailyBatchCard(
  batch: DailyBatchSubmissionRecord,
  onRequestReviewClick: () -> Unit,
  onDownloadExcelClick: () -> Unit,
  onUploadCorrectedExcelClick: () -> Unit,
  onSubmitBatchClick: () -> Unit
) {
  val (statusBg, statusFg) = when (batch.status) {
    SubmissionBatchStatus.PUBLISHED -> StatusVerifiedBg to StatusVerifiedFg
    SubmissionBatchStatus.APPROVED_AWAITING_MASTER_UPDATE -> StatusVerifiedBg to StatusVerifiedFg
    SubmissionBatchStatus.CHANGES_REQUESTED, SubmissionBatchStatus.REJECTED -> StatusExceptionBg to StatusExceptionFg
    SubmissionBatchStatus.UNDER_REVIEW, SubmissionBatchStatus.SUBMITTED -> StatusPendingBg to StatusPendingFg
    else -> StatusWarningBg to StatusWarningFg
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("daily_batch_card_${batch.countingDate}"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = NovaSurfaceWhite),
    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(NovaBorderLight))
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      // Header: Date + Status Badge
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.CalendarMonth,
            contentDescription = null,
            tint = NovaPrimary,
            modifier = Modifier.size(20.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = batch.countingDate.ifBlank { "Unscheduled Date" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = NovaTextNavy
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "v${batch.versionNumber}",
            style = MaterialTheme.typography.labelMedium,
            color = NovaTextMuted
          )
        }

        Surface(
          shape = RoundedCornerShape(8.dp),
          color = statusBg
        ) {
          Text(
            text = batch.status,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = statusFg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Metric summary pills
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        BatchPill(label = "Total Records", value = batch.recordCount.toString(), modifier = Modifier.weight(1f))
        BatchPill(label = "Found", value = batch.foundCount.toString(), modifier = Modifier.weight(1f))
        BatchPill(label = "Not Found", value = batch.notFoundCount.toString(), modifier = Modifier.weight(1f))
        BatchPill(label = "Manual Entries", value = batch.manualEntryCount.toString(), modifier = Modifier.weight(1f))
      }

      // Admin Remarks Banner if changes requested
      if (batch.status == SubmissionBatchStatus.CHANGES_REQUESTED && batch.adminRemarks.isNotBlank()) {
        Spacer(modifier = Modifier.height(12.dp))
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = StatusExceptionBg,
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top
          ) {
            Icon(
              imageVector = Icons.Default.Feedback,
              contentDescription = null,
              tint = StatusExceptionFg,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
              Text(
                text = "Reviewer Remarks (${batch.remarksByName}):",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = StatusExceptionFg
              )
              Text(
                text = batch.adminRemarks,
                style = MaterialTheme.typography.bodySmall,
                color = StatusExceptionFg
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // Action buttons
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        // Method B: Download Excel
        OutlinedButton(
          onClick = onDownloadExcelClick,
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(10.dp)
        ) {
          Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Download Excel", style = MaterialTheme.typography.labelMedium)
        }

        // Method B: Upload Corrected Excel
        if (batch.status == SubmissionBatchStatus.CHANGES_REQUESTED || batch.status == SubmissionBatchStatus.DRAFT) {
          OutlinedButton(
            onClick = onUploadCorrectedExcelClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp)
          ) {
            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Upload Corrected", style = MaterialTheme.typography.labelMedium)
          }
        }

        // Request Admin Review (Section 4.C.8)
        if (batch.status == SubmissionBatchStatus.SUBMITTED || batch.status == SubmissionBatchStatus.DRAFT) {
          Button(
            onClick = onRequestReviewClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NovaPrimary)
          ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Request Review", style = MaterialTheme.typography.labelMedium)
          }
        }
      }
    }
  }
}

@Composable
private fun BatchPill(label: String, value: String, modifier: Modifier = Modifier) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = modifier
  ) {
    Column(
      modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(text = value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = NovaTextNavy)
      Text(text = label, style = MaterialTheme.typography.labelSmall, color = NovaTextMuted)
    }
  }
}
