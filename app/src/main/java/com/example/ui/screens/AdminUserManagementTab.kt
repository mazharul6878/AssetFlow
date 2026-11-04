package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.Switch
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
import com.example.data.model.DeletionImpactSummary
import com.example.data.model.RoleAssignment
import com.example.data.model.UserRole
import com.example.data.repository.AuditRepository

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminUserManagementTab(
  isEffectiveAdmin: Boolean,
  isEffectiveOwner: Boolean = false,
  currentUserId: String = "",
  roleAssignments: List<RoleAssignment>,
  deletionImpactPreview: DeletionImpactSummary? = null,
  onSaveRoleAssignment: (RoleAssignment) -> Unit,
  onUpdateUserPermissions: (String, Boolean, Boolean, Boolean) -> Unit,
  onPreviewUserDeletion: (targetUid: String) -> Unit = {},
  onExecutePermanentUserDeletion: (targetUid: String, confirmationText: String) -> Unit = { _, _ -> },
  onClearDeletionPreview: () -> Unit = {}
) {
  var memberUidInput by remember { mutableStateOf("") }
  var memberEmailInput by remember { mutableStateOf("") }
  var memberNameInput by remember { mutableStateOf("") }
  var selectedRole by remember { mutableStateOf(UserRole.AUDITOR) }
  var hasReviewAuthInput by remember { mutableStateOf(false) }
  var canPublishInput by remember { mutableStateOf(false) }
  var canSyncDriveInput by remember { mutableStateOf(false) }

  var userToDelete by remember { mutableStateOf<RoleAssignment?>(null) }
  var deleteConfirmationInput by remember { mutableStateOf("") }

  val assignableRoles = remember(isEffectiveOwner) {
    if (isEffectiveOwner) {
      listOf(UserRole.AUDITOR, UserRole.LEAD_AUDITOR, UserRole.REVIEWER, UserRole.ADMIN)
    } else {
      listOf(UserRole.AUDITOR, UserRole.LEAD_AUDITOR, UserRole.REVIEWER)
    }
  }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("admin_user_management_screen"),
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
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.ManageAccounts, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
              "User & Role Management (Owner-Controlled Admin Governance)",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
          }

          Text(
            text = if (isEffectiveOwner) {
              "Signed in as System Owner (${AuditRepository.DESIGNATED_OWNER_EMAIL}). You can promote/demote Admin accounts, manage Auditors, or permanently delete users."
            } else {
              "Signed in as Admin. You can create/manage Auditors and Reviewers. Only the System Owner (${AuditRepository.DESIGNATED_OWNER_EMAIL}) can promote or modify Admin accounts."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = memberUidInput,
              onValueChange = { memberUidInput = it },
              label = { Text("Account UID *") },
              enabled = isEffectiveAdmin,
              singleLine = true,
              modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
              value = memberNameInput,
              onValueChange = { memberNameInput = it },
              label = { Text("Full Name *") },
              enabled = isEffectiveAdmin,
              singleLine = true,
              modifier = Modifier.weight(1f)
            )
          }

          OutlinedTextField(
            value = memberEmailInput,
            onValueChange = { memberEmailInput = it },
            label = { Text("User Email *") },
            enabled = isEffectiveAdmin,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )

          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            assignableRoles.forEach { roleOpt ->
              FilterChip(
                selected = selectedRole == roleOpt,
                onClick = {
                  if (isEffectiveAdmin) {
                    selectedRole = roleOpt
                    if (roleOpt == UserRole.REVIEWER || roleOpt == UserRole.ADMIN) {
                      hasReviewAuthInput = true
                    }
                    if (roleOpt == UserRole.ADMIN) {
                      canPublishInput = true
                      canSyncDriveInput = true
                    }
                  }
                },
                label = { Text(roleOpt) }
              )
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text("Review Authority", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
              Text("Can inspect batches and send remarks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
              checked = hasReviewAuthInput,
              onCheckedChange = { if (isEffectiveAdmin) hasReviewAuthInput = it },
              enabled = isEffectiveAdmin
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text("Drive Synchronization Permission", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
              Text("Can provision folders and retry Drive uploads", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
              checked = canSyncDriveInput,
              onCheckedChange = { if (isEffectiveAdmin) canSyncDriveInput = it },
              enabled = isEffectiveAdmin
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text("Master Publication Permission", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
              Text("Can approve batches to update Master Excel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
              checked = canPublishInput,
              onCheckedChange = { if (isEffectiveAdmin) canPublishInput = it },
              enabled = isEffectiveAdmin
            )
          }

          Button(
            onClick = {
              if (memberUidInput.isNotBlank()) {
                onSaveRoleAssignment(
                  RoleAssignment(
                    id = memberUidInput.trim(),
                    memberUid = memberUidInput.trim(),
                    email = memberEmailInput.trim(),
                    displayName = memberNameInput.trim(),
                    fullName = memberNameInput.trim(),
                    role = selectedRole,
                    accountStatus = "Active",
                    hasReviewAuthority = hasReviewAuthInput,
                    canPublish = canPublishInput,
                    canSyncDrive = canSyncDriveInput
                  )
                )
                memberUidInput = ""
                memberEmailInput = ""
                memberNameInput = ""
                hasReviewAuthInput = false
                canPublishInput = false
                canSyncDriveInput = false
              }
            },
            enabled = isEffectiveAdmin && memberUidInput.isNotBlank() && memberNameInput.isNotBlank(),
            modifier = Modifier
              .align(Alignment.End)
              .testTag("save_user_assignment_button")
          ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add / Update User")
          }
        }
      }
    }

    item {
      Text(
        "Registered Accounts (${roleAssignments.size})",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
      )
    }

    items(roleAssignments, key = { it.id.ifBlank { it.memberUid } }) { ra ->
      val targetUid = ra.memberUid.ifBlank { ra.id }
      val isTargetOwner = ra.role == UserRole.OWNER || ra.email.equals(AuditRepository.DESIGNATED_OWNER_EMAIL, ignoreCase = true)
      val isTargetSelf = targetUid == currentUserId
      val canModifyTarget = isEffectiveAdmin && !isTargetOwner && (ra.role != UserRole.ADMIN || isEffectiveOwner)

      var isReviewerAuth by remember(ra) { mutableStateOf(ra.hasReviewAuthority || ra.role == UserRole.REVIEWER || ra.role == UserRole.ADMIN || ra.role == UserRole.OWNER) }
      var isPublishPerm by remember(ra) { mutableStateOf(ra.canPublish || ra.role == UserRole.ADMIN || ra.role == UserRole.OWNER) }
      var isDriveSyncPerm by remember(ra) { mutableStateOf(ra.canSyncDrive || ra.role == UserRole.ADMIN || ra.role == UserRole.OWNER) }

      OutlinedCard(
        modifier = Modifier
          .fillMaxWidth()
          .testTag("user_card_$targetUid"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                "${ra.fullName.ifBlank { ra.displayName }.ifBlank { ra.email }} (${ra.role})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
              )
              Text(
                "UID: $targetUid | Email: ${ra.email} | Status: ${ra.accountStatus}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              if (ra.assignedClientIds.isNotEmpty()) {
                Text(
                  "Assigned Clients: ${ra.assignedClientIds.joinToString()}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary
                )
              }
            }

            if (isTargetOwner) {
              AssistChip(
                onClick = {},
                leadingIcon = { Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("System Owner") }
              )
            }
          }

          HorizontalDivider()

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text("Review Authority", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
              Text(if (isReviewerAuth) "Granted" else "Revoked", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
              checked = isReviewerAuth,
              onCheckedChange = { newVal ->
                if (canModifyTarget) {
                  isReviewerAuth = newVal
                  onUpdateUserPermissions(targetUid, newVal, isPublishPerm, isDriveSyncPerm)
                }
              },
              enabled = canModifyTarget
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text("Publication Permission", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
              Text(if (isPublishPerm) "Granted" else "Revoked", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
              checked = isPublishPerm,
              onCheckedChange = { newVal ->
                if (canModifyTarget) {
                  isPublishPerm = newVal
                  onUpdateUserPermissions(targetUid, isReviewerAuth, newVal, isDriveSyncPerm)
                }
              },
              enabled = canModifyTarget
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text("Drive Synchronization Permission", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
              Text(if (isDriveSyncPerm) "Granted" else "Revoked", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
              checked = isDriveSyncPerm,
              onCheckedChange = { newVal ->
                if (canModifyTarget) {
                  isDriveSyncPerm = newVal
                  onUpdateUserPermissions(targetUid, isReviewerAuth, isPublishPerm, newVal)
                }
              },
              enabled = canModifyTarget
            )
          }

          if (canModifyTarget && !isTargetSelf) {
            HorizontalDivider()
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              val isDeactivated = ra.accountStatus.equals("Deactivated", ignoreCase = true)
              OutlinedButton(
                onClick = {
                  onSaveRoleAssignment(
                    ra.copy(accountStatus = if (isDeactivated) "Active" else "Deactivated")
                  )
                },
                modifier = Modifier.testTag("toggle_deactivate_user_$targetUid")
              ) {
                Text(if (isDeactivated) "Reactivate User" else "Deactivate User")
              }

              OutlinedButton(
                onClick = {
                  userToDelete = ra
                  deleteConfirmationInput = ""
                  onPreviewUserDeletion(targetUid)
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("delete_user_button_$targetUid")
              ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Delete User")
              }
            }
          }
        }
      }
    }
  }

  // Permanent User Deletion Confirmation & Impact Preview Dialog
  userToDelete?.let { targetUser ->
    val targetUid = targetUser.memberUid.ifBlank { targetUser.id }
    val targetIdentifier = targetUser.email.ifBlank { targetUser.fullName.ifBlank { targetUid } }

    AlertDialog(
      onDismissRequest = {
        userToDelete = null
        onClearDeletionPreview()
      },
      title = {
        Text("Permanent User Deletion", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = "You are about to permanently delete user '${targetUser.fullName.ifBlank { targetUser.displayName }}' ($targetIdentifier).",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
          )
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("Impact Preview:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
              if (deletionImpactPreview != null && deletionImpactPreview.targetId == targetUid) {
                Text("• Client Assignments to revoke: ${deletionImpactPreview.assignmentsCount}", style = MaterialTheme.typography.bodySmall)
                Text("• Access Requests to remove: ${deletionImpactPreview.accessRequestsCount}", style = MaterialTheme.typography.bodySmall)
                Text("• Unsubmitted Drafts to permanently delete: ${deletionImpactPreview.userDraftRecordsCount}", style = MaterialTheme.typography.bodySmall)
                Text("• Submitted/Approved Audit Records to pseudonymize ('Deleted User [#hash]'): ${deletionImpactPreview.userSubmittedHistoricalRecordsCount}", style = MaterialTheme.typography.bodySmall)
              } else {
                Text("Calculating associated records...", style = MaterialTheme.typography.bodySmall)
              }
              Text("• Writes a permanent /deleted_identities tombstone blocking future access.", style = MaterialTheme.typography.bodySmall)
            }
          }

          Text(
            text = "Tip: If you only want to suspend login temporarily while keeping profile metadata intact, use 'Deactivate User' instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          OutlinedTextField(
            value = deleteConfirmationInput,
            onValueChange = { deleteConfirmationInput = it },
            label = { Text("Type '$targetIdentifier' or 'DELETE' to confirm *") },
            singleLine = true,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("confirm_delete_user_input")
          )
        }
      },
      confirmButton = {
        val confirmValid = deleteConfirmationInput.trim().equals(targetIdentifier, ignoreCase = true) ||
          deleteConfirmationInput.trim().equals("DELETE", ignoreCase = true)
        Button(
          onClick = {
            onExecutePermanentUserDeletion(targetUid, deleteConfirmationInput.trim())
            userToDelete = null
          },
          enabled = confirmValid,
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          modifier = Modifier.testTag("confirm_permanent_delete_user_button")
        ) {
          Text("Permanently Delete User")
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            userToDelete = null
            onClearDeletionPreview()
          }
        ) {
          Text("Cancel")
        }
      }
    )
  }
}
