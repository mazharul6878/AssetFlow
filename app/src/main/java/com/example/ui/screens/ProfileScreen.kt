package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import com.example.data.model.ClientAssignmentRecord
import com.example.data.model.ClientRecord
import com.example.data.model.UserProfile

/**
 * End User (Auditor) Workspace Tab 4 — Profile:
 * - Name, Email, Photo Nickname
 * - Assigned Role (read-only)
 * - Assigned Clients (view-only list)
 * - Switch Client & Fiscal Year button
 * - End User Logout button
 */
@Composable
fun ProfileScreen(
  userProfile: UserProfile,
  activeClient: ClientRecord? = null,
  activeFiscalYearId: String = "2026_27",
  assignedClients: List<ClientAssignmentRecord> = emptyList(),
  onSaveProfile: (displayName: String, role: String, nickname: String) -> Unit,
  onSwitchClientWorkspace: () -> Unit = {},
  onSignOut: () -> Unit
) {
  var nameInput by remember(userProfile.displayName, userProfile.fullName) {
    mutableStateOf(userProfile.effectiveDisplayName())
  }
  var nicknameInput by remember(userProfile.nickname) {
    mutableStateOf(userProfile.effectiveNickname())
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp)
      .testTag("auditor_profile_screen"),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    Text(
      text = "My Profile",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold
    )

    ElevatedCard(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp)
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = userProfile.effectiveDisplayName(),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = userProfile.email.ifBlank { "Authenticated Account" },
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }

          Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer
          ) {
            Text(
              text = userProfile.role,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
          }
        }

        HorizontalDivider()

        OutlinedTextField(
          value = nameInput,
          onValueChange = { nameInput = it },
          label = { Text("Full Name *") },
          leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_name_input")
        )

        OutlinedTextField(
          value = nicknameInput,
          onValueChange = { nicknameInput = it },
          label = { Text("Photo Nickname *") },
          leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null) },
          supportingText = {
            val preview = UserProfile.sanitizeNickname(nicknameInput.ifBlank { nameInput })
            Text("Google Drive Photo Naming: ${preview}_FA1001.jpg")
          },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_nickname_input")
        )

        Button(
          onClick = {
            if (nameInput.isNotBlank()) {
              onSaveProfile(
                nameInput.trim(),
                userProfile.role,
                UserProfile.sanitizeNickname(nicknameInput.ifBlank { nameInput })
              )
            }
          },
          enabled = nameInput.isNotBlank(),
          modifier = Modifier
            .align(Alignment.End)
            .testTag("save_profile_button")
        ) {
          Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text("Save Profile")
        }
      }
    }

    // Assigned Clients (View Only) & Switch Client card
    OutlinedCard(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
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
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Business, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
              text = "Assigned Clients (View Only)",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
          }

          OutlinedButton(
            onClick = onSwitchClientWorkspace,
            modifier = Modifier.testTag("profile_switch_client_button")
          ) {
            Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Switch Client")
          }
        }

        if (activeClient != null) {
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(
              text = "Active Workspace: ${activeClient.clientName} (${activeFiscalYearId.replace("_", "–")})",
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.padding(12.dp)
            )
          }
        }

        val myAssignments = assignedClients.filter {
          (it.auditorUid == userProfile.userId ||
            (userProfile.email.isNotBlank() && it.auditorEmail.equals(userProfile.email, ignoreCase = true))) &&
            it.status == "Assigned"
        }

        if (myAssignments.isEmpty() && userProfile.assignedClientIds.isEmpty()) {
          Text(
            text = "No clients currently assigned.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        } else {
          myAssignments.forEach { assignment ->
            Text(
              text = "• ${assignment.clientName.ifBlank { assignment.clientId }} — Fiscal Years: ${assignment.fiscalYearIds.joinToString { it.replace("_", "–") }}",
              style = MaterialTheme.typography.bodySmall
            )
          }
        }
      }
    }

    Spacer(Modifier.height(8.dp))

    Button(
      onClick = onSignOut,
      colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
      modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .testTag("end_user_logout_button"),
      shape = RoundedCornerShape(12.dp)
    ) {
      Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
      Spacer(Modifier.width(8.dp))
      Text("End User Logout", fontWeight = FontWeight.Bold)
    }
  }
}
