package com.example.ui.screens.admin

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.offline.OfflinePhotoQueueEntity
import java.io.File

/**
 * UI representation of an audit photo evidence entry for administrative inspection.
 */
data class EvidenceItemUiModel(
  val photoId: String,
  val assetId: String,
  val recordKey: String,
  val clientId: String,
  val fiscalYearId: String,
  val localFilePath: String,
  val filename: String,
  val syncStatus: String,
  val driveFileId: String,
  val driveWebViewLink: String,
  val sha256Hash: String,
  val capturedAt: String,
  val uploadedAt: String,
  val errorMessage: String,
  val retryCount: Int
) {
  val hasDriveLink: Boolean get() = driveWebViewLink.isNotBlank()
  val isUploaded: Boolean get() = syncStatus.equals("Uploaded", ignoreCase = true)
  val isFailed: Boolean get() = syncStatus.contains("Failed", ignoreCase = true)
}

/**
 * Formatting utilities for Admin evidence presentation.
 */
object EvidenceFormatting {
  fun formatSyncStatusLabel(status: String): String {
    return when {
      status.equals("Uploaded", ignoreCase = true) -> "Uploaded to Drive"
      status.equals("Uploading", ignoreCase = true) -> "Uploading..."
      status.contains("Failed", ignoreCase = true) -> "Upload Failed — Retry Available"
      status.equals("Saved Locally", ignoreCase = true) || status.equals("Pending Sync", ignoreCase = true) -> "Saved Locally — Pending Upload"
      else -> status
    }
  }

  fun truncateHash(hash: String, length: Int = 12): String {
    if (hash.isBlank()) return "Pending Hash"
    return if (hash.length > length) "${hash.take(length)}..." else hash
  }

  fun formatTimestamp(isoString: String): String {
    if (isoString.isBlank()) return "Not uploaded"
    return try {
      val instant = java.time.Instant.parse(isoString)
      val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(java.time.ZoneId.systemDefault())
      formatter.format(instant)
    } catch (_: Exception) {
      isoString
    }
  }

  fun mapEntityToUiModel(entity: OfflinePhotoQueueEntity, sha256: String = ""): EvidenceItemUiModel {
    val hash = sha256.ifBlank {
      val file = File(entity.localFilePath)
      if (file.exists()) {
        com.example.data.repository.PhotoEvidenceRepository.computeFileSha256(file)
      } else ""
    }
    return EvidenceItemUiModel(
      photoId = entity.photoId,
      assetId = entity.assetOrObsId,
      recordKey = entity.recordKey,
      clientId = entity.clientId,
      fiscalYearId = entity.fiscalYearId,
      localFilePath = entity.localFilePath,
      filename = entity.targetDriveFileName,
      syncStatus = entity.uploadStatus,
      driveFileId = entity.driveFileId,
      driveWebViewLink = entity.driveWebViewLink,
      sha256Hash = hash,
      capturedAt = entity.capturedAtIso,
      uploadedAt = entity.uploadedAtIso,
      errorMessage = entity.errorMessage,
      retryCount = entity.retryCount
    )
  }
}

/**
 * Additive Admin Evidence Viewer component allowing administrators to inspect photo evidence,
 * view Drive links, inspect SHA-256 integrity hashes, and retry pending/failed uploads
 * without altering the protected AdminReviewQueueTab module.
 */
@Composable
fun AdminEvidenceViewer(
  items: List<EvidenceItemUiModel>,
  modifier: Modifier = Modifier,
  title: String = "Audit Photo Evidence",
  onBack: (() -> Unit)? = null,
  onRetry: ((String) -> Unit)? = null
) {
  var selectedItem by remember { mutableStateOf<EvidenceItemUiModel?>(null) }

  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(16.dp)
      .testTag("admin_evidence_viewer_container")
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 16.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (onBack != null) {
        IconButton(
          onClick = onBack,
          modifier = Modifier.testTag("admin_evidence_back_button")
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back"
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
      }
      Column {
        Text(
          text = title,
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.testTag("admin_evidence_title")
        )
        Text(
          text = "${items.size} evidence photos recorded",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("admin_evidence_subtitle")
        )
      }
    }

    if (items.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .testTag("admin_evidence_empty_state"),
        contentAlignment = Alignment.Center
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
            imageVector = Icons.Default.Image,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "No photo evidence available for this scope.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    } else {
      LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
      ) {
        items(items, key = { it.photoId }) { item ->
          EvidenceItemCard(
            item = item,
            onClick = { selectedItem = item },
            onRetry = onRetry
          )
        }
      }
    }
  }

  // Evidence Detail Dialog
  selectedItem?.let { item ->
    EvidenceDetailDialog(
      item = item,
      onDismiss = { selectedItem = null },
      onRetry = onRetry
    )
  }
}

@Composable
fun EvidenceItemCard(
  item: EvidenceItemUiModel,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onRetry: ((String) -> Unit)? = null
) {
  val context = LocalContext.current
  val localFile = remember(item.localFilePath) { File(item.localFilePath) }

  ElevatedCard(
    modifier = modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .testTag("evidence_card_${item.photoId}"),
    shape = RoundedCornerShape(12.dp),
    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Thumbnail
      Box(
        modifier = Modifier
          .size(72.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
      ) {
        if (localFile.exists()) {
          AsyncImage(
            model = localFile,
            contentDescription = "Photo thumbnail",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
          )
        } else {
          Icon(
            imageVector = Icons.Default.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      Spacer(modifier = Modifier.width(12.dp))

      // Metadata
      Column(
        modifier = Modifier.weight(1f)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = "Asset: ${item.assetId}",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("evidence_asset_id_${item.photoId}")
          )
          StatusBadge(status = item.syncStatus)
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
          text = "ID: ${item.photoId}",
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )

        Text(
          text = "SHA-256: ${EvidenceFormatting.truncateHash(item.sha256Hash)}",
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (item.uploadedAt.isNotBlank()) {
          Text(
            text = "Uploaded: ${EvidenceFormatting.formatTimestamp(item.uploadedAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      // Actions
      if (item.hasDriveLink) {
        IconButton(
          onClick = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.driveWebViewLink))
            context.startActivity(intent)
          },
          modifier = Modifier.testTag("evidence_drive_link_btn_${item.photoId}")
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = "Open in Drive",
            tint = MaterialTheme.colorScheme.primary
          )
        }
      } else if (item.isFailed && onRetry != null) {
        IconButton(
          onClick = { onRetry(item.photoId) },
          modifier = Modifier.testTag("evidence_retry_btn_${item.photoId}")
        ) {
          Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "Retry Upload",
            tint = MaterialTheme.colorScheme.error
          )
        }
      }
    }
  }
}

@Composable
fun StatusBadge(status: String, modifier: Modifier = Modifier) {
  val (bgColor, textColor, icon) = when {
    status.equals("Uploaded", ignoreCase = true) -> Triple(
      Color(0xFFE8F5E9),
      Color(0xFF2E7D32),
      Icons.Default.CloudDone
    )
    status.equals("Uploading", ignoreCase = true) -> Triple(
      Color(0xFFE3F2FD),
      Color(0xFF1565C0),
      Icons.Default.CloudQueue
    )
    status.contains("Failed", ignoreCase = true) -> Triple(
      Color(0xFFFFEBEE),
      Color(0xFFC62828),
      Icons.Default.CloudOff
    )
    else -> Triple(
      Color(0xFFFFF3E0),
      Color(0xFFEF6C00),
      Icons.Default.CheckCircle
    )
  }

  Surface(
    color = bgColor,
    shape = RoundedCornerShape(16.dp),
    modifier = modifier
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(14.dp),
        tint = textColor
      )
      Spacer(modifier = Modifier.width(4.dp))
      Text(
        text = EvidenceFormatting.formatSyncStatusLabel(status),
        color = textColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
      )
    }
  }
}

@Composable
fun EvidenceDetailDialog(
  item: EvidenceItemUiModel,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  onRetry: ((String) -> Unit)? = null
) {
  val context = LocalContext.current
  val localFile = remember(item.localFilePath) { File(item.localFilePath) }

  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier.testTag("evidence_detail_dialog"),
    title = {
      Text(
        text = "Evidence Details",
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleLarge
      )
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 4.dp)
      ) {
        // Large Preview
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
          contentAlignment = Alignment.Center
        ) {
          if (localFile.exists()) {
            AsyncImage(
              model = localFile,
              contentDescription = "Full preview",
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Fit
            )
          } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
              )
              Text(
                text = "Preview available on Google Drive",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        DetailRow(label = "Asset ID", value = item.assetId)
        DetailRow(label = "Photo ID", value = item.photoId)
        DetailRow(label = "Filename", value = item.filename)
        DetailRow(label = "Sync Status", value = EvidenceFormatting.formatSyncStatusLabel(item.syncStatus))
        DetailRow(label = "SHA-256", value = item.sha256Hash.ifBlank { "Not calculated" }, isMonospace = true)
        DetailRow(label = "Captured", value = EvidenceFormatting.formatTimestamp(item.capturedAt))
        DetailRow(label = "Uploaded", value = EvidenceFormatting.formatTimestamp(item.uploadedAt))

        if (item.driveFileId.isNotBlank()) {
          DetailRow(label = "Drive File ID", value = item.driveFileId, isMonospace = true)
        }

        if (item.errorMessage.isNotBlank()) {
          DetailRow(label = "Error Message", value = item.errorMessage, isError = true)
        }
      }
    },
    confirmButton = {
      Row {
        if (item.hasDriveLink) {
          Button(
            onClick = {
              val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.driveWebViewLink))
              context.startActivity(intent)
            },
            modifier = Modifier.testTag("dialog_open_drive_button")
          ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Open in Drive")
          }
          Spacer(modifier = Modifier.width(8.dp))
        }
        if (item.isFailed && onRetry != null) {
          Button(
            onClick = {
              onRetry(item.photoId)
              onDismiss()
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.testTag("dialog_retry_button")
          ) {
            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Retry")
          }
          Spacer(modifier = Modifier.width(8.dp))
        }
        TextButton(
          onClick = onDismiss,
          modifier = Modifier.testTag("dialog_close_button")
        ) {
          Text("Close")
        }
      }
    }
  )
}

@Composable
private fun DetailRow(
  label: String,
  value: String,
  isMonospace: Boolean = false,
  isError: Boolean = false
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 3.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.Top
  ) {
    Text(
      text = "$label:",
      style = MaterialTheme.typography.bodySmall,
      fontWeight = FontWeight.Medium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.width(100.dp)
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodySmall,
      fontWeight = if (isError) FontWeight.Bold else FontWeight.Normal,
      fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
      color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f)
    )
  }
}
