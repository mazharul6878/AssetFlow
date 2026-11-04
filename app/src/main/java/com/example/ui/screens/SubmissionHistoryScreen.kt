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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.example.data.model.AssetWithCount
import com.example.data.model.DailyBatchSubmissionRecord
import com.example.data.model.SubmissionBatchStatus
import com.example.data.model.VerificationResultStatus
import com.example.ui.components.ResultStatusBadge
import com.example.ui.theme.NovaBorderLight
import com.example.ui.theme.NovaPrimary
import com.example.ui.theme.NovaSurfaceWhite
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextNavy
import com.example.ui.theme.StatusExceptionBg
import com.example.ui.theme.StatusExceptionFg

/**
 * End User Panel: Submission History & Direct Record Correction (Section 4.C.5, 4.C.6 Method A)
 * - Search by Asset ID, description, submission identifier, or date
 * - Display submission status and review history
 * - Display Admin remarks
 * - Method A: Direct record correction workflow for records requiring correction
 * - Preserve earlier versions without direct mutation of Master Register
 */
@Composable
fun SubmissionHistoryScreen(
  assetsWithCounts: List<AssetWithCount>,
  dailyBatches: List<DailyBatchSubmissionRecord>,
  onCorrectRecord: (AssetWithCount) -> Unit,
  onResubmitBatch: (batchId: String) -> Unit
) {
  var searchQuery by remember { mutableStateOf("") }

  val countedAssets = remember(assetsWithCounts) {
    assetsWithCounts.filter { it.countingRecord != null }
  }

  val filteredAssets = remember(countedAssets, searchQuery) {
    if (searchQuery.isBlank()) countedAssets
    else {
      val q = searchQuery.trim()
      countedAssets.filter { item ->
        item.farAsset.assetId.contains(q, ignoreCase = true) ||
          item.farAsset.assetDescription.contains(q, ignoreCase = true) ||
          item.countingRecord?.countDate?.contains(q, ignoreCase = true) == true ||
          item.countingRecord?.tagNumberAcn?.contains(q, ignoreCase = true) == true
      }
    }
  }

  val batchesByDate = remember(dailyBatches) {
    dailyBatches.associateBy { it.countingDate }
  }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("submission_history_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    item {
      Column {
        Text(
          text = "Submission History",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          color = NovaTextNavy
        )
        Text(
          text = "Review submitted counting records, audit trails, and apply Method A direct corrections.",
          style = MaterialTheme.typography.bodyMedium,
          color = NovaTextMuted
        )
      }
    }

    item {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = { Text("Search Asset ID, Description, or Date...") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
        trailingIcon = {
          if (searchQuery.isNotEmpty()) {
            IconButton(onClick = { searchQuery = "" }) {
              Icon(Icons.Default.Clear, contentDescription = "Clear")
            }
          }
        },
        modifier = Modifier
          .fillMaxWidth()
          .testTag("submission_history_search_input"),
        shape = RoundedCornerShape(12.dp),
        singleLine = true
      )
    }

    if (filteredAssets.isEmpty()) {
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
            Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(48.dp), tint = NovaPrimary)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = if (searchQuery.isBlank()) "No Count History Yet" else "No matching records found for '$searchQuery'",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              color = NovaTextNavy
            )
          }
        }
      }
    } else {
      items(filteredAssets, key = { it.farAsset.assetKey }) { item ->
        val cnt = item.countingRecord
        val batch = cnt?.countDate?.let { batchesByDate[it] }
        val isCorrectionRequired = batch?.status == SubmissionBatchStatus.CHANGES_REQUESTED ||
          cnt?.reviewStatus == VerificationResultStatus.PENDING_REVIEW

        Card(
          modifier = Modifier
            .fillMaxWidth()
            .testTag("history_item_${item.farAsset.assetId}"),
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = NovaSurfaceWhite),
          border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(NovaBorderLight))
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = item.farAsset.assetId,
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = NovaTextNavy
                )
                Text(
                  text = item.farAsset.assetDescription,
                  style = MaterialTheme.typography.bodySmall,
                  color = NovaTextMuted
                )
              }
              ResultStatusBadge(result = item.effectiveResult)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
              Text(
                text = "Count Date: ${cnt?.countDate.orEmpty()}",
                style = MaterialTheme.typography.labelSmall,
                color = NovaTextMuted
              )
              Text(
                text = "Counted By: ${cnt?.countedBy.orEmpty()}",
                style = MaterialTheme.typography.labelSmall,
                color = NovaTextMuted
              )
              if (!cnt?.reviewedBy.isNullOrBlank()) {
                Text(
                  text = "Reviewed By: ${cnt?.reviewedBy}",
                  style = MaterialTheme.typography.labelSmall,
                  color = NovaTextMuted
                )
              }
            }

            // Show Admin remarks if batch has changes requested
            if (batch?.status == SubmissionBatchStatus.CHANGES_REQUESTED && batch.adminRemarks.isNotBlank()) {
              Spacer(modifier = Modifier.height(8.dp))
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = StatusExceptionBg,
                modifier = Modifier.fillMaxWidth()
              ) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Default.Feedback, contentDescription = null, tint = StatusExceptionFg, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = "Remarks: ${batch.adminRemarks}",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusExceptionFg
                  )
                }
              }
            }

            // Direct Record Correction Button (Method A)
            Spacer(modifier = Modifier.height(10.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.End,
              verticalAlignment = Alignment.CenterVertically
            ) {
              if (isCorrectionRequired) {
                Button(
                  onClick = { onCorrectRecord(item) },
                  shape = RoundedCornerShape(10.dp),
                  colors = ButtonDefaults.buttonColors(containerColor = NovaPrimary),
                  modifier = Modifier.testTag("correct_record_btn_${item.farAsset.assetId}")
                ) {
                  Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Edit & Correct Record (Method A)")
                }
              } else {
                OutlinedButton(
                  onClick = { onCorrectRecord(item) },
                  shape = RoundedCornerShape(10.dp)
                ) {
                  Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("View / Edit")
                }
              }
            }
          }
        }
      }
    }
  }
}
