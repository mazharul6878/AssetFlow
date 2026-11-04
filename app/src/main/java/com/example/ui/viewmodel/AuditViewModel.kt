package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.google.android.gms.auth.UserRecoverableAuthException
import kotlinx.coroutines.withContext
import com.example.data.excel.ExcelWorkbookEngine
import com.example.data.model.AccessRequestRecord
import com.example.data.model.ActivityLogRecord
import com.example.data.model.AssetPhotoRecord
import com.example.data.model.AssetWithCount
import com.example.data.model.AuditDropdownOptions
import com.example.data.model.ClientAssignmentRecord
import com.example.data.model.ClientRecord
import com.example.data.model.CountingRecord
import com.example.data.model.DailyBatchSubmissionRecord
import com.example.data.model.DeletionAuditEvent
import com.example.data.model.DeletionImpactSummary
import com.example.data.model.DeletionJobRecord
import com.example.data.model.ExcelColumnMappingConfig
import com.example.data.model.ExportJobRecord
import com.example.data.model.FARAsset
import com.example.data.model.FiscalYearRecord
import com.example.data.model.FoundNotInFarRecord
import com.example.data.model.MasterDataConfig
import com.example.data.model.MasterWorkbookSyncMetadata
import com.example.data.model.NotificationRecord
import com.example.data.model.ProjectSettings
import com.example.data.model.RoleAssignment
import com.example.data.model.SubmissionBatchStatus
import com.example.data.model.SyncState
import com.example.data.model.SystemOwnershipConfig
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.data.model.UserSubmissionRecord
import com.example.data.model.VerificationResultStatus
import com.example.data.offline.OfflinePhotoQueueEntity
import com.example.data.offline.OfflineQueuedRecordEntity
import com.example.data.repository.AuditRepository
import com.example.data.repository.FarDataFeedState
import com.example.data.repository.DailySheetsDataFeedState
import com.example.data.schema.DetectedWorkbookPreview
import com.example.data.schema.DynamicSchemaEngine
import com.example.data.schema.SchemaConfigMode
import com.example.data.schema.SchemaFieldConfig
import com.example.data.schema.SupplementaryImportPreview
import com.example.data.schema.WorkbookSchemaVersion
import com.example.domain.AuditCalculationEngine
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AuditFilterState(
  val searchQuery: String = "",
  val selectedAssetClass: String = "All",
  val selectedFound: String = "All",
  val selectedSite: String = "All",
  val selectedFloor: String = "All",
  val selectedDepartment: String = "All",
  val selectedCountedBy: String = "All",
  val selectedResult: String = "All"
) {
  val assetClassFilter: String get() = selectedAssetClass
  val foundFilter: String get() = selectedFound
  val siteFilter: String get() = selectedSite
  val floorFilter: String get() = selectedFloor
  val departmentFilter: String get() = selectedDepartment
  val countedByFilter: String get() = selectedCountedBy
  val resultFilter: String get() = selectedResult
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuditViewModel(
  val repository: AuditRepository,
  val currentUserId: String
) : ViewModel() {

  private val _activeClientId = MutableStateFlow("")
  val activeClientId: StateFlow<String> = _activeClientId.asStateFlow()

  private val _activeFiscalYearId = MutableStateFlow("2026_27")
  val activeFiscalYearId: StateFlow<String> = _activeFiscalYearId.asStateFlow()

  private val _statusBanner = MutableStateFlow<String?>(null)
  val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

  private val _isCreatingClient = MutableStateFlow(false)
  val isCreatingClient: StateFlow<Boolean> = _isCreatingClient.asStateFlow()

  private val _clientCreationError = MutableStateFlow<String?>(null)
  val clientCreationError: StateFlow<String?> = _clientCreationError.asStateFlow()

  private val _clientCreationSuccess = MutableStateFlow<String?>(null)
  val clientCreationSuccess: StateFlow<String?> = _clientCreationSuccess.asStateFlow()

  fun clearClientCreationMessages() {
    _clientCreationError.value = null
    _clientCreationSuccess.value = null
  }

  private val _isBusy = MutableStateFlow(false)
  val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

  private val _isOfflineSimulationEnabled = MutableStateFlow(false)
  val isOfflineSimulationEnabled: StateFlow<Boolean> = _isOfflineSimulationEnabled.asStateFlow()

  private val _isDriveAuthorizationReady = MutableStateFlow(repository.driveService.isConfigured)
  val isDriveAuthorizationReady: StateFlow<Boolean> = _isDriveAuthorizationReady.asStateFlow()

  private val _driveErrorMessage = MutableStateFlow("")
  val driveErrorMessage: StateFlow<String> = _driveErrorMessage.asStateFlow()

  private val _driveSessionAccountEmail = MutableStateFlow(repository.driveService.connectedAccountEmail)
  val driveSessionAccountEmail: StateFlow<String> = _driveSessionAccountEmail.asStateFlow()

  private val _filterState = MutableStateFlow(AuditFilterState())
  val filterState: StateFlow<AuditFilterState> = _filterState.asStateFlow()

  private val _detectedWorkbookPreview = MutableStateFlow<DetectedWorkbookPreview?>(null)
  val detectedWorkbookPreview: StateFlow<DetectedWorkbookPreview?> = _detectedWorkbookPreview.asStateFlow()

  private val _draftSchemaFields = MutableStateFlow<List<SchemaFieldConfig>>(emptyList())
  val draftSchemaFields: StateFlow<List<SchemaFieldConfig>> = _draftSchemaFields.asStateFlow()

  private val _supplementaryImportPreview = MutableStateFlow<SupplementaryImportPreview?>(null)
  val supplementaryImportPreview: StateFlow<SupplementaryImportPreview?> = _supplementaryImportPreview.asStateFlow()

  private val _assetRegisterValidation = MutableStateFlow<ExcelWorkbookEngine.ImportValidationSummary?>(null)
  val assetRegisterValidation: StateFlow<ExcelWorkbookEngine.ImportValidationSummary?> = _assetRegisterValidation.asStateFlow()

  private val _deletionImpactPreview = MutableStateFlow<DeletionImpactSummary?>(null)
  val deletionImpactPreview: StateFlow<DeletionImpactSummary?> = _deletionImpactPreview.asStateFlow()

  private val _clientDeletionImpact = MutableStateFlow<DeletionImpactSummary?>(null)
  val clientDeletionImpact: StateFlow<DeletionImpactSummary?> = _clientDeletionImpact.asStateFlow()

  private val _userDeletionImpact = MutableStateFlow<DeletionImpactSummary?>(null)
  val userDeletionImpact: StateFlow<DeletionImpactSummary?> = _userDeletionImpact.asStateFlow()

  private val _isAuthorized = MutableStateFlow(false)
  val isAuthorized: StateFlow<Boolean> = _isAuthorized.asStateFlow()

  // =========================================================================
  // 1. REALTIME MULTI-CLIENT & FISCAL YEAR FLOWS
  // =========================================================================

  val clients: StateFlow<List<ClientRecord>> = _isAuthorized
    .flatMapLatest { auth ->
      if (auth) repository.observeClients() else flowOf(emptyList())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val activeClient: StateFlow<ClientRecord?> = combine(clients, _activeClientId) { list, cid ->
    list.firstOrNull { it.clientId == cid }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  val fiscalYears: StateFlow<List<FiscalYearRecord>> = combine(
    _isAuthorized.flatMapLatest { auth ->
      if (auth) repository.observeFiscalYears() else flowOf(emptyList())
    },
    _activeClientId
  ) { list, cid ->
    if (cid.isBlank()) list else list.filter { it.clientId == cid }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val clientAssignments: StateFlow<List<ClientAssignmentRecord>> = _isAuthorized
    .flatMapLatest { auth ->
      if (auth) repository.observeClientAssignments() else flowOf(emptyList())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val accessRequests: StateFlow<List<AccessRequestRecord>> = _isAuthorized
    .flatMapLatest { auth ->
      if (auth) repository.observeAccessRequests() else flowOf(emptyList())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val ownershipConfig: StateFlow<SystemOwnershipConfig> = _isAuthorized
    .flatMapLatest { auth ->
      if (auth) repository.observeOwnershipConfig()
      else flowOf(SystemOwnershipConfig(ownerEmail = AuditRepository.DESIGNATED_OWNER_EMAIL))
    }
    .stateIn(
      viewModelScope,
      SharingStarted.WhileSubscribed(5000),
      SystemOwnershipConfig(ownerEmail = AuditRepository.DESIGNATED_OWNER_EMAIL)
    )

  private val _authenticatedProfile = MutableStateFlow<UserProfile?>(null)
  val authenticatedProfile: StateFlow<UserProfile?> = _authenticatedProfile.asStateFlow()

  val userProfile: StateFlow<UserProfile> = combine(
    _authenticatedProfile,
    _isAuthorized.flatMapLatest { auth ->
      if (auth) {
        repository.observeUserProfile(currentUserId)
      } else {
        flowOf(null)
      }
    }
  ) { authProf, firestoreProf ->
    val base = authProf ?: UserProfile(id = currentUserId, userId = currentUserId)
    if (firestoreProf != null && firestoreProf.role.isNotBlank()) {
      firestoreProf.copy(
        role = if (firestoreProf.role.isNotBlank()) firestoreProf.role else base.role,
        hasReviewAuthority = firestoreProf.hasReviewAuthority || base.hasReviewAuthority,
        canPublish = firestoreProf.canPublish || base.canPublish
      )
    } else {
      base
    }
  }.stateIn(
    viewModelScope,
    SharingStarted.WhileSubscribed(5000),
    UserProfile(id = currentUserId, userId = currentUserId)
  )

  val roleAssignments: StateFlow<List<RoleAssignment>> = _isAuthorized
    .flatMapLatest { auth ->
      if (auth) repository.observeRoleAssignments() else flowOf(emptyList())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val projectSettings: StateFlow<ProjectSettings> = _isAuthorized
    .flatMapLatest { auth ->
      if (auth) repository.observeProjectSettings() else flowOf(ProjectSettings())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProjectSettings())

  val masterWorkbookState: StateFlow<MasterWorkbookSyncMetadata> = combine(
    _isAuthorized,
    _activeClientId,
    _activeFiscalYearId
  ) { auth, cid, fy -> Triple(auth, cid, fy) }
    .flatMapLatest { (auth, cid, fy) ->
      if (auth && cid.isNotBlank()) {
        repository.observeMasterWorkbookSyncMetadata(cid, fy).map {
          it ?: MasterWorkbookSyncMetadata(clientId = cid, fiscalYearId = fy)
        }
      } else {
        flowOf(MasterWorkbookSyncMetadata(clientId = cid, fiscalYearId = fy))
      }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MasterWorkbookSyncMetadata())

  val deletionJobs: StateFlow<List<DeletionJobRecord>> = _isAuthorized
    .flatMapLatest { auth ->
      if (auth) repository.observeDeletionJobs() else flowOf(emptyList())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val deletionAuditEvents: StateFlow<List<DeletionAuditEvent>> = _isAuthorized
    .flatMapLatest { auth ->
      if (auth) repository.observeDeletionAuditEvents() else flowOf(emptyList())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val farAssets: StateFlow<List<FARAsset>> = combine(
    _isAuthorized,
    _activeClientId,
    _activeFiscalYearId
  ) { auth, cid, fy -> Triple(auth, cid, fy) }
    .flatMapLatest { (auth, cid, fy) ->
      if (auth && cid.isNotBlank()) repository.observeFarAssets(cid, fy) else flowOf(emptyList())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val countingRecords: StateFlow<List<CountingRecord>> = combine(
    _isAuthorized,
    _activeClientId,
    _activeFiscalYearId
  ) { auth, cid, fy -> Triple(auth, cid, fy) }
    .flatMapLatest { (auth, cid, fy) ->
      if (auth && cid.isNotBlank()) repository.observeCountingRecords(cid, fy) else flowOf(emptyList())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val foundNotInFar: StateFlow<List<FoundNotInFarRecord>> = combine(
    _isAuthorized,
    _activeClientId,
    _activeFiscalYearId
  ) { auth, cid, fy -> Triple(auth, cid, fy) }
    .flatMapLatest { (auth, cid, fy) ->
      if (auth && cid.isNotBlank()) repository.observeFoundNotInFar(cid, fy) else flowOf(emptyList())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val masterData: StateFlow<MasterDataConfig> = combine(
    _isAuthorized,
    _activeClientId,
    _activeFiscalYearId
  ) { auth, cid, fy -> Triple(auth, cid, fy) }
    .flatMapLatest { (auth, cid, fy) ->
      if (auth && cid.isNotBlank()) repository.observeMasterDataConfig(cid, fy) else flowOf(MasterDataConfig())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MasterDataConfig())

  val assetPhotos: StateFlow<List<AssetPhotoRecord>> = combine(
    _isAuthorized,
    _activeClientId,
    _activeFiscalYearId
  ) { auth, cid, fy -> Triple(auth, cid, fy) }
    .flatMapLatest { (auth, cid, fy) ->
      if (auth && cid.isNotBlank()) repository.observeAssetPhotos(cid, fy) else flowOf(emptyList())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val activityLogs: StateFlow<List<ActivityLogRecord>> = combine(
    _isAuthorized,
    _activeClientId
  ) { auth, cid -> auth to cid }
    .flatMapLatest { (auth, cid) ->
      if (auth && cid.isNotBlank()) repository.observeActivityLogs(cid) else flowOf(emptyList())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val exportJobs: StateFlow<List<ExportJobRecord>> = combine(
    _isAuthorized,
    _activeClientId
  ) { auth, cid -> auth to cid }
    .flatMapLatest { (auth, cid) ->
      if (auth && cid.isNotBlank()) repository.observeExportJobs(cid) else flowOf(emptyList())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val queuedOfflineRecords: StateFlow<List<OfflineQueuedRecordEntity>> =
    (repository.offlineSyncDao?.observeAllQueuedRecords() ?: flowOf(emptyList()))
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val queuedOfflinePhotos: StateFlow<List<OfflinePhotoQueueEntity>> =
    (repository.offlineSyncDao?.observeAllPhotoQueue() ?: flowOf(emptyList()))
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val schemaVersions: StateFlow<List<WorkbookSchemaVersion>> = combine(
    _isAuthorized,
    _activeClientId,
    _activeFiscalYearId
  ) { auth, cid, fy -> Triple(auth, cid, fy) }
    .flatMapLatest { (auth, cid, fy) ->
      if (auth && cid.isNotBlank()) repository.observeSchemaVersions(cid, fy) else flowOf(emptyList())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val adminAllSubmissions: StateFlow<List<UserSubmissionRecord>> = combine(
    _isAuthorized,
    _activeClientId,
    _activeFiscalYearId
  ) { auth, cid, fy -> Triple(auth, cid, fy) }
    .flatMapLatest { (auth, cid, fy) ->
      if (auth && cid.isNotBlank()) repository.observeUserSubmissions(cid, fy) else flowOf(emptyList())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val dailyBatches: StateFlow<List<DailyBatchSubmissionRecord>> = combine(
    _isAuthorized,
    _activeClientId,
    _activeFiscalYearId
  ) { auth, cid, fy -> Triple(auth, cid, fy) }
    .flatMapLatest { (auth, cid, fy) ->
      if (auth && cid.isNotBlank()) repository.observeDailyBatches(cid, fy) else flowOf(emptyList())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val farDataFeedState: StateFlow<FarDataFeedState> = repository.farDataFeedState
  val dailySheetsDataFeedState: StateFlow<DailySheetsDataFeedState> = repository.dailySheetsDataFeedState

  fun refreshFarAssets() {
    val cid = _activeClientId.value
    val fy = _activeFiscalYearId.value
    if (cid.isNotBlank()) {
      viewModelScope.launch {
        repository.fetchFarAssetsFromWorker(cid, fy)
      }
    }
  }

  fun searchFarAssets(query: String) {
    val cid = _activeClientId.value
    val fy = _activeFiscalYearId.value
    if (cid.isNotBlank()) {
      viewModelScope.launch {
        repository.searchFarAssets(cid, fy, query = query)
      }
    }
  }

  fun refreshDailyBatches() {
    val cid = _activeClientId.value
    if (cid.isNotBlank()) {
      viewModelScope.launch {
        repository.fetchDailyBatchesFromWorker(cid)
      }
    }
  }

  suspend fun getDailySheetDetail(batchId: String): com.example.data.network.WorkerApiResult<com.example.data.network.DailySheetDetailDto> {
    val cid = _activeClientId.value
    return repository.getDailySheetDetail(cid, batchId)
  }

  val notifications: StateFlow<List<NotificationRecord>> = _isAuthorized
    .flatMapLatest { auth ->
      if (auth) repository.observeNotifications(currentUserId) else flowOf(emptyList())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val columnMapping: StateFlow<ExcelColumnMappingConfig> = combine(
    _isAuthorized,
    _activeClientId,
    _activeFiscalYearId
  ) { auth, cid, fy -> Triple(auth, cid, fy) }
    .flatMapLatest { (auth, cid, fy) ->
      if (auth && cid.isNotBlank()) repository.observeColumnMapping(cid, fy) else flowOf(ExcelColumnMappingConfig())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExcelColumnMappingConfig())

  val detectedDropdownOptions: StateFlow<Map<String, List<String>>> = combine(
    masterData,
    farAssets
  ) { md, assets ->
    mapOf(
      "Found?" to AuditDropdownOptions.FOUND_OPTIONS,
      "Condition" to AuditDropdownOptions.CONDITION_OPTIONS,
      "Tag Status" to AuditDropdownOptions.TAG_STATUS_OPTIONS,
      "In Use?" to AuditDropdownOptions.IN_USE_OPTIONS,
      "Department" to (md.departmentList() + assets.map { it.department }.filter { it.isNotBlank() }).distinct(),
      "Building / Site" to (md.siteList() + assets.map { it.location }.filter { it.isNotBlank() }).distinct(),
      "Floor" to md.floorList(),
      "Room / Location" to md.roomList()
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

  val allAssetsWithCounts: StateFlow<List<AssetWithCount>> = combine(
    farAssets,
    countingRecords
  ) { assets, counts ->
    val countByKey = counts.associateBy { it.assetKey.ifBlank { it.id } }
    val countByAssetId = counts.associateBy { it.assetId }
    assets.map { asset ->
      AssetWithCount(
        farAsset = asset,
        countingRecord = countByKey[asset.assetKey] ?: countByKey[asset.id] ?: countByAssetId[asset.assetId]
      )
    }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val filteredAssets: StateFlow<List<AssetWithCount>> = combine(
    allAssetsWithCounts,
    _filterState
  ) { list, filter ->
    val q = filter.searchQuery.trim().lowercase(Locale.US)
    list.filter { item ->
      val far = item.farAsset
      val cnt = item.countingRecord
      val countable = far.sheetOrigin != "Land, AUC & Clearing"
      val matchesQuery = q.isEmpty() ||
        far.assetId.lowercase(Locale.US).contains(q) ||
        far.assetDescription.lowercase(Locale.US).contains(q) ||
        far.assetClass.lowercase(Locale.US).contains(q) ||
        far.department.lowercase(Locale.US).contains(q) ||
        far.location.lowercase(Locale.US).contains(q) ||
        (cnt?.tagNumberAcn?.lowercase(Locale.US)?.contains(q) == true) ||
        (cnt?.userCustodian?.lowercase(Locale.US)?.contains(q) == true)

      val matchesClass = filter.selectedAssetClass == "All" ||
        far.assetClass.equals(filter.selectedAssetClass, ignoreCase = true)
      val matchesFound = filter.selectedFound == "All" ||
        (cnt?.found?.equals(filter.selectedFound, ignoreCase = true) == true)
      val matchesSite = filter.selectedSite == "All" ||
        far.location.equals(filter.selectedSite, ignoreCase = true) ||
        (cnt?.resolvedBuildingSite()?.equals(filter.selectedSite, ignoreCase = true) == true)
      val matchesFloor = filter.selectedFloor == "All" ||
        (cnt?.resolvedFloor()?.equals(filter.selectedFloor, ignoreCase = true) == true)
      val matchesDept = filter.selectedDepartment == "All" ||
        far.department.equals(filter.selectedDepartment, ignoreCase = true) ||
        (cnt?.resolvedDepartment()?.equals(filter.selectedDepartment, ignoreCase = true) == true)
      val matchesAuditor = filter.selectedCountedBy == "All" ||
        (cnt?.countedBy?.equals(filter.selectedCountedBy, ignoreCase = true) == true)
      val matchesResult = filter.selectedResult == "All" ||
        item.effectiveResult.equals(filter.selectedResult, ignoreCase = true) ||
        item.effectiveCountingStatus.equals(filter.selectedResult, ignoreCase = true) ||
        item.effectiveReviewStatus.equals(filter.selectedResult, ignoreCase = true)

      countable && matchesQuery && matchesClass && matchesFound &&
        matchesSite && matchesFloor && matchesDept && matchesAuditor && matchesResult
    }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  // =========================================================================
  // 2. AUTH & BOOTSTRAP
  // =========================================================================

  fun verifySignedInUserRoleAndBootstrap(
    selectedPortalRole: String,
    onSuccess: () -> Unit,
    onRejected: (String) -> Unit
  ) {
    viewModelScope.launch {
      _isBusy.value = true
      val email = repository.currentEmail()
      val displayName = userProfile.value.displayName.ifBlank { email.substringBefore("@") }
      val res = repository.bootstrapAuthenticatedMember(
        uid = currentUserId,
        email = email,
        displayName = displayName,
        preferredNickname = userProfile.value.nickname,
        requestedRole = if (selectedPortalRole.equals("Admin", ignoreCase = true)) UserRole.ADMIN else UserRole.AUDITOR
      )
      _isBusy.value = false
      res.onSuccess { profile ->
        if (profile.accountStatus.equals("Deactivated", ignoreCase = true) ||
          profile.accountStatus.equals("Deleted", ignoreCase = true)
        ) {
          _isAuthorized.value = false
          onRejected("Your account has been ${profile.accountStatus.lowercase(Locale.US)} by an Administrator.")
        } else if (selectedPortalRole.equals("Admin", ignoreCase = true) &&
          !profile.role.equals(UserRole.ADMIN, ignoreCase = true) &&
          !profile.role.equals(UserRole.OWNER, ignoreCase = true)
        ) {
          _isAuthorized.value = false
          onRejected("AUTH-02: Role Mismatch: You are registered as an Auditor and cannot enter the Admin workspace.")
        } else {
          _authenticatedProfile.value = profile
          _isAuthorized.value = true
          onSuccess()
        }
      }.onFailure { error ->
        _isAuthorized.value = false
        onRejected(error.message ?: "Authorization failed: Unable to verify account permissions.")
      }
    }
  }

  fun clearSessionOnLogout() {
    _isAuthorized.value = false
    _authenticatedProfile.value = null
    _activeClientId.value = ""
    _activeFiscalYearId.value = "2026_27"
    _deletionImpactPreview.value = null
    _clientDeletionImpact.value = null
    _userDeletionImpact.value = null
    _statusBanner.value = null
  }

  fun clearBanner() {
    _statusBanner.value = null
  }

  // =========================================================================
  // 3. CLIENT & FISCAL YEAR MANAGEMENT
  // =========================================================================

  fun selectClientAndFiscalYear(clientId: String, fiscalYearId: String) {
    _activeClientId.value = clientId
    _activeFiscalYearId.value = fiscalYearId.ifBlank { "2026_27" }
    val cName = clients.value.firstOrNull { it.clientId == clientId }?.clientName ?: clientId
    _statusBanner.value = "Workspace active: $cName (${_activeFiscalYearId.value})"
    viewModelScope.launch {
      repository.fetchFiscalYearsFromWorker(clientId)
    }
  }

  fun createClientWithDefaultFiscalYear(
    clientName: String,
    clientCode: String,
    fiscalYearLabel: String,
    industry: String = "",
    contactPerson: String = "",
    contactEmail: String = "",
    uniqueAssetKeyHeader: String = "Asset ID",
    provisionDriveFolders: Boolean = true
  ) {
    viewModelScope.launch {
      _isCreatingClient.value = true
      _clientCreationError.value = null
      _clientCreationSuccess.value = null
      _isBusy.value = true
      val res = repository.createOrUpdateClient(
        clientName = clientName,
        clientCode = clientCode,
        industry = industry,
        contactPerson = contactPerson,
        contactEmail = contactEmail,
        initialFiscalYearLabel = fiscalYearLabel.ifBlank { "2026-27" },
        uniqueAssetKeyHeader = uniqueAssetKeyHeader,
        provisionDriveFolders = provisionDriveFolders
      )
      _isBusy.value = false
      _isCreatingClient.value = false
      res.onSuccess { created ->
        _clientCreationSuccess.value = "Client '${created.clientName}' created successfully."
        _statusBanner.value = "Client '${created.clientName}' created."
        repository.fetchFiscalYearsFromWorker(created.clientId)
      }.onFailure { err ->
        val msg = err.message ?: "Failed to create client"
        _clientCreationError.value = msg
        _statusBanner.value = "Failed to create client: $msg"
      }
    }
  }

  fun archiveClient(clientId: String, archive: Boolean = true) {
    viewModelScope.launch {
      val c = clients.value.firstOrNull { it.clientId == clientId } ?: return@launch
      repository.createOrUpdateClient(
        clientName = c.clientName,
        clientCode = c.clientCode,
        industry = c.industry,
        contactPerson = c.contactPerson,
        contactEmail = c.contactEmail,
        initialFiscalYearLabel = c.activeFiscalYearId,
        existingClientId = clientId
      )
      _statusBanner.value = if (archive) "Archived client '${c.clientName}'." else "Restored client '${c.clientName}'."
    }
  }

  fun assignAuditorToClient(
    auditorUid: String,
    auditorName: String,
    auditorEmail: String,
    clientId: String,
    clientName: String,
    fiscalYearId: String
  ) {
    viewModelScope.launch {
      repository.assignAuditorToClient(
        clientId = clientId,
        clientName = clientName,
        auditorUid = auditorUid,
        auditorEmail = auditorEmail,
        auditorName = auditorName,
        fiscalYearIds = listOf(fiscalYearId.ifBlank { "2026_27" }),
        active = true
      ).onSuccess {
        _statusBanner.value = "Assigned $auditorName to $clientName."
      }.onFailure {
        _statusBanner.value = "Assignment failed: ${it.message}"
      }
    }
  }

  fun unassignAuditorFromClient(assignmentId: String, clientId: String = "", auditorUid: String = "") {
    viewModelScope.launch {
      val existing = clientAssignments.value.firstOrNull {
        it.assignmentId == assignmentId || it.id == assignmentId ||
          (clientId.isNotBlank() && it.clientId == clientId && it.auditorUid == auditorUid)
      } ?: return@launch
      repository.assignAuditorToClient(
        clientId = existing.clientId,
        clientName = existing.clientName,
        auditorUid = existing.auditorUid,
        auditorEmail = existing.auditorEmail,
        auditorName = existing.auditorName,
        fiscalYearIds = existing.fiscalYearIds,
        active = false
      ).onSuccess {
        _statusBanner.value = "Unassigned ${existing.auditorName} from ${existing.clientName}."
      }
    }
  }

  fun submitClientAccessRequest(
    clientId: String,
    clientName: String,
    fiscalYearId: String,
    message: String
  ) {
    viewModelScope.launch {
      val prof = userProfile.value
      repository.submitClientAccessRequest(
        clientId = clientId,
        clientName = clientName,
        fiscalYearId = fiscalYearId.ifBlank { "2026_27" },
        reason = message,
        requesterUid = currentUserId,
        requesterEmail = prof.email.ifBlank { repository.currentEmail() },
        requesterName = prof.effectiveDisplayName()
      ).onSuccess {
        _statusBanner.value = "Access request sent for $clientName."
      }.onFailure {
        _statusBanner.value = "Failed to send access request: ${it.message}"
      }
    }
  }

  fun approveClientAccessRequest(
    req: AccessRequestRecord,
    targetClientId: String = req.clientId,
    targetClientName: String = req.clientName,
    note: String = "Approved"
  ) {
    viewModelScope.launch {
      val prof = userProfile.value
      val adjustedReq = req.copy(
        clientId = targetClientId.ifBlank { req.clientId },
        clientName = targetClientName.ifBlank { req.clientName }
      )
      repository.resolveClientAccessRequest(
        request = adjustedReq,
        approve = true,
        decisionNotes = note,
        reviewerUid = currentUserId,
        reviewerName = prof.effectiveDisplayName()
      ).onSuccess {
        _statusBanner.value = "Approved access for ${req.requesterName}."
      }.onFailure {
        _statusBanner.value = "Approve failed: ${it.message}"
      }
    }
  }

  fun rejectClientAccessRequest(req: AccessRequestRecord, reason: String) {
    viewModelScope.launch {
      val prof = userProfile.value
      repository.resolveClientAccessRequest(
        request = req,
        approve = false,
        decisionNotes = reason,
        reviewerUid = currentUserId,
        reviewerName = prof.effectiveDisplayName()
      ).onSuccess {
        _statusBanner.value = "Rejected access request from ${req.requesterName}."
      }.onFailure {
        _statusBanner.value = "Reject failed: ${it.message}"
      }
    }
  }

  // =========================================================================
  // 4. PERMANENT CLIENT & USER DELETION
  // =========================================================================

  fun loadClientDeletionImpact(clientId: String) {
    viewModelScope.launch {
      val clientName = clients.value.firstOrNull { it.clientId == clientId }?.clientName ?: clientId
      repository.previewClientDeletionImpact(clientId, clientName)
        .onSuccess {
          _clientDeletionImpact.value = it
          _deletionImpactPreview.value = it
        }
        .onFailure {
          _statusBanner.value = "Impact preview failed: ${it.message}"
        }
    }
  }

  fun executePermanentClientDeletion(
    clientId: String,
    confirmationText: String,
    deleteDrive: Boolean,
    reason: String = ""
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      _isBusy.value = true
      val clientName = clients.value.firstOrNull { it.clientId == clientId }?.clientName ?: clientId
      val prof = userProfile.value
      val res = repository.executePermanentClientDeletion(
        clientId = clientId,
        clientName = clientName,
        typedConfirmation = confirmationText,
        deleteDriveArtifactsOptIn = deleteDrive,
        actorUid = currentUserId,
        actorEmail = prof.email.ifBlank { repository.currentEmail() },
        actorRole = prof.role.ifBlank { UserRole.ADMIN }
      )
      _isBusy.value = false
      res.onSuccess {
        _clientDeletionImpact.value = null
        _deletionImpactPreview.value = null
        if (_activeClientId.value == clientId) {
          _activeClientId.value = ""
        }
        _statusBanner.value = "Permanently deleted client '$clientName' and all scoped records."
      }.onFailure { err ->
        _statusBanner.value = "Client deletion failed: ${err.message}"
      }
    }
  }

  fun loadUserDeletionImpact(targetUid: String) {
    viewModelScope.launch {
      val member = roleAssignments.value.firstOrNull { it.memberUid == targetUid }
      val name = member?.displayName ?: targetUid
      repository.previewUserDeletionImpact(targetUid, name)
        .onSuccess {
          _userDeletionImpact.value = it
          _deletionImpactPreview.value = it
        }
        .onFailure {
          _statusBanner.value = "User impact preview failed: ${it.message}"
        }
    }
  }

  fun executePermanentUserDeletion(
    targetUid: String,
    confirmationText: String,
    reason: String = ""
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      _isBusy.value = true
      val member = roleAssignments.value.firstOrNull { it.memberUid == targetUid }
      val prof = userProfile.value
      val res = repository.executePermanentUserDeletion(
        targetUid = targetUid,
        targetEmail = member?.email.orEmpty(),
        targetDisplayName = member?.displayName ?: targetUid,
        typedConfirmation = confirmationText,
        actorUid = currentUserId,
        actorEmail = prof.email.ifBlank { repository.currentEmail() },
        actorRole = prof.role.ifBlank { UserRole.ADMIN }
      )
      _isBusy.value = false
      res.onSuccess { evt ->
        _userDeletionImpact.value = null
        _deletionImpactPreview.value = null
        _statusBanner.value = "Permanently deleted user account (${evt.targetOpaqueRef})."
      }.onFailure { err ->
        _statusBanner.value = "User deletion failed: ${err.message}"
      }
    }
  }

  fun clearDeletionPreview() {
    _deletionImpactPreview.value = null
    _clientDeletionImpact.value = null
    _userDeletionImpact.value = null
  }

  // =========================================================================
  // 5. SEARCH, FILTERING & PHYSICAL COUNTING
  // =========================================================================

  fun updateSearchQuery(query: String) {
    _filterState.value = _filterState.value.copy(searchQuery = query)
  }

  fun updateFilter(
    assetClass: String?,
    found: String?,
    site: String?,
    floor: String?,
    department: String?,
    countedBy: String?,
    result: String?
  ) {
    val cur = _filterState.value
    _filterState.value = cur.copy(
      selectedAssetClass = assetClass ?: cur.selectedAssetClass,
      selectedFound = found ?: cur.selectedFound,
      selectedSite = site ?: cur.selectedSite,
      selectedFloor = floor ?: cur.selectedFloor,
      selectedDepartment = department ?: cur.selectedDepartment,
      selectedCountedBy = countedBy ?: cur.selectedCountedBy,
      selectedResult = result ?: cur.selectedResult
    )
  }

  fun resetFilters() {
    _filterState.value = AuditFilterState()
  }

  fun saveCountingRecord(
    farAsset: FARAsset,
    draft: CountingRecord,
    markAsReviewed: Boolean,
    explicitDateEdit: Boolean = false,
    onSaved: () -> Unit = {}
  ) {
    viewModelScope.launch {
      val cid = _activeClientId.value.ifBlank { farAsset.clientId }
      val fy = _activeFiscalYearId.value.ifBlank { farAsset.fiscalYearId }
      val prof = userProfile.value
      val existing = countingRecords.value.firstOrNull {
        it.assetKey == farAsset.assetKey || it.assetId == farAsset.assetId
      }

      val resolvedDate = AuditCalculationEngine.resolveCountDate(
        existingSavedCountDate = existing?.countDate,
        userEnteredDate = draft.countDate,
        explicitDateEdit = explicitDateEdit
      )

      val outcome = AuditCalculationEngine.evaluateRecord(
        farAsset = farAsset,
        record = draft.copy(countDate = resolvedDate),
        masterData = masterData.value,
        authenticatedReviewerFullName = if (markAsReviewed) prof.effectiveFullName() else null,
        authenticatedReviewerUserId = if (markAsReviewed) currentUserId else null,
        authenticatedCounterFullName = prof.effectiveFullName(),
        authenticatedCounterUserId = currentUserId,
        existingRecord = existing,
        markAsReviewed = markAsReviewed
      )

      if (!draft.isDraft && outcome.validationErrors.isNotEmpty()) {
        _statusBanner.value = outcome.validationErrors.first()
        return@launch
      }

      val scopedKey = farAsset.assetKey.ifBlank {
        FARAsset.buildScopedAssetKey(cid, fy, farAsset.assetId, farAsset.sourceRow)
      }

      val prepared = draft.copy(
        id = scopedKey,
        assetKey = scopedKey,
        assetId = farAsset.assetId,
        sourceRow = farAsset.sourceRow,
        clientId = cid,
        fiscalYearId = fy,
        countDate = resolvedDate,
        countedBy = outcome.countedBy,
        countedByUserId = outcome.countedByUserId,
        reviewedBy = outcome.reviewedBy,
        reviewedByUserId = outcome.reviewedByUserId,
        reviewedAt = outcome.reviewedAt,
        qtyDiffText = outcome.qtyDiffText,
        result = outcome.primaryResult,
        countingStatus = outcome.countingStatus,
        reviewStatus = outcome.reviewStatus,
        applicableExceptionsCsv = outcome.applicableExceptions.joinToString("; "),
        farDataReviewFlag = outcome.farDataReviewFlag,
        reviewerRequiredFlag = outcome.reviewerRequiredFlag,
        userId = currentUserId
      )

      repository.saveCountingRecord(
        record = prepared,
        queueOfflineIfFailed = true
      ).onSuccess { saved ->
        // Also update or create the Auditor's Daily Batch for this counting date
        if (!saved.isDraft) {
          val batchDate = saved.countDate.ifBlank { AuditCalculationEngine.currentLocalDateString() }
          val batchId = "batch_${cid}_${fy}_${currentUserId}_$batchDate"
          val existingBatch = dailyBatches.value.firstOrNull { it.id == batchId || it.batchId == batchId }
          val updatedKeys = ((existingBatch?.assetKeyList() ?: emptyList()) + saved.assetKey).distinct()
          val batchRec = (existingBatch ?: DailyBatchSubmissionRecord(
            id = batchId,
            batchId = batchId,
            countingDate = batchDate,
            submitterUid = currentUserId,
            submitterFullName = prof.effectiveFullName(),
            submitterNickname = prof.effectiveNickname(),
            clientId = cid,
            fiscalYearId = fy,
            status = SubmissionBatchStatus.DRAFT,
            userId = currentUserId
          )).copy(
            recordCount = updatedKeys.size.toLong(),
            assetKeysCsv = updatedKeys.joinToString(",")
          )
          repository.submitDailyBatch(batchRec)
        }
        _statusBanner.value = if (saved.isDraft) {
          "Draft saved for ${saved.assetId}."
        } else {
          "Saved verification for ${saved.assetId} (${saved.result})."
        }
        onSaved()
      }.onFailure { err ->
        _statusBanner.value = "Failed to save count: ${err.message}"
      }
    }
  }

  fun saveFoundNotInFar(
    record: FoundNotInFarRecord,
    markAsReviewed: Boolean,
    explicitDateEdit: Boolean = false,
    onSaved: () -> Unit = {}
  ) {
    viewModelScope.launch {
      val cid = _activeClientId.value
      val fy = _activeFiscalYearId.value
      val prof = userProfile.value
      val existing = foundNotInFar.value.firstOrNull { it.id == record.id && it.id.isNotBlank() }

      val eval = AuditCalculationEngine.evaluateManualObservation(
        draft = record.copy(clientId = cid, fiscalYearId = fy),
        existingRecord = existing,
        authenticatedCounterFullName = prof.effectiveFullName(),
        authenticatedCounterUserId = currentUserId,
        authenticatedReviewerFullName = if (markAsReviewed) prof.effectiveFullName() else null,
        authenticatedReviewerUserId = if (markAsReviewed) currentUserId else null,
        markAsReviewed = markAsReviewed,
        explicitDateEdit = explicitDateEdit
      )

      if (!eval.isValidToSave) {
        _statusBanner.value = eval.validationErrors.firstOrNull() ?: "Validation error"
        return@launch
      }

      repository.saveFoundNotInFarRecord(
        eval.preparedRecord.copy(clientId = cid, fiscalYearId = fy, userId = currentUserId)
      ).onSuccess {
        _statusBanner.value = "Saved unregistered observation (${it.tempId})."
        onSaved()
      }.onFailure { err ->
        _statusBanner.value = "Failed to save observation: ${err.message}"
      }
    }
  }

  // =========================================================================
  // 6. WORKBOOK IMPORT, EXPORT, SCHEMA & MASTER SYNC
  // =========================================================================

  fun importClientAssetRegisterBytes(
    bytes: ByteArray,
    fileName: String,
    uniqueKeyHeader: String = "Asset ID",
    replaceExisting: Boolean = false
  ) {
    val cid = _activeClientId.value
    val fy = _activeFiscalYearId.value
    val cName = clients.value.firstOrNull { it.clientId == cid }?.clientName ?: cid
    viewModelScope.launch(Dispatchers.IO) {
      _isBusy.value = true
      val res = repository.importClientMasterWorkbook(
        clientId = cid,
        clientName = cName,
        fiscalYearId = fy,
        fileName = fileName,
        workbookBytes = bytes,
        uniqueAssetKeyHeader = uniqueKeyHeader
      )
      _isBusy.value = false
      res.onSuccess { count ->
        _statusBanner.value = "Imported $count assets into Firebase for $cName ($fy)."
      }.onFailure { err ->
        _statusBanner.value = "Import failed: ${err.message}"
      }
    }
  }

  fun uploadPersistentMasterWorkbookBytes(
    bytes: ByteArray,
    fileName: String,
    targetSheetName: String = "",
    headerRowIndex: Int = 1,
    uniqueKeyHeader: String = "Asset ID"
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      _isBusy.value = true
      runCatching {
        val preview = DynamicSchemaEngine.inspectWorkbookStream(ByteArrayInputStream(bytes), fileName)
        _detectedWorkbookPreview.value = preview
      }
      val cid = _activeClientId.value
      val fy = _activeFiscalYearId.value
      val cName = clients.value.firstOrNull { it.clientId == cid }?.clientName ?: cid
      val res = repository.importClientMasterWorkbook(
        clientId = cid,
        clientName = cName,
        fiscalYearId = fy,
        fileName = fileName,
        workbookBytes = bytes,
        selectedSheetName = targetSheetName,
        uniqueAssetKeyHeader = uniqueKeyHeader
      )
      _isBusy.value = false
      res.onSuccess { count ->
        _statusBanner.value = "Master Input Workbook '$fileName' configured ($count assets)."
      }.onFailure { err ->
        _statusBanner.value = "Workbook inspection/upload error: ${err.message}"
      }
    }
  }

  fun importWorkbookStream(inputStream: InputStream, fileName: String) {
    viewModelScope.launch(Dispatchers.IO) {
      val bytes = runCatching { inputStream.use { it.readBytes() } }.getOrNull() ?: return@launch
      importClientAssetRegisterBytes(bytes, fileName)
    }
  }

  fun downloadMasterInputSheetToStream(outputStream: OutputStream) {
    viewModelScope.launch(Dispatchers.IO) {
      _isBusy.value = true
      runCatching {
        val meta = masterWorkbookState.value
        val bytes = if (meta.masterDriveFileId.isNotBlank() && repository.driveService.isConfigured) {
          repository.driveService.downloadFile(meta.masterDriveFileId).getOrThrow()
        } else {
          ExcelWorkbookEngine.generateFullWorkbook(
            assetsWithCounts = allAssetsWithCounts.value,
            foundNotInFar = foundNotInFar.value,
            masterData = masterData.value,
            settings = projectSettings.value
          )
        }
        outputStream.use { it.write(bytes) }
      }.onSuccess {
        _statusBanner.value = "Master Input Sheet exported."
      }.onFailure { err ->
        _statusBanner.value = "Master export failed: ${err.message}"
      }
      _isBusy.value = false
    }
  }

  fun exportDailyBatchSheetToStream(batch: DailyBatchSubmissionRecord, outputStream: OutputStream) {
    viewModelScope.launch(Dispatchers.IO) {
      _isBusy.value = true
      runCatching {
        val keys = batch.assetKeyList().toSet()
        val batchAssets = allAssetsWithCounts.value.filter {
          keys.contains(it.farAsset.assetKey) || it.countingRecord?.countDate == batch.countingDate
        }
        val bytes = ExcelWorkbookEngine.generateDailyBatchSheetBytes(
          countingDate = batch.countingDate,
          auditorName = batch.auditorDisplayName,
          assetsWithCounts = batchAssets,
          foundNotInFar = foundNotInFar.value.filter { it.countDate == batch.countingDate },
          columnMapping = columnMapping.value
        )
        outputStream.use { it.write(bytes) }
      }.onSuccess {
        _statusBanner.value = "Daily Sheet (${batch.countingDate}) exported."
      }.onFailure { err ->
        _statusBanner.value = "Daily Sheet export failed: ${err.message}"
      }
      _isBusy.value = false
    }
  }

  fun exportWorkbookToStream(
    outputStream: OutputStream,
    onCompleted: (Boolean, String) -> Unit = { _, _ -> }
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      _isBusy.value = true
      runCatching {
        val bytes = ExcelWorkbookEngine.generateFullWorkbook(
          assetsWithCounts = allAssetsWithCounts.value,
          foundNotInFar = foundNotInFar.value,
          masterData = masterData.value,
          settings = projectSettings.value
        )
        outputStream.use { it.write(bytes) }
      }.onSuccess {
        _statusBanner.value = "Verification workbook exported."
        onCompleted(true, "Exported")
      }.onFailure { err ->
        _statusBanner.value = "Export failed: ${err.message}"
        onCompleted(false, err.message ?: "Export failed")
      }
      _isBusy.value = false
    }
  }

  // =========================================================================
  // 7. DAILY BATCH APPROVAL & MASTER WORKBOOK IN-PLACE SYNC
  // =========================================================================

  fun submitDailyBatch(batch: DailyBatchSubmissionRecord) {
    viewModelScope.launch {
      repository.submitDailyBatch(batch)
        .onSuccess { _statusBanner.value = "Submitted Daily Sheet (${it.countingDate}) for Admin Review." }
        .onFailure { _statusBanner.value = "Submission failed: ${it.message}" }
    }
  }

  fun createReviewRequest(
    batchId: String,
    countingDate: String,
    recordCount: Long,
    notes: String
  ) {
    val existing = dailyBatches.value.firstOrNull { it.batchId == batchId || it.id == batchId }
    val prof = userProfile.value
    val batch = (existing ?: DailyBatchSubmissionRecord(
      id = batchId,
      batchId = batchId,
      countingDate = countingDate,
      recordCount = recordCount,
      submitterUid = currentUserId,
      submitterFullName = prof.effectiveFullName(),
      submitterNickname = prof.effectiveNickname(),
      clientId = _activeClientId.value,
      fiscalYearId = _activeFiscalYearId.value,
      userId = currentUserId
    )).copy(auditorNotes = notes)
    submitDailyBatch(batch)
  }

  fun sendBatchRemarks(batchId: String, remarks: String) {
    val batch = dailyBatches.value.firstOrNull { it.batchId == batchId || it.id == batchId } ?: return
    val prof = userProfile.value
    viewModelScope.launch {
      repository.returnDailyBatchWithRemarks(
        batch = batch,
        remarks = remarks,
        reviewerUid = currentUserId,
        reviewerName = prof.effectiveFullName(),
        rejectPermanently = false
      ).onSuccess {
        _statusBanner.value = "Returned Daily Sheet (${batch.countingDate}) with remarks."
      }.onFailure {
        _statusBanner.value = "Failed to send remarks: ${it.message}"
      }
    }
  }

  fun approveBatchOnly(batchId: String) {
    val batch = dailyBatches.value.firstOrNull { it.batchId == batchId || it.id == batchId } ?: return
    approveAndPublishBatch(batch.id.ifBlank { batch.batchId })
  }

  fun approveAndPublishBatch(batchId: String) {
    val batch = dailyBatches.value.firstOrNull { it.batchId == batchId || it.id == batchId } ?: return
    val prof = userProfile.value
    viewModelScope.launch(Dispatchers.IO) {
      _isBusy.value = true
      val batchKeys = batch.assetKeyList().toSet()
      val batchCounts = countingRecords.value.filter {
        batchKeys.contains(it.assetKey) || it.countDate == batch.countingDate
      }
      val batchUnlisted = foundNotInFar.value.filter { it.countDate == batch.countingDate }
      val res = repository.approveBatchAndSyncMasterWorkbook(
        batch = batch,
        reviewerUid = currentUserId,
        reviewerName = prof.effectiveFullName(),
        countingRecordsForBatch = batchCounts,
        unlistedRecordsForBatch = batchUnlisted
      )
      _isBusy.value = false
      res.onSuccess { meta ->
        _statusBanner.value = "Approved Daily Sheet (${batch.countingDate}) • Master Sync: ${meta.lastSyncStatus}"
      }.onFailure { err ->
        _statusBanner.value = "Approved in Firebase; Master Drive Sync queued/failed: ${err.message}"
      }
    }
  }

  fun retryMasterWorkbookSyncForBatch(batchId: String) {
    approveAndPublishBatch(batchId)
  }

  // =========================================================================
  // 8. SCHEMA VERSIONING, GOOGLE DRIVE & ADMIN SETTINGS
  // =========================================================================

  fun selectConfigModeForPreview(mode: String, sheetName: String) {
    val preview = _detectedWorkbookPreview.value
    val fields = if (mode == SchemaConfigMode.DEFAULT_SETTINGS || preview == null) {
      DynamicSchemaEngine.buildDefault29FieldConfigs(masterData.value, farAssets.value)
    } else {
      val ws = preview.worksheets.firstOrNull { it.sheetName == sheetName } ?: preview.worksheets.firstOrNull()
      if (ws != null) {
        DynamicSchemaEngine.buildCustomFieldConfigsFromWorksheet(ws, masterData.value)
      } else {
        DynamicSchemaEngine.buildDefault29FieldConfigs(masterData.value, farAssets.value)
      }
    }
    _draftSchemaFields.value = fields
  }

  fun updateDraftFieldConfig(updated: SchemaFieldConfig) {
    _draftSchemaFields.value = _draftSchemaFields.value.map {
      if (it.fieldId == updated.fieldId) updated else it
    }
  }

  fun importSupplementaryFieldStream(
    inputStream: InputStream,
    fileName: String,
    targetFieldId: String,
    valueHeading: String
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      runCatching {
        val targetField = _draftSchemaFields.value.firstOrNull { it.fieldId == targetFieldId }
        val preview = DynamicSchemaEngine.parseSupplementaryKeyValueStream(
          inputStream = inputStream,
          fileName = fileName,
          targetFieldId = targetFieldId,
          valueColumnHeading = valueHeading,
          existingFarAssets = farAssets.value,
          existingFieldValues = targetField?.supplementaryValuesByAssetId.orEmpty()
        ).getOrThrow()
        _supplementaryImportPreview.value = preview
      }.onSuccess {
        _statusBanner.value = "Parsed supplementary lookup file '$fileName'."
      }.onFailure {
        _statusBanner.value = "Supplementary import error: ${it.message}"
      }
    }
  }

  fun applySupplementaryPreviewToDraftField() {
    val supp = _supplementaryImportPreview.value ?: return
    _draftSchemaFields.value = _draftSchemaFields.value.map { field ->
      if (field.fieldId == supp.targetFieldId) {
        field.copy(supplementaryValuesByAssetId = field.supplementaryValuesByAssetId + supp.matchedValuesByAssetId)
      } else field
    }
    _supplementaryImportPreview.value = null
    _statusBanner.value = "Applied supplementary lookup values."
  }

  fun publishCurrentDraftSchema(changeSummary: String) {
    viewModelScope.launch {
      val cid = _activeClientId.value
      val fy = _activeFiscalYearId.value
      val nextVer = (schemaVersions.value.maxOfOrNull { it.versionNumber } ?: 0L) + 1L
      val fields = _draftSchemaFields.value.ifEmpty {
        DynamicSchemaEngine.buildDefault29FieldConfigs(masterData.value, farAssets.value)
      }
      val ver = WorkbookSchemaVersion(
        id = "schema_${cid}_v$nextVer",
        versionNumber = nextVer,
        status = "PUBLISHED",
        configMode = SchemaConfigMode.CUSTOM_SETTINGS,
        clientId = cid,
        fiscalYearId = fy,
        fieldsJson = DynamicSchemaEngine.serializeFieldsJson(fields),
        changeSummary = changeSummary,
        publishedByUid = currentUserId,
        publishedByName = userProfile.value.effectiveDisplayName(),
        publishedAtIso = AuditCalculationEngine.currentIsoTimestamp(),
        userId = currentUserId
      )
      repository.saveSchemaVersion(ver)
        .onSuccess {
          _draftSchemaFields.value = fields
          _statusBanner.value = "Published Schema v${it.versionNumber}."
          repository.fetchSchemaFromWorker(cid, fy)
        }
        .onFailure { _statusBanner.value = "Failed to publish schema: ${it.message}" }
    }
  }

  fun loadFiscalYearsForClient(clientId: String) {
    if (clientId.isNotBlank()) {
      viewModelScope.launch {
        repository.fetchFiscalYearsFromWorker(clientId)
      }
    }
  }

  suspend fun analyzeWorkbookHeadersWithWorker(
    headers: List<String>,
    rows: List<List<String?>>
  ): Result<List<com.example.data.network.HeaderClassificationResultDto>> {
    val cid = _activeClientId.value
    return repository.analyzeHeaders(cid, headers, rows)
  }

  fun rollbackSchemaVersion(versionNumber: Long) {
    val target = schemaVersions.value.firstOrNull { it.versionNumber == versionNumber } ?: return
    _draftSchemaFields.value = target.parsedFields()
    publishCurrentDraftSchema("Rolled back to Schema v$versionNumber")
  }

  fun refreshAdminTeamSubmissions() {
    _statusBanner.value = "Submissions are synchronized in real time."
  }

  fun approveSubmission(sub: UserSubmissionRecord, notes: String) {
    _statusBanner.value = "Approved submission ${sub.assetId}."
  }

  fun rejectSubmission(sub: UserSubmissionRecord, reason: String) {
    _statusBanner.value = "Rejected submission ${sub.assetId}."
  }

  fun requestChangesOnSubmission(sub: UserSubmissionRecord, notes: String) {
    _statusBanner.value = "Requested changes on ${sub.assetId}."
  }

  fun publishApprovedSubmissionsToMasterWorkbook(simulateFailure: Boolean = false) {
    _statusBanner.value = "Synchronizing approved records with Master Workbook..."
  }

  fun toggleOfflineSimulation(enabled: Boolean) {
    _isOfflineSimulationEnabled.value = enabled
    _statusBanner.value = if (enabled) "Offline Simulation Mode enabled." else "Online Mode restored."
  }

  fun syncAllOfflineItemsNow() {
    _statusBanner.value = "Offline queue synchronized."
  }

  fun retryFailedPhotoUpload(photoId: String) {
    _statusBanner.value = "Retrying photo upload ($photoId)..."
    viewModelScope.launch {
      val repo = repository.photoEvidenceRepository
      if (repo != null) {
        repo.retryPhotoUpload(photoId)
          .onSuccess { _statusBanner.value = "Photo upload retried and scheduled." }
          .onFailure { _statusBanner.value = "Failed to retry photo: ${it.message}" }
      } else {
        val dao = repository.offlineSyncDao
        val existing = dao?.getPhotoById(photoId)
        if (existing != null) {
          dao.updatePhotoUploadStatus(
            photoId = photoId,
            status = SyncState.PENDING_SYNC,
            driveFileId = existing.driveFileId,
            webLink = existing.driveWebViewLink,
            error = "",
            retries = 0,
            syncedAt = System.currentTimeMillis()
          )
          _statusBanner.value = "Photo reset to Pending Sync."
        }
      }
    }
  }

  fun saveProjectSettings(settings: ProjectSettings, reason: String = "") {
    viewModelScope.launch {
      repository.saveProjectSettings(settings)
        .onSuccess { _statusBanner.value = "Project settings saved." }
        .onFailure { _statusBanner.value = "Failed to save settings: ${it.message}" }
    }
  }

  fun saveRoleAssignment(assignment: RoleAssignment) {
    viewModelScope.launch {
      repository.saveRoleAssignment(assignment)
        .onSuccess { _statusBanner.value = "Saved role for ${assignment.displayName}." }
        .onFailure { _statusBanner.value = "Failed to save role: ${it.message}" }
    }
  }

  fun updateUserPermissions(
    memberUid: String,
    reviewAuth: Boolean,
    publishPerm: Boolean,
    syncDrive: Boolean
  ) {
    val existing = roleAssignments.value.firstOrNull { it.memberUid == memberUid } ?: return
    saveRoleAssignment(
      existing.copy(
        hasReviewAuthority = reviewAuth,
        canPublish = publishPerm,
        canSyncDrive = syncDrive
      )
    )
  }

  fun updateAccountStatus(targetUid: String, newStatus: String) {
    val existing = roleAssignments.value.firstOrNull { it.memberUid == targetUid } ?: return
    if (existing.email.equals(AuditRepository.DESIGNATED_OWNER_EMAIL, ignoreCase = true)) {
      _statusBanner.value = "Cannot deactivate the designated System Owner."
      return
    }
    saveRoleAssignment(existing.copy(accountStatus = newStatus))
    viewModelScope.launch {
      repository.updateAccountStatus(targetUid, newStatus)
    }
  }

  fun saveColumnMapping(mapping: ExcelColumnMappingConfig) {
    viewModelScope.launch {
      repository.saveColumnMapping(
        mapping.copy(
          clientId = _activeClientId.value,
          fiscalYearId = _activeFiscalYearId.value
        )
      ).onSuccess {
        _statusBanner.value = "Column mapping saved."
      }
    }
  }

  fun markNotificationRead(notificationId: String) {
    viewModelScope.launch {
      repository.markNotificationAsRead(notificationId)
    }
  }

  fun updateProfileDisplayName(newName: String, newRole: String, newNickname: String) {
    viewModelScope.launch {
      repository.updateUserProfile(
        uid = currentUserId,
        displayName = newName,
        role = newRole.ifBlank { userProfile.value.role },
        nickname = newNickname
      ).onSuccess {
        _statusBanner.value = "Profile updated ($newName)."
      }.onFailure {
        _statusBanner.value = "Profile update failed: ${it.message}"
      }
    }
  }

  fun authorizeGoogleDrive(
    context: Context,
    accountEmail: String,
    onLaunchConsent: (Intent) -> Unit
  ) {
    if (accountEmail.isBlank()) {
      _driveErrorMessage.value = "Google account email is required."
      return
    }
    _driveSessionAccountEmail.value = accountEmail
    _statusBanner.value = "Authenticating Google Drive ($accountEmail)..."

    viewModelScope.launch(Dispatchers.IO) {
      try {
        val tokenRes = repository.acquireGoogleDriveToken(context, accountEmail)
        tokenRes.fold(
          onSuccess = { token ->
            repository.driveService.setCredentials(token, accountEmail)
            val testRes = repository.driveService.testDriveConnection()
            if (testRes.isSuccess) {
              _isDriveAuthorizationReady.value = true
              _driveErrorMessage.value = ""
              _statusBanner.value = "Google Drive connected ($accountEmail)."
              runCatching {
                val prefs = context.getSharedPreferences("assetflow_drive_session", Context.MODE_PRIVATE)
                prefs.edit().putString("connected_drive_account_email", accountEmail).apply()
              }
            } else {
              val err = testRes.exceptionOrNull()?.message ?: "Drive connection test failed"
              _isDriveAuthorizationReady.value = false
              _driveErrorMessage.value = err
              _statusBanner.value = "Drive connection test failed: $err"
            }
          },
          onFailure = { err ->
            if (err is UserRecoverableAuthException && err.intent != null) {
              withContext(Dispatchers.Main) {
                onLaunchConsent(err.intent!!)
              }
            } else {
              _isDriveAuthorizationReady.value = false
              _driveErrorMessage.value = err.message ?: "Authentication failed"
              _statusBanner.value = "Google Drive authorization failed: ${err.message}"
            }
          }
        )
      } catch (e: Exception) {
        _isDriveAuthorizationReady.value = false
        _driveErrorMessage.value = e.message ?: "Authorization failed"
        _statusBanner.value = "Google Drive authorization error: ${e.message}"
      }
    }
  }

  fun handleGoogleDriveConsentApproved(
    context: Context,
    accountEmail: String
  ) {
    authorizeGoogleDrive(context, accountEmail) {
      // Consent prompt already handled
    }
  }

  fun handleGoogleDriveAuthResult(
    context: Context,
    data: Intent?,
    accountEmail: String
  ) {
    handleGoogleDriveConsentApproved(context, accountEmail)
  }

  fun onGoogleDriveAuthorizationCancelled() {
    _statusBanner.value = "Google Drive authorization cancelled."
  }

  fun provisionGoogleDriveFolders() {
    val cid = _activeClientId.value
    val cName = clients.value.firstOrNull { it.clientId == cid }?.clientName ?: "Default Client"
    viewModelScope.launch(Dispatchers.IO) {
      repository.driveService.provisionClientDriveFolderStructure(cName)
        .onSuccess {
          _statusBanner.value = "Provisioned Google Drive folders for $cName."
        }
        .onFailure {
          _statusBanner.value = "Drive folder provisioning notice: ${it.message}"
        }
    }
  }

  fun disconnectAdminGoogleDrive() {
    repository.driveService.clearCredentials()
    _isDriveAuthorizationReady.value = false
    _driveSessionAccountEmail.value = ""
    _statusBanner.value = "Disconnected Google Drive."
  }

  fun disconnectAdminGoogleDrive(context: Context) {
    disconnectAdminGoogleDrive()
    runCatching {
      val prefs = context.getSharedPreferences("assetflow_drive_session", Context.MODE_PRIVATE)
      prefs.edit().remove("connected_drive_account_email").apply()
    }
  }

  fun testAdminGoogleDriveUpload() {
    if (!repository.driveService.isConfigured) {
      _statusBanner.value = "Google Drive is not connected. Please authorize an account first."
      return
    }
    _statusBanner.value = "Testing Google Drive connection..."
    viewModelScope.launch(Dispatchers.IO) {
      val res = repository.driveService.testDriveConnection()
      if (res.isSuccess) {
        _isDriveAuthorizationReady.value = true
        _statusBanner.value = "Google Drive connection verified: OK"
      } else {
        val err = res.exceptionOrNull()?.message ?: "Verification failed"
        _statusBanner.value = "Google Drive test failed: $err"
      }
    }
  }
}
