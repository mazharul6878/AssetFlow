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
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.data.model.SubmissionBatchStatus
import com.example.data.model.VerificationResultStatus
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
 * End User Panel: User Dashboard (Section 4.C.1)
 * Displays:
 * - Accessible assets / counting workload
 * - Counted records
 * - Pending records
 * - Correction-required records
 * - Daily Input Sheet status
 * Adheres strictly to NovaDesk AI light theme design (solid white surfaces, subtle borders, no glass effect).
 */
@Composable
fun UserDashboardScreen(
  allAssets: List<AssetWithCount>,
  dailyBatches: List<DailyBatchSubmissionRecord>,
  userDisplayName: String,
  userNickname: String,
  onNavigateToFindAsset: () -> Unit,
  onNavigateToDailySheets: () -> Unit,
  onNavigateToHistory: () -> Unit
) {
  val countableAssets = remember(allAssets) {
    allAssets.filter { it.farAsset.sheetOrigin != "Land, AUC & Clearing" }
  }

  val totalWorkload = countableAssets.size
  val countedCount = remember(countableAssets) {
    countableAssets.count { it.countingRecord != null && !it.countingRecord.isDraft }
  }
  val pendingCount = totalWorkload - countedCount
  val correctionRequiredCount = remember(countableAssets, dailyBatches) {
    val changesRequestedDates = dailyBatches
      .filter { it.status == SubmissionBatchStatus.CHANGES_REQUESTED }
      .map { it.countingDate }
      .toSet()
    countableAssets.count { item ->
      item.countingRecord != null &&
        (item.countingRecord.countDate in changesRequestedDates ||
          item.effectiveResult == VerificationResultStatus.CONDITION_ISSUE ||
          item.effectiveResult == VerificationResultStatus.TAG_ISSUE)
    }
  }

  val progressPercent = if (totalWorkload > 0) {
    (countedCount.toFloat() / totalWorkload.toFloat()).coerceIn(0f, 1f)
  } else 0f

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("user_dashboard_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // 1. Welcome Card
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NovaSurfaceWhite),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(NovaBorderLight))
      ) {
        Column(modifier = Modifier.padding(18.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Welcome, $userDisplayName",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = NovaTextNavy
              )
              if (userNickname.isNotBlank()) {
                Text(
                  text = "Photo Nickname: @$userNickname",
                  style = MaterialTheme.typography.labelMedium,
                  color = NovaTextMuted
                )
              }
            }

            Button(
              onClick = onNavigateToFindAsset,
              shape = RoundedCornerShape(12.dp),
              colors = ButtonDefaults.buttonColors(containerColor = NovaPrimary),
              modifier = Modifier.testTag("dashboard_find_asset_cta")
            ) {
              Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Find Asset")
            }
          }

          Spacer(modifier = Modifier.height(14.dp))

          // Progress Bar
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(
              text = "Overall Counting Progress",
              style = MaterialTheme.typography.bodySmall,
              color = NovaTextMuted
            )
            Text(
              text = "$countedCount / $totalWorkload (${(progressPercent * 100).toInt()}%)",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = NovaTextNavy
            )
          }
          Spacer(modifier = Modifier.height(6.dp))
          LinearProgressIndicator(
            progress = { progressPercent },
            modifier = Modifier
              .fillMaxWidth()
              .height(8.dp),
            color = NovaPrimary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
          )
        }
      }
    }

    // 2. Operational Metrics Cards
    item {
      Text(
        text = "Counting Workload Summary",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = NovaTextNavy
      )
    }

    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        WorkloadCard(
          label = "Accessible Assets",
          value = totalWorkload.toString(),
          icon = Icons.AutoMirrored.Filled.Assignment,
          accentColor = NovaPrimary,
          modifier = Modifier.weight(1f)
        )
        WorkloadCard(
          label = "Counted Records",
          value = countedCount.toString(),
          icon = Icons.Default.CheckCircle,
          accentColor = StatusVerifiedFg,
          modifier = Modifier.weight(1f)
        )
      }
    }

    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        WorkloadCard(
          label = "Pending Records",
          value = pendingCount.toString(),
          icon = Icons.Default.HourglassTop,
          accentColor = StatusWarningFg,
          modifier = Modifier.weight(1f)
        )
        WorkloadCard(
          label = "Correction Required",
          value = correctionRequiredCount.toString(),
          icon = Icons.Default.WarningAmber,
          accentColor = StatusExceptionFg,
          modifier = Modifier.weight(1f)
        )
      }
    }

    // 3. Daily Input Sheet Status Section
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Daily Input Sheet Status",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = NovaTextNavy
        )
        OutlinedButton(
          onClick = onNavigateToDailySheets,
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("View All", style = MaterialTheme.typography.labelSmall)
        }
      }
    }

    if (dailyBatches.isEmpty()) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = NovaSurfaceWhite),
          border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(NovaBorderLight))
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = NovaTextMuted, modifier = Modifier.size(36.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = "No Daily Batches Submitted",
              style = MaterialTheme.typography.bodyMedium,
              color = NovaTextMuted
            )
          }
        }
      }
    } else {
      items(dailyBatches.take(4), key = { it.batchId.ifBlank { it.id } }) { batch ->
        val (bg, fg) = when (batch.status) {
          SubmissionBatchStatus.PUBLISHED -> StatusVerifiedBg to StatusVerifiedFg
          SubmissionBatchStatus.APPROVED_AWAITING_MASTER_UPDATE -> StatusVerifiedBg to StatusVerifiedFg
          SubmissionBatchStatus.CHANGES_REQUESTED, SubmissionBatchStatus.REJECTED -> StatusExceptionBg to StatusExceptionFg
          SubmissionBatchStatus.UNDER_REVIEW, SubmissionBatchStatus.SUBMITTED -> StatusPendingBg to StatusPendingFg
          else -> StatusWarningBg to StatusWarningFg
        }

        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = NovaSurfaceWhite),
          border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(NovaBorderLight))
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = NovaPrimary, modifier = Modifier.size(20.dp))
              Spacer(modifier = Modifier.width(10.dp))
              Column {
                Text(
                  text = "Date: ${batch.countingDate}",
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  color = NovaTextNavy
                )
                Text(
                  text = "v${batch.versionNumber} • ${batch.recordCount} records",
                  style = MaterialTheme.typography.bodySmall,
                  color = NovaTextMuted
                )
              }
            }

            Surface(
              shape = RoundedCornerShape(8.dp),
              color = bg
            ) {
              Text(
                text = batch.status,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = fg,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun WorkloadCard(
  label: String,
  value: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  accentColor: androidx.compose.ui.graphics.Color,
  modifier: Modifier = Modifier
) {
  Card(
    modifier = modifier,
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = NovaSurfaceWhite),
    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(NovaBorderLight))
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = NovaTextMuted)
        Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
      }
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = value,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = NovaTextNavy
      )
    }
  }
}
