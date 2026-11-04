package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

const val MANUAL_ENTRY_REMARKS = "Manual Entry — Asset Not Found in FAR"

/**
 * Validated dropdown constants matching the Lists worksheet of FA_Count_Verification_Sheet.xlsx
 */
object AuditDropdownOptions {
  val FOUND_OPTIONS = listOf("Yes", "No", "Not Accessible")

  val TAG_STATUS_OPTIONS = listOf(
    "Tagged & Verified",
    "Untagged - New Tag Required",
    "Damaged / Illegible Tag",
    "Tag Mismatch",
    "Not Applicable"
  )

  val CONDITION_OPTIONS = listOf(
    "Good",
    "Fair",
    "Damaged",
    "Obsolete",
    "Under Repair",
    "Scrap"
  )

  val IN_USE_OPTIONS = listOf("Yes", "No", "Idle / Standby")

  const val OTHER_OPTION = "Other"
  const val UNKNOWN_OPTION = "Unknown"
  const val NOT_APPLICABLE_OPTION = "Not Applicable"

  val SPECIAL_OPTIONAL_STATES = listOf(UNKNOWN_OPTION, NOT_APPLICABLE_OPTION, OTHER_OPTION)

  val CONDITION_ISSUE_VALUES = listOf(
    "Poor",
    "Damaged",
    "Idle",
    "Not Working",
    "Obsolete",
    "Under Repair",
    "Scrap"
  )

  val PROBABLE_ASSET_CLASSES = listOf(
    "IT Equipment",
    "Furniture & Fixtures",
    "Office Equipment",
    "Plant & Machinery",
    "Motor Vehicles",
    "Electrical Equipment",
    "Other"
  )

  val EXACT_29_HEADERS = listOf(
    "SL",
    "Asset ID",
    "Asset Description (FAR)",
    "Asset Class",
    "Capitalized On",
    "Cost (Tk)",
    "NBV (Tk)",
    "FAR Qty",
    "Method",
    "Found?",
    "Count Date",
    "Counted By",
    "Building / Site",
    "Floor",
    "Room / Location",
    "Department",
    "User / Custodian",
    "Tag Status",
    "Tag Number (ACN)",
    "Brand / Model",
    "Physical Qty",
    "Condition",
    "In Use?",
    "Photo Ref",
    "Remarks",
    "Qty Diff",
    "Result",
    "Reviewed By",
    "Client Explanation / Follow-up"
  )
}

/**
 * Priority-ordered verification result statuses (1 = highest priority, 9 = lowest)
 */
object VerificationResultStatus {
  const val PENDING = "Pending"
  const val DRAFT = "Draft"
  const val COUNTED = "Counted"
  const val NOT_FOUND = "Not Found"
  const val NOT_ACCESSIBLE = "Not Accessible"
  const val PENDING_VERIFICATION = "Pending Verification"
  const val UNREGISTERED_OBSERVATION = "Unregistered Asset — Pending FAR Verification"
  const val NOT_IN_FAR = UNREGISTERED_OBSERVATION
  const val QTY_SHORT = "Qty Short"
  const val QTY_EXCESS = "Qty Excess"
  const val VERIFIED_CONDITION_ISSUE = "Verified - Condition Issue"
  const val CONDITION_ISSUE = VERIFIED_CONDITION_ISSUE
  const val VERIFIED_TAG_ISSUE = "Verified - Tag Issue"
  const val TAG_ISSUE = VERIFIED_TAG_ISSUE
  const val VERIFIED = "Verified"

  const val COUNTING_NOT_COUNTED = "Not Counted"
  const val COUNTING_IN_PROGRESS = "In Progress"
  const val COUNTING_COMPLETED = "Completed"

  const val REVIEW_NOT_REQUIRED = "Not Required"
  const val PENDING_REVIEW = "Pending Review"
  const val REVIEWED = "Reviewed"
}

/**
 * Role-based access control (RBAC) roles with Owner-Controlled Admin governance
 */
object UserRole {
  const val OWNER = "Owner"
  const val AUDITOR = "Auditor"
  const val LEAD_AUDITOR = "Lead Auditor"
  const val REVIEWER = "Reviewer"
  const val ADMIN = "Admin"
  const val END_USER = "EndUser"
  const val PENDING_ADMIN = "PendingAdmin"

  val ALL_ROLES = listOf(OWNER, AUDITOR, LEAD_AUDITOR, REVIEWER, ADMIN, END_USER, PENDING_ADMIN)
}

/**
 * Offline & Google Drive synchronization status states
 */
object SyncState {
  const val LOCAL_DRAFT = "Local Draft"
  const val PENDING_SYNC = "Pending Sync"
  const val SYNCED = "Synced"
  const val UPLOADED = "Uploaded"
  const val FAILED = "Failed"
  const val CONFLICT_DETECTED = "Conflict Detected"
}

object ApprovalLifecycleStatus {
  const val DRAFT = "DRAFT"
  const val AWAITING_APPROVAL = "AWAITING_APPROVAL"
  const val UNDER_REVIEW = "UNDER_REVIEW"
  const val CHANGES_REQUESTED = "CHANGES_REQUESTED"
  const val APPROVED = "APPROVED"
  const val REJECTED = "REJECTED"
}

object MasterDriveSyncStatus {
  const val NOT_APPLICABLE = "NOT_APPLICABLE"
  const val QUEUED = "QUEUED"
  const val IN_PROGRESS = "IN_PROGRESS"
  const val PENDING_DRIVE_SYNC = "PENDING_DRIVE_SYNC"
  const val SYNCING_TO_DRIVE = "SYNCING_TO_DRIVE"
  const val SYNCED = "SYNCED"
  const val SYNC_FAILED = "SYNC_FAILED"
  const val CONFLICT_DETECTED = "CONFLICT_DETECTED"
}

object DeletionJobStatus {
  const val PREVIEW_READY = "PREVIEW_READY"
  const val CONFIRMED_QUEUED = "CONFIRMED_QUEUED"
  const val IN_PROGRESS = "IN_PROGRESS"
  const val DRIVE_CLEANUP_PENDING = "DRIVE_CLEANUP_PENDING"
  const val VERIFYING = "VERIFYING"
  const val COMPLETED = "COMPLETED"
  const val FAILED_RETRYABLE = "FAILED_RETRYABLE"
}

@IgnoreExtraProperties
data class SystemOwnershipConfig(
  @DocumentId val id: String = "ownership",
  val ownerUid: String = "",
  val ownerEmail: String = "",
  val orgId: String = "",
  val bootstrappedAtIso: String = "",
  val allowSecondaryAdminsToManageAdmins: Boolean = false,
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class UserProfile(
  @DocumentId val id: String = "main",
  val displayName: String = "",
  val fullName: String = "",
  val nickname: String = "",
  val email: String = "",
  val role: String = UserRole.AUDITOR,
  val accountStatus: String = "Active", // Active | Deactivated | Deleted
  val hasReviewAuthority: Boolean = false,
  val canPublish: Boolean = false,
  val assignedClientIds: List<String> = emptyList(),
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  @Exclude
  fun effectiveDisplayName(): String =
    fullName.trim().ifEmpty { displayName.trim().ifEmpty { email.substringBefore("@").ifEmpty { "Auditor" } } }

  @Exclude
  fun effectiveFullName(): String =
    fullName.trim().ifEmpty { displayName.trim().ifEmpty { email.substringBefore("@").ifEmpty { "Auditor" } } }

  @Exclude
  fun effectiveNickname(): String =
    normalizeNickname(nickname.ifBlank { displayName.ifBlank { email.substringBefore("@") } })
      .ifBlank { "auditor" }

  @get:Exclude
  val hasValidNickname: Boolean
    get() = isValidNickname(nickname.ifBlank { displayName.ifBlank { fullName } })

  companion object {
    fun normalizeNickname(raw: String): String =
      raw.trim()
        .lowercase()
        .replace(Regex("[\\s]+"), "_")
        .replace(Regex("[^a-z0-9_\\-]"), "")
        .trim('_', '-')
        .take(24)

    fun sanitizeNickname(raw: String): String =
      normalizeNickname(raw).ifBlank { "auditor" }

    fun isValidNickname(raw: String): Boolean {
      val norm = normalizeNickname(raw)
      return norm.length in 2..24 && norm.matches(Regex("^[a-z0-9][a-z0-9_\\-]*$"))
    }
  }
}

@IgnoreExtraProperties
data class RoleAssignment(
  @DocumentId val id: String = "",
  val memberUid: String = "",
  val email: String = "",
  val displayName: String = "",
  val fullName: String = "",
  val nickname: String = "",
  val role: String = UserRole.AUDITOR, // Owner | Admin | Auditor | Reviewer
  val isInitialOwner: Boolean = false,
  val accountStatus: String = "Active", // Active | Deactivated
  val hasReviewAuthority: Boolean = false,
  val canPublish: Boolean = false,
  val canSyncDrive: Boolean = false,
  val canManageUsersAndRoles: Boolean = false,
  val canDeleteClientsAndUsers: Boolean = false,
  val assignedClientIds: List<String> = emptyList(),
  val auditLogId: String = "",
  val assignedByUid: String = "",
  val assignedByName: String = "",
  val lastActiveAtIso: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ClientRecord(
  @DocumentId val id: String = "",
  val clientId: String = "",
  val clientName: String = "",
  val clientCode: String = "",
  val industry: String = "",
  val industryOrNotes: String = "",
  val contactPerson: String = "",
  val contactEmail: String = "",
  val status: String = "Active", // Active | Archived | Deleting
  val activeFiscalYearId: String = "2026_27",
  val fiscalYearIds: List<String> = listOf("2026_27"),
  val availableFiscalYearsCsv: String = "2025-26,2026-27",
  val uniqueAssetKeyHeader: String = "Identification No.",
  val assetRegisterRowCount: Int = 0,
  val masterWorkbookLastSyncStatus: String = "Not Synced",
  val driveRootFolderId: String = "",
  val driveImageEvidenceFolderId: String = "",
  val driveMasterFileFolderId: String = "",
  val driveDailySheetsFolderId: String = "",
  val masterWorkbookDriveFileId: String = "",
  val masterWorkbookFileName: String = "",
  val assetRegisterDriveFileId: String = "",
  val assetRegisterFileName: String = "",
  val createdByUid: String = "",
  val createdByName: String = "",
  val createdAtIso: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  @Exclude
  fun fiscalYearsList(): List<String> =
    if (fiscalYearIds.isNotEmpty()) {
      fiscalYearIds
    } else {
      availableFiscalYearsCsv.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .ifEmpty { listOf("2025-26", "2026-27") }
    }
}

@IgnoreExtraProperties
data class FiscalYearRecord(
  @DocumentId val id: String = "",
  val fiscalYearId: String = "",
  val clientId: String = "",
  val label: String = "2026-27",
  val startDateIso: String = "2026-07-01",
  val endDateIso: String = "2027-06-30",
  val isClosed: Boolean = false,
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  val yearLabel: String
    @Exclude get() = label
}

@IgnoreExtraProperties
data class ClientAssignmentRecord(
  @DocumentId val id: String = "",
  val assignmentId: String = "",
  val clientId: String = "",
  val clientName: String = "",
  val memberUid: String = "",
  val auditorUid: String = "",
  val memberEmail: String = "",
  val auditorEmail: String = "",
  val memberDisplayName: String = "",
  val auditorName: String = "",
  val assignedStatus: String = "Assigned", // Assigned | Unassigned
  val status: String = "Assigned",
  val active: Boolean = true,
  val fiscalYearIds: List<String> = listOf("2026_27"),
  val assignedDepartmentsCsv: String = "",
  val assignedLocationsCsv: String = "",
  val assignedByUid: String = "",
  val assignedByName: String = "",
  val assignedAtIso: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AccessRequestRecord(
  @DocumentId val id: String = "",
  val requestId: String = "",
  val requesterUid: String = "",
  val requesterEmail: String = "",
  val requesterName: String = "",
  val clientId: String = "",
  val clientName: String = "",
  val targetClientId: String = "",
  val targetClientName: String = "",
  val fiscalYearId: String = "",
  val reason: String = "",
  val requestNotes: String = "",
  val status: String = "Pending", // Pending | Approved | Rejected
  val requestedAtIso: String = "",
  val reviewedByUid: String = "",
  val reviewedByName: String = "",
  val reviewedAtIso: String = "",
  val decisionNotes: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  val requestedAt: String
    @Exclude get() = requestedAtIso
}

@IgnoreExtraProperties
data class FARAsset(
  @DocumentId val id: String = "",
  val sl: Long = 0L,
  val assetId: String = "",
  val sourceRow: Long = 0L,
  val assetDescription: String = "",
  val assetClass: String = "",
  val capitalizedOn: String = "",
  val costTk: Double = 0.0,
  val nbvTk: Double = 0.0,
  val farQty: Long = 0L,
  val farQtyPresent: Boolean = true,
  val method: String = "",
  val sheetOrigin: String = "Count Sheet",
  val importBatchId: String = "",
  val clientId: String = "",
  val fiscalYearId: String = "",
  val department: String = "",
  val location: String = "",
  val matchingKeyValue: String = "",
  val sourceWorkbookName: String = "",
  val sourceWorksheetName: String = "",
  val rawRowByHeaderJson: String = "{}",
  val mappedValuesByFieldIdJson: String = "{}",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  val assetKey: String
    @Exclude get() = id.ifBlank {
      if (clientId.isNotBlank() && fiscalYearId.isNotBlank()) {
        buildScopedAssetKey(clientId, fiscalYearId, assetId, sourceRow)
      } else {
        buildAssetKey(assetId, sourceRow)
      }
    }

  companion object {
    fun buildAssetKey(assetId: String, sourceRow: Long): String {
      val cleanId = assetId.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
      return "${cleanId}_R${sourceRow}"
    }

    fun buildScopedAssetKey(
      clientId: String,
      fiscalYearId: String,
      assetId: String,
      sourceRow: Long
    ): String {
      val cleanClient = clientId.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
      val cleanYear = fiscalYearId.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
      val cleanId = assetId.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
      return "${cleanClient}_${cleanYear}_${cleanId}_R${sourceRow}"
    }
  }
}

@IgnoreExtraProperties
data class CountingRecord(
  @DocumentId val id: String = "",
  val assetKey: String = "",
  val assetId: String = "",
  val sourceRow: Long = 0L,
  val found: String = "",
  val countDate: String = "",
  val countedBy: String = "",
  val countedByUserId: String = "",
  val buildingSite: String = "",
  val buildingSiteOther: String = "",
  val floor: String = "",
  val floorOther: String = "",
  val roomLocation: String = "",
  val roomLocationOther: String = "",
  val department: String = "",
  val departmentOther: String = "",
  val userCustodian: String = "",
  val tagStatus: String = "",
  val tagNumberAcn: String = "",
  val brandModel: String = "",
  val physicalQtyText: String = "",
  val condition: String = "",
  val inUse: String = "",
  val photoRef: String = "",
  val remarks: String = "",
  val qtyDiffText: String = "",
  val result: String = VerificationResultStatus.PENDING,
  val countingStatus: String = VerificationResultStatus.COUNTING_NOT_COUNTED,
  val applicableExceptions: String = "",
  val applicableExceptionsCsv: String = "",
  val farDataReviewFlag: Boolean = false,
  val reviewedBy: String = "",
  val reviewedByUserId: String = "",
  val reviewerRequiredFlag: Boolean = false,
  val reviewedAt: String = "",
  val reviewStatus: String = VerificationResultStatus.REVIEW_NOT_REQUIRED,
  val clientExplanation: String = "",
  val isDraft: Boolean = false,
  val auditTrailSummary: String = "",
  val clientId: String = "",
  val fiscalYearId: String = "",
  val dynamicFieldValuesJson: String = "{}",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
  @get:Exclude val hasPendingWrites: Boolean = false,
  @get:Exclude val isQueuedOffline: Boolean = false
) {
  val varianceQty: Long
    @Exclude get() = qtyDiffText.trim().toLongOrNull() ?: 0L

  val physicalCountQty: Long
    @Exclude get() = physicalQtyText.trim().toLongOrNull() ?: 0L

  @Exclude
  fun exceptionList(): List<String> =
    if (applicableExceptions.isBlank()) emptyList()
    else applicableExceptions.split("|").map { it.trim() }.filter { it.isNotEmpty() }

  @Exclude
  fun resolvedBuildingSite(): String =
    if (buildingSite.equals("Other", ignoreCase = true) && buildingSiteOther.isNotBlank()) {
      "Other: ${buildingSiteOther.trim()}"
    } else buildingSite

  @Exclude
  fun resolvedFloor(): String =
    if (floor.equals("Other", ignoreCase = true) && floorOther.isNotBlank()) {
      "Other: ${floorOther.trim()}"
    } else floor

  @Exclude
  fun resolvedRoomLocation(): String =
    if (roomLocation.equals("Other", ignoreCase = true) && roomLocationOther.isNotBlank()) {
      "Other: ${roomLocationOther.trim()}"
    } else roomLocation

  @Exclude
  fun resolvedDepartment(): String =
    if (department.equals("Other", ignoreCase = true) && departmentOther.isNotBlank()) {
      "Other: ${departmentOther.trim()}"
    } else department

  @Exclude
  fun syncBadgeLabel(): String = when {
    isQueuedOffline || hasPendingWrites -> SyncState.PENDING_SYNC
    isDraft -> SyncState.LOCAL_DRAFT
    else -> SyncState.SYNCED
  }
}

@IgnoreExtraProperties
data class MasterDataConfig(
  @DocumentId val id: String = "default_config",
  val auditors: String = "",
  val sites: String = "",
  val floors: String = "",
  val rooms: String = "",
  val departments: String = "",
  val clientId: String = "",
  val fiscalYearId: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  @Exclude
  fun auditorList(): List<String> = splitLinesWithOther(auditors, includeOther = false)

  @Exclude
  fun siteList(): List<String> = splitLinesWithOther(sites, includeOther = true)

  @Exclude
  fun floorList(): List<String> = splitLinesWithOther(floors, includeOther = true)

  @Exclude
  fun departmentList(): List<String> = splitLinesWithOther(departments, includeOther = true)

  @Exclude
  fun roomList(): List<String> = splitLinesWithOther(rooms, includeOther = true)

  @Exclude
  fun filteredRooms(site: String, floor: String): List<String> = roomListFor(site, floor)

  @Exclude
  fun roomListFor(site: String, floor: String): List<String> {
    val lines = rooms.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val matching = lines.mapNotNull { entry ->
      val parts = entry.split("|").map { it.trim() }
      when (parts.size) {
        3 -> {
          val entrySite = parts[0]
          val entryFloor = parts[1]
          val roomName = parts[2]
          val siteMatches = site.isBlank() || site.equals("Other", true) || entrySite.equals(site, true)
          val floorMatches = floor.isBlank() || floor.equals("Other", true) || entryFloor.equals(floor, true)
          if (siteMatches && floorMatches) roomName else null
        }
        else -> entry
      }
    }.distinct()
    return (matching + "Other").distinct()
  }

  private fun splitLinesWithOther(raw: String, includeOther: Boolean): List<String> {
    val items = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return if (includeOther) (items + "Other").distinct() else items
  }
}

@IgnoreExtraProperties
data class ExceptionRecord(
  @DocumentId val id: String = "",
  val assetKey: String = "",
  val assetId: String = "",
  val sourceRow: Long = 0L,
  val exceptionType: String = "",
  val description: String = "",
  val status: String = "Open",
  val followUpNotes: String = "",
  val resolutionHistory: String = "",
  val clientId: String = "",
  val fiscalYearId: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * Manual Unregistered Asset Observation (Found Not in FAR)
 */
@IgnoreExtraProperties
data class FoundNotInFarRecord(
  @DocumentId val id: String = "",
  val tempId: String = "",
  val assetIdIfKnown: String = "",
  val description: String = "",
  val assetClass: String = "",
  val buildingSite: String = "",
  val buildingSiteOther: String = "",
  val floor: String = "",
  val floorOther: String = "",
  val roomLocation: String = "",
  val roomLocationOther: String = "",
  val department: String = "",
  val departmentOther: String = "",
  val userCustodian: String = "",
  val tagStatus: String = "",
  val tagNumberAcn: String = "",
  val brandModel: String = "",
  val physicalQtyText: String = "",
  val physicalQty: Long = 0L,
  val found: String = "Yes",
  val condition: String = "",
  val inUse: String = "",
  val countDate: String = "",
  val countedBy: String = "",
  val countedByUserId: String = "",
  val reviewedBy: String = "",
  val reviewedByUserId: String = "",
  val reviewedAt: String = "",
  val photoRef: String = "",
  val remarks: String = "",
  val clientExplanation: String = "",
  val recordStatus: String = VerificationResultStatus.PENDING_REVIEW,
  val classificationStatus: String = "Unregistered Asset — Pending FAR Verification",
  val isDraft: Boolean = false,
  val auditTrailSummary: String = "",
  val clientId: String = "",
  val fiscalYearId: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
  @get:Exclude val hasPendingWrites: Boolean = false,
  @get:Exclude val isQueuedOffline: Boolean = false
) {
  val searchedAssetId: String
    @Exclude get() = assetIdIfKnown.ifBlank { tempId }

  val assetDescription: String
    @Exclude get() = description

  @Exclude
  fun resolvedBuildingSite(): String =
    if (buildingSite.equals("Other", ignoreCase = true) && buildingSiteOther.isNotBlank()) {
      "Other: ${buildingSiteOther.trim()}"
    } else buildingSite

  @Exclude
  fun resolvedFloor(): String =
    if (floor.equals("Other", ignoreCase = true) && floorOther.isNotBlank()) {
      "Other: ${floorOther.trim()}"
    } else floor

  @Exclude
  fun resolvedRoomLocation(): String =
    if (roomLocation.equals("Other", ignoreCase = true) && roomLocationOther.isNotBlank()) {
      "Other: ${roomLocationOther.trim()}"
    } else roomLocation

  @Exclude
  fun resolvedDepartment(): String =
    if (department.equals("Other", ignoreCase = true) && departmentOther.isNotBlank()) {
      "Other: ${departmentOther.trim()}"
    } else department
}

/**
 * Google Drive and local asset photo metadata stored separately from editable counting fields.
 * Zero Firebase Storage usage — binary images live locally until uploaded to Google Drive.
 */
@IgnoreExtraProperties
data class AssetPhotoRecord(
  @DocumentId val id: String = "",
  val photoId: String = "",
  val recordKey: String = "",
  val assetOrObsId: String = "",
  val recordType: String = "FAR_COUNT",
  val localPath: String = "",
  val originalLocalFilename: String = "",
  val finalDriveFilename: String = "",
  val mimeType: String = "image/jpeg",
  val fileExtension: String = "jpg",
  val nicknameSnapshot: String = "",
  val photoSequenceNumber: Long = 0L,
  val schemaVersion: Long = 1L,
  val dateFolderName: String = "",
  val driveParentFolderId: String = "",
  val driveDateFolderId: String = "",
  val driveFileId: String = "",
  val driveWebViewLink: String = "",
  val driveFolderId: String = "",
  val uploadStatus: String = SyncState.PENDING_SYNC,
  val errorMessage: String = "",
  val retryCount: Long = 0L,
  val capturedBy: String = "",
  val capturedByUserId: String = "",
  val capturedAtIso: String = "",
  val uploadedAtIso: String = "",
  val clientId: String = "",
  val fiscalYearId: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * User-isolated dynamic counting submission stored under `/users/{uid}/submissions/{submissionId}`
 */
@IgnoreExtraProperties
data class UserSubmissionRecord(
  @DocumentId val id: String = "",
  val submissionId: String = "",
  val submitterUid: String = "",
  val submitterName: String = "",
  val nicknameSnapshot: String = "",
  val workspaceId: String = "",
  val clientId: String = "",
  val fiscalYearId: String = "",
  val assetKey: String = "",
  val assetId: String = "",
  val sourceRow: Long = 0L,
  val worksheetName: String = "Count Sheet",
  val schemaVersionId: String = "schema_v1",
  val schemaVersionNumber: Long = 1L,
  val submissionStatus: String = "Draft",
  val fieldValuesJson: String = "{}",
  val fixedFieldSnapshotJson: String = "{}",
  val photoIdsCsv: String = "",
  val reviewNotes: String = "",
  val rejectionReason: String = "",
  val reviewedByUid: String = "",
  val reviewedByName: String = "",
  val reviewedAtIso: String = "",
  val publishedWorkbookVersion: Long = 0L,
  val publishedAtIso: String = "",
  val publishedByUid: String = "",
  val publishedByName: String = "",
  val publishedAuditLogId: String = "",
  val syncErrorMessage: String = "",
  val auditHistory: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * Admin-managed Google Drive integration settings & Master Excel Workbook status
 */
@IgnoreExtraProperties
data class ProjectSettings(
  @DocumentId val id: String = "main",
  val projectName: String = "AssetFlow — Fixed Asset Verification",
  val connectedDriveEmail: String = "",
  val rootDriveFolderId: String = "",
  val photosDriveFolderId: String = "",
  val reportsDriveFolderId: String = "",
  val backupsDriveFolderId: String = "",
  val masterWorkbookDriveFileId: String = "",
  val masterWorkbookFileName: String = "FA_Count_Verification_Sheet.xlsx",
  val masterWorkbookVersion: Long = 1L,
  val lastMasterUpdateAtIso: String = "",
  val lastMasterUpdateStatus: String = "Not Synced Yet",
  val lastMasterUpdateError: String = "",
  val driveConnectionStatus: String = "Not Connected",
  val projectStartDate: String = "2026-11-01",
  val originalPlannedCompletionDate: String = "2026-12-10",
  val approvedCompletionDate: String = "2026-12-10",
  val workingDaysPerWeek: Long = 6L,
  val nonWorkingDatesCsv: String = "",
  val planRevisionHistory: String = "",
  val activeClientId: String = "",
  val activeFiscalYearId: String = "2026_27",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * Master Workbook Drive sync metadata stored in Firebase (`/users/{orgId}/master_workbook_state/{clientId_fiscalYearId}`).
 * Does NOT duplicate the binary Excel workbook or full rows; stores only revision/MD5 state, lease lock, and idempotency ledger.
 */
@IgnoreExtraProperties
data class MasterWorkbookSyncMetadata(
  @DocumentId val id: String = "",
  val clientId: String = "",
  val fiscalYearId: String = "",
  val masterDriveFileId: String = "",
  val masterFileName: String = "",
  val driveFileName: String = "",
  val fileName: String = "",
  val targetWorksheetName: String = "Count Sheet",
  val targetSheetName: String = "Count Sheet",
  val headerRowIndex: Int = 1,
  val matchingKeyHeader: String = "Asset ID",
  val uniqueAssetKeyHeader: String = "Identification No.",
  val detectedHeaders: List<String> = emptyList(),
  val lastKnownDriveRevisionId: String = "",
  val lastKnownMd5Checksum: String = "",
  val lastModifiedTimeIso: String = "",
  val masterWorkbookVersion: Long = 1L,
  val syncLeaseOwnerJobId: String = "",
  val syncLeaseAcquiredAtMillis: Long = 0L,
  val syncLeaseExpiresAtMillis: Long = 0L,
  val appliedSubmissionIdsJson: String = "{}", // JSON map of submissionId/batchId -> syncJobId
  val lastSyncStatus: String = "NOT_SYNCED", // NOT_SYNCED | IN_PROGRESS | SYNCED_TO_DRIVE | SYNC_FAILED_RETRYABLE | CONFLICT_DETECTED
  val lastSyncError: String = "",
  val lastSyncedByUid: String = "",
  val lastSyncedByName: String = "",
  val lastSyncedAtIso: String = "",
  val lastSyncTimestampIso: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class MasterWorkbookSyncJob(
  @DocumentId val id: String = "",
  val jobId: String = "",
  val clientId: String = "",
  val fiscalYearId: String = "",
  val batchId: String = "",
  val batchVersionNumber: Long = 1L,
  val masterDriveFileId: String = "",
  val status: String = "QUEUED_FOR_DRIVE_SYNC", // QUEUED_FOR_DRIVE_SYNC | IN_PROGRESS | SYNCED_TO_DRIVE | SYNC_FAILED_RETRYABLE | CONFLICT_DETECTED
  val baseDriveRevisionId: String = "",
  val baseMd5Checksum: String = "",
  val resultingDriveRevisionId: String = "",
  val resultingMd5Checksum: String = "",
  val updatedAssetCount: Long = 0L,
  val updatedCellCount: Long = 0L,
  val errorMessage: String = "",
  val requestedByUid: String = "",
  val requestedByName: String = "",
  val startedAtIso: String = "",
  val completedAtIso: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * Audit activity log entry for administrative, review, export, and schedule revision actions
 */
@IgnoreExtraProperties
data class ActivityLogRecord(
  @DocumentId val id: String = "",
  val actionType: String = "",
  val targetId: String = "",
  val targetOwnerUid: String = "",
  val summary: String = "",
  val actorName: String = "",
  val actorUserId: String = "",
  val actorRole: String = "",
  val timestampIso: String = "",
  val clientId: String = "",
  val fiscalYearId: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * Export & Drive Sync Job record
 */
@IgnoreExtraProperties
data class ExportJobRecord(
  @DocumentId val id: String = "",
  val jobType: String = "",
  val status: String = "",
  val versionNumber: Long = 1L,
  val driveFileId: String = "",
  val localFilePath: String = "",
  val recordCount: Long = 0L,
  val errorMessage: String = "",
  val requestedBy: String = "",
  val requestedByUserId: String = "",
  val completedAtIso: String = "",
  val exportedAtIso: String = "",
  val clientId: String = "",
  val fiscalYearId: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * Combined UI view model pairing an original FARAsset with its CountingRecord (if any)
 */
data class AssetWithCount(
  val farAsset: FARAsset,
  val countingRecord: CountingRecord?
) {
  val effectiveResult: String
    get() = countingRecord?.result?.ifBlank { VerificationResultStatus.PENDING }
      ?: VerificationResultStatus.PENDING

  val effectiveCountingStatus: String
    get() = countingRecord?.countingStatus?.ifBlank { VerificationResultStatus.COUNTING_NOT_COUNTED }
      ?: VerificationResultStatus.COUNTING_NOT_COUNTED

  val effectiveReviewStatus: String
    get() = countingRecord?.reviewStatus?.ifBlank { VerificationResultStatus.REVIEW_NOT_REQUIRED }
      ?: VerificationResultStatus.REVIEW_NOT_REQUIRED

  val isCounted: Boolean
    get() = effectiveResult != VerificationResultStatus.PENDING
}

/**
 * Submission & Review Lifecycle Statuses (8 explicit states)
 * Plus orthogonal Master Drive Sync Statuses.
 */
object SubmissionBatchStatus {
  const val DRAFT = "Draft / In Progress"
  const val SUBMITTED = "Submitted / Pending Review"
  const val UNDER_REVIEW = "Under Review"
  const val CHANGES_REQUESTED = "Changes Requested"
  const val CORRECTED = "Corrected / Resubmitted"
  const val APPROVED_AWAITING_MASTER = "Approved — Awaiting Master Update"
  const val APPROVED_AWAITING_MASTER_UPDATE = APPROVED_AWAITING_MASTER
  const val PUBLISHED = "Published"
  const val REJECTED = "Rejected"
  const val SYNC_FAILED = "Sync Failed"

  const val MASTER_SYNC_NOT_QUEUED = "NOT_QUEUED"
  const val MASTER_SYNC_QUEUED = "QUEUED_FOR_DRIVE_SYNC"
  const val MASTER_SYNC_IN_PROGRESS = "IN_PROGRESS"
  const val MASTER_SYNC_SYNCED = "SYNCED_TO_DRIVE"
  const val MASTER_SYNC_FAILED = "SYNC_FAILED_RETRYABLE"
  const val MASTER_SYNC_CONFLICT = "CONFLICT_DETECTED"
  const val PENDING_DRIVE_SYNC = MASTER_SYNC_QUEUED
  const val SYNCING_TO_DRIVE = MASTER_SYNC_IN_PROGRESS
}

/**
 * Date-wise consolidated Daily Input Sheet submitted by a user (`daily_batches`).
 */
@IgnoreExtraProperties
data class DailyBatchSubmissionRecord(
  @DocumentId val id: String = "",
  val batchId: String = "",
  val userId: String = "",
  val submitterUid: String = "",
  val submitterFullName: String = "",
  val submitterNickname: String = "",
  val clientId: String = "",
  val fiscalYearId: String = "",
  val countingDate: String = "",
  val versionNumber: Long = 1L,
  val recordCount: Long = 0L,
  val countedAssetCount: Long = 0L,
  val foundCount: Long = 0L,
  val notFoundCount: Long = 0L,
  val notAccessibleCount: Long = 0L,
  val manualEntryCount: Long = 0L,
  val foundNotInFarCount: Long = 0L,
  val status: String = SubmissionBatchStatus.DRAFT,
  val approvalStatus: String = "PENDING_REVIEW", // PENDING_REVIEW | APPROVED | CHANGES_REQUESTED | REJECTED
  val masterSyncStatus: String = SubmissionBatchStatus.MASTER_SYNC_NOT_QUEUED,
  val masterSyncJobId: String = "",
  val masterSyncError: String = "",
  val masterSyncErrorMessage: String = "",
  val dailySheetDriveFileId: String = "",
  val recordsJson: String = "[]",
  val assetIds: List<String> = emptyList(),
  val assetKeysCsv: String = "",
  val foundNotInFarIds: List<String> = emptyList(),
  val isChunkedSnapshot: Boolean = false,
  val chunkCount: Long = 0L,
  val auditorNotes: String = "",
  val adminRemarks: String = "",
  val remarksByUid: String = "",
  val remarksByName: String = "",
  val remarksAtIso: String = "",
  val reviewedByUid: String = "",
  val reviewedByName: String = "",
  val reviewedAtIso: String = "",
  val publishedByUid: String = "",
  val publishedByName: String = "",
  val publishedAtIso: String = "",
  val publishedMasterFileId: String = "",
  val publishedMasterVersion: Long = 0L,
  val publicationAuditLogId: String = "",
  val historyVersionsJson: String = "[]",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  val batchName: String
    @Exclude get() = "Sheet $countingDate (${submitterFullName.ifBlank { submitterNickname }})"

  val targetDate: String
    @Exclude get() = countingDate

  val auditorDisplayName: String
    @Exclude get() = submitterFullName.ifBlank { submitterNickname }

  val auditorName: String
    @Exclude get() = submitterFullName.ifBlank { submitterNickname }

  val auditorNickname: String
    @Exclude get() = submitterNickname

  val totalRecordCount: Long
    @Exclude get() = recordCount

  @Exclude
  fun assetKeyList(): List<String> =
    if (assetIds.isNotEmpty()) assetIds
    else assetKeysCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * In-App Notification Record for Remarks, Review Status, and Resubmissions
 */
@IgnoreExtraProperties
data class NotificationRecord(
  @DocumentId val id: String = "",
  val userId: String = "",
  val clientId: String = "",
  val fiscalYearId: String = "",
  val title: String = "",
  val message: String = "",
  val type: String = "REMARKS", // REMARKS, APPROVED, PUBLISHED, RESUBMITTED, ACCESS_REQUEST
  val batchId: String = "",
  val countingDate: String = "",
  val isRead: Boolean = false,
  val timestampIso: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null
)

/**
 * Excel Column Mapping Configuration (`column_mapping`)
 */
@IgnoreExtraProperties
data class ExcelColumnMappingConfig(
  @DocumentId val id: String = "main",
  val worksheetName: String = "Count Sheet",
  val targetSheetName: String = "Count Sheet",
  val headerRowIndex: Int = 1,
  val uniqueAssetKeyHeader: String = "Identification No.",
  val clientId: String = "",
  val fiscalYearId: String = "",
  val slColumn: String = "A",
  val assetIdColumn: String = "B",
  val assetDescriptionColumn: String = "C",
  val descriptionColumn: String = "C",
  val assetClassColumn: String = "D",
  val capitalizedOnColumn: String = "E",
  val costColumn: String = "F",
  val nbvColumn: String = "G",
  val farQtyColumn: String = "H",
  val methodColumn: String = "I",
  val foundColumn: String = "J",
  val countDateColumn: String = "K",
  val countedByColumn: String = "L",
  val buildingSiteColumn: String = "M",
  val siteColumn: String = "M",
  val floorColumn: String = "N",
  val roomLocationColumn: String = "O",
  val departmentColumn: String = "P",
  val userCustodianColumn: String = "Q",
  val tagStatusColumn: String = "R",
  val tagNumberColumn: String = "S",
  val brandModelColumn: String = "T",
  val physicalQtyColumn: String = "U",
  val conditionColumn: String = "V",
  val inUseColumn: String = "W",
  val photoRefColumn: String = "X",
  val remarksColumn: String = "Y",
  val qtyDiffColumn: String = "Z",
  val resultColumn: String = "AA",
  val reviewedByColumn: String = "AB",
  val clientExplanationColumn: String = "AC",
  val userId: String = "",
  @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * Durable backend/repository state machine for Permanent Client and User Deletion (`deletion_jobs`).
 */
@IgnoreExtraProperties
data class DeletionJobRecord(
  @DocumentId val id: String = "",
  val jobId: String = "",
  val jobType: String = "CLIENT_DELETION", // CLIENT_DELETION | USER_DELETION
  val targetId: String = "", // clientId or targetUid
  val targetLabel: String = "",
  val status: String = "PREVIEW_READY", // PREVIEW_READY | CONFIRMED_QUEUED | IN_PROGRESS | DRIVE_CLEANUP_PENDING | VERIFYING | COMPLETED | FAILED_RETRYABLE
  val deleteDriveArtifactsOptIn: Boolean = false,
  val impactSummaryJson: String = "{}",
  val deletedCountsJson: String = "{}",
  val pseudonymizedCount: Long = 0L,
  val authAccountDeleted: Boolean = false,
  val errorMessage: String = "",
  val requestedByUid: String = "",
  val requestedByRole: String = "",
  val startedAtIso: String = "",
  val completedAtIso: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DeletedIdentityTombstone(
  @DocumentId val id: String = "",
  val deletedUid: String = "",
  val pseudonymLabel: String = "",
  val deletedByUid: String = "",
  val deletedAtIso: String = "",
  val deletionJobId: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DeletionAuditEvent(
  @DocumentId val id: String = "",
  val eventId: String = "",
  val jobId: String = "",
  val deletionType: String = "", // CLIENT_DELETION | USER_DELETION
  val targetType: String = "",
  val targetOpaqueRef: String = "", // clientId or Deleted User [#hash]
  val targetIdHash: String = "",
  val categoryCountsJson: String = "{}",
  val driveCleanupIncluded: Boolean = false,
  val outcome: String = "COMPLETED",
  val status: String = "COMPLETED",
  val actorUid: String = "",
  val actorEmail: String = "",
  val actorRole: String = "",
  val timestampIso: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null
)

data class DeletionImpactSummary(
  val targetType: String = "CLIENT", // "CLIENT" or "USER"
  val targetId: String = "",
  val targetDisplayName: String = "",
  val farAssetCount: Int = 0,
  val farAssetsCount: Int = 0,
  val countingRecordCount: Int = 0,
  val assetCountsCount: Int = 0,
  val dailyBatchCount: Int = 0,
  val dailyBatchesCount: Int = 0,
  val submissionCount: Int = 0,
  val photoMetadataCount: Int = 0,
  val assetPhotosCount: Int = 0,
  val unlistedAssetsCount: Int = 0,
  val assignmentCount: Int = 0,
  val assignmentsCount: Int = 0,
  val accessRequestCount: Int = 0,
  val accessRequestsCount: Int = 0,
  val schemaVersionCount: Int = 0,
  val schemaConfigsCount: Int = 0,
  val draftRecordCountToDelete: Int = 0,
  val userDraftRecordsCount: Int = 0,
  val historicalRecordCountToPseudonymize: Int = 0,
  val userSubmittedHistoricalRecordsCount: Int = 0,
  val driveRootFolderId: String = "",
  val masterWorkbookDriveFileId: String = ""
)
