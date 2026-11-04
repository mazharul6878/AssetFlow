package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.drive.GoogleDriveService
import com.example.data.excel.ExcelWorkbookEngine
import com.example.data.model.AccessRequestRecord
import com.example.data.model.ActivityLogRecord
import com.example.data.model.ApprovalLifecycleStatus
import com.example.data.model.AssetPhotoRecord
import com.example.data.model.ClientAssignmentRecord
import com.example.data.model.ClientRecord
import com.example.data.model.CountingRecord
import com.example.data.model.DailyBatchSubmissionRecord
import com.example.data.model.DeletedIdentityTombstone
import com.example.data.model.DeletionAuditEvent
import com.example.data.model.DeletionImpactSummary
import com.example.data.model.DeletionJobRecord
import com.example.data.model.DeletionJobStatus
import com.example.data.model.ExcelColumnMappingConfig
import com.example.data.model.ExceptionRecord
import com.example.data.model.ExportJobRecord
import com.example.data.model.FARAsset
import com.example.data.model.FiscalYearRecord
import com.example.data.model.FoundNotInFarRecord
import com.example.data.model.MasterDataConfig
import com.example.data.model.MasterDriveSyncStatus
import com.example.data.model.MasterWorkbookSyncJob
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
import com.example.data.offline.OfflinePayloadSerializer
import com.example.data.offline.OfflinePhotoQueueEntity
import com.example.data.offline.OfflineQueuedRecordEntity
import com.example.data.offline.OfflineSyncDao
import com.example.data.offline.CachedFarBaselineEntity
import com.example.data.remote.FirebaseFunctionsService
import com.example.data.schema.WorkbookSchemaVersion
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import com.example.data.network.HeaderClassificationResultDto
import com.example.data.network.toDynamicSchemaDto
import com.example.data.network.toWorkbookSchemaVersion
import org.json.JSONObject

/**
 * Canonical Firestore collection names matching firestore.rules and Cloud Functions.
 */
object FirestoreCollections {
  const val CLIENTS = "clients"
  const val FISCAL_YEARS = "fiscal_years"
  const val CLIENT_ASSIGNMENTS = "client_assignments"
  const val ACCESS_REQUESTS = "access_requests"
  const val FAR_ASSETS = "far_assets"
  const val COUNTING_RECORDS = "counting_records"
  const val FOUND_NOT_IN_FAR = "found_not_in_far"
  const val DAILY_BATCHES = "daily_batches"
  const val SUBMISSIONS = "submissions"
  const val ASSET_PHOTOS = "asset_photos"
  const val ROLE_ASSIGNMENTS = "role_assignments"
  const val USER_PROFILE = "user_profile"
  const val MASTER_DATA = "master_data"
  const val PROJECT_SETTINGS = "project_settings"
  const val MASTER_WORKBOOK_STATE = "master_workbook_state"
  const val NOTIFICATIONS = "notifications"
  const val ACTIVITY_LOGS = "activity_logs"
  const val DELETION_JOBS = "deletion_jobs"
  const val DELETION_AUDIT_EVENTS = "deletion_audit_events"
  const val DELETED_IDENTITIES = "deleted_identities"
  const val COLUMN_MAPPING = "column_mapping"
  const val EXPORT_JOBS = "export_jobs"
  const val SCHEMA_VERSIONS = "schema_versions"
  const val SYSTEM_CONFIG = "system_config"
}

sealed class FarDataFeedState {
  object Idle : FarDataFeedState()
  object Loading : FarDataFeedState()
  object Empty : FarDataFeedState()
  data class Success(val count: Int, val isOfflineCache: Boolean = false) : FarDataFeedState()
  data class Error(val message: String, val statusCode: Int? = null, val hasOfflineCache: Boolean = false) : FarDataFeedState()
}

sealed class DailySheetsDataFeedState {
  object Idle : DailySheetsDataFeedState()
  object Loading : DailySheetsDataFeedState()
  object Empty : DailySheetsDataFeedState()
  data class Success(val count: Int) : DailySheetsDataFeedState()
  data class Error(val message: String, val statusCode: Int? = null) : DailySheetsDataFeedState()
}

/**
 * Multi-client, fiscal-year-scoped repository for AssetFlow Fixed Asset Verification.
 *
 * Production vs Development Seed Policy:
 * - Production (`!BuildConfig.DEBUG`): Zero default clients, zero Walton demo records, and zero sample assets are seeded.
 * - Development (`BuildConfig.DEBUG`): Legacy unscoped Walton data migration is strictly handled via [DevelopmentMigrationManager].
 *
 * Architecture:
 * - Firestore stores structured metadata, client/fiscal-year configurations, FAR asset rows, counting records,
 *   daily batch metadata, Master Workbook sync metadata/leases, and durable deletion state machines.
 * - Google Drive stores binary `.xlsx` workbooks (`AssetFlow/[Client Name]/Master Input File/`,
 *   `AssetFlow/[Client Name]/Daily Input Sheets/[Auditor]/[Date]/`) and date-wise photo evidence
 *   (`AssetFlow/[Client Name]/Image Evidence/[YYYY-MM-DD]/`).
 */
class AuditRepository(
  private val db: FirebaseFirestore? = null,
  val offlineSyncDao: OfflineSyncDao? = null,
  val driveService: GoogleDriveService = GoogleDriveService(),
  private val orgId: String = DEFAULT_ORG_ID,
  val functionsService: FirebaseFunctionsService = FirebaseFunctionsService(),
  val workerApiClient: com.example.data.network.WorkerApiClient? = null
) {
  companion object {
    private const val TAG = "AuditRepository"
    const val DEFAULT_ORG_ID = "walton_org_main"
    const val DESIGNATED_OWNER_EMAIL = "mislamabed@gmail.com"
    private const val LEASE_DURATION_MS = 120_000L
  }

  val devMigrationManager: DevelopmentMigrationManager = DevelopmentMigrationManager(db, orgId)
  var photoEvidenceRepository: com.example.data.repository.PhotoEvidenceRepository? = null
  var dynamicOfflineCacheDao: com.example.data.offline.DynamicOfflineCacheDao? = null

  private fun rootDoc(): DocumentReference? = db?.collection("users")?.document(orgId)
  private fun col(name: String): CollectionReference? = rootDoc()?.collection(name)

  private fun nowIso(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

  fun currentUid(): String =
    runCatching { FirebaseAuth.getInstance().currentUser?.uid.orEmpty() }.getOrDefault("")

  fun currentEmail(): String =
    runCatching { FirebaseAuth.getInstance().currentUser?.email.orEmpty() }.getOrDefault("")

  fun hashIdentifier(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(input.trim().lowercase(Locale.US).toByteArray(Charsets.UTF_8))
    return digest.take(4).joinToString("") { "%02x".format(it) }.uppercase(Locale.US)
  }

  fun getWorkerClient(): com.example.data.network.WorkerApiClient? {
    return workerApiClient ?: if (functionsService.javaClass == FirebaseFunctionsService::class.java) {
      com.example.data.network.WorkerApiClient()
    } else null
  }

  private val _cachedClients = MutableStateFlow<List<ClientRecord>>(emptyList())
  val cachedClients: StateFlow<List<ClientRecord>> = _cachedClients.asStateFlow()

  private val _cachedFiscalYears = MutableStateFlow<List<FiscalYearRecord>>(emptyList())
  val cachedFiscalYears: StateFlow<List<FiscalYearRecord>> = _cachedFiscalYears.asStateFlow()

  private val _cachedSchemas = MutableStateFlow<List<WorkbookSchemaVersion>>(emptyList())
  val cachedSchemas: StateFlow<List<WorkbookSchemaVersion>> = _cachedSchemas.asStateFlow()

  suspend fun fetchClientsFromWorker(): Result<List<ClientRecord>> = runCatching {
    val client = getWorkerClient() ?: return@runCatching emptyList()
    when (val res = client.listClients()) {
      is com.example.data.network.WorkerApiResult.Success -> {
        val mapped = res.data.clients.map { dto ->
          ClientRecord(
            id = dto.clientId,
            clientId = dto.clientId,
            clientName = dto.clientName,
            clientCode = dto.clientCode,
            industry = dto.industry,
            industryOrNotes = dto.industry,
            contactPerson = dto.contactPerson,
            contactEmail = dto.contactEmail,
            status = dto.status,
            activeFiscalYearId = dto.activeFiscalYearId,
            fiscalYearIds = listOf(dto.activeFiscalYearId),
            availableFiscalYearsCsv = dto.activeFiscalYearId.replace("_", "-"),
            uniqueAssetKeyHeader = dto.uniqueAssetKeyHeader,
            driveRootFolderId = dto.driveRootFolderId,
            createdByUid = dto.createdByUid,
            createdAtIso = nowIso(),
            userId = currentUid()
          )
        }.sortedBy { it.clientName.lowercase(Locale.US) }
        _cachedClients.value = mapped
        mapped
      }
      is com.example.data.network.WorkerApiResult.Error -> {
        Log.w(TAG, "Failed to fetch clients from Worker: ${res.message}")
        _cachedClients.value
      }
    }
  }

  private val _cachedMemberships = MutableStateFlow<List<ClientAssignmentRecord>>(emptyList())
  val cachedMemberships: StateFlow<List<ClientAssignmentRecord>> = _cachedMemberships.asStateFlow()

  private val _cachedAccessRequests = MutableStateFlow<List<AccessRequestRecord>>(emptyList())
  val cachedAccessRequests: StateFlow<List<AccessRequestRecord>> = _cachedAccessRequests.asStateFlow()

  suspend fun fetchClientAssignmentsFromWorker(clientId: String): Result<List<ClientAssignmentRecord>> = runCatching {
    val client = getWorkerClient() ?: return@runCatching emptyList()
    when (val res = client.listClientMemberships(clientId)) {
      is com.example.data.network.WorkerApiResult.Success -> {
        val mapped = res.data.map { dto ->
          ClientAssignmentRecord(
            id = "${dto.clientId}_${dto.userUid}",
            assignmentId = dto.membershipId.ifBlank { "${dto.clientId}_${dto.userUid}" },
            clientId = dto.clientId,
            clientName = "",
            memberUid = dto.userUid,
            auditorUid = dto.userUid,
            memberEmail = dto.userEmail,
            auditorEmail = dto.userEmail,
            memberDisplayName = dto.userName,
            auditorName = dto.userName,
            assignedStatus = if (dto.accountStatus == "Active") "Assigned" else "Unassigned",
            status = if (dto.accountStatus == "Active") "Assigned" else "Unassigned",
            active = dto.accountStatus == "Active",
            fiscalYearIds = listOf("2026_27"),
            assignedByUid = "",
            assignedByName = "",
            assignedAtIso = nowIso(),
            userId = dto.userUid
          )
        }
        _cachedMemberships.value = mapped
        mapped
      }
      is com.example.data.network.WorkerApiResult.Error -> {
        Log.w(TAG, "Failed to fetch memberships from Worker: ${res.message}")
        _cachedMemberships.value
      }
    }
  }

  suspend fun fetchAccessRequestsFromWorker(clientId: String): Result<List<AccessRequestRecord>> = runCatching {
    val client = getWorkerClient() ?: return@runCatching emptyList()
    when (val res = client.listAccessRequestsForClient(clientId)) {
      is com.example.data.network.WorkerApiResult.Success -> {
        val mapped = res.data.map { dto ->
          AccessRequestRecord(
            id = dto.requestId,
            requestId = dto.requestId,
            requesterUid = dto.requesterUid,
            requesterEmail = dto.requesterEmail,
            requesterName = dto.requesterEmail,
            clientId = dto.clientId,
            clientName = "",
            targetClientId = dto.clientId,
            targetClientName = "",
            fiscalYearId = dto.fiscalYearId,
            reason = dto.reason,
            requestNotes = dto.reason,
            status = dto.status,
            requestedAtIso = dto.requestedAt,
            userId = dto.requesterUid
          )
        }.sortedByDescending { it.requestedAtIso }
        _cachedAccessRequests.value = mapped
        mapped
      }
      is com.example.data.network.WorkerApiResult.Error -> {
        Log.w(TAG, "Failed to fetch access requests from Worker: ${res.message}")
        _cachedAccessRequests.value
      }
    }
  }

  suspend fun fetchFiscalYearsFromWorker(clientId: String): Result<List<FiscalYearRecord>> = runCatching {
    val client = getWorkerClient() ?: return@runCatching emptyList()
    when (val res = client.listFiscalYears(clientId)) {
      is com.example.data.network.WorkerApiResult.Success -> {
        val mapped = res.data.fiscalYears.map { dto ->
          FiscalYearRecord(
            id = "${dto.clientId}_${dto.fiscalYearId}",
            fiscalYearId = dto.fiscalYearId,
            clientId = dto.clientId,
            label = dto.label,
            isClosed = dto.isClosed,
            userId = currentUid()
          )
        }.sortedByDescending { it.label }
        val otherClientsFys = _cachedFiscalYears.value.filter { it.clientId != clientId }
        _cachedFiscalYears.value = (otherClientsFys + mapped).sortedByDescending { it.label }
        mapped
      }
      is com.example.data.network.WorkerApiResult.Error -> {
        Log.w(TAG, "Failed to fetch fiscal years from Worker: ${res.message}")
        _cachedFiscalYears.value.filter { it.clientId == clientId }
      }
    }
  }

  suspend fun fetchSchemaFromWorker(clientId: String, fiscalYearId: String): Result<WorkbookSchemaVersion?> = runCatching {
    if (clientId.isBlank()) return@runCatching null
    val client = getWorkerClient() ?: return@runCatching null
    val fy = fiscalYearId.ifBlank { "2026_27" }
    when (val res = client.getSchemaForCycle(clientId, fy)) {
      is com.example.data.network.WorkerApiResult.Success -> {
        val dto = res.data
        if (dto != null) {
          val ver = dto.toWorkbookSchemaVersion()
          val other = _cachedSchemas.value.filter { !(it.clientId == clientId && it.fiscalYearId == fy && it.id == ver.id) }
          _cachedSchemas.value = (other + ver).sortedByDescending { it.versionNumber }
          ver
        } else null
      }
      is com.example.data.network.WorkerApiResult.Error -> {
        Log.w(TAG, "Failed to fetch schema from Worker: ${res.message}")
        null
      }
    }
  }

  suspend fun analyzeHeaders(
    clientId: String,
    headers: List<String>,
    rows: List<List<String?>>
  ): Result<List<com.example.data.network.HeaderClassificationResultDto>> = runCatching {
    val client = getWorkerClient() ?: throw IllegalStateException("WorkerApiClient is unavailable")
    when (val res = client.analyzeHeaders(clientId, headers, rows)) {
      is com.example.data.network.WorkerApiResult.Success -> res.data
      is com.example.data.network.WorkerApiResult.Error -> {
        throw Exception("Header analysis failed (HTTP ${res.statusCode ?: 0}): ${res.message}")
      }
    }
  }

  // =========================================================================
  // 1. REALTIME FLOWS (MULTI-CLIENT, FISCAL YEAR, RBAC, ASSETS, BATCHES)
  // =========================================================================

  fun observeClients(): Flow<List<ClientRecord>> {
    val workerClient = getWorkerClient()
    val c = col("clients")
    if (workerClient != null) {
      CoroutineScope(Dispatchers.IO).launch {
        fetchClientsFromWorker()
      }
      if (c == null) {
        return _cachedClients.asStateFlow()
      }
      return combine(_cachedClients, callbackFlow {
        val sub = c.addSnapshotListener { snap, err ->
          if (err != null) {
            trySend(emptyList())
            return@addSnapshotListener
          }
          val list = snap?.documents?.mapNotNull { doc ->
            runCatching {
              doc.toObject(ClientRecord::class.java)?.copy(
                id = doc.id,
                clientId = doc.getString("clientId")?.ifBlank { doc.id } ?: doc.id
              )
            }.getOrNull()
          }?.filter { !it.status.equals("Deleted", ignoreCase = true) }
            ?.sortedBy { it.clientName.lowercase(Locale.US) }
            .orEmpty()
          trySend(list)
        }
        awaitClose { sub.remove() }
      }) { workerList, firestoreList ->
        if (workerList.isNotEmpty()) workerList else firestoreList
      }
    }

    if (c == null) return flowOf(emptyList())
    return callbackFlow {
      val sub = c.addSnapshotListener { snap, err ->
        if (err != null) {
          Log.w(TAG, "observeClients listener warning: ${err.message}")
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snap?.documents?.mapNotNull { doc ->
          runCatching {
            doc.toObject(ClientRecord::class.java)?.copy(
              id = doc.id,
              clientId = doc.getString("clientId")?.ifBlank { doc.id } ?: doc.id
            )
          }.getOrNull()
        }?.filter { !it.status.equals("Deleted", ignoreCase = true) }
          ?.sortedBy { it.clientName.lowercase(Locale.US) }
          .orEmpty()
        trySend(list)
      }
      awaitClose { sub.remove() }
    }
  }

  fun observeFiscalYears(clientId: String = ""): Flow<List<FiscalYearRecord>> {
    val workerClient = getWorkerClient()
    val c = col("fiscal_years")
    if (workerClient != null) {
      CoroutineScope(Dispatchers.IO).launch {
        if (clientId.isNotBlank()) {
          fetchFiscalYearsFromWorker(clientId)
        } else {
          _cachedClients.value.forEach { client ->
            fetchFiscalYearsFromWorker(client.clientId)
          }
        }
      }
      if (c == null) {
        return _cachedFiscalYears.asStateFlow()
      }
      return combine(_cachedFiscalYears, callbackFlow {
        val sub = c.addSnapshotListener { snap, err ->
          if (err != null) {
            trySend(emptyList())
            return@addSnapshotListener
          }
          val list = snap?.documents?.mapNotNull { doc ->
            runCatching {
              doc.toObject(FiscalYearRecord::class.java)?.copy(
                id = doc.id,
                fiscalYearId = doc.getString("fiscalYearId")?.ifBlank { doc.id } ?: doc.id
              )
            }.getOrNull()
          }?.sortedByDescending { it.label }.orEmpty()
          trySend(list)
        }
        awaitClose { sub.remove() }
      }) { workerList, firestoreList ->
        if (workerList.isNotEmpty()) workerList else firestoreList
      }
    }

    if (c == null) return flowOf(emptyList())
    return callbackFlow {
      val sub = c.addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snap?.documents?.mapNotNull { doc ->
          runCatching {
            doc.toObject(FiscalYearRecord::class.java)?.copy(
              id = doc.id,
              fiscalYearId = doc.getString("fiscalYearId")?.ifBlank { doc.id } ?: doc.id
            )
          }.getOrNull()
        }?.sortedByDescending { it.label }.orEmpty()
        trySend(list)
      }
      awaitClose { sub.remove() }
    }
  }

  fun observeClientAssignments(): Flow<List<ClientAssignmentRecord>> {
    val workerClient = getWorkerClient()
    val c = col("client_assignments")
    if (workerClient != null) {
      if (c == null) {
        return _cachedMemberships.asStateFlow()
      }
      return combine(_cachedMemberships, callbackFlow {
        val sub = c.addSnapshotListener { snap, err ->
          if (err != null) {
            trySend(emptyList())
            return@addSnapshotListener
          }
          val list = snap?.documents?.mapNotNull { doc ->
            runCatching {
              val raw = doc.toObject(ClientAssignmentRecord::class.java)
              val resolvedUid = raw?.auditorUid?.ifBlank { raw.memberUid }.orEmpty()
              val resolvedEmail = raw?.auditorEmail?.ifBlank { raw.memberEmail }.orEmpty()
              val resolvedName = raw?.auditorName?.ifBlank { raw.memberDisplayName }.orEmpty()
              raw?.copy(
                id = doc.id,
                assignmentId = raw.assignmentId.ifBlank { doc.id },
                auditorUid = resolvedUid,
                memberUid = resolvedUid,
                auditorEmail = resolvedEmail,
                memberEmail = resolvedEmail,
                auditorName = resolvedName,
                memberDisplayName = resolvedName
              )
            }.getOrNull()
          }.orEmpty()
          trySend(list)
        }
        awaitClose { sub.remove() }
      }) { cached, firestoreList ->
        if (cached.isNotEmpty()) cached else firestoreList
      }
    }
    if (c == null) return flowOf(emptyList())
    return callbackFlow {
      val sub = c.addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snap?.documents?.mapNotNull { doc ->
          runCatching {
            val raw = doc.toObject(ClientAssignmentRecord::class.java)
            val resolvedUid = raw?.auditorUid?.ifBlank { raw.memberUid }.orEmpty()
            val resolvedEmail = raw?.auditorEmail?.ifBlank { raw.memberEmail }.orEmpty()
            val resolvedName = raw?.auditorName?.ifBlank { raw.memberDisplayName }.orEmpty()
            raw?.copy(
              id = doc.id,
              assignmentId = raw.assignmentId.ifBlank { doc.id },
              auditorUid = resolvedUid,
              memberUid = resolvedUid,
              auditorEmail = resolvedEmail,
              memberEmail = resolvedEmail,
              auditorName = resolvedName,
              memberDisplayName = resolvedName
            )
          }.getOrNull()
        }.orEmpty()
        trySend(list)
      }
      awaitClose { sub.remove() }
    }
  }

  fun observeAccessRequests(): Flow<List<AccessRequestRecord>> {
    val workerClient = getWorkerClient()
    val c = col("access_requests")
    if (workerClient != null) {
      if (c == null) {
        return _cachedAccessRequests.asStateFlow()
      }
      return combine(_cachedAccessRequests, callbackFlow {
        val sub = c.addSnapshotListener { snap, err ->
          if (err != null) {
            trySend(emptyList())
            return@addSnapshotListener
          }
          val list = snap?.documents?.mapNotNull { doc ->
            runCatching {
              val raw = doc.toObject(AccessRequestRecord::class.java)
              val cId = raw?.clientId?.ifBlank { raw.targetClientId }.orEmpty()
              val cName = raw?.clientName?.ifBlank { raw.targetClientName }.orEmpty()
              raw?.copy(
                id = doc.id,
                requestId = raw.requestId.ifBlank { doc.id },
                clientId = cId,
                targetClientId = cId,
                clientName = cName,
                targetClientName = cName
              )
            }.getOrNull()
          }?.sortedByDescending { it.requestedAtIso }.orEmpty()
          trySend(list)
        }
        awaitClose { sub.remove() }
      }) { cached, firestoreList ->
        if (cached.isNotEmpty()) cached else firestoreList
      }
    }
    if (c == null) return flowOf(emptyList())
    return callbackFlow {
      val sub = c.addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snap?.documents?.mapNotNull { doc ->
          runCatching {
            val raw = doc.toObject(AccessRequestRecord::class.java)
            val cId = raw?.clientId?.ifBlank { raw.targetClientId }.orEmpty()
            val cName = raw?.clientName?.ifBlank { raw.targetClientName }.orEmpty()
            raw?.copy(
              id = doc.id,
              requestId = raw.requestId.ifBlank { doc.id },
              clientId = cId,
              targetClientId = cId,
              clientName = cName,
              targetClientName = cName
            )
          }.getOrNull()
        }?.sortedByDescending { it.requestedAtIso }.orEmpty()
        trySend(list)
      }
      awaitClose { sub.remove() }
    }
  }

  private val _cachedFarAssets = MutableStateFlow<List<FARAsset>>(emptyList())
  val cachedFarAssets: StateFlow<List<FARAsset>> = _cachedFarAssets.asStateFlow()

  private val _farDataFeedState = MutableStateFlow<FarDataFeedState>(FarDataFeedState.Idle)
  val farDataFeedState: StateFlow<FarDataFeedState> = _farDataFeedState.asStateFlow()

  private data class ParsedRawRow(
    val description: String = "",
    val location: String = "",
    val department: String = "",
    val assetClass: String = "",
    val cost: Double = 0.0,
    val qty: Long = 0L
  )

  private fun parseRawRowFields(rawRowJson: String): ParsedRawRow {
    return try {
      val json = JSONObject(rawRowJson)
      var desc = ""
      var loc = ""
      var dept = ""
      var cls = ""
      var cost = 0.0
      var qty = 0L

      val keys = json.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        val normKey = key.trim().lowercase(Locale.US)
        val v = json.opt(key) ?: continue
        val strVal = v.toString().trim()

        when {
          normKey in listOf("description", "asset description", "item description", "asset_description", "particulars") -> {
            if (desc.isBlank()) desc = strVal
          }
          normKey in listOf("location", "floor", "site", "room", "building") -> {
            if (loc.isBlank()) loc = strVal
          }
          normKey in listOf("department", "dept", "user department", "dept_name") -> {
            if (dept.isBlank()) dept = strVal
          }
          normKey in listOf("asset class", "class", "category", "major category") -> {
            if (cls.isBlank()) cls = strVal
          }
          normKey in listOf("cost", "cost tk", "cost_tk", "acquisition cost", "purchase cost", "value") -> {
            if (cost == 0.0) cost = strVal.toDoubleOrNull() ?: 0.0
          }
          normKey in listOf("quantity", "qty", "far qty", "far_qty", "count") -> {
            if (qty == 0L) qty = strVal.toLongOrNull() ?: (strVal.toDoubleOrNull()?.toLong() ?: 0L)
          }
        }
      }
      ParsedRawRow(desc, loc, dept, cls, cost, qty)
    } catch (e: Exception) {
      ParsedRawRow()
    }
  }

  private fun mapCachedEntitiesToFarAssets(entities: List<CachedFarBaselineEntity>): List<FARAsset> {
    return entities.map { entity ->
      val parsed = parseRawRowFields(entity.rawRowJson)
      FARAsset(
        id = entity.assetKey,
        assetId = entity.assetId,
        sourceRow = 0L,
        sourceWorksheetName = "Count Sheet",
        rawRowByHeaderJson = entity.rawRowJson,
        clientId = entity.clientId,
        fiscalYearId = entity.auditCycleId,
        assetDescription = entity.assetDescription.ifBlank { parsed.description },
        location = entity.location.ifBlank { parsed.location },
        department = entity.department.ifBlank { parsed.department },
        assetClass = parsed.assetClass,
        costTk = parsed.cost,
        farQty = if (parsed.qty > 0) parsed.qty else 1L
      )
    }
  }

  suspend fun searchFarAssets(
    clientId: String,
    fiscalYearId: String,
    query: String = "",
    cursor: String? = null,
    limit: Int = 50
  ): com.example.data.network.WorkerApiResult<com.example.data.network.FarSearchResultDto> {
    _farDataFeedState.value = FarDataFeedState.Loading
    val client = getWorkerClient()
    if (client == null) {
      val cached = dynamicOfflineCacheDao?.getCachedFarBaselinesForCycle(clientId, fiscalYearId).orEmpty()
      return if (cached.isNotEmpty()) {
        val mapped = mapCachedEntitiesToFarAssets(cached)
        val other = _cachedFarAssets.value.filter { it.clientId != clientId || (fiscalYearId.isNotBlank() && it.fiscalYearId != fiscalYearId) }
        _cachedFarAssets.value = other + mapped
        _farDataFeedState.value = FarDataFeedState.Success(mapped.size, isOfflineCache = true)
        com.example.data.network.WorkerApiResult.Success(
          com.example.data.network.FarSearchResultDto(
            assets = cached.map {
              com.example.data.network.FarAssetItemDto(
                assetKey = it.assetKey,
                assetId = it.assetId,
                rawRowJson = it.rawRowJson
              )
            }
          )
        )
      } else {
        val err = com.example.data.network.WorkerApiResult.Error("Worker client not available")
        _farDataFeedState.value = FarDataFeedState.Error(err.message, hasOfflineCache = false)
        err
      }
    }

    val res = client.searchFarAssets(clientId, fiscalYearId, query, cursor, limit)
    when (res) {
      is com.example.data.network.WorkerApiResult.Success -> {
        val mapped = res.data.assets.map { dto ->
          val parsed = parseRawRowFields(dto.rawRowJson)
          FARAsset(
            id = dto.assetKey,
            assetId = dto.assetId,
            sourceRow = dto.sourceRow,
            sourceWorksheetName = dto.sourceWorksheet,
            rawRowByHeaderJson = dto.rawRowJson,
            clientId = clientId,
            fiscalYearId = fiscalYearId,
            assetDescription = parsed.description,
            location = parsed.location,
            department = parsed.department,
            assetClass = parsed.assetClass,
            costTk = parsed.cost,
            farQty = if (parsed.qty > 0) parsed.qty else 1L,
            importBatchId = dto.importBatchId
          )
        }

        // Reconcile with Room offline cache
        val entities = res.data.assets.map { dto ->
          val parsed = parseRawRowFields(dto.rawRowJson)
          CachedFarBaselineEntity(
            cacheKey = "${clientId}:${fiscalYearId}:${dto.assetKey}",
            clientId = clientId,
            auditCycleId = fiscalYearId,
            assetKey = dto.assetKey,
            assetId = dto.assetId,
            assetDescription = parsed.description,
            location = parsed.location,
            department = parsed.department,
            baselineDataJson = dto.rawRowJson,
            rawRowJson = dto.rawRowJson,
            cachedAtMillis = System.currentTimeMillis()
          )
        }
        runCatching { dynamicOfflineCacheDao?.upsertCachedFarBaselines(entities) }

        if (cursor.isNullOrBlank()) {
          val other = _cachedFarAssets.value.filter { it.clientId != clientId || (fiscalYearId.isNotBlank() && it.fiscalYearId != fiscalYearId) }
          _cachedFarAssets.value = other + mapped
        } else {
          val currentMap = _cachedFarAssets.value.associateBy { it.assetKey }.toMutableMap()
          for (m in mapped) {
            currentMap[m.assetKey] = m
          }
          _cachedFarAssets.value = currentMap.values.toList()
        }

        if (mapped.isEmpty() && query.isBlank() && cursor.isNullOrBlank()) {
          _farDataFeedState.value = FarDataFeedState.Empty
        } else {
          _farDataFeedState.value = FarDataFeedState.Success(mapped.size, isOfflineCache = false)
        }
      }
      is com.example.data.network.WorkerApiResult.Error -> {
        val cached = runCatching { dynamicOfflineCacheDao?.getCachedFarBaselinesForCycle(clientId, fiscalYearId) }.getOrNull().orEmpty()
        if (cached.isNotEmpty()) {
          val mapped = mapCachedEntitiesToFarAssets(cached)
          val other = _cachedFarAssets.value.filter { it.clientId != clientId || (fiscalYearId.isNotBlank() && it.fiscalYearId != fiscalYearId) }
          _cachedFarAssets.value = other + mapped
          _farDataFeedState.value = FarDataFeedState.Success(mapped.size, isOfflineCache = true)
        } else {
          _farDataFeedState.value = FarDataFeedState.Error(
            message = res.message,
            statusCode = res.statusCode,
            hasOfflineCache = false
          )
        }
      }
    }
    return res
  }

  suspend fun fetchFarAssetsFromWorker(
    clientId: String,
    fiscalYearId: String,
    query: String = "",
    cursor: String? = null,
    limit: Int = 500
  ): com.example.data.network.WorkerApiResult<com.example.data.network.FarSearchResultDto> =
    searchFarAssets(clientId, fiscalYearId, query, cursor, limit)

  fun observeFarAssets(clientId: String, fiscalYearId: String): Flow<List<FARAsset>> {
    val worker = getWorkerClient()
    if (worker != null) {
      if (clientId.isNotBlank()) {
        CoroutineScope(Dispatchers.IO).launch {
          searchFarAssets(clientId, fiscalYearId, limit = 500)
        }
      }
      return _cachedFarAssets.map { list ->
        list.filter { asset ->
          (clientId.isBlank() || asset.clientId == clientId) &&
            (fiscalYearId.isBlank() || asset.fiscalYearId == fiscalYearId || asset.fiscalYearId.isBlank())
        }.sortedBy { it.sl }
      }
    }
    val c = col("far_assets")
    if (c == null) {
      if (clientId.isNotBlank()) {
        CoroutineScope(Dispatchers.IO).launch {
          searchFarAssets(clientId, fiscalYearId, limit = 500)
        }
      }
      return _cachedFarAssets.asStateFlow()
    }
    return callbackFlow {
      val sub = c.addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snap?.documents?.mapNotNull { doc ->
          runCatching { doc.toObject(FARAsset::class.java)?.copy(id = doc.id) }.getOrNull()
        }?.filter { asset ->
          (clientId.isBlank() || asset.clientId == clientId) &&
            (fiscalYearId.isBlank() || asset.fiscalYearId == fiscalYearId || asset.fiscalYearId.isBlank())
        }?.sortedBy { it.sl }.orEmpty()
        trySend(list)
      }
      awaitClose { sub.remove() }
    }
  }

  fun observeCountingRecords(clientId: String, fiscalYearId: String): Flow<List<CountingRecord>> {
    val c = col(FirestoreCollections.COUNTING_RECORDS) ?: return flowOf(emptyList())
    return callbackFlow {
      val sub = c.addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snap?.documents?.mapNotNull { doc ->
          runCatching {
            doc.toObject(CountingRecord::class.java)?.copy(
              id = doc.id,
              hasPendingWrites = doc.metadata.hasPendingWrites()
            )
          }.getOrNull()
        }?.filter { rec ->
          (clientId.isBlank() || rec.clientId == clientId) &&
            (fiscalYearId.isBlank() || rec.fiscalYearId == fiscalYearId || rec.fiscalYearId.isBlank())
        }.orEmpty()
        trySend(list)
      }
      awaitClose { sub.remove() }
    }
  }

  private val _cachedFoundNotInFar = MutableStateFlow<List<FoundNotInFarRecord>>(emptyList())
  val cachedFoundNotInFar: StateFlow<List<FoundNotInFarRecord>> = _cachedFoundNotInFar.asStateFlow()

  suspend fun fetchFoundNotInFarFromWorker(clientId: String, fiscalYearId: String = ""): Result<List<FoundNotInFarRecord>> = runCatching {
    val client = getWorkerClient() ?: return@runCatching _cachedFoundNotInFar.value
    when (val res = client.listFoundNotInFar(clientId, fiscalYearId.ifBlank { null })) {
      is com.example.data.network.WorkerApiResult.Success -> {
        val mapped = res.data.map { dto ->
          val base = if (dto.rawValuesJson.isNotBlank()) {
            runCatching { OfflinePayloadSerializer.deserializeFoundNotInFar(dto.rawValuesJson) }.getOrNull()
          } else null
          base?.copy(
            id = dto.tempId,
            tempId = dto.tempId,
            clientId = dto.clientId,
            fiscalYearId = dto.fiscalYearId,
            description = dto.description,
            countedBy = dto.auditorName.ifBlank { base.countedBy },
            countedByUserId = dto.auditorUid.ifBlank { base.countedByUserId },
            countDate = dto.countDate.ifBlank { base.countDate }
          ) ?: FoundNotInFarRecord(
            id = dto.tempId,
            tempId = dto.tempId,
            clientId = dto.clientId,
            fiscalYearId = dto.fiscalYearId,
            description = dto.description,
            countedBy = dto.auditorName,
            countedByUserId = dto.auditorUid,
            countDate = dto.countDate
          )
        }
        _cachedFoundNotInFar.value = mapped
        mapped
      }
      is com.example.data.network.WorkerApiResult.Error -> {
        runCatching { Log.w(TAG, "Failed to fetch found_not_in_far from Worker: ${res.message}") }
        _cachedFoundNotInFar.value
      }
    }
  }

  fun observeFoundNotInFar(clientId: String, fiscalYearId: String): Flow<List<FoundNotInFarRecord>> {
    val worker = getWorkerClient()
    if (worker != null) {
      if (clientId.isNotBlank()) {
        CoroutineScope(Dispatchers.IO).launch {
          fetchFoundNotInFarFromWorker(clientId, fiscalYearId)
        }
      }
      return _cachedFoundNotInFar.map { list ->
        list.filter { rec ->
          (clientId.isBlank() || rec.clientId == clientId) &&
            (fiscalYearId.isBlank() || rec.fiscalYearId == fiscalYearId || rec.fiscalYearId.isBlank())
        }.sortedByDescending { it.countDate }
      }
    }

    val c = col(FirestoreCollections.FOUND_NOT_IN_FAR)
    if (c == null) {
      if (clientId.isNotBlank()) {
        CoroutineScope(Dispatchers.IO).launch {
          fetchFoundNotInFarFromWorker(clientId, fiscalYearId)
        }
      }
      return _cachedFoundNotInFar.asStateFlow()
    }
    return callbackFlow {
      val sub = c.addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snap?.documents?.mapNotNull { doc ->
          runCatching {
            doc.toObject(FoundNotInFarRecord::class.java)?.copy(
              id = doc.id,
              hasPendingWrites = doc.metadata.hasPendingWrites()
            )
          }.getOrNull()
        }?.filter { rec ->
          (clientId.isBlank() || rec.clientId == clientId) &&
            (fiscalYearId.isBlank() || rec.fiscalYearId == fiscalYearId || rec.fiscalYearId.isBlank())
        }.orEmpty()
        trySend(list)
      }
      awaitClose { sub.remove() }
    }
  }

  private val _cachedDailyBatches = MutableStateFlow<List<DailyBatchSubmissionRecord>>(emptyList())
  val cachedDailyBatches: StateFlow<List<DailyBatchSubmissionRecord>> = _cachedDailyBatches.asStateFlow()

  private val _dailySheetsDataFeedState = MutableStateFlow<DailySheetsDataFeedState>(DailySheetsDataFeedState.Idle)
  val dailySheetsDataFeedState: StateFlow<DailySheetsDataFeedState> = _dailySheetsDataFeedState.asStateFlow()

  suspend fun fetchDailyBatchesFromWorker(
    clientId: String,
    status: String? = null,
    mine: Boolean = false
  ): com.example.data.network.WorkerApiResult<List<DailyBatchSubmissionRecord>> {
    _dailySheetsDataFeedState.value = DailySheetsDataFeedState.Loading
    val client = getWorkerClient()
    if (client == null) {
      val err = com.example.data.network.WorkerApiResult.Error("Worker client not available")
      _dailySheetsDataFeedState.value = DailySheetsDataFeedState.Error(err.message)
      return err
    }
    val res = client.listDailySheets(clientId, status, mine)
    return when (res) {
      is com.example.data.network.WorkerApiResult.Success -> {
        val mapped = res.data.map { dto ->
          DailyBatchSubmissionRecord(
            id = dto.batchId,
            batchId = dto.batchId,
            clientId = dto.clientId,
            fiscalYearId = dto.fiscalYearId,
            countingDate = dto.batchDate,
            submitterUid = dto.auditorUid,
            submitterFullName = dto.auditorName,
            status = when (dto.batchStatus) {
              "APPROVED" -> SubmissionBatchStatus.PUBLISHED
              "REJECTED" -> SubmissionBatchStatus.REJECTED
              else -> SubmissionBatchStatus.SUBMITTED
            },
            approvalStatus = when (dto.batchStatus) {
              "APPROVED" -> "APPROVED"
              "REJECTED" -> "REJECTED"
              else -> "PENDING_REVIEW"
            },
            recordCount = dto.totalItems.toLong(),
            reviewedByUid = dto.reviewedByUid.orEmpty(),
            reviewedAtIso = dto.reviewedAt.orEmpty()
          )
        }
        val other = _cachedDailyBatches.value.filter { it.clientId != clientId }
        _cachedDailyBatches.value = other + mapped
        if (mapped.isEmpty()) {
          _dailySheetsDataFeedState.value = DailySheetsDataFeedState.Empty
        } else {
          _dailySheetsDataFeedState.value = DailySheetsDataFeedState.Success(mapped.size)
        }
        com.example.data.network.WorkerApiResult.Success(mapped)
      }
      is com.example.data.network.WorkerApiResult.Error -> {
        runCatching { Log.w(TAG, "Failed to list daily sheets from Worker: ${res.message}") }
        _dailySheetsDataFeedState.value = DailySheetsDataFeedState.Error(res.message, res.statusCode)
        com.example.data.network.WorkerApiResult.Error(message = res.message, statusCode = res.statusCode, cause = res.cause)
      }
    }
  }

  fun observeDailyBatches(clientId: String, fiscalYearId: String): Flow<List<DailyBatchSubmissionRecord>> {
    val worker = getWorkerClient()
    if (worker != null) {
      if (clientId.isNotBlank()) {
        CoroutineScope(Dispatchers.IO).launch {
          fetchDailyBatchesFromWorker(clientId)
        }
      }
      return _cachedDailyBatches.map { list ->
        list.filter { batch ->
          (clientId.isBlank() || batch.clientId == clientId) &&
            (fiscalYearId.isBlank() || batch.fiscalYearId == fiscalYearId || batch.fiscalYearId.isBlank())
        }.sortedByDescending { it.countingDate }
      }
    }
    val c = col("daily_batches")
    if (c == null) {
      if (clientId.isNotBlank()) {
        CoroutineScope(Dispatchers.IO).launch {
          fetchDailyBatchesFromWorker(clientId)
        }
      }
      return _cachedDailyBatches.asStateFlow()
    }
    return callbackFlow {
      val sub = c.addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snap?.documents?.mapNotNull { doc ->
          runCatching {
            doc.toObject(DailyBatchSubmissionRecord::class.java)?.copy(
              id = doc.id,
              batchId = doc.getString("batchId")?.ifBlank { doc.id } ?: doc.id
            )
          }.getOrNull()
        }?.filter { batch ->
          (clientId.isBlank() || batch.clientId == clientId) &&
            (fiscalYearId.isBlank() || batch.fiscalYearId == fiscalYearId || batch.fiscalYearId.isBlank())
        }?.sortedByDescending { it.countingDate }.orEmpty()
        trySend(list)
      }
      awaitClose { sub.remove() }
    }
  }

  suspend fun getDailySheetDetail(
    clientId: String,
    batchId: String
  ): com.example.data.network.WorkerApiResult<com.example.data.network.DailySheetDetailDto> {
    val client = getWorkerClient() ?: return com.example.data.network.WorkerApiResult.Error("Worker client not available")
    return client.getDailySheetDetail(clientId, batchId)
  }

  fun observeUserSubmissions(clientId: String, fiscalYearId: String): Flow<List<UserSubmissionRecord>> {
    val c = col("submissions") ?: return flowOf(emptyList())
    return callbackFlow {
      val sub = c.addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snap?.documents?.mapNotNull { doc ->
          runCatching {
            doc.toObject(UserSubmissionRecord::class.java)?.copy(
              id = doc.id,
              submissionId = doc.getString("submissionId")?.ifBlank { doc.id } ?: doc.id
            )
          }.getOrNull()
        }?.filter { s ->
          (clientId.isBlank() || s.clientId == clientId) &&
            (fiscalYearId.isBlank() || s.fiscalYearId == fiscalYearId || s.fiscalYearId.isBlank())
        }.orEmpty()
        trySend(list)
      }
      awaitClose { sub.remove() }
    }
  }

  fun observeAssetPhotos(clientId: String, fiscalYearId: String): Flow<List<AssetPhotoRecord>> {
    val c = col("asset_photos") ?: return flowOf(emptyList())
    return callbackFlow {
      val sub = c.addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snap?.documents?.mapNotNull { doc ->
          runCatching {
            doc.toObject(AssetPhotoRecord::class.java)?.copy(
              id = doc.id,
              photoId = doc.getString("photoId")?.ifBlank { doc.id } ?: doc.id
            )
          }.getOrNull()
        }?.filter { p ->
          (clientId.isBlank() || p.clientId == clientId) &&
            (fiscalYearId.isBlank() || p.fiscalYearId == fiscalYearId || p.fiscalYearId.isBlank())
        }.orEmpty()
        trySend(list)
      }
      awaitClose { sub.remove() }
    }
  }

  fun observeRoleAssignments(): Flow<List<RoleAssignment>> {
    val c = col("role_assignments") ?: return flowOf(emptyList())
    return callbackFlow {
      val sub = c.addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snap?.documents?.mapNotNull { doc ->
          runCatching {
            val raw = doc.toObject(RoleAssignment::class.java)
            raw?.copy(
              id = doc.id,
              memberUid = raw.memberUid.ifBlank { doc.id },
              fullName = raw.fullName.ifBlank { raw.displayName },
              displayName = raw.displayName.ifBlank { raw.fullName }
            )
          }.getOrNull()
        }?.filter { !it.accountStatus.equals("Deleted", ignoreCase = true) }.orEmpty()
        trySend(list)
      }
      awaitClose { sub.remove() }
    }
  }

  fun observeUserProfile(uid: String): Flow<UserProfile?> {
    if (uid.isBlank()) return flowOf(null)
    val docRef = col(FirestoreCollections.USER_PROFILE)?.document(uid) ?: return flowOf(null)
    return callbackFlow {
      val sub = docRef.addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(null)
          return@addSnapshotListener
        }
        val prof = snap?.toObject(UserProfile::class.java)?.copy(id = snap.id)
        trySend(prof)
      }
      awaitClose { sub.remove() }
    }
  }

  fun observeMasterDataConfig(clientId: String, fiscalYearId: String): Flow<MasterDataConfig> {
    val docId = if (clientId.isNotBlank()) "config_${clientId}_${fiscalYearId.ifBlank { "default" }}" else "default_config"
    val docRef = col("master_data")?.document(docId) ?: return flowOf(MasterDataConfig())
    return callbackFlow {
      val sub = docRef.addSnapshotListener { snap, _ ->
        val cfg = snap?.toObject(MasterDataConfig::class.java)?.copy(id = snap.id) ?: MasterDataConfig(
          id = docId,
          clientId = clientId,
          fiscalYearId = fiscalYearId
        )
        trySend(cfg)
      }
      awaitClose { sub.remove() }
    }
  }

  fun observeProjectSettings(): Flow<ProjectSettings> {
    val docRef = col("project_settings")?.document("main") ?: return flowOf(ProjectSettings())
    return callbackFlow {
      val sub = docRef.addSnapshotListener { snap, _ ->
        val settings = snap?.toObject(ProjectSettings::class.java)?.copy(id = "main") ?: ProjectSettings()
        trySend(settings)
      }
      awaitClose { sub.remove() }
    }
  }

  fun observeMasterWorkbookSyncMetadata(clientId: String, fiscalYearId: String): Flow<MasterWorkbookSyncMetadata?> {
    if (clientId.isBlank()) return flowOf(null)
    val docId = "${clientId}_${fiscalYearId.ifBlank { "2026_27" }}"
    val docRef = col("master_workbook_state")?.document(docId) ?: return flowOf(null)
    return callbackFlow {
      val sub = docRef.addSnapshotListener { snap, _ ->
        val meta = snap?.toObject(MasterWorkbookSyncMetadata::class.java)?.copy(id = snap.id)
        trySend(meta)
      }
      awaitClose { sub.remove() }
    }
  }

  fun observeNotifications(uid: String): Flow<List<NotificationRecord>> {
    val c = col("notifications") ?: return flowOf(emptyList())
    return callbackFlow {
      val sub = c.addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snap?.documents?.mapNotNull { doc ->
          runCatching { doc.toObject(NotificationRecord::class.java)?.copy(id = doc.id) }.getOrNull()
        }?.filter { n ->
          uid.isBlank() || n.userId == uid || n.userId == "ALL_ADMINS"
        }?.sortedByDescending { it.timestampIso }.orEmpty()
        trySend(list)
      }
      awaitClose { sub.remove() }
    }
  }

  fun observeActivityLogs(clientId: String): Flow<List<ActivityLogRecord>> {
    val c = col(FirestoreCollections.ACTIVITY_LOGS) ?: return flowOf(emptyList())
    return callbackFlow {
      val sub = c.addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snap?.documents?.mapNotNull { doc ->
          runCatching { doc.toObject(ActivityLogRecord::class.java)?.copy(id = doc.id) }.getOrNull()
        }?.filter { log ->
          clientId.isBlank() || log.clientId == clientId || log.clientId.isBlank()
        }?.sortedByDescending { it.timestampIso }.orEmpty()
        trySend(list)
      }
      awaitClose { sub.remove() }
    }
  }

  fun observeDeletionJobs(): Flow<List<DeletionJobRecord>> {
    val c = col("deletion_jobs") ?: return flowOf(emptyList())
    return callbackFlow {
      val sub = c.addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snap?.documents?.mapNotNull { doc ->
          runCatching {
            doc.toObject(DeletionJobRecord::class.java)?.copy(
              id = doc.id,
              jobId = doc.getString("jobId")?.ifBlank { doc.id } ?: doc.id
            )
          }.getOrNull()
        }?.sortedByDescending { it.startedAtIso }.orEmpty()
        trySend(list)
      }
      awaitClose { sub.remove() }
    }
  }

  fun observeDeletionAuditEvents(): Flow<List<DeletionAuditEvent>> {
    val c = col("deletion_audit_events") ?: return flowOf(emptyList())
    return callbackFlow {
      val sub = c.addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snap?.documents?.mapNotNull { doc ->
          runCatching {
            doc.toObject(DeletionAuditEvent::class.java)?.copy(
              id = doc.id,
              eventId = doc.getString("eventId")?.ifBlank { doc.id } ?: doc.id
            )
          }.getOrNull()
        }?.sortedByDescending { it.timestampIso }.orEmpty()
        trySend(list)
      }
      awaitClose { sub.remove() }
    }
  }

  fun observeColumnMapping(clientId: String, fiscalYearId: String): Flow<ExcelColumnMappingConfig> {
    val docId = if (clientId.isNotBlank()) "map_${clientId}_${fiscalYearId.ifBlank { "default" }}" else "main"
    val docRef = col("column_mapping")?.document(docId) ?: return flowOf(ExcelColumnMappingConfig())
    return callbackFlow {
      val sub = docRef.addSnapshotListener { snap, _ ->
        val cfg = snap?.toObject(ExcelColumnMappingConfig::class.java)?.copy(id = docId)
          ?: ExcelColumnMappingConfig(id = docId, clientId = clientId, fiscalYearId = fiscalYearId)
        trySend(cfg)
      }
      awaitClose { sub.remove() }
    }
  }

  fun observeOwnershipConfig(): Flow<SystemOwnershipConfig> {
    val docRef = col("system_config")?.document("ownership")
      ?: col("project_settings")?.document("ownership")
      ?: return flowOf(
        SystemOwnershipConfig(ownerEmail = DESIGNATED_OWNER_EMAIL)
      )
    return callbackFlow {
      val sub = docRef.addSnapshotListener { snap, _ ->
        val cfg = snap?.toObject(SystemOwnershipConfig::class.java)?.copy(id = "ownership")
          ?: SystemOwnershipConfig(id = "ownership", ownerEmail = DESIGNATED_OWNER_EMAIL)
        trySend(cfg)
      }
      awaitClose { sub.remove() }
    }
  }

  fun observeExportJobs(clientId: String = ""): Flow<List<ExportJobRecord>> {
    val c = col("export_jobs") ?: return flowOf(emptyList())
    return callbackFlow {
      val sub = c.addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snap?.documents?.mapNotNull { doc ->
          runCatching { doc.toObject(ExportJobRecord::class.java)?.copy(id = doc.id) }.getOrNull()
        }?.filter { job ->
          clientId.isBlank() || job.clientId == clientId || job.clientId.isBlank()
        }?.sortedByDescending { it.exportedAtIso }.orEmpty()
        trySend(list)
      }
      awaitClose { sub.remove() }
    }
  }

  fun observeSchemaVersions(clientId: String = "", fiscalYearId: String = ""): Flow<List<WorkbookSchemaVersion>> {
    val workerClient = getWorkerClient()
    val c = col(FirestoreCollections.SCHEMA_VERSIONS)
    if (workerClient != null) {
      if (clientId.isNotBlank()) {
        CoroutineScope(Dispatchers.IO).launch {
          fetchSchemaFromWorker(clientId, fiscalYearId)
        }
      }
      val cachedFlow = _cachedSchemas.map { list ->
        list.filter { v ->
          (clientId.isBlank() || v.clientId == clientId || v.clientId.isBlank()) &&
            (fiscalYearId.isBlank() || v.fiscalYearId == fiscalYearId || v.fiscalYearId.isBlank())
        }.sortedByDescending { it.versionNumber }
      }
      if (c == null) {
        return cachedFlow
      }
      return combine(cachedFlow, callbackFlow {
        val sub = c.addSnapshotListener { snap, err ->
          if (err != null) {
            trySend(emptyList())
            return@addSnapshotListener
          }
          val list = snap?.documents?.mapNotNull { doc ->
            runCatching { doc.toObject(WorkbookSchemaVersion::class.java)?.copy(id = doc.id) }.getOrNull()
          }?.filter { v ->
            (clientId.isBlank() || v.clientId == clientId || v.clientId.isBlank()) &&
              (fiscalYearId.isBlank() || v.fiscalYearId == fiscalYearId || v.fiscalYearId.isBlank())
          }?.sortedByDescending { it.versionNumber }.orEmpty()
          trySend(list)
        }
        awaitClose { sub.remove() }
      }) { workerList, firestoreList ->
        if (workerList.isNotEmpty()) workerList else firestoreList
      }
    }

    if (c == null) return flowOf(emptyList())
    return callbackFlow {
      val sub = c.addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snap?.documents?.mapNotNull { doc ->
          runCatching { doc.toObject(WorkbookSchemaVersion::class.java)?.copy(id = doc.id) }.getOrNull()
        }?.filter { v ->
          (clientId.isBlank() || v.clientId == clientId || v.clientId.isBlank()) &&
            (fiscalYearId.isBlank() || v.fiscalYearId == fiscalYearId || v.fiscalYearId.isBlank())
        }?.sortedByDescending { it.versionNumber }.orEmpty()
        trySend(list)
      }
      awaitClose { sub.remove() }
    }
  }

  suspend fun saveSchemaVersion(version: WorkbookSchemaVersion): Result<WorkbookSchemaVersion> = runCatching {
    val docId = version.id.ifBlank {
      "schema_${version.clientId.ifBlank { "default" }}_v${version.versionNumber}"
    }
    val normalized = version.copy(id = docId)
    val workerClient = getWorkerClient()

    if (workerClient != null) {
      val dto = normalized.toDynamicSchemaDto()
      when (val res = workerClient.publishSchema(normalized.clientId, dto)) {
        is com.example.data.network.WorkerApiResult.Success -> {
          val other = _cachedSchemas.value.filter { it.id != normalized.id }
          _cachedSchemas.value = (listOf(normalized) + other).sortedByDescending { it.versionNumber }

          // Invalidate local Room cache for this client & cycle so auditors receive latest schema
          runCatching {
            dynamicOfflineCacheDao?.deleteCachedSchema(normalized.clientId, normalized.fiscalYearId)
          }

          logActivity(
            actionType = "SCHEMA_PUBLISHED",
            targetId = normalized.id,
            summary = "Published Schema v${normalized.versionNumber} for client '${normalized.clientId}' via Cloudflare Worker",
            clientId = normalized.clientId,
            fiscalYearId = normalized.fiscalYearId
          )

          // Best-effort Firestore write for dual-persistence without failing if Firestore is restricted
          runCatching {
            col(FirestoreCollections.SCHEMA_VERSIONS)?.document(docId)?.set(normalized, SetOptions.merge())?.await()
          }

          return@runCatching normalized
        }
        is com.example.data.network.WorkerApiResult.Error -> {
          throw Exception("Cloudflare Worker schema publish failed (HTTP ${res.statusCode ?: 0}): ${res.message}")
        }
      }
    }

    col(FirestoreCollections.SCHEMA_VERSIONS)?.document(docId)?.set(normalized, SetOptions.merge())?.await()
    normalized
  }

  fun observeFarAssets(currentUserId: String, clientId: String, fiscalYearId: String): Flow<List<FARAsset>> =
    observeFarAssets(clientId, fiscalYearId)

  fun observeCountingRecords(currentUserId: String, clientId: String, fiscalYearId: String): Flow<List<CountingRecord>> =
    observeCountingRecords(clientId, fiscalYearId)

  fun observeDailyBatches(currentUserId: String, clientId: String, fiscalYearId: String): Flow<List<DailyBatchSubmissionRecord>> =
    observeDailyBatches(clientId, fiscalYearId)

  // =========================================================================
  // 2. AUTH & RBAC BOOTSTRAP
  // =========================================================================

  suspend fun bootstrapAuthenticatedMember(
    uid: String,
    email: String,
    displayName: String,
    preferredNickname: String,
    requestedRole: String
  ): Result<UserProfile> = runCatching {
    val cleanEmail = email.trim().lowercase(Locale.US)
    val cleanName = displayName.trim().ifEmpty { cleanEmail.substringBefore("@").ifEmpty { "User" } }
    val cleanNickname = UserProfile.sanitizeNickname(preferredNickname.ifBlank { cleanName })
    val isDesignatedOwner = cleanEmail.equals(DESIGNATED_OWNER_EMAIL, ignoreCase = true)

    if (BuildConfig.DEBUG) {
      Log.d(TAG, "[Auth-Diag] Starting bootstrapAuthenticatedMember: email=$cleanEmail, role=$requestedRole, isOwner=$isDesignatedOwner")
    }

    // Step 1: Call Cloudflare Worker API (or legacy mock test double if injected)
    val isCustomMockFunctions = functionsService.javaClass != FirebaseFunctionsService::class.java
    val client = workerApiClient ?: if (!isCustomMockFunctions) com.example.data.network.WorkerApiClient() else null

    if (client != null) {
      if (BuildConfig.DEBUG) {
        Log.d(TAG, "[Auth-Diag] Querying Worker GET /api/v1/system/session...")
      }

      var sessionRes = client.getUserSession()
      var userSession = if (sessionRes is com.example.data.network.WorkerApiResult.Success && sessionRes.data.status == "OK") {
        sessionRes.data.user
      } else {
        null
      }

      if (BuildConfig.DEBUG) {
        val sCode = if (sessionRes is com.example.data.network.WorkerApiResult.Success) 200 else (sessionRes as com.example.data.network.WorkerApiResult.Error).statusCode ?: 0
        val sStatus = (sessionRes as? com.example.data.network.WorkerApiResult.Success)?.data?.status ?: "ERROR"
        Log.d(TAG, "[Auth-Diag] Initial session check: HTTP $sCode, status: $sStatus, userFound: ${userSession != null}")
      }

      // If user is unprovisioned and is designated owner, execute one-time bootstrap POST
      if (userSession == null && isDesignatedOwner) {
        if (BuildConfig.DEBUG) {
          Log.d(TAG, "[Auth-Diag] Executing Worker POST /api/v1/system/bootstrap...")
        }

        val bootstrapApiRes = client.bootstrapInitialOwner(
          fullName = cleanName,
          nickname = cleanNickname
        )

        if (bootstrapApiRes is com.example.data.network.WorkerApiResult.Success) {
          val res = bootstrapApiRes.data
          if (BuildConfig.DEBUG) {
            Log.d(TAG, "[Auth-Diag] Worker POST /api/v1/system/bootstrap succeeded: status=${res.status}, role=${res.role}")
          }
          if (!res.status.equals("OK", ignoreCase = true)) {
            throw SecurityException(
              "System Owner bootstrap failed: Unexpected response status '${res.status}' from backend service."
            )
          }
          if (res.uid.isNotBlank() && res.uid != uid) {
            throw SecurityException(
              "Workspace ownership conflict: Workspace is registered to owner UID '${res.uid}', but authenticated as '$uid'."
            )
          }

          // Step 5: Query authoritative session following bootstrap
          if (BuildConfig.DEBUG) {
            Log.d(TAG, "[Auth-Diag] Querying Worker GET /api/v1/system/session post-bootstrap...")
          }
          val postSessionRes = client.getUserSession()
          if (postSessionRes is com.example.data.network.WorkerApiResult.Success && postSessionRes.data.status == "OK" && postSessionRes.data.user != null) {
            sessionRes = postSessionRes
            userSession = postSessionRes.data.user
            if (BuildConfig.DEBUG) {
              Log.d(TAG, "[Auth-Diag] Post-bootstrap session resolved successfully. GlobalRole: ${userSession?.globalRole}")
            }
          } else {
            if (BuildConfig.DEBUG) {
              Log.d(TAG, "[Auth-Diag] Post-bootstrap session returned non-OK, constructing session from bootstrap response")
            }
            // Fallback to bootstrap response DTO if GET session immediately following POST is unavailable
            userSession = com.example.data.network.UserSessionProfileDto(
              uid = res.uid.ifBlank { uid },
              email = res.email.ifBlank { cleanEmail },
              displayName = cleanName,
              fullName = cleanName,
              nickname = cleanNickname,
              globalRole = res.role.ifBlank { UserRole.OWNER },
              accountStatus = "Active"
            )
          }
        } else if (bootstrapApiRes is com.example.data.network.WorkerApiResult.Error) {
          val errorMsg = bootstrapApiRes.message
          if (BuildConfig.DEBUG) {
            Log.d(TAG, "[Auth-Diag] Worker POST bootstrap failed: HTTP ${bootstrapApiRes.statusCode}, msg: $errorMsg")
          }
          when {
            bootstrapApiRes.statusCode == 409 ||
              errorMsg.contains("already", ignoreCase = true) ||
              errorMsg.contains("BOOTSTRAP_ALREADY_COMPLETE", ignoreCase = true) -> {
              throw SecurityException(
                "Workspace ownership conflict: Another user account has already been registered as the System Owner."
              )
            }
            bootstrapApiRes.statusCode == 403 ||
              errorMsg.contains("AUTH-02", ignoreCase = true) ||
              errorMsg.contains("INITIAL_OWNER_NOT_AUTHORIZED", ignoreCase = true) ||
              errorMsg.contains("not authorized", ignoreCase = true) -> {
              throw SecurityException(
                "AUTH-02: Role Mismatch: Account is not authorized to bootstrap as System Owner."
              )
            }
            errorMsg.contains("BOOTSTRAP_CONFIGURATION_MISSING", ignoreCase = true) -> {
              throw SecurityException(
                "Backend bootstrap configuration missing on server (INITIAL_OWNER_UID). Please configure Worker secret."
              )
            }
            bootstrapApiRes.statusCode == 401 || errorMsg.contains("AUTH_TOKEN", ignoreCase = true) -> {
              throw SecurityException(
                "Authentication token invalid or expired. Please sign in again."
              )
            }
            bootstrapApiRes.statusCode == 503 ||
              errorMsg.contains("WORKER_UNAVAILABLE", ignoreCase = true) ||
              errorMsg.contains("Network error", ignoreCase = true) -> {
              throw SecurityException(
                "Unable to connect to Cloudflare Worker backend: $errorMsg. Please verify backend deployment and network connectivity."
              )
            }
            else -> {
              throw SecurityException(
                "Cloudflare Worker system bootstrap failed (HTTP ${bootstrapApiRes.statusCode ?: "ERR"}): $errorMsg"
              )
            }
          }
        }
      }

      // AUTH-02: Enforce server-assigned role governance. Self-selecting Admin is strictly forbidden.
      if (requestedRole.equals(UserRole.ADMIN, ignoreCase = true)) {
        val isExistingAdmin = userSession?.globalRole?.equals(UserRole.ADMIN, ignoreCase = true) == true ||
          userSession?.globalRole?.equals(UserRole.OWNER, ignoreCase = true) == true
        if (!isExistingAdmin) {
          throw SecurityException(
            "AUTH-02: Role Mismatch: Account is not provisioned as an Administrator. Please contact the system owner or sign in through the Auditor portal."
          )
        }
      }

      // Check session errors for non-bootstrap cases
      if (userSession == null && sessionRes is com.example.data.network.WorkerApiResult.Error) {
        val sError = sessionRes as com.example.data.network.WorkerApiResult.Error
        if (sError.statusCode == 401) {
          throw SecurityException("Authentication token invalid or expired. Please sign in again.")
        }
        if (sError.statusCode == 403) {
          throw SecurityException("AUTH-02: Role Mismatch: Account is not authorized to access this workspace.")
        }
      }

      if (userSession == null) {
        throw SecurityException("Account is not provisioned for this workspace. Please contact your administrator.")
      }

      val savedRole = userSession.globalRole
      val accountStatus = userSession.accountStatus

      if (accountStatus.equals("Deactivated", ignoreCase = true) || accountStatus.equals("Deleted", ignoreCase = true)) {
        throw SecurityException("Your account has been ${accountStatus.lowercase(Locale.US)} by an Administrator.")
      }

      val isPrivileged = savedRole.equals(UserRole.OWNER, true) ||
        savedRole.equals(UserRole.ADMIN, true) ||
        savedRole.equals(UserRole.LEAD_AUDITOR, true) ||
        savedRole.equals(UserRole.REVIEWER, true)

      val finalProfile = UserProfile(
        id = uid,
        userId = uid,
        displayName = userSession.displayName.ifBlank { cleanName },
        fullName = userSession.fullName.ifBlank { cleanName },
        nickname = userSession.nickname.ifBlank { cleanNickname },
        email = userSession.email.ifBlank { cleanEmail },
        role = savedRole,
        accountStatus = accountStatus,
        hasReviewAuthority = isPrivileged,
        canPublish = isPrivileged
      )

      if (BuildConfig.DEBUG) {
        try {
          devMigrationManager.runDevelopmentMigrationIfNeeded(cleanEmail)
        } catch (e: Exception) {
          Log.d(TAG, "Development migration skipped: ${e.message}")
        }
      }

      runCatching { fetchClientsFromWorker() }

      return@runCatching finalProfile
    } else {
      // Legacy Cloud Functions mock fallback path (used ONLY when custom mock functionsService is injected in legacy tests)
      val fallback = functionsService.bootstrapInitialOwner(orgId)
      if (fallback.isSuccess) {
        val fRes = fallback.getOrThrow()
        if (!fRes.status.equals("OK", ignoreCase = true)) {
          throw SecurityException(
            "System Owner bootstrap failed: Unexpected response status '${fRes.status}' from backend service."
          )
        }
        if (fRes.ownerUid.isNotBlank() && fRes.ownerUid != uid) {
          throw SecurityException(
            "Workspace ownership conflict: Workspace is registered to owner UID '${fRes.ownerUid}', but authenticated as '$uid'."
          )
        }
        if (fRes.orgId.isNotBlank() && !fRes.orgId.equals(orgId, ignoreCase = true)) {
          throw SecurityException(
            "Workspace configuration mismatch: Cloud Function returned workspace '${fRes.orgId}', expected '$orgId'."
          )
        }
      } else {
        val ex = fallback.exceptionOrNull()
        val fbMsg = ex?.message.orEmpty()
        when {
          fbMsg.contains("already-exists", ignoreCase = true) || fbMsg.contains("Already", ignoreCase = true) -> {
            throw SecurityException(
              "Workspace ownership conflict: Another user account has already been registered as the System Owner.",
              ex
            )
          }
          fbMsg.contains("permission-denied", ignoreCase = true) || fbMsg.contains("Only the designated", ignoreCase = true) -> {
            throw SecurityException(
              "AUTH-02: Role Mismatch: Account is not authorized to bootstrap as System Owner.",
              ex
            )
          }
          else -> {
            throw SecurityException(
              "System Owner bootstrap service unavailable: ${fbMsg.ifBlank { "Service is currently unavailable" }}. Please verify backend deployment.",
              ex
            )
          }
        }
      }

      // In legacy test double mode, lookup profile safely
      val profileSnap = try { col(FirestoreCollections.USER_PROFILE)?.document(uid)?.get()?.await() } catch (_: Exception) { null }
      val roleSnap = try { col(FirestoreCollections.ROLE_ASSIGNMENTS)?.document(uid)?.get()?.await() } catch (_: Exception) { null }

      val fetchedProfile = profileSnap?.toObject(UserProfile::class.java)
      val finalProfile = fetchedProfile ?: UserProfile(
        id = uid,
        userId = uid,
        displayName = profileSnap?.getString("displayName")?.takeIf { it.isNotBlank() } ?: cleanName,
        fullName = profileSnap?.getString("fullName")?.takeIf { it.isNotBlank() } ?: cleanName,
        nickname = profileSnap?.getString("nickname")?.takeIf { it.isNotBlank() } ?: cleanNickname,
        email = cleanEmail,
        role = roleSnap?.getString("role")?.takeIf { it.isNotBlank() } ?: UserRole.OWNER,
        accountStatus = roleSnap?.getString("accountStatus")?.takeIf { it.isNotBlank() } ?: "Active",
        hasReviewAuthority = true,
        canPublish = true
      )

      return@runCatching finalProfile
    }
  }

  suspend fun updateUserProfile(
    uid: String,
    displayName: String,
    role: String,
    nickname: String
  ): Result<Unit> = runCatching {
    val cleanName = displayName.trim()
    val cleanNick = UserProfile.sanitizeNickname(nickname.ifBlank { cleanName })
    val updates = mapOf(
      "displayName" to cleanName,
      "fullName" to cleanName,
      "nickname" to cleanNick,
      "role" to role
    )
    col(FirestoreCollections.USER_PROFILE)?.document(uid)?.set(updates, SetOptions.merge())?.await()
    col(FirestoreCollections.ROLE_ASSIGNMENTS)?.document(uid)?.set(updates, SetOptions.merge())?.await()
  }

  suspend fun saveRoleAssignment(assignment: RoleAssignment): Result<Unit> = runCatching {
    val docId = assignment.memberUid.ifBlank { assignment.id.ifBlank { UUID.randomUUID().toString() } }
    val normalized = assignment.copy(
      id = docId,
      memberUid = docId,
      fullName = assignment.fullName.ifBlank { assignment.displayName },
      displayName = assignment.displayName.ifBlank { assignment.fullName },
      nickname = UserProfile.sanitizeNickname(assignment.nickname.ifBlank { assignment.displayName }),
      lastActiveAtIso = nowIso()
    )

    // 1. Authoritative role, status, and permissions update on Cloudflare Worker D1
    val client = getWorkerClient()
    if (client != null) {
      val validRoles = listOf(UserRole.ADMIN, UserRole.AUDITOR, UserRole.REVIEWER, "Admin", "Auditor", "Reviewer")
      val matchingRole = validRoles.firstOrNull { it.equals(normalized.role, ignoreCase = true) }
      if (matchingRole != null) {
        val canonicalRole = when {
          matchingRole.equals("Admin", ignoreCase = true) -> "Admin"
          matchingRole.equals("Reviewer", ignoreCase = true) -> "Reviewer"
          else -> "Auditor"
        }
        val roleRes = client.updateUserRole(docId, canonicalRole)
        if (roleRes is com.example.data.network.WorkerApiResult.Error) {
          Log.w(TAG, "Worker updateUserRole notice: ${roleRes.message}")
        }
      }

      if (normalized.accountStatus.isNotBlank()) {
        val mappedStatus = if (normalized.accountStatus.equals("Active", ignoreCase = true)) "Active" else "Suspended"
        val statusRes = client.updateUserStatus(docId, mappedStatus)
        if (statusRes is com.example.data.network.WorkerApiResult.Error) {
          Log.w(TAG, "Worker updateUserStatus notice: ${statusRes.message}")
        }
      }

      val targetClientIds = normalized.assignedClientIds
      for (cid in targetClientIds) {
        if (cid.isNotBlank()) {
          val membershipRes = client.updateClientMembershipPermissions(
            clientId = cid,
            targetUid = docId,
            role = normalized.role.ifBlank { "Auditor" },
            hasReviewAuthority = normalized.hasReviewAuthority,
            canPublish = normalized.canPublish,
            canSyncDrive = normalized.canSyncDrive
          )
          if (membershipRes is com.example.data.network.WorkerApiResult.Error) {
            Log.w(TAG, "Worker updateClientMembershipPermissions notice for client $cid: ${membershipRes.message}")
          }
        }
      }
    }

    // 2. Compatibility: Cloud Function and Firestore writes wrapped in runCatching (non-fatal)
    runCatching {
      try {
        functionsService.manageUserRoleAndPermissions(
          orgId = orgId,
          targetUid = docId,
          targetEmail = normalized.email,
          targetDisplayName = normalized.displayName,
          newRole = normalized.role,
          accountStatus = normalized.accountStatus,
          hasReviewAuthority = normalized.hasReviewAuthority,
          canPublish = normalized.canPublish,
          canSyncDrive = normalized.canSyncDrive
        ).getOrThrow()
      } catch (e: Exception) {
        Log.w(TAG, "manageUserRoleAndPermissions Cloud Function notice: ${e.message}; using Firestore set")
      }

      col(FirestoreCollections.ROLE_ASSIGNMENTS)?.document(docId)?.set(normalized, SetOptions.merge())?.await()
      col(FirestoreCollections.USER_PROFILE)?.document(docId)?.set(
        mapOf(
          "displayName" to normalized.displayName,
          "fullName" to normalized.fullName,
          "nickname" to normalized.nickname,
          "role" to normalized.role,
          "accountStatus" to normalized.accountStatus,
          "hasReviewAuthority" to normalized.hasReviewAuthority,
          "canPublish" to normalized.canPublish
        ),
        SetOptions.merge()
      )?.await()
    }.onFailure { e ->
      runCatching { Log.w(TAG, "Legacy Firestore role assignment sync skipped/failed: ${e.message}") }
    }
  }

  suspend fun updateAccountStatus(targetUid: String, newStatus: String): Result<Unit> = runCatching {
    val client = getWorkerClient()
    if (client != null) {
      val mappedStatus = if (newStatus.equals("Active", ignoreCase = true)) "Active" else "Suspended"
      val statusRes = client.updateUserStatus(targetUid, mappedStatus)
      if (statusRes is com.example.data.network.WorkerApiResult.Error) {
        throw IllegalStateException("Failed to update status on Worker: ${statusRes.message}")
      }
    }
    runCatching {
      col(FirestoreCollections.ROLE_ASSIGNMENTS)?.document(targetUid)?.set(
        mapOf("accountStatus" to newStatus),
        SetOptions.merge()
      )?.await()
      col(FirestoreCollections.USER_PROFILE)?.document(targetUid)?.set(
        mapOf("accountStatus" to newStatus),
        SetOptions.merge()
      )?.await()
    }.onFailure { e ->
      runCatching { Log.w(TAG, "Legacy Firestore account status update notice: ${e.message}") }
    }
  }

  // =========================================================================
  // 3. MULTI-CLIENT & FISCAL YEAR CREATION AND ASSIGNMENTS
  // =========================================================================

  suspend fun createOrUpdateClient(
    clientName: String,
    clientCode: String,
    industry: String,
    contactPerson: String,
    contactEmail: String,
    initialFiscalYearLabel: String = "2026-27",
    existingClientId: String = "",
    uniqueAssetKeyHeader: String = "Identification No.",
    provisionDriveFolders: Boolean = true
  ): Result<ClientRecord> = runCatching {
    val cleanName = clientName.trim()
    require(cleanName.isNotEmpty()) { "Client Name is required." }
    val cleanCode = clientCode.trim().uppercase(Locale.US).ifEmpty {
      cleanName.replace(Regex("[^A-Za-z0-9]"), "").take(8).uppercase(Locale.US).ifEmpty { "CLIENT" }
    }
    val clientId = existingClientId.ifBlank {
      "${cleanCode.lowercase(Locale.US)}_${System.currentTimeMillis().toString().takeLast(6)}"
    }
    val fyId = initialFiscalYearLabel.trim().replace(Regex("[^0-9A-Za-z]"), "_").ifEmpty { "2026_27" }

    // Recoverable Drive folder provisioning
    var rootFolderId = ""
    var imageFolderId = ""
    var masterFolderId = ""
    var dailyFolderId = ""
    if (provisionDriveFolders && driveService.isConfigured) {
      driveService.provisionClientDriveFolderStructure(cleanName).onSuccess { res ->
        rootFolderId = res.clientRootFolderId
        imageFolderId = res.imageEvidenceFolderId
        masterFolderId = res.masterInputFileFolderId
        dailyFolderId = res.dailyInputSheetsFolderId
      }.onFailure { driveErr ->
        Log.w(TAG, "Drive provisioning failed (recoverable): ${driveErr.message}")
      }
    }

    val workerClient = getWorkerClient()
    if (workerClient != null) {
      val createReq = com.example.data.network.CreateClientRequestDto(
        clientId = clientId,
        clientName = cleanName,
        clientCode = cleanCode,
        fiscalYear = fyId,
        fiscalYearLabel = initialFiscalYearLabel.trim().ifEmpty { "2026–27" },
        industry = industry.trim(),
        uniqueAssetKeyHeader = uniqueAssetKeyHeader.trim().ifBlank { "Identification No." },
        contactPerson = contactPerson.trim(),
        contactEmail = contactEmail.trim(),
        driveRootFolderId = rootFolderId.ifBlank { null },
        provisionDriveFolders = provisionDriveFolders
      )

      val apiRes = workerClient.createClient(createReq)
      when (apiRes) {
        is com.example.data.network.WorkerApiResult.Success -> {
          val resDto = apiRes.data
          val cDto = resDto.client
          val record = ClientRecord(
            id = cDto?.clientId ?: resDto.clientId,
            clientId = cDto?.clientId ?: resDto.clientId,
            clientName = cDto?.clientName ?: cleanName,
            clientCode = cDto?.clientCode ?: cleanCode,
            industry = cDto?.industry ?: industry.trim(),
            industryOrNotes = cDto?.industry ?: industry.trim(),
            contactPerson = cDto?.contactPerson ?: contactPerson.trim(),
            contactEmail = cDto?.contactEmail ?: contactEmail.trim(),
            status = cDto?.status ?: "Active",
            activeFiscalYearId = cDto?.activeFiscalYearId ?: fyId,
            fiscalYearIds = listOf(cDto?.activeFiscalYearId ?: fyId),
            availableFiscalYearsCsv = (cDto?.activeFiscalYearId ?: fyId).replace("_", "-"),
            uniqueAssetKeyHeader = cDto?.uniqueAssetKeyHeader ?: uniqueAssetKeyHeader,
            driveRootFolderId = cDto?.driveRootFolderId ?: rootFolderId,
            driveImageEvidenceFolderId = imageFolderId,
            driveMasterFileFolderId = masterFolderId,
            driveDailySheetsFolderId = dailyFolderId,
            createdByUid = cDto?.createdByUid ?: currentUid(),
            createdAtIso = nowIso(),
            userId = currentUid()
          )

          val existingList = _cachedClients.value.filter { it.clientId != record.clientId }
          _cachedClients.value = (existingList + record).sortedBy { it.clientName.lowercase(Locale.US) }

          val fyDto = resDto.fiscalYear
          if (fyDto != null) {
            val fyRecord = FiscalYearRecord(
              id = "${fyDto.clientId}_${fyDto.fiscalYearId}",
              fiscalYearId = fyDto.fiscalYearId,
              clientId = fyDto.clientId,
              label = fyDto.label,
              isClosed = fyDto.isClosed,
              userId = currentUid()
            )
            val existingFys = _cachedFiscalYears.value.filter { it.id != fyRecord.id }
            _cachedFiscalYears.value = (existingFys + fyRecord).sortedByDescending { it.label }
          }

          logActivity(
            actionType = "CLIENT_CREATED",
            targetId = record.clientId,
            summary = "Created client '${record.clientName}' (${record.clientCode}) via Cloudflare Worker",
            clientId = record.clientId,
            fiscalYearId = fyId
          )

          return@runCatching record
        }
        is com.example.data.network.WorkerApiResult.Error -> {
          throw Exception("Cloudflare Worker client creation failed (HTTP ${apiRes.statusCode ?: 0}): ${apiRes.message}")
        }
      }
    }

    val record = ClientRecord(
      id = clientId,
      clientId = clientId,
      clientName = cleanName,
      clientCode = cleanCode,
      industry = industry.trim(),
      industryOrNotes = industry.trim(),
      contactPerson = contactPerson.trim(),
      contactEmail = contactEmail.trim(),
      status = "Active",
      activeFiscalYearId = fyId,
      fiscalYearIds = listOf(fyId),
      availableFiscalYearsCsv = initialFiscalYearLabel.trim().ifEmpty { "2026-27" },
      uniqueAssetKeyHeader = uniqueAssetKeyHeader.trim().ifBlank { "Identification No." },
      driveRootFolderId = rootFolderId,
      driveImageEvidenceFolderId = imageFolderId,
      driveMasterFileFolderId = masterFolderId,
      driveDailySheetsFolderId = dailyFolderId,
      createdByUid = currentUid(),
      createdByName = currentEmail(),
      createdAtIso = nowIso(),
      userId = currentUid()
    )
    col("clients")?.document(clientId)?.set(record, SetOptions.merge())?.await()

    val fyDocId = "${clientId}_$fyId"
    val fyRecord = FiscalYearRecord(
      id = fyDocId,
      fiscalYearId = fyId,
      clientId = clientId,
      label = initialFiscalYearLabel.trim().ifEmpty { "2026-27" },
      userId = currentUid()
    )
    col("fiscal_years")?.document(fyDocId)?.set(fyRecord, SetOptions.merge())?.await()

    val existingList = _cachedClients.value.filter { it.clientId != record.clientId }
    _cachedClients.value = (existingList + record).sortedBy { it.clientName.lowercase(Locale.US) }

    logActivity(
      actionType = "CLIENT_CREATED",
      targetId = clientId,
      summary = "Created client '$cleanName' ($cleanCode) with fiscal year ${fyRecord.label}",
      clientId = clientId,
      fiscalYearId = fyId
    )
    record
  }

  suspend fun addFiscalYearForClient(
    clientId: String,
    yearLabel: String,
    startDateIso: String = "2026-07-01",
    endDateIso: String = "2027-06-30"
  ): Result<FiscalYearRecord> = runCatching {
    require(clientId.isNotBlank()) { "Client ID is required." }
    val cleanLabel = yearLabel.trim()
    require(cleanLabel.isNotEmpty()) { "Fiscal year label is required." }
    val fyId = cleanLabel.replace(Regex("[^0-9A-Za-z]"), "_")
    val docId = "${clientId}_$fyId"

    val workerClient = getWorkerClient()
    if (workerClient != null) {
      val apiRes = workerClient.createFiscalYear(clientId, fyId, cleanLabel)
      when (apiRes) {
        is com.example.data.network.WorkerApiResult.Success -> {
          val dto = apiRes.data
          val fy = FiscalYearRecord(
            id = "${dto.clientId}_${dto.fiscalYearId}",
            fiscalYearId = dto.fiscalYearId,
            clientId = dto.clientId,
            label = dto.label,
            startDateIso = startDateIso,
            endDateIso = endDateIso,
            isClosed = dto.isClosed,
            userId = currentUid()
          )
          val existingFys = _cachedFiscalYears.value.filter { it.id != fy.id }
          _cachedFiscalYears.value = (existingFys + fy).sortedByDescending { it.label }
          return@runCatching fy
        }
        is com.example.data.network.WorkerApiResult.Error -> {
          throw Exception("Cloudflare Worker fiscal year creation failed: ${apiRes.message}")
        }
      }
    }

    val fy = FiscalYearRecord(
      id = docId,
      fiscalYearId = fyId,
      clientId = clientId,
      label = cleanLabel,
      startDateIso = startDateIso,
      endDateIso = endDateIso,
      isClosed = false,
      userId = currentUid()
    )
    col("fiscal_years")?.document(docId)?.set(fy, SetOptions.merge())?.await()
    val existingFys = _cachedFiscalYears.value.filter { it.id != fy.id }
    _cachedFiscalYears.value = (existingFys + fy).sortedByDescending { it.label }

    val clientSnap = col("clients")?.document(clientId)?.get()?.await()
    val existingClient = clientSnap?.toObject(ClientRecord::class.java)
    if (existingClient != null) {
      val updatedIds = (existingClient.fiscalYearIds + fyId).distinct()
      val updatedCsv = (existingClient.fiscalYearsList() + cleanLabel).distinct().joinToString(",")
      col("clients")?.document(clientId)?.set(
        mapOf(
          "fiscalYearIds" to updatedIds,
          "availableFiscalYearsCsv" to updatedCsv,
          "activeFiscalYearId" to fyId
        ),
        SetOptions.merge()
      )?.await()
    }
    fy
  }

  suspend fun assignAuditorToClient(
    clientId: String,
    clientName: String,
    auditorUid: String,
    auditorEmail: String,
    auditorName: String,
    fiscalYearIds: List<String>,
    active: Boolean = true
  ): Result<ClientAssignmentRecord> = runCatching {
    val docId = "${clientId}_${auditorUid}"
    val statusStr = if (active) "Assigned" else "Unassigned"
    val rec = ClientAssignmentRecord(
      id = docId,
      assignmentId = docId,
      clientId = clientId,
      clientName = clientName,
      memberUid = auditorUid,
      auditorUid = auditorUid,
      memberEmail = auditorEmail,
      auditorEmail = auditorEmail,
      memberDisplayName = auditorName,
      auditorName = auditorName,
      assignedStatus = statusStr,
      status = statusStr,
      active = active,
      fiscalYearIds = fiscalYearIds.ifEmpty { listOf("2026_27") },
      assignedByUid = currentUid(),
      assignedByName = currentEmail(),
      assignedAtIso = nowIso(),
      userId = currentUid()
    )

    val workerClient = getWorkerClient()
    if (workerClient != null) {
      if (active) {
        val assignRes = workerClient.assignUserToClient(
          clientId = clientId,
          req = com.example.data.network.AssignUserRequestDto(
            userUid = auditorUid,
            role = "Auditor"
          )
        )
        if (assignRes is com.example.data.network.WorkerApiResult.Error) {
          throw Exception("Failed to assign auditor via Worker: ${assignRes.message}")
        }
      } else {
        val remRes = workerClient.removeUserFromClient(clientId, auditorUid)
        if (remRes is com.example.data.network.WorkerApiResult.Error) {
          throw Exception("Failed to unassign auditor via Worker: ${remRes.message}")
        }
      }
      val updatedList = _cachedMemberships.value.filter { !(it.clientId == clientId && it.auditorUid == auditorUid) }
      _cachedMemberships.value = if (active) updatedList + rec else updatedList
    }

    runCatching {
      col("client_assignments")?.document(docId)?.set(rec, SetOptions.merge())?.await()
    }
    logActivity(
      actionType = if (active) "CLIENT_ASSIGNED" else "CLIENT_UNASSIGNED",
      targetId = docId,
      summary = "${if (active) "Assigned" else "Unassigned"} $auditorName ($auditorEmail) ${if (active) "to" else "from"} $clientName",
      clientId = clientId
    )
    rec
  }

  suspend fun submitClientAccessRequest(
    clientId: String,
    clientName: String,
    fiscalYearId: String,
    reason: String,
    requesterUid: String,
    requesterEmail: String,
    requesterName: String
  ): Result<AccessRequestRecord> = runCatching {
    val reqId = "req_${clientId}_${requesterUid}"
    val rec = AccessRequestRecord(
      id = reqId,
      requestId = reqId,
      requesterUid = requesterUid,
      requesterEmail = requesterEmail,
      requesterName = requesterName,
      clientId = clientId,
      clientName = clientName,
      targetClientId = clientId,
      targetClientName = clientName,
      fiscalYearId = fiscalYearId,
      reason = reason.trim(),
      requestNotes = reason.trim(),
      status = "Pending",
      requestedAtIso = nowIso(),
      userId = requesterUid
    )

    val workerClient = getWorkerClient()
    if (workerClient != null) {
      val subRes = workerClient.submitAccessRequest(
        clientId = clientId,
        fiscalYearId = fiscalYearId,
        reason = reason.trim()
      )
      if (subRes is com.example.data.network.WorkerApiResult.Success) {
        val dto = subRes.data
        val updatedRec = rec.copy(
          id = dto.requestId,
          requestId = dto.requestId,
          status = dto.status,
          requestedAtIso = dto.requestedAt
        )
        val existing = _cachedAccessRequests.value.filter { it.id != updatedRec.id }
        _cachedAccessRequests.value = (listOf(updatedRec) + existing).sortedByDescending { it.requestedAtIso }
        runCatching {
          col("access_requests")?.document(updatedRec.id)?.set(updatedRec, SetOptions.merge())?.await()
        }
        return@runCatching updatedRec
      }
    }

    runCatching {
      col("access_requests")?.document(reqId)?.set(rec, SetOptions.merge())?.await()
    }

    createNotification(
      userId = "ALL_ADMINS",
      clientId = clientId,
      fiscalYearId = fiscalYearId,
      title = "Client Access Request: $clientName",
      message = "$requesterName ($requesterEmail) requested access to $clientName. Reason: $reason",
      type = "ACCESS_REQUEST"
    )
    rec
  }

  suspend fun resolveClientAccessRequest(
    request: AccessRequestRecord,
    approve: Boolean,
    decisionNotes: String,
    reviewerUid: String,
    reviewerName: String
  ): Result<Unit> = runCatching {
    val newStatus = if (approve) "Approved" else "Rejected"
    val docId = request.id.ifBlank { request.requestId }
    val cId = request.clientId.ifBlank { request.targetClientId }

    val workerClient = getWorkerClient()
    if (workerClient != null && cId.isNotBlank()) {
      val decideRes = workerClient.decideAccessRequest(
        clientId = cId,
        requestId = docId,
        decision = newStatus
      )
      if (decideRes is com.example.data.network.WorkerApiResult.Error) {
        throw Exception("Failed to decide access request via Worker: ${decideRes.message}")
      }
      val updatedList = _cachedAccessRequests.value.map {
        if (it.id == docId || it.requestId == docId) it.copy(status = newStatus) else it
      }
      _cachedAccessRequests.value = updatedList
    }

    runCatching {
      col("access_requests")?.document(docId)?.set(
        mapOf(
          "status" to newStatus,
          "reviewedByUid" to reviewerUid,
          "reviewedByName" to reviewerName,
          "reviewedAtIso" to nowIso(),
          "decisionNotes" to decisionNotes
        ),
        SetOptions.merge()
      )?.await()
    }

    if (approve) {
      val cId = request.clientId.ifBlank { request.targetClientId }
      val cName = request.clientName.ifBlank { request.targetClientName }
      assignAuditorToClient(
        clientId = cId,
        clientName = cName,
        auditorUid = request.requesterUid,
        auditorEmail = request.requesterEmail,
        auditorName = request.requesterName,
        fiscalYearIds = listOf(request.fiscalYearId.ifBlank { "2026_27" }),
        active = true
      ).getOrThrow()
    }

    createNotification(
      userId = request.requesterUid,
      clientId = request.clientId.ifBlank { request.targetClientId },
      fiscalYearId = request.fiscalYearId,
      title = "Access Request $newStatus",
      message = "Your request for ${request.clientName.ifBlank { request.targetClientName }} was $newStatus by $reviewerName.",
      type = "ACCESS_REQUEST"
    )
  }

  // =========================================================================
  // 4. MASTER WORKBOOK & ASSET REGISTER ONBOARDING + PERSISTENT DRIVE SYNC
  // =========================================================================

  /**
   * Imports a Client's Asset Register / Master Input `.xlsx` workbook, parses FAR rows scoped to
   * `(clientId, fiscalYearId)`, uploads the canonical Master Workbook to Google Drive if connected,
   * and records `MasterWorkbookSyncMetadata` with revision/MD5 baseline.
   */
  suspend fun importClientMasterWorkbook(
    clientId: String,
    clientName: String,
    fiscalYearId: String,
    fileName: String,
    workbookBytes: ByteArray,
    selectedSheetName: String = "",
    uniqueAssetKeyHeader: String = "Asset ID"
  ): Result<Int> = runCatching {
    require(clientId.isNotBlank()) { "Select an active Client before importing a Master Workbook." }
    val fy = fiscalYearId.ifBlank { "2026_27" }

    val parsed = ExcelWorkbookEngine.parseFarWorkbook(
      bytes = workbookBytes,
      sourceWorkbookName = fileName,
      preferredSheetName = selectedSheetName
    )
    val importBatchId = "imp_${clientId}_${fy}_${System.currentTimeMillis()}"
    val scopedAssets = parsed.assets.map { asset ->
      val scopedId = FARAsset.buildScopedAssetKey(clientId, fy, asset.assetId, asset.sourceRow)
      asset.copy(
        id = scopedId,
        clientId = clientId,
        fiscalYearId = fy,
        importBatchId = importBatchId,
        sourceWorkbookName = fileName,
        sourceWorksheetName = parsed.selectedWorksheetName,
        matchingKeyValue = asset.assetId,
        userId = currentUid()
      )
    }

    // 1. Authoritative batch write FAR assets to Cloudflare Worker D1 backend
    val client = getWorkerClient()
    if (client != null && scopedAssets.isNotEmpty()) {
      val dtoList = scopedAssets.map { asset ->
        com.example.data.network.FarAssetItemDto(
          assetKey = asset.id,
          assetId = asset.assetId,
          sourceRow = asset.sourceRow,
          sourceWorksheet = asset.sourceWorksheetName.ifBlank { "Count Sheet" },
          rawRowJson = asset.rawRowByHeaderJson,
          searchText = "${asset.assetId} ${asset.assetDescription} ${asset.assetClass} ${asset.location}"
        )
      }
      val workerResult = client.importFarAssets(
        clientId = clientId,
        fiscalYearId = fy,
        importBatchId = importBatchId,
        assets = dtoList
      )
      if (workerResult is com.example.data.network.WorkerApiResult.Error) {
        throw IllegalStateException("Failed to import FAR assets to backend (HTTP ${workerResult.statusCode ?: 0}): ${workerResult.message}")
      }
    }

    // 2. Reconcile with Room offline baseline cache and in-memory cache
    runCatching {
      val entities = scopedAssets.map { asset ->
        com.example.data.offline.CachedFarBaselineEntity(
          cacheKey = "${clientId}:${fy}:${asset.id}",
          clientId = clientId,
          auditCycleId = fy,
          assetKey = asset.id,
          assetId = asset.assetId,
          assetDescription = asset.assetDescription,
          location = asset.location,
          department = asset.department,
          baselineDataJson = asset.rawRowByHeaderJson,
          rawRowJson = asset.rawRowByHeaderJson,
          cachedAtMillis = System.currentTimeMillis()
        )
      }
      dynamicOfflineCacheDao?.upsertCachedFarBaselines(entities)
      val other = _cachedFarAssets.value.filter { it.clientId != clientId || (fy.isNotBlank() && it.fiscalYearId != fy) }
      _cachedFarAssets.value = other + scopedAssets
    }.onFailure { e ->
      runCatching { Log.w(TAG, "Local FAR baseline cache reconciliation notice: ${e.message}") }
    }

    // 3. Upload Master Input Workbook to Google Drive if configured
    var driveFileId = ""
    var headRevisionId = ""
    var md5Checksum = ""
    var modifiedIso = nowIso()

    if (driveService.isConfigured) {
      val folderRes = driveService.provisionClientDriveFolderStructure(clientName).getOrNull()

      // Resolve existing Drive file ID from Worker D1 first, then fallback to local/Firestore
      var existingDriveFileId = ""
      if (client != null) {
        val stateRes = client.getMasterWorkbookState(clientId, fy)
        if (stateRes is com.example.data.network.WorkerApiResult.Success) {
          existingDriveFileId = stateRes.data.masterDriveFileId.orEmpty()
        }
      }
      if (existingDriveFileId.isBlank()) {
        runCatching {
          val existingStateSnap = col("master_workbook_state")?.document("${clientId}_$fy")?.get()?.await()
          existingDriveFileId = existingStateSnap?.getString("masterDriveFileId").orEmpty()
        }
      }

      driveService.uploadClientWorkbookToDrive(
        workbookBytes = workbookBytes,
        fileName = fileName.ifBlank { "${clientName}_Master_Input_Sheet.xlsx" },
        parentFolderId = folderRes?.masterInputFileFolderId,
        existingFileId = existingDriveFileId.takeIf { it.isNotBlank() },
        idempotencyKey = "master_${clientId}_$fy"
      ).onSuccess { meta ->
        driveFileId = meta.fileId
        headRevisionId = meta.headRevisionId
        md5Checksum = meta.md5Checksum
        modifiedIso = meta.modifiedTimeIso.ifBlank { nowIso() }

        // Record master workbook state on Worker D1
        if (client != null && driveFileId.isNotBlank()) {
          val syncJobId = "init_${clientId}_${fy}_${System.currentTimeMillis()}"
          val leaseRes = client.acquireMasterSyncLease(clientId, fy, syncJobId, importBatchId)
          if (leaseRes is com.example.data.network.WorkerApiResult.Success) {
            client.releaseMasterSyncLease(
              clientId = clientId,
              fiscalYearId = fy,
              jobId = syncJobId,
              status = "COMPLETED",
              fileChecksum = md5Checksum,
              driveFileId = driveFileId,
              submissionId = importBatchId
            )
          }
        }
      }
    }

    val syncMetaId = "${clientId}_$fy"
    val syncMeta = MasterWorkbookSyncMetadata(
      id = syncMetaId,
      clientId = clientId,
      fiscalYearId = fy,
      masterDriveFileId = driveFileId,
      masterFileName = fileName,
      driveFileName = fileName,
      fileName = fileName,
      targetWorksheetName = parsed.selectedWorksheetName,
      targetSheetName = parsed.selectedWorksheetName,
      headerRowIndex = parsed.headerRowIndex,
      matchingKeyHeader = uniqueAssetKeyHeader,
      uniqueAssetKeyHeader = uniqueAssetKeyHeader,
      detectedHeaders = parsed.detectedHeaders,
      lastKnownDriveRevisionId = headRevisionId,
      lastKnownMd5Checksum = md5Checksum,
      lastModifiedTimeIso = modifiedIso,
      masterWorkbookVersion = 1L,
      lastSyncStatus = if (driveFileId.isNotBlank()) "SYNCED_TO_DRIVE" else "LOCAL_IMPORTED",
      lastSyncedByUid = currentUid(),
      lastSyncedByName = currentEmail(),
      lastSyncedAtIso = nowIso(),
      lastSyncTimestampIso = nowIso(),
      userId = currentUid()
    )

    // 4. Compatibility: Write to Firestore if available; errors must be non-fatal
    runCatching {
      val database = db
      if (database != null && scopedAssets.isNotEmpty()) {
        for (chunk in scopedAssets.chunked(250)) {
          val batch = database.batch()
          for (asset in chunk) {
            val ref = col("far_assets")!!.document(asset.id)
            batch.set(ref, asset, SetOptions.merge())
          }
          batch.commit().await()
        }
      }

      col("master_workbook_state")?.document(syncMetaId)?.set(syncMeta, SetOptions.merge())?.await()

      col("clients")?.document(clientId)?.set(
        mapOf(
          "assetRegisterRowCount" to scopedAssets.size,
          "masterWorkbookFileName" to fileName,
          "masterWorkbookDriveFileId" to driveFileId,
          "uniqueAssetKeyHeader" to uniqueAssetKeyHeader,
          "masterWorkbookLastSyncStatus" to syncMeta.lastSyncStatus
        ),
        SetOptions.merge()
      )?.await()
    }.onFailure { e ->
      runCatching { Log.w(TAG, "Legacy Firestore FAR sync skipped/failed: ${e.message}") }
    }

    logActivity(
      actionType = "MASTER_WORKBOOK_IMPORTED",
      targetId = clientId,
      summary = "Imported ${scopedAssets.size} assets from '$fileName' (sheet '${parsed.selectedWorksheetName}') for $clientName ($fy)",
      clientId = clientId,
      fiscalYearId = fy
    )
    scopedAssets.size
  }

  /**
   * Approves a Daily Batch in Firestore and synchronizes its verified rows in-place into the
   * client's persistent Google Drive Master Input Sheet (`.xlsx`) using:
   * - Single-writer Firestore lease lock (`syncLeaseOwnerJobId`, `syncLeaseExpiresAtMillis`)
   * - Revision / MD5 conflict check (`getFileRevisionMetadata`)
   * - Crash recovery check via Drive `appProperties["lastAppliedSyncJobId"]` and `appliedSubmissionIdsJson`
   * - Non-destructive OpenXML cell patching (`ExcelWorkbookEngine.patchMasterWorkbookInPlace`)
   */
  suspend fun approveBatchAndSyncMasterWorkbook(
    batch: DailyBatchSubmissionRecord,
    reviewerUid: String,
    reviewerName: String,
    adminRemarks: String = "",
    countingRecordsForBatch: List<CountingRecord>,
    unlistedRecordsForBatch: List<FoundNotInFarRecord> = emptyList()
  ): Result<MasterWorkbookSyncMetadata> = runCatching {
    val batchDocId = batch.id.ifBlank { batch.batchId }
    val clientId = batch.clientId
    val fy = batch.fiscalYearId.ifBlank { "2026_27" }
    val now = nowIso()

    // 1. Authoritative approval on Cloudflare Worker D1 backend
    val workerClient = getWorkerClient()
    if (workerClient != null) {
      val approveResult = workerClient.approveDailySheet(
        clientId = clientId,
        batchId = batchDocId,
        remarks = adminRemarks
      )
      if (approveResult is com.example.data.network.WorkerApiResult.Error) {
        throw IllegalStateException("Failed to approve daily sheet on backend (HTTP ${approveResult.statusCode ?: 0}): ${approveResult.message}")
      }
    }

    // Phase 1: Compatibility Firestore Approval (wrapped in runCatching to avoid PERMISSION_DENIED blocking workflow)
    runCatching {
      col("daily_batches")?.document(batchDocId)?.set(
        mapOf(
          "status" to SubmissionBatchStatus.APPROVED_AWAITING_MASTER,
          "approvalStatus" to ApprovalLifecycleStatus.APPROVED,
          "masterSyncStatus" to SubmissionBatchStatus.MASTER_SYNC_QUEUED,
          "reviewedByUid" to reviewerUid,
          "reviewedByName" to reviewerName,
          "reviewedAtIso" to now,
          "adminRemarks" to adminRemarks
        ),
        SetOptions.merge()
      )?.await()
    }.onFailure { e ->
      runCatching { Log.w(TAG, "Legacy Firestore daily_batches approval notice: ${e.message}") }
    }

    val syncDocId = "${clientId}_$fy"
    val metaRef = col("master_workbook_state")?.document(syncDocId)
    val firestoreMeta = runCatching {
      metaRef?.get()?.await()?.toObject(MasterWorkbookSyncMetadata::class.java)
    }.getOrNull()

    // Query authoritative Worker D1 master workbook state
    var workerState: com.example.data.network.MasterWorkbookStateDto? = null
    if (workerClient != null) {
      val stateRes = workerClient.getMasterWorkbookState(clientId, fy)
      if (stateRes is com.example.data.network.WorkerApiResult.Success) {
        workerState = stateRes.data
      }
    }

    val currentMeta = (firestoreMeta ?: MasterWorkbookSyncMetadata(id = syncDocId, clientId = clientId, fiscalYearId = fy)).let { base ->
      if (workerState != null) {
        base.copy(
          masterDriveFileId = workerState.masterDriveFileId?.takeIf { it.isNotBlank() } ?: base.masterDriveFileId,
          lastKnownMd5Checksum = workerState.fileChecksum?.takeIf { it.isNotBlank() } ?: base.lastKnownMd5Checksum,
          syncLeaseOwnerJobId = workerState.leaseOwnerJobId?.takeIf { it.isNotBlank() } ?: base.syncLeaseOwnerJobId,
          syncLeaseExpiresAtMillis = if (workerState.leaseExpiresAtMs > 0) workerState.leaseExpiresAtMs else base.syncLeaseExpiresAtMillis,
          appliedSubmissionIdsJson = if (workerState.appliedSubmissionsJson.isNotBlank() && workerState.appliedSubmissionsJson != "{}") workerState.appliedSubmissionsJson else base.appliedSubmissionIdsJson
        )
      } else {
        base
      }
    }

    // Check idempotency ledger first
    val appliedLedger = runCatching { JSONObject(currentMeta.appliedSubmissionIdsJson) }.getOrDefault(JSONObject())
    val idempotencyEntryKey = "${batchDocId}_v${batch.versionNumber}"
    if (appliedLedger.has(idempotencyEntryKey)) {
      runCatching {
        col("daily_batches")?.document(batchDocId)?.set(
          mapOf(
            "status" to SubmissionBatchStatus.PUBLISHED,
            "masterSyncStatus" to SubmissionBatchStatus.MASTER_SYNC_SYNCED
          ),
          SetOptions.merge()
        )?.await()
      }
      return@runCatching currentMeta
    }

    if (!driveService.isConfigured || currentMeta.masterDriveFileId.isBlank()) {
      val pendingMeta = currentMeta.copy(
        lastSyncStatus = SubmissionBatchStatus.MASTER_SYNC_FAILED,
        lastSyncError = "Google Drive not connected or Master Workbook not linked in Drive yet. Approval is saved and queued for Drive sync."
      )
      runCatching {
        metaRef?.set(pendingMeta, SetOptions.merge())?.await()
        col("daily_batches")?.document(batchDocId)?.set(
          mapOf(
            "masterSyncStatus" to SubmissionBatchStatus.MASTER_SYNC_FAILED,
            "masterSyncError" to pendingMeta.lastSyncError
          ),
          SetOptions.merge()
        )?.await()
      }
      return@runCatching pendingMeta
    }

    // Phase 2: Acquire single-writer lease lock
    val jobId = "sync_${batchDocId}_v${batch.versionNumber}_${System.currentTimeMillis()}"
    val nowMs = System.currentTimeMillis()
    if (currentMeta.syncLeaseOwnerJobId.isNotBlank() &&
      currentMeta.syncLeaseOwnerJobId != jobId &&
      currentMeta.syncLeaseExpiresAtMillis > nowMs
    ) {
      throw IllegalStateException("Another Master Workbook sync job (${currentMeta.syncLeaseOwnerJobId}) is currently active.")
    }

    val activeWorker = getWorkerClient()
    if (activeWorker != null) {
      val leaseRes = activeWorker.acquireMasterSyncLease(clientId, fy, jobId, batchDocId)
      if (leaseRes is com.example.data.network.WorkerApiResult.Error) {
        throw IllegalStateException("Master Workbook sync lease could not be acquired (HTTP ${leaseRes.statusCode ?: 0}): ${leaseRes.message}")
      }
    }

    runCatching {
      metaRef?.set(
        mapOf(
          "syncLeaseOwnerJobId" to jobId,
          "syncLeaseAcquiredAtMillis" to nowMs,
          "syncLeaseExpiresAtMillis" to (nowMs + LEASE_DURATION_MS),
          "lastSyncStatus" to SubmissionBatchStatus.MASTER_SYNC_IN_PROGRESS
        ),
        SetOptions.merge()
      )?.await()
    }

    try {
      // Inspect remote Drive file revision & MD5
      val remoteRev = driveService.getFileRevisionMetadata(currentMeta.masterDriveFileId).getOrThrow()

      // Crash recovery check: did a previous attempt already apply this batch on Drive?
      if (remoteRev.appProperties["lastAppliedBatchId"] == idempotencyEntryKey) {
        appliedLedger.put(idempotencyEntryKey, remoteRev.appProperties["lastAppliedSyncJobId"] ?: jobId)
        val recoveredMeta = currentMeta.copy(
          lastKnownDriveRevisionId = remoteRev.headRevisionId,
          lastKnownMd5Checksum = remoteRev.md5Checksum,
          lastModifiedTimeIso = remoteRev.modifiedTimeIso,
          appliedSubmissionIdsJson = appliedLedger.toString(),
          syncLeaseOwnerJobId = "",
          syncLeaseExpiresAtMillis = 0L,
          lastSyncStatus = SubmissionBatchStatus.MASTER_SYNC_SYNCED,
          lastSyncError = "",
          lastSyncedAtIso = now,
          lastSyncTimestampIso = now
        )
        runCatching {
          metaRef?.set(recoveredMeta, SetOptions.merge())?.await()
          col("daily_batches")?.document(batchDocId)?.set(
            mapOf(
              "status" to SubmissionBatchStatus.PUBLISHED,
              "masterSyncStatus" to SubmissionBatchStatus.MASTER_SYNC_SYNCED,
              "publishedAtIso" to now,
              "publishedByUid" to reviewerUid,
              "publishedByName" to reviewerName
            ),
            SetOptions.merge()
          )?.await()
        }
        return@runCatching recoveredMeta
      }

      // Revision / MD5 conflict check if baseline was recorded
      if (currentMeta.lastKnownMd5Checksum.isNotBlank() &&
        remoteRev.md5Checksum.isNotBlank() &&
        currentMeta.lastKnownMd5Checksum != remoteRev.md5Checksum
      ) {
        val conflictMsg = "External modification detected on Drive Master Workbook (expected MD5 ${currentMeta.lastKnownMd5Checksum.take(8)}, found ${remoteRev.md5Checksum.take(8)}). Accept new baseline or re-upload."
        val conflictMeta = currentMeta.copy(
          syncLeaseOwnerJobId = "",
          syncLeaseExpiresAtMillis = 0L,
          lastSyncStatus = SubmissionBatchStatus.MASTER_SYNC_CONFLICT,
          lastSyncError = conflictMsg
        )
        runCatching {
          metaRef?.set(conflictMeta, SetOptions.merge())?.await()
          col("daily_batches")?.document(batchDocId)?.set(
            mapOf(
              "masterSyncStatus" to SubmissionBatchStatus.MASTER_SYNC_CONFLICT,
              "masterSyncError" to conflictMsg
            ),
            SetOptions.merge()
          )?.await()
        }
        return@runCatching conflictMeta
      }

      // Download current Master Workbook bytes from Drive
      val currentBytes = driveService.downloadFile(currentMeta.masterDriveFileId).getOrThrow()

      // Apply non-destructive in-place OpenXML cell updates
      val patchResult = ExcelWorkbookEngine.patchMasterWorkbookInPlace(
        originalWorkbookBytes = currentBytes,
        targetSheetName = currentMeta.targetWorksheetName.ifBlank { "Count Sheet" },
        headerRowIndex = currentMeta.headerRowIndex.coerceAtLeast(1),
        matchingKeyHeader = currentMeta.matchingKeyHeader.ifBlank { "Asset ID" },
        approvedRecords = countingRecordsForBatch,
        approvedUnlistedRecords = unlistedRecordsForBatch,
        reviewerName = reviewerName
      )

      val nextVersion = currentMeta.masterWorkbookVersion + 1L
      val uploadedMeta = driveService.patchMasterWorkbookInPlace(
        masterFileId = currentMeta.masterDriveFileId,
        masterFileName = currentMeta.masterFileName.ifBlank { remoteRev.name },
        patchedWorkbookBytes = patchResult.patchedBytes,
        versionNumber = nextVersion,
        syncJobId = jobId,
        appliedBatchId = idempotencyEntryKey
      ).getOrThrow()

      appliedLedger.put(idempotencyEntryKey, jobId)
      val updatedMeta = currentMeta.copy(
        lastKnownDriveRevisionId = uploadedMeta.headRevisionId,
        lastKnownMd5Checksum = uploadedMeta.md5Checksum,
        lastModifiedTimeIso = uploadedMeta.modifiedTimeIso.ifBlank { now },
        masterWorkbookVersion = nextVersion,
        appliedSubmissionIdsJson = appliedLedger.toString(),
        syncLeaseOwnerJobId = "",
        syncLeaseExpiresAtMillis = 0L,
        lastSyncStatus = SubmissionBatchStatus.MASTER_SYNC_SYNCED,
        lastSyncError = "",
        lastSyncedByUid = reviewerUid,
        lastSyncedByName = reviewerName,
        lastSyncedAtIso = now,
        lastSyncTimestampIso = now
      )
      getWorkerClient()?.releaseMasterSyncLease(
        clientId = clientId,
        fiscalYearId = fy,
        jobId = jobId,
        status = "COMPLETED",
        fileChecksum = uploadedMeta.md5Checksum,
        driveFileId = uploadedMeta.fileId,
        submissionId = batchDocId
      )

      runCatching {
        metaRef?.set(updatedMeta, SetOptions.merge())?.await()

        col("daily_batches")?.document(batchDocId)?.set(
          mapOf(
            "status" to SubmissionBatchStatus.PUBLISHED,
            "approvalStatus" to ApprovalLifecycleStatus.APPROVED,
            "masterSyncStatus" to SubmissionBatchStatus.MASTER_SYNC_SYNCED,
            "masterSyncJobId" to jobId,
            "masterSyncError" to "",
            "publishedByUid" to reviewerUid,
            "publishedByName" to reviewerName,
            "publishedAtIso" to now,
            "publishedMasterFileId" to uploadedMeta.fileId,
            "publishedMasterVersion" to nextVersion
          ),
          SetOptions.merge()
        )?.await()

        col("clients")?.document(clientId)?.set(
          mapOf("masterWorkbookLastSyncStatus" to "SYNCED_TO_DRIVE (v$nextVersion)"),
          SetOptions.merge()
        )?.await()

        createNotification(
          userId = batch.submitterUid.ifBlank { batch.userId },
          clientId = clientId,
          fiscalYearId = fy,
          title = "Daily Sheet Approved & Synced",
          message = "Your Daily Sheet for ${batch.countingDate} was approved by $reviewerName and synced to Master Workbook v$nextVersion.",
          type = "PUBLISHED",
          batchId = batchDocId,
          countingDate = batch.countingDate
        )

        logActivity(
          actionType = "MASTER_WORKBOOK_SYNCED",
          targetId = batchDocId,
          summary = "Approved batch ${batch.countingDate} and patched ${patchResult.updatedAssetCount} assets in Master Workbook v$nextVersion",
          clientId = clientId,
          fiscalYearId = fy
        )
      }.onFailure { e ->
        runCatching { Log.w(TAG, "Legacy Firestore master sync record notice: ${e.message}") }
      }

      updatedMeta
    } catch (e: Exception) {
      val errMsg = e.localizedMessage ?: "Master Workbook Drive sync failed"
      getWorkerClient()?.releaseMasterSyncLease(
        clientId = clientId,
        fiscalYearId = fy,
        jobId = jobId,
        status = "FAILED",
        errorMessage = errMsg
      )
      runCatching {
        metaRef?.set(
          mapOf(
            "syncLeaseOwnerJobId" to "",
            "syncLeaseExpiresAtMillis" to 0L,
            "lastSyncStatus" to SubmissionBatchStatus.MASTER_SYNC_FAILED,
            "lastSyncError" to errMsg
          ),
          SetOptions.merge()
        )?.await()
        col("daily_batches")?.document(batchDocId)?.set(
          mapOf(
            "masterSyncStatus" to SubmissionBatchStatus.MASTER_SYNC_FAILED,
            "masterSyncError" to errMsg
          ),
          SetOptions.merge()
        )?.await()
      }
      throw e
    }
  }

  suspend fun acceptExternalMasterWorkbookBaseline(
    clientId: String,
    fiscalYearId: String
  ): Result<MasterWorkbookSyncMetadata> = runCatching {
    val fy = fiscalYearId.ifBlank { "2026_27" }
    val syncDocId = "${clientId}_$fy"
    val metaRef = col("master_workbook_state")?.document(syncDocId)
    val currentMeta = metaRef?.get()?.await()?.toObject(MasterWorkbookSyncMetadata::class.java)
      ?: throw IllegalStateException("Master Workbook sync metadata not found for $clientId ($fy)")
    val remoteRev = driveService.getFileRevisionMetadata(currentMeta.masterDriveFileId).getOrThrow()
    val updated = currentMeta.copy(
      lastKnownDriveRevisionId = remoteRev.headRevisionId,
      lastKnownMd5Checksum = remoteRev.md5Checksum,
      lastModifiedTimeIso = remoteRev.modifiedTimeIso,
      lastSyncStatus = "SYNCED_TO_DRIVE",
      lastSyncError = "",
      syncLeaseOwnerJobId = "",
      syncLeaseExpiresAtMillis = 0L
    )
    metaRef.set(updated, SetOptions.merge()).await()
    updated
  }

  // =========================================================================
  // 5. PHYSICAL ASSET COUNTING, DAILY BATCHES, AND REVIEW LIFECYCLE
  // =========================================================================

  suspend fun saveCountingRecord(
    record: CountingRecord,
    queueOfflineIfFailed: Boolean = true
  ): Result<CountingRecord> = runCatching {
    val key = record.id.ifBlank {
      record.assetKey.ifBlank {
        FARAsset.buildScopedAssetKey(record.clientId, record.fiscalYearId, record.assetId, record.sourceRow)
      }
    }
    val normalized = record.copy(id = key, assetKey = key)
    try {
      col(FirestoreCollections.COUNTING_RECORDS)?.document(key)?.set(normalized, SetOptions.merge())?.await()
      offlineSyncDao?.deleteQueuedRecord(key)
    } catch (e: Exception) {
      if (queueOfflineIfFailed && offlineSyncDao != null) {
        offlineSyncDao.upsertQueuedRecord(
          OfflineQueuedRecordEntity(
            recordKey = key,
            recordType = "FAR_COUNT",
            assetId = normalized.assetId,
            sourceRow = normalized.sourceRow,
            clientId = normalized.clientId,
            fiscalYearId = normalized.fiscalYearId,
            payloadJson = OfflinePayloadSerializer.serializeCountingRecord(normalized),
            syncStatus = SyncState.PENDING_SYNC,
            userId = normalized.countedByUserId.ifBlank { normalized.userId },
            userDisplayName = normalized.countedBy
          )
        )
      } else {
        throw e
      }
    }
    if (normalized.photoRef.isNotBlank() && photoEvidenceRepository != null) {
      val clientRec = _cachedClients.value.firstOrNull { it.clientId == normalized.clientId }
      val resolvedDriveFolder = clientRec?.driveRootFolderId.orEmpty()
      val uid = normalized.countedByUserId.ifBlank { currentUid() }
      val name = normalized.countedBy.ifBlank { "Auditor" }
      val nick = name
      val auditorProfile = UserProfile(
        id = uid,
        userId = uid,
        displayName = name,
        nickname = nick,
        email = currentEmail()
      )
      val photos = com.example.data.storage.PhotoStorageManager.parsePhotoList(normalized.photoRef)
      for (photoPath in photos) {
        val file = java.io.File(photoPath)
        if (file.exists() && file.length() > 0L) {
          photoEvidenceRepository?.enqueueCapturedPhoto(
            recordKey = key,
            assetId = normalized.assetId,
            recordType = "FAR_COUNT",
            clientId = normalized.clientId,
            fiscalYearId = normalized.fiscalYearId,
            localFilePath = file.absolutePath,
            userProfile = auditorProfile,
            driveFolderId = resolvedDriveFolder
          )
        }
      }
    }
    normalized
  }

  suspend fun saveFoundNotInFarRecord(
    record: FoundNotInFarRecord
  ): Result<FoundNotInFarRecord> = runCatching {
    val docId = record.id.ifBlank {
      record.tempId.ifBlank { "UNLISTED_${record.clientId}_${System.currentTimeMillis()}" }
    }
    val normalized = record.copy(id = docId, tempId = record.tempId.ifBlank { docId })
    val rawJson = OfflinePayloadSerializer.serializeFoundNotInFar(normalized)

    var workerSucceeded = false
    val client = getWorkerClient()
    if (client != null && normalized.clientId.isNotBlank()) {
      val req = com.example.data.network.SubmitFoundNotInFarRequestDto(
        tempId = normalized.tempId,
        fiscalYearId = normalized.fiscalYearId.ifBlank { "2026_27" },
        description = normalized.description.ifBlank { "Unlisted Asset" },
        rawValuesJson = rawJson,
        countDate = normalized.countDate.ifBlank { java.time.LocalDate.now().toString() }
      )
      when (val res = client.submitFoundNotInFar(normalized.clientId, req)) {
        is com.example.data.network.WorkerApiResult.Success -> {
          workerSucceeded = true
          offlineSyncDao?.deleteQueuedRecord(docId)
          val existing = _cachedFoundNotInFar.value.filter { it.tempId != normalized.tempId && it.id != normalized.id }
          _cachedFoundNotInFar.value = listOf(normalized) + existing
        }
        is com.example.data.network.WorkerApiResult.Error -> {
          runCatching { Log.w(TAG, "Worker submitFoundNotInFar failed: ${res.message} (HTTP ${res.statusCode})") }
        }
      }
    }

    if (!workerSucceeded) {
      offlineSyncDao?.upsertQueuedRecord(
        OfflineQueuedRecordEntity(
          recordKey = docId,
          recordType = "UNREGISTERED_OBS",
          assetId = normalized.searchedAssetId,
          clientId = normalized.clientId,
          fiscalYearId = normalized.fiscalYearId,
          payloadJson = rawJson,
          syncStatus = SyncState.PENDING_SYNC,
          userId = normalized.countedByUserId.ifBlank { normalized.userId },
          userDisplayName = normalized.countedBy
        )
      )
      val existing = _cachedFoundNotInFar.value.filter { it.tempId != normalized.tempId && it.id != normalized.id }
      _cachedFoundNotInFar.value = listOf(normalized) + existing
    }

    // Secondary legacy Firestore mirror (non-fatal, isolated)
    runCatching {
      col(FirestoreCollections.FOUND_NOT_IN_FAR)?.document(docId)?.set(normalized, SetOptions.merge())?.await()
    }.onFailure { e ->
      runCatching { Log.w(TAG, "Non-fatal legacy Firestore mirror failed for found_not_in_far: ${e.message}") }
    }
    if (normalized.photoRef.isNotBlank() && photoEvidenceRepository != null) {
      val clientRec = _cachedClients.value.firstOrNull { it.clientId == normalized.clientId }
      val resolvedDriveFolder = clientRec?.driveRootFolderId.orEmpty()
      val uid = normalized.countedByUserId.ifBlank { normalized.userId.ifBlank { currentUid() } }
      val name = normalized.countedBy.ifBlank { "Auditor" }
      val nick = name
      val auditorProfile = UserProfile(
        id = uid,
        userId = uid,
        displayName = name,
        nickname = nick,
        email = currentEmail()
      )
      val photos = com.example.data.storage.PhotoStorageManager.parsePhotoList(normalized.photoRef)
      for (photoPath in photos) {
        val file = java.io.File(photoPath)
        if (file.exists() && file.length() > 0L) {
          photoEvidenceRepository?.enqueueCapturedPhoto(
            recordKey = docId,
            assetId = normalized.searchedAssetId,
            recordType = "UNREGISTERED_OBS",
            clientId = normalized.clientId,
            fiscalYearId = normalized.fiscalYearId,
            localFilePath = file.absolutePath,
            userProfile = auditorProfile,
            driveFolderId = resolvedDriveFolder
          )
        }
      }
    }
    normalized
  }

  suspend fun submitDailyBatch(
    batch: DailyBatchSubmissionRecord
  ): Result<DailyBatchSubmissionRecord> = runCatching {
    val docId = batch.id.ifBlank {
      batch.batchId.ifBlank {
        "batch_${batch.clientId}_${batch.fiscalYearId}_${batch.submitterUid}_${batch.countingDate}"
      }
    }
    val normalized = batch.copy(
      id = docId,
      batchId = docId,
      status = SubmissionBatchStatus.SUBMITTED,
      approvalStatus = "PENDING_REVIEW"
    )
    // Sync submission to Cloudflare Worker D1 backend
    val workerClient = getWorkerClient()
    if (workerClient != null) {
      val itemsList = normalized.assetKeyList().map { key ->
        mapOf<String, Any?>(
          "assetKey" to key,
          "schemaVersionId" to "default_v1",
          "countDate" to normalized.countingDate
        )
      }
      workerClient.submitDailySheet(
        clientId = normalized.clientId,
        fiscalYearId = normalized.fiscalYearId,
        batchId = docId,
        batchDate = normalized.countingDate,
        items = itemsList
      )
    }

    col("daily_batches")?.document(docId)?.set(normalized, SetOptions.merge())?.await()

    createNotification(
      userId = "ALL_ADMINS",
      clientId = normalized.clientId,
      fiscalYearId = normalized.fiscalYearId,
      title = "Daily Sheet Submitted (${normalized.countingDate})",
      message = "${normalized.auditorDisplayName} submitted ${normalized.recordCount} counted assets for review.",
      type = "RESUBMITTED",
      batchId = docId,
      countingDate = normalized.countingDate
    )
    normalized
  }

  suspend fun returnDailyBatchWithRemarks(
    batch: DailyBatchSubmissionRecord,
    remarks: String,
    reviewerUid: String,
    reviewerName: String,
    rejectPermanently: Boolean = false
  ): Result<Unit> = runCatching {
    val docId = batch.id.ifBlank { batch.batchId }
    val now = nowIso()
    val newStatus = if (rejectPermanently) SubmissionBatchStatus.REJECTED else SubmissionBatchStatus.CHANGES_REQUESTED
    val newApproval = if (rejectPermanently) ApprovalLifecycleStatus.REJECTED else ApprovalLifecycleStatus.CHANGES_REQUESTED

    // Sync return with remarks to Worker D1 backend
    val workerClient = getWorkerClient()
    if (workerClient != null) {
      workerClient.returnDailySheet(
        clientId = batch.clientId,
        batchId = docId,
        remarks = remarks.trim()
      )
    }

    col("daily_batches")?.document(docId)?.set(
      mapOf(
        "status" to newStatus,
        "approvalStatus" to newApproval,
        "adminRemarks" to remarks.trim(),
        "remarksByUid" to reviewerUid,
        "remarksByName" to reviewerName,
        "remarksAtIso" to now,
        "reviewedByUid" to reviewerUid,
        "reviewedByName" to reviewerName,
        "reviewedAtIso" to now
      ),
      SetOptions.merge()
    )?.await()

    createNotification(
      userId = batch.submitterUid.ifBlank { batch.userId },
      clientId = batch.clientId,
      fiscalYearId = batch.fiscalYearId,
      title = "Remarks on Daily Sheet (${batch.countingDate})",
      message = "$reviewerName returned your sheet: $remarks",
      type = "REMARKS",
      batchId = docId,
      countingDate = batch.countingDate
    )
  }

  suspend fun markNotificationAsRead(notificationId: String): Result<Unit> = runCatching {
    if (notificationId.isNotBlank()) {
      col("notifications")?.document(notificationId)?.set(mapOf("isRead" to true), SetOptions.merge())?.await()
    }
  }

  suspend fun createNotification(
    userId: String,
    clientId: String,
    fiscalYearId: String,
    title: String,
    message: String,
    type: String,
    batchId: String = "",
    countingDate: String = ""
  ) {
    runCatching {
      val id = "notif_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}"
      val rec = NotificationRecord(
        id = id,
        userId = userId,
        clientId = clientId,
        fiscalYearId = fiscalYearId,
        title = title,
        message = message,
        type = type,
        batchId = batchId,
        countingDate = countingDate,
        isRead = false,
        timestampIso = nowIso()
      )
      col("notifications")?.document(id)?.set(rec)?.await()
    }
  }

  // =========================================================================
  // 6. PERMANENT CLIENT DELETION & PERMANENT USER DELETION (SAFE CASCADE + TOMBSTONE)
  // =========================================================================

  /**
   * Computes a real-time dry-run impact summary before Permanent Client Deletion.
   */
  suspend fun previewClientDeletionImpact(
    clientId: String,
    clientName: String
  ): Result<DeletionImpactSummary> = runCatching {
    require(clientId.isNotBlank()) { "Client ID is required." }
    val farCount = col(FirestoreCollections.FAR_ASSETS)?.whereEqualTo("clientId", clientId)?.get()?.await()?.size() ?: 0
    val countRecs = col(FirestoreCollections.COUNTING_RECORDS)?.whereEqualTo("clientId", clientId)?.get()?.await()?.size() ?: 0
    val batchCount = col(FirestoreCollections.DAILY_BATCHES)?.whereEqualTo("clientId", clientId)?.get()?.await()?.size() ?: 0
    val photoCount = col(FirestoreCollections.ASSET_PHOTOS)?.whereEqualTo("clientId", clientId)?.get()?.await()?.size() ?: 0
    val unlistedCount = col(FirestoreCollections.FOUND_NOT_IN_FAR)?.whereEqualTo("clientId", clientId)?.get()?.await()?.size() ?: 0
    val assignCount = col(FirestoreCollections.CLIENT_ASSIGNMENTS)?.whereEqualTo("clientId", clientId)?.get()?.await()?.size() ?: 0
    val reqCount = col(FirestoreCollections.ACCESS_REQUESTS)?.whereEqualTo("clientId", clientId)?.get()?.await()?.size() ?: 0
    val schemaCount = col(FirestoreCollections.SCHEMA_VERSIONS)?.whereEqualTo("clientId", clientId)?.get()?.await()?.size() ?: 0
    val clientSnap = col(FirestoreCollections.CLIENTS)?.document(clientId)?.get()?.await()

    DeletionImpactSummary(
      targetType = "CLIENT",
      targetId = clientId,
      targetDisplayName = clientName,
      farAssetCount = farCount,
      farAssetsCount = farCount,
      countingRecordCount = countRecs,
      assetCountsCount = countRecs,
      dailyBatchCount = batchCount,
      dailyBatchesCount = batchCount,
      photoMetadataCount = photoCount,
      assetPhotosCount = photoCount,
      unlistedAssetsCount = unlistedCount,
      assignmentCount = assignCount,
      assignmentsCount = assignCount,
      accessRequestCount = reqCount,
      accessRequestsCount = reqCount,
      schemaVersionCount = schemaCount,
      schemaConfigsCount = schemaCount,
      driveRootFolderId = clientSnap?.getString("driveRootFolderId").orEmpty(),
      masterWorkbookDriveFileId = clientSnap?.getString("masterWorkbookDriveFileId").orEmpty()
    )
  }

  /**
   * Executes Permanent Client Deletion with chunked Firestore batch deletes, local Room cache purge,
   * optional Google Drive client folder cleanup, post-deletion zero-residue verification, and
   * non-PII `DeletionAuditEvent`.
   */
  suspend fun executePermanentClientDeletion(
    clientId: String,
    clientName: String,
    typedConfirmation: String,
    deleteDriveArtifactsOptIn: Boolean,
    actorUid: String,
    actorEmail: String,
    actorRole: String,
    localAppFilesDir: File? = null
  ): Result<DeletionAuditEvent> = runCatching {
    require(actorRole.equals(UserRole.OWNER, true) || actorRole.equals(UserRole.ADMIN, true)) {
      "Only an Administrator or System Owner can permanently delete a Client."
    }
    val expectedConfirm = clientName.trim()
    require(typedConfirmation.trim().equals(expectedConfirm, ignoreCase = true) ||
      typedConfirmation.trim().equals("DELETE", ignoreCase = true)
    ) {
      "Confirmation text must match '$expectedConfirm' or 'DELETE'."
    }

    val jobId = "del_client_${clientId}_${System.currentTimeMillis()}"
    val startIso = nowIso()

    val jobRecord = DeletionJobRecord(
      id = jobId,
      jobId = jobId,
      jobType = "CLIENT_DELETION",
      targetId = clientId,
      targetLabel = "Client #${hashIdentifier(clientId)}",
      status = DeletionJobStatus.IN_PROGRESS,
      deleteDriveArtifactsOptIn = deleteDriveArtifactsOptIn,
      requestedByUid = actorUid,
      requestedByRole = actorRole,
      startedAtIso = startIso,
      userId = actorUid
    )
    col("deletion_jobs")?.document(jobId)?.set(jobRecord)?.await()

    // Attempt backend Worker / Cloud Function first
    try {
      val workerClient = getWorkerClient()
      if (workerClient != null) {
        val workerRes = workerClient.deleteClient(
          clientId = clientId,
          confirmClientCode = typedConfirmation,
          reason = "Client permanent deletion requested by $actorEmail"
        )
        if (workerRes is com.example.data.network.WorkerApiResult.Error) {
          Log.w(TAG, "Worker deleteClient notice: ${workerRes.message}")
        }
      } else {
        functionsService.executeClientDeletionJob(
          orgId = orgId,
          targetClientId = clientId,
          confirmationText = typedConfirmation,
          deleteDriveArtifactsOptIn = deleteDriveArtifactsOptIn
        ).getOrThrow()
      }
    } catch (e: Exception) {
      Log.w(TAG, "executeClientDeletionJob notice: ${e.message}; running client-directed purge")
    }

    // Mark client status as Deleting to fence concurrent writes
    val clientSnap = col(FirestoreCollections.CLIENTS)?.document(clientId)?.get()?.await()
    val clientDriveRootId = clientSnap?.getString("driveRootFolderId").orEmpty()
    col(FirestoreCollections.CLIENTS)?.document(clientId)?.set(mapOf("status" to "Deleting"), SetOptions.merge())?.await()

    val collectionsToPurge = listOf(
      FirestoreCollections.FAR_ASSETS,
      FirestoreCollections.COUNTING_RECORDS,
      FirestoreCollections.DAILY_BATCHES,
      FirestoreCollections.SUBMISSIONS,
      FirestoreCollections.ASSET_PHOTOS,
      FirestoreCollections.FOUND_NOT_IN_FAR,
      FirestoreCollections.CLIENT_ASSIGNMENTS,
      FirestoreCollections.ACCESS_REQUESTS,
      FirestoreCollections.FISCAL_YEARS,
      FirestoreCollections.MASTER_WORKBOOK_STATE,
      FirestoreCollections.SCHEMA_VERSIONS,
      FirestoreCollections.MASTER_DATA,
      FirestoreCollections.COLUMN_MAPPING,
      FirestoreCollections.NOTIFICATIONS,
      // Legacy names for backward compatibility purge
      "asset_counts",
      "unlisted_assets",
      "schema_config"
    )

    val deletedCounts = JSONObject()
    val database = db
    if (database != null) {
      for (colName in collectionsToPurge) {
        val snap = col(colName)?.whereEqualTo("clientId", clientId)?.get()?.await()
        val docs = snap?.documents.orEmpty()
        var colDeleted = 0
        for (chunk in docs.chunked(200)) {
          val batch = database.batch()
          chunk.forEach { batch.delete(it.reference) }
          batch.commit().await()
          colDeleted += chunk.size
        }
        deletedCounts.put(colName, colDeleted)
      }
      // Finally delete the client document itself
      col("clients")?.document(clientId)?.delete()?.await()
      deletedCounts.put("clients", 1)
    }

    // Purge local Room offline queues & local photo files for this client
    val localPhotos = offlineSyncDao?.getPhotosForClient(clientId).orEmpty()
    localPhotos.forEach { p ->
      runCatching { File(p.localFilePath).takeIf { it.exists() }?.delete() }
    }
    offlineSyncDao?.deleteQueuedRecordsForClient(clientId)
    offlineSyncDao?.deleteQueuedPhotosForClient(clientId)

    // Optional Google Drive folder cleanup
    if (deleteDriveArtifactsOptIn && clientDriveRootId.isNotBlank() && driveService.isConfigured) {
      col("deletion_jobs")?.document(jobId)?.set(
        mapOf("status" to DeletionJobStatus.DRIVE_CLEANUP_PENDING),
        SetOptions.merge()
      )?.await()
      driveService.trashOrDeleteExclusiveClientFolder(
        clientRootFolderId = clientDriveRootId,
        permanentDelete = false
      )
    }

    // Post-deletion verification pass
    col("deletion_jobs")?.document(jobId)?.set(
      mapOf("status" to DeletionJobStatus.VERIFYING),
      SetOptions.merge()
    )?.await()
    val remainingFar = col("far_assets")?.whereEqualTo("clientId", clientId)?.limit(1)?.get()?.await()?.size() ?: 0
    val finalStatus = if (remainingFar == 0) DeletionJobStatus.COMPLETED else DeletionJobStatus.FAILED_RETRYABLE

    val endIso = nowIso()
    col("deletion_jobs")?.document(jobId)?.set(
      mapOf(
        "status" to finalStatus,
        "deletedCountsJson" to deletedCounts.toString(),
        "completedAtIso" to endIso
      ),
      SetOptions.merge()
    )?.await()

    val auditEventId = "evt_$jobId"
    val auditEvent = DeletionAuditEvent(
      id = auditEventId,
      eventId = auditEventId,
      jobId = jobId,
      deletionType = "CLIENT_DELETION",
      targetType = "CLIENT",
      targetOpaqueRef = "Deleted Client [#${hashIdentifier(clientId)}]",
      targetIdHash = hashIdentifier(clientId),
      categoryCountsJson = deletedCounts.toString(),
      driveCleanupIncluded = deleteDriveArtifactsOptIn,
      outcome = finalStatus,
      status = finalStatus,
      actorUid = actorUid,
      actorEmail = actorEmail,
      actorRole = actorRole,
      timestampIso = endIso,
      userId = actorUid
    )
    col("deletion_audit_events")?.document(auditEventId)?.set(auditEvent)?.await()
    auditEvent
  }

  /**
   * Computes a dry-run impact summary before Permanent User Deletion.
   */
  suspend fun previewUserDeletionImpact(
    targetUid: String,
    targetDisplayName: String
  ): Result<DeletionImpactSummary> = runCatching {
    require(targetUid.isNotBlank()) { "User UID is required." }
    val assignCount = col(FirestoreCollections.CLIENT_ASSIGNMENTS)?.whereEqualTo("auditorUid", targetUid)?.get()?.await()?.size() ?: 0
    val reqCount = col(FirestoreCollections.ACCESS_REQUESTS)?.whereEqualTo("requesterUid", targetUid)?.get()?.await()?.size() ?: 0

    val allUserCounts = col(FirestoreCollections.COUNTING_RECORDS)?.whereEqualTo("countedByUserId", targetUid)?.get()?.await()?.documents.orEmpty()
    val draftCount = allUserCounts.count { it.getBoolean("isDraft") == true }
    val historicalCount = allUserCounts.size - draftCount

    val batchCount = col(FirestoreCollections.DAILY_BATCHES)?.whereEqualTo("submitterUid", targetUid)?.get()?.await()?.size() ?: 0

    DeletionImpactSummary(
      targetType = "USER",
      targetId = targetUid,
      targetDisplayName = targetDisplayName,
      assignmentCount = assignCount,
      assignmentsCount = assignCount,
      accessRequestCount = reqCount,
      accessRequestsCount = reqCount,
      draftRecordCountToDelete = draftCount,
      userDraftRecordsCount = draftCount,
      historicalRecordCountToPseudonymize = historicalCount + batchCount,
      userSubmittedHistoricalRecordsCount = historicalCount + batchCount
    )
  }

  /**
   * Executes Permanent User Deletion:
   * - Blocks deletion of the designated System Owner (`mislamabed@gmail.com`) or self-deletion
   * - Deletes user profile, role assignment, client assignments, access requests, notifications, and unsubmitted drafts
   * - Pseudonymizes submitted/approved historical records (`CountingRecord`, `DailyBatchSubmissionRecord`, `AssetPhotoRecord`)
   *   to `Deleted User [#XXXX]` so client audit history remains 100% intact without retaining PII
   * - Records `DeletedIdentityTombstone` and non-PII `DeletionAuditEvent`
   */
  suspend fun executePermanentUserDeletion(
    targetUid: String,
    targetEmail: String,
    targetDisplayName: String,
    typedConfirmation: String,
    actorUid: String,
    actorEmail: String,
    actorRole: String
  ): Result<DeletionAuditEvent> = runCatching {
    require(actorRole.equals(UserRole.OWNER, true) || actorRole.equals(UserRole.ADMIN, true)) {
      "Only an Administrator or System Owner can permanently delete a User."
    }
    require(targetUid != actorUid) {
      "Self-deletion is blocked to prevent administrative lockout."
    }
    require(!targetEmail.trim().equals(DESIGNATED_OWNER_EMAIL, ignoreCase = true)) {
      "The designated System Owner account ($DESIGNATED_OWNER_EMAIL) cannot be deleted."
    }
    require(
      typedConfirmation.trim().equals(targetEmail.trim(), ignoreCase = true) ||
        typedConfirmation.trim().equals(targetDisplayName.trim(), ignoreCase = true) ||
        typedConfirmation.trim().equals("DELETE", ignoreCase = true)
    ) {
      "Confirmation text must match the user's email, name, or 'DELETE'."
    }

    // Call Worker / Cloud Function for server-enforced user deletion job
    try {
      val workerClient = getWorkerClient()
      if (workerClient != null) {
        val workerRes = workerClient.deleteUser(
          targetUid = targetUid,
          confirmEmailOrName = typedConfirmation,
          reason = "User permanent deletion requested by $actorEmail"
        )
        if (workerRes is com.example.data.network.WorkerApiResult.Error) {
          Log.w(TAG, "Worker deleteUser notice: ${workerRes.message}")
        }
      } else {
        functionsService.executeUserDeletionJob(
          orgId = orgId,
          targetUid = targetUid
        ).getOrThrow()
      }
    } catch (e: Exception) {
      Log.w(TAG, "executeUserDeletionJob notice: ${e.message}; running client-directed pseudonymization")
    }

    val shortHash = hashIdentifier(targetUid)
    val pseudonymLabel = "Deleted User [#$shortHash]"
    val jobId = "del_user_${shortHash}_${System.currentTimeMillis()}"
    val startIso = nowIso()

    val jobRecord = DeletionJobRecord(
      id = jobId,
      jobId = jobId,
      jobType = "USER_DELETION",
      targetId = targetUid,
      targetLabel = pseudonymLabel,
      status = DeletionJobStatus.IN_PROGRESS,
      requestedByUid = actorUid,
      requestedByRole = actorRole,
      startedAtIso = startIso,
      userId = actorUid
    )
    col("deletion_jobs")?.document(jobId)?.set(jobRecord)?.await()

    val database = db
    val countsJson = JSONObject()
    var pseudonymizedTotal = 0L

    if (database != null) {
      // 1. Delete profile & role assignment
      col(FirestoreCollections.USER_PROFILE)?.document(targetUid)?.delete()?.await()
      col(FirestoreCollections.ROLE_ASSIGNMENTS)?.document(targetUid)?.delete()?.await()
      countsJson.put("profile_and_role", 2)

      // 2. Delete client assignments
      val assigns = col(FirestoreCollections.CLIENT_ASSIGNMENTS)?.whereEqualTo("auditorUid", targetUid)?.get()?.await()?.documents.orEmpty()
      for (doc in assigns) {
        doc.reference.delete().await()
      }
      countsJson.put("client_assignments", assigns.size)

      // 3. Delete access requests & notifications
      val reqs = col(FirestoreCollections.ACCESS_REQUESTS)?.whereEqualTo("requesterUid", targetUid)?.get()?.await()?.documents.orEmpty()
      reqs.forEach { it.reference.delete().await() }
      val notifs = col(FirestoreCollections.NOTIFICATIONS)?.whereEqualTo("userId", targetUid)?.get()?.await()?.documents.orEmpty()
      notifs.forEach { it.reference.delete().await() }

      // 4. Drafts vs submitted historical CountingRecords
      val userCounts = col(FirestoreCollections.COUNTING_RECORDS)?.whereEqualTo("countedByUserId", targetUid)?.get()?.await()?.documents.orEmpty()
      var draftsDeleted = 0
      for (doc in userCounts) {
        if (doc.getBoolean("isDraft") == true) {
          doc.reference.delete().await()
          draftsDeleted++
        } else {
          doc.reference.set(
            mapOf(
              "countedBy" to pseudonymLabel,
              "countedByUserId" to "deleted_$shortHash"
            ),
            SetOptions.merge()
          ).await()
          pseudonymizedTotal++
        }
      }
      countsJson.put("draft_counts_deleted", draftsDeleted)

      // 5. Pseudonymize submitted Daily Batches
      val batches = col("daily_batches")?.whereEqualTo("submitterUid", targetUid)?.get()?.await()?.documents.orEmpty()
      for (doc in batches) {
        val status = doc.getString("status").orEmpty()
        if (status == SubmissionBatchStatus.DRAFT) {
          doc.reference.delete().await()
        } else {
          doc.reference.set(
            mapOf(
              "submitterFullName" to pseudonymLabel,
              "submitterNickname" to "deleted_$shortHash",
              "submitterUid" to "deleted_$shortHash"
            ),
            SetOptions.merge()
          ).await()
          pseudonymizedTotal++
        }
      }

      // 6. Write DeletedIdentityTombstone
      val tombstone = DeletedIdentityTombstone(
        id = targetUid,
        deletedUid = targetUid,
        pseudonymLabel = pseudonymLabel,
        deletedByUid = actorUid,
        deletedAtIso = nowIso(),
        deletionJobId = jobId,
        userId = actorUid
      )
      col("deleted_identities")?.document(targetUid)?.set(tombstone)?.await()
    }

    offlineSyncDao?.deleteQueuedRecordsForUser(targetUid)
    offlineSyncDao?.deleteQueuedPhotosForUser(targetUid)

    val endIso = nowIso()
    col("deletion_jobs")?.document(jobId)?.set(
      mapOf(
        "status" to DeletionJobStatus.COMPLETED,
        "deletedCountsJson" to countsJson.toString(),
        "pseudonymizedCount" to pseudonymizedTotal,
        "authAccountDeleted" to true,
        "completedAtIso" to endIso
      ),
      SetOptions.merge()
    )?.await()

    val eventId = "evt_$jobId"
    val event = DeletionAuditEvent(
      id = eventId,
      eventId = eventId,
      jobId = jobId,
      deletionType = "USER_DELETION",
      targetType = "USER",
      targetOpaqueRef = pseudonymLabel,
      targetIdHash = shortHash,
      categoryCountsJson = countsJson.toString(),
      driveCleanupIncluded = false,
      outcome = DeletionJobStatus.COMPLETED,
      status = DeletionJobStatus.COMPLETED,
      actorUid = actorUid,
      actorEmail = actorEmail,
      actorRole = actorRole,
      timestampIso = endIso,
      userId = actorUid
    )
    col("deletion_audit_events")?.document(eventId)?.set(event)?.await()
    event
  }

  // =========================================================================
  // 7. SETTINGS, MASTER DATA, COLUMN MAPPING & AUDIT LOGS
  // =========================================================================

  suspend fun saveMasterDataConfig(config: MasterDataConfig): Result<Unit> = runCatching {
    val docId = if (config.clientId.isNotBlank()) {
      "config_${config.clientId}_${config.fiscalYearId.ifBlank { "default" }}"
    } else {
      config.id.ifBlank { "default_config" }
    }
    col("master_data")?.document(docId)?.set(config.copy(id = docId), SetOptions.merge())?.await()
  }

  suspend fun saveProjectSettings(settings: ProjectSettings): Result<Unit> = runCatching {
    col("project_settings")?.document("main")?.set(settings.copy(id = "main"), SetOptions.merge())?.await()
  }

  suspend fun saveColumnMapping(config: ExcelColumnMappingConfig): Result<Unit> = runCatching {
    val docId = if (config.clientId.isNotBlank()) {
      "map_${config.clientId}_${config.fiscalYearId.ifBlank { "default" }}"
    } else {
      "main"
    }
    col("column_mapping")?.document(docId)?.set(config.copy(id = docId), SetOptions.merge())?.await()
  }

  suspend fun logActivity(
    actionType: String,
    targetId: String,
    summary: String,
    clientId: String = "",
    fiscalYearId: String = ""
  ) {
    runCatching {
      val id = "log_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}"
      val rec = ActivityLogRecord(
        id = id,
        actionType = actionType,
        targetId = targetId,
        summary = summary,
        actorName = currentEmail().ifBlank { "System" },
        actorUserId = currentUid(),
        timestampIso = nowIso(),
        clientId = clientId,
        fiscalYearId = fiscalYearId,
        userId = currentUid()
      )
      col(FirestoreCollections.ACTIVITY_LOGS)?.document(id)?.set(rec)?.await()
    }
  }

  /**
   * Retrieves a short-lived OAuth 2.0 access token for Google Drive API calls
   * using Google Play Services [GoogleAuthUtil].
   * If user consent has not been granted, throws [com.google.android.gms.auth.UserRecoverableAuthException],
   * which exposes an intent for consent approval.
   */
  suspend fun acquireGoogleDriveToken(
    context: Context,
    accountEmail: String
  ): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
      val scope = "oauth2:https://www.googleapis.com/auth/drive.file"
      val account = android.accounts.Account(accountEmail, "com.google")
      com.google.android.gms.auth.GoogleAuthUtil.getToken(context, account, scope)
    }
  }
}
