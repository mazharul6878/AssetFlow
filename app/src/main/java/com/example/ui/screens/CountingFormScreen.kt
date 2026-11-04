package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.AssetWithCount
import com.example.data.model.AuditDropdownOptions
import com.example.data.model.CountingRecord
import com.example.data.model.MasterDataConfig
import com.example.data.model.UserProfile
import com.example.domain.AuditCalculationEngine
import com.example.domain.TagStatusNormalizer
import com.example.ui.components.AuditDropdownField
import com.example.ui.components.PhotoEvidenceSection
import com.example.ui.components.ResultStatusBadge
import com.example.ui.theme.AuditNavyPrimary
import com.example.ui.theme.AuditNavySecondary
import com.example.ui.theme.StatusExceptionBg
import com.example.ui.theme.StatusExceptionFg
import com.example.ui.theme.StatusVerifiedFg
import com.example.ui.theme.StatusWarningBg
import com.example.ui.theme.StatusWarningFg
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CountingFormScreen(
  assetWithCount: AssetWithCount,
  masterData: MasterDataConfig,
  userProfile: UserProfile,
  onSaveDraft: (draft: CountingRecord, explicitDateEdit: Boolean) -> Unit,
  onSaveVerification: (draft: CountingRecord, explicitDateEdit: Boolean) -> Unit,
  onMarkReviewed: (draft: CountingRecord, explicitDateEdit: Boolean) -> Unit,
  onBack: () -> Unit
) {
  BackHandler(onBack = onBack)

  val far = assetWithCount.farAsset
  val existing = assetWithCount.countingRecord

  val knownSites = remember(masterData) { masterData.siteList() }
  val knownFloors = remember(masterData) { masterData.floorList() }
  val knownDepts = remember(masterData) { masterData.departmentList() }

  var found by remember(existing) { mutableStateOf(existing?.found ?: "") }

  // Count Date: Defaults to current local date for new count, preserves historical saved date on reopen
  var countDate by remember(existing) {
    mutableStateOf(
      AuditCalculationEngine.resolveCountDate(
        existingSavedCountDate = existing?.countDate,
        userEnteredDate = null
      )
    )
  }
  var isEditingCountDate by remember { mutableStateOf(false) }
  var explicitDateEdited by remember { mutableStateOf(false) }

  // Counted By: Automatically populated from authenticated user's profile displayName when created,
  // or retains the historical countedBy recorded on previously completed counting actions.
  val resolvedCounterPair = remember(existing, userProfile) {
    AuditCalculationEngine.resolveCounterAttribution(
      existingCountedBy = existing?.countedBy,
      existingCountedByUserId = existing?.countedByUserId,
      draftCountedBy = existing?.countedBy,
      authenticatedFullName = userProfile.effectiveFullName(),
      authenticatedUserId = userProfile.userId
    )
  }
  val countedBy = resolvedCounterPair.first
  val countedByUserId = resolvedCounterPair.second

  var buildingSite by remember(existing) {
    mutableStateOf(
      when {
        existing == null || existing.buildingSite.isBlank() -> ""
        existing.buildingSite == AuditDropdownOptions.OTHER_OPTION -> AuditDropdownOptions.OTHER_OPTION
        existing.buildingSite in AuditDropdownOptions.SPECIAL_OPTIONAL_STATES -> existing.buildingSite
        knownSites.isNotEmpty() && existing.buildingSite !in knownSites -> AuditDropdownOptions.OTHER_OPTION
        else -> existing.buildingSite
      }
    )
  }
  var buildingSiteOther by remember(existing) {
    mutableStateOf(
      if (existing != null && existing.buildingSiteOther.isNotBlank()) existing.buildingSiteOther
      else if (
        existing != null &&
        knownSites.isNotEmpty() &&
        existing.buildingSite !in knownSites &&
        existing.buildingSite !in AuditDropdownOptions.SPECIAL_OPTIONAL_STATES &&
        existing.buildingSite != AuditDropdownOptions.OTHER_OPTION
      ) existing.buildingSite
      else ""
    )
  }

  var floor by remember(existing) {
    mutableStateOf(
      when {
        existing == null || existing.floor.isBlank() -> ""
        existing.floor == AuditDropdownOptions.OTHER_OPTION -> AuditDropdownOptions.OTHER_OPTION
        existing.floor in AuditDropdownOptions.SPECIAL_OPTIONAL_STATES -> existing.floor
        knownFloors.isNotEmpty() && existing.floor !in knownFloors -> AuditDropdownOptions.OTHER_OPTION
        else -> existing.floor
      }
    )
  }
  var floorOther by remember(existing) {
    mutableStateOf(
      if (existing != null && existing.floorOther.isNotBlank()) existing.floorOther
      else if (
        existing != null &&
        knownFloors.isNotEmpty() &&
        existing.floor !in knownFloors &&
        existing.floor !in AuditDropdownOptions.SPECIAL_OPTIONAL_STATES &&
        existing.floor != AuditDropdownOptions.OTHER_OPTION
      ) existing.floor
      else ""
    )
  }

  val filteredRooms = remember(masterData, buildingSite, floor) {
    masterData.filteredRooms(buildingSite, floor)
  }

  var roomLocation by remember(existing) {
    mutableStateOf(existing?.roomLocation ?: "")
  }
  var roomLocationOther by remember(existing) {
    mutableStateOf(existing?.roomLocationOther ?: "")
  }

  var department by remember(existing) {
    mutableStateOf(
      when {
        existing == null || existing.department.isBlank() -> ""
        existing.department == AuditDropdownOptions.OTHER_OPTION -> AuditDropdownOptions.OTHER_OPTION
        existing.department in AuditDropdownOptions.SPECIAL_OPTIONAL_STATES -> existing.department
        knownDepts.isNotEmpty() && existing.department !in knownDepts -> AuditDropdownOptions.OTHER_OPTION
        else -> existing.department
      }
    )
  }
  var departmentOther by remember(existing) {
    mutableStateOf(
      if (existing != null && existing.departmentOther.isNotBlank()) existing.departmentOther
      else if (
        existing != null &&
        knownDepts.isNotEmpty() &&
        existing.department !in knownDepts &&
        existing.department !in AuditDropdownOptions.SPECIAL_OPTIONAL_STATES &&
        existing.department != AuditDropdownOptions.OTHER_OPTION
      ) existing.department
      else ""
    )
  }

  var userCustodian by remember(existing) { mutableStateOf(existing?.userCustodian ?: "") }
  var tagStatus by remember(existing) { mutableStateOf(TagStatusNormalizer.normalize(existing?.tagStatus ?: "")) }
  var tagNumberAcn by remember(existing) { mutableStateOf(existing?.tagNumberAcn ?: "") }
  var brandModel by remember(existing) { mutableStateOf(existing?.brandModel ?: "") }
  var physicalQtyText by remember(existing) { mutableStateOf(existing?.physicalQtyText ?: "") }
  var condition by remember(existing) { mutableStateOf(existing?.condition ?: "") }
  var inUse by remember(existing) { mutableStateOf(existing?.inUse ?: "") }
  var photoRef by remember(existing) { mutableStateOf(existing?.photoRef ?: "") }
  var remarks by remember(existing) { mutableStateOf(existing?.remarks ?: "") }
  var clientExplanation by remember(existing) { mutableStateOf(existing?.clientExplanation ?: "") }

  var showScannerDialog by remember { mutableStateOf(false) }

  val currentDraft by remember(
    found, countDate, countedBy, countedByUserId,
    buildingSite, buildingSiteOther, floor, floorOther,
    roomLocation, roomLocationOther, department, departmentOther,
    userCustodian, tagStatus, tagNumberAcn, brandModel,
    physicalQtyText, condition, inUse, photoRef, remarks,
    clientExplanation, existing
  ) {
    derivedStateOf {
      CountingRecord(
        id = far.assetKey,
        assetKey = far.assetKey,
        assetId = far.assetId,
        sourceRow = far.sourceRow,
        found = found,
        countDate = countDate,
        countedBy = countedBy,
        countedByUserId = countedByUserId,
        buildingSite = buildingSite,
        buildingSiteOther = buildingSiteOther,
        floor = floor,
        floorOther = floorOther,
        roomLocation = roomLocation,
        roomLocationOther = roomLocationOther,
        department = department,
        departmentOther = departmentOther,
        userCustodian = userCustodian,
        tagStatus = tagStatus,
        tagNumberAcn = tagNumberAcn,
        brandModel = brandModel,
        physicalQtyText = physicalQtyText,
        condition = condition,
        inUse = inUse,
        photoRef = photoRef,
        remarks = remarks,
        reviewedBy = existing?.reviewedBy ?: "",
        reviewedByUserId = existing?.reviewedByUserId ?: "",
        reviewedAt = existing?.reviewedAt ?: "",
        reviewStatus = existing?.reviewStatus ?: "Pending Review",
        clientExplanation = clientExplanation,
        auditTrailSummary = existing?.auditTrailSummary ?: ""
      )
    }
  }

  val liveCalc by remember(currentDraft, far, masterData, userProfile, existing) {
    derivedStateOf {
      AuditCalculationEngine.evaluateRecord(
        farAsset = far,
        record = currentDraft,
        masterData = masterData,
        authenticatedReviewerFullName = userProfile.effectiveFullName(),
        authenticatedReviewerUserId = userProfile.userId,
        authenticatedCounterFullName = userProfile.effectiveFullName(),
        authenticatedCounterUserId = userProfile.userId,
        existingRecord = existing,
        markAsReviewed = false
      )
    }
  }

  val qtyValidation by remember(physicalQtyText) {
    derivedStateOf { AuditCalculationEngine.validatePhysicalQty(physicalQtyText) }
  }

  val canSaveWithoutFatalError = qtyValidation.isValid && liveCalc.validationErrors.isEmpty()

  Column(
    modifier = Modifier
      .fillMaxSize()
      .testTag("counting_form_screen")
  ) {
    // Top Sticky Bar with Back button, Asset ID, Sync status, and Live Result Badge
    Surface(
      color = MaterialTheme.colorScheme.surface,
      shadowElevation = 4.dp
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f)
        ) {
          IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("back_to_list_button")
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Asset List")
          }
          Column {
            Text(
              text = "${far.assetId} (SL #${far.sl} • Row ${far.sourceRow})",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              if (existing != null) {
                Icon(
                  imageVector = if (existing.hasPendingWrites) Icons.Default.CloudUpload else Icons.Default.CloudDone,
                  contentDescription = null,
                  modifier = Modifier.size(14.dp),
                  tint = if (existing.hasPendingWrites) StatusWarningFg else StatusVerifiedFg
                )
                Text(
                  text = if (existing.hasPendingWrites) "Queued for Cloud Sync" else "Synced to Firestore",
                  style = MaterialTheme.typography.labelSmall,
                  color = if (existing.hasPendingWrites) StatusWarningFg else StatusVerifiedFg
                )
              } else {
                Text(
                  text = "New Physical Count Record",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }
        }
        ResultStatusBadge(result = liveCalc.primaryResult)
      }
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // SECTION 1 & 2: Prominent Asset Identity & Read-Only FAR Register Fields (Fields 1..9)
      item {
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .testTag("readonly_far_fields_card"),
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
          )
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
              Text(
                text = "SECTION 1 & 2 — FIXED ASSET REGISTER (READ-ONLY, FIELDS 1–9)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
              )
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primaryContainer
              ) {
                Text(
                  text = "Protected FAR Source",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
              }
            }

            Text(
              text = "2. Asset ID: ${far.assetId}",
              style = MaterialTheme.typography.headlineMedium,
              color = MaterialTheme.colorScheme.primary
            )
            Text(
              text = "3. Asset Description (FAR): ${far.assetDescription}",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface
            )

            HorizontalDivider()

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              ReadOnlyFieldBox("1. SL", far.sl.toString(), Modifier.weight(1f))
              ReadOnlyFieldBox("Source Row Ref", "Row #${far.sourceRow}", Modifier.weight(1f))
              ReadOnlyFieldBox("4. Asset Class", far.assetClass, Modifier.weight(1.5f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              ReadOnlyFieldBox("5. Capitalized On", far.capitalizedOn, Modifier.weight(1f))
              ReadOnlyFieldBox("6. Cost (Tk)", String.format(Locale.US, "%,.2f", far.costTk), Modifier.weight(1f))
              ReadOnlyFieldBox("7. NBV (Tk)", String.format(Locale.US, "%,.2f", far.nbvTk), Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              ReadOnlyFieldBox(
                label = "8. FAR Qty",
                value = if (far.farQtyPresent) far.farQty.toString() else "MISSING (Flagged)",
                modifier = Modifier.weight(1f)
              )
              ReadOnlyFieldBox("9. Method", far.method, Modifier.weight(1.5f))
            }
          }
        }
      }

      // SECTION 3: Physical Verification Inputs (Fields 10, 11, 12, 18, 19, 20, 21, 22, 23)
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
          ) {
            Text(
              text = "SECTION 3 — PHYSICAL VERIFICATION INPUTS",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary
            )

            AuditDropdownField(
              label = "10. Found? (Lists Dropdown)",
              selectedValue = found,
              options = AuditDropdownOptions.FOUND_OPTIONS,
              onValueSelected = { found = it },
              testTag = "field_found_dropdown"
            )

            // 11. Count Date (Auto — Preserved on reopen, explicit edit option)
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              OutlinedTextField(
                value = countDate,
                onValueChange = {
                  if (isEditingCountDate) {
                    countDate = it
                    explicitDateEdited = true
                  }
                },
                readOnly = !isEditingCountDate,
                label = { Text("11. Count Date (Auto — Historical Date Preserved)") },
                supportingText = {
                  Text(
                    if (explicitDateEdited) "Explicit date change will be recorded in audit history."
                    else if (existing != null) "Historical Count Date preserved from initial record."
                    else "Auto-populated with current local date."
                  )
                },
                singleLine = true,
                modifier = Modifier
                  .weight(1f)
                  .testTag("field_count_date")
              )
              OutlinedButton(
                onClick = { isEditingCountDate = !isEditingCountDate },
                modifier = Modifier.testTag("edit_count_date_button")
              ) {
                Icon(Icons.Default.EditCalendar, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isEditingCountDate) "Lock" else "Edit Date")
              }
            }

            // 12. Counted By (Automatically populated from authenticated user's profile displayName)
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("field_counted_by_auto")
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.weight(1f)
                ) {
                  Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Column {
                    Text(
                      text = "12. Counted By (Auto from Authenticated User Profile)",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                      text = countedBy,
                      style = MaterialTheme.typography.bodyLarge,
                      fontWeight = FontWeight.Bold
                    )
                    Text(
                      text = if (existing?.countedBy?.isNotBlank() == true)
                        "Historical counter name preserved (Account ID: $countedByUserId)"
                      else
                        "Associated with Account ID: $countedByUserId",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                }
              }
            }

            // 18. Tag Status (Dropdown)
            AuditDropdownField(
              label = "18. Tag Status (Lists Dropdown)",
              selectedValue = tagStatus,
              options = AuditDropdownOptions.TAG_STATUS_OPTIONS,
              onValueSelected = { tagStatus = it },
              includeSpecialOptionalStates = true,
              testTag = "field_tag_status_dropdown"
            )

            // 19. Tag Number (ACN) — Preserves leading zeros & alphanumeric + Barcode Scanner
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              OutlinedTextField(
                value = tagNumberAcn,
                onValueChange = { tagNumberAcn = it },
                label = { Text("19. Tag Number (ACN)") },
                supportingText = { Text("Preserves leading zeros and alphanumeric characters") },
                singleLine = true,
                modifier = Modifier
                  .weight(1f)
                  .testTag("field_tag_number_input")
              )
              OutlinedButton(
                onClick = { showScannerDialog = true },
                modifier = Modifier
                  .height(56.dp)
                  .testTag("scan_barcode_button"),
                shape = RoundedCornerShape(12.dp)
              ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan Tag Barcode/QR")
                Spacer(modifier = Modifier.width(6.dp))
                Text("Scan")
              }
            }

            // 20. Brand / Model
            OutlinedTextField(
              value = brandModel,
              onValueChange = { brandModel = it },
              label = { Text("20. Brand / Model (Optional)") },
              singleLine = true,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("field_brand_model_input")
            )

            // 21. Physical Qty (Whole numbers >= 0 only; blank != 0)
            OutlinedTextField(
              value = physicalQtyText,
              onValueChange = { physicalQtyText = it },
              label = { Text("21. Physical Qty (Non-negative whole number; FAR Qty = ${far.farQty})") },
              isError = !qtyValidation.isValid,
              supportingText = {
                if (!qtyValidation.isValid && qtyValidation.errorMessage != null) {
                  Text(qtyValidation.errorMessage!!, color = MaterialTheme.colorScheme.error)
                } else {
                  Text("Enter 0, 1, 2... Leave blank if not yet counted. Blank is never treated as zero.")
                }
              },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("field_physical_qty_input")
            )

            // 22. Condition (Dropdown)
            AuditDropdownField(
              label = "22. Condition (Lists Dropdown)",
              selectedValue = condition,
              options = AuditDropdownOptions.CONDITION_OPTIONS,
              onValueSelected = { condition = it },
              includeSpecialOptionalStates = true,
              testTag = "field_condition_dropdown"
            )

            // 23. In Use? (Dropdown)
            AuditDropdownField(
              label = "23. In Use? (Lists Dropdown)",
              selectedValue = inUse,
              options = AuditDropdownOptions.IN_USE_OPTIONS,
              onValueSelected = { inUse = it },
              includeSpecialOptionalStates = true,
              testTag = "field_in_use_dropdown"
            )
          }
        }
      }

      // SECTION 4: Location & Custodian Information (Fields 13, 14, 15, 16, 17)
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
          ) {
            Text(
              text = "SECTION 4 — LOCATION & CUSTODIAN (DROPDOWN + OTHER / BLANK / N/A / UNKNOWN)",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary
            )

            AuditDropdownField(
              label = "13. Building / Site",
              selectedValue = buildingSite,
              options = masterData.siteList(),
              onValueSelected = { buildingSite = it },
              supportOther = true,
              otherValue = buildingSiteOther,
              onOtherValueChange = { buildingSiteOther = it },
              includeSpecialOptionalStates = true,
              searchable = true,
              testTag = "field_building_site_dropdown"
            )

            AuditDropdownField(
              label = "14. Floor",
              selectedValue = floor,
              options = masterData.floorList(),
              onValueSelected = { floor = it },
              supportOther = true,
              otherValue = floorOther,
              onOtherValueChange = { floorOther = it },
              includeSpecialOptionalStates = true,
              searchable = true,
              testTag = "field_floor_dropdown"
            )

            AuditDropdownField(
              label = "15. Room / Location (Filtered by Site & Floor)",
              selectedValue = roomLocation,
              options = filteredRooms,
              onValueSelected = { roomLocation = it },
              supportOther = true,
              otherValue = roomLocationOther,
              onOtherValueChange = { roomLocationOther = it },
              includeSpecialOptionalStates = true,
              searchable = true,
              testTag = "field_room_location_dropdown"
            )

            AuditDropdownField(
              label = "16. Department",
              selectedValue = department,
              options = masterData.departmentList(),
              onValueSelected = { department = it },
              supportOther = true,
              otherValue = departmentOther,
              onOtherValueChange = { departmentOther = it },
              includeSpecialOptionalStates = true,
              searchable = true,
              testTag = "field_department_dropdown"
            )

            OutlinedTextField(
              value = userCustodian,
              onValueChange = { userCustodian = it },
              label = { Text("17. User / Custodian (Optional Free-Text)") },
              singleLine = true,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("field_user_custodian_input")
            )
          }
        }
      }

      // SECTION 5: Auto-Calculated Quantity Difference, Separate Counting/Review/Result States & Exceptions (Fields 26, 27, 28)
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Text(
              text = "SECTION 5 — WORKFLOW STATES, QTY DIFF, RESULT & EXCEPTIONS (FIELDS 26–28)",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              ReadOnlyFieldBox(
                label = "26. Qty Diff (Phys - FAR)",
                value = liveCalc.qtyDiffText.ifBlank { "Blank (Awaiting Qty)" },
                modifier = Modifier.weight(1f)
              )
              ReadOnlyFieldBox(
                label = "Counting State",
                value = if (existing?.isDraft == true) "Draft" else liveCalc.countingStatus,
                modifier = Modifier.weight(1f)
              )
              ReadOnlyFieldBox(
                label = "Review State",
                value = liveCalc.reviewStatus,
                modifier = Modifier.weight(1f)
              )
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(
                text = "27. Verification Result (9-Step Priority):",
                style = MaterialTheme.typography.labelLarge
              )
              ResultStatusBadge(result = liveCalc.primaryResult)
            }

            if (liveCalc.applicableExceptions.isNotEmpty()) {
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = StatusExceptionBg,
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(
                  modifier = Modifier.padding(12.dp),
                  verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                  Text(
                    text = "All Applicable Exceptions Preserved (${liveCalc.applicableExceptions.size}):",
                    style = MaterialTheme.typography.labelLarge,
                    color = StatusExceptionFg,
                    fontWeight = FontWeight.Bold
                  )
                  FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                  ) {
                    liveCalc.applicableExceptions.forEach { ex ->
                      Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.White.copy(alpha = 0.85f)
                      ) {
                        Text(
                          text = "• $ex",
                          style = MaterialTheme.typography.labelSmall,
                          color = StatusExceptionFg,
                          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                      }
                    }
                  }
                }
              }
            }

            if (liveCalc.validationErrors.isNotEmpty()) {
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = StatusWarningBg,
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(
                  modifier = Modifier.padding(12.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                      Icons.Default.Warning,
                      contentDescription = null,
                      tint = StatusWarningFg,
                      modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                      text = "Validation Checks Requiring Attention (Unsaved Data Preserved):",
                      style = MaterialTheme.typography.labelLarge,
                      color = StatusWarningFg,
                      fontWeight = FontWeight.Bold
                    )
                  }
                  liveCalc.validationErrors.forEach { err ->
                    Text(
                      text = "• $err",
                      style = MaterialTheme.typography.bodyMedium,
                      color = StatusWarningFg
                    )
                  }
                }
              }
            }

            ReadOnlyFieldBox(
              label = "28. Reviewed By (Populated Only Upon Explicit Review Action)",
              value = when {
                existing?.reviewedBy?.isNotBlank() == true ->
                  "${existing.reviewedBy} (UID: ${existing.reviewedByUserId.ifBlank { "Recorded" }} • Reviewed at ${existing.reviewedAt})"
                else ->
                  "Not Yet Reviewed (Pending Review — Will record '${userProfile.effectiveDisplayName()}' when reviewed)"
              },
              modifier = Modifier
                .fillMaxWidth()
                .testTag("field_reviewed_by_box")
            )
          }
        }
      }

      // SECTION 6: Camera Photo Evidence, Remarks & Follow-up (Fields 24, 25, 29)
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
          ) {
            Text(
              text = "SECTION 6 — CAMERA PHOTO EVIDENCE, REMARKS & FOLLOW-UP (FIELDS 24, 25, 29)",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary
            )

            PhotoEvidenceSection(
              recordKey = far.assetKey,
              photoRef = photoRef,
              userProfile = userProfile,
              onPhotoRefChange = { photoRef = it }
            )

            OutlinedTextField(
              value = remarks,
              onValueChange = { remarks = it },
              label = { Text("25. Remarks (Auditor Physical Observation — Optional)") },
              minLines = 3,
              maxLines = 5,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("field_remarks_input")
            )

            OutlinedTextField(
              value = clientExplanation,
              onValueChange = { clientExplanation = it },
              label = { Text("29. Client Explanation / Follow-up (Optional)") },
              minLines = 3,
              maxLines = 5,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("field_client_explanation_input")
            )
          }
        }
      }

      // SECTION 7: Save Draft, Save Count & Explicit Reviewer Sign-Off + Audit Trail
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Text(
              text = "SECTION 7 — SAVE DRAFT, SAVE COUNT & EXPLICIT REVIEWER SIGN-OFF",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              OutlinedButton(
                onClick = {
                  if (qtyValidation.isValid) {
                    onSaveDraft(currentDraft.copy(isDraft = true), explicitDateEdited)
                  }
                },
                enabled = qtyValidation.isValid,
                modifier = Modifier
                  .weight(1f)
                  .height(50.dp)
                  .testTag("save_draft_button"),
                shape = RoundedCornerShape(12.dp)
              ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save Draft")
              }

              Button(
                onClick = {
                  if (canSaveWithoutFatalError) {
                    onSaveVerification(currentDraft.copy(isDraft = false), explicitDateEdited)
                  }
                },
                enabled = canSaveWithoutFatalError,
                modifier = Modifier
                  .weight(1.3f)
                  .height(50.dp)
                  .testTag("save_verification_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AuditNavyPrimary)
              ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save Count")
              }
            }

            Button(
              onClick = {
                if (canSaveWithoutFatalError) {
                  onMarkReviewed(currentDraft.copy(isDraft = false), explicitDateEdited)
                }
              },
              enabled = canSaveWithoutFatalError,
              modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("mark_reviewed_button"),
              shape = RoundedCornerShape(12.dp),
              colors = ButtonDefaults.buttonColors(containerColor = AuditNavySecondary)
            ) {
              Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text("Perform Review & Sign Off as ${userProfile.effectiveDisplayName()}")
            }

            if (!existing?.auditTrailSummary.isNullOrBlank()) {
              HorizontalDivider()
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  Icons.Default.History,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = "Audit Trail History",
                  style = MaterialTheme.typography.labelLarge
                )
              }
              Text(
                text = existing!!.auditTrailSummary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }
      }
    }
  }

  if (showScannerDialog) {
    var scannedCode by remember { mutableStateOf(tagNumberAcn.ifBlank { "000${far.sl}98A" }) }
    AlertDialog(
      onDismissRequest = { showScannerDialog = false },
      title = { Text("Barcode / QR Tag Scanner (Field 19)") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            "Scan or verify the physical asset tag barcode/QR code. Leading zeros and alphanumeric ACN prefixes are strictly preserved.",
            style = MaterialTheme.typography.bodyMedium
          )
          OutlinedTextField(
            value = scannedCode,
            onValueChange = { scannedCode = it },
            label = { Text("Decoded ACN / Barcode String") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            tagNumberAcn = scannedCode
            if (tagStatus.isBlank()) tagStatus = TagStatusNormalizer.CANONICAL_POSITIVE_TAG
            showScannerDialog = false
          }
        ) {
          Text("Apply Scanned Tag")
        }
      },
      dismissButton = {
        TextButton(onClick = { showScannerDialog = false }) { Text("Cancel") }
      }
    )
  }
}

@Composable
private fun ReadOnlyFieldBox(
  label: String,
  value: String,
  modifier: Modifier = Modifier
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.surface
  ) {
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = value.ifBlank { "—" },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
      )
    }
  }
}
