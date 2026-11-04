package com.example

import android.accounts.AccountManager
import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.model.DailyBatchSubmissionRecord
import com.example.data.model.FoundNotInFarRecord
import com.example.data.model.UserRole
import com.example.data.offline.OfflineSyncDatabase
import com.example.data.repository.AuditRepository
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.UserProfileDialog
import com.example.ui.auth.signOutUser
import com.example.ui.screens.AdminControlPanelScreen
import com.example.ui.screens.AssetListScreen
import com.example.ui.screens.ClientSelectionScreen
import com.example.ui.screens.CountingFormScreen
import com.example.ui.screens.DynamicCountingFormScreen
import com.example.ui.screens.DailyInputSheetsScreen
import com.example.ui.screens.ManualObservationFormScreen
import com.example.ui.screens.NotificationsScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.SubmissionHistoryScreen
import com.example.ui.screens.UserDashboardScreen
import com.example.ui.theme.AuditGoldLight
import com.example.ui.theme.AuditNavyPrimary
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.StatusWarningBg
import com.example.ui.theme.StatusWarningFg
import com.example.ui.viewmodel.AuditViewModel
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * 4-Tab Auditor Navigation Structure matching the AssetFlow UI/UX Flowchart:
 * Home | Counting | Activity | Profile
 */
enum class AuditorNavTab(val label: String) {
  HOME("Home"),
  COUNTING("Counting"),
  ACTIVITY("Activity"),
  PROFILE("Profile")
}

enum class AuditorActivitySubTab(val label: String) {
  DAILY_SHEETS("My Daily Sheets"),
  SUBMISSION_HISTORY("Submission History"),
  NOTIFICATIONS("Notifications")
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        AppRoot()
      }
    }
  }
}

@Composable
fun AppRoot() {
  var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }
  var selectedRole by remember { mutableStateOf("End User") }
  var externalAuthError by remember { mutableStateOf<String?>(null) }

  DisposableEffect(Unit) {
    val listener = FirebaseAuth.AuthStateListener { auth ->
      currentUser = auth.currentUser
    }
    Firebase.auth.addAuthStateListener(listener)
    onDispose {
      Firebase.auth.removeAuthStateListener(listener)
    }
  }

  val user = currentUser
  if (user == null) {
    AuthScreen(
      externalAuthError = externalAuthError,
      onClearExternalAuthError = { externalAuthError = null },
      onAuthenticated = { role ->
        externalAuthError = null
        selectedRole = role
        currentUser = Firebase.auth.currentUser
      }
    )
  } else {
    AuthenticatedAuditApp(
      currentUserId = user.uid,
      initialSelectedRole = selectedRole,
      onAuthRejected = { errorReason ->
        externalAuthError = errorReason
        Firebase.auth.signOut()
        currentUser = null
      },
      onSignedOut = {
        externalAuthError = null
        currentUser = null
      }
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthenticatedAuditApp(
  currentUserId: String,
  initialSelectedRole: String = "End User",
  onAuthRejected: (String) -> Unit,
  onSignedOut: () -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember { CredentialManager.create(context) }

  val viewModel: AuditViewModel = viewModel(
    key = currentUserId,
    factory = viewModelFactory {
      initializer {
        val application = checkNotNull(this[APPLICATION_KEY]) {
          "APPLICATION_KEY missing from CreationExtras"
        }
        val app = FirebaseApp.getInstance()
        val databaseId = application.getString(R.string.firestore_database_id).trim()
        val db = if (databaseId.isNotEmpty() && databaseId != "(default)") {
          FirebaseFirestore.getInstance(app, databaseId)
        } else {
          FirebaseFirestore.getInstance(app)
        }
        val offlineDao = if (com.example.data.offline.OfflineDatabaseGate.isAvailable(application)) {
          OfflineSyncDatabase.getInstance(application).offlineSyncDao()
        } else {
          null
        }
        val appContainer = (application as? AssetFlowApplication)?.appContainer
        val workerApiClient = appContainer?.apiClient ?: com.example.data.network.WorkerApiClient()
        val repo = AuditRepository(
          db = db,
          offlineSyncDao = offlineDao,
          workerApiClient = workerApiClient
        )
        repo.dynamicOfflineCacheDao = appContainer?.dynamicOfflineStore?.cacheDao()
        AuditViewModel(
          repo,
          currentUserId
        )
      }
    }
  )

  var isAuthVerified by remember(currentUserId, initialSelectedRole) { mutableStateOf(false) }

  LaunchedEffect(currentUserId, initialSelectedRole) {
    isAuthVerified = false
    viewModel.verifySignedInUserRoleAndBootstrap(
      selectedPortalRole = initialSelectedRole,
      onSuccess = {
        isAuthVerified = true
      },
      onRejected = { reason ->
        isAuthVerified = false
        viewModel.clearSessionOnLogout()
        onAuthRejected(reason)
      }
    )
  }

  val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
  val activeClientId by viewModel.activeClientId.collectAsStateWithLifecycle()
  val activeFiscalYearId by viewModel.activeFiscalYearId.collectAsStateWithLifecycle()
  val clients by viewModel.clients.collectAsStateWithLifecycle()
  val fiscalYears by viewModel.fiscalYears.collectAsStateWithLifecycle()
  val clientAssignments by viewModel.clientAssignments.collectAsStateWithLifecycle()
  val accessRequests by viewModel.accessRequests.collectAsStateWithLifecycle()
  val activeClient by viewModel.activeClient.collectAsStateWithLifecycle()
  val ownershipConfig by viewModel.ownershipConfig.collectAsStateWithLifecycle()
  val masterWorkbookState by viewModel.masterWorkbookState.collectAsStateWithLifecycle()
  val assetRegisterValidation by viewModel.assetRegisterValidation.collectAsStateWithLifecycle()
  val deletionJobs by viewModel.deletionJobs.collectAsStateWithLifecycle()
  val deletionAuditEvents by viewModel.deletionAuditEvents.collectAsStateWithLifecycle()
  val deletionImpactPreview by viewModel.deletionImpactPreview.collectAsStateWithLifecycle()
  val clientDeletionImpact by viewModel.clientDeletionImpact.collectAsStateWithLifecycle()
  val userDeletionImpact by viewModel.userDeletionImpact.collectAsStateWithLifecycle()

  val allAssets by viewModel.allAssetsWithCounts.collectAsStateWithLifecycle()
  val filteredAssets by viewModel.filteredAssets.collectAsStateWithLifecycle()
  val filterState by viewModel.filterState.collectAsStateWithLifecycle()
  val masterData by viewModel.masterData.collectAsStateWithLifecycle()
  val foundNotInFar by viewModel.foundNotInFar.collectAsStateWithLifecycle()
  val roleAssignments by viewModel.roleAssignments.collectAsStateWithLifecycle()
  val projectSettings by viewModel.projectSettings.collectAsStateWithLifecycle()
  val assetPhotos by viewModel.assetPhotos.collectAsStateWithLifecycle()
  val activityLogs by viewModel.activityLogs.collectAsStateWithLifecycle()
  val exportJobs by viewModel.exportJobs.collectAsStateWithLifecycle()
  val queuedOfflineRecords by viewModel.queuedOfflineRecords.collectAsStateWithLifecycle()
  val queuedOfflinePhotos by viewModel.queuedOfflinePhotos.collectAsStateWithLifecycle()
  val isOfflineSimEnabled by viewModel.isOfflineSimulationEnabled.collectAsStateWithLifecycle()
  val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
  val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
  val isDriveAuthorizationReady by viewModel.isDriveAuthorizationReady.collectAsStateWithLifecycle()
  val driveErrorMessage by viewModel.driveErrorMessage.collectAsStateWithLifecycle()
  val driveSessionAccountEmail by viewModel.driveSessionAccountEmail.collectAsStateWithLifecycle()
  val detectedWorkbookPreview by viewModel.detectedWorkbookPreview.collectAsStateWithLifecycle()
  val draftSchemaFields by viewModel.draftSchemaFields.collectAsStateWithLifecycle()
  val schemaVersions by viewModel.schemaVersions.collectAsStateWithLifecycle()
  val supplementaryPreview by viewModel.supplementaryImportPreview.collectAsStateWithLifecycle()
  val adminAllSubmissions by viewModel.adminAllSubmissions.collectAsStateWithLifecycle()
  val isCreatingClient by viewModel.isCreatingClient.collectAsStateWithLifecycle()
  val clientCreationError by viewModel.clientCreationError.collectAsStateWithLifecycle()
  val clientCreationSuccess by viewModel.clientCreationSuccess.collectAsStateWithLifecycle()
  val dailyBatches by viewModel.dailyBatches.collectAsStateWithLifecycle()
  val notifications by viewModel.notifications.collectAsStateWithLifecycle()
  val columnMapping by viewModel.columnMapping.collectAsStateWithLifecycle()
  val detectedDropdownOptions by viewModel.detectedDropdownOptions.collectAsStateWithLifecycle()

  var activeAuditorTab by remember { mutableStateOf(AuditorNavTab.HOME) }
  var activeActivitySubTab by remember { mutableStateOf(AuditorActivitySubTab.DAILY_SHEETS) }
  var selectedAdminTab by remember { mutableStateOf(AdminNavTab.HOME) }
  var isSelectingClient by remember { mutableStateOf(false) }

  val currentWorkspaceAssignment = roleAssignments.firstOrNull { it.memberUid == currentUserId }
  val isOwner = ownershipConfig.ownerUid == currentUserId ||
    currentWorkspaceAssignment?.role == UserRole.OWNER ||
    userProfile.role.equals(UserRole.OWNER, ignoreCase = true)
  val isWorkspaceAdmin = isOwner ||
    currentWorkspaceAssignment?.role == UserRole.ADMIN ||
    userProfile.role.equals(UserRole.ADMIN, ignoreCase = true)
  val hasReviewPrivilege = isWorkspaceAdmin ||
    currentWorkspaceAssignment?.role == UserRole.REVIEWER ||
    currentWorkspaceAssignment?.hasReviewAuthority == true ||
    userProfile.hasReviewAuthority
  val hasPublicationPrivilege = isWorkspaceAdmin ||
    currentWorkspaceAssignment?.canPublish == true ||
    userProfile.canPublish
  val hasDriveSyncPrivilege = isWorkspaceAdmin || currentWorkspaceAssignment?.canSyncDrive == true

  val isAdminPortal = initialSelectedRole.equals("Admin", ignoreCase = true) && isWorkspaceAdmin

  var selectedAssetKeyForCounting by remember { mutableStateOf<String?>(null) }
  var activeManualObservation by remember { mutableStateOf<FoundNotInFarRecord?>(null) }
  var isCreatingManualObservation by remember { mutableStateOf(false) }
  var manualObservationPrefilledAssetId by remember { mutableStateOf("") }
  var showProfileDialog by remember { mutableStateOf(false) }
  var pendingDriveDownload by remember { mutableStateOf(false) }
  var pendingDailyBatchForExcelExport by remember { mutableStateOf<DailyBatchSubmissionRecord?>(null) }
  var pendingDriveAccountEmail by remember { mutableStateOf("") }

  val snackbarHostState = remember { SnackbarHostState() }
  LaunchedEffect(Unit) {
    val gateStatus = com.example.data.offline.OfflineDatabaseGate.getStatus()
    if (gateStatus is com.example.data.offline.OfflineDatabaseStatus.Unavailable) {
      snackbarHostState.showSnackbar(
        message = gateStatus.userRecoveryMessage,
        duration = androidx.compose.material3.SnackbarDuration.Indefinite
      )
    }
  }
  LaunchedEffect(statusBanner) {
    val msg = statusBanner
    if (!msg.isNullOrBlank()) {
      snackbarHostState.showSnackbar(msg)
      viewModel.clearBanner()
    }
  }

  // Google Drive OAuth Consent Launcher
  val driveAuthLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      viewModel.handleGoogleDriveConsentApproved(context, pendingDriveAccountEmail)
    } else {
      viewModel.onGoogleDriveAuthorizationCancelled()
    }
  }

  val driveAccountPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      val accountEmail = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME).orEmpty()
      if (accountEmail.isBlank()) {
        viewModel.onGoogleDriveAuthorizationCancelled()
      } else {
        pendingDriveAccountEmail = accountEmail
        viewModel.authorizeGoogleDrive(context, accountEmail) { consentIntent ->
          driveAuthLauncher.launch(consentIntent)
        }
      }
    } else {
      viewModel.onGoogleDriveAuthorizationCancelled()
    }
  }

  // Excel (.xlsx) Import Launcher
  val importExcelLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri ->
    if (uri != null) {
      context.contentResolver.openInputStream(uri)?.let { stream ->
        viewModel.importWorkbookStream(stream, uri.lastPathSegment ?: "Imported_Workbook.xlsx")
      }
    }
  }

  // Excel (.xlsx) Export Launcher (Master Input Download or Daily Sheet Export)
  val exportExcelLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument(
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )
  ) { uri ->
    if (uri != null) {
      val outStream = context.contentResolver.openOutputStream(uri)
      if (outStream != null) {
        val batchToExport = pendingDailyBatchForExcelExport
        when {
          pendingDriveDownload -> {
            pendingDriveDownload = false
            viewModel.downloadMasterInputSheetToStream(outStream)
          }
          batchToExport != null -> {
            pendingDailyBatchForExcelExport = null
            viewModel.exportDailyBatchSheetToStream(batchToExport, outStream)
          }
          else -> {
            viewModel.exportWorkbookToStream(outStream) { _, _ -> }
          }
        }
      } else {
        pendingDriveDownload = false
        pendingDailyBatchForExcelExport = null
      }
    } else {
      pendingDriveDownload = false
      pendingDailyBatchForExcelExport = null
    }
  }

  // Authorization Gate: Verify credentials and workspace authorization before rendering protected UI
  if (!isAuthVerified) {
    Surface(
      modifier = Modifier
        .fillMaxSize()
        .testTag("auth_verification_loading"),
      color = MaterialTheme.colorScheme.background
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
      ) {
        CircularProgressIndicator(
          modifier = Modifier.size(48.dp),
          color = AuditNavyPrimary,
          strokeWidth = 4.dp
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
          text = "Verifying credentials & workspace authorization...",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = AuditNavyPrimary,
          textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = "Establishing secure zero-trust session",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center
        )
      }
    }
    return
  }

  // Route to Client & Fiscal Year selection screen when no client is active or user tapped Switch Client
  if (activeClientId.isBlank() || isSelectingClient) {
    ClientSelectionScreen(
      userProfile = userProfile,
      isEffectiveAdmin = isWorkspaceAdmin,
      clients = clients,
      fiscalYears = fiscalYears,
      clientAssignments = clientAssignments,
      accessRequests = accessRequests.filter { it.requesterUid == currentUserId },
      isCreatingClient = isCreatingClient,
      clientCreationError = clientCreationError,
      clientCreationSuccess = clientCreationSuccess,
      onClearCreationMessages = { viewModel.clearClientCreationMessages() },
      onContinueWithWorkspace = { clientId, fyId ->
        viewModel.selectClientAndFiscalYear(clientId, fyId)
        isSelectingClient = false
      },
      onCreateClient = { clientName, clientCode, industry, contactPerson, contactEmail, fyLabel, uniqueKeyHeader, provisionDrive ->
        viewModel.createClientWithDefaultFiscalYear(
          clientName = clientName,
          clientCode = clientCode,
          fiscalYearLabel = fyLabel,
          industry = industry,
          contactPerson = contactPerson,
          contactEmail = contactEmail,
          uniqueAssetKeyHeader = uniqueKeyHeader,
          provisionDriveFolders = provisionDrive
        )
      },
      onRequestClientAccess = { clientId, clientName, fyId, message ->
        viewModel.submitClientAccessRequest(clientId, clientName, fyId, message)
      },
      onSignOut = {
        viewModel.clearSessionOnLogout()
        signOutUser(
          credentialManager = credentialManager,
          onSignOutComplete = onSignedOut,
          scope = scope
        )
      }
    )
    return
  }

  val activeClientName = remember(clients, activeClientId) {
    clients.firstOrNull { it.clientId == activeClientId }?.clientName ?: activeClientId
  }
  val activeFiscalYearLabel = remember(fiscalYears, activeFiscalYearId) {
    fiscalYears.firstOrNull { it.fiscalYearId == activeFiscalYearId }?.yearLabel ?: activeFiscalYearId
  }

  val selectedAssetWithCount = remember(allAssets, selectedAssetKeyForCounting) {
    allAssets.find { it.farAsset.assetKey == selectedAssetKeyForCounting }
  }

  val isSubFormOpen = selectedAssetWithCount != null || isCreatingManualObservation || activeManualObservation != null

  if (!isSubFormOpen) {
    if (isAdminPortal && selectedAdminTab != AdminNavTab.HOME) {
      BackHandler {
        selectedAdminTab = AdminNavTab.HOME
      }
    } else if (!isAdminPortal && activeAuditorTab != AuditorNavTab.HOME) {
      BackHandler {
        activeAuditorTab = AuditorNavTab.HOME
      }
    }
  }

  val totalPendingOffline = queuedOfflineRecords.size + queuedOfflinePhotos.size
  val unreadNotificationCount = notifications.count { !it.isRead }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val isExpandedScreen = maxWidth >= 720.dp

    Scaffold(
      contentWindowInsets = WindowInsets.safeDrawing,
      topBar = {
        TopAppBar(
          title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.FactCheck,
                contentDescription = null,
                tint = AuditGoldLight,
                modifier = Modifier.size(24.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Column {
                Text(
                  text = "AssetFlow",
                  style = MaterialTheme.typography.titleMedium,
                  color = Color.White,
                  fontWeight = FontWeight.Bold
                )
                Text(
                  text = "$activeClientName • $activeFiscalYearLabel",
                  style = MaterialTheme.typography.labelSmall,
                  color = AuditGoldLight
                )
              }
            }
          },
          actions = {
            if (isOfflineSimEnabled || totalPendingOffline > 0) {
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = StatusWarningBg,
                modifier = Modifier
                  .padding(end = 4.dp)
                  .testTag("sync_status_pill")
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Icon(
                    imageVector = if (isOfflineSimEnabled) Icons.Default.CloudOff else Icons.Default.CloudDone,
                    contentDescription = if (isOfflineSimEnabled) "Offline Mode" else "Pending Sync",
                    tint = StatusWarningFg,
                    modifier = Modifier.size(14.dp)
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text(
                    text = if (isOfflineSimEnabled) "Offline ($totalPendingOffline)" else "Pending ($totalPendingOffline)",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusWarningFg,
                    fontWeight = FontWeight.Bold
                  )
                }
              }
            }

            TextButton(
              onClick = { isSelectingClient = true },
              modifier = Modifier.testTag("switch_client_top_button")
            ) {
              Icon(
                imageVector = Icons.Default.Business,
                contentDescription = "Switch Client",
                tint = AuditGoldLight,
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "Switch Client",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
              )
            }

            TextButton(
              onClick = { showProfileDialog = true },
              modifier = Modifier.testTag("open_profile_button")
            ) {
              Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = "Profile",
                tint = AuditGoldLight,
                modifier = Modifier.size(20.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "Profile",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge
              )
            }
            IconButton(
              onClick = {
                viewModel.clearSessionOnLogout()
                signOutUser(
                  credentialManager = credentialManager,
                  onSignOutComplete = onSignedOut,
                  scope = scope
                )
              },
              modifier = Modifier.testTag("sign_out_button")
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = "Sign Out",
                tint = Color.White
              )
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(
            containerColor = AuditNavyPrimary
          )
        )
      },
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
      bottomBar = {
        if (!isExpandedScreen && !isSubFormOpen) {
          NavigationBar {
            if (isAdminPortal) {
              AdminNavTab.entries.forEach { tab ->
                NavigationBarItem(
                  selected = selectedAdminTab == tab,
                  onClick = { selectedAdminTab = tab },
                  icon = { Icon(tab.icon, contentDescription = tab.label) },
                  label = { Text(tab.label) },
                  modifier = Modifier.testTag("admin_nav_tab_${tab.name.lowercase()}")
                )
              }
            } else {
              AuditorNavTab.entries.forEach { tab ->
                NavigationBarItem(
                  selected = activeAuditorTab == tab,
                  onClick = { activeAuditorTab = tab },
                  icon = {
                    Icon(
                      imageVector = when (tab) {
                        AuditorNavTab.HOME -> Icons.Default.Home
                        AuditorNavTab.COUNTING -> Icons.Default.EditNote
                        AuditorNavTab.ACTIVITY -> Icons.AutoMirrored.Filled.ListAlt
                        AuditorNavTab.PROFILE -> Icons.Default.AccountCircle
                      },
                      contentDescription = tab.label
                    )
                  },
                  label = {
                    Text(
                      if (tab == AuditorNavTab.ACTIVITY && unreadNotificationCount > 0) {
                        "${tab.label} ($unreadNotificationCount)"
                      } else tab.label
                    )
                  },
                  modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                )
              }
            }
          }
        }
      }
    ) { innerPadding ->
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding)
      ) {
        if (isExpandedScreen) {
          NavigationRail(modifier = Modifier.fillMaxHeight()) {
            if (isAdminPortal) {
              AdminNavTab.entries.forEach { tab ->
                NavigationRailItem(
                  selected = selectedAdminTab == tab,
                  onClick = { selectedAdminTab = tab },
                  icon = { Icon(tab.icon, contentDescription = tab.label) },
                  label = { Text(tab.label) }
                )
              }
            } else {
              AuditorNavTab.entries.forEach { tab ->
                NavigationRailItem(
                  selected = activeAuditorTab == tab && !isSubFormOpen,
                  onClick = {
                    selectedAssetKeyForCounting = null
                    activeManualObservation = null
                    isCreatingManualObservation = false
                    activeAuditorTab = tab
                  },
                  icon = {
                    Icon(
                      imageVector = when (tab) {
                        AuditorNavTab.HOME -> Icons.Default.Home
                        AuditorNavTab.COUNTING -> Icons.Default.EditNote
                        AuditorNavTab.ACTIVITY -> Icons.AutoMirrored.Filled.ListAlt
                        AuditorNavTab.PROFILE -> Icons.Default.AccountCircle
                      },
                      contentDescription = tab.label
                    )
                  },
                  label = {
                    Text(
                      if (tab == AuditorNavTab.ACTIVITY && unreadNotificationCount > 0) {
                        "${tab.label} ($unreadNotificationCount)"
                      } else tab.label
                    )
                  }
                )
              }
            }
          }
        }

        Column(modifier = Modifier.fillMaxSize()) {
          if (isBusy) {
            LinearProgressIndicator(
              modifier = Modifier.fillMaxWidth(),
              color = AuditGoldLight
            )
          }

          Box(modifier = Modifier.fillMaxSize()) {
            when {
              selectedAssetWithCount != null -> {
                val appContainer = (context.applicationContext as? AssetFlowApplication)?.appContainer
                if (appContainer != null) {
                  DynamicCountingFormScreen(
                    clientId = activeClientId,
                    cycleId = activeFiscalYearId,
                    assetWithCount = selectedAssetWithCount,
                    userProfile = userProfile,
                    syncRepository = appContainer.syncRepository,
                    cacheRepository = appContainer.offlineCacheRepository,
                    onBack = { selectedAssetKeyForCounting = null }
                  )
                } else {
                  CountingFormScreen(
                    assetWithCount = selectedAssetWithCount,
                    masterData = masterData,
                    userProfile = userProfile,
                    onSaveDraft = { draft, explicitDateEdit ->
                      viewModel.saveCountingRecord(
                        farAsset = selectedAssetWithCount.farAsset,
                        draft = draft,
                        markAsReviewed = false,
                        explicitDateEdit = explicitDateEdit
                      )
                    },
                    onSaveVerification = { draft, explicitDateEdit ->
                      viewModel.saveCountingRecord(
                        farAsset = selectedAssetWithCount.farAsset,
                        draft = draft,
                        markAsReviewed = false,
                        explicitDateEdit = explicitDateEdit,
                        onSaved = { selectedAssetKeyForCounting = null }
                      )
                    },
                    onMarkReviewed = { draft, explicitDateEdit ->
                      viewModel.saveCountingRecord(
                        farAsset = selectedAssetWithCount.farAsset,
                        draft = draft,
                        markAsReviewed = true,
                        explicitDateEdit = explicitDateEdit,
                        onSaved = { selectedAssetKeyForCounting = null }
                      )
                    },
                    onBack = { selectedAssetKeyForCounting = null }
                  )
                }
              }

              isCreatingManualObservation || activeManualObservation != null -> {
                ManualObservationFormScreen(
                  initialObservation = activeManualObservation,
                  prefilledSearchedAssetId = manualObservationPrefilledAssetId,
                  masterData = masterData,
                  userProfile = userProfile,
                  onSaveObservation = { record, isDraft, markAsReviewed, explicitDateEdit ->
                    viewModel.saveFoundNotInFar(
                      record = record.copy(isDraft = isDraft),
                      markAsReviewed = markAsReviewed,
                      explicitDateEdit = explicitDateEdit,
                      onSaved = {
                        isCreatingManualObservation = false
                        activeManualObservation = null
                        manualObservationPrefilledAssetId = ""
                      }
                    )
                  },
                  onBack = {
                    isCreatingManualObservation = false
                    activeManualObservation = null
                    manualObservationPrefilledAssetId = ""
                  }
                )
              }

              isAdminPortal -> {
                AdminControlPanelScreen(
                  userProfile = userProfile,
                  isEffectiveOwner = isOwner,
                  isEffectiveAdmin = isWorkspaceAdmin,
                  hasReviewAuthority = hasReviewPrivilege,
                  canPublishToMaster = hasPublicationPrivilege,
                  canSyncDrive = hasDriveSyncPrivilege,
                  selectedTab = selectedAdminTab,
                  onNavigateToAdminTab = { selectedAdminTab = it },
                  activeClient = activeClient,
                  activeFiscalYearId = activeFiscalYearId,
                  clients = clients,
                  clientAssignments = clientAssignments,
                  accessRequests = accessRequests,
                  assetRegisterValidation = assetRegisterValidation,
                  masterWorkbookSyncMeta = masterWorkbookState,
                  deletionImpactPreview = deletionImpactPreview ?: clientDeletionImpact ?: userDeletionImpact,
                  deletionJobs = deletionJobs,
                  deletionAuditEvents = deletionAuditEvents,
                  assetsWithCounts = allAssets,
                  foundNotInFar = foundNotInFar,
                  projectSettings = projectSettings,
                  roleAssignments = roleAssignments,
                  assetPhotos = assetPhotos,
                  activityLogs = activityLogs,
                  exportJobs = exportJobs,
                  queuedOfflineRecords = queuedOfflineRecords,
                  queuedOfflinePhotos = queuedOfflinePhotos,
                  isOfflineSimulationEnabled = isOfflineSimEnabled,
                  onToggleOfflineSimulation = viewModel::toggleOfflineSimulation,
                  onSyncAllOfflineNow = viewModel::syncAllOfflineItemsNow,
                  onRetryPhotoUpload = viewModel::retryFailedPhotoUpload,
                  onSaveProjectSettings = viewModel::saveProjectSettings,
                  onAuthorizeGoogleDrive = {
                    driveAccountPicker.launch(
                      AccountManager.newChooseAccountIntent(
                        null,
                        null,
                        arrayOf("com.google"),
                        "Choose the Google account for Drive",
                        null,
                        null,
                        null
                      )
                    )
                  },
                  onProvisionDriveFolders = viewModel::provisionGoogleDriveFolders,
                  onSyncMasterWorkbookToDrive = { selectedAdminTab = AdminNavTab.REVIEW },
                  onDownloadMasterInputSheet = {
                    pendingDriveDownload = true
                    exportExcelLauncher.launch(
                      masterWorkbookState.fileName.ifBlank {
                        projectSettings.masterWorkbookFileName.ifBlank { "Master Input Sheet.xlsx" }
                      }
                    )
                  },
                  onSaveRoleAssignment = viewModel::saveRoleAssignment,
                  detectedPreview = detectedWorkbookPreview,
                  draftFields = draftSchemaFields,
                  schemaVersions = schemaVersions,
                  supplementaryPreview = supplementaryPreview,
                  teamSubmissions = adminAllSubmissions,
                  dailyBatches = dailyBatches,
                  columnMapping = columnMapping,
                  detectedDropdownOptions = detectedDropdownOptions,
                  onImportAssetRegisterToFirebase = { bytes, fileName, uniqueKeyHeader, replaceExisting ->
                    viewModel.importClientAssetRegisterBytes(bytes, fileName, uniqueKeyHeader, replaceExisting)
                  },
                  onUploadMasterInputWorkbook = { bytes, fileName, targetSheetName, headerRowIdx, uniqueKeyHeader ->
                    viewModel.uploadPersistentMasterWorkbookBytes(
                      bytes = bytes,
                      fileName = fileName,
                      targetSheetName = targetSheetName,
                      headerRowIndex = headerRowIdx,
                      uniqueKeyHeader = uniqueKeyHeader
                    )
                  },
                  onInspectWorkbook = { bytes, fileName ->
                    viewModel.uploadPersistentMasterWorkbookBytes(bytes, fileName)
                  },
                  onSelectConfigMode = viewModel::selectConfigModeForPreview,
                  onUpdateFieldConfig = viewModel::updateDraftFieldConfig,
                  onImportSupplementary = { bytes, fileName, fieldId, heading ->
                    viewModel.importSupplementaryFieldStream(
                      java.io.ByteArrayInputStream(bytes),
                      fileName,
                      fieldId,
                      heading
                    )
                  },
                  onApplySupplementary = viewModel::applySupplementaryPreviewToDraftField,
                  onPublishSchema = viewModel::publishCurrentDraftSchema,
                  onRollbackSchema = viewModel::rollbackSchemaVersion,
                  onRefreshSubmissions = viewModel::refreshAdminTeamSubmissions,
                  onApproveSubmission = viewModel::approveSubmission,
                  onRejectSubmission = viewModel::rejectSubmission,
                  onRequestChangesOnSubmission = viewModel::requestChangesOnSubmission,
                  onPublishApprovedToMaster = viewModel::publishApprovedSubmissionsToMasterWorkbook,
                  onDisconnectGoogleDrive = viewModel::disconnectAdminGoogleDrive,
                  onTestDriveUpload = viewModel::testAdminGoogleDriveUpload,
                  onSendBatchRemarks = { batch, remarks ->
                    viewModel.sendBatchRemarks(batch.batchId.ifBlank { batch.id }, remarks)
                  },
                  onApproveBatchOnly = { batch ->
                    viewModel.approveBatchOnly(batch.batchId.ifBlank { batch.id })
                  },
                  onApproveAndPublishBatch = { batch ->
                    viewModel.approveAndPublishBatch(batch.batchId.ifBlank { batch.id })
                  },
                  onDownloadDailyBatchExcel = { batch ->
                    pendingDailyBatchForExcelExport = batch
                    exportExcelLauncher.launch("Daily_Sheet_${batch.countingDate}_${batch.auditorName}.xlsx")
                  },
                  onSaveColumnMapping = { mapping -> viewModel.saveColumnMapping(mapping) },
                  onUpdateUserPermissions = { uid, reviewAuth, publishPerm, syncDrive ->
                    viewModel.updateUserPermissions(uid, reviewAuth, publishPerm, syncDrive)
                  },
                  onSelectClientWorkspace = { clientId, fyId ->
                    viewModel.selectClientAndFiscalYear(clientId, fyId)
                  },
                  onCreateClient = { name, code, industry, contactPerson, contactEmail, fy, uniqueHeader, provisionDrive ->
                    viewModel.createClientWithDefaultFiscalYear(
                      clientName = name,
                      clientCode = code,
                      fiscalYearLabel = fy,
                      industry = industry,
                      contactPerson = contactPerson,
                      contactEmail = contactEmail,
                      uniqueAssetKeyHeader = uniqueHeader,
                      provisionDriveFolders = provisionDrive
                    )
                  },
                  onArchiveOrRestoreClient = { clientId, archive ->
                    viewModel.archiveClient(clientId, archive)
                  },
                  onAssignClientToAuditor = { clientId, clientName, fyIds, auditorUid, auditorEmail, auditorName ->
                    viewModel.assignAuditorToClient(
                      auditorUid = auditorUid,
                      auditorName = auditorName,
                      auditorEmail = auditorEmail,
                      clientId = clientId,
                      clientName = clientName,
                      fiscalYearId = fyIds.firstOrNull().orEmpty()
                    )
                  },
                  onUnassignClientFromAuditor = { assignmentId, clientId, auditorUid ->
                    viewModel.unassignAuditorFromClient(assignmentId, clientId, auditorUid)
                  },
                  onReviewAccessRequest = { req, approve, targetCid, targetCName, note ->
                    if (approve) {
                      viewModel.approveClientAccessRequest(req, targetCid, targetCName, note)
                    } else {
                      viewModel.rejectClientAccessRequest(req, note)
                    }
                  },
                  onPreviewClientDeletion = { clientId ->
                    viewModel.loadClientDeletionImpact(clientId)
                  },
                  onExecutePermanentClientDeletion = { clientId, confirmText, deleteDrive ->
                    viewModel.executePermanentClientDeletion(clientId, confirmText, deleteDrive)
                  },
                  onPreviewUserDeletion = { targetUid ->
                    viewModel.loadUserDeletionImpact(targetUid)
                  },
                  onExecutePermanentUserDeletion = { targetUid, confirmText ->
                    viewModel.executePermanentUserDeletion(targetUid, confirmText)
                  },
                  onClearDeletionPreview = viewModel::clearDeletionPreview,
                  onSignOut = {
                    viewModel.clearSessionOnLogout()
                    signOutUser(
                      credentialManager = credentialManager,
                      onSignOutComplete = onSignedOut,
                      scope = scope
                    )
                  },
                  isDriveAuthorizationReady = isDriveAuthorizationReady,
                  driveErrorMessage = driveErrorMessage,
                  driveSessionAccountEmail = driveSessionAccountEmail
                )
              }

              else -> {
                when (activeAuditorTab) {
                  AuditorNavTab.HOME -> UserDashboardScreen(
                    allAssets = allAssets,
                    dailyBatches = dailyBatches,
                    userDisplayName = userProfile.effectiveDisplayName(),
                    userNickname = userProfile.effectiveNickname(),
                    onNavigateToFindAsset = { activeAuditorTab = AuditorNavTab.COUNTING },
                    onNavigateToDailySheets = {
                      activeActivitySubTab = AuditorActivitySubTab.DAILY_SHEETS
                      activeAuditorTab = AuditorNavTab.ACTIVITY
                    },
                    onNavigateToHistory = {
                      activeActivitySubTab = AuditorActivitySubTab.SUBMISSION_HISTORY
                      activeAuditorTab = AuditorNavTab.ACTIVITY
                    }
                  )

                  AuditorNavTab.COUNTING -> AssetListScreen(
                    allAssets = allAssets,
                    filteredAssets = filteredAssets,
                    filterState = filterState,
                    masterData = masterData,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    onUpdateFilter = viewModel::updateFilter,
                    onResetFilters = viewModel::resetFilters,
                    onSelectAsset = { selectedAssetKeyForCounting = it.farAsset.assetKey },
                    onCreateManualObservation = { searchedId ->
                      manualObservationPrefilledAssetId = searchedId
                      activeManualObservation = null
                      isCreatingManualObservation = true
                    }
                  )

                  AuditorNavTab.ACTIVITY -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                      Row(
                        modifier = Modifier
                          .fillMaxWidth()
                          .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                      ) {
                        AuditorActivitySubTab.entries.forEach { subTab ->
                          val label = if (subTab == AuditorActivitySubTab.NOTIFICATIONS && unreadNotificationCount > 0) {
                            "${subTab.label} ($unreadNotificationCount)"
                          } else subTab.label
                          FilterChip(
                            selected = activeActivitySubTab == subTab,
                            onClick = { activeActivitySubTab = subTab },
                            label = { Text(label) },
                            leadingIcon = if (subTab == AuditorActivitySubTab.NOTIFICATIONS) {
                              { Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null
                          )
                        }
                      }

                      Box(modifier = Modifier.weight(1f)) {
                        when (activeActivitySubTab) {
                          AuditorActivitySubTab.DAILY_SHEETS -> DailyInputSheetsScreen(
                            dailyBatches = dailyBatches,
                            userDisplayName = userProfile.effectiveDisplayName(),
                            onRequestReview = { batchId, countingDate, recordCount, notes ->
                              viewModel.createReviewRequest(batchId, countingDate, recordCount, notes)
                            },
                            onDownloadExcel = { batch ->
                              pendingDailyBatchForExcelExport = batch
                              exportExcelLauncher.launch("FA_Count_Daily_Sheet_${batch.countingDate}.xlsx")
                            },
                            onUploadCorrectedExcel = { _ ->
                              importExcelLauncher.launch(
                                arrayOf(
                                  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                  "application/octet-stream",
                                  "*/*"
                                )
                              )
                            },
                            onSubmitBatch = { batch ->
                              viewModel.submitDailyBatch(batch)
                            }
                          )

                          AuditorActivitySubTab.SUBMISSION_HISTORY -> SubmissionHistoryScreen(
                            assetsWithCounts = allAssets,
                            dailyBatches = dailyBatches,
                            onCorrectRecord = { item ->
                              selectedAssetKeyForCounting = item.farAsset.assetKey
                            },
                            onResubmitBatch = { batchId ->
                              val batch = dailyBatches.find { it.batchId == batchId || it.id == batchId }
                              if (batch != null) {
                                viewModel.submitDailyBatch(batch)
                              }
                            }
                          )

                          AuditorActivitySubTab.NOTIFICATIONS -> NotificationsScreen(
                            notifications = notifications,
                            onNotificationClick = { notification ->
                              viewModel.markNotificationRead(notification.id)
                              activeActivitySubTab = AuditorActivitySubTab.DAILY_SHEETS
                            }
                          )
                        }
                      }
                    }
                  }

                  AuditorNavTab.PROFILE -> ProfileScreen(
                    userProfile = userProfile,
                    activeClient = activeClient,
                    activeFiscalYearId = activeFiscalYearId,
                    assignedClients = clientAssignments.filter { it.auditorUid == currentUserId && it.active },
                    onSaveProfile = { name, role, nick ->
                      viewModel.updateProfileDisplayName(name, role, nick)
                    },
                    onSwitchClientWorkspace = { isSelectingClient = true },
                    onSignOut = {
                      viewModel.clearSessionOnLogout()
                      signOutUser(credentialManager, onSignedOut, scope)
                    }
                  )
                }
              }
            }
          }
        }
      }
    }
  }

  if (showProfileDialog) {
    UserProfileDialog(
      userProfile = userProfile,
      onSaveProfile = { newName, newRole, newNickname ->
        viewModel.updateProfileDisplayName(newName, newRole, newNickname)
      },
      onDismiss = { showProfileDialog = false }
    )
  }
}
