package com.example.domain

import com.example.data.model.AssetWithCount
import com.example.data.model.AuditDropdownOptions
import com.example.data.model.CountingRecord
import com.example.data.model.FARAsset
import com.example.data.model.FoundNotInFarRecord
import com.example.data.model.MasterDataConfig
import com.example.data.model.VerificationResultStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class QuantityValidationResult(
  val isValid: Boolean,
  val isBlank: Boolean,
  val parsedQuantity: Long?,
  val errorMessage: String? = null
)

data class CalculationOutcome(
  val qtyDiffText: String,
  val primaryResult: String,
  val countingStatus: String,
  val applicableExceptions: List<String>,
  val validationErrors: List<String>,
  val farDataReviewFlag: Boolean,
  val countedBy: String,
  val countedByUserId: String,
  val reviewedBy: String,
  val reviewedByUserId: String,
  val reviewerRequiredFlag: Boolean,
  val reviewStatus: String,
  val reviewedAt: String
)

data class ManualObservationOutcome(
  val isValidToSave: Boolean,
  val validationErrors: List<String>,
  val preparedRecord: FoundNotInFarRecord
)

data class FarAssetSearchResult(
  val query: String,
  val exactMatches: List<AssetWithCount>,
  val partialMatches: List<AssetWithCount>,
  val hasDuplicateExactAssetId: Boolean,
  val isNotFoundInFar: Boolean
)

/**
 * Pure domain calculation and audit rules engine implementing all 29-field verification rules,
 * profile display name attribution with historical preservation, search-first FAR lookup,
 * manual unregistered observation validation, and separate Counting / Review / Verification states.
 */
object AuditCalculationEngine {

  fun currentLocalDateString(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    return sdf.format(Date())
  }

  fun currentIsoTimestamp(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    return sdf.format(Date())
  }

  fun generateUniqueObservationId(): String {
    val dateCompact = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    val suffix = UUID.randomUUID().toString().uppercase(Locale.US).replace("-", "").take(6)
    return "OBS-$dateCompact-$suffix"
  }

  /**
   * Resolves Count Date:
   * - Defaults to the current local date when creating a new count record.
   * - Reopening or editing a record does NOT automatically replace its original count date.
   * - If the user explicitly edits the date (explicitDateEdit == true), the new date is preserved.
   */
  fun resolveCountDate(
    existingSavedCountDate: String?,
    userEnteredDate: String?,
    explicitDateEdit: Boolean = false
  ): String {
    if (explicitDateEdit && !userEnteredDate.isNullOrBlank()) {
      return userEnteredDate.trim()
    }
    if (!existingSavedCountDate.isNullOrBlank()) {
      return existingSavedCountDate.trim()
    }
    if (!userEnteredDate.isNullOrBlank()) {
      return userEnteredDate.trim()
    }
    return currentLocalDateString()
  }

  /**
   * Resolves Counted By name and user ID:
   * - Automatically populates with the current authenticated user's Full Name when created.
   * - If the record already has a saved historical countedBy name, preserves that historical name.
   */
  fun resolveCounterAttribution(
    existingCountedBy: String?,
    existingCountedByUserId: String?,
    draftCountedBy: String?,
    authenticatedFullName: String?,
    authenticatedUserId: String?
  ): Pair<String, String> {
    val historicalName = existingCountedBy?.trim().orEmpty()
    val historicalUid = existingCountedByUserId?.trim().orEmpty()
    if (historicalName.isNotEmpty()) {
      return historicalName to historicalUid.ifEmpty { authenticatedUserId?.trim().orEmpty() }
    }
    val fullName = authenticatedFullName?.trim().orEmpty()
    val profileUid = authenticatedUserId?.trim().orEmpty()
    if (fullName.isNotEmpty()) {
      return fullName to profileUid
    }
    return draftCountedBy?.trim().orEmpty() to profileUid
  }

  /**
   * Validates Physical Qty:
   * - Accepts non-negative whole numbers only (0, 1, 2, ...).
   * - Rejects negative numbers, decimals, and invalid text.
   * - Distinguishes blank ("") from valid zero ("0").
   */
  fun validatePhysicalQty(rawInput: String): QuantityValidationResult {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) {
      return QuantityValidationResult(
        isValid = true,
        isBlank = true,
        parsedQuantity = null,
        errorMessage = null
      )
    }
    if (trimmed.contains(".") || trimmed.contains(",")) {
      return QuantityValidationResult(
        isValid = false,
        isBlank = false,
        parsedQuantity = null,
        errorMessage = "Decimals are not allowed in Physical Qty. Enter a whole number."
      )
    }
    val parsed = trimmed.toLongOrNull()
    if (parsed == null) {
      return QuantityValidationResult(
        isValid = false,
        isBlank = false,
        parsedQuantity = null,
        errorMessage = "Invalid text in Physical Qty. Enter a non-negative whole number."
      )
    }
    if (parsed < 0L) {
      return QuantityValidationResult(
        isValid = false,
        isBlank = false,
        parsedQuantity = null,
        errorMessage = "Negative numbers are not allowed in Physical Qty."
      )
    }
    return QuantityValidationResult(
      isValid = true,
      isBlank = false,
      parsedQuantity = parsed,
      errorMessage = null
    )
  }

  /**
   * Formula: Qty Diff = Physical Qty - FAR Qty
   * Rules:
   * - If Physical Qty is blank, Qty Diff is blank.
   * - If FAR Qty is missing (!farQtyPresent), Qty Diff is blank and record is flagged for data review.
   * - Zero is a valid quantity; blank is never treated as zero.
   */
  fun calculateQtyDiff(
    physicalQtyText: String,
    farQty: Long,
    farQtyPresent: Boolean
  ): Pair<String, Boolean> {
    if (!farQtyPresent) {
      return "" to true
    }
    val qtyValidation = validatePhysicalQty(physicalQtyText)
    if (!qtyValidation.isValid || qtyValidation.isBlank || qtyValidation.parsedQuantity == null) {
      return "" to false
    }
    val diff = qtyValidation.parsedQuantity - farQty
    return diff.toString() to false
  }

  /**
   * Evaluates the exact 9-step priority for Result, maintains separate Counting and Review statuses,
   * enforces Full Name attribution for Counted By and Reviewed By with historical name
   * preservation, and collects ALL applicable exceptions separately.
   */
  fun evaluateRecord(
    farAsset: FARAsset,
    record: CountingRecord,
    masterData: MasterDataConfig? = null,
    authenticatedReviewerFullName: String? = null,
    authenticatedReviewerUserId: String? = null,
    authenticatedCounterFullName: String? = null,
    authenticatedCounterUserId: String? = null,
    existingRecord: CountingRecord? = null,
    markAsReviewed: Boolean = false
  ): CalculationOutcome {
    val validationErrors = mutableListOf<String>()
    val exceptions = mutableListOf<String>()

    val (resolvedCountedBy, resolvedCountedByUid) = resolveCounterAttribution(
      existingCountedBy = existingRecord?.countedBy,
      existingCountedByUserId = existingRecord?.countedByUserId,
      draftCountedBy = record.countedBy,
      authenticatedFullName = authenticatedCounterFullName,
      authenticatedUserId = authenticatedCounterUserId
    )

    val found = record.found.trim()
    if (found.isNotEmpty() && found !in AuditDropdownOptions.FOUND_OPTIONS) {
      validationErrors.add("Invalid Found? option: '$found'")
    }

    val qtyValidation = validatePhysicalQty(record.physicalQtyText)
    if (!qtyValidation.isValid && qtyValidation.errorMessage != null) {
      validationErrors.add(qtyValidation.errorMessage)
    }

    if (record.tagStatus.isNotBlank() &&
      !TagStatusNormalizer.isValidTagStatus(record.tagStatus)
    ) {
      validationErrors.add("Invalid Tag Status option: '${record.tagStatus}'")
    }

    if (record.condition.isNotBlank() &&
      record.condition !in AuditDropdownOptions.CONDITION_OPTIONS &&
      record.condition !in AuditDropdownOptions.SPECIAL_OPTIONAL_STATES
    ) {
      validationErrors.add("Invalid Condition option: '${record.condition}'")
    }

    if (record.inUse.isNotBlank() &&
      record.inUse !in AuditDropdownOptions.IN_USE_OPTIONS &&
      record.inUse !in AuditDropdownOptions.SPECIAL_OPTIONAL_STATES
    ) {
      validationErrors.add("Invalid In Use? option: '${record.inUse}'")
    }

    // Validate "Other" custom inputs
    if (record.buildingSite == AuditDropdownOptions.OTHER_OPTION && record.buildingSiteOther.isBlank()) {
      validationErrors.add("Building / Site is 'Other' but custom Building / Site value is blank.")
    }
    if (record.floor == AuditDropdownOptions.OTHER_OPTION && record.floorOther.isBlank()) {
      validationErrors.add("Floor is 'Other' but custom Floor value is blank.")
    }
    if (record.roomLocation == AuditDropdownOptions.OTHER_OPTION && record.roomLocationOther.isBlank()) {
      validationErrors.add("Room / Location is 'Other' but custom Room / Location value is blank.")
    }
    if (record.department == AuditDropdownOptions.OTHER_OPTION && record.departmentOther.isBlank()) {
      validationErrors.add("Department is 'Other' but custom Department value is blank.")
    }

    // Check authorised auditor master list only when no authenticated profile Full Name is driving attribution
    val configuredAuditors = masterData?.auditorList().orEmpty()
    if (authenticatedCounterFullName.isNullOrBlank() &&
      resolvedCountedBy.isNotBlank() &&
      configuredAuditors.isNotEmpty() &&
      resolvedCountedBy !in configuredAuditors
    ) {
      validationErrors.add("Counted By '$resolvedCountedBy' is not in the authorised auditor master list.")
    }

    val (qtyDiffText, farDataReviewFlag) = calculateQtyDiff(
      physicalQtyText = record.physicalQtyText,
      farQty = farAsset.farQty,
      farQtyPresent = farAsset.farQtyPresent
    )

    if (farDataReviewFlag) {
      exceptions.add("FAR Data Review: FAR Qty is missing in register")
    }

    // Collect every applicable exception independently of primary Result priority
    if (found == "No") {
      exceptions.add(VerificationResultStatus.NOT_FOUND)
    } else if (found == "Not Accessible") {
      exceptions.add(VerificationResultStatus.NOT_ACCESSIBLE)
    }

    if (farAsset.farQtyPresent && qtyValidation.isValid && !qtyValidation.isBlank && qtyValidation.parsedQuantity != null) {
      val physQty = qtyValidation.parsedQuantity
      if (physQty < farAsset.farQty) {
        exceptions.add("${VerificationResultStatus.QTY_SHORT} (Physical: $physQty vs FAR: ${farAsset.farQty})")
      } else if (physQty > farAsset.farQty) {
        exceptions.add("${VerificationResultStatus.QTY_EXCESS} (Physical: $physQty vs FAR: ${farAsset.farQty})")
      }
    }

    if (record.condition in AuditDropdownOptions.CONDITION_ISSUE_VALUES) {
      exceptions.add("Condition Issue (${record.condition})")
    }

    val normalizedTagStatus = TagStatusNormalizer.normalize(record.tagStatus)
    if (record.tagStatus.isNotBlank() &&
      TagStatusNormalizer.isValidTagStatus(record.tagStatus) &&
      !TagStatusNormalizer.isPositive(record.tagStatus)
    ) {
      exceptions.add("Tag Issue ($normalizedTagStatus)")
    }

    // Determine if all required verification fields are complete when Found? == "Yes"
    // Note: "Unknown" is treated as incomplete for full verification, while "Not Applicable" is valid for Floor/Room
    val siteComplete = record.buildingSite.isNotBlank() &&
      record.buildingSite != AuditDropdownOptions.UNKNOWN_OPTION &&
      (record.buildingSite != AuditDropdownOptions.OTHER_OPTION || record.buildingSiteOther.isNotBlank())
    val floorComplete = record.floor.isNotBlank() &&
      record.floor != AuditDropdownOptions.UNKNOWN_OPTION &&
      (record.floor != AuditDropdownOptions.OTHER_OPTION || record.floorOther.isNotBlank())
    val roomComplete = record.roomLocation.isNotBlank() &&
      record.roomLocation != AuditDropdownOptions.UNKNOWN_OPTION &&
      (record.roomLocation != AuditDropdownOptions.OTHER_OPTION || record.roomLocationOther.isNotBlank())
    val deptComplete = record.department.isNotBlank() &&
      record.department != AuditDropdownOptions.UNKNOWN_OPTION &&
      (record.department != AuditDropdownOptions.OTHER_OPTION || record.departmentOther.isNotBlank())

    val requiredFieldsCompleteForYes =
      !record.isDraft &&
      record.countDate.isNotBlank() &&
      resolvedCountedBy.isNotBlank() &&
      siteComplete &&
      floorComplete &&
      roomComplete &&
      deptComplete &&
      TagStatusNormalizer.isCompleteForVerification(record.tagStatus) &&
      qtyValidation.isValid && !qtyValidation.isBlank && qtyValidation.parsedQuantity != null &&
      farAsset.farQtyPresent &&
      record.condition in AuditDropdownOptions.CONDITION_OPTIONS &&
      record.inUse in AuditDropdownOptions.IN_USE_OPTIONS &&
      validationErrors.isEmpty()

    // Exact 9-step priority for Result (never labels a Draft or incomplete entry "Verified"):
    val primaryResult = when {
      record.isDraft -> VerificationResultStatus.PENDING_VERIFICATION
      // 1. Found? blank -> Pending
      found.isEmpty() -> VerificationResultStatus.PENDING
      // 2. Found? = No -> Not Found
      found == "No" -> VerificationResultStatus.NOT_FOUND
      // 3. Found? = Not Accessible -> Not Accessible
      found == "Not Accessible" -> VerificationResultStatus.NOT_ACCESSIBLE
      // 4. Required verification fields incomplete -> Pending Verification
      found != "Yes" || !requiredFieldsCompleteForYes -> VerificationResultStatus.PENDING_VERIFICATION
      // 5. Physical Qty < FAR Qty -> Qty Short
      qtyValidation.parsedQuantity!! < farAsset.farQty -> VerificationResultStatus.QTY_SHORT
      // 6. Physical Qty > FAR Qty -> Qty Excess
      qtyValidation.parsedQuantity > farAsset.farQty -> VerificationResultStatus.QTY_EXCESS
      // 7. Condition = Poor, Damaged, Idle, or Not Working -> Verified - Condition Issue
      record.condition in AuditDropdownOptions.CONDITION_ISSUE_VALUES ->
        VerificationResultStatus.VERIFIED_CONDITION_ISSUE
      // 8. Tag Status is not positive -> Verified - Tag Issue
      !TagStatusNormalizer.isPositive(record.tagStatus) -> VerificationResultStatus.VERIFIED_TAG_ISSUE
      // 9. All required checks are complete and no exceptions exist -> Verified
      else -> VerificationResultStatus.VERIFIED
    }

    val countingStatus = when {
      record.isDraft -> VerificationResultStatus.DRAFT
      found.isEmpty() -> VerificationResultStatus.PENDING
      primaryResult == VerificationResultStatus.PENDING_VERIFICATION -> VerificationResultStatus.PENDING_VERIFICATION
      else -> VerificationResultStatus.COUNTED
    }

    // Reviewer attribution & historical preservation:
    // - Only populate Reviewed By when markAsReviewed == true or when a previous review was already recorded.
    // - Never copy the original counter's name into Reviewed By.
    // - Preserve historical reviewedBy if already reviewed.
    val cleanReviewerFullName = authenticatedReviewerFullName?.trim().orEmpty()
    val cleanReviewerUid = authenticatedReviewerUserId?.trim().orEmpty()
    val existingReviewedBy = existingRecord?.reviewedBy?.trim().orEmpty().ifEmpty { record.reviewedBy.trim() }
    val existingReviewedByUid = existingRecord?.reviewedByUserId?.trim().orEmpty().ifEmpty { record.reviewedByUserId.trim() }
    val existingReviewedAt = existingRecord?.reviewedAt?.trim().orEmpty().ifEmpty { record.reviewedAt.trim() }

    val resolvedReviewedBy: String
    val resolvedReviewedByUid: String
    val reviewerRequiredFlag: Boolean
    val reviewStatus: String
    val reviewedAt: String

    if (record.isDraft && !markAsReviewed) {
      resolvedReviewedBy = existingReviewedBy
      resolvedReviewedByUid = existingReviewedByUid
      reviewerRequiredFlag = false
      reviewStatus = VerificationResultStatus.DRAFT
      reviewedAt = existingReviewedAt
    } else if (markAsReviewed) {
      if (existingReviewedBy.isNotEmpty()) {
        // Historical reviewer name preserved if already reviewed earlier
        resolvedReviewedBy = existingReviewedBy
        resolvedReviewedByUid = existingReviewedByUid.ifEmpty { cleanReviewerUid }
        reviewerRequiredFlag = false
        reviewStatus = VerificationResultStatus.REVIEWED
        reviewedAt = existingReviewedAt.ifBlank { currentIsoTimestamp() }
      } else if (cleanReviewerFullName.isNotEmpty()) {
        resolvedReviewedBy = cleanReviewerFullName
        resolvedReviewedByUid = cleanReviewerUid
        reviewerRequiredFlag = false
        reviewStatus = VerificationResultStatus.REVIEWED
        reviewedAt = existingReviewedAt.ifBlank { currentIsoTimestamp() }
      } else {
        resolvedReviewedBy = ""
        resolvedReviewedByUid = ""
        reviewerRequiredFlag = true
        reviewStatus = "Reviewer Identification Required"
        reviewedAt = ""
      }
    } else if (existingReviewedBy.isNotEmpty()) {
      resolvedReviewedBy = existingReviewedBy
      resolvedReviewedByUid = existingReviewedByUid
      reviewerRequiredFlag = false
      reviewStatus = VerificationResultStatus.REVIEWED
      reviewedAt = existingReviewedAt
    } else {
      resolvedReviewedBy = ""
      resolvedReviewedByUid = ""
      reviewerRequiredFlag = false
      reviewStatus = VerificationResultStatus.PENDING_REVIEW
      reviewedAt = ""
    }

    return CalculationOutcome(
      qtyDiffText = qtyDiffText,
      primaryResult = primaryResult,
      countingStatus = countingStatus,
      applicableExceptions = exceptions,
      validationErrors = validationErrors,
      farDataReviewFlag = farDataReviewFlag,
      countedBy = resolvedCountedBy,
      countedByUserId = resolvedCountedByUid,
      reviewedBy = resolvedReviewedBy,
      reviewedByUserId = resolvedReviewedByUid,
      reviewerRequiredFlag = reviewerRequiredFlag,
      reviewStatus = reviewStatus,
      reviewedAt = reviewedAt
    )
  }

  /**
   * Search-First FAR Lookup (Requirement 2 & 3):
   * - Supports exact Asset ID matching and partial Asset ID / Description / Tag matching.
   * - Detects duplicate Asset IDs across multiple source rows so the user can choose the exact row.
   * - Identifies when a searched Asset ID is not found in the imported FAR so the user can create
   *   a manual unregistered asset observation.
   */
  fun searchFarAssets(
    countableAssets: List<AssetWithCount>,
    rawQuery: String
  ): FarAssetSearchResult {
    val q = rawQuery.trim()
    if (q.isEmpty()) {
      return FarAssetSearchResult(
        query = "",
        exactMatches = emptyList(),
        partialMatches = countableAssets,
        hasDuplicateExactAssetId = false,
        isNotFoundInFar = false
      )
    }

    val exactMatches = countableAssets.filter {
      it.farAsset.assetId.trim().equals(q, ignoreCase = true)
    }.sortedBy { it.farAsset.sourceRow }

    val partialMatches = countableAssets.filter { item ->
      val far = item.farAsset
      val cnt = item.countingRecord
      far.assetId.contains(q, ignoreCase = true) ||
        far.assetDescription.contains(q, ignoreCase = true) ||
        cnt?.tagNumberAcn?.contains(q, ignoreCase = true) == true ||
        cnt?.userCustodian?.contains(q, ignoreCase = true) == true
    }.sortedBy { it.farAsset.sourceRow }

    return FarAssetSearchResult(
      query = q,
      exactMatches = exactMatches,
      partialMatches = partialMatches,
      hasDuplicateExactAssetId = exactMatches.size > 1,
      isNotFoundInFar = exactMatches.isEmpty()
    )
  }

  /**
   * Validates and prepares a Manual Physical Asset Observation ("Found Not in FAR" / "Unregistered Asset Observation"):
   * - Generates a unique observation ID when the user does not have an Asset ID.
   * - Requires at minimum a saveable observation identifier and enough information to identify the
   *   observation (such as a description, photo, or known Asset ID/tag).
   * - Allows all other unknown fields to remain blank ("") without inserting fake placeholders.
   * - Preserves original Count Date and historical Counted By / Reviewed By display names.
   * - Never automatically classifies the item as a confirmed client-owned fixed asset.
   */
  fun evaluateManualObservation(
    draft: FoundNotInFarRecord,
    existingRecord: FoundNotInFarRecord? = null,
    authenticatedCounterFullName: String? = null,
    authenticatedCounterUserId: String? = null,
    authenticatedReviewerFullName: String? = null,
    authenticatedReviewerUserId: String? = null,
    markAsReviewed: Boolean = false,
    explicitDateEdit: Boolean = false
  ): ManualObservationOutcome {
    val errors = mutableListOf<String>()

    val resolvedTempId = existingRecord?.tempId?.takeIf { it.isNotBlank() }
      ?: draft.tempId.trim().ifEmpty {
        if (draft.assetIdIfKnown.isNotBlank()) {
          "OBS-${draft.assetIdIfKnown.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_")}"
        } else {
          generateUniqueObservationId()
        }
      }

    // Require at minimum a description, photo, or known Asset ID/tag to identify the observation
    val hasIdentifyingInfo = draft.description.isNotBlank() ||
      draft.photoRef.isNotBlank() ||
      draft.assetIdIfKnown.isNotBlank() ||
      draft.tagNumberAcn.isNotBlank()

    if (!hasIdentifyingInfo) {
      errors.add("Provide at least an Asset Description, Photo attachment, or known Asset ID/Tag to identify this observation.")
    }

    val qtyValidation = validatePhysicalQty(draft.physicalQtyText)
    if (!qtyValidation.isValid && qtyValidation.errorMessage != null) {
      errors.add(qtyValidation.errorMessage)
    }

    if (draft.buildingSite == AuditDropdownOptions.OTHER_OPTION && draft.buildingSiteOther.isBlank()) {
      errors.add("Building / Site is 'Other' but custom Building / Site value is blank.")
    }
    if (draft.floor == AuditDropdownOptions.OTHER_OPTION && draft.floorOther.isBlank()) {
      errors.add("Floor is 'Other' but custom Floor value is blank.")
    }
    if (draft.roomLocation == AuditDropdownOptions.OTHER_OPTION && draft.roomLocationOther.isBlank()) {
      errors.add("Room / Location is 'Other' but custom Room / Location value is blank.")
    }
    if (draft.department == AuditDropdownOptions.OTHER_OPTION && draft.departmentOther.isBlank()) {
      errors.add("Department is 'Other' but custom Department value is blank.")
    }

    val resolvedCountDate = resolveCountDate(
      existingSavedCountDate = existingRecord?.countDate,
      userEnteredDate = draft.countDate,
      explicitDateEdit = explicitDateEdit
    )

    val (resolvedCountedBy, resolvedCountedByUid) = resolveCounterAttribution(
      existingCountedBy = existingRecord?.countedBy,
      existingCountedByUserId = existingRecord?.countedByUserId,
      draftCountedBy = draft.countedBy,
      authenticatedFullName = authenticatedCounterFullName,
      authenticatedUserId = authenticatedCounterUserId
    )

    val existingReviewedBy = existingRecord?.reviewedBy?.trim().orEmpty().ifEmpty { draft.reviewedBy.trim() }
    val existingReviewedByUid = existingRecord?.reviewedByUserId?.trim().orEmpty().ifEmpty { draft.reviewedByUserId.trim() }
    val existingReviewedAt = existingRecord?.reviewedAt?.trim().orEmpty().ifEmpty { draft.reviewedAt.trim() }

    val resolvedReviewedBy: String
    val resolvedReviewedByUid: String
    val resolvedReviewedAt: String

    if (markAsReviewed) {
      if (existingReviewedBy.isNotEmpty()) {
        resolvedReviewedBy = existingReviewedBy
        resolvedReviewedByUid = existingReviewedByUid.ifEmpty { authenticatedReviewerUserId?.trim().orEmpty() }
        resolvedReviewedAt = existingReviewedAt.ifBlank { currentIsoTimestamp() }
      } else {
        resolvedReviewedBy = authenticatedReviewerFullName?.trim().orEmpty()
        resolvedReviewedByUid = authenticatedReviewerUserId?.trim().orEmpty()
        resolvedReviewedAt = currentIsoTimestamp()
      }
    } else {
      resolvedReviewedBy = existingReviewedBy
      resolvedReviewedByUid = existingReviewedByUid
      resolvedReviewedAt = existingReviewedAt
    }

    val recordStatus = when {
      draft.isDraft -> VerificationResultStatus.DRAFT
      markAsReviewed && resolvedReviewedBy.isNotEmpty() -> VerificationResultStatus.REVIEWED
      else -> VerificationResultStatus.UNREGISTERED_OBSERVATION
    }

    val classification = if (markAsReviewed && resolvedReviewedBy.isNotEmpty()) {
      "Not in FAR — Reviewed Observation (Pending Accounting & Ownership Decision)"
    } else {
      "Not in FAR — Unregistered Asset Observation (Pending Ownership Review)"
    }

    val parsedQty = qtyValidation.parsedQuantity ?: 0L

    val prepared = draft.copy(
      id = existingRecord?.id?.ifBlank { resolvedTempId } ?: resolvedTempId,
      tempId = resolvedTempId,
      assetIdIfKnown = draft.assetIdIfKnown.trim(),
      description = draft.description.trim(),
      assetClass = draft.assetClass.trim(),
      buildingSite = draft.buildingSite.trim(),
      buildingSiteOther = draft.buildingSiteOther.trim(),
      floor = draft.floor.trim(),
      floorOther = draft.floorOther.trim(),
      roomLocation = draft.roomLocation.trim(),
      roomLocationOther = draft.roomLocationOther.trim(),
      department = draft.department.trim(),
      departmentOther = draft.departmentOther.trim(),
      userCustodian = draft.userCustodian.trim(),
      tagStatus = TagStatusNormalizer.normalize(draft.tagStatus.trim()),
      tagNumberAcn = draft.tagNumberAcn.trim(),
      brandModel = draft.brandModel.trim(),
      physicalQtyText = draft.physicalQtyText.trim(),
      physicalQty = parsedQty,
      found = draft.found.trim(),
      condition = draft.condition.trim(),
      inUse = draft.inUse.trim(),
      countDate = resolvedCountDate,
      countedBy = resolvedCountedBy,
      countedByUserId = resolvedCountedByUid,
      reviewedBy = resolvedReviewedBy,
      reviewedByUserId = resolvedReviewedByUid,
      reviewedAt = resolvedReviewedAt,
      photoRef = draft.photoRef.trim(),
      remarks = formatManualEntryRemarks(draft.remarks),
      clientExplanation = draft.clientExplanation.trim(),
      recordStatus = recordStatus,
      classificationStatus = classification,
      isDraft = draft.isDraft
    )

    return ManualObservationOutcome(
      isValidToSave = errors.isEmpty(),
      validationErrors = errors,
      preparedRecord = prepared
    )
  }

  /**
   * Section 5: Automatically populates Excel Remarks for manual entries with
   * "Not Found in Asset Register" and preserves any additional user remarks.
   */
  fun formatManualEntryRemarks(userRemarks: String?): String {
    val clean = userRemarks?.trim().orEmpty()
    return when {
      clean.isEmpty() -> com.example.data.model.MANUAL_ENTRY_REMARKS
      clean.startsWith(com.example.data.model.MANUAL_ENTRY_REMARKS) -> clean
      clean.contains(com.example.data.model.MANUAL_ENTRY_REMARKS) -> clean
      else -> "${com.example.data.model.MANUAL_ENTRY_REMARKS} — $clean"
    }
  }

  /**
   * Audit Control (Phase 7):
   * Do not count G/L cost and accumulated-depreciation summary rows as separate physical assets.
   */
  fun isNonCountableGlOrDepreciationRow(assetId: String, description: String): Boolean {
    val combined = "${assetId.trim()} ${description.trim()}".lowercase(Locale.US)
    val nonCountablePatterns = listOf(
      "accumulated depreciation",
      "accum. depreciation",
      "acc. dep",
      "g/l cost",
      "gl cost",
      "g/l balance",
      "general ledger total",
      "sub-total",
      "subtotal",
      "grand total",
      "clearing account balance"
    )
    return nonCountablePatterns.any { combined.contains(it) }
  }
}
