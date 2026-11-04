package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.model.AccessRequestRecord
import com.example.data.model.ClientAssignmentRecord
import com.example.data.model.ClientRecord
import com.example.data.model.FiscalYearRecord
import com.example.data.model.UserProfile
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaBorderLight
import com.example.ui.theme.NovaPrimary
import com.example.ui.theme.NovaPrimarySubtle
import com.example.ui.theme.NovaSurfaceMuted
import com.example.ui.theme.NovaSurfaceWhite
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextNavy
import com.example.ui.theme.StatusExceptionBg
import com.example.ui.theme.StatusExceptionFg
import com.example.ui.theme.StatusVerifiedBg
import com.example.ui.theme.StatusVerifiedFg

/**
 * Post-Login Client & Fiscal Year Selection Screen matching the AssetFlow UI/UX Flowchart:
 * - Admin / Owner: can select any active client, create a new client, or add a fiscal year.
 * - End User (Auditor): sees assigned clients; if unassigned (or selecting an unassigned client),
 *   displays the inline "Access Denied (End User) — You are not assigned to this client" card
 *   with a "Request Access to Admin" action (no recurring modal dialog loops).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ClientSelectionScreen(
  userProfile: UserProfile,
  isEffectiveAdmin: Boolean,
  clients: List<ClientRecord>,
  fiscalYears: List<FiscalYearRecord>,
  clientAssignments: List<ClientAssignmentRecord>,
  accessRequests: List<AccessRequestRecord>,
  isCreatingClient: Boolean = false,
  clientCreationError: String? = null,
  clientCreationSuccess: String? = null,
  onClearCreationMessages: () -> Unit = {},
  onContinueWithWorkspace: (clientId: String, fiscalYearId: String) -> Unit,
  onCreateClient: (
    clientName: String,
    clientCode: String,
    industry: String,
    contactPerson: String,
    contactEmail: String,
    fiscalYearLabel: String,
    uniqueAssetKeyHeader: String,
    provisionDriveFolders: Boolean
  ) -> Unit,
  onRequestClientAccess: (clientId: String, clientName: String, fiscalYearId: String, reason: String) -> Unit,
  onSignOut: () -> Unit
) {
  val activeClients = remember(clients) {
    clients.filter { it.status == "Active" }
  }

  val assignedClientIdsForAuditor = remember(userProfile, clientAssignments) {
    val fromAssignments = clientAssignments
      .filter {
        (it.auditorUid == userProfile.userId ||
          (userProfile.email.isNotBlank() && it.auditorEmail.equals(userProfile.email, ignoreCase = true))) &&
          it.status == "Assigned"
      }
      .map { it.clientId }
    (userProfile.assignedClientIds + fromAssignments).distinct()
  }

  val accessibleClientsForAuditor = remember(activeClients, assignedClientIdsForAuditor) {
    activeClients.filter { assignedClientIdsForAuditor.contains(it.clientId) }
  }

  val selectableClients = if (isEffectiveAdmin) activeClients else activeClients

  var selectedClient by remember(selectableClients, accessibleClientsForAuditor, isEffectiveAdmin) {
    mutableStateOf(
      if (isEffectiveAdmin) {
        selectableClients.firstOrNull()
      } else {
        accessibleClientsForAuditor.firstOrNull() ?: selectableClients.firstOrNull()
      }
    )
  }

  var selectedFiscalYearId by remember(selectedClient) {
    mutableStateOf(
      selectedClient?.activeFiscalYearId?.ifBlank { "2026_27" } ?: "2026_27"
    )
  }

  LaunchedEffect(selectedClient) {
    selectedClient?.let { c ->
      selectedFiscalYearId = c.activeFiscalYearId.ifBlank {
        c.fiscalYearIds.firstOrNull().orEmpty().ifBlank { "2026_27" }
      }
    }
  }

  var clientMenuExpanded by remember { mutableStateOf(false) }
  var showCreateClientDialog by remember { mutableStateOf(false) }
  var requestNotes by remember { mutableStateOf("") }

  val isSelectedClientAssignedToAuditor = remember(selectedClient, assignedClientIdsForAuditor, isEffectiveAdmin) {
    if (isEffectiveAdmin) true
    else selectedClient != null && assignedClientIdsForAuditor.contains(selectedClient!!.clientId)
  }

  val myPendingRequests = remember(accessRequests, userProfile.userId) {
    accessRequests.filter { it.requesterUid == userProfile.userId }
  }

  Surface(
    modifier = Modifier
      .fillMaxSize()
      .testTag("client_selection_screen"),
    color = NovaBackground
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .widthIn(max = 500.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NovaSurfaceWhite),
        border = BorderStroke(1.dp, NovaBorderLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          // Header
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(14.dp),
                color = NovaPrimarySubtle
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = Icons.Default.Business,
                    contentDescription = null,
                    tint = NovaPrimary,
                    modifier = Modifier.size(26.dp)
                  )
                }
              }
              Column {
                Text(
                  text = "Select Client & Fiscal Year",
                  style = MaterialTheme.typography.titleLarge,
                  fontWeight = FontWeight.Bold,
                  color = NovaTextNavy
                )
                Text(
                  text = "${userProfile.effectiveDisplayName()} (${userProfile.role})",
                  style = MaterialTheme.typography.bodySmall,
                  color = NovaTextMuted
                )
              }
            }

            TextButton(
              onClick = onSignOut,
              modifier = Modifier.testTag("client_selection_sign_out_button")
            ) {
              Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign Out", modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(4.dp))
              Text("Logout")
            }
          }

          HorizontalDivider(color = NovaBorderLight)

          // Inline Operational Feedback (Error / Success / Loading)
          if (!clientCreationError.isNullOrBlank()) {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = StatusExceptionBg,
              border = BorderStroke(1.dp, StatusExceptionFg.copy(alpha = 0.5f)),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("client_creation_error_banner")
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = StatusExceptionFg, modifier = Modifier.size(20.dp))
                Text(
                  text = clientCreationError,
                  style = MaterialTheme.typography.bodySmall,
                  fontWeight = FontWeight.SemiBold,
                  color = StatusExceptionFg,
                  modifier = Modifier.weight(1f)
                )
              }
            }
          }

          if (!clientCreationSuccess.isNullOrBlank()) {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = StatusVerifiedBg,
              border = BorderStroke(1.dp, StatusVerifiedFg.copy(alpha = 0.5f)),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("client_creation_success_banner")
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusVerifiedFg, modifier = Modifier.size(20.dp))
                Text(
                  text = clientCreationSuccess,
                  style = MaterialTheme.typography.bodySmall,
                  fontWeight = FontWeight.SemiBold,
                  color = StatusVerifiedFg,
                  modifier = Modifier.weight(1f)
                )
              }
            }
          }

          if (isCreatingClient) {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = NovaPrimarySubtle,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("client_creation_loading_banner")
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                androidx.compose.material3.CircularProgressIndicator(
                  modifier = Modifier.size(18.dp),
                  strokeWidth = 2.dp,
                  color = NovaPrimary
                )
                Text(
                  text = "Provisioning client workspace & Cloudflare D1 shard...",
                  style = MaterialTheme.typography.bodySmall,
                  fontWeight = FontWeight.SemiBold,
                  color = NovaPrimary
                )
              }
            }
          }

          // Admin Empty State or Client Picker
          if (selectableClients.isEmpty()) {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = NovaSurfaceMuted,
              border = BorderStroke(1.dp, NovaBorderLight),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Business,
                  contentDescription = null,
                  tint = NovaPrimary,
                  modifier = Modifier.size(36.dp)
                )
                Text(
                  text = if (isEffectiveAdmin) {
                    "No Clients Configured Yet"
                  } else {
                    "No Clients Available Yet"
                  },
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = NovaTextNavy
                )
                Text(
                  text = if (isEffectiveAdmin) {
                    "Create your first Client workspace and Fiscal Year below to begin importing the Asset Register and persistent Master Input Workbook."
                  } else {
                    "An Administrator has not yet created or assigned a client workspace to your account. You can submit an access request below."
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = NovaTextMuted,
                  textAlign = TextAlign.Center
                )
                if (isEffectiveAdmin) {
                  Button(
                    onClick = { showCreateClientDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = NovaPrimary),
                    modifier = Modifier.testTag("create_first_client_button")
                  ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Create New Client")
                  }
                }
              }
            }
          } else {
            // Client Dropdown
            Text(
              text = "Select Client",
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.SemiBold,
              color = NovaTextNavy
            )

            ExposedDropdownMenuBox(
              expanded = clientMenuExpanded,
              onExpandedChange = { clientMenuExpanded = !clientMenuExpanded }
            ) {
              OutlinedTextField(
                value = selectedClient?.let { "${it.clientName} (${it.clientCode.ifBlank { it.clientId }})" } ?: "Choose a client...",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = clientMenuExpanded) },
                modifier = Modifier
                  .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                  .fillMaxWidth()
                  .testTag("select_client_dropdown"),
                shape = RoundedCornerShape(12.dp)
              )
              ExposedDropdownMenu(
                expanded = clientMenuExpanded,
                onDismissRequest = { clientMenuExpanded = false }
              ) {
                selectableClients.forEach { client ->
                  val isAssigned = isEffectiveAdmin || assignedClientIdsForAuditor.contains(client.clientId)
                  DropdownMenuItem(
                    text = {
                      Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        Column(modifier = Modifier.weight(1f)) {
                          Text(client.clientName, fontWeight = FontWeight.SemiBold)
                          Text(
                            text = if (isAssigned) "Assigned • FY: ${client.activeFiscalYearId}" else "Unassigned — Request Access",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isAssigned) StatusVerifiedFg else StatusExceptionFg
                          )
                        }
                      }
                    },
                    onClick = {
                      selectedClient = client
                      selectedFiscalYearId = client.activeFiscalYearId.ifBlank { "2026_27" }
                      clientMenuExpanded = false
                    }
                  )
                }
              }
            }

            // Fiscal Year Selection
            Text(
              text = "Fiscal Year",
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.SemiBold,
              color = NovaTextNavy
            )

            val availableFyLabels = remember(selectedClient, fiscalYears) {
              val fromClient = selectedClient?.fiscalYearIds.orEmpty()
              val fromDocs = fiscalYears
                .filter { it.clientId == selectedClient?.clientId }
                .map { it.fiscalYearId }
              (fromClient + fromDocs + listOf("2026_27", "2025_26")).filter { it.isNotBlank() }.distinct()
            }

            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              availableFyLabels.forEach { fyId ->
                val displayFy = fyId.replace("_", "–")
                FilterChip(
                  selected = selectedFiscalYearId == fyId,
                  onClick = { selectedFiscalYearId = fyId },
                  leadingIcon = {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                  },
                  label = { Text(displayFy) },
                  modifier = Modifier.testTag("select_fy_chip_$fyId")
                )
              }
            }

            if (isEffectiveAdmin) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                OutlinedButton(
                  onClick = { showCreateClientDialog = true },
                  modifier = Modifier.testTag("open_create_client_dialog_button")
                ) {
                  Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                  Spacer(Modifier.width(6.dp))
                  Text("Create New Client")
                }

                Button(
                  onClick = {
                    selectedClient?.let { c ->
                      onContinueWithWorkspace(c.clientId, selectedFiscalYearId.ifBlank { "2026_27" })
                    }
                  },
                  enabled = selectedClient != null,
                  colors = ButtonDefaults.buttonColors(containerColor = NovaPrimary),
                  modifier = Modifier.testTag("continue_to_workspace_button")
                ) {
                  Text("Continue")
                  Spacer(Modifier.width(6.dp))
                  Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
              }
            } else {
              // End User (Auditor) branch
              if (isSelectedClientAssignedToAuditor && selectedClient != null) {
                Surface(
                  shape = RoundedCornerShape(12.dp),
                  color = StatusVerifiedBg,
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                  ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusVerifiedFg)
                    Text(
                      text = "Assigned to ${selectedClient!!.clientName} (${selectedFiscalYearId.replace("_", "–")})",
                      style = MaterialTheme.typography.bodySmall,
                      fontWeight = FontWeight.SemiBold,
                      color = StatusVerifiedFg
                    )
                  }
                }

                Button(
                  onClick = {
                    selectedClient?.let { c ->
                      onContinueWithWorkspace(c.clientId, selectedFiscalYearId.ifBlank { "2026_27" })
                    }
                  },
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("continue_to_workspace_button"),
                  shape = RoundedCornerShape(12.dp),
                  colors = ButtonDefaults.buttonColors(containerColor = NovaPrimary)
                ) {
                  Text("Continue to Auditor Workspace", fontWeight = FontWeight.Bold)
                  Spacer(Modifier.width(8.dp))
                  Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
              }
            }
          }

          // Access Denied (End User) card when Auditor has no assigned client or selects an unassigned client
          if (!isEffectiveAdmin && (!isSelectedClientAssignedToAuditor || selectableClients.isEmpty())) {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = StatusExceptionBg,
              border = BorderStroke(1.dp, StatusExceptionFg.copy(alpha = 0.3f)),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("auditor_access_denied_card")
            ) {
              Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Icon(Icons.Default.Lock, contentDescription = null, tint = StatusExceptionFg)
                  Text(
                    text = "Access Denied (End User)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = StatusExceptionFg
                  )
                }

                Text(
                  text = if (selectedClient != null) {
                    "You are not assigned to '${selectedClient!!.clientName}'. Send an access request to the Administrator to gain access."
                  } else {
                    "You are not assigned to any client workspace yet. Send an access request to the Administrator."
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = StatusExceptionFg
                )

                val existingReqForTarget = myPendingRequests.firstOrNull {
                  it.clientId == (selectedClient?.clientId.orEmpty()) && it.status == "Pending"
                }

                if (existingReqForTarget != null) {
                  Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Text(
                      text = "Access Request Pending Review (Requested at ${existingReqForTarget.requestedAt.take(16)})",
                      style = MaterialTheme.typography.labelMedium,
                      fontWeight = FontWeight.SemiBold,
                      color = NovaTextNavy,
                      modifier = Modifier.padding(10.dp)
                    )
                  }
                } else {
                  OutlinedTextField(
                    value = requestNotes,
                    onValueChange = { requestNotes = it },
                    label = { Text("Note to Administrator (Optional)") },
                    placeholder = { Text("e.g. Assigned to Factory Floor 2 audit team") },
                    singleLine = true,
                    modifier = Modifier
                      .fillMaxWidth()
                      .testTag("access_request_note_input")
                  )

                  Button(
                    onClick = {
                      onRequestClientAccess(
                        selectedClient?.clientId.orEmpty(),
                        selectedClient?.clientName ?: "Client Assignment Request",
                        selectedFiscalYearId.ifBlank { "2026_27" },
                        requestNotes.trim()
                      )
                      requestNotes = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusExceptionFg),
                    modifier = Modifier
                      .fillMaxWidth()
                      .testTag("request_access_to_admin_button")
                  ) {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Request Access to Admin", color = Color.White, fontWeight = FontWeight.Bold)
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  LaunchedEffect(clientCreationSuccess) {
    if (!clientCreationSuccess.isNullOrBlank()) {
      showCreateClientDialog = false
    }
  }

  if (showCreateClientDialog) {
    CreateClientDialog(
      isSubmitting = isCreatingClient,
      errorMessage = clientCreationError,
      onDismiss = {
        showCreateClientDialog = false
        onClearCreationMessages()
      },
      onConfirm = { name, code, industry, contactPerson, contactEmail, fyLabel, uniqueKeyHeader, provisionDrive ->
        onCreateClient(name, code, industry, contactPerson, contactEmail, fyLabel, uniqueKeyHeader, provisionDrive)
      }
    )
  }
}

@Composable
fun CreateClientDialog(
  isSubmitting: Boolean = false,
  errorMessage: String? = null,
  onDismiss: () -> Unit,
  onConfirm: (
    clientName: String,
    clientCode: String,
    industry: String,
    contactPerson: String,
    contactEmail: String,
    fiscalYearLabel: String,
    uniqueAssetKeyHeader: String,
    provisionDriveFolders: Boolean
  ) -> Unit
) {
  var clientName by remember { mutableStateOf("") }
  var clientCode by remember { mutableStateOf("") }
  var industry by remember { mutableStateOf("Manufacturing & Industrial") }
  var contactPerson by remember { mutableStateOf("") }
  var contactEmail by remember { mutableStateOf("") }
  var fiscalYearLabel by remember { mutableStateOf("2026–27") }
  var uniqueKeyHeader by remember { mutableStateOf("Identification No.") }
  var provisionDrive by remember { mutableStateOf(true) }

  AlertDialog(
    onDismissRequest = { if (!isSubmitting) onDismiss() },
    title = {
      Text("Create New Client Workspace", fontWeight = FontWeight.Bold)
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        if (!errorMessage.isNullOrBlank()) {
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = StatusExceptionBg,
            border = BorderStroke(1.dp, StatusExceptionFg.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().testTag("dialog_create_client_error_banner")
          ) {
            Row(
              modifier = Modifier.padding(10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(Icons.Default.Lock, contentDescription = null, tint = StatusExceptionFg, modifier = Modifier.size(16.dp))
              Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = StatusExceptionFg,
                fontWeight = FontWeight.SemiBold
              )
            }
          }
        }

        Text(
          text = "Each client has isolated Asset Register records, persistent Master Input Workbook, Daily Input Sheets, and Google Drive folders.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
          value = clientName,
          onValueChange = { clientName = it },
          label = { Text("Client Name *") },
          placeholder = { Text("e.g. Walton Hi-Tech Industries") },
          singleLine = true,
          enabled = !isSubmitting,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("create_client_name_input")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = clientCode,
            onValueChange = { clientCode = it },
            label = { Text("Client Code *") },
            placeholder = { Text("e.g. WALTON") },
            singleLine = true,
            enabled = !isSubmitting,
            modifier = Modifier
              .weight(1f)
              .testTag("create_client_code_input")
          )
          OutlinedTextField(
            value = fiscalYearLabel,
            onValueChange = { fiscalYearLabel = it },
            label = { Text("Fiscal Year *") },
            placeholder = { Text("2026–27") },
            singleLine = true,
            enabled = !isSubmitting,
            modifier = Modifier
              .weight(1f)
              .testTag("create_client_fy_input")
          )
        }
        OutlinedTextField(
          value = industry,
          onValueChange = { industry = it },
          label = { Text("Industry / Sector") },
          singleLine = true,
          enabled = !isSubmitting,
          modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
          value = uniqueKeyHeader,
          onValueChange = { uniqueKeyHeader = it },
          label = { Text("Unique Asset Key Column Header *") },
          supportingText = { Text("Used to match Asset Register rows with Master Input Sheet rows") },
          singleLine = true,
          enabled = !isSubmitting,
          modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = contactPerson,
            onValueChange = { contactPerson = it },
            label = { Text("Contact Person") },
            singleLine = true,
            enabled = !isSubmitting,
            modifier = Modifier.weight(1f)
          )
          OutlinedTextField(
            value = contactEmail,
            onValueChange = { contactEmail = it },
            label = { Text("Contact Email") },
            singleLine = true,
            enabled = !isSubmitting,
            modifier = Modifier.weight(1f)
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Provision Google Drive Folders", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text("AssetFlow/[Client Name]/Image Evidence, Master Input File, Daily Input Sheets", style = MaterialTheme.typography.bodySmall)
          }
          Switch(checked = provisionDrive, onCheckedChange = { provisionDrive = it }, enabled = !isSubmitting)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (clientName.isNotBlank() && !isSubmitting) {
            onConfirm(
              clientName.trim(),
              clientCode.trim().ifBlank { clientName.take(6).uppercase() },
              industry.trim(),
              contactPerson.trim(),
              contactEmail.trim(),
              fiscalYearLabel.trim().ifBlank { "2026–27" },
              uniqueKeyHeader.trim().ifBlank { "Identification No." },
              provisionDrive
            )
          }
        },
        enabled = clientName.isNotBlank() && !isSubmitting,
        modifier = Modifier.testTag("confirm_create_client_button")
      ) {
        if (isSubmitting) {
          androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary
          )
          Spacer(Modifier.width(6.dp))
          Text("Creating...")
        } else {
          Text("Create Client")
        }
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, enabled = !isSubmitting) {
        Text("Cancel")
      }
    }
  )
}
