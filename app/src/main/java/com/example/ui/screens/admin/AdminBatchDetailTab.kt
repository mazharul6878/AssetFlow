package com.example.ui.screens.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AssetWithCount
import com.example.data.model.DailyBatchSubmissionRecord
import com.example.data.model.FoundNotInFarRecord
import com.example.ui.theme.StatusVerifiedBg
import com.example.ui.theme.StatusVerifiedFg
import com.example.ui.theme.StatusWarningBg
import com.example.ui.theme.StatusWarningFg

@Composable
fun AdminBatchDetailTab(
  batch: DailyBatchSubmissionRecord?,
  allAssets: List<AssetWithCount>,
  foundNotInFar: List<FoundNotInFarRecord>,
  onBackToQueue: () -> Unit
) {
  if (batch == null) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Icon(Icons.AutoMirrored.Filled.Assignment, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.padding(8.dp))
      Text("No batch selected for detailed inspection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      Text("Select a batch from the Review Queue (Action: 1. Review) to view asset-by-asset comparison.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    return
  }

  val matchingAssets = remember(allAssets, batch) {
    allAssets.filter { it.countingRecord?.countDate == batch.targetDate || batch.assetIds.contains(it.farAsset.assetId) }
  }

  val matchingFound = remember(foundNotInFar, batch) {
    foundNotInFar.filter { it.countDate == batch.targetDate || batch.foundNotInFarIds.contains(it.id) }
  }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("admin_batch_detail_screen"),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            OutlinedButton(onClick = onBackToQueue) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text("Back to Queue")
            }

            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
              Text(
                text = batch.status,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
              )
            }
          }

          Text(
            text = "Batch Inspection: ${batch.batchName}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Target Date: ${batch.targetDate} | Counter: ${batch.auditorDisplayName} (${batch.auditorNickname}) | Total: ${batch.totalRecordCount} items",
            style = MaterialTheme.typography.bodyMedium
          )

          if (batch.adminRemarks.isNotBlank()) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
              Column(modifier = Modifier.padding(10.dp)) {
                Text("Current Admin Remarks:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text(batch.adminRemarks, style = MaterialTheme.typography.bodySmall)
              }
            }
          }
        }
      }
    }

    item {
      Text("Counted Asset Items (${matchingAssets.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }

    items(matchingAssets, key = { it.farAsset.assetKey }) { item ->
      val cnt = item.countingRecord
      OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(item.farAsset.assetId, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            val hasVariance = cnt != null && cnt.varianceQty != 0L
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = if (hasVariance) StatusWarningBg else StatusVerifiedBg
            ) {
              Text(
                text = if (hasVariance) "Variance: ${cnt.varianceQty}" else "Qty Matched",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (hasVariance) StatusWarningFg else StatusVerifiedFg,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }
          }

          Text(item.farAsset.assetDescription, style = MaterialTheme.typography.bodySmall)
          Text(
            "FAR Qty: ${item.farAsset.farQty} | Counted Qty: ${cnt?.physicalCountQty ?: 0} | Condition: ${cnt?.condition ?: "N/A"} | Tag: ${cnt?.tagStatus ?: "N/A"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }

    if (matchingFound.isNotEmpty()) {
      item {
        Text("Found Not in FAR Items (${matchingFound.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      }

      items(matchingFound, key = { it.id }) { found ->
        OutlinedCard(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(found.searchedAssetId.ifBlank { found.id }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
              Surface(shape = RoundedCornerShape(6.dp), color = StatusWarningBg) {
                Text(
                  "Not in FAR",
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color = StatusWarningFg,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
              }
            }
            Text(found.assetDescription, style = MaterialTheme.typography.bodySmall)
            Text(
              "Remarks: ${found.remarks}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.SemiBold
            )
          }
        }
      }
    }
  }
}
