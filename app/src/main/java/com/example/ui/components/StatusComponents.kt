package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LabelOff
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.data.model.AuditDropdownOptions
import com.example.data.model.VerificationResultStatus
import com.example.data.storage.PhotoStorageManager
import com.example.ui.theme.StatusExceptionBg
import com.example.ui.theme.StatusExceptionFg
import com.example.ui.theme.StatusPendingBg
import com.example.ui.theme.StatusPendingFg
import com.example.ui.theme.StatusVerifiedBg
import com.example.ui.theme.StatusVerifiedFg
import com.example.ui.theme.StatusWarningBg
import com.example.ui.theme.StatusWarningFg
import java.io.File

data class StatusVisuals(
  val bgColor: Color,
  val fgColor: Color,
  val icon: ImageVector,
  val prefixLabel: String
)

fun visualsForResult(result: String): StatusVisuals {
  return when (result) {
    VerificationResultStatus.VERIFIED -> StatusVisuals(
      bgColor = StatusVerifiedBg,
      fgColor = StatusVerifiedFg,
      icon = Icons.Default.CheckCircle,
      prefixLabel = "VERIFIED"
    )
    VerificationResultStatus.REVIEWED -> StatusVisuals(
      bgColor = StatusVerifiedBg,
      fgColor = StatusVerifiedFg,
      icon = Icons.Default.Verified,
      prefixLabel = "REVIEWED"
    )
    VerificationResultStatus.COUNTED -> StatusVisuals(
      bgColor = Color(0xFFE0F2FE),
      fgColor = Color(0xFF0369A1),
      icon = Icons.Default.CheckCircle,
      prefixLabel = "COUNTED"
    )
    VerificationResultStatus.DRAFT -> StatusVisuals(
      bgColor = StatusPendingBg,
      fgColor = StatusPendingFg,
      icon = Icons.Default.Edit,
      prefixLabel = "DRAFT"
    )
    VerificationResultStatus.NOT_FOUND,
    VerificationResultStatus.QTY_SHORT -> StatusVisuals(
      bgColor = StatusExceptionBg,
      fgColor = StatusExceptionFg,
      icon = Icons.Default.Error,
      prefixLabel = "ALERT"
    )
    VerificationResultStatus.NOT_ACCESSIBLE -> StatusVisuals(
      bgColor = StatusWarningBg,
      fgColor = StatusWarningFg,
      icon = Icons.Default.Lock,
      prefixLabel = "LOCKED"
    )
    VerificationResultStatus.QTY_EXCESS,
    VerificationResultStatus.CONDITION_ISSUE,
    VerificationResultStatus.VERIFIED_CONDITION_ISSUE -> StatusVisuals(
      bgColor = StatusWarningBg,
      fgColor = StatusWarningFg,
      icon = Icons.Default.Build,
      prefixLabel = "ISSUE"
    )
    VerificationResultStatus.TAG_ISSUE,
    VerificationResultStatus.VERIFIED_TAG_ISSUE -> StatusVisuals(
      bgColor = StatusWarningBg,
      fgColor = StatusWarningFg,
      icon = Icons.AutoMirrored.Filled.LabelOff,
      prefixLabel = "TAG"
    )
    VerificationResultStatus.PENDING_VERIFICATION,
    VerificationResultStatus.PENDING_REVIEW,
    VerificationResultStatus.UNREGISTERED_OBSERVATION,
    VerificationResultStatus.NOT_IN_FAR -> StatusVisuals(
      bgColor = StatusWarningBg,
      fgColor = StatusWarningFg,
      icon = Icons.Default.Warning,
      prefixLabel = "STATUS"
    )
    else -> StatusVisuals(
      bgColor = StatusPendingBg,
      fgColor = StatusPendingFg,
      icon = Icons.Default.HourglassEmpty,
      prefixLabel = "PENDING"
    )
  }
}

/**
 * Accessible status badge that uses an icon, text prefix, and distinct color token
 * so status is never communicated by color alone.
 */
@Composable
fun ResultStatusBadge(
  result: String,
  modifier: Modifier = Modifier
) {
  val visuals = visualsForResult(result)
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(8.dp),
    color = visuals.bgColor
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Icon(
        imageVector = visuals.icon,
        contentDescription = "${visuals.prefixLabel}: $result",
        tint = visuals.fgColor,
        modifier = Modifier.size(16.dp)
      )
      Text(
        text = "[${visuals.prefixLabel}] $result",
        style = MaterialTheme.typography.labelLarge,
        color = visuals.fgColor,
        fontWeight = FontWeight.Bold
      )
    }
  }
}

/**
 * Reusable Dropdown selector supporting exact options, optional "Blank", "Not Applicable",
 * "Unknown", and optional "Other" custom text input.
 */
@Composable
fun AuditDropdownField(
  label: String,
  selectedValue: String,
  options: List<String>,
  onValueSelected: (String) -> Unit,
  modifier: Modifier = Modifier,
  supportOther: Boolean = false,
  otherValue: String = "",
  onOtherValueChange: (String) -> Unit = {},
  allowBlankSelection: Boolean = true,
  includeSpecialOptionalStates: Boolean = false,
  searchable: Boolean = false,
  testTag: String = ""
) {
  var expanded by remember { mutableStateOf(false) }
  var searchFilter by remember { mutableStateOf("") }
  val allOptions = remember(options, supportOther, includeSpecialOptionalStates) {
    val base = options.distinct().toMutableList()
    if (includeSpecialOptionalStates) {
      AuditDropdownOptions.SPECIAL_OPTIONAL_STATES.forEach { sp ->
        if (sp !in base) base.add(sp)
      }
    }
    if (supportOther && AuditDropdownOptions.OTHER_OPTION !in base) {
      base.add(AuditDropdownOptions.OTHER_OPTION)
    }
    base.toList()
  }

  Column(modifier = modifier.fillMaxWidth()) {
    Box(modifier = Modifier.fillMaxWidth()) {
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 52.dp)
          .clip(RoundedCornerShape(12.dp))
          .border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
            shape = RoundedCornerShape(12.dp)
          )
          .clickable { expanded = true }
          .let { if (testTag.isNotBlank()) it.testTag(testTag) else it },
        color = MaterialTheme.colorScheme.surface
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = label,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = selectedValue.ifBlank { "Blank (Select $label...)" },
              style = MaterialTheme.typography.bodyLarge,
              color = if (selectedValue.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
              } else {
                MaterialTheme.colorScheme.onSurface
              },
              fontWeight = if (selectedValue.isBlank()) FontWeight.Normal else FontWeight.Medium
            )
          }
          Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = "Expand $label",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      DropdownMenu(
        expanded = expanded,
        onDismissRequest = {
          expanded = false
          searchFilter = ""
        },
        modifier = Modifier
          .fillMaxWidth(0.88f)
          .background(MaterialTheme.colorScheme.surface)
      ) {
        if (searchable) {
          Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            OutlinedTextField(
              value = searchFilter,
              onValueChange = { searchFilter = it },
              label = { Text("Search $label") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth()
            )
          }
        }
        if (allowBlankSelection && searchFilter.isBlank()) {
          DropdownMenuItem(
            text = {
              Text(
                text = "— Leave Blank (Not Entered) —",
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            },
            onClick = {
              onValueSelected("")
              expanded = false
              searchFilter = ""
            }
          )
        }
        val filtered = if (searchFilter.isBlank()) {
          allOptions
        } else {
          allOptions.filter { it.contains(searchFilter.trim(), ignoreCase = true) }
        }
        if (filtered.isEmpty()) {
          DropdownMenuItem(
            text = { Text("No matching options in master list") },
            onClick = { expanded = false }
          )
        } else {
          filtered.forEach { option ->
            DropdownMenuItem(
              text = {
                Text(
                  text = option,
                  fontWeight = if (option == selectedValue) FontWeight.Bold else FontWeight.Normal
                )
              },
              onClick = {
                onValueSelected(option)
                expanded = false
                searchFilter = ""
              }
            )
          }
        }
      }
    }

    if (supportOther && selectedValue == AuditDropdownOptions.OTHER_OPTION) {
      Spacer(modifier = Modifier.height(8.dp))
      OutlinedTextField(
        value = otherValue,
        onValueChange = onOtherValueChange,
        label = { Text("Specify Custom $label (Required) *") },
        isError = otherValue.isBlank(),
        supportingText = {
          if (otherValue.isBlank()) {
            Text("Required when 'Other' is selected")
          }
        },
        singleLine = true,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("${testTag}_other_input")
      )
    }
  }
}

/**
 * Camera-Based Photo Evidence section (Requirement 5):
 * - Supports "Take Photo" via device camera with runtime CAMERA permission request.
 * - Supports "Choose Photo" via Android Photo Picker.
 * - Allows one or more photographs per record.
 * - Shows image previews, verified local storage status, and per-photo Replace / Remove actions.
 * - Preserves unsaved form data if permissions are denied or capture is cancelled.
 */
@Composable
fun PhotoEvidenceSection(
  recordKey: String,
  photoRef: String,
  onPhotoRefChange: (String) -> Unit,
  userProfile: com.example.data.model.UserProfile? = null,
  onPromptSetNickname: (() -> Unit)? = null,
  photoStatuses: Map<String, String> = emptyMap(),
  onRetryUpload: ((String) -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val photoPaths = remember(photoRef) { PhotoStorageManager.parsePhotoList(photoRef) }
  var replacingIndex by remember { mutableIntStateOf(-1) }
  var photoStatusMessage by remember { mutableStateOf<String?>(null) }
  var isPhotoError by remember { mutableStateOf(false) }

  // Camera capture launcher saving directly to app-private filesDir/audit_photos
  val cameraPreviewLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.TakePicturePreview()
  ) { bitmap: Bitmap? ->
    if (bitmap != null) {
      val saveRes = PhotoStorageManager.saveCameraBitmap(context, recordKey, bitmap)
      saveRes.onSuccess { savedPath ->
        val updated = if (replacingIndex >= 0) {
          PhotoStorageManager.replacePhotoAt(photoRef, replacingIndex, savedPath)
        } else {
          PhotoStorageManager.appendPhoto(photoRef, savedPath)
        }
        replacingIndex = -1
        onPhotoRefChange(updated)
        isPhotoError = false
        photoStatusMessage = "Camera photo captured and saved to local audit storage."
      }.onFailure { err ->
        replacingIndex = -1
        isPhotoError = true
        photoStatusMessage = "Photo save failed: ${err.localizedMessage}. Unsaved form data is preserved."
      }
    } else {
      replacingIndex = -1
      isPhotoError = false
      photoStatusMessage = "Camera capture cancelled. Form data preserved."
    }
  }

  // Runtime permission launcher for Manifest.permission.CAMERA
  val cameraPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) {
      isPhotoError = false
      cameraPreviewLauncher.launch(null)
    } else {
      replacingIndex = -1
      isPhotoError = true
      photoStatusMessage =
        "Camera permission was denied. Unsaved form data is preserved. Grant permission or use 'Choose Photo'."
    }
  }

  // Zero-permission Android Photo Picker
  val photoPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia()
  ) { uri ->
    if (uri != null) {
      val saveRes = PhotoStorageManager.savePickedPhotoUri(context, recordKey, uri)
      saveRes.onSuccess { savedPath ->
        val updated = if (replacingIndex >= 0) {
          PhotoStorageManager.replacePhotoAt(photoRef, replacingIndex, savedPath)
        } else {
          PhotoStorageManager.appendPhoto(photoRef, savedPath)
        }
        replacingIndex = -1
        onPhotoRefChange(updated)
        isPhotoError = false
        photoStatusMessage = "Photo copied and verified in local audit storage."
      }.onFailure { err ->
        replacingIndex = -1
        isPhotoError = true
        photoStatusMessage = "Failed to import photo: ${err.localizedMessage}. Unsaved form data is preserved."
      }
    } else {
      replacingIndex = -1
    }
  }

  fun triggerCameraCapture(targetReplaceIndex: Int = -1) {
    if (userProfile != null && !userProfile.hasValidNickname) {
      isPhotoError = true
      photoStatusMessage = "Please configure your photo filename nickname in Profile (e.g. 'abed') before capturing photos."
      onPromptSetNickname?.invoke()
      return
    }
    replacingIndex = targetReplaceIndex
    val hasPerm = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    if (hasPerm) {
      cameraPreviewLauncher.launch(null)
    } else {
      cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
  }

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    OutlinedTextField(
      value = photoRef,
      onValueChange = onPhotoRefChange,
      label = { Text("24. Photo Ref (Persisted File Reference(s) — Optional)") },
      supportingText = {
        Text("Use 'Take Photo' or 'Choose Photo' to attach one or more photographs, or enter a reference.")
      },
      singleLine = true,
      modifier = Modifier
        .fillMaxWidth()
        .testTag("field_photo_ref_input")
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      OutlinedButton(
        onClick = { triggerCameraCapture(-1) },
        modifier = Modifier
          .weight(1f)
          .testTag("capture_photo_button"),
        shape = RoundedCornerShape(12.dp)
      ) {
        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Take Photo")
      }
      OutlinedButton(
        onClick = {
          if (userProfile != null && !userProfile.hasValidNickname) {
            isPhotoError = true
            photoStatusMessage = "Please configure your photo filename nickname in Profile (e.g. 'abed') before selecting photos."
            onPromptSetNickname?.invoke()
            return@OutlinedButton
          }
          replacingIndex = -1
          photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
          )
        },
        modifier = Modifier
          .weight(1f)
          .testTag("pick_photo_button"),
        shape = RoundedCornerShape(12.dp)
      ) {
        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Choose Photo")
      }
    }

    if (!photoStatusMessage.isNullOrBlank()) {
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isPhotoError) StatusWarningBg else StatusVerifiedBg,
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          text = photoStatusMessage!!,
          style = MaterialTheme.typography.bodyMedium,
          color = if (isPhotoError) StatusWarningFg else StatusVerifiedFg,
          modifier = Modifier.padding(10.dp)
        )
      }
    }

    if (photoPaths.isNotEmpty()) {
      Text(
        text = "Attached Photographs (${photoPaths.size}) — Preview, Replace, or Remove:",
        style = MaterialTheme.typography.labelLarge
      )
      photoPaths.forEachIndexed { idx, path ->
        val fileObj = File(path)
        val existsLocally = fileObj.exists() && fileObj.length() > 0L
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("photo_preview_card_$idx")
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Surface(
              modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp)),
              color = MaterialTheme.colorScheme.surface
            ) {
              AsyncImage(
                model = if (existsLocally) fileObj else path,
                contentDescription = "Attached Asset Photograph ${idx + 1}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp)
              )
            }
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Photo #${idx + 1}: ${fileObj.name.ifBlank { path }}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
              )
              Text(
                text = if (existsLocally) {
                  "Verified Local File (${fileObj.length() / 1024} KB)"
                } else {
                  "Reference: $path"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (existsLocally) StatusVerifiedFg else MaterialTheme.colorScheme.onSurfaceVariant
              )

              val rawStatus = photoStatuses[fileObj.name] ?: photoStatuses[path]
              val statusText = when {
                (rawStatus == null || rawStatus == "Pending Sync") && existsLocally -> "Saved Locally"
                rawStatus != null -> rawStatus
                existsLocally -> "Saved Locally"
                else -> "Pending Sync"
              }
              val (badgeBg, badgeFg) = when (statusText) {
                "Uploaded" -> StatusVerifiedBg to StatusVerifiedFg
                "Uploading" -> Color(0xFFE0F2FE) to Color(0xFF0369A1)
                "Saved Locally" -> StatusPendingBg to StatusPendingFg
                "Waiting for Internet" -> StatusWarningBg to StatusWarningFg
                "Metadata Sync Pending" -> StatusWarningBg to StatusWarningFg
                "Sign-in Required", "Drive Access Required", "Upload Failed — Retry Available" -> StatusExceptionBg to StatusExceptionFg
                else -> StatusPendingBg to StatusPendingFg
              }
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = badgeBg,
                modifier = Modifier
                  .padding(top = 4.dp)
                  .testTag("photo_status_badge_$idx")
              ) {
                Text(
                  text = statusText,
                  style = MaterialTheme.typography.labelSmall,
                  color = badgeFg,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
              }
              if (statusText == "Upload Failed — Retry Available" && onRetryUpload != null) {
                OutlinedButton(
                  onClick = { onRetryUpload(fileObj.name) },
                  modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag("retry_upload_button_$idx")
                ) {
                  Text("Retry Upload", style = MaterialTheme.typography.labelSmall)
                }
              }
            }
            IconButton(
              onClick = { triggerCameraCapture(idx) },
              modifier = Modifier.testTag("replace_photo_button_$idx")
            ) {
              Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = "Replace Photo ${idx + 1}"
              )
            }
            IconButton(
              onClick = {
                val updated = PhotoStorageManager.removePhotoAt(photoRef, idx)
                onPhotoRefChange(updated)
                photoStatusMessage = "Removed Photo #${idx + 1}."
                isPhotoError = false
              },
              modifier = Modifier.testTag("remove_photo_button_$idx")
            ) {
              Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = "Remove Photo ${idx + 1}",
                tint = StatusExceptionFg
              )
            }
          }
        }
      }
    }
  }
}
